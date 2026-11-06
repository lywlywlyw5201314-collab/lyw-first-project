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
 *
 * 说明(评审§3.2): [LedgerRepository] 现按 applicationContext 缓存为单例,
 * 避免每次 [chain] 都新建一份含 [LedgerRepository] 的 SQLiteOpenHelper(该 Helper 本身线程安全, 可安全共享)。
 */
object JudgeProvider {

    /** 按 applicationContext 缓存的 [LedgerRepository]; 同一进程内共享一份, 避免逐次分配 Helper。 */
    private val cached = ArrayDeque<Pair<Context, LedgerRepository>>()

    /** 完整判断链: 忽略规则 → 本地规则学习。 */
    fun chain(context: Context): JudgeChain {
        val repo = repoFor(context)
        val judges: MutableList<Judge> = mutableListOf(
            RuleJudge { repo.learnedRules() }
        )
        return JudgeChain(judges)
    }

    /** 取/建 applicationContext 维度的 [LedgerRepository] 单例(双重检查, 线程安全)。 */
    fun sharedRepo(context: Context): LedgerRepository = repoFor(context)

    private fun repoFor(context: Context): LedgerRepository {
        val ctx = context.applicationContext
        cached.firstOrNull { it.first === ctx }?.let { return it.second }
        synchronized(cached) {
            cached.firstOrNull { it.first === ctx }?.let { return it.second }
            val repo = LedgerRepository(ctx)
            cached.addLast(ctx to repo)
            return repo
        }
    }
}
