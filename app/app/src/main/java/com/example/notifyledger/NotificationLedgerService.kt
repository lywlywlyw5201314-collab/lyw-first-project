package com.example.notifyledger

import android.content.Context
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.notifyledger.data.LedgerRepository
import com.example.notifyledger.judge.JudgeChain
import com.example.notifyledger.judge.JudgeProvider
import com.example.notifyledger.judge.Verdict
import com.example.notifyledger.parser.PayNotifyParser
import com.example.notifyledger.model.ParseResult
import com.example.notifyledger.util.TimeUtil
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.roundToLong

/**
 * 方向 B 的服务端: 监听系统通知(支付 App 的弹窗)。
 *
 * 流水线(与流程图 / web 原型一致):
 *   通知发布 → 合并 标题+内容
 *   → [关键词入口门] PayNotifyParser.isPaymentRelated:
 *        未命中(天气/短信/群消息/系统…) → 直接忽略, 不做任何记录
 *   → 判断链 JudgeChain( 忽略规则 → RuleJudge 本地规则学习 ):
 *        Keep    → 写入本地账本(LedgerRepository)
 *        Discard → 「其他信息」直接放弃, 不写库, 仅日志留痕
 *
 * 线程模型: 通知回调本身要快; 判断链在单线程执行器上执行,
 * 永不阻塞 NotificationListenerService 主线程。
 */
class NotificationLedgerService : NotificationListenerService() {

    private val repo: LedgerRepository by lazy { LedgerRepository(applicationContext) }

    /** 判断链: 延迟构建(首用), 避免服务启动即读 SharedPreferences/数据库 */
    private val chain: JudgeChain by lazy { JudgeProvider.chain(applicationContext) }

    /** 判断执行器: 单线程、daemon、命名 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "notify-ledger-judge").apply { isDaemon = true }
    }

    /** 去重窗口: 同 key 5 分钟内不重复入账 */
    private val recentKeys = LinkedHashMap<String, Long>()
    private val dedupLock = Any()

    /** 去重键持久化: 服务进程被杀重启后窗口仍有效, 防止同一笔支付被重复入账 */
    private val dedupPrefs: SharedPreferences by lazy {
        getSharedPreferences(DEDUP_PREFS, Context.MODE_PRIVATE)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Log.d(TAG, "onNotificationPosted pkg=" + sbn.packageName)
        val extras = sbn.notification?.extras ?: run { Log.d(TAG, "extras 空, 忽略"); return }
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        val text = listOf(title, body).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (text.isEmpty()) return

        // [关键词入口门] 只有带支付关键词的弹窗才进判断层, 其他信息在门前就被忽略
        if (!PayNotifyParser.isPaymentRelated(text)) {
            Log.d(TAG, "入口门未命中(非支付), 忽略; 原文按隐私约定不落日志")
            return
        }
        Log.d(TAG, "入口门命中, 进判断层; 原文按隐私约定不落日志")

        // 同一笔支付的通知可能刷新/重发多次 → 时间窗去重, 防止重复入账
        // §3.3 去重键不再依赖系统 locale: 旧键用 String.format 按系统格式生成小数(中文/德语等
        // locale 下小数点不同, 同一笔支付会生成不同的键, 5 分钟去重窗口就失效了)。
        // 现在成功解析的键 = 整数分 + 商户 + 当天日期(全部与 locale 无关); 解析失败用原文 MD5 摘要。
        val parsed = PayNotifyParser.parse(text)
        val dedupKey = if (parsed is ParseResult.Success) {
            "P|${(parsed.amount * 100).roundToLong()}|${parsed.merchant ?: ""}|${TimeUtil.todayDay()}"
        } else {
            "F|" + md5Hex(text)
        }
        if (isDuplicate(dedupKey)) {
            Log.d(TAG, "去重命中(键摘要=${md5Hex(dedupKey).take(12)}), 跳过; 按隐私约定只记键的摘要, 原文与键本身都不落日志")
            return
        }

        executor.execute {
            val source = PayNotifyParser.detectSource(text)
            val ignoreRules = repo.learnedRules().filter { it.merchant.startsWith("@ignore:") }.map { it.merchant.removePrefix("@ignore:") }
            when (val v = chain.judge(text, source, ignoreRules)) {
                is Verdict.Keep -> {
                    repo.insertEntry(v.entry)
                    Log.i(TAG, "入账 [${v.via}] ${v.entry.amount} ${v.entry.merchant ?: "-"} → ${v.entry.category}")
                }
                is Verdict.Discard -> {
                    Log.i(TAG, "放弃 [${v.via}] ${v.reason} (原文按隐私约定不落日志)")
                }
                Verdict.Abstain -> Unit
            }
        }
    }

    override fun onListenerConnected() {
        Log.i(TAG, "通知监听已连接, 方向 B 自动记账生效")
        restoreDedupKeys()
    }

    private fun isDuplicate(key: String): Boolean {
        synchronized(dedupLock) {
            val now = System.currentTimeMillis()
            val stale = recentKeys.filterValues { it < now - DEDUP_WINDOW_MS }
            stale.keys.forEach { recentKeys.remove(it) }
            if (recentKeys.containsKey(key)) return true
            recentKeys[key] = now
            capDedupKeys()
            persistDedupKeys()
            return false
        }
    }

    /** 启动时从 SharedPreferences 恢复仍在 5 分钟窗口内的去重键 */
    private fun restoreDedupKeys() {
        synchronized(dedupLock) {
            val now = System.currentTimeMillis()
            val all = dedupPrefs.all
            for ((k, v) in all) {
                val t = (v as? Long) ?: continue
                if (t >= now - DEDUP_WINDOW_MS) recentKeys[k] = t
            }
            capDedupKeys()
            persistDedupKeys()
        }
    }

    /** 把当前窗口内的去重键写回 SharedPreferences(调用方须持有 dedupLock) */
    private fun persistDedupKeys() {
        dedupPrefs.edit()
            .clear()
            .also { e -> recentKeys.forEach { (k, v) -> e.putLong(k, v) } }
            .apply()
    }

    /** 超出上限时按时间戳从旧到新丢弃, 保持持久化条数有界(调用方须持有 dedupLock) */
    private fun capDedupKeys() {
        if (recentKeys.size <= MAX_DEDUP_KEYS) return
        val drop = recentKeys.toList().sortedBy { it.second }.take(recentKeys.size - MAX_DEDUP_KEYS)
        drop.forEach { recentKeys.remove(it.first) }
    }

    /** 算字符串的 MD5 摘要, 输出小写十六进制(固定 UTF-8, 与系统 locale 无关), 用于去重键的"指纹"日志 */
    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> b.toUByte().toString(16).padStart(2, '0') }

    companion object {
        private const val TAG = "NotifyLedger"
        private const val DEDUP_WINDOW_MS = 5L * 60 * 1000 // 5 分钟
        private const val DEDUP_PREFS = "notifyledger_dedup"
        private const val MAX_DEDUP_KEYS = 100 // 持久化上限, 超出按时间戳丢弃最旧
    }
}