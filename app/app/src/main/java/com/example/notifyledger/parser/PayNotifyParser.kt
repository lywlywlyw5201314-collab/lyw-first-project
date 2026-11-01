package com.example.notifyledger.parser

import com.example.notifyledger.model.Category
import com.example.notifyledger.model.ClassifyResult
import com.example.notifyledger.model.LearnedRule
import com.example.notifyledger.model.ParseResult
import kotlin.math.round

/**
 * PayNotifyParser — 支付通知解析 / 自动分类 / 规则学习
 *
 * Kotlin 镜像, 与 web 端 web\parser.js 保持逐行一致(同一套正则与语义):
 *  - [detectSource]    识别来源: 微信 / 支付宝 / 银联
 *  - [isPayment]       判断是否为"支出"类支付通知(收入/退款/非支付 → false)
 *  - [extractAmount]   金额提取(带"优惠/立减/减免/抵扣"前缀的金额不作为实付)
 *  - [extractTime]     时间(无时间则退回日期)
 *  - [extractMerchant] 商户清洗(去来源/金额/状态词/收入句式/日期时间/连接词)
 *  - [parse]           组合解析, 失败返回 [ParseResult.Failure](reason)
 *  - [classify]        先查用户学习规则(精确商户名, 忽略大小写), 再默认关键词, 否则 OTHER
 *  - [learn]           upsert 学习规则(纯函数, 返回新列表, 不修改入参)
 *  - [selfTest]        内置自检, 与 parser.js selfTest() 同 9 例
 *
 * 修改任一侧正则后, 请同步另一侧, 并跑 web 端 Node 测试 (test/parser.test.js)。
 */
object PayNotifyParser {

    /* ---------------- 分类(与 parser.js DEFAULT_RULES 一一对应) ---------------- */

    private val DEFAULT_RULES: List<Pair<Category, List<String>>> = listOf(
        Category.DINING to listOf(
            "美团", "饿了么", "大众点评", "外卖", "餐厅", "饭店", "火锅", "寿司",
            "咖啡", "瑞幸", "星巴克", "奶茶", "茶饮", "豆浆", "油条", "面馆", "拉面",
            "早餐", "午餐", "晚餐", "零食", "烘焙", "蛋糕"
        ),
        Category.SHOPPING to listOf(
            "便利店", "淘宝", "京东", "拼多多", "超市", "沃尔玛", "永辉", "盒马",
            "苏宁", "得物", "闲鱼", "商场", "商城", "旗舰店", "药房", "药"
        ),
        Category.TRANSPORT to listOf(
            "滴滴", "DiDi", "地铁", "高铁", "12306", "航空", "航班", "打车",
            "出租车", "单车", "骑行", "加油", "加油站", "高速", "过路"
        ),
        Category.LIFE to listOf(
            "水电", "电费", "水费", "燃气", "话费", "账单", "移动", "联通", "电信",
            "物业", "宽带", "有线电视"
        )
    )

    /* ---------------- 通知识别 ---------------- */

    private val SOURCE_PATTERNS = listOf(
        "微信" to Regex("微信", RegexOption.IGNORE_CASE),
        "支付宝" to Regex("支付宝", RegexOption.IGNORE_CASE),
        "银联" to Regex("银联|云闪付", RegexOption.IGNORE_CASE)
    )

    private val PAYMENT_MARKERS =
        Regex("支付|付款|扣款|消费|缴费|买单|代付|充值|话费|油费|水费|电费|网费|交易|结算|刷卡|订单完成")

    private val INCOME_PATTERNS = Regex("收到|退回|退款|入账|到账")

    /** 识别来源: 微信 / 支付宝 / 银联, 都没有返回 null */
    fun detectSource(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        return SOURCE_PATTERNS.firstOrNull { it.second.containsMatchIn(text) }?.first
    }

    /** 是否为"支出"支付通知; 收入/退款/非支付一律 false */
    fun isPayment(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        if (INCOME_PATTERNS.containsMatchIn(text)) return false
        return PAYMENT_MARKERS.containsMatchIn(text)
    }

    /**
     * 方向 B 的"关键词入口门": 通知文本是否出现支付类关键词(支出标记 或 收入标记)。
     * 命中才进入 AI 判断层; 未命中(天气/验证码/群消息等)在判断层之前直接忽略, 不做任何记录。
     * 注意: 命中只表示"值得判断", 不代表入账 —— 是否保留由判断层决定。
     */
    fun isPaymentRelated(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return PAYMENT_MARKERS.containsMatchIn(text) || INCOME_PATTERNS.containsMatchIn(text)
    }

