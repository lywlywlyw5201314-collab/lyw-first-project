package com.example.notifyledger.model

/** 用户纠正后学到的分类规则(镜像 parser.js 的 {merchant, category}) */
data class LearnedRule(
    val merchant: String,
    val category: Category
)
