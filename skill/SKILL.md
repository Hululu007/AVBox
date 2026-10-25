---
name: AVBox项目开发规范
description: 项目规则、通用原则、通用代码规范、交付验证与审查收敛约定(验证前置、审查停止标准、改动前自查) + 项目文档地图(索引与按需检索约定)
---

# 项目规则

- 禁止未经允许就将代码提交到远程仓库
- 禁止排除项目根目录下的 .key 文件夹（密钥入库）

# 通用原则

- 优先官方文档 > 官方最佳实践 > 稳定版本文档；与官方指导冲突时以官方为准
- 可维护性优先于开发速度，简洁性优先于不必要的复杂设计
- 禁止编造 API、文档内容、版本信息、性能数据、项目事实；不确定就查官方文档或明确说明
- 保持架构模块化、单一职责、低耦合高内聚、避免循环依赖、集中管理共享功能、不重复已有实现；除非明确要求，否则不改变项目架构
- 不要在已经错误的架构设计上持续打补丁，优先给出根治方案，哪怕是需要重构
- 新增代码必须与现有项目风格一致，统一为新写法，禁止新旧写法混用
- 优先渐进式改进，而非大规模重写
- 优先使用项目已有技术，没有充分理由不要引入新框架

# 通用代码规范

- **代码风格**：清晰命名、小型函数/类、不可变数据、可读代码、单一职责；避免超长方法、巨大类、重复代码、过深嵌套、魔法数字、不必要全局状态
- **分层职责**：UI层负责渲染与交互，业务层负责逻辑/状态/工作流程协调，数据层负责 Repository/网络请求/数据库/缓存；禁止绕过分层，业务逻辑不得直接访问具体数据源
- **状态管理**：使用单向数据流，状态集中管理，UI由状态驱动，避免维护重复状态
- **注释**：**全库零注释**（2026-10-06 用户指令：文档已完整，代码自解释）。只有三类例外可以存在：
  - **许可证块**：第三方派生文件（`subtitle/**` 等）的 `Copyright` / `Permission is hereby granted` / `SPDX-License` 声明——删了违反许可
  - **机器可读指令**：`// i18n: keep`（`i18n_gate.py` 的白名单，删了 ui 层硬闸门立刻变红）、`// null-assert: keep`（`null_assert_gate.py` 的行级豁免，删了该行断言立刻变红）、`//noinspection`
  - **禁止**任何解释性注释：含"为什么"、决策叙事（日期 / 规范章节号 §x.x / 出处 / 演进对比）——这些一律归 `skill/history/`
  - 批量删注释用 `.tools/strip_comments_all.py`（`--dry` 报告 / `--apply` 写回 / `--verify` 校验）；`--verify` 做的是"剥注释后的代码投影与 `git HEAD` 逐行一致"，这是唯一能证明"只动了注释"的手段。删完必须重跑 `prune_imports.py`（只在注释里被引用的 import 会变成未使用）
- **错误处理**：禁止静默忽略错误；使用统一错误处理方式，明确处理异常
- **空安全**：不用裸 `!!`。空属正常业务状态 ⇒ 守卫早退（`?: return` / `isNullOrEmpty()`）；数据本该存在 ⇒ `requireNotNull(x) { "英文消息" }`。**禁止用 `?.` 把崩溃改成静默跳过来"消除"断言**，也不得顺手改可空性标注 / 签名。存量已清零，新增断言由 `null_assert_gate.py` 拦截（判定口径见 `skill/avbox-code-review-spec.md` 的「空安全与非空断言」）
- **并发**：遵循项目现有异步编程方式；永远不要阻塞主线程；避免共享可变状态，确保线程安全
- **配置**：禁止硬编码 URL、版本号、配置参数；版本一律走 `gradle/libs.versions.toml`；适用情况下支持多环境配置
- **技术决策**：多方案需说明优缺点及对项目影响，不将某方案描述为唯一正确方案
- **代码质量**：新增代码应易读、易维护、遵循现有架构、保持向后兼容、具备可测试性
- **重构触发**：仅在存在 Bug、可维护性较差、性能明显受影响或明确要求时重构
- **修改说明**：改动公共 API / 核心业务逻辑 / 项目架构时，必须说明原因、收益、可能缺点及是否需同步修改相关组件

