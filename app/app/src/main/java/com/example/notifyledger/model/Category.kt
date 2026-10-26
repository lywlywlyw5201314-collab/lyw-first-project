package com.example.notifyledger.model

/** 支出分类(与 web 端 parser.js 的 CATEGORIES 一一对应) */
enum class Category(val label: String) {
    DINING("餐饮"),
    SHOPPING("购物"),
    TRANSPORT("交通"),
    LIFE("生活"),
    OTHER("其他");

    companion object {
        /** 按中文标签解析, 找不到返回 OTHER */
        fun fromLabel(label: String?): Category =
            entries.firstOrNull { it.label == label } ?: OTHER
    }
}
