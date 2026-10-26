package com.example.notifyledger.judge

import android.content.Context
import com.example.notifyledger.data.LedgerRepository

/**
 * 判断链工厂: 方向 A(粘贴文本 / 截图)与方向 B(弹窗)共用同一条判断链 —— 同一套
 * 「AI 判断(可选) + 本地规则兜底」。
 *
 * 顺序: [LlmJudge](启用时首选) → [RuleJudge](确定性兜底)。
 * 全部弃权 → JudgeChain 兜底 Discard(「其他信息直接放弃」的产品语义)。
 */
object JudgeProvider {

    /** 生产链: LLM(启用时) + 规则(带实时学习规则 provider) */
    fun chain(context: Context): JudgeChain {
        val repo = LedgerRepository(context.applicationContext)
        val cfg = LlmConfig.load(context.applicationContext)
        return JudgeChain(
            listOf(
                LlmJudge { cfg },
                RuleJudge { repo.learnedRules() }
            )
        )
    }

    /** 测试/演示链: 可自定义顺序与组合 */
    fun custom(llmEnabled: Boolean = false, customLlm: LlmJudge? = null, rules: () -> List<com.example.notifyledger.model.LearnedRule>): JudgeChain {
        val judges = mutableListOf<Judge>()
        if (llmEnabled || customLlm != null) judges += customLlm ?: LlmJudge()
        judges += RuleJudge(rules)
        return JudgeChain(judges)
    }
}
