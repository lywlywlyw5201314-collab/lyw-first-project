package com.example.notifyledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.notifyledger.data.LedgerRepository
import com.example.notifyledger.judge.JudgeProvider

/** 评审 §2.6: 规则页逻辑容器 — 学习规则展示/保存/待确认条目处理共用应用级 Repository。 */
class RulesViewModel(application: Application) : AndroidViewModel(application) {
    val repo: LedgerRepository = JudgeProvider.sharedRepo(application)
}
