# AVBox 多语言(i18n)适配 Spec:简体中文 / 英语 / 繁体中文(香港、台湾)

> 项目:AVBox(TVBox OSC fork;仓库根目录 = 本文件所在目录的上一级)
> 配套:先读 `SKILL.md`(通用规范 + 文档地图)与 `avbox-mobile-ui-spec.md`(§2 技术基线 / §4 页面规范)。
> 状态:**草案,未实施**(2026-09-21 盘点)。§5 决策点需拍板后才进入实施。
> **实施节奏(用户 2026-09-21 定):渐进式,按语言分四步** —— 简体中文 → 英语 → 繁体中文(台湾)→ 繁体中文(香港);每完成一个语言即回写 §6.1 进度表 + 追加 `history/features.md` + 同步双副本(哈希核对),本文档随步更新。
> 触发背景:用户 2026-09-21 提出"适配简体中文 / 英语 / 繁体中文(香港)/ 繁体中文(台湾)";本文 = 可行性评估结论 + 实施方案。

## 0. 摘要

四种语言 + 「跟随系统」。**难度中等偏下**:无架构障碍(Compose + 资源系统原生支持),成本集中在"把生产代码里 **645 处**硬编码中文字面量(去重 **453 条**)换成资源引用",真正的风险只有 6 处「中文参与持久化与逻辑判断」的红线(§1.3)。估算 **8–13 人日**;按语言分四步渐进交付(§6),**每步结束都是一个可发布的完整状态**(第 1 步承载全部外置工作量,后三步各是一个语言包)。

**实施后(2026-09-22)**:第 1 步(简体)**代码与静态验收完成** —— `i18n_gate.py` = ui 0 处 + 非 ui 0 处(全部外置或 `// i18n: keep`);`values/strings.xml` **424 条**(声明=引用);构建 + 230 单测全绿。第 2 步(英语)`values-en/strings.xml` **424 条**;第 3 步(繁體台灣)`values-b+zh+Hant/strings.xml` **424 条**(繁体基础层,用词取台湾)+ `Trans` 门控改造;第 4 步(繁體香港)`values-zh-rHK/strings.xml` **66 条**差异条目(未覆盖条目回落基础层);四语入口已全部放开,**均待真机走查**(§6 出口条件 C/D)。

实施前现状(2026-09-21 盘点):

- 全仓 `R.string.` 引用 **0 处**,`res/values/strings.xml` 只有 `app_name` —— i18n 基础设施为零,但也没有"资源/硬编码并存"的历史包袱;
- 文案主战场在 Compose(`ui/` + `player/ui/` 共 43 文件 435 处);layout XML 只有 2 个文件且无硬编码文本;`player/` fork 模块几乎无 UI 文案;
- 项目已有一套**数据侧**简繁字符映射(`crawler/js/Trans.java`,供爬虫与弹幕搜索用)。语言设置落地后把它的门控从"系统 Locale"改为"应用语言",源返回的片名/站点名即可跟随转繁体 —— 繁体适配的额外收益(§4.5)。

## 1. 现状盘点(2026-09-21 实测)

### 1.1 基础设施

| 项 | 现状 |
|---|---|
| `res/values/strings.xml` | 盘点时只有 `app_name`(`app` / `player` 模块各一份同名文件)；实施后 **424 条**(app 模块)；四语资源:`values-en` **424 条**、`values-b+zh+Hant` **424 条**(繁体基础层)、`values-zh-rHK` **66 条**(地区差异层,未覆盖条目回落基础层) |
| `R.string.` / `getString(R.string.)` 引用 | 盘点时 **0 处**；实施后 **424 处**(`i18n_check_keys.py`：声明=引用) |
| `res/xml/` | 无 `locales_config.xml`;manifest 无 `android:localeConfig` |
| 语言设置入口 | 盘点时无；实施后 = 偏好设置页顶部 `LanguageRow`(白名单**按步放开**,四语已全部放开:「跟随系统 / 简体中文 / English / 繁體(台灣) / 繁體(香港)」)。`Trans` 门控已随第 3 步改为跟随应用语言(§4.5) |
| layout XML 硬编码文本 | 0 处(全项目只有 `activity_main.xml` / `view_play_container.xml`)；实施后复核:除 `strings.xml` 外 `res/*.xml` 无文案 |
| 平台条件 | minSdk 24 / targetSdk 37 / compileSdk 37;appcompat 已在依赖;`supportsRtl="true"` 已声明 |

### 1.2 文案规模

| 范围 | 含中文的字符串字面量 | 去重 |
|---|---|---|
| 全仓(含单测) | 941 | — |
| **生产代码** | **645** | **453 条** |
| ├ UI 层(Compose / 页面) | 435(43 文件) | — |
| └ 非 UI 层(Toast / 异常提示 / 名字表) | 213(32 文件,需逐条甄别) | — |
| 单测 `app/src/test` | 265(无需翻译) | — |

**实施后(2026-09-22)**:生产 645 处全部归零(外置为资源 或 按 §1.3 红线/日志判据 `// i18n: keep`);落 `values/strings.xml` **424 条**;第 2 步落 `values-en/strings.xml` **424 条**(英语译文,不新增 key);单测 265 处保持不动。

文案密度 Top(生产代码,完整清单见附录 A):

| 文件 | 条数 | 备注 |
|---|---|---|
| `ui/page/SettingsPage.kt` | 37 | 设置 tab(分组标题 / 开关名 / 弹窗) |
| `ui/page/ConfigManagePage.kt` | 35 | 配置管理页 |
| `api/ApiConfig.java` | 33 | 源加载提示(Toast,源 msg 透传除外) |
| `player/PlaybackController.java` | 25 | 播放链路提示(Toast) |
| `ui/activity/LiveScreens.kt` | 21 | 直播页 UI |
| `player/ui/DanmuSheets.kt` | 20 | 弹幕设置面板 |
| `ui/activity/DetailScreens.kt` | 20 | 详情页 UI |
| `ui/page/PlaySettingsPage.kt` | 20 | 播放设置页 |
| `player/ui/SubtitleSheets.kt` | 19 | 字幕设置面板 |
| `player/controller/ComposeVideoController.kt` | 19 | 播放器提示 |
| `ui/page/ThemeSettingsPage.kt` | 19 | 主题设置页 |
| `util/PlayerHelper.java` | 17 | 播放器名/缩放名 + 外链播放器提示 |

通用词复用度高(去重收益明显):`返回` 9 / `取消` 6 / `播放` 6 / `默认` 5 / `重试` 5 / `全部` 5 / `直播` 5 …… ⇒ 资源 key 按「通用 / 域」两级拆分(§3.2)。

### 1.3 红线:中文参与数据与逻辑(翻译必须只动显示)

