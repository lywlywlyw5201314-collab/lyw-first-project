package com.example.notifyledger.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地账本数据库(SQLite, Android 框架自带, 无需额外依赖)。
 *
 *  - [表 entries]        账本流水: 每保留一条支付通知落一行(放弃的不写库)
 *  - [表 learned_rules]  学习规则: 商户名 → 分类(UNIQUE + COLLATE NOCASE, 忽略大小写)
 *
 * 设计说明(给 Codex 的提示):
 *  - day 列统一存 "YYYY-MM-DD"(Asia/Shanghai), 与 web 端 localStorage 的 key 规则一致, 便于对账
 *  - 统计查询全部走 day 列的 BETWEEN, 索引 idx_entries_day 已建
 */
class LedgerDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE entries (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "created_at INTEGER NOT NULL, " +
                "day TEXT NOT NULL, " +
                "source TEXT, " +
                "amount REAL NOT NULL, " +
                "merchant TEXT, " +
                "category TEXT NOT NULL, " +
                "raw TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_entries_day ON entries(day)")
        db.execSQL(
            "CREATE TABLE learned_rules (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "merchant TEXT NOT NULL UNIQUE COLLATE NOCASE, " +
                "category TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 骨架阶段: 直接重建(Codex 接手后如需保留数据, 改为增量迁移)
        db.execSQL("DROP TABLE IF EXISTS learned_rules")
        db.execSQL("DROP TABLE IF EXISTS entries")
        onCreate(db)
    }

    companion object {
        const val DB_NAME = "notifyledger.db"
        const val DB_VERSION = 1
    }
}
