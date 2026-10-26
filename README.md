# 支付通知自动记账(NotifyLedger)

支付成功弹窗 → 自动识别记账 → 统计看板。两个输入方向, 双端实现:

- **方向 A(主动)**: 用户粘贴支付通知文本 / 选择支付截图(截图为扩展点) / 手动录入一笔
- **方向 B(被动)**: 手机弹出含支付关键词的通知时, 系统监听并交给「判断链」: 支付类 → 识别保留并记账; 其他信息 → 直接放弃, 不记录

| 目录 | 说明 | 状态 |
|---|---|---|
| `web/` | 网页端原型(手机框架单页, 零 JS 依赖, localStorage 持久化), 即未来 App 的基础 | 功能完成, Node 测试 32/32 通过 |
| `app/` | Android 骨架(Kotlin, NotificationListenerService + SQLite), 与 web 解析器逐行对齐 | 代码完成, 本机无 Java/Gradle/Android SDK, 未编译(见下) |
| `web/test/regex-align.js` | Kotlin ↔ JS 正则 1:1 对齐校验脚本 | 通过(16 个 pattern 全部一致) |

## 1. 快速开始

### 网页端
浏览器直接打开 `web/index.html`(零依赖, 直接可用; 数据存 localStorage `notifyledger.v1`)。

### 网页端测试(Node)
```
node D:\apk\web\test\parser.test.js    # 5 组 32 断言, 全绿退出码 0
node D:\apk\web\test\regex-align.js   # JS ↔ Kotlin 正则对齐, 全一致退出码 0
```

### Android 端
用 Android Studio(AGP 8.5.0 / Kotlin 1.9.22 / JDK 17)打开 `app/` 编译即可。
**本机没有安装 Java、Gradle、Kotlin 编译器、Android SDK, 因此 Android 侧未经过编译验证** —— 这是交付说明里的诚实标注, 不是代码省略。
编译后启用通知监听: 设置 → 通知(或「通知使用权/通知监听」) → 允许「支付记账」应用。

## 2. 架构(方向 B 判定流程)

```
通知 → NotificationLedgerService.onNotificationPosted
  │
  ├─ PayNotifyParser.isPaymentRelated(text)   ← 关键词入口门
  │    (含 支付/付款/扣款/到账/退款 等任一标记才继续; 命中只代表"值得判断")
  │
  ├─ PayNotifyParser.parse(text)              ← 本地解析: 金额/商户/时间/来源
  │    (Success → 去重键 "文本|金额"; 失败 → 去重键 "原文本")
  │
  ├─ 5 分钟去重窗口(同一文本+金额不重复入账)
  │
  └─ 单线程守护 executor "notify-ledger-judge" 上运行判断链 JudgeChain:
       LlmJudge(启用时为主, OpenAI 兼容 HTTP, 超时 8s/20s, 异常→弃权)
       → RuleJudge(定论后备: 解析成功→入账并自动分类; 任何失败→按原因丢弃)
       第一个非弃权裁决生效; 全部弃权 → 丢弃("其他信息直接放弃")
```

**裁决语义(三态)**: `Keep(entry, via)` / `Discard(reason, raw, via)` / `Abstain`。
- 丢弃的「其他信息」**不写库**, 只在日志留痕(`Log.i "放弃"`)。
- 判断链在主线程绝不阻塞: 通知回调只做门控+解析+去重, 判断链(含可能的 AI HTTP)丢到单线程 executor。

### 数据与学习回路
- SQLite `notifyledger.db`: `entries`(流水) + `learned_rules`(学习规则, UNIQUE+COLLATE NOCASE, upsert = `INSERT OR REPLACE`)。
- 流水行点击 → 改分类 → `updateEntryCategory` + `upsertLearnedRule(商户→分类)`, 下次同商户自动归到该分类(学习回路闭合)。
- 统计: 本周/本月合计、分类占比、近 7 天(零值填充)。

### AI 判断层配置(扩展点, 暂无设置界面)
SharedPreferences `notifyledger_llm`, 四个 key:
| key | 默认 | 说明 |
|---|---|---|
| `enabled` | false | 是否启用 LlmJudge |
| `endpoint` | `https://api.openai.com/v1/chat/completions` | OpenAI 兼容端点 |
| `apiKey` | (空) | 密钥 |
| `model` | `gpt-4o-mini` | 模型名 |

启用后 `JudgeProvider` 让 LlmJudge 排在 RuleJudge 之前(主判), 响应只认单行 JSON `{keep, category, amount, merchant, time, reason}`; 解析不出/缺字段 → 弃权(不猜), 交 RuleJudge 兜底。

