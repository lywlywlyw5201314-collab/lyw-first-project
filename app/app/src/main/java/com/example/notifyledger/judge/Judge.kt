package com.example.notifyledger.judge

import com.example.notifyledger.model.LedgerEntry

/**
 * 判定结果 —— 方向 B「关键词入口门 → 判断层 → 保留/放弃」中判断层产出的唯一决定。
 *
 * 三态语义(与 web 原型、流程图一致):
 *  - [Keep]     判定为支出支付, 携带入账记录, 写入本地账本
 *  - [Discard]  定论「其他信息」, 直接放弃, 不写入任何账本(仅日志留痕, 便于审计)
 *  - [Abstain]  该判断器不确定, 交给链上的下一个判断器(预留扩展点; 当前仅有本地规则判断器)
 */
sealed class Verdict {
    /** 保留: 入账。[via] 标注由哪个判断器做出("rule:learned" / "rule:default" / "rule:unknown") */
    data class Keep(val entry: LedgerEntry, val via: String) : Verdict()

    /** 放弃: 「其他信息」。[reason] 说明原因, [raw] 保留原文供审计, [via] 标注判定来源 */
    data class Discard(val reason: String, val raw: String, val via: String) : Verdict()

    /** 弃权: 交给下一个判断器 */
    object Abstain : Verdict()
}

/**
 * 判断器: 判断层的一个阶段。
 *
 * 入参: [text] = 通知文本(标题+内容合并), [source] = PayNotifyParser.detectSource 识别出的
 * 支付来源(微信/支付宝/银联), 未识别时为 null。
 */
interface Judge {
    /** 判断器标识, 用于日志与 Verdict.via */
    fun name(): String

    /** 对一条文本做出判定; 不确定时返回 [Verdict.Abstain] */
    fun judge(text: String, source: String?): Verdict
}

/**
 * 判断链: 按 [judges] 顺序执行, 第一个非弃权结果即为最终决定。
 *
 * 全部弃权 → [Verdict.Discard](理由=[ABSTAIN_ALL_REASON]) —— 即产品语义「如果是其他信息,
 * 输出直接放弃」的兜底: 没有任何判断器给出定论时, 一样不进账本。
 */
class JudgeChain(private val judges: List<Judge>) {

    /** 忽略规则(用户设置的'忽略通知')命中则直接 Discard, 优先于规则判断 */
    fun judge(text: String, source: String?, ignoreRules: List<String> = emptyList()): Verdict {
        for (ig in ignoreRules) {
            if (ig.isNotEmpty() && text.contains(ig, ignoreCase = true)) {
                return Verdict.Discard("用户忽略规则: " + ig, text, "ignore-rule")
            }
        }
        for (j in judges) {
            when (val v = j.judge(text, source)) {
                is Verdict.Keep -> return v
                is Verdict.Discard -> return v
                Verdict.Abstain -> Unit
            }
        }
        return Verdict.Discard(ABSTAIN_ALL_REASON, text, "chain")
    }

    companion object {
        const val ABSTAIN_ALL_REASON = "判断链全部弃权, 按设计放弃(其他信息不记录)"
    }
}