    /* ---------------- 字段提取 ---------------- */

    private val AMOUNT_RE =
        Regex("¥\\s*(\\d+(?:,\\d{3})*(?:\\.\\d{1,2})?)|(\\d+(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*元")

    private val DISCOUNT_CTX_RE = Regex("(优惠|立减|减免|抵扣)\\s*$")

    private class AmountCandidate(val value: Double, val dec: Int, val discount: Boolean)

    /**
     * 提取实付金额:
     *  - 识别 ¥xx / xx元(支持千分位, 小数最多两位)
     *  - 金额前 4 字符内出现"优惠/立减/减免/抵扣"的视为优惠额, 不作实付
     *  - 优先"两位小数"的金额(真实支付金额), 其次金额大者; 结果保留两位小数
     */
    fun extractAmount(text: String?): Double? {
        if (text.isNullOrEmpty()) return null
        val found = mutableListOf<AmountCandidate>()
        for (m in AMOUNT_RE.findAll(text)) {
            val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (raw.isEmpty()) continue
            val v = raw.replace(",", "").toDoubleOrNull() ?: continue
            if (!v.isFinite() || v <= 0) continue
            val dec = raw.split('.').getOrNull(1)?.length ?: 0
            // 前缀出现"优惠/立减/减免/抵扣"的金额视为优惠额, 不作为实付金额
            val prefixStart = maxOf(0, m.range.first - 4)
            val discount =
                DISCOUNT_CTX_RE.containsMatchIn(text.substring(prefixStart, m.range.first))
            found += AmountCandidate(round(v * 100.0) / 100.0, dec, discount)
        }
        if (found.isEmpty()) return null
        var main = found.filter { !it.discount }.ifEmpty { found }
        main = main.sortedWith(compareByDescending<AmountCandidate> { it.dec }.thenByDescending { it.value })
        return main.first().value
    }

    private val TIME_RE = Regex("\\d{1,2}:\\d{2}(?::\\d{2})?")
    private val DATE_RE = Regex("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}")

    /** 提取时间(没有 HH:mm 则退回日期), 都没有返回 null */
    fun extractTime(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        return TIME_RE.find(text)?.value ?: DATE_RE.find(text)?.value
    }

    /** 商户清洗管线(顺序与 parser.js 一致): 去来源→去金额→去状态词→去收入句式→去日期时间→去连接词→清理标点 */
    fun extractMerchant(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        var t = text
        t = t.replace(Regex("微信\\s*支付|微信|支付宝|银联\\s*支付|银联|云闪付"), "")
        t = t.replace(Regex("¥\\s*\\d+(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:,\\d{3})*(?:\\.\\d{1,2})?\\s*元"), "")
        t = t.replace(Regex("支付成功|交易成功|消费成功|扣款成功|退款成功|订单完成|刷卡成功|付款|扣款|车费|支付"), "")
        t = t.replace(Regex("收到(一个|好友)? ?转账|收款"), "")
        t = t.replace(Regex("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}"), "")
        t = t.replace(Regex("\\d{1,2}:\\d{2}(?::\\d{2})?"), "")
        t = t.replace(Regex("向|给|商户|本笔|备注|您|已"), "")
        t = t.replace(Regex("[，。|、:：,\\s]"), " ").trim()
        t = Regex("\\s{2,}").replace(t, " ").trim()
        return t.ifEmpty { null }
    }

    /* ---------------- 组合解析 ---------------- */

    /** 解析一条通知文本。成功 [ParseResult.Success](source/amount/merchant/time/raw); 失败 [ParseResult.Failure](reason, kind) */
    fun parse(text: String?): ParseResult {
        if (text == null || text.trim().isEmpty()) {
            return ParseResult.Failure("空输入", ParseResult.FailKind.EMPTY)
        }
        if (INCOME_PATTERNS.containsMatchIn(text)) {
            return ParseResult.Failure("收入/退款类通知, 不计入支出账本", ParseResult.FailKind.INCOME)
        }
        if (!PAYMENT_MARKERS.containsMatchIn(text)) {
            return ParseResult.Failure("非支付通知, 已丢弃", ParseResult.FailKind.NOT_PAYMENT)
        }
        val amount = extractAmount(text)
        if (amount == null) {
            return ParseResult.Failure("未识别到金额", ParseResult.FailKind.NO_AMOUNT)
        }
        return ParseResult.Success(
            source = detectSource(text),
            amount = amount,
            merchant = extractMerchant(text),
            time = extractTime(text),
            raw = text
        )
    }