# 交付验证与审查收敛

- **禁止擅自跑 release 构建（2026-10-05 用户指令）**：`:app:assembleRelease` 未经用户明确许可一律不跑（单次 4–5 分钟，只为 R8/keep/混淆与契约层 release 侧 `javap` 比对服务）；日常验证只跑 debug 变体。
- **验证不得攒到最后**：每完成一组改动（或一轮审查修复）立即跑 `.\gradlew :app:assembleDebug` 与 `.\gradlew :app:testDebugUnitTest`；改配置解析 / 规则 / 字段取值的必须补单测锁口径（范例：`ConfigParserTest`、`HeaderGuardTest`）。纯人眼推理会把"会崩 / 会失效"级问题拖到很后面才暴露。
- **跑构建的三个坑（2026-09-23 实测，别再重复踩）**：①**不要用 `| Select-String … | Select-Object -First N` 过滤 gradle 输出** —— 管道会在拿到 `BUILD SUCCESSFUL/FAILED` 之前被截断，命令以非零码结束，极易误判成"构建失败"（本次为此白跑一轮）。要过滤就 `> log 2>&1` 落盘后再读。②**该日志是 UTF-16**，Git Bash 的 `grep` / `tail` 对它零命中（表现为"明明有输出却搜不到"），只能走 PowerShell 的 `Get-Content`。③Git Bash 里 `./gradlew` 报 `ClassNotFoundException: GradleWrapperMain`（jar 完好，脚本路径问题），用 PowerShell 的 `.\gradlew.bat`。判读结果以 `BUILD SUCCESSFUL` 与 `app/build/test-results/testDebugUnitTest/*.xml` 的用例计数为准，别只看退出码。
- **新增非空断言须过门禁（手动执行，项目暂无 CI）**：与 `i18n_gate.py` 同族，交付前跑 `python .tools/null_assert_gate.py`；判定域（`src/main` + Chaquopy）已归零，`libs/backdrop`/`quickjs` 两处 vendored 自 2026-10-25 起**剔出判定域、只打印**，故基线文件已删除（`--update` 会按需重建）；新增断言即 FAIL，行级例外用 `// null-assert: keep` 并逐条登记理由。
- **Composable 不得直连下层（手动执行，项目暂无 CI）**：跑 `python .tools/ui_layer_gate.py`。口径 = `ui/**` 里含 `@Composable` 的文件禁止 `import …osc.{api,base,data,sourcedata,net}.*`；`*ViewModel` 与 `AppBootstrap` 白名单放行。基线 `ui_layer_baseline.json`（2026-10-25 首冻：20 文件 / 35 处）**只允许下调**，收敛目标见 `skill/review/refactor-plan-20261025.md` §10。ViewModel / Activity / Controller 不在判定范围 —— 它们依赖下层是合理方向。
- **审查有终止线，不以"零发现"为目标**：换个角度总能找到东西，无限轮没有收益。可以收尾 = 连续一轮没有 阻断 / 高 / 中 级发现，且剩余发现全部属于"既有问题"或"口味差异"。每轮发现按「严重度」×「本次引入 / 既有 / 口味差异」两轴记账，严重度单调下降即可停。
- **改动前两项自查**：①直接沿用既有写法时必须连带抄它的防御与归一化，漏抄等于把既有缺陷一起复制进来；②改动全局字段 / 新增数据源 / 触碰"第 0 项""唯一"这类隐式约定时，先列出它打破的不变量与全部消费方。实例与修复过程见 `history/features.md` 的 2026-09-23 条目。

# 文档地图（先读这里，再按需检索）

