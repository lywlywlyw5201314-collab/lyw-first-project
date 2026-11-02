package com.example.notifyledger

import com.example.notifyledger.model.Category
import com.example.notifyledger.model.ParseResult
import com.example.notifyledger.parser.PayNotifyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PayNotifyParser JUnit 单测 —— 与 web 端 test/parser.test.js 完全同构:
 *
 *  [1] 内置 selfTest() 9 例(与 parser.js selfTest() 字节级对齐的 9 例)
 *  [2] 关键字段精确断言(镜像 JS 组 [2], 8 项)
 *  [3] 应丢弃的场景(镜像 JS 组 [3], 6 项)
 *  [4] 金额/时间提取(镜像 JS 组 [4], 6 项)
 *  [5] 来源识别(镜像 JS 组 [5], 4 项)
 *
 * 任何一侧改了解析行为, 两侧测试必须同步修改(单一事实来源 = web/parser.js)。
 */
class PayNotifyParserTest {

        private val P = PayNotifyParser

    /* ---------- 1) 内置自检 9 例 ---------- */

    @Test
    fun selfTest_allCasesPass() {
        val results = P.selfTest()
        assertEquals(9, results.size)
        results.forEach { r ->
            assertTrue("自检用例失败: ${r.name}\n${r.detail}", r.ok)
        }
    }

    /* ---------- 2) 关键字段精确断言 ---------- */

    @Test
    fun wechatConsumption_fields() {
        val p = P.parse("微信支付 支付成功 ¥23.50 XX便利店 12:47")
        assertTrue(p is ParseResult.Success)
        val s = p as ParseResult.Success
        assertEquals("微信", s.source)
        assertEquals(23.5, s.amount, 0.0001)
        assertEquals("XX便利店", s.merchant)
        assertEquals("12:47", s.time)
    }
    @Test
    fun wechatPaySuccess_merchantClean() {
        val p = P.parse("微信支付成功 ¥12.30 瑞幸咖啡 13:05")
        assertTrue(p is ParseResult.Success)
        val s = p as ParseResult.Success
        assertEquals("瑞幸咖啡", s.merchant)
        assertEquals(12.3, s.amount, 0.0001)
    }

    @Test
    fun convenienceStore_toShopping() {
        assertEquals(Category.SHOPPING, P.classify("XX便利店", emptyList()).category)
    }

    @Test
    fun meituanToDining_default() {
        assertEquals(Category.DINING, P.classify("美团外卖", emptyList()).category)
    }

    @Test
    fun learnedRuleOverridesDefault() {
        val rules = P.learn("美团外卖", Category.SHOPPING, emptyList())
        val cr = P.classify("美团外卖", rules)
        assertEquals(Category.SHOPPING, cr.category)
        assertEquals("learned", cr.via)
    }

    @Test
    fun learnIsUpsert_sameMerchantKeepsOne() {
        var rules = P.learn("美团外卖", Category.SHOPPING, emptyList())
        rules = P.learn("美团外卖", Category.DINING, rules)
        assertEquals(1, rules.size)
        assertEquals(Category.DINING, rules[0].category)
    }

    @Test
    fun learnIsPure_doesNotMutateInput() {
        val before = listOf(com.example.notifyledger.model.LearnedRule("a", Category.DINING))
        val after = P.learn("b", Category.SHOPPING, before)
        assertEquals(1, before.size)
        assertEquals(2, after.size)
    }

    @Test
    fun classifyCaseInsensitive() {
        val rules = P.learn("DI DI", Category.TRANSPORT, emptyList())
        assertEquals("learned", P.classify("di di", rules).via)
    }

    /* ---------- 3) 应丢弃的场景 ---------- */

    @Test
    fun income_friendTransfer() {
        assertFalse(P.parse("微信 收到好友转账 ¥100.00").ok)
    }

    @Test
    fun refund() {
        assertFalse(P.parse("支付宝 退款 15.00元").ok)
    }

    @Test
    fun notPayment_systemUpdate() {
        assertFalse(P.parse("系统更新完成").ok)
    }

    @Test
    fun notPayment_voiceCall() {
        assertFalse(P.parse("微信 语音通话 结束").ok)
    }

    @Test
    fun missingAmount() {
        val p = P.parse("支付宝 支付成功 XX商户")
        assertFalse(p.ok)
        assertEquals("未识别到金额", (p as ParseResult.Failure).reason)
    }

    @Test
    fun emptyInput() {
        assertFalse(P.parse("").ok)
        assertFalse(P.parse(null).ok)
    }

    /* ---------- 4) 金额/时间提取 ---------- */

    @Test
    fun discountAmountNotPrimary() {
        val p = P.parse("支付宝 向 A店 支付 99元 优惠 9.90元")
        assertTrue(p is ParseResult.Success)
        assertEquals(99.0, (p as ParseResult.Success).amount, 0.0001)
    }

    @Test
    fun thousandsSeparator() {
        assertEquals(1234.56, P.extractAmount("¥1,234.56")!!, 0.0001)
    }

    @Test
    fun extractAmountNullWhenNone() {
        assertNull(P.extractAmount("今天消费愉快"))
    }

    @Test
    fun extractTime_hhmm() {
        assertEquals("12:47", P.extractTime("12:47"))
    }

    @Test
    fun extractTime_fullDate() {
        assertEquals("2026-10-20", P.extractTime("2026-10-20 订单完成"))
    }

    @Test
    fun extractTimeNullWhenNone() {
        assertNull(P.extractTime("今天消费"))
    }

    /* ---------- 5) 来源识别 ---------- */

    @Test
    fun source_wechat() {
        assertEquals("微信", P.detectSource("微信支付成功"))
    }

    @Test
    fun source_alipay() {
        assertEquals("支付宝", P.detectSource("支付宝到账"))
    }

    @Test
    fun source_unionpay() {
        assertEquals("银联", P.detectSource("银联 扣款成功 5.00元"))
    }

    @Test
    fun source_unknown() {
        assertNull(P.detectSource("滴滴出行 订单完成 12.50元"))
    }
}
