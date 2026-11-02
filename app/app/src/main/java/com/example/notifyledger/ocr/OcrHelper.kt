package com.example.notifyledger.ocr

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.example.notifyledger.parser.PayNotifyParser

object OcrHelper {
    /** 本地 OCR 引擎不可用(缺 GMS)的哨兵文本, 上层据此提示需真机/GMS 环境 */
    private const val ERROR_UNAVAILABLE = "__ocr_unavailable__"

    /** 本地 OCR 引擎不可用(缺 GMS)的哨兵文本, 上层据此提示需真机/GMS 环境 */


    /** 识别一张截图, 返回 保留入账的 判断结果 列表(不含放弃项)。
     *  失败/异常(例如模拟器缺 ML Kit 运行时依赖)返回空列表, 不抛出, 由上层按"未识别到支付信息"处理并在界面提示。 */
    suspend fun recognizeAndJudge(
        context: Context,
        imageUri: Uri,
        learnedRules: List<com.example.notifyledger.model.LearnedRule>
    ): List<PayNotifyParser.JudgeResult>? {
        val raw = recognize(context, imageUri)
        if (raw == ERROR_UNAVAILABLE) {
            Log.w("OcrHelper", "OCR 引擎不可用(模拟器缺 GMS), 需真机或带 Google Play 的镜像")
            return null
        }
        if (raw.isBlank()) {
            Log.w("OcrHelper", "recognize() 返回空文本, 不入账 (非支付截图)")
            return emptyList()
        }
        val cleaned = PayNotifyParser.cleanOcrText(raw)
        Log.i("OcrHelper", "OCR 完成: 原文 " + raw.length + " 字符, 清洗后 " + cleaned.length + " 字符 (原文内容按隐私约定不落日志)")
        val segs = PayNotifyParser.splitTransactions(cleaned)
        val segments = if (segs.isNotEmpty()) segs else listOf(cleaned)
        Log.i("OcrHelper", "拆分出 " + segments.size + " 个交易段")
        val keeps = mutableListOf<PayNotifyParser.JudgeResult>()
        val discards = mutableListOf<PayNotifyParser.JudgeResult>()
        segments.forEach { seg ->
            val v = PayNotifyParser.judge(seg, learnedRules)
            if (v.verdict == "keep") keeps += v else discards += v
        }
        Log.i("OcrHelper", "保留 " + keeps.size + " 笔, 丢弃 " + discards.size + " 笔")
        return keeps
    }

    /** 识别一张截图为纯文本(中文模型). 失败返回空串(不抛异常, 便于界面统一提示"未识别到支付信息")。 */
    suspend fun recognize(context: Context, imageUri: Uri): String =
        try {
            val options = ChineseTextRecognizerOptions.Builder().build()
            Log.i("OcrHelper", "已创建中文 TextRecognizer 客户端")
            val recognizer: TextRecognizer = TextRecognition.getClient(options)
            val image = InputImage.fromFilePath(context, imageUri)
            val text = Tasks.await(recognizer.process(image)).text
            recognizer.close()
            Log.i("OcrHelper", "OCR 完成, 识别到 " + text.length + " 字符")
            text
        } catch (e: Throwable) {
            Log.e("OcrHelper", "OCR 引擎不可用(缺 GMS/ML Kit), 本次跳过: " + e.message, e)
            ERROR_UNAVAILABLE
        }
}
