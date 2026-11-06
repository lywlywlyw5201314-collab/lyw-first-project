package com.example.notifyledger

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * 宿主(评审 §2.6): 只负责 Fragment 容器 + 底部 Tab 导航 + 通知权限申请。
 * 各页签的界面与逻辑已拆入 [BookFragment] / [BillFragment] / [RulesFragment] / [StatsFragment]
 * 及各自 ViewModel, 数据经应用级 [com.example.notifyledger.judge.JudgeProvider] 共享。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG_BOOK = "book"
        const val TAG_BILL = "bill"
        const val TAG_RULES = "rules"
        const val TAG_STATS = "stats"
    }

    private lateinit var bookFragment: BookFragment
    private lateinit var billFragment: BillFragment
    private lateinit var rulesFragment: RulesFragment
    private lateinit var statsFragment: StatsFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            bookFragment = BookFragment()
            billFragment = BillFragment()
            rulesFragment = RulesFragment()
            statsFragment = StatsFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, bookFragment, TAG_BOOK)
                .add(R.id.fragmentContainer, billFragment, TAG_BILL)
                .add(R.id.fragmentContainer, rulesFragment, TAG_RULES)
                .add(R.id.fragmentContainer, statsFragment, TAG_STATS)
                .hide(billFragment)
                .hide(rulesFragment)
                .hide(statsFragment)
                .commit()
        } else {
            bookFragment = supportFragmentManager.findFragmentByTag(TAG_BOOK) as BookFragment
            billFragment = supportFragmentManager.findFragmentByTag(TAG_BILL) as BillFragment
            rulesFragment = supportFragmentManager.findFragmentByTag(TAG_RULES) as RulesFragment
            statsFragment = supportFragmentManager.findFragmentByTag(TAG_STATS) as StatsFragment
        }

        // 导航
        findViewById<Button>(R.id.tabBook).setOnClickListener { goPage(0) }
        findViewById<Button>(R.id.tabBill).setOnClickListener { goPage(1) }
        findViewById<Button>(R.id.tabRules).setOnClickListener { goPage(2) }
        findViewById<Button>(R.id.tabStats).setOnClickListener { goPage(3) }

        requestPostNotificationPermission()
        goPage(0)
    }

    override fun onResume() {
        super.onResume()
        bookFragment.hostResume()
        refreshAll()
    }

    /** 各页数据变更后统一刷新(替代原 MainActivity.reload()) */
    fun refreshAll() {
        billFragment.refresh()
        rulesFragment.refresh()
        statsFragment.refresh()
    }

    /** Tab 切换: show/hide 事务 + 底部按钮样式(原 MainActivity.goPage 逐行保留) */
    private fun goPage(index: Int) {
        val tx = supportFragmentManager.beginTransaction()
        when (index) {
            0 -> tx.show(bookFragment).hide(billFragment).hide(rulesFragment).hide(statsFragment)
            1 -> tx.show(billFragment).hide(bookFragment).hide(rulesFragment).hide(statsFragment)
            2 -> tx.show(rulesFragment).hide(bookFragment).hide(billFragment).hide(statsFragment)
            3 -> tx.show(statsFragment).hide(bookFragment).hide(billFragment).hide(rulesFragment)
        }
        tx.commit()

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

        if (index == 1) refreshAll()
        if (index == 2) rulesFragment.refresh()
        if (index == 3) statsFragment.refresh()
    }

    private fun requestPostNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }
}
