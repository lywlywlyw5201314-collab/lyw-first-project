# NotifyLedger 30 天代码评审报告

- **评审日期**: 2026-11-03(设备时区 Asia/Shanghai)
- **仓库**: `D:\apk2` → `https://github.com/lywlywlyw5201314-collab/lyw-first-project.git`, HEAD `2038e5b`(已推送)
- **评审范围**: 2026-10-04 ~ 2026-11-03 共 8 个提交(`665c11d`…`2038e5b`)改动的全部文件 + 当前未跟踪的临时文件(范围清单见附录 A)
- **评审维度**: 代码质量 / 安全问题 / 性能瓶颈 / 架构设计 / 测试覆盖 / 依赖更新 / 最佳实践
- **基线验证**: regex-align 19/19 通过、judge 13/13 通过、JUnit 33/33 通过、`gradle :app:assembleDebug :app:testDebugUnitTest` BUILD SUCCESSFUL;模拟器 `emulator-5554` 全链路(显式广播 → 判断链 → SQLite)实测通过
- **行号说明**: 以下所有行号均基于 `2038e5b` 工作树实测,非估算

## 0. 摘要表

| 指标 | 数量 | 说明 |
|---|---|---|
| 高风险项数 | **2** | ① 无鉴权测试后门广播 ② 非支付原文日志泄漏(隐私铁律违规) |
| 关键依赖更新数 | **8** | AGP / Kotlin / coroutines / core-ktx / appcompat / material / recyclerview / JUnit(均经 maven-metadata 实测,见 §7.1) |
| 必须修复的问题数 | **2** | 即 2 个高风险项(发版阻断项) |
| 中风险项数 | 8 | 金额浮点 / 阻塞式 Task 调用 / 判断链旁路 / 备份开关 / 依赖滞后 / 上帝类 / 死迁移分支 / 仓库卫生 |
| 低风险项数 | 4 | 权限冗余 / 工厂重复分配 / 去重键 locale 敏感 / 文档与代码不一致 |

## 1. 高风险(HIGH)

### 1.1 `TestCommandReceiver` 无鉴权外部广播后门 —— 发版阻断

- **严重等级**: 高
- **问题描述**:
  `TestCommandReceiver` 在 Manifest 中声明 `exported="true"` 且**未设任何 `android:permission`**,4 个 action 均为私有未签名 action(`TEST_PASTE` / `TEST_OCR` / `TEST_PAYMENT_NOTIFICATION` / `TEST_NONPAY`)。任何已安装应用(或经 adb/shell 的攻击者)都可以向本应用注入伪支付通知、触发 OCR 流水线、写 `test_prefs`,等价于一个可被外部触发的测试后门。代码 KDoc 自身已注明「测试后门广播接收器(仅用于模拟器首版验证, 正式发版应删除)」,即作者已知晓但未落地。
- **相关文件和行号**:
  - `app/app/src/main/AndroidManifest.xml` L33-42(receiver 声明,L35 `exported="true"`,L37-40 四个 action)
  - `app/app/src/main/java/com/example/notifyledger/TestCommandReceiver.kt` L19(KDoc 自述「测试后门…正式发版应删除」)、L58(TEST_OCR 走 `OcrHelper.recognizeAndJudge` 静态旁路)、L116-120(action 常量定义)
- **具体修复建议**:
  1. 正式发版前整体删除 `TestCommandReceiver.kt` + Manifest L33-42(KDoc 既定结论,推荐)。
  2. 若需保留给内部测试,加 `android:permission` 自定义签名级权限并只在 debug 构建变体中注册(`buildTypes` 条件或 `BuildConfig` 门控),release 包中不可达。
  3. 无论保留与否,`L58` 静态旁路(见 §3.3)随之收敛。

### 1.2 非支付通知原文全文写入 logcat —— 违反隐私铁律

