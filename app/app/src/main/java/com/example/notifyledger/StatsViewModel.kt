package com.example.notifyledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.notifyledger.data.LedgerRepository
import com.example.notifyledger.judge.JudgeProvider

/** 评审 §2.6: 分析页逻辑容器 — 统计/周柱图/自检/监听状态共用应用级 Repository。 */
class StatsViewModel(application: Application) : AndroidViewModel(application) {
    val repo: LedgerRepository = JudgeProvider.sharedRepo(application)
}