| 文档 | 内容 | 何时读 |
| --- | --- | --- |
| `AGENTS.md`（仓库根） | **架构硬约束速查**：6 条包依赖不变量 + 交付前 5 道门禁命令 + 4 条红线；面向非 WorkBuddy 工具与新人，内容为摘要，权威仍是本文件与各活规范 | 开工前扫一眼；改包结构 / 加依赖 / 动契约层前必读 |
| `skill/avbox-mobile-ui-spec.md` | **活规范**：技术基线(§2)、信息架构与主题(§3)、各页面规范(§4)、视觉与组件约定(§5)、关键技术约束与已知坑(§6，含 **§6.12 订阅源配置与站点字段**)、未决清单(§7)、历史索引(§8) | 改动 UI / 页面 / 播放内核 / 依赖 / **订阅源解析与站点字段**前**必读**（读 §0–§7） |
| `skill/avbox-kv-mmkv-spec.md` | **迁移 Spec(已实施 2026-09-13)**：Hawk → MMKV 的现状盘点(键类型分布)、KV 门面设计、类型编码/加密/一次性数据迁移、分阶段实施与验收清单、决策记录 | 改动键值存储(新增/调整 KV 键、怀疑存储读写问题时)**必读**;§7 决策记录与文末修订记录是结论来源 |
| `skill/avbox-iso-playback-spec.md` | **活规范**：蓝光 ISO / 裸 m2ts 在 media3 上的 as-built —— 技术基线与设备边界(§1)、配置与入口判定(§2)、模块地图(§3)、**不变量 C1–C14**(§4:声明轨必须有 Format、包装层不得扣数据、`endTracks` 语义、BD `stream_type` 表、STN 语言、主视频 PID、去重选片、快照版本、VRS 序列、多 run、日志前缀、解码失败分轨类型、`open()` 非镜像长度)、已知坑与判据 17 条(§5)、运维与日志速查(§6)、**走查清单 1–12**(§7)、决策 D1–D8(§8)、未决 13 项(§9);过程与取证在 `history/iso-playback-chronicle.md` | 改动 `player/engine/iso/**`、`MediaSources` 的 ISO 分支、`PlayerEngine` 的 `isoSession` 语义,或**新增任何 `TrackOutput`/`ExtractorOutput` 包装层**前**必读**(至少 §4 + §5);播放/黑屏/无声/选错片类问题先看 §5 判据 |
| `skill/history/iso-playback-chronicle.md` | **历史归档(只增不改)**：8 个真实案例(甲)、14 条缺陷的完整解剖与盘面取证(乙,`§14.x`)、seek/倍速判负与被推翻的实验(丙,`§11.5–§11.10`)、多段盘与性能实测(丁,`§12.9`/`§13.x`)。**保留原始编号**,便于与旧日志对账 | 按需检索:某现象的日志原文、盘的事实、为什么某方案被判负。**默认不通读** |
| `skill/avbox-playback-service-spec.md` | **播放服务化 Spec(2026-10-06 重写为 as-built)**：播放器所有权归前台服务的**现状架构** —— `PlaybackService` 托管 + `PlaybackEngine` 持有 + 页面只挂摘；组件职责表、所有权/线程模型、挂摘协议与运行机制(遮黑帧 / 归属判定 / 内核复用 / 进度落盘 / 媒体会话与 FGS / 直播接管 / 预载 / 渲染宿主)、D1–D9 决策、风险登记、**§4 真机走查清单(编号 1–14 稳定;15–18 为 M7 换代新增,19 服务回收后内核去留、20 投屏「只解析」加固)**、P0–P5 与 M7/M10 实施记录 | 改动播放器归属/跨页复用/后台播放与通知/播放页职责划分/播放栈内核前**必读**;§4 是走查基线 |
| `skill/avbox-i18n-spec.md` | **多语言适配 Spec(四语全部落地:简体 / 英语 / 繁體台灣 / 繁體香港,代码完成待真机走查;2026-09-22)**：四语现状盘点(645 处硬编码文案、453 条去重)、中文参与数据与逻辑的红线清单(R1–R13)、资源组织与命名约定 + **§3.3 术语表**、`LanguageManager`+KV 方案与三处 Context 包裹、**`Trans` 门控(语言签名懒重建)**、D1–D6 决策点、四步(按语言)实施与进度表、验收清单与风险 | 改动文案/新增 UI 文案/做多语言前**必读**;§1.3 与 §9 是红线 |
| `skill/history/steps.md` | **历史归档**：Step 0–7 改造实施记录、Step 1 删除清单实际对账 | 按需检索：某类/布局/依赖当初为什么删、某步架构决策 |
| `skill/history/features.md` | **历史归档**：2026-09-09 起功能迭代记录（下拉刷新、隧道模式+AAC、配置管理页、主题设置页、顶栏改造、选集溢出修复、快搜删除、卡片点击分发…） | 按需检索：某功能当初怎么实现、为什么这么定、踩过什么坑 |
| `skill/history/preload-toast-deadlock.md` | **排查归档(根因已证;2026-09-27 已按方案 A 改磁盘预缓存,待真机验证)**：拖动进度条后预载 toast 概率不出现 = media3 1.11.1 预载完成死锁(时长目标 vs 32MB 字节闸门互不收敛,静默无回调);含源码证据链、撞顶码率阈值、修复选项 A/B/C、A 的实施方案(headers/cache key/mimeType 三处桥接)与真机验证判据 | 改动**预载/`PreloadManagerHolder`/`PreloadCoordinator`/预载读盘链路** 前必读;验证预载"完成/命中"类问题先读 |
| `skill/avbox-code-review-spec.md` | **代码审查 Spec**：把本文件整份当提示词喂给 AI 做**只读**审查。含角色定义与技术栈实况、既定约定(报了即误报)、高危约束、快速/深度(三批)模式与分批策略、审查清单与硬阈值(文件 >500 行 / 方法 >100 行 / 重复 ≥3 次 / 门面被 ≥20 文件依赖；含**空安全与非空断言**小节)、输出格式(第 0–6 节 + **附录 A 度量盘点** + **附录 B 上轮对账**)、审查者禁止事项、**审查收敛终止线** | 要做代码审查、或判断"本轮审查能否收尾"时**必读**；判断标准以文末「审查收敛」为准 |
| `skill/avbox-kotlin-migration-spec.md` | **活规范**：Java→Kotlin 迁移转换规范——M0 实测基线、每切片流程与卡口、Kotlin 静态/字段/构造器转换细则、契约层（`catvod`）逐条清单、Gson×data class 规范、并发不变量、`skill/scripts/verify-migration.ps1` 用法 | 改动 `com.github.catvod.**`、或改动被动态 jar / 第三方 AAR 按名字访问的宿主静态面前**必读** |
| `skill/avbox-okgo-removal-spec.md` | **活规范(已完成:N0–N5 全部实施,2026-10-06;待真机走查)**：OkGo → OkHttp + 协程迁移 —— 现状盘点(19 文件)与 okgo 源码取证(E1-E10)、await 层与全 Job 化取消设计(§4)、P1-P11 决策、N0-N5 分片计划与实测登记、验收口径与风险(R1-R14) | 改动 `util/net` 请求层 / `HttpClients` / 涉及 `cancelTag` 取消链 / 删 okgo 依赖前**必读**;§5 为分片实测登记(终态) |
| `skill/review/` | **审查报告存档**：`review-<YYYYMMDD>-batch<N>.md`(分批审查报告)与 `refactor-plan-*.md`(重构计划)；同批修复轮记录追加在同一文件(内容 = 改了哪些条目 / 构建与单测结果 / 回归面清单) | 接手上一轮审查结论、需要出「附录 B 上轮对账」时**必读** |
| 本地参考代码（只读） | 上游 TVBox 与 fongmi/OK影视 的本地副本 | 需要对照上游实现时（**只读参考，不改**） |

