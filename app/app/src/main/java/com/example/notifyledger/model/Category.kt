package com.example.notifyledger.model

/** 支出分类(与 web 端 parser.js 的 CATEGORIES 一一对应) */
enum class Category(val label: String) {
    DINING("餐饮"),
    SHOPPING("购物"),
    TRANSPORT("交通"),
    LIFE("生活"),
    OTHER("其他");

    companion object {
        /** 分类中文标签(与枚举序一致), 供弹窗选择器/规则页共用; 评审 §2.6 自 MainActivity 下沉至此。 */
        val CAT_LABELS: List<String> = entries.map { it.label }

        /** 按中文标签解析, 找不到返回 OTHER */
        fun fromLabel(label: String?): Category =
            entries.firstOrNull { it.label == label } ?: OTHER
    }
}