- **严重等级**: 高
- **问题描述**:
  隐私铁律:「只识别支付相关的数据,检测到其他一律丢弃,保护用户隐私」。当前实现「丢弃了数据,但把原文记进了日志」:
  - **L62 是最大泄漏面**:关键词入口门**未命中**时(即绝大多数非支付通知)执行 `Log.d(TAG, "入口门未命中(非支付), 忽略: " + text)` —— 用户设备上每一条微信/短信/系统通知的完整标题+正文都会进 logcat。debug 构建、adb 连接的 logcat 对任何工具可读,「丢弃」形同虚设。
  - L86 丢弃分支 `Log.i(TAG, "放弃 [${v.via}] ${v.reason} | 原文: ${v.raw}")` 把被判定为非支付的原文再写一遍。
  - L65(门命中)与 L73(去重命中,日志内容=去重键,内含全文)各自再泄漏一次。
  - 截图 OCR 路径 `OcrHelper` L37 `Log.i("OcrHelper", "OCR 原始识别文本: " + raw)` 与 L38(清洗后文本)把**任意截图**的完整识别文本进 logcat。
- **相关文件和行号**:
  - `app/app/src/main/java/com/example/notifyledger/NotificationLedgerService.kt` L53、L62、L65、L73、L86
  - `app/app/src/main/java/com/example/notifyledger/ocr/OcrHelper.kt` L37、L38
- **具体修复建议**:
  1. L62:去掉 `+ text`(或改为仅记 `pkg` + 长度),这是最高频泄漏点。
  2. L86:去掉 `| 原文: ${v.raw}`,保留 `via/reason`(非敏感元数据);审计需要原文时可写本地文件而非 logcat。
  3. L73:去重键本身含全文(见 §5.3),改用不含原文的键后日志自然脱敏。
  4. L37/L38:改为仅记字符数与段数(现有 L41/L48 的计数日志足够调试)。
  5. 统一加 `if (BuildConfig.DEBUG)` 门控,并新增 lint 规则/评审清单项:禁止日志字符串拼接通知原文。

## 2. 中风险(MEDIUM)

### 2.1 金额用 `REAL`(IEEE-754 浮点)存储 —— 财务精度

- **严重等级**: 中
- **问题描述**: `entries.amount REAL`,插入/汇总全走 `double`(统计 `COALESCE(SUM(amount),0)`、`getDouble` 读取)。浮点累加在跨日统计时会产生 `0.30000000000000004` 类误差,金额展示与「共 N 笔/合计」对账存在系统性偏差风险。
- **相关文件和行号**: `data/LedgerDb.kt` L27(建表 `amount REAL NOT NULL`);`data/LedgerRepository.kt` L97-113(三个统计查询)、L134(`getDouble`)。
- **修复建议**: 金额改存整数分(`amount_cents INTEGER`,展示时 `/100`),作为一次真实的 `DB_VERSION=2` 迁移(与 §2.7 的无头迁移绑定);web 端 localStorage 侧同步改整数分以保双端对齐。

### 2.2 `OcrHelper.recognize` 在挂起协程中阻塞 + 全量吞异常

- **严重等级**: 中
- **问题描述**: L59 `Tasks.await(recognizer.process(image))` 是**同步阻塞调用**,挂在 `suspend fun` 里直接占住调用方线程(当前调用方是主线程 `lifecycleScope`,阻塞=UI 冻结,直到 OCR 完成)。L63 `catch (e: Throwable)` 把所有异常(含 OOM、类加载失败等真崩溃)统一降级为「引擎不可用」哨兵,真实故障被掩盖成「模拟器缺 GMS」。
- **相关文件和行号**: `ocr/OcrHelper.kt` L53-66(`L59` 阻塞、`L63-64` 全量捕获并仅记 `e.message`)。
- **修复建议**: ① 引入 `com.google.android.gms.tasks:play-services-tasks` 的 `awaitTask()`(Kotlin 协程挂起扩展,非阻塞)替代 `Tasks.await`;② 捕获面收窄为 `Exception` 并对 `TaskCancellationException`/`Exception` 分类记日志(记异常类名即可,不记敏感内容);③ 调用侧确保在 `Dispatchers.IO` 或专用 scope 中运行。

