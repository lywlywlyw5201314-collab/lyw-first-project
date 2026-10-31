package com.example.notifyledger.judge

import android.content.Context
import com.example.notifyledger.model.Category
import com.example.notifyledger.model.LedgerEntry
import com.example.notifyledger.util.TimeUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.round

/**
 * AI 判断器(方向 B 判断层的首选): 调用 OpenAI 兼容的 /chat/completions 端点,
 * 由模型判断「这条通知文本是不是支出支付」, 是则直接给出 金额/商户/时间/分类。
 *
 * 零新增依赖: HttpURLConnection + Android 内置 org.json。
 * 安全契约: 未配置(默认就是未配置) / 网络或模型失败 / 响应无法解析 → 一律 [Verdict.Abstain],
 * 由链上的 [RuleJudge] 兜底 —— AI 挂了绝不影响记账, 也绝不把异常抛进服务层。
 *
 * 扩展点: 同一个 LlmConfig 也可指向「截图 OCR / 视觉模型」端点(方向 A 的截图路径),
 * 把识别出的文本喂给同一条判断链。
 */
class LlmJudge(private val config: () -> LlmConfig = { LlmConfig() }) : Judge {

    override fun name(): String = "llm"

    override fun judge(text: String, source: String?): Verdict {
        val cfg = config()
        if (!cfg.isEnabled || cfg.endpoint.isBlank() || cfg.apiKey.isBlank()) {
            return Verdict.Abstain // 未配置 → 交给规则层
        }
        val content: String
        try {
            content = callLlm(cfg, text)
        } catch (e: Exception) {
            return Verdict.Abstain // 网络 / 模型失败 → 交给规则层, 不阻塞
        }
        return interpretResponse(content, text, source)
    }

    /**
     * 把模型原始输出解析为判定(公开以便单元测试)。
     * 容错: 取第一个完整 {…}(模型可能附带解释文字或代码块); 金额不可信则弃权。
     */
    fun interpretResponse(modelOutput: String, text: String, source: String?): Verdict {
        val jsonStr = extractJson(modelOutput) ?: return Verdict.Abstain
        val jo: JSONObject
        try {
            jo = JSONObject(jsonStr)
        } catch (e: Exception) {
            return Verdict.Abstain
        }
        if (!jo.optBoolean("keep", false)) {
            val reason = jo.optString("reason", "").trim().ifEmpty { DISCARD_DEFAULT_REASON }
            return Verdict.Discard(reason, text, "llm")
        }
        val amount = jo.optDouble("amount", Double.NaN)
        if (amount.isNaN() || amount <= 0.0) return Verdict.Abstain // 金额不可信 → 不冒然入账
        return Verdict.Keep(
            LedgerEntry(
                day = TimeUtil.todayDay(),
                source = source,
                amount = round(amount * 100.0) / 100.0,
                merchant = jo.optString("merchant", "").trim().ifEmpty { null },
                category = Category.fromLabel(jo.optString("category", "其他")).label,
                raw = text
            ),
            "llm"
        )
    }

    private fun callLlm(cfg: LlmConfig, text: String): String {
        // 单次重试 + 短退避: 网络抖动/读超时只重连一次; 仍失败则抛 IOException,
        // 由 judge() 捕获转 Verdict.Abstain, 交规则层兜底。(429/503 限流退避暂不做)
        var lastError: IOException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return requestLlm(cfg, text)
            } catch (e: IOException) {
                lastError = e
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_MS)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            }
        }
        throw lastError ?: IOException("LLM 请求失败")
    }

    private fun requestLlm(cfg: LlmConfig, text: String): String {
        val body = JSONObject()
            .put("model", cfg.model)
            .put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                put(JSONObject().put("role", "user").put("content", "通知文本: $text"))
            })
            .toString()
        val conn = URL(cfg.endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val resp = conn.inputStream.bufferedReader().readText()
            return JSONObject(resp)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        } finally {
            conn.disconnect()
        }
    }

    /** 模型可能在 JSON 前后附带文字/代码块, 取第一个完整 {…} */
    private fun extractJson(s: String): String? {
        val i = s.indexOf('{')
        val j = s.lastIndexOf('}')
        if (i < 0 || j <= i) return null
        return s.substring(i, j + 1)
    }

    private companion object {
        const val MAX_ATTEMPTS = 2 // 首次 + 重试一次
        const val RETRY_BACKOFF_MS = 1500L

        val DISCARD_DEFAULT_REASON = "AI 判定不是支出支付"

        val SYSTEM_PROMPT = """
            你是"支付通知识别"引擎。判断用户给出的手机通知文本是否代表一次真实的"支出"支付(消费/扣款/缴费)。
            只输出一个 JSON 对象, 不要任何其它文字, 不要用代码块包裹:
            如果是支出支付: {"keep":true,"category":"餐饮|购物|交通|生活|其他","amount":实付金额数字,"merchant":"商户名,没有则空串","time":"通知中的时间,没有则空串"}
            如果不是(转账/退款/到账/营销/系统消息等): {"keep":false,"reason":"一句话原因"}
        """.trimIndent()
    }
}

/**
 * AI 判断器配置, 存放于 SharedPreferences([LlmConfig.PREFS])。
 * 默认全部关闭 —— 未配置时 [LlmJudge] 直接弃权, 判断链由 [RuleJudge] 兜底, App 开箱即用。
 * 配置入口: 主界面「AI 设置」区块(打开时回显已存值, [MainActivity.saveLlmConfig] 写入)。
 */
data class LlmConfig(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = LlmConfig.DEFAULT_MODEL
) {
    val isEnabled: Boolean
        get() = enabled && endpoint.isNotBlank() && apiKey.isNotBlank()

    companion object {
        const val PREFS = "notifyledger_llm"
        const val DEFAULT_MODEL = "gpt-4o-mini"

        /** 从应用 SharedPreferences 读取配置(不存在则返回全默认=未启用) */
        fun load(context: Context): LlmConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return LlmConfig(
                enabled = p.getBoolean("enabled", false),
                endpoint = p.getString("endpoint", "").orEmpty().trim(),
                apiKey = p.getString("apiKey", "").orEmpty().trim(),
                model = p.getString("model", "").orEmpty().trim().ifEmpty { DEFAULT_MODEL }
            )
        }
    }
}