| # | 位置 | 中文值 | 性质 |
|---|---|---|---|
| R1 | `"硬解码"` / `"软解码"` 全仓出现 35 次(硬 26 / 软 9),集中在 `bean/LivePlayerManager`、`player/PlaybackController`、`player/controller/ComposeVideoController`、`ui/page/PlaySettingsPage` | `硬解码` / `软解码` | **KV 持久化值**(`HawkConfig.IJK_CODEC` / `EXO_DECODE`)+ 播放配置 JSON 值 + 逻辑判据(`equals("硬解码")` / 切解码写回)。显示可翻译,**值一律不动** |
| R2 | `viewmodel/SourceViewModel.java:235` | `name.endsWith("搜")` | 站点命名约定(数据规则),不能翻译 |
| R3 | `ui/activity/LiveEpgParser.kt:155` | `contains("未提供")` / `contains("暂无")` | EPG 文本内容判据(数据规则) |
| R4 | `util/FileUtils.java:536` | `contains("模板.js")` | 本地文件名约定 |
| R5 | `player/PlaybackController.java:1232` | `contains("歌词")` | 媒体文件名约定 |
| R6 | `crawler/js/Trans` 字表 + `DanmakuApi` 的 `Trans.t2s` | 简繁字表 | 数据转换(§4.5),不是 UI 文案 |
| R7 | `api/ApiConfig.addSuperParse` | `"超级解析"` | 解析名参与 `HawkConfig.DEFAULT_PARSE` 持久化 + `getName().equals()` 比较 ⇒ 值不动(第 1 步实测发现) |
| R8 | `viewmodel/SourceViewModel.createPushDetail` | `"推送"` / `"播放$" + url` / `InfoBean("播放")` | 合成 Movie 的结构化数据(type / 线路 flag / `名字$地址` 格式),进历史与播放链路 ⇒ 值不动(第 1 步实测发现) |
| R9 | `viewmodel/SourceViewModel` | `IllegalStateException("网络请求错误")`(9 处,含变体) | 只经 `convertResponse → onError → LOG.i` 进日志,无 UI 出口 ⇒ 不翻 |
| R10 | `ui/page/HistoryPage.kt` | `Regex("(\\d+)\\s*[集期]")` | 匹配源数据(片名/备注)里的集数标记,不是 UI 文案 |
| R11 | `api/ApiConfig.defaultIJKADS` | ijk 分组的 `"硬解码"` / `"软解码"` | R1 的另一处落点:该 JSON 是 KV 值与 `getIJKCodec(name)` 的比较键 ⇒ 值不动 |
| R12 | `ui/activity/DetailViewModel.kt` | `SOURCE_EMPTY_MSG = "数据列表"` | 源返回 msg 的"非错误"哨兵值(数据规则)⇒ 不翻 |
| R13 | `ui/activity/DetailViewModel.kt` | `Regex("(?i)(?:ep|第|e|[\\-\\.\\s])\\s?(\\d{1,4})")` | 从源侧标题里抽集数,关键词是数据规则 ⇒ 不翻 |

实施要求:改这些文件时只替换 UI 展示点;**R1–R13 的值点保持字面量不动**,行尾(或上一行)加 `// i18n: keep` 标记,由 §7-A 校验脚本白名单化。

显示侧覆盖(值不动、只换显示)已在第 1 步落地:`硬解码/软解码` → `player_decode_hard/soft`(PlaySettingsPage 用 `DecodeHard/DecodeSoft` 常量持值);`跟随点播源` → `live_follow_vod_source`;推送源名 → `source_push_agent`;`源N` → `live_source_index_name`。

### 1.4 已有资产:`Trans` 简繁映射

- `app/src/main/java/com/github/catvod/crawler/js/Trans.java`:约 2000 对简繁字符映射(单例 `Loader.INSTANCE`),`s2t` / `t2s` 双向;
- 出口:① JS 爬虫 `Global.s2t/t2s`;② 弹幕搜索 `api/DanmakuApi` 用 `Trans.t2s` 把片名转简体再查;
- 启用判据:**第 3 步已改造**为 `LanguageManager.isTraditional()`(按语言签名懒重建,见 §4.5);改造前是构造时 `Locale.getDefault().getCountry().equals("TW")`(一次性固化、与应用内语言无关,港区不生效);
- 能力边界:**只解决字形,不解决用词**(港"軟件" / 台"軟體"、"網絡" vs "網路")⇒ 繁体 UI 文案仍需人工准备,不能靠字表生成。

## 2. 目标与非目标

**目标**

- G1 应用内提供四种语言(简体 / 英语 / 繁体港 / 繁体台)+「跟随系统」;
- G2 全部用户可见文案走资源:所有 `ui/` 与提示类字面量外置,新增文案不得再硬编码中文;
- G3 语言切换**即时生效**(重建页面),播放不中断、KV 设置全部保持;
- G4 语言选择持久化(KV),冷启动直接按所选语言加载(不闪系统语言);
- G5 繁体档位下源数据的简繁转换跟随应用语言(改造 `Trans` 门控)。

**非目标**

- N1 不翻译采集源返回的内容(站点名 / 分类 / 片名 / 简介 / EPG 文本 / 爬虫 `msg`)—— 内容侧不可控,联网内容保持源语言;英语用户看到"英文 UI + 中文内容"是产品固有限制;
- N2 不交付日 / 韩 / 法等其他语言(本次只交四语 + 语言框架,新增语言只需加一个 `values-*` 目录);
- N3 不重构数据层(除 §4.2 Context 包裹与 §4.5 `Trans` 门控这类必要接线);
- N4 不引入 i18n 第三方库、不接 AppCompatDelegate 的自动语言存储(理由见 §5-D2 / §4.6)。

## 3. 语言清单与资源组织

### 3.1 语言与资源目录

| 实施步 | 语言 | 资源目录 | 命中(设备语言) |
|---|---|---|---|
| 第 1 步 | 简体中文(**默认**) | `values/strings.xml` | 默认兜底 + `zh-Hans` / `zh-CN` / `zh-SG` |
| 第 2 步 | 英语 | `values-en/strings.xml` | `en-*` |
| 第 3 步 | 繁体中文(台湾) | `values-b+zh+Hant/strings.xml`(繁体基础层,用词取台湾) | `zh-Hant` / `zh-TW` / `zh-MO` 及所有繁体兜底 |
| 第 4 步 | 繁体中文(香港) | `values-zh-rHK/strings.xml`(只放港台用词差异条目) | `zh-HK`(未覆盖的条目回落第 3 步的基础层) |

- **默认资源 = 简体**(不采用"默认英语"):现有原文直接落 `values/`(**424 条**),`zh-Hans` 设备零配置命中;非中/英设备(法/日等)回落简体,符合本项目用户主体。代价:将来若上架海外商店需把默认切英语(424 条一次性挪 `values-zh-rCN/`);
- **繁体结构 = 基础层 + 地区差异层**(对应第 3 / 4 步):`values-b+zh+Hant` 是繁体基础层(用词取台湾),香港在其上只覆盖差异条目(匹配优先级:地区限定 > 脚本限定 > 默认)⇒ 第 4 步不必维护第二份几乎相同的繁体;
- 若第 4 步盘点发现港台差异条目占比过高(经验阈值 >30%),再改为 `values-zh-rTW` + `values-zh-rHK` 两份独立目录(§5-D3);
- 落地前必须真机验证 `zh-TW` / `zh-HK` 设备的命中链路(方法见 §7-C);
- 本次只改 `app` 模块;`player` 模块的 `app_name` 不参与 UI,不动。
- **打包期过滤(2026-09-22 加)**:`app/build.gradle.kts` 的 `androidResources.localeFilters = ["en", "zh", "zh-rCN", "b+zh+Hant", "zh-rTW", "zh-rHK"]` —— 保留交付四语(简体=默认资源无需列)与依赖库的中文资源,剥离其余 ≈22 个语言变体。⚠️ 脚本限定必须写 aapt2 的 `-c` 语法 `b+zh+Hant`(见 §9-14);`localeFilters` 只做保留/剥离,**不会新增语言支持**。

### 3.2 资源命名约定

- 域前缀:`common_`(通用按钮/状态)、`settings_` / `config_` / `theme_` / `home_` / `detail_` / `player_` / `live_` / `search_` / `music_` / `toast_` / `dialog_`;
- 形式 `域_用途[_限定]`,全小写下划线,如 `common_back`、`settings_play_title`、`toast_network_error`;
- 同一文案必须复用同一 key(9 处"返回" = 一个 `common_back`),禁止同义多个 key;
- 带参文案用**带位置的占位符**:`<string name="detail_episode_count">共 %1$d 集</string>`;禁止代码里拼中文片段(`"共" + n + "集"`)。

### 3.3 术语表(第 2 步定,第 3/4 步沿用)