### 2.3 OCR 路径旁路 `JudgeChain`,判断入口分裂

- **严重等级**: 中
- **问题描述**: `OcrHelper.recognizeAndJudge` L45 直接调静态 `PayNotifyParser.judge(seg, learnedRules)`,而服务主路径走 `JudgeChain`(忽略规则 → `RuleJudge`)。两条判断入口并存:目前语义一致(都读 `learned_rules`),但 `JudgeProvider` KDoc 明确把「截图 OCR 接入判断链」列为扩展点,实际未接入;后续若判断链新增判断器,OCR 路径会静默分叉。
- **相关文件和行号**: `ocr/OcrHelper.kt` L45;对照 `judge/JudgeProvider.kt` L12(KDoc 扩展点)、L17-23(`chain()`)。
- **修复建议**: 把 `PayNotifyParser.judge` 包装成一个 `Judge` 实现(或让 OCR 结果流经同一 `JudgeChain`),调用处改为 `chain.judge(...)`,消灭平行判断入口。

### 2.4 `allowBackup="true"` 财务数据随备份外流

- **严重等级**: 中
- **问题描述**: Manifest 全局 `allowBackup="true"` 且未配置备份排除规则,`notifyledger.db`(金额/商户/分类流水)、`learned_rules`、`notifyledger_dedup` 均可被 `adb backup`/云备份整体拷走——与本应用「本地隐私账本」的定位直接冲突(备份通道不受用户「本机记账」预期约束)。
- **相关文件和行号**: `AndroidManifest.xml` L8(`android:allowBackup="true"`);受影响数据见 `data/LedgerDb.kt` L57(`DB_NAME`)。
- **修复建议**: 改 `android:allowBackup="false"`;若仍需保留界面设置类数据,用 `fullBackupContent` 排除 + API 31+ 的 `dataExtractionRules` 精确放行非财务资源。

### 2.5 依赖滞后(8 项可升级,经 maven-metadata 实测)

- **严重等级**: 中(单项低,累积高:coroutines 落后 4 个 minor,AGP/Kotlin 各落后 1 个大版本线)
- **问题描述 / 修复建议**: 见 §7.1 升级矩阵(含来源 URL 与置信度标注);建议分三批:批1 低风险 minor(coroutines 1.11.0、recyclerview 1.4.0、core-ktx 1.19.1、appcompat 1.8.0、material 1.14.0)各自独立提交+跑全量测试;批2 AGP 9.x(连带 Gradle 9 迁移);批3 Kotlin 2.x(语法/默认参数变化回归)。JUnit4 行已 EOL,测试迁 JUnit 5(5.14.x)作为独立任务。

### 2.6 `MainActivity` 680 行上帝类

- **严重等级**: 中(架构,非缺陷)
- **问题描述**: 四个页签(记账/账单/规则/分析)的 UI、Adapter、OCR 流程、规则编辑全部集中在单个 680 行 Activity(实测 680 行),状态管理无 ViewModel,页面状态与控件强耦合,后续加页签/加功能的回归面全在这一个文件。
- **相关文件和行号**: `MainActivity.kt` 全文件(680 行);近 30 天 5 个提交改动过它(`889e9ba`、`a191d83`、`d8ecbbe`、`f9e5ce5`、`f9e92f4`)。
- **修复建议**: 按页签拆 4 个 `Fragment`/composable 容器 + 各自 `ViewModel`;`EntryAdapter`/规则列表逻辑下沉;每拆一块跑一次 33/33 单测兜底。优先级可排后,不阻断发版。

### 2.7 `DB_VERSION=1` 下存在永不可达的 `onUpgrade` 迁移分支(死代码)

