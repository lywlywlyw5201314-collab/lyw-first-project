package com.example.notifyledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.notifyledger.data.LedgerRepository
import com.example.notifyledger.judge.JudgeProvider

/** 评审 §2.6: 账单页逻辑容器 — 流水筛选/CSV 导出/改分类/删除共用应用级 Repository。 */
class BillViewModel(application: Application) : AndroidViewModel(application) {
    val repo: LedgerRepository = JudgeProvider.sharedRepo(application)
}
