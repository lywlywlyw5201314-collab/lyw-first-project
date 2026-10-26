package com.example.notifyledger.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.example.notifyledger.model.Category
import com.example.notifyledger.model.LedgerEntry
import com.example.notifyledger.model.LearnedRule

/**
 * 账本仓库: 对 UI 层与通知服务暴露的统一入口(全部同步操作, 调用方自行放到后台线程)。
 *
 * 对应 web 端 localStorage("notifyledger.v1") 的同一组能力:
 *  - 流水: [insertEntry] / [allEntries] / [deleteEntry] / [updateEntryCategory]
 *  - 学习: [learnedRules] / [upsertLearnedRule] / [deleteLearnedRule]
 *  - 统计: [sumBetween] / [sumByCategory] / [dailyTotals]
 */
class LedgerRepository(context: Context) {

    private val helper: LedgerDb = LedgerDb(context.applicationContext)

    /* ---------------- 账本流水 ---------------- */

    /** 保留一条支付 → 落库; 返回自增 id */
    fun insertEntry(e: LedgerEntry): Long {
        val v = ContentValues().apply {
            put("created_at", e.createdAt)
            put("day", e.day)
            put("source", e.source)
            put("amount", e.amount)
            put("merchant", e.merchant)
            put("category", e.category)
            put("raw", e.raw)
        }
        return helper.writableDatabase.insert("entries", null, v)
    }

    /** 全部流水(倒序), 默认上限 500 条防 UI 膨胀 */
    fun allEntries(limit: Int = 500): List<LedgerEntry> {
        return helper.readableDatabase.rawQuery(
            "SELECT id, created_at, day, source, amount, merchant, category, raw " +
                "FROM entries ORDER BY day DESC, id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            val out = mutableListOf<LedgerEntry>()
            while (c.moveToNext()) out += bindEntry(c)
            out
        }
    }

    fun deleteEntry(id: Long) {
        helper.writableDatabase.delete("entries", "id = ?", arrayOf(id.toString()))
    }

    /** 分类纠错(账本行点选分类后), 同时建议调用 [upsertLearnedRule] 沉淀学习 */
    fun updateEntryCategory(id: Long, category: String) {
        helper.writableDatabase.update(
            "entries",
            ContentValues().apply { put("category", category) },
            "id = ?",
            arrayOf(id.toString())
        )
    }

    /* ---------------- 学习规则 ---------------- */

    /** 全部学习规则(传给 PayNotifyParser.classify / RuleJudge) */
    fun learnedRules(): List<LearnedRule> =
        helper.readableDatabase.rawQuery(
            "SELECT merchant, category FROM learned_rules ORDER BY merchant", null
        ).use { c ->
            val out = mutableListOf<LearnedRule>()
            while (c.moveToNext()) {
                out += LearnedRule(c.getString(0), Category.fromLabel(c.getString(1)))
            }
            out
        }

    /**
     * 分类纠错后 upsert 学习规则(商户名 UNIQUE + COLLATE NOCASE: 同名(忽略大小写)只保留最新一条)。
     * 后续同类商户自动归入该分类 —— 这就是「学习」闭环。
     */
    fun upsertLearnedRule(rule: LearnedRule) {
        helper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO learned_rules (merchant, category) VALUES (?, ?)",
            arrayOf(rule.merchant, rule.category.label)
        )
    }

    fun deleteLearnedRule(merchant: String) {
        helper.writableDatabase.delete("learned_rules", "merchant = ?", arrayOf(merchant))
    }

    /* ---------------- 统计看板 ---------------- */

    /** 日期区间内总支出 */
    fun sumBetween(fromDay: String, toDay: String): Double =
        queryOne("SELECT COALESCE(SUM(amount), 0) FROM entries WHERE day BETWEEN ? AND ?", fromDay, toDay)

    /** 日期区间内按分类汇总(降序), 用于分类饼图/柱状 */
    fun sumByCategory(fromDay: String, toDay: String): List<Pair<String, Double>> =
        helper.readableDatabase.rawQuery(
            "SELECT category, COALESCE(SUM(amount), 0) FROM entries " +
                "WHERE day BETWEEN ? AND ? GROUP BY category ORDER BY SUM(amount) DESC",
            arrayOf(fromDay, toDay)
        ).use { c ->
            val out = mutableListOf<Pair<String, Double>>()
            while (c.moveToNext()) out += c.getString(0) to c.getDouble(1)
            out
        }

    /** 按传入日期顺序返回每日总额(无数据的日期补 0.0), 用于近 N 天趋势 */
    fun dailyTotals(days: List<String>): List<Pair<String, Double>> {
        if (days.isEmpty()) return emptyList()
        val totals = helper.readableDatabase.rawQuery(
            "SELECT day, COALESCE(SUM(amount), 0) FROM entries " +
                "WHERE day IN (${days.joinToString(",") { "?" }}) GROUP BY day",
            days.toTypedArray()
        ).use { c ->
            val m = HashMap<String, Double>()
            while (c.moveToNext()) m[c.getString(0)] = c.getDouble(1)
            m
        }
        return days.map { it to (totals[it] ?: 0.0) }
    }

    /* ---------------- 内部工具 ---------------- */

    private fun bindEntry(c: Cursor): LedgerEntry = LedgerEntry(
        id = c.getLong(0),
        createdAt = c.getLong(1),
        day = c.getString(2),
        source = c.getString(3),
        amount = c.getDouble(4),
        merchant = c.getString(5),
        category = c.getString(6),
        raw = c.getString(7)
    )

    private fun queryOne(sql: String, vararg args: String): Double =
        helper.readableDatabase.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }
}