## 3. 单一事实来源与同步纪律(⚠️ 重点)

**`web/parser.js` 是解析语义的唯一事实来源; Kotlin `PayNotifyParser.kt` 是逐行镜像**(相同正则、相同 7 步商户清洗顺序、相同默认规则顺序、相同 9 例 selfTest)。

**已知不一致(已在 Kotlin 侧修复, 文档化在此)**:
1. JS 的 life(生活)关键词列表里「燃气」重复出现两次(Kotlin 已去重)。两侧分类结果不受影响(按顺序 first-match), 但修改默认规则时先改 parser.js 再镜像。
2. 逗号处理: JS 用正则 `raw.replace(/,/g, '')`, Kotlin 用等价的 `raw.replace(",", "")`(Kotlin 的 String.replace 是全文替换, 语义一致)。`regex-align.js` 已把这对等价列入白名单。

**每次改动解析行为的必做清单**:
- [ ] 改 `web/parser.js` → 同步改 `PayNotifyParser.kt`(正则字符串、顺序、默认规则)
- [ ] 跑 `node web/test/parser.test.js`(32 断言)
- [ ] 跑 `node web/test/regex-align.js`(正则 1:1)
- [ ] Android 侧跑 `./gradlew testDebugUnitTest`(JUnit 已就位: `app/src/test/java/.../PayNotifyParserTest.kt`, 镜像 JS 测试同 5 组)

## 4. Codex 接手清单(按优先级)

1. **编译验证**: 本机无 SDK, Android 侧尚未过编译。AS 打开 `app/`, 预期问题点: 资源引用完整性(8 个 XML 已全部通过良构检查), `EntryAdapter`/`MainActivity` 的 API 用法。
2. **截图 OCR(方向 A 扩展点)**: `MainActivity` 中 `btnPickScreenshot` 目前是诚实的占位提示(截图识别复用同一判断链)。建议: 本地 ML Kit text recognition / 或走 LlmJudge 多模态。
3. **数据库增量迁移**: `LedgerDb.onUpgrade` 目前是「drop+重建」的骨架写法, 正式版请改为增量 `ALTER`。
4. **AI 设置界面**: 目前 `LlmConfig` 只有 SharedPreferences 读写, 无 UI; 补一个设置页(4 个 key)。
5. **性能/健壮性**: LlmJudge 超时与重试策略、去重窗口持久化(当前为进程内 5 分钟窗口, 重启清空, 可接受)。
6. **发布前**: 去掉种子数据说明(`web/app.js` 首次启动注入 3 笔示例: XX便利店 ¥23.50 购物 12:47 / 美团外卖 ¥15.00 餐饮 12:30 / 豆浆油条铺 ¥8.00 餐饮 08:12, 与 Android 侧统计演示保持一致)。

## 5. 已运行验证(本交付附带的实际结果)

| 检查 | 结果 |
|---|---|
| `node web/test/parser.test.js` | 5 组 32 断言全部通过, 退出码 0 |
| `node web/test/regex-align.js` | JS 18 个正则字面量(16 pattern)↔ Kotlin 17 个 Regex()(15 pattern)+1 个已知等价, 全部一致, 退出码 0 |
| 8 个 Android XML 良构检查 | 全部合法(manifest / 2 layout / 4 values / mipmap / drawable) |
| Android 编译 | **未执行**: 本机无 Java/Gradle/kotlinc/Android SDK(已验证: 三者均未安装, ANDROID_HOME 为空) |

## 6. 目录速查

```
D:\apk
├── README.md                 ← 本文件
├── 支付通知自动记账流程图.svg   ← 架构流程图
├── web/                      ← 网页端原型 + 测试
│   ├── index.html / app.js / style.css / parser.js
│   └── test/parser.test.js / regex-align.js
└── app/
    ├── settings.gradle.kts / build.gradle.kts / gradle.properties
    └── app/
        ├── build.gradle.kts   (含 testImplementation junit 4.13.2)
        └── src/
            ├── main/java/com/example/notifyledger/
            │   ├── MainActivity.kt / NotificationLedgerService.kt / EntryAdapter.kt
            │   ├── parser/PayNotifyParser.kt   ← 镜像 parser.js
            │   ├── judge/{Judge,RuleJudge,LlmJudge,JudgeProvider}.kt
            │   ├── model/{Category,LearnedRule,ParseResult,LedgerEntry}.kt
            │   ├── data/{LedgerDb,LedgerRepository}.kt
            │   └── util/TimeUtil.kt
            ├── main/res/... (layout/values/mipmap/drawable, 全部良构)
            └── test/java/com/example/notifyledger/PayNotifyParserTest.kt
```
