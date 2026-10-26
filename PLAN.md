# 通知记账项目 · 交接计划（给下一个会话 / 给自己）

> 新会话第一句话：「读 D:\apk2\PLAN.md，按计划继续。最终目的是做 App，先网页后 App，核心逻辑在 parser.js 不动，隐私原则：同意才读、非支付不保留。」

## 一、最终目标（不要忘）

**先做网页，后做 App。** 网页端是 App 的前身和验证场：网页端跑通的功能、判断逻辑、数据结构，未来全部平移进 Android App（`D:\apk\app`，DeepSeek 已写好的 Kotlin 骨架，与本目录同源）。**最终交付的是 App，不是停在网页。**

## 二、两个核心功能（业务不变）

1. **功能 A · 主动记账**：用户粘贴支付通知文本 / 上传支付截图（本地 OCR）→ 判断是否支付相关 → 提取金额/商户/时间/支付方式 → 确认入账 → 自动分类（学习规则优先）。
2. **功能 B · 受控读取**：用户**明确同意后**才识别；支付相关信息保留入账；**其他任何非支付信息一律丢弃、不展示、不落库、不外传**（隐私最小化）。

## 三、目录说明
D:\apk2
├── PLAN.md          ← 本文件
└── web
    ├── index.html   ← 页面结构（iOS 风格重构版：四导航+三弹层）
    ├── app.js       ← 页面编排层（接 parser.js API）
    ├── parser.js    ← ★ 核心判断引擎（解析/判断/拆段/分类/学习/自检），勿改
    ├── style.css    ← 视觉（iOS 极简，克制蓝 #2f7cf6）
    └── test
        ├── parser.test.js   ← 32 断言
        └── judge.test.js    ← 15 断言

原项目 `D:\apk\` 里还有 Android 端（`D:\apk\app`）和流程图 SVG，本目录是网页端工作副本。

## 四、当前进度

### 已完成（网页端）
- **判断引擎 parser.js**：`parse` / `judge`（保留/丢弃/弃权三态，对齐 Android JudgeChain）/ `splitTransactions`（一张多平台截图拆多笔）/ `classify` / `learn`（upsert 学习规则）/ `cleanOcrText` / `selfTest`（9 用例）
- **测试全绿**：parser.test 32 断言、judge.test 15 断言（保留/丢弃/学习规则/AI 弃权兜底/OCR 清洗）
- **关键词已扩充**：支付|付款|扣款|消费|缴费|买单|代付|充值|话费|油费|水费|电费|交易|结算；「有商户名+有金额」也算支付；收入/退款/到账/非支付照丢
- **多平台拆分**：`splitTransactions` 按金额位置切段，一段多笔→逐笔 judge 入账
- **截图 OCR**：本地 Tesseract.js（CDN 懒加载，不上传图片），识别后 cleanOcrText→splitTransactions→judge
- **iOS 风格重构版**：底部四导航（记账/账单/规则/分析）；齿轮进「设置与诊断」弹层；确认入账/账单编辑/规则编辑三个底部弹层；主色 #2f7cf6；430px 居中容器；safe-area 支持
- **数据**：localStorage 键 `notifyledger.v1`（entries + rules + consent），新旧数据兼容

### 待验证
- iOS 重构版 3 文件（index.html / style.css / app.js）是否已落盘到本目录，5 场景实测：①粘贴通知入账 ②截图识别 ③改分类存规则 ④删账单 ⑤统计同步

### 待做（网页收尾）
1. 落盘 iOS 重构版 + 5 场景验证 + 两套测试重跑
2. 截图 OCR 离线化（Tesseract.js 本地化，摆脱 CDN）
3. 确认账单页的搜索/筛选/CSV 导出/日期分组真实可用（重构版已写，需实测）

### 待做（App 端，最终目标，在 D:\apk\app）
4. 网页定稿逻辑与 Kotlin `PayNotifyParser.kt` 逐行对齐（纪律：改 parser.js → 同步 Kotlin → 跑 `node D:\apk\web\test\regex-align.js` → 跑 JUnit）
5. 补齐 Android Studio 接手清单（在 `D:\apk\README.md` 第 4 节）：编译验证、截图 OCR 扩展点（ML Kit / 视觉模型）、数据库增量迁移（onUpgrade 改增量 ALTER）、AI 设置界面（LlmConfig 4 key）
6. AI 判断层：Kotlin `LlmJudge`（OpenAI 兼容端点）已写好，默认关闭；网页端 `judge(text, rules, aiJudge)` 已预留 aiJudge 参数。接 AI 只需配 endpoint/key/model

## 五、铁律（任何时候不能破）

- **核心判断逻辑只在 parser.js / Kotlin 解析器，UI 层（app.js）不重写判断规则**
- 隐私：未同意不读取；非支付不保留、不展示、不落库
- 不删功能、不用假数据冒充真实数据、同一能力不在多页面重复实现
- 改 parser.js 必须同步 Kotlin + 跑两套测试 + regex-align
- 数据键 `notifyledger.v1` 保持兼容，重置演示不得误删真实账单（重置只清 entries，保留 rules）

## 六、运行方式

- 网页：`node D:\apk\.srv.js`（静态服务 8090），开 `http://localhost:8090/`；或工具可用时直接双击 `web\index.html`
- 测试：`node D:\apk2\web\test\parser.test.js`、`node D:\apk2\web\test\judge.test.js`
- App：Android Studio（AGP 8.5 / Kotlin 1.9.22 / JDK 17）打开 `D:\apk\app`；本机无 Java/SDK，未编译过（诚实标注）

## 七、新会话接手清单（按顺序）

1. 读本文件 + `D:\apk2\web` 全部文件
2. 确认 iOS 重构版已落盘；没有则用最近一次会话里给过的 3 段代码落盘
3. 跑两套测试 + 5 场景浏览器实测
4. 全绿后进入 App 端对齐（待做第 4 项）
5. 每完成一项，更新本文件「进度」部分再收工