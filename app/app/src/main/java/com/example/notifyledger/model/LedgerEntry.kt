package com.example.notifyledger.model

/** 账本条目(镜像 web 端 LedgerEntry: 一条已入账的支付记录) */
data class LedgerEntry(
    val id: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    /** 本地日期, Asia/Shanghai, 形如 2026-10-21(统计口径与 web 端一致) */
    val day: String = "",
    val source: String? = null,
    val amount: Double = 0.0,
    val merchant: String? = null,
    val category: String = Category.OTHER.label,
    val raw: String = ""
)