- **严重等级**: 中(迁移策略隐患)
- **问题描述**: `LedgerDb` 声明 `DB_VERSION = 1`,但 `onUpgrade` 内已写好 `if (oldVersion < 2)` 的「v1→v2」分支(`CREATE INDEX IF NOT EXISTS`——而该索引在 `onCreate` L32 已建)。该分支永远不会执行(升级只在 `DB_VERSION` 提升后触发),且注释把示例与实码混排,后续做 §2.1 的金额迁移时容易误判「迁移已有」。
- **相关文件和行号**: `data/LedgerDb.kt` L41-48(死分支 + 注释掉的示例)、L58(`DB_VERSION = 1`)、L32(索引已在 onCreate 建)。
- **修复建议**: 二选一:① 删掉 `oldVersion < 2` 死分支,迁移模板另开 v2 时再写;② 真正需要索引兜底时把 `DB_VERSION` 提到 2。与 §2.1 一起做,一次迁移到位。

### 2.8 仓库卫生:测试杂物已入库 + `.gitignore` 逐名列写漏放

- **严重等级**: 中
- **问题描述**:
  1. `889e9ba` 已把验证/调试杂物提交入库:`verify/*.png`(4 张截图)、`web/.screenshot_ui.png`、`logs/apk测试2026-10-25/26.md`、`web/CONTINUE.md`。
  2. `.gitignore` L52-70 采用「逐文件名」列写,未覆盖当前工作区的 `_` 前缀杂物(`_md2pdf.py`、`_pull_db.py`、`_review_files.txt`、`_logcat*.txt`、`_srv8091.js`、`_ui*.xml`、`_gitstatus.txt`、`CODEX_HANDOFF.md`、`web/*.bak` 等),`git status` 噪声大,存在误 `git add .` 把过程文件推上远端的风险。
- **相关文件和行号**: `.gitignore` L9-16、L36-50、L52-70(逐名列写区段);提交 `889e9ba` 文件清单见附录 A。
- **修复建议**: `.gitignore` 增补模式行:根级 `_*`、`_srv*.js`、`_ui*.xml`、`CODEX_HANDOFF.md`、`web/*.bak`;对已入库杂物 `git rm --cached` 并补一次「仓库卫生」提交(保留 `logs/*.pdf` 的 `!` 例外不变,开发日志 PDF 仍需入库)。

## 3. 低风险(LOW)

### 3.1 `INTERNET` 权限疑似冗余(低置信度)

- **严重等级**: 低(置信度:低)
- **问题描述**: AI 判断器在 `f9e5ce5` 移除后,应用仅剩本地判断链 + 本地 ML Kit OCR(v2 API 为离线模型),`INTERNET` 很可能不再被使用;但未做运行时权限审计(Play 的 unused-permission 报告未获取),不排除 `play-services-mlkit` 客户端在初始化时探测网络。
- **相关文件和行号**: `AndroidManifest.xml` L4。
- **修复建议**: 用 `adb shell dumpsys package` / Play 控制台 unused-permission 报告确认,无引用即删除;本条在证据补齐前保持「疑似」。

### 3.2 `JudgeProvider.chain()` 每次新建 `LedgerRepository`

- **严重等级**: 低
- **问题描述**: `chain()` 每次调用都 `LedgerRepository(context)`(内含 `SQLiteOpenHelper` 实例化)。当前服务侧 L36 `by lazy` 只建一次链,实际影响≈0,但工厂语义下若被多处调用会产生多份 Helper;KDoc 的「工厂」定位与逐次分配不一致。
- **相关文件和行号**: `judge/JudgeProvider.kt` L17-23(L18 分配点);调用方 `NotificationLedgerService.kt` L36。
- **修复建议**: provider 内缓存 `LedgerRepository` 单例(`SQLiteOpenHelper` 本身线程安全),或把 repo 作为 `chain()` 参数注入,调用方持有。

### 3.3 去重键 locale 敏感且内嵌全文

