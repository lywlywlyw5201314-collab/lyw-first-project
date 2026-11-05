package com.example.notifyledger

import com.example.notifyledger.judge.JudgeChain
import com.example.notifyledger.judge.RuleJudge
import com.example.notifyledger.judge.Verdict
import com.example.notifyledger.model.Category
import com.example.notifyledger.model.LearnedRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JudgeChain JUnit 单测 —— 纯本地规则判断链(已完全去掉 AI/网络外呼):
 *
 *  [1] 规则保留(keep): 解析成功 → 默认分类
 *  [2] 学习规则优先: 用户改过的分类立即生效(rule:learned)
 *  [3] 定论丢弃: 非支付 / 缺金额 → 不进账本(隐私红线)
 *  [4] 忽略规则: 用户丢弃过的签名 → 优先直接丢弃, 不再保留
 *  [5] 全弃权兜底: 空链也给出定论(放弃), 不崩
 *
 * 与 web 端 test/judge.test.js 对齐: 两侧签名均为两参, 无 aiJudge 回调。
 * 评审§F: 已迁到 JUnit 5(jupiter), 断言统一用"条件在前、消息在后"。
 */
class JudgeChainTest {

    private val chain = JudgeChain(listOf(RuleJudge { emptyList() }))

    @Test
    fun ruleKeep_payment() {
        val v = chain.judge("微信支付 支付成功 ¥23.50 XX便利店 12:47", "微信")
        assertTrue(v is Verdict.Keep, "应为 keep: $v")
        val keep = v as Verdict.Keep
        assertEquals(23.5, keep.entry.amount, 0.001)
        assertEquals("XX便利店", keep.entry.merchant)
    }

    @Test
    fun learnedRuleWins_defaultMerchant() {
        val learned = JudgeChain(listOf(RuleJudge {
            listOf(LearnedRule("美团外卖", Category.SHOPPING))
        }))
        val v = learned.judge("支付宝 向美团外卖支付 15.00元", "支付宝")
        assertTrue(v is Verdict.Keep, "应为 keep: $v")
        val keep = v as Verdict.Keep
        assertEquals("购物", keep.entry.category)
        assertEquals("rule:learned", keep.via)
    }

    @Test
    fun defaultClassify_unknownMerchantOther() {
        val v = chain.judge("微信支付 支付成功 ¥12.00 小卖部 09:00", "微信")
        assertTrue(v is Verdict.Keep, "应为 keep: $v")
        val keep = v as Verdict.Keep
        assertEquals("其他", keep.entry.category)
        assertEquals("rule:unknown", keep.via)
    }

    @Test
    fun discard_nonPayment() {
        val v = chain.judge("系统更新完成, 请重启", null)
        assertTrue(v is Verdict.Discard, "应为 discard: $v")
        assertEquals("rule", (v as Verdict.Discard).via)
    }

    @Test
    fun discard_missingAmount() {
        val v = chain.judge("支付宝 向 XX商户 支付成功", "支付宝")
        assertTrue(v is Verdict.Discard, "缺金额必须丢弃(隐私红线): $v")
    }

    @Test
    fun ignoreRule_blocksKeep() {
        val text = "微信支付 支付成功 ¥23.50 XX便利店 12:47"
        val v = chain.judge(text, "微信", ignoreRules = listOf("XX便利店"))
        assertTrue(v is Verdict.Discard, "忽略规则命中应丢弃: $v")
        val discard = v as Verdict.Discard
        assertEquals("ignore-rule", discard.via)
        assertTrue(discard.reason.startsWith("用户忽略规则"))
    }

    @Test
    fun ignoreRuleCaseInsensitive() {
        val v = chain.judge("WeChat 微信支付 ¥5.00 瑞幸咖啡", "微信", ignoreRules = listOf("瑞幸咖啡"))
        assertTrue(v is Verdict.Discard, "忽略应大小写不敏感: $v")
    }

    @Test
    fun emptyChain_abstainAll_discards() {
        val empty = JudgeChain(emptyList())
        val v = empty.judge("微信支付 支付成功 ¥9.90 任意商户", "微信")
        assertTrue(v is Verdict.Discard, "空链必须兜底放弃: $v")
        val discard = v as Verdict.Discard
        assertEquals("chain", discard.via)
        assertEquals(JudgeChain.ABSTAIN_ALL_REASON, discard.reason)
    }

    @Test
    fun emptyText_discard() {
        val v = chain.judge("", null)
        assertTrue(v is Verdict.Discard, "空输入丢弃: $v")
    }
}
