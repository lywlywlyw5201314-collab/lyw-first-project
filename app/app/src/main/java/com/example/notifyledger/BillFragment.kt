package com.example.notifyledger

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.notifyledger.model.LedgerEntry
import com.example.notifyledger.model.Category
import com.example.notifyledger.model.LearnedRule
import com.example.notifyledger.util.Money
import com.example.notifyledger.util.TimeUtil
import java.io.File
import java.io.FileOutputStream

/** 账单页(评审 §2.6 自 MainActivity 拆出): 流水筛选/CSV 导出/改分类/删除(学习闭环)。 */
class BillFragment : Fragment() {

    private val viewModel: BillViewModel by viewModels()
    private lateinit var adapter: EntryAdapter

    private lateinit var billSearch: EditText
    private lateinit var fCat: Spinner
    private lateinit var fTime: Spinner
    private lateinit var tvMonthTotal: TextView
    private lateinit var tvBillCount: TextView
    private var viewsReady = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_bill, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        billSearch = view.findViewById(R.id.billSearch)
        fCat = view.findViewById(R.id.fCat)
        fTime = view.findViewById(R.id.fTime)
        tvMonthTotal = view.findViewById(R.id.tvMonthTotal)
        tvBillCount = view.findViewById(R.id.tvBillCount)
        val rv = view.findViewById<RecyclerView>(R.id.rvEntries)
        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = EntryAdapter(::pickCategoryFor, ::deleteEntry)
        rv.adapter = adapter
        rv.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        billSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, b: Int) {}
            override fun afterTextChanged(s: Editable?) { (requireActivity() as MainActivity).refreshAll() }
        })
        view.findViewById<Button>(R.id.btnCsv).setOnClickListener { exportCsv() }
        fCat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { (requireActivity() as MainActivity).refreshAll() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        fTime.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { (requireActivity() as MainActivity).refreshAll() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        viewsReady = true
    }

    /** 刷新账单页视图(原 MainActivity.reload() 的账单部分: 统计卡 + 筛选 + 流水列表) */
    fun refresh() {
        if (!viewsReady) return
        val q = billSearch.text.toString().trim()
        val _today = TimeUtil.todayDay()
        tvMonthTotal.text = Money.fmt(viewModel.repo.sumBetween(TimeUtil.firstOfMonth(), _today))
        val all = viewModel.repo.allEntries()
        val catLabel = if (fCat.selectedItemPosition > 0) Category.Companion.CAT_LABELS[fCat.selectedItemPosition - 1] else null
        val timeCut = billTimeCutoff(fTime.selectedItemPosition)
        val filtered = all.filter { e ->
            val okSearch = q.isEmpty() || (e.merchant ?: "").contains(q, true) || (e.raw ?: "").contains(q, true)
            val okCat = catLabel == null || (e.category ?: "其他") == catLabel
            val okTime = timeCut == null || (e.day ?: "") >= timeCut
            okSearch && okCat && okTime
        }
        adapter.entries = filtered
        val spend = filtered.sumOf { it.amount }
        val cnt = filtered.size
        tvMonthTotal.text = Money.fmt(spend)
        tvBillCount.text = "共 $cnt 笔"
    }

    /* ============ 账本: 学习闭环 / 删除 ============ */

    private fun pickCategoryFor(entry: LedgerEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle("把「" + (entry.merchant ?: "未识别商户") + "」归类到?")
            .setItems(Category.Companion.CAT_LABELS.toTypedArray()) { _, which ->
                val labels = Category.Companion.CAT_LABELS
                val cat = Category.fromLabel(labels[which])
                viewModel.repo.updateEntryCategory(entry.id, cat.label)
                entry.merchant?.let { viewModel.repo.upsertLearnedRule(LearnedRule(it, cat)) }
                Toast.makeText(requireContext(), "已改为[" + cat.label + "]; 该商户以后自动归入此类", Toast.LENGTH_LONG).show()
                (requireActivity() as MainActivity).refreshAll()
            }
            .show()
    }

    private fun deleteEntry(entry: LedgerEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除这笔记录?")
            .setMessage(Money.fmt(entry.amount) + " " + (entry.merchant ?: "") + " [" + entry.category + "]")
            .setPositiveButton("删除") { _, _ ->
                viewModel.repo.deleteEntry(entry.id)
                (requireActivity() as MainActivity).refreshAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /* ============ CSV 导出 ============ */

    private fun exportCsv() {
        val entries = viewModel.repo.allEntries()
        if (entries.isEmpty()) {
            Toast.makeText(requireContext(), "无数据可导出", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        sb.append("id,day,source,amount,merchant,category,raw\n")
        for (e in entries) {
            sb.append(e.id).append(',')
              .append(e.day).append(',')
              .append(e.source ?: "").append(',')
              .append(Money.fmt(e.amount)).append(',')
              .append(e.merchant ?: "").append(',')
              .append(e.category).append(',')
              .append((e.raw ?: "").replace("\n", " ")).append('\n')
        }
        try {
            val ctx = requireContext()
            val f = File(ctx.externalCacheDir, "notifyledger_export_" + System.currentTimeMillis() + ".csv")
            FileOutputStream(f).use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
            Toast.makeText(ctx, "已导出: " + f.absolutePath, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "导出失败: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    /** 时间筛选下拉 → 起始日期字符串(null=全部) */
    private fun billTimeCutoff(pos: Int): String? {
        val t = TimeUtil.todayDay()
        val d = java.time.LocalDate.parse(t)
        return when (pos) {
            0 -> t // 今天
            1 -> d.minusDays(6).toString() // 近7天(含今天)
            2 -> d.minusDays(29).toString() // 近30天
            3 -> d.minusDays(59).toString() // 近60天
            4 -> d.minusMonths(6).toString() // 近半年
            5 -> d.minusYears(1).toString() // 近1年
            else -> null // 全部
        }
    }
}