| 中文 | 英语 | 约定 |
|---|---|---|
| 点播 / 直播 | VOD / Live | 点播用缩写 `VOD`(短、避免行超长) |
| 源 / 站点 / 订阅源 | source / site / subscription | 「推送」源名 = `Push` |
| 线路 | line | `线路N` = `Line N` |
| 解析(超级解析) | parse / resolve | 超级解析 = `Super parse` |
| 弹幕 | Danmaku | 保留日语借词(二次元语境通行),**不译** bullet comment |
| 解码(硬解 / 软解) | Decoding(Hardware / Software) | 播放器底栏短标签 = `HW` / `SW` |
| 预载 / 缓存 / 缓冲 | preload / cache / buffer | |
| 投屏 | cast | |
| 节目单 | TV Guide | 节目单**数据内容**不翻(§2-N1) |
| 主题 / 配色风格 | theme / color style | 配色风格用 Material 官方名(`Tonal Spot` / `Vibrant` / `Fruit Salad`…) |
| 收藏 | favorites | |
| 换源 / 换线 / 换仓 | switch source / line / repo | |
| 净化 | purify | `M3U8 净化` = `M3U8 Purify` |
| 片头 / 片尾 | Opening / Ending | 播放器底栏在时长位显示 |

## 4. 技术方案

### 4.1 语言状态:`LanguageManager`(Kotlin,`util/`)

```
enum class AppLanguage(val tag: String?) {        // null = 跟随系统
    System(null), SimplifiedChinese("zh-Hans"), English("en"),
    TraditionalHK("zh-Hant-HK"), TraditionalTW("zh-Hant-TW"),
}
object LanguageManager {
    fun current(): AppLanguage       // KV 读取(未登记 KVKeySpec ⇒ 读取必须带默认值)
    fun set(lang: AppLanguage)       // 写 KV;由调用方触发页面重建
    fun available(): List<AppLanguage> // 已交付语言白名单(按步放开,§6)
    fun resolve(): Locale            // System → 系统 Locale(保留系统给的具体 tag)
    fun isTraditional(): Boolean     // 供 Trans 门控(§4.5)
    fun wrap(base: Context): Context // createConfigurationContext(带 locale 的 Configuration)
}
```

- KV 键 `app_language`(String,存枚举名);未登记 `KVKeySpec` ⇒ 读取必须带默认值(项目既有约定,同 `HomeSettings` / `SearchSettings`);
- **语言白名单按步放开**(`available()`):设置页「语言」入口只列已交付语言 +「跟随系统」—— 第 1 步只有简体,第 2 步加英语,第 3 步加"繁體(台灣)",第 4 步加"繁體(香港)";未交付的语言不得出现在入口(§9-8);
- 港 / 台两个枚举值是第 3、4 步分开交付的依据:选"繁體(香港)"走 `zh-Hant-HK` locale,未命中 `zh-rHK` 的条目自然回落基础层;同时供 `Trans` 判方向;
- **语言值只用于"选资源"与"Trans 门控",不进任何业务逻辑判断。**

### 4.2 Context 包裹(三个落点)

| 落点 | 位置 | 说明 |
|---|---|---|
| Application | `base/App.java` 新增 `attachBaseContext` | ① 先 `KV.init(base)`;② `super.attachBaseContext(LanguageManager.wrap(base))` |
| Activity | `base/BaseActivity.java` 新增 `attachBaseContext` | 包裹后再交给 `AppCompatActivity`(其 delegate 依赖包裹后的 base) |
| Service | `player/PlaybackService.java` 新增 `attachBaseContext` | 通知文案走 Service 自身 Context,不包裹会是系统语言 |

- ⚠️ `attachBaseContext` **早于** `Application.onCreate`,而 `KV.init` 目前在 `onCreate` ⇒ 必须在 `App.attachBaseContext` 里提前 `KV.init(base)`。`KV.init` 幂等(`MMKV.initialize` 幂等 + 同 ID 同模式返回同实例),`onCreate` 里那处保留不动;
- ⚠️ `KV.init` 内部用 `context.getApplicationContext()`,此时为 null,代码已有 `appContext == null ? context : appContext` 兜底,可直接传 `base`;
- 只做"MMKV 初始化 + 读一个 String 键",不在这里做 IO / 装 crash 记录器等(`BootGuard.install()` 等仍留在 `onCreate`);
- `wrap()`:`Configuration(base.resources.configuration)` 拷贝 + `setLocale(locale)`(API 24+ 自动同步 `LocaleList`),**不要直接改 `resources.configuration`**。

### 4.3 语言切换路径(2026-09-22 用户拍板:**重启后生效**)

用户在「设置 tab → 偏好设置」选中语言后(**写 KV 提前到"选中时"**,对齐参考项目 `示例文件/android` —— 它在 ViewModel 里选择即持久化,点重启时只剩重启本身):

1. 选中语言 → **立即** `LanguageManager.set(lang)` 写 KV(给 MMKV 落盘留出后续弹窗交互的时间),并记下原语言用于回滚;
2. 弹确认框:内容 = `settings_language_restart_message`(「重启后生效」),右下「确认」/ 左下「取消」(M3 `AlertDialog` 默认 `confirmButton` / `dismissButton` 位置);
3. 取消 / 点外部 / 返回键 → `set(原语言)` **回滚**,语言不生效(重启也不会变);
4. 确认 → `restartApp()` **立即执行(无延迟)**:`util/AppRestart.kt` = `startActivity(getLaunchIntentForPackage + CLEAR_TOP|NEW_TASK)`(同步 binder,返回即已在 AMS 登记)+ `Process.killProcess` —— AMS 发现目标 Activity 的进程已死,在新进程里重新拉起它(等同冷启动),重走 `App.attachBaseContext` 包裹,一次性全量生效(回首页、播放中断属预期)。

- ⚠️ 重启实现的三个坑(前两条 **2026-09-22 真机实测**):① **不要用 `AlarmManager` 登记后等触发** —— 进程已死而新进程未起的那段空档没有窗口 ⇒ 可见**黑屏**,且部分 ROM(vivo)在进程被杀后会先自动恢复一次 ⇒ 表现为**黑屏 + 重启两次**;② flags 用 `CLEAR_TOP` **而不是** `CLEAR_TASK`(清任务会让 AMS 失去要恢复的 ActivityRecord,反而起不来);③ **写 KV 必须在弹窗之前(选中时)完成,不要放在确认回调里紧跟 `restartApp`** —— KV(MMKV)写入有落盘窗口(`KV.java` 注释:约 1s),"写"与"kill"之间要靠弹窗交互那段间隔兜底;若确认时才写,就得在 kill 前加延迟,体验会与参考项目不一致;④ **确认后不要"同一帧"就 kill** —— `pending = null` 只是请求重组,弹窗要**下一帧**才从屏幕消失,同帧 `killProcess` 会把弹窗留在最后一帧(2026-09-22 真机现象:"点完确认后弹窗还在")。做法:置 `restarting` 后由 `LaunchedEffect + withFrameNanos`(等 2 帧,~32ms 无感)再调 `restartApp`。

**为什么不用"立刻生效"**(2026-09-22 评估结论):
- 立刻生效需要一整套运行时规则:三处 `attachBaseContext` 包裹 + 遍历 `AppManager.snapshot()` 逐个 `recreate()` + 长生命周期组件必须走 `LanguageManager.localized()` + 禁进程级文案缓存 —— 规则多、易踩(每条新文案都要选对取法);
- 且存在确定性缺陷:① **解析期取文案进 bean 的位置**(直播设置面板组名 / `源%d` / 默认线名)切语言后不刷新,需重解析直播配置;② 通知渠道名建后不可改(Android 机制);③ `recreate()` 丢页面临时状态(非 saveable 的 `remember`、`DetailActivity.fullScreen` 等普通字段 ⇒ 播放页可能从全屏退回预览);④ 播放页重建后的重挂路径、AutoSize × 包裹 Configuration 叠加,只有真机才能验(§8)。
- 重启方案把正确性从"运行时"搬到"启动时":只需 `attachBaseContext` 包裹一次(已实现),确定性更高;代价 = 切语言中断播放 + 回首页(用户接受)。