- **严重等级**: 低
- **问题描述**: L69-71 去重键 = `"$text|${String.format(Locale.getDefault(), "%.2f", parsed.amount)}"`:① `%.2f` 随系统 locale 变化(逗号小数点环境产键不一致,同笔通知跨 locale 可重复入账);② 键含全文,放大 L73 日志泄漏(与 §1.2 联动)。
- **相关文件和行号**: `NotificationLedgerService.kt` L69-73。
- **修复建议**: 键改为 locale 无关的组合:成功解析用 `"A|{amountCents}|{merchant}|{day}"`(金额用整数分,顺带消掉 locale 问题),失败解析用 `MD5(text)`;L73 日志只记键摘要。

### 3.4 KDoc 与 action 常量不一致(`TEST_NONPAY` vs `TEST_NONPAY_NOTIFICATION`)

- **严重等级**: 低
- **问题描述**: `TestCommandReceiver.kt` KDoc 文档写 `TEST_NONPAY_NOTIFICATION`,实际常量与 Manifest 均为 `TEST_NONPAY`,复制文档命令会发不进去(实测隐式广播本就静默失效,见 §6.2)。
- **相关文件和行号**: `TestCommandReceiver.kt` L26(文档)对照 L120 区(常量定义)、`AndroidManifest.xml` L40。
- **修复建议**: 若按 §1.1 删除整个 receiver 则自动消解;保留则修文档。

## 4. 性能结论

- **主线程零 SQLite**:服务模型为「主线程只做快速解析/去重/prefs `apply()`,其余(读 learned_rules、判断链、`insertEntry`)全部在单线程守护 executor `notify-ledger-judge` 内」,线程模型本身是健康的,30 天内未劣化。
- 性能瓶颈集中在 §2.2(OCR 阻塞)与未来大列表(账单页 `allEntries(limit=500)` 一次性拉 500 行进内存,量级内可接受,无分页——记录为观察项,未立 finding)。
- 去重窗口 5 分钟/上限 100 键的内存占用可忽略;持久化每键一次 `apply()` 有轻度 I/O 放大,频率上(通知风暴 <100 次/5min)不构成瓶颈。

## 5. 架构结论

- 判断链收敛状态**正确且干净**:`LlmJudge` 已在 `f9e5ce5` 彻底删除(工作树无残留,仅 `Judge.kt` 保留 `Verdict.Abstain` 作为扩展点,这是有意保留,非死代码);现链路 = 忽略规则(`@ignore:`) ⇒ `RuleJudge`(唯一判断器) ⇒ 入账标记 `rule:learned|default|unknown`,全 abstain ⇒ 丢弃(经 chain 语义),实测 DB 中 `rule:learned` 路径已验证(§6.3 第 3 条证据)。
- 遗留架构项:§2.3(判断入口分裂)、§2.6(上帝类)、§3.2(工厂分配),均为可排期的重构,非阻断。
- 双端对齐(Kotlin `PayNotifyParser` ↔ JS `web/parser.js`)以 19/19 regex-align 测试守护,30 天内 `2038e5b` 的状态词剥离修复双端同步,对齐机制有效。

## 6. 实测验证记录(2026-11-03,emulator-5554 / API 34)

### 6.1 去重窗口实测(正向证据)

对同一笔「微信支付成功 ¥12.30 瑞幸咖啡 13:05」连发两条显式广播:第一条 `入账 [rule:learned] 12.3 瑞幸咖啡 → 餐饮`(写库 id=6);第二条命中去重,logcat 出现 `去重命中, 跳过: <键>`(`NotificationLedgerService.kt` L73 实测输出,产物 `_logcat_dedup4.txt`)。`notifyledger_dedup` prefs 已持久化该键,杀进程重启后窗口仍有效(恢复逻辑 L111-113 覆盖)。

### 6.2 广播投递陷阱(复现方法,必读)

**本机 API-34 模拟器上,shell 隐式广播 `am broadcast -a <action>` 会被静默丢弃**(`result=0` 只代表入队,receiver 不执行)。必须显式指定组件:

