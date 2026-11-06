package com.example.notifyledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.notifyledger.data.LedgerRepository
import com.example.notifyledger.judge.JudgeChain
import com.example.notifyledger.judge.JudgeProvider

/** 评审 §2.6: 记账页逻辑容器 — 粘贴解析/确认入账/手动录入/截图 OCR 共用应用级 Repository 与判断链。 */
class BookViewModel(application: Application) : AndroidViewModel(application) {
    val repo: LedgerRepository = JudgeProvider.sharedRepo(application)
    val chain: JudgeChain = JudgeProvider.chain(application)
}