    /* ---------------- 分类 + 规则学习 ---------------- */

    /** 先匹配用户学习规则(精确商户名, 忽略大小写), 再默认关键词, 否则 OTHER */
    fun classify(merchant: String?, learnedRules: List<LearnedRule>): ClassifyResult {
        val name = (merchant ?: "").trim()
        if (name.isNotEmpty()) {
            if (learnedRules.isNotEmpty()) {
                val low = name.lowercase()
                for (r in learnedRules) {
                    if (r.merchant.trim().lowercase() == low) {
                        return ClassifyResult(r.category, "learned")
                    }
                }
            }
            for ((cat, kws) in DEFAULT_RULES) {
                for (kw in kws) {
                    if (name.contains(kw)) {
                        return ClassifyResult(cat, "default")
                    }
                }
            }
        }
        return ClassifyResult(Category.OTHER, "unknown")
    }

    /** upsert 学习规则(纯函数, 返回新列表); 商户名为空时原样返回 */
    fun learn(merchant: String?, category: Category, rules: List<LearnedRule>): List<LearnedRule> {
        val name = (merchant ?: "").trim()
        if (name.isEmpty()) return rules
        val low = name.lowercase()
        val out = rules.filter { it.merchant.trim().lowercase() != low }
        return out + LearnedRule(name, category)
    }

    /* ---------------- 内置自检(与 parser.js selfTest() 同 9 例) ---------------- */

    data class SelfTestCase(
        val name: String,
        val text: String,
        val learned: List<LearnedRule> = emptyList(),
        val expectOk: Boolean,
        val expectAmount: Double? = null,
        val expectMerchant: String? = null,
        val expectTime: String? = null,
        val expectCategory: String? = null, // 分类名, 如 "shopping"
        val expectSource: String? = null
    )

    data class SelfTestResult(val name: String, val ok: Boolean, val detail: String)

    private val SELF_TEST_CASES = listOf(
        // —— 与 web/parser.js selfTest() 完全同 9 例(同一文本、同一期望), 作为两侧一致性基准 ——
        SelfTestCase(
            "微信消费(便利店)", "微信支付 支付成功 ¥23.50 XX便利店 12:47",
            expectOk = true, expectSource = "微信", expectAmount = 23.5,
            expectMerchant = "XX便利店", expectTime = "12:47", expectCategory = "shopping"
        ),
        SelfTestCase(
            "支付宝消费(美团外卖)", "支付宝 向美团外卖支付 15.00元",
            expectOk = true, expectSource = "支付宝", expectAmount = 15.0,
            expectMerchant = "美团外卖", expectCategory = "dining"
        ),
        SelfTestCase(
            "收入转账应丢弃", "微信 收到好友转账 ¥100.00",
            expectOk = false
        ),
        SelfTestCase(
            "非支付通知应丢弃", "系统更新完成, 请重启手机安装最新功能",
            expectOk = false
        ),
        SelfTestCase(
            "生活缴费(话费账单)", "中国移动 话费账单 支付成功 29.00元",
            expectOk = true, expectAmount = 29.0, expectMerchant = "中国移动 话费账单",
            expectCategory = "life"
        ),
        SelfTestCase(
            "交通(滴滴打车)", "滴滴出行 订单完成 车费 12.50元 2026-10-20 08:05",
            expectOk = true, expectAmount = 12.5, expectMerchant = "滴滴出行",
            expectTime = "08:05", expectCategory = "transport"
        ),
        SelfTestCase(
            "缺金额应丢弃", "支付宝 向 XX商户 支付成功",
            expectOk = false
        ),
        SelfTestCase(
            "未知商户归\"其他\"", "支付宝 向 小明文具 支付 18.00元",
            expectOk = true, expectAmount = 18.0, expectMerchant = "小明文具",
            expectCategory = "other"
        ),
        SelfTestCase(
            "学习规则优先于默认", "支付宝 向美团外卖支付 15.00元",
            learned = listOf(LearnedRule("美团外卖", Category.SHOPPING)),
            expectOk = true, expectMerchant = "美团外卖", expectCategory = "shopping"
        )
    )