`LanguageManager.localized()` **保留**(在重启语义下依然正确且无害,长生命周期组件照旧走它);上一轮为「立刻生效」新增的 `AppManager.snapshot()` **已随该方案一并移除**(避免死代码,历史实现见 git)。

### 4.4 文案访问方式

| 上下文 | 用法 |
|---|---|
| Compose | `stringResource(R.string.xxx)`;组合外(VM 回调 / Toast)用 `context.getString(R.string.xxx)` |
| **无 Context 的 Java/Kotlin(数据层/ViewModel/Service 内的长生命周期对象)** | `LanguageManager.localized(App.getInstance()).getString(R.string.xxx)` —— ⚠️ Application / Service 的 base 只在创建时挂一次,切语言不会重挂,**直接 `App.getInstance().getString(...)` 会停在旧语言**;`localized()` 的结果按 `set()` 失效 |
| Java(页面/Activity/有 Context) | `getString(R.string.xxx)` / `mContext.getString(...)` |
| Java(静态工具方法,如 `PlayerHelper.getScaleName`) | 推荐**改为返回资源 id**,由调用点决定文案;备选"接收 Context 参数" |
| Service / 通知 | Service 内 `getString`(已包裹,§4.2) |
| Toast | `Toast.makeText(context, context.getString(R.string.xxx), ...)` |

- 硬性要求:`ui/` 与 `player/ui/` 层禁止出现中文字符串字面量(§7-A 卡口);
- `util/*` 里透传的源 `msg`(5 处)保持原样,不外置、不翻译。

### 4.5 `Trans` 门控改造(数据侧简繁,**已实施 2026-09-22**)

- 现状(改造前):`.trans = Locale.getDefault().getCountry().equals("TW")`,构造时固化,港区不生效、与应用内语言无关;
- **落地做法 = 方案①(语言签名懒重建)**:`Trans.get()` 先读 `LanguageManager.isTraditional()` 得到目标档位,与 `Loader` 缓存的签名比对,不一致才新建实例(`trans=true` 时才构建字表,简体 / 英语档仍零开销)—— 语言变化(含系统语言变化、KV 就绪后读到真实档位)都能自愈,不会残留旧方向。未用方案②(`reset()`):重建入口有三处(爬虫 `Global` / 弹幕 / 未来调用),签名自校验不依赖调用时序;
- ⚠️ **判据的「跟随系统」档必须回落系统 locale**(2026-09-22 升级兼容审查):`isTraditional()` 若只看 `tag.startsWith("zh-Hant")`,「跟随系统」档(tag = null)恒为 false ⇒ 系统语言 zh-TW / zh-HK 且**未在应用内选过语言**的用户会"UI 命中繁体资源、源数据不转"(旧版判据是系统国家 == `TW`,不会漏)。现判定 = 显式档看 tag,否则看系统 locale(script == `Hant` 或 region ∈ `{TW, HK, MO}`);显式「简体中文」即使在 TW 系统下也不转;
- 方向语义:`s2t` = 简体→繁体(繁体档下把源内容转繁体);`t2s` = 繁体→简体(**弹幕搜索 `DanmakuApi` 恒用简体查询,与 UI 语言无关,保持不变**);
- ⚠️ `Trans` 首次使用可能早于 `KV.init`(爬虫在 Application 早期加载):`LanguageManager.current()` 已容忍(读不到 = 跟随系统 → 非繁体),不抛异常;
- ⚠️ 缓存失效:重建方案下下次 `get()` 即按新签名;重启生效语义下进程重建,天然无残留。

### 4.6 系统集成(可选,默认不做)

Android 13+ 的「系统设置 → 应用 → 语言」需要 `res/xml/locales_config.xml` + manifest `android:localeConfig` + `AppCompatDelegate.setApplicationLocales`。

**默认不做**:AppCompat 在 API < 33 会把语言写进自己的 SharedPreferences(与"全仓无 SharedPreferences"红线冲突),且形成"系统设置 / App 内设置"双真值。若将来要接,应以系统 API 为唯一写入口,作为独立决策(**不与本次混做**)。

## 5. 决策点(实施前需拍板)

| # | 决策 | 推荐 | 备选与代价 |
|---|---|---|---|
| D1 | 默认资源语言 | **简体**(现文案直接落 `values/`) | 默认英语:424 条挪 `values-zh-rCN`,且 `zh-Hans` 必须精确命中否则回落英语 |
| D2 | 语言切换实现 | **自研 `LanguageManager` + KV**(§4.2) | `AppCompatDelegate.setApplicationLocales`:系统集成好,但 API<33 落 SP(破红线)、双真值源 |
| D3 | 港台资源结构 | **基础层 `values-b+zh+Hant`(台用词)+ 香港差异层 `values-zh-rHK`**(第 3 / 4 步) | 两份独立目录 `values-zh-rTW` + `values-zh-rHK`:无隐式回落,但 ~90% 内容重复需双份维护(差异条目 >30% 时改用) |
| D4 | 繁体档位粒度 | **港 / 台两个枚举,分第 3 / 4 步交付** | 合并为一个"繁体"一步交付:更省,但港台用词只能二选一 |
| D5 | 系统 per-app language | **本次不做**(§4.6) | 做:需处理 SP 与双真值 |
| D6 | 外语文案来源 | 机器初翻 + 人工校对(第 2 步实际做法:按统一术语表 AI 初翻,人工在走查阶段校对) | 纯人工:质量最高、周期长 |

> 落地情况(2026-09-22):**D1 / D2 已按推荐实施**(默认资源 = 简体;自研 `LanguageManager` + KV,未用 `AppCompatDelegate.setApplicationLocales`);**D6 已随第 2 步落地**(AI 按统一术语表初翻 → 英语走查阶段人工校对);D3 / D4 / D5 随第 3、4 步推进。

## 6. 分四步实施(每步 = 一个语言)与出口条件

四步顺序的必然性:**第 1 步必须承载"框架 + 全部文案外置"** —— 外置是逐文件的机械动作(抽 key → 替换 → 编译 → 该文件扫描归零),若拆散到四步,中间态长期是"一部分资源、一部分硬编码"的双体系,更难维护;简体是默认资源、原文即成品 ⇒ "外置"本身就等于"完成简体"。

