package com.example.notifyledger

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.notifyledger.data.LedgerRepository
import com.example.notifyledger.judge.JudgeChain
import com.example.notifyledger.judge.JudgeProvider
import com.example.notifyledger.judge.Verdict
import com.example.notifyledger.parser.PayNotifyParser
import com.example.notifyledger.model.ParseResult
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 方向 B 的服务端: 监听系统通知(支付 App 的弹窗)。
 *
 * 流水线(与流程图 / web 原型一致):
 *   通知发布 → 合并 标题+内容
 *   → [关键词入口门] PayNotifyParser.isPaymentRelated:
 *        未命中(天气/短信/群消息/系统…) → 直接忽略, 不做任何记录
 *   → 判断链 JudgeChain( LlmJudge → RuleJudge ):
 *        Keep    → 写入本地账本(LedgerRepository)
 *        Discard → 「其他信息」直接放弃, 不写库, 仅日志留痕
 *
 * 线程模型: 通知回调本身要快; 判断链(可能含 AI 网络调用)在单线程执行器上执行,
 * 永不阻塞 NotificationListenerService 主线程。
 */
class NotificationLedgerService : NotificationListenerService() {

    private val repo: LedgerRepository by lazy { LedgerRepository(applicationContext) }

    /** 判断链: 延迟构建(首用), 避免服务启动即读 SharedPreferences/数据库 */
    private val chain: JudgeChain by lazy { JudgeProvider.chain(applicationContext) }

    /** 判断执行器: 单线程、daemon、命名 —— AI 网络调用隔离在此 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "notify-ledger-judge").apply { isDaemon = true }
    }

    /** 去重窗口: 同 key 5 分钟内不重复入账 */
    private val recentKeys = LinkedHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Log.d(TAG, "onNotificationPosted pkg=" + sbn.packageName)
        val extras = sbn.notification?.extras ?: run { Log.d(TAG, "extras 空, 忽略"); return }
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        val text = listOf(title, body).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (text.isEmpty()) return

        // [关键词入口门] 只有带支付关键词的弹窗才进判断层, 其他信息在门前就被忽略
        if (!PayNotifyParser.isPaymentRelated(text)) {
            Log.d(TAG, "入口门未命中(非支付), 忽略: " + text)
            return
        }
        Log.d(TAG, "入口门命中, 进判断层: " + text)

        // 同一笔支付的通知可能刷新/重发多次 → 时间窗去重, 防止重复入账
        val parsed = PayNotifyParser.parse(text)
        val dedupKey = if (parsed is ParseResult.Success) {
            "$text|${String.format(Locale.getDefault(), "%.2f", parsed.amount)}"
        } else text
        if (isDuplicate(dedupKey)) return

        executor.execute {
            val source = PayNotifyParser.detectSource(text)
            when (val v = chain.judge(text, source)) {
                is Verdict.Keep -> {
                    repo.insertEntry(v.entry)
                    Log.i(TAG, "入账 [${v.via}] ${v.entry.amount} ${v.entry.merchant ?: "-"} → ${v.entry.category}")
                }
                is Verdict.Discard -> {
                    Log.i(TAG, "放弃 [${v.via}] ${v.reason} | 原文: ${v.raw}")
                }
                Verdict.Abstain -> Unit
            }
        }
    }

    override fun onListenerConnected() {
        Log.i(TAG, "通知监听已连接, 方向 B 自动记账生效")
    }

    private fun isDuplicate(key: String): Boolean {
        synchronized(recentKeys) {
            val now = System.currentTimeMillis()
            val stale = recentKeys.filterValues { it < now - DEDUP_WINDOW_MS }
            stale.keys.forEach { recentKeys.remove(it) }
            if (recentKeys.containsKey(key)) return true
            recentKeys[key] = now
            return false
        }
    }

    companion object {
        private const val TAG = "NotifyLedger"
        private const val DEDUP_WINDOW_MS = 5L * 60 * 1000 // 5 分钟
    }
}