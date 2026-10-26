# 通知记账 App 项目 — 继续工作快照 (2026-10-23 16:00 前保存)

> **新会话只需说「继续」，读本文件即可恢复全部上下文，不会丢失任何进度。**

## 一、项目最终目标

**先网页、后 App。** 当前阶段：Android App 第一版基础功能实现。
核心原则：
- 判断逻辑只在 `PayNotifyParser.kt` / `parser.js`（单一事实来源），UI 层不重写规则
- 隐私最小化：同意才读取，非支付信息不保留、不展示、不落库
- 双方向：A（用户粘贴/手动/截图）+ B（通知监听）

## 二、目录说明

```
D:\apk2
├── web\           ← 网页端(已完成), 核心解析器 parser.js
├── app\           ← Android App(Kotlin, 当前工作目录)
│   ├── build.gradle.kts          ← 根(AGP 8.5.0 / Kotlin 1.9.22)
│   ├── settings.gradle.kts
│   ├── gradle.properties         ← 已加 android.suppressUnsupportedCompileSdk=34
│   └── app\
│       ├── build.gradle.kts      ← compileSdk=34, 依赖 ML Kit text-recognition
│       └── src\main\java\com\example\notifyledger\
│           ├── MainActivity.kt          ← 四导航(记账/账单/规则/分析)
│           ├── EntryAdapter.kt
│           ├── NotificationLedgerService.kt ← 方向 B
│           ├── data\LedgerDb.kt, LedgerRepository.kt
│           ├── judge\Judge.kt, JudgeProvider.kt, LlmJudge.kt, RuleJudge.kt
│           ├── model\Category.kt, LedgerEntry.kt, LearnedRule.kt, ParseResult.kt
│           ├── parser\PayNotifyParser.kt ← ★ 核心解析器(含 splitTransactions/cleanOcrText/judge)
│           ├── util\TimeUtil.kt
│           └── ocr\OcrHelper.kt        ← ML Kit 本地 OCR
├── PLAN.md         ← 原交接计划
└── verify\         ← 测试截图(ui.xml, 06_bill.png, 07_rules.png, 08_rule_added.png)
```

## 三、当前进度（2026-10-23）

### 已完成
- [x] **环境**：JDK 17(`D:\softprogram\jdk17\jdk-17.0.20.1+1`)、Gradle 8.7(`D:\gradle-8.7`)、Android SDK(`D:\AndroidSDK`)、platforms;android-34、build-tools;34.0.0、emulator + AVD(Pixel4)
- [x] **APK 编译成功**：`D:\apk2\app\app\build\outputs\apk\debug\app-debug.apk`
- [x] **模拟器已启动**：`emulator-5554`（Pixel 4 / API 34）
- [x] **APK 已安装**：`adb install -r` 成功
- [x] **App 启动正常**（修复了 NPE：`LedgerRepository(this)` → `by lazy`）
- [x] **功能验证（界面实测）**：
  - 粘贴文本 → 判断链 → 丢弃/入账 ✅
  - 手动入账 ✅（¥23.50 已落库）
  - 账单页：搜索/筛选/统计看板/最近记录 ✅
  - 规则页：添加规则"convenience→餐饮" ✅
  - 分析页：通知监听状态 + AI 设置 + 9 例自检 ✅
- [x] **parser.js ↔ Kotlin 对齐**：`splitTransactions`、`cleanOcrText`、`judge`(三态) 已补进 `PayNotifyParser.kt`
- [x] **ML Kit 依赖已加**：`com.google.mlkit:text-recognition:16.0.0`

### 进行中（下次继续从这里接）
- [ ] **OcrHelper.kt**：ML Kit 本地 OCR 类（写完后编译验证）
- [ ] **MainActivity 截图按钮**：接 OcrHelper → 识别文本 → `cleanOcrText` → `splitTransactions` → 判断链
- [ ] **通知监听(方向 B)**：`NotificationLedgerService` 已写好，需授予权限验证
- [ ] **数据库增量迁移**：`LedgerDb.onUpgrade` 改 `ALTER`（正式版）
- [ ] **AI 设置界面**：LlmConfig 4 key 已可读写，缺 UI（`tvLlmStatus` 已有）
- [ ] **性能/健壮性**：LlmJudge 超时/重试、去重窗口持久化

### 遗留
- [ ] JUnit 测试 `PayNotifyParserTest.kt` 全 5 组 25+ 断言需在 Android Studio 跑
- [ ] 截图 OCR 离线化（ML Kit 已选，本地运行不上传）

## 四、关键命令（快速恢复）

