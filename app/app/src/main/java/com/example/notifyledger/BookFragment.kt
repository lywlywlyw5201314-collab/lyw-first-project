package com.example.notifyledger

import android.content.Context
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.notifyledger.model.LedgerEntry
import com.example.notifyledger.judge.JudgeChain
import com.example.notifyledger.judge.Verdict
import com.example.notifyledger.ocr.OcrHelper
import com.example.notifyledger.parser.PayNotifyParser
import com.example.notifyledger.util.Money
import com.example.notifyledger.util.TimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 记账页(评审 §2.6 自 MainActivity 拆出): 粘贴解析/确认入账/手动录入/截图 OCR。
 * 测试后门(原 MainActivity L261–281)原样迁移: [hostResume] 由宿主 onResume 调用, 语义不变。
 */
class BookFragment : Fragment() {

    private val viewModel: BookViewModel by viewModels()
    private val uiScope = CoroutineScope(Dispatchers.Main)

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            tvShotNote.text = "识别中... 本地 OCR 处理截图, 不上传图片"
            uiScope.launch {
                val results = withContext(Dispatchers.IO) {
                    OcrHelper.recognizeAndJudge(requireContext(), uri, viewModel.repo.learnedRules())
                }
                if (results == null) {
                    tvShotNote.text = "本机 OCR 引擎不可用(模拟器缺 Google 组件), 截图识别需在真机或带 GMS 的镜像运行"
                } else if (results.isEmpty()) {
                    tvShotNote.text = "截图中未识别到可入账的支付信息(非支付内容已丢弃, 不保存)"
                } else {
                    var count = 0
                    results.forEach { v ->
                        val entry = LedgerEntry(
                            day = TimeUtil.todayDay(),
                            source = v.source,
                            amount = v.amount ?: 0.0,
                            merchant = v.merchant,
                            category = v.category ?: "其他",
                            raw = "截图OCR: " + (v.raw ?: "")
                        )
                        viewModel.repo.insertEntry(entry)
                        count = count + 1
                    }
                    tvShotNote.text = "截图识别入账 " + count + " 笔(非支付内容已丢弃)"
                    (requireActivity() as MainActivity).refreshAll()
                }
            }
        }
    }

    private lateinit var etPaste: EditText
    private lateinit var etManualAmount: EditText
    private lateinit var etManualMerchant: EditText
    private lateinit var tvPasteNote: TextView
    private lateinit var tvShotNote: TextView
    private var viewsReady = false

    private val pendingKeeps = mutableListOf<PayNotifyParser.JudgeResult>()
    private val pendingDiscards = mutableListOf<Pair<String, String>>()
    private var confirmIdx = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_book, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        etPaste = view.findViewById(R.id.etPaste)
        etManualAmount = view.findViewById(R.id.etManualAmount)
        etManualMerchant = view.findViewById(R.id.etManualMerchant)
        tvPasteNote = view.findViewById(R.id.tvPasteNote)
        tvShotNote = view.findViewById(R.id.tvShotNote)

        view.findViewById<Button>(R.id.btnParse).setOnClickListener { onSmartParse() }
        view.findViewById<Button>(R.id.btnManualAdd).setOnClickListener { onManualAdd() }
        view.findViewById<Button>(R.id.btnPickScreenshot).setOnClickListener { pickImage.launch("image/*") }

        viewsReady = true
    }

    /** 宿主 onResume 时调用: 测试后门载入路径 + OCR 提示(原 MainActivity 语义不变) */
    fun hostResume() {
        if (!viewsReady) return
        maybeLoadTestPasteFromIntent()
        uiScope.launch {
            withContext(Dispatchers.IO) { maybeShowTestOcrNote() }
        }
    }

    /* ============ 测试后门: 由 TestCommandReceiver 广播驱动(仅模拟器首版验证用) ============ */

    private fun maybeLoadTestPasteFromIntent() {
        var text = requireActivity().intent?.getStringExtra(TestCommandReceiver.EXTRA_TEST_PASTE_TEXT) ?: ""
        if (text.isEmpty()) {
            val b64 = requireActivity().intent?.getStringExtra("test_paste_text_b64")
            if (!b64.isNullOrEmpty()) {
                text = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            }
        }
        if (text.isEmpty()) return
        etPaste.setText(text)
        tvPasteNote.text = "已载入支付通知, 点「解析并记账」继续"
        tvPasteNote.visibility = View.VISIBLE
    }

    private suspend fun maybeShowTestOcrNote() {
        val prefs = requireContext().getSharedPreferences("test_prefs", Context.MODE_PRIVATE)
        val note = prefs.getString("ocr_result", null) ?: return
        prefs.edit().remove("ocr_result").apply()
        tvShotNote.text = note
        tvShotNote.visibility = View.VISIBLE
    }

    /* ============ 记账: 粘贴 → 判断层 → 确认弹层 ============ */

    private fun onSmartParse() {
        if (!viewsReady) return
        val text = etPaste.text.toString().trim()
        if (text.isEmpty()) {
            tvPasteNote.text = "请输入或粘贴内容"
            tvPasteNote.visibility = View.VISIBLE
            return
        }
        tvPasteNote.visibility = View.GONE
        val segs = PayNotifyParser.splitTransactions(text)
        val rules = viewModel.repo.learnedRules()
        pendingKeeps.clear()
        pendingDiscards.clear()
        val ignoreRules = rules.filter { it.merchant.startsWith("@ignore:") }.map { it.merchant.removePrefix("@ignore:") }
        (if (segs.isNotEmpty()) segs else listOf(text)).forEach { seg ->
            // §2.6: 粘贴路径也改走与通知/OCR 同一套 JudgeChain(忽略规则优先, 本地规则判定),
            // 三入口判定逻辑收口在判断层, 不再各自内联
            when (val v = viewModel.chain.judge(seg, null, ignoreRules)) {
                is Verdict.Keep -> pendingKeeps.add(
                    PayNotifyParser.JudgeResult(
                        verdict = "keep",
                        amount = v.entry.amount,
                        merchant = v.entry.merchant,
                        source = v.entry.source,
                        time = null,
                        category = v.entry.category,
                        via = v.via,
                        raw = v.entry.raw
                    )
                )
                is Verdict.Discard -> pendingDiscards.add(seg to v.reason)
                Verdict.Abstain -> pendingDiscards.add(seg to JudgeChain.ABSTAIN_ALL_REASON)
            }
        }
        if (pendingKeeps.isEmpty()) {
            tvPasteNote.text = "未识别到可入账的支付信息: " + (pendingDiscards.firstOrNull()?.second ?: "非支付")
            tvPasteNote.visibility = View.VISIBLE
            return
        }
        confirmIdx = 0
        showConfirmSheet()
    }

    private fun showConfirmSheet() {
        val e = pendingKeeps[confirmIdx]
        // e.category 已是中文 label(Cr.category.label), 直接展示; 不再走英文 CAT_KEYS 索引(该索引恒 -1 → 误显"餐饮")
        val catLabel = e.category ?: "其他"
        AlertDialog.Builder(requireContext())
            .setTitle("确认入账")
            .setMessage(
                "来源: " + (e.source ?: "未知") + "\n" +
                "金额: " + Money.fmt(e.amount ?: 0.0) + "\n" +
                "商户: " + (e.merchant ?: "未识别") + "\n" +
                "分类: " + catLabel + " (" + (e.via ?: "rule") + ")"
            )
            .setPositiveButton("保存") { _, _ ->
                confirmCurrent()
            }
            .setNegativeButton("下一条/放弃") { _, _ ->
                confirmIdx = confirmIdx + 1
                if (confirmIdx < pendingKeeps.size) {
                    showConfirmSheet()
                } else {
                    tvPasteNote.text = "已入账 " + pendingKeeps.size + " 笔, 忽略 " + pendingDiscards.size + " 条"
                    tvPasteNote.visibility = View.VISIBLE
                    etPaste.setText("")
                    (requireActivity() as MainActivity).refreshAll()
                }
            }
            .show()
    }

    private fun confirmCurrent() {
        val v = pendingKeeps[confirmIdx]
        val entry = LedgerEntry(
            day = TimeUtil.todayDay(),
            source = v.source,
            amount = v.amount ?: 0.0,
            merchant = v.merchant,
            category = v.category ?: "其他",
            raw = v.raw ?: ""
        )
        viewModel.repo.insertEntry(entry)
        confirmIdx = confirmIdx + 1
        if (confirmIdx < pendingKeeps.size) {
            showConfirmSheet()
        } else {
            tvPasteNote.text = "共入账 " + pendingKeeps.size + " 笔, 放弃 " + pendingDiscards.size + " 条"
            tvPasteNote.visibility = View.VISIBLE
            etPaste.setText("")
            (requireActivity() as MainActivity).refreshAll()
        }
    }

    /* ============ 手动录入 ============ */

    private fun onManualAdd() {
        if (!viewsReady) return
        val amount = etManualAmount.text.toString().trim().toDoubleOrNull()
        if (amount == null || amount <= 0.0) {
            Toast.makeText(requireContext(), "请输入有效金额", Toast.LENGTH_SHORT).show()
            return
        }
        val merchant = etManualMerchant.text.toString().trim()
        val cr = PayNotifyParser.classify(merchant.ifEmpty { null }, viewModel.repo.learnedRules())
        val entry = LedgerEntry(
            day = TimeUtil.todayDay(),
            source = null,
            amount = amount,
            merchant = merchant.ifEmpty { null },
            category = cr.category.label,
            raw = "手动录入"
        )
        viewModel.repo.insertEntry(entry)
        etManualAmount.setText("")
        etManualMerchant.setText("")
        Toast.makeText(requireContext(), "已入账 " + Money.fmt(amount) + " [" + cr.category.label + "]", Toast.LENGTH_LONG).show()
        (requireActivity() as MainActivity).refreshAll()
    }
}
