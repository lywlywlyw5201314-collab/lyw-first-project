package com.example.notifyledger.ocr

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.example.notifyledger.judge.JudgeChain
import com.example.notifyledger.judge.JudgeProvider
import com.example.notifyledger.judge.Verdict
import com.example.notifyledger.parser.PayNotifyParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OcrHelper {
    /** 本地 OCR 引擎不可用(缺 GMS)的哨兵文本, 上层据此提示需真机/GMS 环境 */
    private const val ERROR_UNAVAILABLE = "__ocr_unavailable__"

    /** 本地 OCR 引擎不可用(缺 GMS)的哨兵文本, 上层据此提示需真机/GMS 环境 */


    /** 识别一张截图, 返回 保留入账的 判断结果 列表(不含放弃项)。
     *  引擎不可用(例如模拟器缺 GMS/ML Kit 运行时依赖)返回 null, 由上层按"本地 OCR 引擎不可用"提示;
     *  未识别到文本(非支付截图)返回空列表。不抛出, 由上层按"未识别到支付信息"处理并在界面提示。
     *  §2.3: 判断改走 JudgeProvider.chain(context) 生成的 JudgeChain(本地规则从
     *  LedgerRepository 实时读取), 入参 learnedRules 仅作调用方快照提示, 不参与判断。 */
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
        val chain = JudgeProvider.chain(context)
        segments.forEach { seg ->
            val v = when (val verdict = chain.judge(seg, source = null)) {
                is Verdict.Keep -> PayNotifyParser.JudgeResult(
                    verdict = "keep",
                    amount = verdict.entry.amount,
                    merchant = verdict.entry.merchant,
                    source = verdict.entry.source,
                    time = null,
                    category = verdict.entry.category,
                    via = verdict.via,
                    raw = verdict.entry.raw
                )
                is Verdict.Discard -> PayNotifyParser.JudgeResult(
                    verdict = "discard",
                    reason = verdict.reason,
                    via = verdict.via,
                    raw = verdict.raw
                )
                else -> PayNotifyParser.JudgeResult(
                    verdict = "discard",
                    reason = JudgeChain.ABSTAIN_ALL_REASON,
                    via = "chain",
                    raw = seg
                )
            }
            if (v.verdict == "keep") keeps += v else discards += v
        }
        Log.i("OcrHelper", "保留 " + keeps.size + " 笔, 丢弃 " + discards.size + " 笔")
        return keeps
    }

    /** 识别一张截图为纯文本(中文模型). 引擎不可用返回哨兵文本;
     *  不向调用方抛出异常(便于界面统一提示"未识别到支付信息")。协程被取消时重抛 CancellationException。 */
    suspend fun recognize(context: Context, imageUri: Uri): String =
        try {
            val options = ChineseTextRecognizerOptions.Builder().build()
            Log.i("OcrHelper", "已创建中文 TextRecognizer 客户端")
            val recognizer: TextRecognizer = TextRecognition.getClient(options)
            val image = InputImage.fromFilePath(context, imageUri)
            val text = recognizer.process(image).awaitTask().text
            recognizer.close()
            Log.i("OcrHelper", "OCR 完成, 识别到 " + text.length + " 字符")
            text
        } catch (e: CancellationException) {
            // 协程取消是结构化控制流而非引擎故障: 原样重抛, 防止上层误判为「已正常完成」(§2.2)
            throw e
        } catch (e: Exception) {
            // GMS/ML Kit 缺失或运行时异常: 降级为哨兵, 由上层提示需真机或带 Google Play 的镜像。
            // §2.2: catch 范围由 Throwable 收窄为 Exception(OOM 等真实崩溃不再被掩盖为「引擎不可用」);
            // 仅记录异常类名, e.message 可能含敏感内容, 不落日志(隐私红线)。
            // 偏差点: play-services-tasks 18.0.2 无顶层 TaskCancellationException; 任务取消
            // 不走 onFailure 通道(走 addOnCanceledListener), 协程取消路径由上面的 CancellationException 覆盖。
            Log.e("OcrHelper", "OCR 引擎不可用(缺 GMS/ML Kit), 本次跳过: " + e.javaClass.name)
            ERROR_UNAVAILABLE
        }
}

/**
 * [Task] 的非阻塞协程扩展(§2.2 修复): 挂起直至 Task 完成并返回结果。
 * 偏差点: play-services-tasks 18.x(18.0.2/18.2.0 javap 验证)未提供内置协程版 awaitTask,
 * 且 `Task` 无 `cancel()` 公共方法、`TaskCancellationException` 类不存在。
 * 故以 [suspendCancellableCoroutine] 桥接 onSuccess/onFailure:
 * - 成功 → resume 结果;
 * - 失败 → resume 异常, 由 `recognize` 的 `catch (Exception)` 统一降级为哨兵(仅类名, 不落原文/e.message);
 * - 协程被取消 → kotlinx 在挂起点自动重抛 CancellationException(`recognize` 中原样重抛);
 *   此时底层 MLKit 任务无法再取消(18.x 无 `Task.cancel()`), 自行跑完即可, 迟到的
 *   `resume` 对已取消 continuation 是 no-op, 不会悬挂或二次 resume。
 * 调用方需确保运行于 Dispatchers.IO(现调用点 MainActivity 已在 withContext(Dispatchers.IO) 内)。
 */
private suspend fun <T> Task<T>.awaitTask(): T =
    suspendCancellableCoroutine { c ->
        addOnSuccessListener(object : OnSuccessListener<T> {
            override fun onSuccess(result: T) {
                c.resume(result)
            }
        })
        addOnFailureListener(object : OnFailureListener {
            override fun onFailure(e: Exception) {
                c.resumeWithException(e)
            }
        })
    }
