package com.example.notifyledger

import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
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
    private lateinit var tvWeekTotal: TextView
    private lateinit var tvMonthTotalStats: TextView
    private lateinit var tvCountTotal: TextView
    private lateinit var tvBillCount: TextView
    private lateinit var weeklyBarsContainer: LinearLayout

    // 规则页
    private lateinit var etRuleMerchant: EditText
    private lateinit var spRuleCat: Spinner
    private lateinit var tvLearned: TextView

    // 账单页筛选
    private lateinit var fCat: Spinner
    private lateinit var fTime: Spinner
    private lateinit var tvRangeTotal: TextView
    private lateinit var tvRangeCount: TextView

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

    private val CAT_LABELS = listOf("餐饮", "购物", "交通", "生活", "其他")

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        tvShotNote.text = "识别中... 本地 OCR 处理截图, 不上传图片"
        uiScope.launch {
            val results = withContext(Dispatchers.IO) {
                OcrHelper.recognizeAndJudge(this@MainActivity, uri, repo.learnedRules())
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
        tvWeekTotal = findViewById(R.id.tvWeekTotal)
        tvMonthTotalStats = findViewById(R.id.tvMonthTotalStats)
        tvCountTotal = findViewById(R.id.tvCountTotal)
        tvBillCount = findViewById(R.id.tvBillCount)
        weeklyBarsContainer = findViewById(R.id.weeklyBarsContainer)
        etRuleMerchant = findViewById(R.id.etRuleMerchant)
        spRuleCat = findViewById(R.id.spRuleCat)
        tvLearned = findViewById(R.id.tvLearned)
        fCat = findViewById(R.id.fCat)
        fTime = findViewById(R.id.fTime)
        tvRangeTotal = findViewById(R.id.tvMonthTotal)
        tvRangeCount = findViewById(R.id.tvBillCount)
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
        fCat.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { reload() }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        fTime.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { reload() }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        // 规则页
        findViewById<Button>(R.id.btnRuleSave).setOnClickListener { saveRule() }
        val ruleAddCard = findViewById<android.view.View>(R.id.ruleAddCard)
        ruleAddCard.setOnClickListener { etRuleMerchant.requestFocus() }
        val spRuleType = findViewById<Spinner>(R.id.spRuleType)
        spRuleType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                spRuleCat.visibility = if (pos == 1) android.view.View.GONE else android.view.View.VISIBLE
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        // 分析页
        findViewById<Button>(R.id.btnOpenListenerSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnLlmSave).setOnClickListener { saveLlmConfig() }
        findViewById<Button>(R.id.btnLlmClear).setOnClickListener { clearLlmConfig() }
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
        val tabIcons = listOf(R.drawable.ic_tab_book, R.drawable.ic_tab_bill, R.drawable.ic_tab_rules, R.drawable.ic_tab_stats)
        tabs.forEachIndexed { i, btn ->
            btn.setAllCaps(false)
            btn.setTextColor(
                if (i == index) 0xFF236B55.toInt() else 0xFF929B94.toInt()
            )
            val icon = androidx.core.content.ContextCompat.getDrawable(this, tabIcons[i])
            if (icon != null) {
                val tint = if (i == index) 0xFF236B55.toInt() else 0xFF929B94.toInt()
                val filtered = androidx.core.graphics.drawable.DrawableCompat.wrap(icon)
                androidx.core.graphics.drawable.DrawableCompat.setTint(filtered, tint)
                btn.setCompoundDrawablesWithIntrinsicBounds(filtered, null, null, null)
                btn.compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
            }
        }
        if (index == 1) reload()
        if (index == 2) reloadRulesPage()
        if (index == 3) reloadStatsPage()
    }

    /* ============ 测试后门: 由 TestCommandReceiver 广播驱动(仅模拟器首版验证用) ============ */

    private fun maybeLoadTestPasteFromIntent() {
        var text = intent?.getStringExtra(TestCommandReceiver.EXTRA_TEST_PASTE_TEXT) ?: ""
        if (text.isEmpty()) {
            val b64 = intent?.getStringExtra("test_paste_text_b64")
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
        val ignoreRules = rules.filter { it.merchant.startsWith("@ignore:") }.map { it.merchant.removePrefix("@ignore:") }
        (if (segs.isNotEmpty()) segs else listOf(text)).forEach { seg ->
            val hitIgnore = ignoreRules.firstOrNull { seg.contains(it, ignoreCase = true) }
            if (hitIgnore != null) {
                pendingDiscards.add(seg to "用户忽略规则: " + hitIgnore)
                return@forEach
            }
            val v = PayNotifyParser.judge(seg, rules.filter { !it.merchant.startsWith("@ignore:") })
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
        // e.category 已是中文 label(Cr.category.label), 直接展示; 不再走英文 CAT_KEYS 索引(该索引恒 -1 → 误显"餐饮")
        val catLabel = e.category ?: "其他"
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
            Toast.makeText(this, "请填写商户关键词", Toast.LENGTH_SHORT).show()
            return
        }
        val typeIgnore = findViewById<Spinner>(R.id.spRuleType).selectedItemPosition == 1
        if (Regex("验证码|\\d{4,}").containsMatchIn(m) && m.length <= 8) {
            Toast.makeText(this, "关键词疑似验证码/编号，已忽略保存", Toast.LENGTH_SHORT).show()
            return
        }
        if (typeIgnore) {
            repo.upsertLearnedRule(LearnedRule("@ignore:" + m, Category.OTHER))
            etRuleMerchant.setText("")
            Toast.makeText(this, "已添加忽略规则: " + m, Toast.LENGTH_SHORT).show()
        } else {
            val catLabel = CAT_LABELS[spRuleCat.selectedItemPosition.coerceIn(0, CAT_LABELS.size - 1)]
            val cat = Category.fromLabel(catLabel)
            repo.upsertLearnedRule(LearnedRule(m, cat))
            etRuleMerchant.setText("")
            Toast.makeText(this, "已保存: " + m + " → " + catLabel, Toast.LENGTH_SHORT).show()
        }
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
        // 打开时回显已存配置: 只填空字段, 不覆盖用户正在编辑的内容
        val etEndpoint = findViewById<EditText>(R.id.etLlmEndpoint)
        val etKey = findViewById<EditText>(R.id.etLlmKey)
        val etModel = findViewById<EditText>(R.id.etLlmModel)
        if (etEndpoint.text.isNullOrBlank() && cfg.endpoint.isNotEmpty()) etEndpoint.setText(cfg.endpoint)
        if (etKey.text.isNullOrBlank() && cfg.apiKey.isNotEmpty()) etKey.setText(cfg.apiKey)
        if (etModel.text.isNullOrBlank()) etModel.setText(cfg.model)
        tvLlmStatus.text = if (cfg.isEnabled) {
            "智能识别: 已启用(模型 " + cfg.model + ")"
        } else {
            "智能识别: 未配置, 当前由本地规则自动判断"
        }
    }

    private fun saveLlmConfig() {
        val prev = LlmConfig.load(applicationContext)
        // 空字段继承上次保存值, 防止误把配置抹掉; 要彻底关闭请用「清除 AI 设置」
        val endpoint = findViewById<EditText>(R.id.etLlmEndpoint).text.toString().trim().ifEmpty { prev.endpoint }
        val key = findViewById<EditText>(R.id.etLlmKey).text.toString().trim().ifEmpty { prev.apiKey }
        val model = findViewById<EditText>(R.id.etLlmModel).text.toString().trim().ifEmpty { prev.model }
        val editor = getSharedPreferences(LlmConfig.PREFS, MODE_PRIVATE).edit()
        editor.putBoolean("enabled", endpoint.isNotEmpty() && key.isNotEmpty())
        editor.putString("endpoint", endpoint)
        editor.putString("apiKey", key)
        editor.putString("model", model)
        editor.apply()
        Toast.makeText(this, "AI 设置已保存", Toast.LENGTH_SHORT).show()
        reloadLlmStatus()
    }

    private fun clearLlmConfig() {
        getSharedPreferences(LlmConfig.PREFS, MODE_PRIVATE).edit()
            .putBoolean("enabled", false)
            .putString("endpoint", "")
            .putString("apiKey", "")
            .putString("model", LlmConfig.DEFAULT_MODEL)
            .apply()
        findViewById<EditText>(R.id.etLlmEndpoint).setText("")
        findViewById<EditText>(R.id.etLlmKey).setText("")
        findViewById<EditText>(R.id.etLlmModel).setText(LlmConfig.DEFAULT_MODEL)
        Toast.makeText(this, "已清除 AI 设置, 恢复本地规则判断", Toast.LENGTH_SHORT).show()
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
        val _today = TimeUtil.todayDay()
        tvMonthTotal.text = fmt(repo.sumBetween(TimeUtil.firstOfMonth(), _today))
        tvWeekTotal.text = fmt(repo.sumBetween(TimeUtil.mondayOfToday(), _today))
        tvMonthTotalStats.text = fmt(repo.sumBetween(TimeUtil.firstOfMonth(), _today))
        tvCountTotal.text = repo.allEntries().size.toString()
        renderWeeklyBars(repo.dailyTotals(TimeUtil.lastNDays(7)))
        val all = repo.allEntries()
        val catLabel = if (fCat.selectedItemPosition > 0) CAT_LABELS[fCat.selectedItemPosition - 1] else null
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
        tvMonthTotal.text = fmt(spend)
        tvBillCount.text = "共 $cnt 笔"
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

    private fun renderWeeklyBars(daily: List<Pair<String, Double>>) {
        weeklyBarsContainer.removeAllViews()
        val ctx = this
        val max = daily.maxOfOrNull { it.second } ?: 0.0
        val barColor = 0xFF236B55.toInt()
        val lightColor = 0xFFE5F0EA.toInt()
        val textSec = 0xFF737D76.toInt()
        daily.forEach { (day, sum) ->
            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            col.layoutParams = lp
            col.gravity = android.view.Gravity.BOTTOM
            val valTv = TextView(ctx)
            valTv.text = if (sum > 0) "¥" + String.format(java.util.Locale.getDefault(), "%.1f", sum) else ""
            valTv.textSize = 9f
            valTv.setTextColor(textSec)
            valTv.gravity = android.view.Gravity.CENTER
            val bar = View(ctx)
            val hPct = if (max > 0) ((sum / max).toInt().coerceIn(0, 100) * 80) else 8
            val barLp = LinearLayout.LayoutParams(0, hPct)
            barLp.weight = 0f
            bar.layoutParams = barLp
            val todayDay = TimeUtil.todayDay()
            bar.setBackgroundColor(if (day == todayDay) barColor else lightColor)
            bar.setPadding(6, 0, 6, 0)
            val label = TextView(ctx)
            label.text = if (day == todayDay) "今天" else day.takeLast(5)
            label.textSize = 9f
            label.setTextColor(textSec)
            label.gravity = android.view.Gravity.CENTER
            label.setPadding(0, 4, 0, 0)
            col.addView(valTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            col.addView(bar, barLp)
            col.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            weeklyBarsContainer.addView(col, lp)
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

    private fun fmt(v: Double) = "¥" + String.format(Locale.getDefault(), "%.2f", v)

    private fun requestPostNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }
}
