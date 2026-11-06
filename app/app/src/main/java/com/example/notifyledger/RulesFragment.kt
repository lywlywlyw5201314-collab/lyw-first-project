package com.example.notifyledger

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.notifyledger.model.LedgerEntry
import com.example.notifyledger.model.Category
import com.example.notifyledger.model.LearnedRule
import com.example.notifyledger.util.Money
import java.util.LinkedHashMap

/** 规则页(评审 §2.6 自 MainActivity 拆出): 新建规则 + 已识别支付信息(逐条) + 丢弃学习。 */
class RulesFragment : Fragment() {

    private val viewModel: RulesViewModel by viewModels()

    private lateinit var ruleAddCard: View
    private lateinit var spRuleType: Spinner
    private lateinit var etRuleMerchant: EditText
    private lateinit var spRuleCat: Spinner
    private lateinit var llPaidItems: LinearLayout
    private lateinit var tvLearned: TextView
    private var viewsReady = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_rules, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ruleAddCard = view.findViewById(R.id.ruleAddCard)
        spRuleType = view.findViewById(R.id.spRuleType)
        etRuleMerchant = view.findViewById(R.id.etRuleMerchant)
        spRuleCat = view.findViewById(R.id.spRuleCat)
        llPaidItems = view.findViewById(R.id.llPaidItems)
        tvLearned = view.findViewById(R.id.tvLearned)

