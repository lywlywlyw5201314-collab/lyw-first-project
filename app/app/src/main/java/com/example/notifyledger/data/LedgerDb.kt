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
 *  - 金额统一存整数分(列 amount_cents, INTEGER, §2.1): 小数算钱容易出精度问题, 存整数分就不会,
 *    统计时 SUM(amount_cents) 是整数加法, 结果精确, 展示时再 /100.0 变回元
 *  - 内存里 LedgerEntry.amount 还是 Double 元不动, 元↔分 的换算只发生在 LedgerRepository
 *    (写库时 *100 取整, 读库时 /100.0), 改数据库不影响外面任何代码
 *  - 老数据怎么办(§2.7): 升级时整张表重建, 每一行原样搬进新表, 只有金额那一列从元换算成分,
 *    其他所有列一模一样, 不丢任何数据; 全程在一个事务里做, 中途出错就整体回滚
 */
class LedgerDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createEntriesTable(db)
        db.execSQL("CREATE INDEX idx_entries_day ON entries(day)")
        db.execSQL(
            "CREATE TABLE learned_rules (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "merchant TEXT NOT NULL UNIQUE COLLATE NOCASE, " +
                "category TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 升级规则: 老库里存的每一笔账都必须原样保留, 不能为了升级把数据弄丢。
        // 以后加版本就在下面追加 "if (oldVersion < N)" 分支, 老分支别动。
        if (oldVersion < 2) {
            // v1 → v2: 金额列从 "元(小数)" 改成 "分(整数)"。SQLite 不能直接改列的类型,
            // 所以走标准的"重建表"套路: 建一张新表 → 把老表每行抄过去(只有金额列 *100 变成分)
            // → 删掉老表 → 新表改名顶上去 → 把 day 索引重新建一遍。
            // 包在一个事务里: 任何一步失败就全部撤销, 老表还是原样, 保证零丢数据。
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE entries_v2 (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "created_at INTEGER NOT NULL, " +
                    "day TEXT NOT NULL, " +
                    "source TEXT, " +
                    "amount_cents INTEGER NOT NULL, " +
                    "merchant TEXT, " +
                    "category TEXT NOT NULL, " +
                    "raw TEXT NOT NULL)")
                db.execSQL(
                    "INSERT INTO entries_v2 (id, created_at, day, source, amount_cents, merchant, category, raw) " +
                        "SELECT id, created_at, day, source, CAST(ROUND(amount * 100) AS INTEGER), merchant, category, raw " +
                        "FROM entries"
                )
                db.execSQL("DROP TABLE entries")
                db.execSQL("ALTER TABLE entries_v2 RENAME TO entries")
                db.execSQL("CREATE INDEX idx_entries_day ON entries(day)")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        // 未来加列示例(启用时把 DB_VERSION +1, 并取消注释):
        // if (oldVersion < 3) {
        //     db.execSQL("ALTER TABLE entries ADD COLUMN note TEXT")
        //     db.execSQL("UPDATE entries SET note = '' WHERE note IS NULL")
        // }
    }

    /** entries 表的建表语句(v2: 金额存整数分), 新建库和老库升级共用这一份 */
    private fun createEntriesTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE entries (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "created_at INTEGER NOT NULL, " +
                "day TEXT NOT NULL, " +
                "source TEXT, " +
                "amount_cents INTEGER NOT NULL, " +
                "merchant TEXT, " +
                "category TEXT NOT NULL, " +
                "raw TEXT NOT NULL)"
        )
    }

    companion object {
        const val DB_NAME = "notifyledger.db"
        const val DB_VERSION = 2
    }
}