```powershell
# 环境
$env:JAVA_HOME = "D:\softprogram\jdk17\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "D:\AndroidSDK"
$env:ANDROID_SDK_ROOT = "D:\AndroidSDK"

# 启动模拟器
& "D:\AndroidSDK\emulator\emulator.exe" -avd "Pixel4" -no-audio -no-snapshot -gpu swiftshader_indirect

# 等模拟器就绪
& "D:\AndroidSDK\platform-tools\adb.exe" wait-for-device
# 然后手动确认 boot:
& "D:\AndroidSDK\platform-tools\adb.exe" shell getprop sys.boot_completed  # 期望输出 1

# 编译
Set-Location "D:\apk2\app"
& "D:\gradle-8.7\bin\gradle.bat" :app:assembleDebug --no-daemon

# 安装 + 启动
$adb = "D:\AndroidSDK\platform-tools\adb.exe"
& $adb install -r "D:\apk2\app\app\build\outputs\apk\debug\app-debug.apk"
& $adb shell am start -n "com.example.notifyledger/.MainActivity"

# 截屏
& $adb shell screencap -p /sdcard/s.png
& $adb pull /sdcard/s.png "D:\apk2\verify\latest.png"
```

## 五、文件变更记录（本次会话）

| 文件 | 变更 |
|------|------|
| `app/build.gradle.kts` | compileSdk=34, 加 ML Kit + exifinterface 依赖 |
| `app/gradle.properties` | 加 `android.suppressUnsupportedCompileSdk=34` |
| `app/.../parser/PayNotifyParser.kt` | 加 `splitTransactions`、`cleanOcrText`、`JudgeResult`、`judge` 三态入口 |
| `app/.../MainActivity.kt` | 重写为四导航布局，`by lazy` 修 NPE，加规则/分析/AI 设置页逻辑 |
| `app/.../data/LedgerRepository.kt` | 重写，`helper.readableDatabase/writableDatabase` 修 rawQuery 错误 |
| `app/.../judge/LlmJudge.kt` | `Abstain` 去括号、`const val` 改 `val` |
| `app/.../NotificationLedgerService.kt` | 重写，修 `isDuplicate` 签名不一致 |
| `app/.../res/layout/activity_main.xml` | 重写为四页面 + 底部导航布局 |
| `app/.../res/values/strings.xml` | 扩展（tab 文案、按钮、提示） |
| `app/.../res/values/arrays.xml` | 新建（分类/时间范围 Spinner 选项） |
| `app/gradle/wrapper/` | 生成 wrapper(gradle-8.7-bin.zip) |

## 六、已知问题 / 注意事项

- `input text` 不支持中文，测试中文逻辑需跑 JUnit 或界面手动输入
- ML Kit 首次运行需下载模型（~5MB），离线环境需预装
- 方向 B 需用户在 设置 → 通知 → 通知使用权 里手动授权
- `LedgerDb.onUpgrade` 目前是 drop+重建，正式版改 `ALTER`
- 无 OCR 测试图，用 ML Kit 识别结果直接走 `cleanOcrText → splitTransactions → judge`

## 七、下一步（优先顺序）

1. 写 `OcrHelper.kt`（ML Kit 识别 → 清洗 → 拆分 → 判断链）
2. `MainActivity` 截图按钮接 OcrHelper，替代当前"占位提示"
3. 编译 + 安装 + 验证截图入账全链路
4. 跑 `PayNotifyParserTest`（Android Studio 或 `gradle testDebugUnitTest`）
5. 更新 PLAN.md 进度


## 八、关机后重启快速恢复（给继续的会话看）

关机再开机后，**以下内容会变，需要重建**：
1. 我的会话记忆和工具调用上下文 — 会丢，靠读 D:\apk2\CONTINUE.md 恢复
2. 模拟器进程（emulator64）— 会消失，需重新启动：& 'D:\AndroidSDK\emulator\emulator.exe' -avd Pixel4 -no-audio -no-snapshot -gpu swiftshader_indirect，再 db wait-for-device 等到 boot
3. Gradle daemon — 会自动重建，无需干预

**以下内容在磁盘上，关机不丢**：
- 所有代码改动（D:\apk2\app\ 下各 .kt/.xml/.kts）
- 编译好的 APK（D:\apk2\app\app\build\outputs\apk\debug\app-debug.apk，5.7MB）
- JDK / Gradle / Android SDK / 模拟器镜像 / AVD（D:\softprogram、D:\gradle-8.7、D:\AndroidSDK）
- 本文件 + erify/ 测试截图

**继续工作的第一步**：读完整份 D:\apk2\CONTINUE.md，重启模拟器，然后接第七节第 1 项（写 OcrHelper.kt）。


---

## 最新断点更新 (2026-10-25 收工)

### 本轮已跑通 / 已确认
- ✅ 功能3 通知监听: 定位并修复根因 —— targetSdk34 缺 `POST_NOTIFICATIONS` 运行时权限, 补 manifest + onCreate 申请 + adb pm grant 后, 支付通知自动入账(日志: 入口门命中→rule 入账), 非支付通知正确丢弃。
- ✅ 编译: 含权限改动后 `gradle :app:assembleDebug` BUILD SUCCESSFUL, 已 install -r 进 emulator-5554。
- ✅ JUnit: `PayNotifyParserTest` 24 tests / 0 fail / 0 err (报告 D:\apk2\app\app\build\reports\tests\testDebugUnitTest, 结果 xml 时间戳 2026-10-25)。
- ✅ 日志: D:\apk2\docs\dev-logs\2026-10-25.md (完成/Bug/根因/方案/测试/遗留)。

