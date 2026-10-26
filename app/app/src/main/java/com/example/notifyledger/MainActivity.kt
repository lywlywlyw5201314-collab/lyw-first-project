package com.example.notifyledger

import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.notifyledger.data.LedgerRepository
import com.example.notifyledger.judge.JudgeChain
import com.example.notifyledger.judge.JudgeProvider
import com.example.notifyledger.judge.LlmConfig
import com.example.notifyledger.model.Category
import com.example.notifyledger.model.LedgerEntry
import com.example.notifyledger.model.LearnedRule
import com.example.notifyledger.ocr.OcrHelper
import com.example.notifyledger.parser.PayNotifyParser
import com.example.notifyledger.util.TimeUtil
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
class MainActivity : AppCompatActivity() {

    private val repo by lazy { LedgerRepository(applicationContext) }

    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val chain by lazy { JudgeProvider.chain(applicationContext) }
    private lateinit var adapter: EntryAdapter

    // 记账页
    private lateinit var etPaste: EditText
    private lateinit var etManualAmount: EditText
    private lateinit var etManualMerchant: EditText
    private lateinit var tvPasteNote: TextView
    private lateinit var tvShotNote: TextView

    // 账单页
    private lateinit var billSearch: EditText
    private lateinit var tvMonthTotal: TextView

    // 规则页
    private lateinit var etRuleMerchant: EditText
    private lateinit var spRuleCat: Spinner
    private lateinit var tvLearned: TextView

    // 分析页
    private lateinit var tvAnalytics: TextView
    private lateinit var tvListenerStatus: TextView
    private lateinit var tvLlmStatus: TextView
    private lateinit var tvSelfTestResult: TextView

    // 页面
    private lateinit var pageBook: ScrollView
    private lateinit var pageBill: ScrollView
    private lateinit var pageRules: ScrollView
    private lateinit var pageStats: ScrollView
    private lateinit var tabBar: LinearLayout

    // 确认入账弹层
    private var pendingKeeps = mutableListOf<PayNotifyParser.JudgeResult>()
    private var pendingDiscards = mutableListOf<Pair<String, String>>()
    private var confirmIdx = 0
    private var curCat = "other"

