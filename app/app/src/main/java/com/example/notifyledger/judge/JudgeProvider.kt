package com.example.notifyledger.judge

import android.content.Context
import com.example.notifyledger.data.LedgerRepository

/**
 * 判断器工厂。
 *
 * 已移除 AI 判断: 判断链只有 [RuleJudge](本地规则学习, 无网络、隐私安全)。
 * 非支付信息一律丢弃, 识别不到商户的支付条目兜底归「其他」, 并可通过规则页学习纠正。
 *
 * 扩展点: 将来若再接入新的判断器(如截图 OCR), 只需新增 [Judge] 实现并加入 [chain] 即可。
 */
object JudgeProvider {

    /** 完整判断链: 忽略规则 → 本地规则学习。 */
    fun chain(context: Context): JudgeChain {
        val repo = LedgerRepository.get(context)
        val judges: MutableList<Judge> = mutableListOf(
            RuleJudge { repo.learnedRules() }
        )
        return JudgeChain(judges)
    }
}
