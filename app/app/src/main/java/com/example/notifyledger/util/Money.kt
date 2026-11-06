package com.example.notifyledger.util

import java.util.Locale

/**
 * 金额展示工具。评审 §2.6: 自 MainActivity 下沉, 供账单/分析 Fragment 共用。
 */
object Money {
    /** 与旧 `MainActivity.fmt(v)` 完全一致: "¥" + 两位小数 */
    fun fmt(v: Double): String = "¥" + String.format(Locale.getDefault(), "%.2f", v)
}