    private val CAT_LABELS = listOf("餐饮", "购物", "交通", "生活", "其他")
    private val CAT_KEYS = listOf("dining", "shopping", "transport", "life", "other")

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        tvShotNote.text = "识别中... 本地 OCR 处理截图, 不上传图片"
        uiScope.launch {
            val results = withContext(Dispatchers.IO) {
                OcrHelper.recognizeAndJudge(this@MainActivity, uri, repo.learnedRules())
            }
            if (results.isEmpty()) {
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
                    repo.insertEntry(entry)
                    count = count + 1
                }
                tvShotNote.text = "截图识别入账 " + count + " 笔(非支付内容已丢弃)"
                reload()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 控件绑定
        etPaste = findViewById(R.id.etPaste)
        etManualAmount = findViewById(R.id.etManualAmount)
        etManualMerchant = findViewById(R.id.etManualMerchant)
        tvPasteNote = findViewById(R.id.tvPasteNote)
        tvShotNote = findViewById(R.id.tvShotNote)
        billSearch = findViewById(R.id.billSearch)
        tvMonthTotal = findViewById(R.id.tvMonthTotal)
        etRuleMerchant = findViewById(R.id.etRuleMerchant)
        spRuleCat = findViewById(R.id.spRuleCat)
        tvLearned = findViewById(R.id.tvLearned)
        tvAnalytics = findViewById(R.id.tvAnalytics)
        tvListenerStatus = findViewById(R.id.tvListenerStatus)
        tvLlmStatus = findViewById(R.id.tvLlmStatus)
        tvSelfTestResult = findViewById(R.id.tvSelfTestResult)
        pageBook = findViewById(R.id.pageBook)
        pageBill = findViewById(R.id.pageBill)
        pageRules = findViewById(R.id.pageRules)
        pageStats = findViewById(R.id.pageStats)
        tabBar = findViewById(R.id.tabBar)

        val rv = findViewById<RecyclerView>(R.id.rvEntries)
        adapter = EntryAdapter(::pickCategoryFor, ::deleteEntry)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        // 导航
        findViewById<Button>(R.id.tabBook).setOnClickListener { goPage(0) }
        findViewById<Button>(R.id.tabBill).setOnClickListener { goPage(1) }
        findViewById<Button>(R.id.tabRules).setOnClickListener { goPage(2) }
        findViewById<Button>(R.id.tabStats).setOnClickListener { goPage(3) }

        // 记账页
        findViewById<Button>(R.id.btnParse).setOnClickListener { onSmartParse() }
        findViewById<Button>(R.id.btnManualAdd).setOnClickListener { onManualAdd() }
        findViewById<Button>(R.id.btnPickScreenshot).setOnClickListener { pickImage.launch("image/*") }

        // 账单页
        billSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, b: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { reload() }
        })
        findViewById<Button>(R.id.btnCsv).setOnClickListener { exportCsv() }

        // 规则页
        findViewById<Button>(R.id.btnRuleSave).setOnClickListener { saveRule() }

        // 分析页
        findViewById<Button>(R.id.btnOpenListenerSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnLlmSave).setOnClickListener { saveLlmConfig() }
        findViewById<Button>(R.id.btnSelfTest).setOnClickListener { runSelfTest() }

        requestPostNotificationPermission()
        goPage(0)
    }

    override fun onResume() {
        super.onResume()
        maybeLoadTestPasteFromIntent()
        uiScope.launch {
            withContext(Dispatchers.IO) { maybeShowTestOcrNote() }
        }
        reloadListenerStatus()
        reloadLlmStatus()
        reload()
    }

    private fun goPage(index: Int) {
        pageBook.visibility = if (index == 0) View.VISIBLE else View.GONE
        pageBill.visibility = if (index == 1) View.VISIBLE else View.GONE
        pageRules.visibility = if (index == 2) View.VISIBLE else View.GONE
        pageStats.visibility = if (index == 3) View.VISIBLE else View.GONE
        val tabs = listOf(
            findViewById<Button>(R.id.tabBook),
            findViewById<Button>(R.id.tabBill),
            findViewById<Button>(R.id.tabRules),
            findViewById<Button>(R.id.tabStats)
        )
        tabs.forEachIndexed { i, btn ->
            btn.setAllCaps(false)
            btn.setTextColor(
                if (i == index) 0xFF236B55.toInt() else 0xFF929B94.toInt()
            )
        }
        if (index == 1) reload()
        if (index == 2) reloadRulesPage()
        if (index == 3) reloadStatsPage()
    }

    /* ============ 测试后门: 由 TestCommandReceiver 广播驱动(仅模拟器首版验证用) ============ */

    private fun maybeLoadTestPasteFromIntent() {
        val text = intent?.getStringExtra(TestCommandReceiver.EXTRA_TEST_PASTE_TEXT) ?: return
        if (text.isEmpty()) return
        etPaste.setText(text)
        tvPasteNote.text = "已载入支付通知, 点「解析并记账」继续"
        tvPasteNote.visibility = View.VISIBLE
    }

    private suspend fun maybeShowTestOcrNote() {
        val prefs = getSharedPreferences("test_prefs", MODE_PRIVATE)
        val note = prefs.getString("ocr_result", null) ?: return
        prefs.edit().remove("ocr_result").apply()
        tvShotNote.text = note
        tvShotNote.visibility = View.VISIBLE
    }

    /* ============ 记账: 粘贴 → 判断层 → 确认弹层 ============ */

    private fun onSmartParse() {
        val text = etPaste.text.toString().trim()
        if (text.isEmpty()) {
            tvPasteNote.text = "请输入或粘贴内容"
            tvPasteNote.visibility = View.VISIBLE
            return
        }
        tvPasteNote.visibility = View.GONE
        val segs = PayNotifyParser.splitTransactions(text)
        val rules = repo.learnedRules()
        pendingKeeps.clear()
        pendingDiscards.clear()
        (if (segs.isNotEmpty()) segs else listOf(text)).forEach { seg ->
            val v = PayNotifyParser.judge(seg, rules)
            if (v.verdict == "keep") pendingKeeps.add(v)
            else pendingDiscards.add(seg to (v.reason ?: "非支付"))
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
        curCat = e.category ?: "other"
        val catLabel = CAT_LABELS[CAT_KEYS.indexOf(e.category ?: "other").coerceAtLeast(0)]
        AlertDialog.Builder(this)
            .setTitle("确认入账")
            .setMessage(
                "来源: " + (e.source ?: "未知") + "\n" +
                "金额: " + fmt(e.amount ?: 0.0) + "\n" +
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
                    reload()
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
        repo.insertEntry(entry)
        confirmIdx = confirmIdx + 1
        if (confirmIdx < pendingKeeps.size) {
            showConfirmSheet()
        } else {
            tvPasteNote.text = "共入账 " + pendingKeeps.size + " 笔, 放弃 " + pendingDiscards.size + " 条"
            tvPasteNote.visibility = View.VISIBLE
            etPaste.setText("")
            reload()
        }
    }

    /* ============ 手动录入 ============ */

    private fun onManualAdd() {
        val amount = etManualAmount.text.toString().trim().toDoubleOrNull()
        if (amount == null || amount <= 0.0) {
            Toast.makeText(this, "请输入有效金额", Toast.LENGTH_SHORT).show()
            return
        }
        val merchant = etManualMerchant.text.toString().trim()
        val cr = PayNotifyParser.classify(merchant.ifEmpty { null }, repo.learnedRules())
        val entry = LedgerEntry(
            day = TimeUtil.todayDay(),
            source = null,
            amount = amount,
            merchant = merchant.ifEmpty { null },
            category = cr.category.label,
            raw = "手动录入"
        )
        repo.insertEntry(entry)
        etManualAmount.setText("")
        etManualMerchant.setText("")
        Toast.makeText(this, "已入账 " + fmt(amount) + " [" + cr.category.label + "]", Toast.LENGTH_LONG).show()
        reload()
    }

    /* ============ 规则页 ============ */

    private fun reloadRulesPage() {
        tvLearned.text = buildLearnedText()
    }

    private fun saveRule() {
        val m = etRuleMerchant.text.toString().trim()
        if (m.isEmpty()) {
            Toast.makeText(this, "请输入商户名", Toast.LENGTH_SHORT).show()
            return
        }
        val catLabel = CAT_LABELS[spRuleCat.selectedItemPosition.coerceIn(0, CAT_LABELS.size - 1)]
        val cat = Category.fromLabel(catLabel)
        repo.upsertLearnedRule(LearnedRule(m, cat))
        etRuleMerchant.setText("")
        Toast.makeText(this, "已保存: " + m + " → " + catLabel, Toast.LENGTH_SHORT).show()
        reloadRulesPage()
    }

    /* ============ 分析页 ============ */

    private fun reloadStatsPage() {
        tvAnalytics.text = buildStats()
        reloadListenerStatus()
        reloadLlmStatus()
    }

    private fun reloadListenerStatus() {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        tvListenerStatus.text = if (enabled) {
            "已开启: 支付通知会自动识别入账, 其他信息自动忽略"
        } else {
            "未开启: 点「开启通知监听」授权, 通知功能才会生效"
        }
    }

    private fun reloadLlmStatus() {
        val cfg = LlmConfig.load(applicationContext)
        tvLlmStatus.text = if (cfg.isEnabled) {
            "智能识别: 已启用(模型 " + cfg.model + ")"
        } else {
            "智能识别: 未配置, 当前由本地规则自动判断"
        }
    }

    private fun saveLlmConfig() {
        val endpoint = findViewById<EditText>(R.id.etLlmEndpoint).text.toString().trim()
        val key = findViewById<EditText>(R.id.etLlmKey).text.toString().trim()
        val model = findViewById<EditText>(R.id.etLlmModel).text.toString().trim().ifEmpty { LlmConfig.DEFAULT_MODEL }
        val editor = getSharedPreferences(LlmConfig.PREFS, MODE_PRIVATE).edit()
        editor.putBoolean("enabled", key.isNotEmpty())
        editor.putString("endpoint", endpoint)
        editor.putString("apiKey", key)
        editor.putString("model", model)
        editor.apply()
        Toast.makeText(this, "AI 设置已保存", Toast.LENGTH_SHORT).show()
        reloadLlmStatus()
    }

    private fun runSelfTest() {
        val results = PayNotifyParser.selfTest()
        val passed = results.count { it.ok }
        val failed = results.count { !it.ok }
        val detail = results.joinToString("\n") { r -> (if (r.ok) "√" else "×") + " " + r.name + ": " + r.detail }
        tvSelfTestResult.text = "通过 " + passed + "/" + results.size + ", 失败 " + failed + "\n\n" + detail
        if (passed == results.size) {
            Toast.makeText(this, "全部自检通过 🎉", Toast.LENGTH_LONG).show()
        }
    }

    /* ============ 账本: 学习闭环 / 删除 ============ */

    private fun pickCategoryFor(entry: LedgerEntry) {
        AlertDialog.Builder(this)
            .setTitle("把「" + (entry.merchant ?: "未识别商户") + "」归类到?")
            .setItems(CAT_LABELS.toTypedArray()) { _, which ->
                val cat = Category.fromLabel(CAT_LABELS[which])
                repo.updateEntryCategory(entry.id, cat.label)
                entry.merchant?.let { repo.upsertLearnedRule(LearnedRule(it, cat)) }
                Toast.makeText(this, "已改为[" + cat.label + "]; 该商户以后自动归入此类", Toast.LENGTH_LONG).show()
                reload()
            }
            .show()
    }

    private fun deleteEntry(entry: LedgerEntry) {
        AlertDialog.Builder(this)
            .setTitle("删除这笔记录?")
            .setMessage(fmt(entry.amount) + " " + (entry.merchant ?: "") + " [" + entry.category + "]")
            .setPositiveButton("删除") { _, _ ->
                repo.deleteEntry(entry.id)
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /* ============ 统计 + 学习规则 ============ */

    private fun reload() {
        val q = billSearch.text.toString().trim()
        tvMonthTotal.text = fmt(repo.sumBetween(TimeUtil.firstOfMonth(), TimeUtil.todayDay()))
        val all = repo.allEntries()
        val filtered = if (q.isEmpty()) all else all.filter {
            (it.merchant ?: "").contains(q, true) || (it.raw ?: "").contains(q, true)
        }
        adapter.entries = filtered
        tvLearned.text = buildLearnedText()
        tvAnalytics.text = buildStats()
    }

    private fun buildStats(): String {
        val t = TimeUtil
        val today = t.todayDay()
        val week = repo.sumBetween(t.mondayOfToday(), today)
        val month = repo.sumBetween(t.firstOfMonth(), today)
        val byCat = repo.sumByCategory(t.firstOfMonth(), today)
        val daily = repo.dailyTotals(t.lastNDays(7))
        val sb = StringBuilder()
        sb.append("本周已花: ").append(fmt(week)).append('\n')
        sb.append("本月已花: ").append(fmt(month)).append('\n')
        sb.append("本月分类: ")
        if (byCat.isEmpty()) sb.append("(暂无)") else byCat.forEach { (cat, sum) -> sb.append(cat).append(" ").append(fmt(sum)).append("  ") }
        sb.append('\n').append("近 7 天: ")
        daily.forEach { (day, sum) -> sb.append(day.takeLast(5)).append("=").append(fmt(sum)).append("  ") }
        return sb.toString()
    }

    private fun buildLearnedText(): String {
        val rules = repo.learnedRules()
        return if (rules.isEmpty()) "暂无学习规则"
        else rules.joinToString("\n") { r -> r.merchant + " → " + r.category.label }
    }

    /* ============ CSV 导出 ============ */

    private fun exportCsv() {
        val entries = repo.allEntries()
        if (entries.isEmpty()) {
            Toast.makeText(this, "无数据可导出", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        sb.append("id,day,source,amount,merchant,category,raw\n")
        for (e in entries) {
            sb.append(e.id).append(',')
              .append(e.day).append(',')
              .append(e.source ?: "").append(',')
              .append(fmt(e.amount)).append(',')
              .append(e.merchant ?: "").append(',')
              .append(e.category).append(',')
              .append((e.raw ?: "").replace("\n", " ")).append('\n')
        }
        try {
            val f = File(externalCacheDir, "notifyledger_export_" + System.currentTimeMillis() + ".csv")
            FileOutputStream(f).use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "已导出: " + f.absolutePath, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun fmt(v: Double) = "¥" + String.format(Locale.getDefault(), "%.2f", v)

    private fun requestPostNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }
}