```
adb shell am broadcast -n com.example.notifyledger/.TestCommandReceiver \
  -a com.example.notifyledger.TEST_PAYMENT_NOTIFICATION \
  --es title_b64 5b6u5L+h5pSv5LuY5oiQ5YqfIMKlMTIuMzAg55Ge5bm45ZKW5ZWhIDEzOjA1 \
  --es text_b64  5b6u5L+h5pSv5LuY5oiQ5YqfIMKlMTIuMzAg55Ge5bm45ZKW5ZWhIDEzOjA1
```

(base64 载荷 = UTF-8「微信支付成功 ¥12.30 瑞幸咖啡 13:05」。验证: `shared_prefs/test_prefs.xml` 的 `notification_sent` 更新 + logcat tag `NotifyLedger` / `NotifyLedgerTest`。)隐式形态的负向证据存于 `_logcat_dedup2/3.txt`。

### 6.3 数据库落点抽查(二进制提取方法)

`adb shell run-as … cat` 会损坏二进制(32772 坏字节,`database disk image is malformed`),正确方式为 **`adb exec-out run-as com.example.notifyledger cat databases/notifyledger.db`**(32768 字节,可用;脚本 `_pull_db.py`)。`_db_pull.db` 抽查:
- `entries` 3 行:id 6 (2026-11-03, 12.3, 瑞幸咖啡, 餐饮, 微信)、id 5 (2026-11-02, 5.0, TEST, 餐饮, source=None)、id 4 (2026-11-02, 13.1, 瑞幸咖啡, 餐饮, 微信) —— 去重后同一商户同日不同金额各自入账,符合窗口语义;
- `learned_rules` 2 行:(「瑞幸咖啡」→「餐饮」,本条由 §6.1 的 rule:learned 入账链路写入,活体验证) + 1 条忽略规则。

### 6.4 测试覆盖现状

单元测试 33/33(parser 19 + judge 13 + 杂项 1,含 `2038e5b` 新增的状态词回归);`web/test` 侧 regex-align/judge 全绿。**缺口**: 无任何 UI/集成测试(模拟器链路靠手工脚本),`TestCommandReceiver` 分支无单测——若按 §1.1 删除则缺口自动消失;`OcrHelper` 无测试(依赖 GMS,建议在 §2.2 改造时补 `awaitTask` 的伪 Task 单测)。

## 7. 依赖更新(§2.5 展开,2026-11-03 经 maven-metadata 实测)

### 7.1 升级矩阵(8 项已核实)

| 依赖 | 现用 | 最新(核实) | 来源 | 批次 |
|---|---|---|---|---|
| com.android.tools.build:gradle (AGP) | 8.5.0 | 9.5.0 稳定(9.6.0-alpha02 预发) | dl.google.com AGP maven-metadata | 批2 |
| org.jetbrains.kotlin | 1.9.22 | 2.4.21 稳定(2.5.0-Beta2) | maven-metadata | 批3 |
| kotlinx-coroutines-android | 1.7.3 | 1.11.0 | repo1.maven.org | 批1 |
| androidx.core:core-ktx | 1.13.1 | 1.19.1 | dl.google.com | 批1 |
| androidx.appcompat:appcompat | 1.7.0 | 1.8.0 | dl.google.com | 批1 |
| com.google.android.material:material | 1.12.0 | 1.14.0 | dl.google.com | 批1 |
| androidx.recyclerview:recyclerview | 1.3.2 | 1.4.0 | dl.google.com | 批1 |
| junit:junit | 4.13.2(v4 线已终结,4.13.2 为末版) | JUnit 5 最新 5.14.4(6.x 线 6.1.3) | repo1.maven.org | 独立任务 |

未核实项(置信度:低,本次会话 ML Kit 元数据抓取 404/失败,不作结论): `play-services-mlkit-text-recognition-chinese:16.0.0` 与 `force` 的 `common:17.1.0`(L37)、`androidx.exifinterface:exifinterface:1.3.6`(L49)、`compileSdk/targetSdk=34` 相对 2026 年 Play 商店 target 要求的滞后程度。