# 检索约定（按需搜索，不要通读）

- `history/` 约 80KB 历史，**默认不通读**；先用关键词定位命中行，再定点展开上下文：

```powershell
Select-String -Path skill\history\*.md -Pattern '顶栏','帧率' | Select-Object -First 20
Select-String -Path skill\history\features.md -Pattern '配置管理页' -Context 0,3
```

- **单一事实来源**：仍生效的规范写在 `avbox-mobile-ui-spec.md`；`history/` 只增不改，是过程与决策的存档。
- **新内容往哪写**：规范/约束 → 活规范对应小节；实施过程、补丁、排查记录 → `history/`（改造步骤进 `steps.md`，功能迭代进 `features.md`）。不要把过程叙述堆进活规范。
- **新增文档**：必须登记到上面「文档地图」，否则等于不存在（被 git 排除的是例外）。

# 已知高危约束（动手前扫一眼，详见 spec）

- **ISO 链路的两个"包装层"雷区**：①`ExtractorOutput.endTracks()` 一旦被转发，media3 就把之后新声明的轨丢进 `DiscardingTrackOutput` ⇒ 想延迟声明必须**扣住 `endTracks()`**；②任何 `TrackOutput` 包装层**不得扣留 sample data**（`H264Reader` 在发 Format 之前就写数据，扣了会让样本绝对位置变负，抛 `IndexOutOfBoundsException: off=...`）。另外"声明了轨却拿不到 Format"会卡死 `prepare`（media3 内部 4s 计时器，无 API）。三条都在 `avbox-iso-playback-spec.md` §4-C1..C3。
- **依赖裁剪不得只看宿主源码静态引用**：动态加载的爬虫 jar 需要宿主提供 gson / okhttp / **zxing** 等，删了会在运行期崩（spec §6.3）。
- **Exo 帧率匹配必须保持关闭**（`disableFrameRateMatching()`）：否则 ROM 会把整机刷新率降到 60Hz，表现为"播放时卡顿"（spec §6.1）。
- **爬虫阻塞调用必须在 IO 线程**（spec §6.2）。
- **KV(MMKV)复杂键必须在 `util/kv/KVKeySpec` 登记显式类型**，且禁止用匿名 `TypeToken` 捕获类型变量推元素类型（spec §6.7）；另注意 `KV.contains` 才是判存在性、`KV.get(key, def)` 分不清"键不存在"与"值就是 def"。
- **配置驱动的 header 必须过字符集过滤**（`ConfigParser.isHeaderNameSendable`/`isHeaderValueSendable`）：OkHttp 在构造请求时校验，越界抛 `IllegalArgumentException`，而站点请求分支没有 try/catch ⇒ 一份带中文 header 的配置会把 App 带崩（spec §6.12）。
- **规则表只能在 `parseJson` 入口清**（`VideoParseRuler.clearRule()`）：放进 `resetConfigData()` 会让换源失败时规则真空（在播内容广告回归/click 失效）；只在 `has("rules")` 时清则跨源残留（spec §6.12）。
- **崩溃标记只由"可能与源有关"的崩溃写入**（`BootGuard.looksSourceRelated` 的白名单 `IGNORABLE_FRAME_PREFIXES` 是唯一旋钮；无帧/判不出按"有关"）：平台层 + 宿主全包（`com.github.avbox.osc.`）算无关，`com.github.catvod.` 一律算有关；放宽到 catvod 等于让坏源重新把应用锁进启动崩溃。**标记为空时不再清零 `BOOT_LOADING_COUNT`**（2026-10-21 推翻旧口径），改为保留计数 + 读 `ApplicationExitInfo` 的 `REASON_CRASH_NATIVE`（8 秒窗口，只认 native，Java 崩溃仍走白名单）；停用粒度是 jar 不是订阅（spec §6.13）。
- **契约层（`com.github.catvod.**`）迁移后签名必须逐成员不变**：`open`/`@JvmStatic`/`@JvmField`/`@JvmOverloads` 只是手段，判据是 `javap -p -s` 输出等价；漏一处表现为"启动正常、点开该类源才崩"（细则见 `skill/avbox-kotlin-migration-spec.md` §3.2）。
- **交给爬虫的 proxy 参数值必须全 ASCII**（`JarLoader.proxyInvoke` 调 jar 前从副本剔除客户端专用参数 `siteKey`）：`/proxy` 的 params 会把播放器请求头并进来，第三方 jar 会把它**当 header 原样透传给上游**，值里有中文（如中文站点 key）即被 OkHttp 以 `Unexpected char` 拒绝、最终兜底成 HTTP 500，表现为"网盘源播放出错"且 logcat 无任何 App 日志（spec §6.12）。