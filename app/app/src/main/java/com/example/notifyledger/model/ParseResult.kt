package com.example.notifyledger.model

/** parse() 结果: 成功带字段, 失败带原因(镜像 parser.js 的 {ok:true,...}/{ok:false,reason}) */
sealed class ParseResult {
    /**
     * 失败类型:
     *  - EMPTY      空输入
     *  - INCOME     收入/退款类(定论: 不是支出, 直接丢弃)
     *  - NOT_PAYMENT 无任何支付关键词(定论: 其他信息, 直接丢弃)
     *  - NO_AMOUNT  是支付但金额未识别(不确定: 可交给 AI 判断层)
     */
    enum class FailKind { EMPTY, INCOME, NOT_PAYMENT, NO_AMOUNT }

    data class Success(
        val source: String?,
        val amount: Double,
        val merchant: String?,
        val time: String?,
        val raw: String
    ) : ParseResult()

    data class Failure(val reason: String, val kind: FailKind) : ParseResult()

    val ok: Boolean get() = this is Success
}

/** classify() 结果: via ∈ learned / default / unknown */
data class ClassifyResult(
    val category: Category,
    val via: String
)