### 7.2 升级注意事项

- 批1 全部为 minor/patch,逐条独立提交,每次跑 `:app:assembleDebug :app:testDebugUnitTest` + regex-align 双端回归;
- 批2 AGP 9.x 需同步升级 wrapper(现 gradle-8.7),先单独做编译期回归;
- 批3 Kotlin 2.x 注意 `expect/actual`、默认参数与 new type inference 行为变化,先跑全量 33 单测;
- 每批完成后按工作流补开发日志 PDF(见附录 B 流程)。

## 8. 必须修复清单(发版前)

| # | 项 | 对应 finding | 工作量 |
|---|---|---|---|
| 1 | 删除/门控 `TestCommandReceiver` | §1.1 | 小(删文件+Manifest 块) |
| 2 | 日志脱敏(L62/L65/L73/L86/L37/L38) | §1.2 | 小(6 行改动+回归) |
| 3(建议) | `allowBackup` 关闭财务数据外流 | §2.4 | 小 |

## 附录 A: 30 天评审范围(2026-10-04 ~ 2026-11-03)

提交序列(`_review_commits.txt` / `_review_files.txt` 完整清单):

| 提交 | 日期 | 说明 | 改动面 |
|---|---|---|---|
| 665c11d | 10-23 | Initial commit | .gitignore, README |
| 889e9ba | 10-26 | 第一版:粘贴/截图OCR/通知监听 | 全应用主体(26 源文件)+ verify/杂物(§2.8) |
| 9f7145d | 10-26 | merge 第一版 | (无独有文件) |
| a191d83 | 10-26 | UI iOS 极简四页重构 | MainActivity + res 布局/主题 |
| d8ecbbe | 11-01 | 账单页笔数元素 + 双端 1:1 对齐 | 9 源文件 + 11 res + web 5 文件 |
| f9e5ce5 | 11-01 | 移除 AI 判断改本地规则(WIP 标签,实为完成态) | 9 源文件(含 LlmJudge 删除) |
| f9e92f4 | 11-02 | 规则页识别列表 + 改类型/丢弃/删除 | 5 源 + web 4 文件 |
| 2038e5b | 11-02 | 微信支付格式商户识别修复 + 双端回归 | 3 源 + 2 测试 + 日志 |

未跟踪文件(不计入评审,但为 §2.8 证据): `_md2pdf.py`、`_pull_db.py`、`_read_db.py`、`_build_ui.py`、`_check_bill.py`、`_final_demo.py`、`_find_tab.py`、`_junit.py`、`_manual.js`、`_parse_ui.py`、`_re.txt`、`_read_journal.py`、`_run3.bat`、`_screenshot.js`、`_srv8091.js`、`_type.js/.sh`、`_gitstatus.txt`、`_staged.txt`、`_commitmsg.txt`、`_commit1/2.txt`、`_msgcheck.txt`、`_push.txt`、`_gradle.txt`、`_judge.txt`、`_parser.txt`、`_review_files.txt`、``_review_commits.txt`、`_logcat*.txt`、`_bill_proof.png`、`_s_t2state.png`、`_db_pull.db`、`_ui*.xml`、`_2026-*.docx`、`CODEX_HANDOFF.md`、`web/*.bak`、`app/*.png`、`verify/*`(部分)等。

## 附录 B: 交付与调试流程(既有约定)

每完成一项 → 中文提交(`git commit -F` BOM-free UTF-8 消息文件)+ `git push` + 当日开发日志 `logs/devlog-YYYY-MM-DD.pdf`(python-docx 生成 docx → LibreOffice 转 PDF,`git add -f` 入库)。本报告同批交付。

---
*报告生成: 2026-11-03,基于 `2038e5b` 工作树 + 模拟器实测证据;未核实项均已标注低置信度。*