| 步骤 | 语言 | 内容(该步做全) | 出口条件 | 状态 |
|---|---|---|---|---|
| 第 1 步 | 简体中文(默认) | ① i18n 框架:`LanguageManager`+KV 键 + 三处 Context 包裹(§4.2)+ `AppManager.snapshot()` + 设置页「语言」入口(此刻只放开"跟随系统 / 简体");② **全部文案外置**:UI 43 文件 435 处 + 非 UI 32 文件 213 处逐条甄别(附录 A);③ §1.3 红线打 `// i18n: keep`;④ `values/` 的值 = 现文案原文 | §7-A 静态扫描全绿(字面量=0、白名单齐);§7-B 红线不变;简体走查零行为回归(与改造前逐页对照);`values/strings.xml` = 实际外置数(**424 条**) | **代码完成**(2026-09-22 收口:全部文案外置完成,`values/strings.xml` **424 条**;§7-A 卡口全绿 = ui 0 处 + 非 ui 0 处;构建 + 230 单测通过;**待真机走查**;语言切换 2026-09-22 改为「重启后生效」,见 §4.3) |
| 第 2 步 | 英语 | ① `values-en/strings.xml`(**424 条**,来源见 §5-D6);② 语言入口放开"英语"(已放开 `delivered`);③ 英语逐页走查并修布局(§7-D) | §7-A key 集合对齐(**已达**:`i18n_align.py en` = key 集合/占位符/空值/空白全 PASS);英语走查无截断/挤压(高风险区逐个过);冷启动英语无中文闪现(§7-C) | **代码完成**(2026-09-22:资源 424 条 + `delivered` 放开 English + `assembleDebug` 绿;**待真机走查** §7-C/D) |
| 第 3 步 | 繁体中文(台湾) | ① `values-b+zh+Hant/` 繁体基础层(用词取台湾,**424 条**);② **`Trans` 门控改造**(§4.5:跟随应用语言 + 切语言缓存失效,采用"语言签名懒重建");③ 语言入口放开"繁體(台灣)";④ 繁体走查 | §7-A key 对齐(**已达**:`i18n_align.py b+zh+Hant` = 424=424 PASS);`zh-TW` 系统语言命中链路验证(§7-C);源数据(片名/站点名)在繁体档确实转繁体 | **代码完成**(2026-09-22:基础层 424 条 + `Trans` 改造 + 入口放开 + `assembleDebug` 绿;**待真机走查** §7-C/D) |
| 第 4 步 | 繁体中文(香港) | ① 盘点港台用词差异条目 → `values-zh-rHK/`(只放差异,**66 条**:網絡 / 軟件·互聯網 / 緩存 / 隊列 / 列表 / 導航 / 控件 / 視頻 / 音頻 / 屏幕·全屏 / 點播 / 超時 / 地址 / 獲取·資訊 / 文件夾·數據 / 本地 / 線程 / 記錄·項 / 死機 / 收起 / 訪問·項目);② 语言入口放开"繁體(香港)";③ 香港用词走查 | 差异条目全部落地(**已达**:`i18n_align.py zh-rHK --subset` = 66 条子集 PASS,0 多余/0 冗余(与上级层同值)/0 占位符失配);`zh-HK` 命中链路验证(含"未覆盖条目回落基础层"的行为确认) | **代码完成**(2026-09-22:审查补齐后 66 条;**待真机走查** §7-C/D) |

**每步完成的固定动作(不执行 = 该步未完成)**:

1. 回写本节进度表(状态改"已完成" + 完成日期 + 偏差备注);
2. `skill/history/features.md` 追加一条实施记录(改了什么 / 验证方式 / 遗留);
3. 双副本同步:`.codebuddy/skills/android/` 覆盖 + 哈希核对;
4. 若该步改动了 §1–§5 的口径(文案条数 / 资源结构等),就地更新对应章节。

> 单文件推进方式:一个文件一次做全(抽 key → 替换 → 编译 → 该文件扫描归零),不要"先全仓替换再统一补资源"。

## 7. 验收清单

> 按步取用:A(静态)/ B(红线)在第 1 步全量执行,其后每步回归;第 2 / 3 / 4 步各完成一次对应语言的 C + D 走查。
>
> **2026-09-22 状态**:A 全绿(卡口 ui 0 / 非 ui 0;四语资源对齐 `i18n_align.py` 全 PASS:`en` 424=424、`b+zh+Hant` 424=424、`zh-rHK` 66 子集[0 冗余]);B 静态面通过(§1.3 值点全部 `// i18n: keep`,KV/JSON 值未动);C / D 待真机走查(四语;英语重点看截断/挤压,繁体重点看源数据简繁转换)。
>
> **2026-09-26 复核**:上记条数为当日快照,其后增量已使其漂移 —— 当前四语声明数 = `values` **435** / `values-en` **435** / `values-b+zh+Hant` **435** / `values-zh-rHK` **71**(子集,与基础层无同值项)。本次新增 2 条:`history_incognito`(无痕下历史页空态)、`search_incognito`(无痕下搜索页空态);港层两处均用「記錄」以区别于基础层「紀錄」。

**A. 静态检查(脚本可复现)**

- `ui/`、`player/ui/` 下含中文的字符串字面量 = 0(白名单 = §1.3 R1–R5 的 `// i18n: keep` 行);
- `values-en` / `values-b+zh+Hant` 与 `values` 的 key 集合**逐一对齐**(无多无少),`values-zh-rHK` 为**子集**(允许少、禁止多);校验工具 `.codebuddy/tools/i18n_align.py <lang> [--subset]` = **key 集合 + 占位符(含 `%%`、是否带位置) + 空值/首尾空白 + 简繁残留提示**(CJK 残留检查只对 `en` 开,繁体层改 `S2T-SUSPECT` 提示项) —— 2026-09-22 三目录全 PASS:`en` 424=424、`b+zh+Hant` 424=424(S2T-SUSPECT 仅 1 条 = 语言名 endonym「简体中文」)、`zh-rHK` 66 子集(0 多余/0 冗余/0 占位符失配;`--subset` 模式含 `REDUNDANT-VS-UPPER` 检查 = 差异层里与上级层同值的条目);
- `values-zh-rHK`(差异层)的 key 必须是 `values` 的**子集**(允许少、禁止多;缺失条目按预期回落基础层);
- 无"中文片段拼接"(`"共" + n + "集"` 形态);
- 占位符一致性:各语言条目的 `%1$s` 参数齐全;
- ⚠️ **扫描盲区 1:`\uXXXX` 转义中文** —— 状态机只认原字符,转义写法不命中。2026-09-22 审查专项扫描(全仓 118 处):**显示值**已补齐(轨道前缀 Ijk/Exo 的音轨·视轨·字幕、Exo 声道标签 单声道/立体声/`%1$d 声道`),剩余 100 处均为**数据判据**(`ExoPlayer`/`IjkMediaPlayer` 语言映射表、`DanmakuApi` 电影·国语·粤语判据、`EpisodeMatcher` "第"、`SourceViewModel` "豆瓣"、`ExoPlayer` "未知" 过滤),按 keep 处理、英语步前复核;
- ⚠️ **扫描盲区 2:日志豁免正则** —— `i18n_hardcoded_scan.LOG_HINT` 原含过宽模式 `KL`/`TAG,`(任意含此串的行都豁免);2026-09-22 收紧为 `LOG\.|Log\.[dviwe]|printStackTrace|System\.out|DebugLog|logger\.|LogUtils`,严格模式对拍 **0 假阴性**。

**B. 红线校验**

- 四种语言下 KV `ijk_codec` / `exo_decode` 与播放配置 JSON 的 `ijk` / `exo` 字段仍是 `硬解码` / `软解码` 字面量;
- 切语言前后 R2–R5 行为不变(搜索源识别 / EPG 空态 / 模板.js 判定 / 歌词识别)。

**C. 语言机制**

- 冷启动:选"英语"并重启后,首帧即英语(无中文闪现);
- 切换:设置页选中语言 → 弹「重启后生效」确认框;**确认后应用自重启**,重启后全量生效(回首页、播放中断属预期);
- 取消:语言不生效(KV 未写,重启也不会变);
- 跟随系统:第 3 步起,系统语言 `zh-TW` 显示繁体基础层;第 4 步起,`zh-HK` 显示香港用词、未覆盖条目回落基础层;
- 语言入口(每步):入口只出现"已交付语言 + 跟随系统",不出现半成品语言。

**D. 四语言真机走查(vivo V2425A,用户操控)**

- 逐页(4 tab + 设置系 7 页 + 详情 / 播放 / 直播 / 搜索 / 音乐)检查:无中文残留、无截断溢出(英文重点)、按钮不换行挤压布局;
- 高风险区:顶栏(胶囊 / 标题槽)、设置行(`SettingsCard`)、播放器底栏与各面板;
- 截图留档;量间距方法见项目记忆(截图亮度游程 ÷ 像素密度)。

## 8. 风险登记

