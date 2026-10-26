# 继续手册（明天说"继续"，读这里即可）

## 项目目标
- 先做网页（本目录 web/），后做 App（D:/apk/app/）
- 功能 A：用户粘贴/上传截图 → AI/规则判断 → 保留支付相关 → 记账
- 功能 B：用户同意后自动读取支付信息 → 支付相关保留入账，其他信息一律丢弃、不展示、不落库

## 当前状态（已完成且已自测）
1. 判断层 judge() 已加进 D:/apk/web/parser.js
   - 三态：keep / discard / abstain（对齐 Android JudgeChain）
   - ruleJudge：本地规则定论（支付保留，收入/退款/非支付/缺金额/空 → 丢弃）
   - cleanOcrText：多行 OCR 文本清洗成一行
   - judge 支持传入 aiJudge（预留），AI 弃权/异常自动回落规则层
2. 网页 UI 已加（index.html / app.js / style.css）
   - 截图上传 → 本地 Tesseract.js OCR（CDN 懒加载，不上传、不联网判断）→ 走 judge 入账
   - 同意开关（consentToggle）：非支付内容一律丢弃、不展示、不落库
   - 导出 CSV（btnExport）
3. 测试
   - node web/test/parser.test.js  原 32 断言 全绿
   - node web/test/judge.test.js   新增 15 断言 全绿（保留/丢弃/学习规则/AI弃权/异常/OCR清洗）
   - 真实浏览器实测：支付入账、收入丢弃、非支付丢弃、自检 9/9 均验证通过

## 已知事项 / 注意
- 截图 OCR 依赖 CDN（tesseract.js），无网时 OCR 会失败，判断层仍可用
- 浏览器 localStorage 在 in-app 浏览器中可能不可写（save 已 try/catch 容错），内存态正常
- D:/apk/.srv.js 是临时静态服务（端口 8090），用完可删；测试时 node .srv.js 起服务

## 待办（明天继续）
1. [可选] 给截图 OCR 加本地离线方案（下载 tesseract.js 到本地，彻底不依赖 CDN）
2. [可选] CSV 导出已做；如需加导入，再加
3. App 端（D:/apk/app）：按 README「Codex 接手清单」推进
   - 编译验证（本机无 Android SDK）
   - 截图 OCR 扩展点（ML Kit / 多模态 AI）
   - 数据库增量迁移（onUpgrade 目前是 drop+重建）
   - AI 设置界面（LlmConfig 4 key 无 UI）
   - 同步 parser.js ↔ PayNotifyParser.kt（改 parser.js 后跑 regex-align.js）
4. 如需联网 AI 判断：接一个 OpenAI 兼容端点（LlmJudge 已写好，仅需配 endpoint/apiKey/model）

## 文件清单（web/）
- index.html / app.js / style.css / parser.js
- test/parser.test.js（原 32 断言）
- test/judge.test.js（新增 15 断言）
- .screenshot_ui.png（UI 截图）
- 运行：node .srv.js 起 8090，浏览器开 http://localhost:8090/


## 2026-10-22 下午 UI 升级完成（本轮）

### 做了什么
1. 修复 index.html 嵌套 bug（截图识别/隐私授权 section 误嵌套在模拟推送 section 里）
2. 顶部操作按钮包进 .topbar-actions 容器，窄屏不换行错乱
3. style.css 全部重写为设计 Token 系统：
   - 暖白背景 / 墨黑文字 / 森林绿强调 / 细分隔线
   - 430px max-width 居中，桌面端容器化 + sticky 底部导航
   - safe-area-inset-bottom 支持
   - 触控目标 44px，所有 button/select 视觉统一
   - 账本金额用等宽数字字体，删除按钮低视觉权重
   - 统计卡片 / 分类条形图 / 7 天趋势图全部重设样式
   - 自检结果列表统一卡片式
4. tabbar 定位修复：桌面端容器化时左偏移对齐 .phone 容器

### 验证结果
- 浏览器实测四个 tab（账本/规则/统计/自检）视觉正常，无横向溢出
- 功能回归：推送支付通知 +1 条、推送非支付 0 条，行为正确
- 自检 9/9 通过
- parser.test.js 全绿，judge.test.js 15/15 全绿

### 本轮改动文件（仅 CSS + HTML 结构修复，未改任何业务 JS）
- web/style.css（全部重写）
- web/index.html（嵌套修复 + topbar-actions + viewport-fit=cover）

### 待验证（尚未跑）
- 360px 视口下统计图表标签是否溢出（浏览器无法直接改视口，建议明晚用真实手机/模拟器验证）
- 底部安全区域（iOS 刘海屏）实际表现