    /** 运行全部自检用例, 返回逐例结果 */
    fun selfTest(): List<SelfTestResult> = SELF_TEST_CASES.map { c ->
        var ok = false
        val parts = mutableListOf<String>()
        when (val p = parse(c.text)) {
            is ParseResult.Success -> {
                ok = c.expectOk
                if (c.expectOk) {
                    if (c.expectAmount != null && p.amount != c.expectAmount) {
                        ok = false; parts += "金额=${p.amount}(期望${c.expectAmount})"
                    }
                    if (c.expectMerchant != null && p.merchant != c.expectMerchant) {
                        ok = false; parts += "商户=${p.merchant}(期望${c.expectMerchant})"
                    }
                    if (c.expectTime != null && p.time != c.expectTime) {
                        ok = false; parts += "时间=${p.time}(期望${c.expectTime})"
                    }
                    if (c.expectSource != null && p.source != c.expectSource) {
                        ok = false; parts += "来源=${p.source}(期望${c.expectSource})"
                    }
                    val cr = classify(p.merchant, c.learned)
                    if (c.expectCategory != null && cr.category.name.lowercase() != c.expectCategory) {
                        ok = false; parts += "分类=${cr.category}(期望${c.expectCategory})"
                    }
                } else {
                    parts += "应为非支付, 却解析成功: 金额=${p.amount} 商户=${p.merchant}"
                }
            }
            is ParseResult.Failure -> {
                ok = !c.expectOk
                if (c.expectOk) parts += "解析失败: ${p.reason}"
            }
        }
        SelfTestResult(c.name, ok, if (ok) "通过" else parts.joinToString(" "))
    }

    /* ---------------- 多交易拆分 + OCR 清洗 + 三态 judge (对齐 web/parser.js) ---------------- */

    private val AMOUNT_SPLIT_RE =
        Regex("¥\\s*\\d+(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:,\\d{3})*(?:\\.\\d{1,2})?\\s*元")

    /**
     * 拆分"多交易混合"文本(一张多平台截图 OCR 后的整行)为独立交易段。
     * 规则: 按每个"金额"位置切段, 一段可含多个相邻金额, 段边界 = 金额起始位置。
     * 过滤: 只保留含 ¥ 或 \d+元 的段。
     * (与 web parser.js 的 splitTransactions 1:1 对齐)
     */
    fun splitTransactions(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val parts = mutableListOf<String>()
        var last = 0
        for (m in AMOUNT_SPLIT_RE.findAll(text)) {
            if (m.range.first > last) {
                parts += text.substring(last, m.range.last + 1)
            } else if (parts.isNotEmpty()) {
                parts[parts.lastIndex] += " " + text.substring(last, m.range.last + 1)
            } else {
                parts += text.substring(last, m.range.last + 1)
            }
            last = m.range.last + 1
        }
        if (last < text.length) parts += text.substring(last)
        return parts.filter { seg ->
            val s = seg.trim()
            s.isNotEmpty() && (AMOUNT_SPLIT_RE.containsMatchIn(s) || s.contains('¥'))
        }
    }

    /**
     * 本地 OCR 文本清洗: 把截图识别出的多行零散文字清洗成单行, 便于走 parse/judge。
     * (与 web parser.js 的 cleanOcrText 对齐)
     */
    fun cleanOcrText(ocrText: String?): String {
        if (ocrText.isNullOrEmpty()) return ""
        val lines = ocrText.split('\n', '\r')
            .map { it.replace(Regex("\\t+"), " ").trim() }
            .filter { it.isNotEmpty() }
        return lines.joinToString(" ")
    }

    /** 三态判定结果(对齐 web judge() 和 Android JudgeChain 的三态语义) */
    data class JudgeResult(
        val verdict: String, // "keep" / "discard"
        val amount: Double? = null,
        val merchant: String? = null,
        val source: String? = null,
        val time: String? = null,
        val category: String? = null,
        val via: String? = null,
        val reason: String? = null,
        val raw: String? = null
    )

    /**
     * 判断链本地入口(与 web judge(text, rules) 对齐):
     *  本地规则判断 —— 解析成功且关键字门通过才 keep, 否则 discard, 不外呼任何 AI。
     */
    fun judge(
        text: String?,
        learnedRules: List<LearnedRule> = emptyList()
    ): JudgeResult {
        // 本地规则判断
        return when (val p = parse(text)) {
            is ParseResult.Failure -> JudgeResult(
                verdict = "discard",
                reason = p.reason,
                raw = text
            )
            is ParseResult.Success -> {
                val cr = classify(p.merchant, learnedRules)
                JudgeResult(
                    verdict = "keep",
                    amount = p.amount,
                    merchant = p.merchant,
                    source = p.source,
                    time = p.time,
                    category = cr.category.label,
                    via = cr.via,
                    raw = text
                )
            }
        }
    }
}