        view.findViewById<Button>(R.id.btnRuleSave).setOnClickListener { saveRule() }
        ruleAddCard.setOnClickListener { etRuleMerchant.requestFocus() }
        spRuleType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                spRuleCat.visibility = if (pos == 1) View.GONE else View.VISIBLE
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        viewsReady = true
    }

    /** 刷新规则页(原 MainActivity.reloadRulesPage(): 学习规则汇总 + 已识别支付信息) */
    fun refresh() {
        if (!viewsReady) return
        renderPaidItems(viewModel.repo.allEntries())
        tvLearned.text = buildLearnedText()
    }

    private fun saveRule() {
        if (!viewsReady) return
        val m = etRuleMerchant.text.toString().trim()
        if (m.isEmpty()) {
            Toast.makeText(requireContext(), "请填写商户关键词", Toast.LENGTH_SHORT).show()
            return
        }
        val typeIgnore = spRuleType.selectedItemPosition == 1
        if (Regex("验证码|\\d{4,}").containsMatchIn(m) && m.length <= 8) {
            Toast.makeText(requireContext(), "关键词疑似验证码/编号，已忽略保存", Toast.LENGTH_LONG).show()
            return
        }
        if (typeIgnore) {
            viewModel.repo.upsertLearnedRule(LearnedRule("@ignore:" + m, Category.OTHER))
            etRuleMerchant.setText("")
            Toast.makeText(requireContext(), "已添加忽略规则: " + m, Toast.LENGTH_SHORT).show()
        } else {
            val labels = Category.Companion.CAT_LABELS
            val catLabel = labels[spRuleCat.selectedItemPosition.coerceIn(0, labels.size - 1)]
            val cat = Category.fromLabel(catLabel)
            viewModel.repo.upsertLearnedRule(LearnedRule(m, cat))
            Toast.makeText(requireContext(), "已保存: " + m + " → " + catLabel, Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    /* ============ 规则页: 已识别的支付信息(逐条) + 丢弃学习 ============ */

    /** 该条目的信息签名: 商户名; 未识别商户时退回通知原文(去首尾空白) */
    private fun sigOf(e: LedgerEntry): String =
        e.merchant?.takeIf { it.isNotBlank() } ?: e.raw?.trim().orEmpty()

    private fun renderPaidItems(all: List<LedgerEntry>) {
        llPaidItems.removeAllViews()
        // 只列出弹窗读到的支付条目(手动录入的 source 为 null, 不上列表)
        // 同一「商户(或原文签名)+金额」合并为一行
        val ctx = requireContext()
        val groups = LinkedHashMap<Pair<String, Double>, MutableList<LedgerEntry>>()
        for (e in all) {
            if (e.source == null) continue
            val sig = sigOf(e)
            if (sig.isEmpty()) continue
            groups.getOrPut(sig to e.amount) { mutableListOf() }.add(e)
        }
        if (groups.isEmpty()) {
            val t = TextView(ctx)
            t.text = "暂无(在弹窗里确认支付后, 这里会逐条列出)"
            t.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            t.setTextSize(13f)
            llPaidItems.addView(t)
            return
        }
        val head = TextView(ctx)
        head.text = "共 " + groups.size + " 条"
        head.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        head.setTextSize(13f)
        llPaidItems.addView(head)
        val pad = (12 * resources.displayMetrics.density).toInt()
        for ((key, items) in groups) {
            val row = TextView(ctx)
            val label = if (key.first.length > 20) key.first.take(20) + "…" else key.first
            row.text = label + "  " + Money.fmt(key.second) + "  ×" + items.size
            row.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            row.setTextSize(14f)
            row.setPadding(0, pad, 0, pad)
            row.isClickable = true
            row.setOnClickListener { pickPaidItemAction(key.first, items) }
            llPaidItems.addView(row)
        }
    }

    /** 点按某条支付信息 → 改类型 / 丢弃此信息 / 删除此记录 */
    private fun pickPaidItemAction(sig: String, items: List<LedgerEntry>) {
        val label = if (sig.length > 24) sig.take(24) + "…" else sig
        AlertDialog.Builder(requireContext())
            .setTitle("如何处理「" + label + "」?")
            .setItems(arrayOf("改类型", "丢弃此信息", "删除此记录")) { _, which ->
                when (which) {
                    0 -> pickCategoryForGroup(sig, items)
                    1 -> discardSigItems(sig, items)
                    else -> deleteSigItems(items)
                }
            }
            .show()
    }

    /** 改类型: 整组条目改分类 + 学习一条规则(同商户同分类), 保证下一次同信息自动归入此类 */
    private fun pickCategoryForGroup(sig: String, items: List<LedgerEntry>) {
        AlertDialog.Builder(requireContext())
            .setTitle("「" + sig + "」归入哪类?")
            .setItems(Category.Companion.CAT_LABELS.toTypedArray()) { _, which ->
                val labels = Category.Companion.CAT_LABELS
                val cat = Category.fromLabel(labels[which])
                items.forEach { viewModel.repo.updateEntryCategory(it.id, cat.label) }
                // 只有有商户名的条目才学成规则: 未识别商户没有可匹配的商户名, 原文签名不进 learned_rules
                val m = items.first().merchant
                if (m.isNullOrBlank() == false) {
                    viewModel.repo.upsertLearnedRule(LearnedRule(m, cat))
                }
                Toast.makeText(requireContext(), "已改为[" + cat.label + "]; 该信息以后自动归入此类", Toast.LENGTH_LONG).show()
                (requireActivity() as MainActivity).refreshAll()
            }
            .show()
    }

    /** 丢弃此信息: 学一条忽略规则(@ignore: 前缀) + 删除本组全部记录; 同签名信息永不再被保留 */
    private fun discardSigItems(sig: String, items: List<LedgerEntry>) {
        AlertDialog.Builder(requireContext())
            .setTitle("丢弃「" + (if (sig.length > 24) sig.take(24) + "…" else sig) + "」?")
            .setMessage("两件事会同时发生:\n" +
                "1. 学一条忽略规则: 以后弹窗里再出现这条信息, 直接丢弃, 不再记录;\n" +
                "2. 删除现有的 " + items.size + " 条对应记录。")
            .setPositiveButton("丢弃") { _, _ ->
                viewModel.repo.upsertLearnedRule(LearnedRule("@ignore:" + sig, Category.OTHER))
                items.forEach { viewModel.repo.deleteEntry(it.id) }
                Toast.makeText(requireContext(), "已丢弃: 这条信息以后不再记录", Toast.LENGTH_LONG).show()
                (requireActivity() as MainActivity).refreshAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 删除此记录: 只删账本记录, 不动规则 —— 同信息以后还会被记下来 */
    private fun deleteSigItems(items: List<LedgerEntry>) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除这 " + items.size + " 条记录?")
            .setMessage("只是从账本里删掉, 不学忽略规则: 同样的弹窗信息以后还会被记下来。")
            .setPositiveButton("删除") { _, _ ->
                items.forEach { viewModel.repo.deleteEntry(it.id) }
                Toast.makeText(requireContext(), "已删除 " + items.size + " 条记录", Toast.LENGTH_SHORT).show()
                (requireActivity() as MainActivity).refreshAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildLearnedText(): String {
        val rules = viewModel.repo.learnedRules()
        if (rules.isEmpty()) return "暂无规则"
        val normal = rules.filter { !it.merchant.startsWith("@ignore:") }
        val ignores = rules.filter { it.merchant.startsWith("@ignore:") }
        val sb = StringBuilder()
        if (normal.isNotEmpty()) {
            sb.append("自动分类:\n")
            sb.append(normal.joinToString("\n") { r -> "  " + r.merchant + " → " + r.category.label })
        }
        if (ignores.isNotEmpty()) {
            sb.append("\n\n忽略通知(不入账):\n")
            sb.append(ignores.joinToString("\n") { r -> "  " + r.merchant.removePrefix("@ignore:") })
        }
        return sb.toString()
    }
}
