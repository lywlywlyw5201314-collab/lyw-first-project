package com.example.notifyledger

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.notifyledger.parser.PayNotifyParser
import com.example.notifyledger.util.Money
import com.example.notifyledger.util.TimeUtil
import java.util.Locale

/** 分析页(评审 §2.6 自 MainActivity 拆出): 本周/本月统计 + 7 天柱状图 + 分类明细 + 通知监听状态/自检。 */
class StatsFragment : Fragment() {

    private val viewModel: StatsViewModel by viewModels()

    private lateinit var tvWeekTotal: TextView
    private lateinit var tvMonthTotalStats: TextView
    private lateinit var tvCountTotal: TextView
    private lateinit var weeklyBarsContainer: LinearLayout
    private lateinit var tvAnalytics: TextView
    private lateinit var tvListenerStatus: TextView
    private lateinit var tvSelfTestResult: TextView
    private var viewsReady = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_stats, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvWeekTotal = view.findViewById(R.id.tvWeekTotal)
        tvMonthTotalStats = view.findViewById(R.id.tvMonthTotalStats)
        tvCountTotal = view.findViewById(R.id.tvCountTotal)
        weeklyBarsContainer = view.findViewById(R.id.weeklyBarsContainer)
        tvAnalytics = view.findViewById(R.id.tvAnalytics)
        tvListenerStatus = view.findViewById(R.id.tvListenerStatus)
        tvSelfTestResult = view.findViewById(R.id.tvSelfTestResult)

        view.findViewById<Button>(R.id.btnOpenListenerSettings).setOnClickListener {
            requireContext().startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        view.findViewById<Button>(R.id.btnSelfTest).setOnClickListener { runSelfTest() }

        viewsReady = true
    }

    /** 刷新分析页(原 MainActivity.reloadStatsPage(): 统计卡 + 7 天柱状图 + 分类明细 + 监听状态) */
    fun refresh() {
        if (!viewsReady) return
        val t = TimeUtil
        val today = t.todayDay()
        tvWeekTotal.text = Money.fmt(viewModel.repo.sumBetween(t.mondayOfToday(), today))
        tvMonthTotalStats.text = Money.fmt(viewModel.repo.sumBetween(t.firstOfMonth(), today))
        tvCountTotal.text = viewModel.repo.allEntries().size.toString()
        renderWeeklyBars(viewModel.repo.dailyTotals(t.lastNDays(7)))
        tvAnalytics.text = buildStats()
        reloadListenerStatus()
    }

    /* ============ 分析页 ============ */

    private fun buildStats(): String {
        val t = TimeUtil
        val today = t.todayDay()
        val week = viewModel.repo.sumBetween(t.mondayOfToday(), today)
        val month = viewModel.repo.sumBetween(t.firstOfMonth(), today)
        val byCat = viewModel.repo.sumByCategory(t.firstOfMonth(), today)
        val sb = StringBuilder()
        sb.append("本周已花: ").append(Money.fmt(week)).append('\n')
        sb.append("本月已花: ").append(Money.fmt(month)).append('\n')
        sb.append("本月分类: ")
        if (byCat.isEmpty()) {
            sb.append("(暂无)")
        } else {
            byCat.forEach { (cat, sum) -> sb.append(cat).append(' ').append(Money.fmt(sum)).append("  ") }
        }
        sb.append('\n').append("近 7 天: ")
        viewModel.repo.dailyTotals(t.lastNDays(7)).forEach { (day, sum) ->
            sb.append(day.takeLast(5)).append("=").append(Money.fmt(sum)).append("  ")
        }
        return sb.toString()
    }

    private fun reloadListenerStatus() {
        val ctx = requireContext()
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        tvListenerStatus.text =
            if (enabled) "已开启: 支付通知会自动识别入账, 其他信息自动忽略"
            else "未开启: 点「开启通知监听」授权, 通知功能才会生效"
    }

    private fun runSelfTest() {
        val results = PayNotifyParser.selfTest()
        val passed = results.count { it.ok }
        val failed = results.count { !it.ok }
        val detail = results.joinToString("\n") { r -> (if (r.ok) "√" else "×") + " " + r.name + ": " + r.detail }
        tvSelfTestResult.text = "通过 " + passed + "/" + results.size + ", 失败 " + failed + "\n\n" + detail
        if (passed == results.size) {
            Toast.makeText(requireContext(), "全部自检通过 🎉", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderWeeklyBars(daily: List<Pair<String, Double>>) {
        weeklyBarsContainer.removeAllViews()
        val ctx = requireContext()
        val max = daily.maxOfOrNull { it.second } ?: 0.0
        val barColor = 0xFF236B55.toInt()
        val lightColor = 0xFFE5F0EA.toInt()
        val textSec = 0xFF737D76.toInt()
        daily.forEach { (day, sum) ->
            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            col.layoutParams = lp
            col.gravity = Gravity.BOTTOM
            val valTv = TextView(ctx)
            valTv.text = if (sum > 0) "¥" + String.format(Locale.getDefault(), "%.1f", sum) else ""
            valTv.textSize = 9f
            valTv.setTextColor(textSec)
            valTv.gravity = Gravity.CENTER
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
            label.gravity = Gravity.CENTER
            label.setPadding(0, 4, 0, 0)
            col.addView(valTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            col.addView(bar, barLp)
            col.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            weeklyBarsContainer.addView(col, lp)
        }
    }
}
