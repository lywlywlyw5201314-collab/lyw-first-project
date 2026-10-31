package com.example.notifyledger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.notifyledger.data.LedgerRepository
import com.example.notifyledger.model.LedgerEntry
import com.example.notifyledger.ocr.OcrHelper
import com.example.notifyledger.util.TimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 测试后门广播接收器(仅用于模拟器首版验证, 正式发版应删除):
 * 绕过 scoped storage 限制, 用 am broadcast 驱动三大功能。中文参数走 base64 (text_b64) 避免 shell 编码截断。
 *  - TEST_PASTE       --es text_b64 <base64中文文本>: 预填粘贴框并打开 MainActivity
 *  - TEST_OCR         --es png <base64图片>: 写 app 私有目录, 走 OcrHelper 识别入账
 *  - TEST_PAYMENT_NOTIFICATION --es text_b64 <base64正文> [--es title_b64 <base64标题>]: 发一条真实支付通知,
 *      依赖已授权的 NotificationLedgerService 监听 → 自动入账(方向B完整链路)

 *  - TEST_NONPAY_NOTIFICATION  发一条非支付通知, 用于验证"其余信息直接丢弃"逻辑
 */
class TestCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_TEST_PASTE -> {
                val textB64 = intent?.extras?.getString(EXTRA_TEXT_B64)

                val text = if (textB64 != null) {
                    String(Base64.decode(textB64, Base64.DEFAULT), Charsets.UTF_8)
                } else (intent?.extras?.getString(EXTRA_TEXT) ?: "")
                Log.i(TAG, "TEST_PASTE 收到文本=" + text)

                val launch = Intent(context, MainActivity::class.java).apply {
                    putExtra(EXTRA_TEST_PASTE_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(launch)
            }
            ACTION_TEST_OCR -> {
                val b64 = intent?.extras?.getString(EXTRA_PNG).orEmpty()
                if (b64.isEmpty()) { writeNote(context, "ocr_result", "测试OCR: 未收到png base64"); return }
                try {
                    val dir = context.getExternalFilesDir(null) ?: context.filesDir
                    val f = File(dir, "notifyledger_ocr_test.png")
                    f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val repo = LedgerRepository(context)
                            val rules = repo.learnedRules()
                            val uri = Uri.fromFile(f)
                            val results = OcrHelper.recognizeAndJudge(context, uri, rules)
                            var n = 0
                            if (results == null) {
                                // OCR 引擎不可用(缺 GMS), 记录提示
                                context.getSharedPreferences("test_prefs", android.content.Context.MODE_PRIVATE).edit().putString("ocr_result","OCR 引擎不可用(模拟器缺 GMS)").apply()
                            } else {
                            results.forEach { v ->
                                val entry = LedgerEntry(
                                    day = TimeUtil.todayDay(),
                                    source = v.source,
                                    amount = v.amount ?: 0.0,
                                    merchant = v.merchant,
                                    category = v.category ?: "其他",
                                    raw = "截图OCR: " + (v.raw ?: "")
                                )
                                repo.insertEntry(entry)
                                n = n + 1
                            }
                            val note = if (n == 0) "测试OCR: 截图未识别到可入账支付信息(OCR引擎不可用或非支付截图)" else "测试OCR 入账 " + n + " 笔"
                            writeNote(context, "ocr_result", note)
                            Log.i(TAG, "TEST_OCR 完成: " + note)
                            }
                        } catch (err: Exception) {
                            writeNote(context, "ocr_result", "测试OCR 异常: " + err.message)
                            Log.e(TAG, "TEST_OCR 异常", err)
                        }
                    }
                } catch (err: Exception) {
                    writeNote(context, "ocr_result", "测试OCR 文件写入失败: " + err.message)
                    Log.e(TAG, "TEST_OCR 写入失败", err)
                }
            }
            ACTION_TEST_PAYMENT_NOTIFICATION -> {
                val titleB64 = intent?.extras?.getString(EXTRA_TITLE_B64)

                val title = if (titleB64 != null) String(Base64.decode(titleB64, Base64.DEFAULT), Charsets.UTF_8) else "微信支付"
                val textB64 = intent?.extras?.getString(EXTRA_TEXT_B64)

                val text = if (textB64 != null) String(Base64.decode(textB64, Base64.DEFAULT), Charsets.UTF_8) else "支付成功 ¥23.50 XX便利店 12:47"
                TestHelper.postTestNotification(context, title, text)
                writeNote(context, "notification_sent", "已发一条支付通知: " + title + " " + text + ", 等 NotificationLedgerService 自动入账")
                Log.i(TAG, "TEST_PAYMENT_NOTIFICATION 已发: " + title + " | " + text)
            }
            ACTION_TEST_NONPAY -> {
                TestHelper.postTestNotification(context, "天气", "明天多云转晴, 最高 18 度")
                writeNote(context, "nonpay_sent", "已发一条非支付通知, 5分钟内应未新增入账")
                Log.i(TAG, "TEST_NONPAY 已发")
            }
            else -> Log.w(TAG, "未知 action: " + intent?.action)
        }
    }

    private fun writeNote(context: Context, key: String, msg: String) {
        context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE).edit().putString(key, msg).apply()
        Log.i(TAG, "note[" + key + "] = " + msg)
    }

    companion object {
        private const val TAG = "NotifyLedgerTest"
        const val ACTION_TEST_PASTE = "com.example.notifyledger.TEST_PASTE"
        const val ACTION_TEST_OCR = "com.example.notifyledger.TEST_OCR"
        const val ACTION_TEST_PAYMENT_NOTIFICATION = "com.example.notifyledger.TEST_PAYMENT_NOTIFICATION"
        const val ACTION_TEST_NONPAY = "com.example.notifyledger.TEST_NONPAY"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TEXT_B64 = "text_b64"
        const val EXTRA_TITLE_B64 = "title_b64"
        const val EXTRA_PNG = "png"
        const val EXTRA_TEST_PASTE_TEXT = "test_paste_text"
    }
}