### 功能2 OCR (如实标注: 本环境不可用)
- ML Kit 中文 OCR 在**无 GMS 的模拟器**上 `TextRecognition.getClient` 抛 `ClassNotFoundException: TextRecognizerOptionsUtils`。已加 try/catch(Throwable) 优雅降级(app 不崩, 界面提示"未识别到支付信息"), 但**真识别需有 GMS 的环境**(真机/有 Play 服务的模拟器)。代码逻辑正确, 属环境限制。

### 明早继续 (按计划)
1. 三入口各重测 2 遍, 截图存 D:\apk2\verify\ (命名 paste_r1/r2, ocr_r1/r2, notify_r1/r2 + 对应账单页)。
2. 清理 D:\apk2\verify\ 里解压的 aar/apk/class 污染文件(建议移入 verify\_archive\)。
3. Git: D:\apk2 目前**非仓库**、无 gh CLI。按用户红线(禁 git init/reset/clean/强推/盲目 add .)——**先给方案待确认**: 是否 init 新仓库 + 只 add 源码目录(app/docs/CONTINUE.md/.gitignore) + 建 .gitignore 排除 build/verify 大文件/密钥, 再推 https://github.com/lywlywlyw5201314-collab/lyw-first-project。不擅自动。
4. 生成 PDF 日志 apk测试2026-10-25.pdf 放指定文件夹(用户指定目录)。

### 通道 / 工具保留说明
- 执行: 本轮 exec/execSync 走 node_repl 的 child_process(execSync/spawn), 后台任务用 spawn detached + 写日志文件轮询; 中文参数一律 base64。
- GUI: computer-use(cua_repl) 操控模拟器; 模拟器 emulator-5554 在跑(AVD Pixel4)。
- 编译命令: 设 Env JAVA_HOME/ANDROID_HOME/ANDROID_SDK_ROOT → gradle.bat :app:assembleDebug --no-daemon (D:\gradle-8.7)。
- 授权: adb shell cmd notification allow_listener com.example.notifyledger/.NotificationLedgerService 0; adb shell pm grant com.example.notifyledger android.permission.POST_NOTIFICATIONS。
- 装包后需重新执行上面两条授权。


## 日志规范(用户 2026-10-25 新增, 以后都照此)
- 统一放 **D:\apk2\logs\** 文件夹(不再放 verify/ 或 docs/dev-logs/)。
- 每天收工时写一份, 文件名 **apk测试<日期>.pdf**(如 apk测试2026-10-25.pdf), 同时配一份同名 .md 便于复用。
- 内容**写详细、说大白话**, 可以带专业名词, 但整篇正文 **不超 300 字** 为上限节奏(以前"加起来不超300字", 现在要求写详细些, 控制在 300 字左右即可)。
- 固定结构: 做了什么 / 结果通过项 / 失败卡点 / 下一步 / 环境与通道保留。
- PDF 用 fpdf2 + simhei.ttf(已装好), 脚本参考 logs/ 里同名 md。


---

## 断点更新 (2026-10-26 收工前)

### 本轮已完成
- ✅ 三大功能各重测(粘贴/通知各 2 遍, OCR 1 遍确认降级), 截图存 verify/ (paste_r1/r2_book, notify_bill_fixed, ocr_bill)。
- ✅ **修了商户提取 bug**: extractMerchant 加规则清"交易号/订单号/流水号/单号/纯数字流水/成功"等残留。通知路径现在正确提商户(瑞幸咖啡)、归餐饮, 不再是"(未识别商户)"。
- ✅ 重新编译 + JUnit 24/0/0 + 装新包复验: 通知入账显示 瑞幸咖啡·餐饮。
- ✅ 日志: logs/apk测试2026-10-26.md + .pdf。

### 三大功能真实状态
- 功能1 粘贴: 通过(粘贴→AI判断→确认框→入账→账单)。
- 功能3 通知: 通过(自动入账 + 非支付丢弃)。
- 功能2 OCR: 优雅降级(模拟器无 GMS, 真识别需真机/带 GMS 模拟器)。

### 还差(下次接着干)
1. Git 推送: D:\apk2 非仓库、无 gh CLI, 按红线先给方案再动手(init+只add源码+.gitignore排除大文件/密钥+推 lyw-first-project)。
2. 三入口可再补 notify 第 2 遍截图(已有 notify_pay_r1)。
3. 清理 verify/ 污染文件(解压的 aar/apk/class)。

### 通道(保留)
- 执行: node_repl child_process(execSync/spawn, 后台 detached 写日志文件轮询); 中文/¥ 等参数一律先 base64 再传 ASCII。
- 截图: adb shell screencap -p /sdcard/_cap.png + exec-out cat 拉本地(用 pull 目标路径会 0 字节, 别用)。
- 模拟器: D:\AndroidSDK\emulator\emulator.exe -avd Pixel4; adb shell cmd notification allow_listener ...; pm grant ... POST_NOTIFICATIONS。