| 风险 | 等级 | 处置 |
|---|---|---|
| 英文超长导致布局溢出 | 高 | 第 2 步逐页走查;`Text` 补 `maxLines` / `TextOverflow`;长条目给英语短版 |
| 红线值被误翻(R1 的 35 处) | 高 | `// i18n: keep` + §7-A/§7-B 校验(第 1 步全量,后续每步回归) |
| `AutoSize`(design 1280 + 全局 density 覆写)与包裹 Context 叠加 | 中 | 第 1 步真机验证字宽 / 字号自洽;AutoSize 只改 density 不动 locale,理论无冲突 |
| `Trans` 缓存不失效,切语言后数据层仍按旧方向转 | 中 | §4.5 的 `reset()` / 语言签名;`KV` 未就绪时兜底系统语言 |
| 港台设备未命中 `b+zh+Hant` | 中 | §7-C 真机验证,失败补地区目录 |
| Service / 通知文案漏包裹 | 中 | §4.2 落点 + 通知文案检查 |
| 翻译术语不一致(播放内核/解码/预载/投屏/弹幕/直播) | 低 | 先定术语表再翻正文 |

## 9. 关键坑(实施前必读)

1. **`attachBaseContext` 早于 `KV.init`**:语言判定依赖的 KV 必须在 `App.attachBaseContext` 内提前 `KV.init`(幂等,可重复)。
2. **`values-b+zh+Hant` 是脚本限定不是地区限定**:港台用词差异只能靠 `values-zh-rTW` / `values-zh-rHK` 覆盖(优先级:地区 > 脚本 > 默认)。
3. **`Trans.pass()` 语义是"不转换" = `!trans`**:改造别把方向弄反;`t2s` 给弹幕搜索的语义恒为"转简体",与 UI 语言无关。
4. **不要翻译 R1–R5 的字面量**,也不要顺手把 `HawkConfig` 的键名/值改成英文(键名是持久化契约)。
5. **`stringResource` 只能在 Composable 内用**;VM / 回调 / Java 里用 `context.getString`。
6. **占位符必须带位置**(`%1$s`),多语言重排参数才不会错位。
7. 切语言**要重启进程**(2026-09-22 用户拍板反转口径);正确姿势(照搬 `示例文件/android`)= **选中语言即 `LanguageManager.set` 写 KV(提前写,留落盘时间) → 确认后 `restartApp()` 立即 `startActivity(CLEAR_TOP|NEW_TASK)` + `killProcess`**;取消则 `set(原语言)` 回滚。⚠️ 四个反例:① 遍历 `AppManager` 快照 `recreate()`(丢页面临时状态、解析期文案不刷新,见 §4.3);② `AlarmManager` 登记后等触发(实测**黑屏 + 重启两次**);③ `CLEAR_TASK`(AMS 失去要恢复的 ActivityRecord);④ 把 `set` 放在确认回调里紧跟 `restartApp`(写与 kill 间隔≈0,可能丢设置);⑤ 确认回调里 `pending = null` 后**同帧** `restartApp`(弹窗残留最后一帧,须等 1~2 帧)。
8. **语言入口按步放开**(`LanguageManager.available()`):未交付的语言不得出现在设置页 —— 否则用户切到半成品语言(缺的条目回落简体)会误报成 bug。
9. **资源里的撇号必须转义或改写**:Android 字符串中裸 `'` 会构建失败(`Apostrophe not preceded by \`)⇒ 英文文案优先改写(用 `cannot`、避免属格),必须使用时写 `\'`;双引号同理(`\"`,或按第 2 步做法改用弯引号 `“ ”`)。
10. **语言名条目用 endonym(各自语言的写法)**:语言选择列表里写 `简体中文` / `繁體(台灣)` / `English`,不按界面语言译成 "Simplified Chinese"(与系统语言选择器一致);`i18n_align.py` 的 CJK 残留白名单正是这三个 key。
11. **英文允许"同值多 key",简中不允许**:英文的区分粒度低于中文(`上一个` / `上一首` / `上一集` 都可能译 "Previous")⇒ `i18n_align.py` 不做重复值检查;`values` 侧"同文案必须同 key"仍由 `i18n_check_keys.py` 把关。
12. **繁体结构 = 基础层 + 地区差异层**:`values-b+zh+Hant` 是脚本限定(繁体基础层,用词取台湾),`values-zh-rHK` 只放港台用词差异(未覆盖条目自动回落基础层;匹配优先级:地区 > 脚本 > 默认)⇒ 新增港台差异只改差异层,不要把地区词写进基础层。
13. **繁体文案不能靠 `Trans` 字表生成**:字表只解决字形,不解决用词(網絡 / 網路、快取 / 緩存、執行緒 / 線程、佇列 / 隊列、導覽 / 導航…)⇒ 必须人工按台湾 / 香港用词撰写;`i18n_align.py` 的 `S2T-SUSPECT` 只能提示"含可转换字形"(漏转),简繁同形字会假阳性,仍需人工核对。
14. **`localeFilters` 的值是 aapt2 的 `-c` 语法,不是 BCP-47**:AGP 原样透传给 `aapt2 -c` ⇒ 脚本限定必须写 `b+zh+Hant`(写 `zh-Hant` 直接构建失败 `invalid config 'zh-Hant' for -c option`);且它是**保留白名单** —— 漏列 `b+zh+Hant` 会让 `values-b+zh+Hant` 被剥离,**繁體(台灣/香港)档大面积回落简体**;体积收益只是 KB 级(APK 大头是 .so / Python / QuickJS),别把它当瘦身手段。

## 附录 A:实施清单(按文件,2026-09-21 统计)

> 本附录的 645 处**全部在第 1 步**完成(抽 key + 替换调用点);第 2–4 步只新增 `values-*` 资源文件,不动代码(仅 `Trans` 门控与走查发现的布局微调例外)。
>
> **完成情况(2026-09-22)**:645 处已全部归零 —— 绝大多数外置为资源,少部分按 §1.3 红线/日志判据改为 `// i18n: keep`;卡口实测 **ui 0 处 + 非 ui 0 处**。

**A.1 UI 层(43 文件 / 435 处)** —— `ui/page/`:`SettingsPage` 37、`ConfigManagePage` 35、`PlaySettingsPage` 20、`ThemeSettingsPage` 19、`PreferenceSettingsPage` 17、`HomePage` 15、`HistoryPage` 14、`MainScreen` 9、`PreloadSettingsPage` 8、`HomeGridLayout` 7、`CollectPage` 7、`HomeViewModel` 2、`VodCardAction` 1;`ui/activity/`:`LiveScreens` 21、`DetailScreens` 20、`SearchActivity` 13、`LivePlayActivity` 11、`DetailViewModel` 8、`SearchScreens` 7、`LiveEpgParser` 4、`PartitionListActivity` 3、`DetailActivity` 3、`MusicPlayerActivity` 2、`LivePlayViewModel` 2、`ConfigManageActivity` 1;`ui/components/`:`SearchSettingsSheet` 10、`VodCardMenu` 7、`ThemeColorPickerSheet` 5、`FilterSheet` 3、`VodCard` 2、`HeroCarousel` 2;`ui/music/`:`MusicPlayerScreen` 11、`MusicPlayerState` 3;`ui/theme/`:`ThemeConfig` 17、`Theme` 2;`ui/player/`:`PlayContainer` 16;`player/ui/`:`DanmuSheets` 20、`SubtitleSheets` 19、`CastSheet` 13、`PlayerBottomBar` 10、`PlayerOverlay` 4、`PlayerLayers` 4、`PlayerTopBar` 1。

**A.2 非 UI 层(32 文件 / 213 处,逐条甄别)** —— `api/`:ApiConfig 33、SpiderLoader 5、DanmakuApi 4、ConfigParser 1;`player/`:PlaybackController 25、PlayUrlResolver 15、ComposeVideoController 19、PlaybackService 6、M3u8PurifyUseCase 2、PlayerUiState 2、IjkMediaPlayer 2、ExoPlayer 1;`util/`:PlayerHelper 17、Thunder 13、LocalConfigHelper 11、DefaultConfig 6、OkGoHelper 4、Proxy 2、FileUtils 2、KV 1、HistoryHelper 1、EpisodeTotals 1、parser/SuperParse 1、live/TxtSubscribe 1;`bean/`:LivePlayerManager 9;`viewmodel/`:SourceViewModel 15;`dlna/`:DLNACastManager 2;`crawler/`:RSAEncrypt 6、Trans 2、JsSpider 1;`python/java/`:PythonSpider 2、PyLog 1。

## 附录 B:统计口径与复现

- 统计脚本(本地审计工具,未入库):`.codebuddy/tools/i18n_hardcoded_scan.py` —— 状态机剥离注释与字符边界,只统计"含中文的字符串字面量",按所在行是否日志上下文分类;输出同目录 `i18n_scan_out.json`。
- 第 1 步新增的配套工具(同在 `.codebuddy/tools/`,未入库):`i18n_file.py <file...>`(逐文件列字面量+行号)、**`i18n_gate.py`(§7-A 卡口:ui 层硬闸门 + `// i18n: keep`[本行或上一行] + 日志豁免)**、`i18n_check_keys.py`(声明↔引用一致/重名/未用/**重复文案**/首尾空白)、**`i18n_align.py <lang> [--subset]`(第 2/3/4 步:多语言资源与 `values` 的 key 集合/占位符/空值逐条对齐;`--subset` = 地区差异层只允许少;繁体层以 `S2T-SUSPECT` 提示简繁漏转)**、`i18n_fidelity.py` + `i18n_verify_long.py`(HEAD 版原字面量 vs 资源值,逐字符含 `\n` 转义还原)。
- 复核(Win 下 PowerShell,口径略宽含注释,看趋势够用):

```powershell
Get-ChildItem -Recurse app\src\main\java -Include *.kt,*.java |
  Select-String -Pattern '"[^"]*[\u4e00-\u9fff]' | Measure-Object
```

- 关键数字(2026-09-21):生产 645 处 / 453 条去重;UI 层 435(43 文件);非 UI 213(32 文件);单测 265(不计)。
- 关键数字(2026-09-22 收口+审查修复后):卡口 **0 处**(ui 0 / 非 ui 0);`values/strings.xml` **424 条**(含语言重启新增的 `settings_language_restart_message`);`// i18n: keep` 78 行;构建 + 230 单测全绿;`\uXXXX` 转义中文 100 处(均为数据判据,盲区登记见 §7-A);54 个文件 CRLF/LF 混用(历史遗留,提交时 git 归一化)。
- 关键数字(2026-09-22 第 2 步):`values-en/strings.xml` **424 条**(= `values` 的 424,key 集合/占位符零差异);`LanguageManager.delivered` = 「跟随系统 + 简体中文 + English」。
- 关键数字(2026-09-22 第 3/4 步):`values-b+zh+Hant/strings.xml` **424 条**(繁体基础层,`i18n_align.py` 全量 PASS,`S2T-SUSPECT` 1 条 = 语言名 endonym);`values-zh-rHK/strings.xml` **66 条**(差异层,子集 PASS + `REDUNDANT-VS-UPPER` 0;2026-09-22 审查补齐 15 条、删冗余 2 条);`Trans` 门控 = `LanguageManager.isTraditional()` + 语言签名懒重建;`delivered` 四语全放开。

## 修订记录

| 日期 | 修订 | 说明 |
|---|---|---|
| 2026-09-21 | 初版 | 可行性评估 + 实施方案;数据来源 = `.codebuddy/tools/i18n_hardcoded_scan.py` 实测(645 处 / 453 条) |
| 2026-09-21 | 实施节奏改为"按语言四步" | 用户要求"渐进式适配,分四步,对应四个语言,每完成一个语言及时更新":§3.1 增"实施步"列并重定义繁体结构(基础层 + 香港差异层)、§4.1 增语言白名单 `available()`、§5-D3/D4 改为港台资源结构与分步交付、§6 由 P0–P4 改为四步 + 进度表 + 每步固定动作、§7 增按步取用说明与入口验收、§8/§9 同步 |
| 2026-09-22 | 第 1 步实施(进行中) | 框架落地(`LanguageManager` + 三处 `attachBaseContext` + `AppManager.snapshot()` + 语言入口,「跟随系统」不包裹);外置 33 文件 ≈465 处、`values/strings.xml` 344 条;§1.3 新增 R7–R13 红线;§4.4 增"无 Context 取文案"行(长生命周期组件走 `LanguageManager.localized`,直用 `App.getInstance().getString` 会停旧语言);§6 状态改"进行中";附录 B 增 4 个配套工具;双副本已同步核哈希 |
| 2026-09-22 | 审查修复(gate 盲区) | ① `\uXXXX` 转义显示值补外置:`IjkMediaPlayer` 音轨/字幕、`ExoPlayer` 音轨/视轨/字幕 + 单声道/立体声/`%1$d 声道`(新增 `player_channel_mono/stereo/count`)—— 转义数 118→100,剩余全为语言映射表等数据判据(keep);② `i18n_hardcoded_scan.LOG_HINT` 收紧(去掉过宽 `KL`/`TAG,`,严格对拍 0 假阴性);③ 复核:`assembleDebug` + 230 单测全绿、卡口仍全绿、`res/*.xml`(除 strings)与 `player/` fork 模块无 UI 文案、`localized()` 调用点全传 Application;④ §7-A 增两条"扫描盲区"登记 |
| 2026-09-22 | 第 1 步文案外置收口(卡口归零) | ui 层 39→0、非 ui 层 138→0:`i18n_gate.py` = ui 0 / 非 ui 0;`values/strings.xml` **420 条**(声明=引用,无重名/未用/重复值/首尾空白);ui 31 处外置(含 contentDescription、`FilterSheet`/`HeroCarousel` 带参、`MusicPlayerState` 改 `@StringRes labelRes`、`HomeViewModel`/`LivePlayViewModel` 加 `str()`) + 非 ui 54 处外置(`PlayUrlResolver`/`Thunder`/`LocalConfigHelper`/`DanmakuApi`/`SpiderLoader`/`M3u8PurifyUseCase`/`DLNACastManager`/`IjkMediaPlayer`);keep 打标 47 处;`ConfigParser` 的"线路N"回归字面量 keep(数据默认名 + 单测锁定);`PlayerUiState` 片头尾默认值改空串;`OkGoHelper` DNS "关闭"保持索引锚点、显示侧在 `SettingsPage` 映射资源;⚠️ 补 `PlaybackController` 的 `import com.github.tvbox.osc.R` + 静态 `str()`(此前 Java 侧只换引用未定义 helper,Kotlin-only 编译绿灯掩盖);`assembleDebug` + 230 单测全绿 |
| 2026-09-22 | 语言切换改为"重启后生效"(用户拍板;真机实测后修正) | §4.3 重写:确认框(内容「重启后生效」,右下确认 / 左下取消)+ 确认后 `LanguageManager.set` + `restartApp()`(新增 `util/AppRestart.kt`:**先 `startActivity(CLEAR_TOP|NEW_TASK)` 再 `killProcess`**,照搬 `示例文件/android`),附"为什么不用立刻生效"的 4 条缺陷与两条实测反例(**AlarmManager 登记等触发 = 黑屏 + 重启两次**;`CLEAR_TASK` = AMS 失去 ActivityRecord);§7-C 验收改口径(含"取消不生效");§9-7 由"不要重启进程"反转为"要重启进程";新增资源 `settings_language_restart_message`;`LanguageRow` 移除 `recreate()` 遍历与 `AppManager` import;`localized()` 保留、`AppManager.snapshot()` 已移除 |
| 2026-09-22 | 文档口径对齐(实施后状态) | §0 摘要、§1.1 基础设施表、§1.2 规模表、§5 决策点(D1/D2 已按推荐实施)、§7 验收清单(标注 A 全绿/B 静态面通过/C·D 待走查)、附录 A(完成情况)、附录 B(关键数字:423 条 / 卡口 0 处 / 230 单测 / `\u` 转义 100 处)由"盘点时"口径更新为"实施后";§6 第 1 步状态保持"代码完成·待真机走查"。注:上一条"420 条"为审查前数字,审查修复新增 `player_channel_*` 3 条 ⇒ 现 **423 条** |
| 2026-09-22 | 第 2 步(英语)资源与入口落地 | 新增 `values-en/strings.xml`(**424 条**):按 §3.3 术语表统一初翻,key 集合/占位符/空值经 `i18n_align.py en` 逐条对齐(PASS);`LanguageManager.delivered` 放开 `English`(设置页语言入口出现「English」,`languageLabelRes` 已含该分支);语言名条目保留各自语言自称(endonym,见 §9-10),英文允许同值多 key(见 §9-11);`assembleDebug` 绿、卡口仍 0。⚠️ 数字口径:第 1 步落地后又新增 `settings_language_restart_message` ⇒ `values/strings.xml` 实为 **424 条**(上一条修订记录的"423 条"为其时数字);**英语逐页走查(截断/挤压)待真机**(§7-D) |
| 2026-09-22 | 新增 §3.3 术语表 + §9 坑 9–11 | 术语表(中/英对照 + 约定)供第 3/4 步沿用;§9 增:资源撇号需转义或改写、语言名用 endonym 且为其设 CJK 白名单、英文同值多 key 的检查口径(仅 `i18n_align.py` 不查重复值) |
| 2026-09-22 | 第 3 步(繁體台灣)落地 | 新增 `values-b+zh+Hant/strings.xml` **424 条**(繁体基础层,用词取台湾:搜尋 / 設定 / 預設 / 快取 / 執行緒 / 佇列 / 導覽 / 儲存庫 / 應用程式 / 網路 / 軟體 / 畫質 / 逾時 / 位址…,标点按台湾习惯用全角括号与全角冒号);`Trans` 门控改造落地(§4.5 重写为"语言签名懒重建":`Trans.get()` 读 `LanguageManager.isTraditional()` 比对签名,不一致才重建,`trans=true` 才构建字表);`delivered` 放开 `TraditionalTW`;`i18n_align.py b+zh+Hant` PASS(424=424) |
| 2026-09-22 | 第 4 步(繁體香港)落地 | 新增 `values-zh-rHK/strings.xml` **53 条**(差异层,只覆盖港台用词不同条目:網絡 / 軟件·互聯網 / 緩存 / 隊列 / 列表 / 導航 / 控件 / 視頻 / 音頻 / 屏幕·全屏 / 點播 / 超時 / 地址 / 獲取·信息 / 文件夾·數據 / 本地 / 線程 / 訪問·項目,未覆盖条目回落基础层;占比 12.5% < spec §3.1 的 30% 阈值,维持"基础层 + 差异层"结构);`delivered` 放开 `TraditionalHK`(四语全放开);`i18n_align.py zh-rHK --subset` PASS;`assembleDebug` 绿 |
| 2026-09-22 | 升级兼容审查 + `isTraditional()` 修正 | 用户问"从旧版本升级不会出问题吧":逐项核对 = ① KV 新键 `app_language` 读取带默认值且**不回写**、MMKV 无 schema / 无迁移 ⇒ 数据层安全;② 「跟随系统」档 `wrap()` 原样返回 base ⇒ 默认渲染路径与改造前一致;③ `App.attachBaseContext` 提前 `KV.init`(幂等,`onCreate` 那处保留);④ 预期内变化 = 英文 / 繁体系统语言设备升级后 UI 跟随系统语言(产品目标),非中英回落简体;⑤ 已知限制 = 通知渠道名建后不可改。**发现并修复 1 处真回归**:`isTraditional()` 原只看 `tag.startsWith("zh-Hant")` ⇒ 「跟随系统」档恒 false,zh-TW / zh-HK 老用户(未在应用内选语言)"UI 繁体但源数据不转"(旧版按系统国家 `TW` 判定不会漏)⇒ 改为显式档看 tag、「跟随系统」回落系统 locale(`script == Hant` 或 region ∈ `{TW, HK, MO}`);§4.5 增对应坑注 |
| 2026-09-22 | 四语审查(错误/遗漏/回归)与修正 | ① **HK 层补漏 15 条**(台港用词差异):「記錄」vs 台「紀錄」(搜尋記錄 ×3 / 歷史記錄上限 / 刪除記錄 / 觀看記錄 / 全部觀看歷史記錄)、「項」vs 台「筆」(历史上限值与副标题)、「從本地選擇」vs 台「從本機」、"暫無熱搜數據"vs 台「資料」、「超時換源」vs 台「逾時換源」、「收起」vs 台「收合」、「死機」vs 台「當機」(自动停用提示 + 禁用源弹窗 2 条);② **修 2 条港层自造不一致**:`player_get_info_error` / `player_getting_info` 的「信息」改回「資訊」(港台通行,避免半港半台);③ **删 2 条冗余**:`toast_local_grant_not_persisted` / `toast_local_refs_missing` 与台湾层逐字相同(差异层只放差异)⇒ HK 层 53→**66 条**(补 15、删 2);④ `Trans.get()` 的"实例 + 独立签名"两处状态合并为只读实例字段 `trans`(消除 volatile 不一致窗口与多余重建);⑤ 核查通过:未登记 String 键走 MMKV 原生分支(`KVDecoder` 的 `wanted.isInstance(raw)` 直接返回,不打日志、不依赖登记表)、全仓无 `Locale.setDefault` 污染判定、`Service` 仅 `PlaybackService`(已包裹)、两个 `BroadcastReceiver` 无用户可见文案;⑥ 工具:`i18n_align.py --subset` 增 `REDUNDANT-VS-UPPER` 检查,且 subset 模式不再打印超长 MISSING 清单 |
| 2026-09-22 | 打包期语言过滤(`androidResources.localeFilters`) | 用户问"是否需要在 app/build.gradle.kts 加 localeFilters";`aapt2 dump configurations` 实测 APK 内带 27 个语言变体(依赖库的 ar/de/fr/ja/ko/ru… + en 各区域变体 + zh-rCN/zh-rTW)⇒ 值得过滤。**实踩坑**:AGP 把 localeFilters 原样透传成 `aapt2 -c`,脚本限定必须写 **`b+zh+Hant`**(写 BCP-47 的 `zh-Hant` 直接构建失败)。落地 `["en", "zh", "zh-rCN", "b+zh+Hant", "zh-rTW", "zh-rHK"]`;debug + release(R8/资源收缩)均 EXIT=0,两 APK 语言配置均只剩这 5 项(四语齐全);体积收益 KB 级(debug 83.96→83.959 MB)⇒ 定位为"显式声明交付语言",非瘦身手段;§3.1 加说明 + §9-14 |
| 2026-09-26 | 本地源导入判据改造:删 2 条文案 | 删 `toast_local_direct_grant_hint`(直引不再拦导入)、`toast_local_grant_not_persisted`(目录授权不再作为直引前提,持久化提示失去意义)⇒ 四语各删 2 条,**港层只删前一条**(`grant_not_persisted` 本就未进港层)。其余本地源文案(`missing_files_hint` / `missing_files_all_files` / `tree_denied` / `tree_forbidden` / `refs_missing`)全部仍在用,口径未变 |
