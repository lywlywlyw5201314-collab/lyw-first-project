package com.example.notifyledger.judge

import com.example.notifyledger.model.LedgerEntry
import com.example.notifyledger.model.LearnedRule
import com.example.notifyledger.model.ParseResult
import com.example.notifyledger.parser.PayNotifyParser
import com.example.notifyledger.util.TimeUtil

/**
 * 规则判断器(方向 B 判断层的确定性兜底): 完全用 [PayNotifyParser] 的本地规则判定, 零依赖。
 *
 * 语义:
 *  - 解析失败且是「定论」(空输入 / 收入退款 / 非支付 / 无法确认金额) → [Verdict.Discard]
 *  - 解析成功 → 分类(学习规则优先, 再默认关键词, 否则归 其他) → 构造 [LedgerEntry] → [Verdict.Keep]
 *
 * 注意: 缺金额(NO_AMOUNT)对本地规则同样是「解析失败」, 按定论放弃 ——
 * 需要「猜出金额」的场景由前置的 [LlmJudge](AI 判断器)负责, 它弃权时规则层兜底。
 *
 * [learnedRules] 为惰性 provider: 每次判定都取最新学习规则(用户改分类后立刻生效)。
 */
class RuleJudge(private val learnedRules: () -> List<LearnedRule> = { emptyList() }) : Judge {

    override fun name(): String = "rule"

    override fun judge(text: String, source: String?): Verdict {
        when (val p = PayNotifyParser.parse(text)) {
            is ParseResult.Failure -> {
                // 定论: 不是支出(空 / 收入退款 / 非支付 / 未识别到金额) → 直接放弃
                return Verdict.Discard(p.reason, text, "rule")
            }
            is ParseResult.Success -> {
                val cr = PayNotifyParser.classify(p.merchant, learnedRules())
                return Verdict.Keep(
                    LedgerEntry(
                        day = TimeUtil.todayDay(),
                        source = source ?: p.source,
                        amount = p.amount,
                        merchant = p.merchant,
                        category = cr.category.label,
                        raw = text
                    ),
                    "rule:${cr.via}"
                )
            }
        }
    }
}
