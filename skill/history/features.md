# AVBox 功能迭代实施记录（历史归档）

> 本文件是 `skill/history/` 归档的一部分：2026-09-09 起各项功能改造的**实施过程、历史补丁与排查记录**。
> 活规范见 `../avbox-mobile-ui-spec.md`，通用规则见 `../SKILL.md`。
> 用途：**仅在需要追溯「当初怎么做的、为什么这么做、踩过什么坑」时按需检索**，不要通读。
> 说明：文内 `§x` 引用沿用归档前的旧编号（§4.x 未变；旧 §5/§6/§7/§8 已重组，见 SKILL.md 文档地图）。

---

## 归档时的项目状态（2026-09-12）

- **状态**:Step 7 已完成(assembleDebug 通过,真机回归待装包验证);快搜功能已删除(2026-09-09,见下)
- **最近更新**:2026-09-11(★ **主题设置页**(照搬 `示例文件/android`:取色来源/深浅模式/预设色卡/HSV 取色器/配色风格,新增 materialkolor 依赖与 `AppThemeState` 全局状态,设置 tab 入口,见下方「主题设置页」);**顶部应用栏无边框化**(4 tab + 二级页;内容延伸至状态栏 + 随滚动滚走 + 顶部渐变遮罩,见 §6 与下方「顶部应用栏无边框化」;首页/栏目页**卡片点击分发**+ 网盘目录下钻 + 源级「搜索/详情」策略,见 §4.1「卡片点击分发」与本页「卡片点击分发 + 网盘目录下钻」;详情页 UI 补丁:⑦ 线路/清晰度卡片化、⑧ 状态栏图标外观断言,见 §8 Step 4 补丁;加载指示器全局改用 `ContainedLoadingIndicator` 并定稿 64dp(详情页例外 48dp;**直播页例外已于 2026-09-19 取消,改回 64dp**),见 §6;搜索结果页 = 波浪线进度条 + 结果源筛选 chips,见 §4.6;**配置管理页**(2026-09-11 二轮:设置 tab 新入口 = 源添加/管理唯一入口,订阅源开关切换 + 长按删除,见下方「配置管理页」;隧道模式 + AAC 优先(见下方小节);2026-09-12:首页**下拉刷新**(48dp 圆形指示器 + 松手整页重载,见下方「首页下拉刷新」);**点播 / 直播配置拆分 + 配置管理页分段**(2026-09-12 晚,见下方同名小节))
- **下一步**:真机回归(Step 4/5/6/7 欠账合并验证 + 主题设置页新功能验证 + 隧道模式/AAC 优先 + 首页下拉刷新)

## 点播 / 直播配置拆分 + 配置管理页分段（2026-09-12 晚）

**背景（用户原话）**：「配置管理能否支持点播和直播分开来？如果没有加上直播源就默认使用目前开启的点播源作为直播，如果添加了直播源优先级则更高，但是点播还是用原来的点播源。」

**对照上游（`示例文件/TV-fongmi`，只读参考）**：FongMi 已是同语义实现，直接照搬其判定方式而非自创开关 —— `BaseConfig.needSync()`（`sync || config==null || url 为空 || url 等于直播配置 url`）、`LiveConfig.config()`（`sync = config.getUrl().equals(VodConfig.getUrl())`）、`LiveConfig.load()`（`if (sync) return`，跟随态连拉都不拉）、`VodConfig.initLive()`（仅 `needSync` 时把点播 JSON 的 lives 喂给 LiveConfig）；存储 = Room `Config` 实体带 `type` 列（0 点播 / 1 直播 / 2 壁纸），UI = 设置页 Vod / Live 两条独立行 + 同一个输入弹窗按 type 换标题（**清空 url = 删除该 type = 回到跟随**）。

**改造前的实际状态（读代码得出的关键结论）**：读路径**已经**具备该语义 —— `API_URL` / `LIVE_API_URL` 本就是两个键，`loadLiveConfig()` 空则回落 `API_URL`，`parseJson()` 只在 `live_api_url` 为空或等于当前解析的 `apiUrl` 时才从点播 JSON 取 `lives`，直播页切换也只写 `LIVE_API_URL`。**真正的缺口只有两处**：① `ConfigManagePage.applySubscribe()` 与 `SettingsPage` 接口线路**双写两个键**（点播/直播被强制同源，并会把独立直播源冲掉）；② 没有任何录入独立直播源的入口。

**落地（数据层 → 写入侧 → UI）**：

1. **数据层**：`ApiConfig.getEffectiveLiveUrl()`（独立源优先、空则回落点播源）/ `isLiveFollowVod()` / `invalidateLiveConfig()`（清内存 + `loadedLiveConfigUrl=""`，让直播页下次进入必然重载）；`clearConfig()` 拆成 `clearVodConfig()` / `clearLiveConfig()`。**跟随的表示法沿用「空 或 等于点播 url」** —— 旧双写留下的相等状态天然判定为跟随，老用户升级后行为不变，**不需要数据迁移**。
2. **写入侧解耦**：`applySubscribe()` 拆成 `applyVodSource()`（返回"切换后是否跟随"；跟随则把 `LIVE_API_URL` 归一化为空并继续跟随新点播源，独立则一个字都不改）/ `applyLiveSource()`（不碰 `API_URL`、不触发 `AppBootstrap.retry()`）/ `applyLiveFollowVod()`；`SettingsPage` 接口线路同样只写点播。删空点播列表改走 `clearVodConfig()`（独立直播源保留，旧 `clearConfig()` 会连坐清掉）。
3. **UI**：配置管理页顶栏改 `collapseEnabled = false`（pinned，`topPad` 恒定，分段行才能常驻 —— 与首页/搜索页同款模式），顶部加「点播 / 直播」分段；`CapsuleSegmentedButton` 加 `badge`（第二行摘要，null 时渲染与旧版一致，主题页不受影响）与 `minHeight`；直播段首项 = 合成的「跟随点播源」卡（开关不可关 —— 直播至少要有一个来源）；新增 `HawkConfig.LIVE_SUBSCRIBE_LIST` 存独立直播源（格式与 `SUBSCRIBE_LIST` 一致，`loadSubscribes/saveSubscribe` 参数化 key 复用，零迁移）。
4. **直播页「配置切换」组**：第 0 项补合成的「跟随点播源」（`ApiConfig.LIVE_FOLLOW_ITEM_NAME`），历史项下标整体 +1，选中判定 / 点击 / 长按删除三处同步换算。**不补这一项不行**：解耦后 `LIVE_API_HISTORY` 不再记录点播 url，该组会没有任何选中项。

**踩过的坑 / 决策（可复用）**：

- `ApiConfig.getLiveGroupIndexKey()` **故意保持原样**（仍按原始 `LIVE_API_URL` 取键）：改成按"生效地址"会让老用户的直播分组记忆一次性失效，收益不抵风险。
- 「跟随点播源」卡的当前源名走 `subtitle` 而非 `SettingsSwitchRow.valueText` —— `valueText` 在 Row 里不受 `weight` 约束，源名过长会把右侧 `Switch` 挤出卡片。
- 切分段必须重置 `manageMode` / `selected`，否则会把另一角色勾中的源当成当前角色的删除目标。
- `OutlinedTextField.supportingText` 用显式标注 `(@Composable () -> Unit)?` 的局部 val；写成 `x?.let { { Text(it) } }` 会被推断成普通 `()->Unit` 与可组合类型不匹配。
- `clearVodConfig()` 里 `isLiveFollowVod()` 必须在把 `API_URL` 置空**之前**求值，否则跟随判定失效、`LIVE_API_URL` 残留成陈旧快照。
- 直播源支持纯文本形态（`parseLiveConfigContent` 三分支:带 lives 的 JSON / 纯直播 JSON / m3u-txt 文本），因此直播段的添加框提示写「支持配置 JSON / m3u / txt 直播源」；本地文件经 `clan://` 同样走这条路。

**验证**：`:app:compileDebugKotlin` + `:app:compileDebugJavaWithJavac` 均 BUILD SUCCESSFUL（产物时间戳已核对晚于源文件改动）；真机待验：①点播段切源后直播仍跟随;②添加独立直播源后切点播源,直播源不被覆盖;③直播段关闭普通卡回到跟随;④删空点播列表时独立直播源仍在;⑤直播设置「配置切换」组跟随项选中态。

**同日追加（分段外观按页区分 + 配置管理页编辑控件 + 卡片源图标）**：

- **分段外观按页区分（用户反馈驱动）**：轨道样式一度做成组件全局默认,用户看过真机后反馈主题设置页"难看死了",遂改为 `SegmentStyle` 二选一 —— `Connected`(默认,原连接胶囊,主题设置页不传参即原样) / `Track`(配置管理页显式传)。`Connected` 分支除连接形状外**不传任何参数**、全走 M3 `ToggleButton` 默认,这样"主题页与引入时逐像素一致"是被代码结构保证的,而不是靠比对。教训:共享组件改默认外观 = 同时改所有调用页,先问清哪个页面要变。
- **图标转换**：`.tubiao/编辑.svg` → `drawable/ic_edit.xml`、`.tubiao/配置管理的订阅源卡片icon图标.svg` → `drawable/ic_subscribe_source.xml`,沿用既有约定(`viewportWidth/Height=960` + `<group android:translateY="960">` 平移负坐标 + `fillColor="#FFFFFFFF"`,颜色交给 Compose `Icon(tint=)`）。
- **编辑控件**：管理模式右上改为 `Row(8dp 间距)` = 编辑(左)+ 删除(右),两者复用历史页 `ManageActionIcon`;编辑仅当**勾选恰好 1 项**时 `enabled`(多选无意义)。新增 `updateSubscribe(mode, original, name, url)` 按**原链接**定位原地更新 —— 与 `saveSubscribe` 的"按新链接查重"不同,因为编辑允许改链接本身;链接撞车时去掉被撞项。保存后把勾选值从旧字符串替换为新字符串,避免"选中集里留着已不存在的条目 → 管理模式下无可见勾选却仍能点删除"的错位。
- **共用一个 dialog**：`AddSubscribeDialog` 加 `initialName/initialUrl`,新增态传空串、编辑态预填;标题在「添加/编辑」×「订阅/直播源」四种组合间切换。
- **当前在用源被编辑**：仅当地址变化时重新生效(点播 → `switchToVod` 整页重载;直播 → `switchToLive` 只换直播侧),纯改名不重载。
- **卡片源图标**：复用设置页的 `SettingsIconBadge`(40dp `primaryContainer` 圆 + 22dp 图标)。⚠️ 第一版把图标放进文字 Column 内与名称同行,导致链接左侧不缩进、与名称不对齐 —— 改为置于 Column **之外**,名称/链接同基线且整块与图标垂直居中,卡高也不变。
- **验证**：`:app:compileDebugKotlin` BUILD SUCCESSFUL;`:app:installDebug` BUILD SUCCESSFUL 27s,已装机(`lastUpdateTime=2026-09-13 00:13:23`)。

## 无痕模式（2026-09-12 晚；偏好设置页）

**需求（用户原话）**：「在偏好设置页面 m3u8 净化和弹幕开关卡片的中间新增加一项功能名为无痕模式，开启后搜索历史不记录，观看历史也不记录，手动收藏功能正常」。

**先查上游**：FongMi 已有同名功能（`Setting.isIncognito()` = `Prefers.getBoolean("incognito")`,设置页一行开关）,拦截点全在策略层 `playback/vod/VodHistoryPolicy.java`(`save` / `saveVisit` / `saveCurrent` 三个写入口都 `if (Setting.isIncognito()) return`,另外 `findOrCreate` 里 `history.delete()` 让本会话内仍能续播)。**差异**:FongMi 的无痕**只覆盖观看历史**,不拦搜索历史;本项目按用户要求把搜索历史也纳入。

**落地点（在数据层拦,不在调用点拦）**：

- `HawkConfig.INCOGNITO = "incognito"`(默认关);判定收敛到 `HistoryHelper.isIncognito()`,避免多处重复读 Hawk。
- 搜索历史:`HistoryHelper.setSearchHistory()` 开头 return。全工程写 `SEARCH_HISTORY` 的只有本类三处 —— `setSearchHistory`(拦)/ `clearSearchHistory` / `removeSearchHistory`(后两者是用户主动删除,**故意不拦**)。
- 观看历史:`RoomDataManger.insertVodRecord()` 开头 return。**这是关键判断** —— 该方法经核查是观看历史 + 播放进度的**唯一落库点**(`DetailActivity` 的片头 `preparePlayBundle`、切集、`syncPlayingVodInfo`、`playerCfg` 四处 `insertVod()` 全汇聚到这里),所以在数据层拦一处就全覆盖,且未来新增调用点自动受保护;比在 4 个调用点各写一遍可靠。
- **收藏不受影响**:收藏走 `RoomDataManger.insertVodCollect`(DetailActivity 收藏按钮 + 首页长按「加入收藏」),完全没碰。

**为什么只拦写入、不隐藏已有数据**:用户说的是"不记录",不是"看不到"。已有历史照常展示、清空/删除照常可用 —— 否则用户会以为历史丢了。

**验证**：`:app:compileDebugKotlin` + `:app:compileDebugJavaWithJavac` BUILD SUCCESSFUL;`:app:installDebug` BUILD SUCCESSFUL 22s,已装机。真机待验:①开启后搜索一次,退出重进搜索页无新历史;②播放一集后历史页不出现该条目,重进详情页从第 1 集开始(不续播);③同状态下点收藏,收藏页正常出现该条目;④关闭无痕后上述记录恢复。

## 详情页选集行去重 + 首页直播 FAB 换项目图标（2026-09-12 深夜，用户截图反馈）

- **选集行重复「全部」**：用户截图指出"选集区域这一栏不要在全部下面还显示全部"。核实:`EpisodeRow`(`DetailActivity.kt`)表头右侧已有 `PillAction(ic_episode_grid_all, "全部") → vm.showEpisodeSheet()`，而 `LazyRow` 末尾还挂了一颗 `item(key = "all")` 的 `FilterChip("全部")`，两者点击行为完全相同 → 删除末尾那颗（同一动作两个入口，既冗余又挤占横向 chips 空间）。⚠️ 保留表头那颗：它带图标、与「倒序/正序」成对，是选集卡片表头的固定组成。
- **首页直播 FAB 换图标**：用户新放入 `.tubiao/直播fab.svg`（Material Symbols 的 screen-share/直播图标）。转换 → `drawable/ic_live_fab.xml`（同前约定:viewport 960 + `translateY=960` + 白色 fill 交给 Compose tint），`HomePage.kt` 的 `Icon(imageVector = Icons.Filled.LiveTv)` 改为 `Icon(painter = painterResource(R.drawable.ic_live_fab))`，并清掉 `androidx.compose.material.icons.filled.LiveTv` 导入（全工程仅此一处引用）。HomePage 此前完全没用过 `R`/`painterResource`，本次补了两个 import。
- **验证**：`:app:compileDebugKotlin` BUILD SUCCESSFUL；`:app:installDebug` BUILD SUCCESSFUL 31s，已装机。

## 死代码 / 死文件审计（2026-09-13，用户要求）

**方法（可复现）**：先把 `app/src` + `quickjs/src` + `player/src` + `pyramid/src` 全部 `.kt/.java/.xml` 读成一个大字符串作为"全工程引用集"，再逐个比对：
①资源名 — 每个 `res/drawable*`/`mipmap*` 文件名、`res/values*` 里声明的每个 `name`，查 `R.<type>.<名>` 或 `@<type>/<名>`；
②文件级 — 每个 `.kt/.java` 提取顶层 `fun/class/object/interface` 名，看是否在本文件之外出现；
③成员级 — 每个 public/internal 方法/字段名在全工程是否只出现 1 次（即只有声明）。
⚠️ **两个必须做的修正**（否则误报一大片）：Java getter/setter 在 Kotlin 侧是**属性语法**（`getChannelGroupList()` 写成 `.channelGroupList`），要额外按首字母小写再数一次；`RefreshEvent` 那种全大写常量要注意大小写敏感的匹配。

**本次实际删除（已验证零引用、零风险）**：

| 项 | 位置 | 判定依据 |
|---|---|---|
| `ic_launcher_background.xml` | `res/drawable/` | AS 模板网格图；自适应图标用的是 **`@color/ic_launcher_background`**（colors.xml 里的同名颜色），这个 drawable 从建仓起就没人引用 |
| `color_CCFFFFFF` | `res/values/colors.xml` | 注释说服务于 `view_play_container.xml` 的加载提示，但那三个提示 View 已于 2026-09-11 移除（布局文件自己的注释可证）；此后无引用 |
| `IpScanningVo.java` | `bean/` | 整个类零引用（含 python/java 侧与 assets），IP 扫描功能早已不存在 |
| `hotVodDelete` | `HawkConfig` | `public static boolean`，零引用（注意：它是**字段**不是 Hawk 键，与其它常量不同，删掉不影响持久化数据） |
| 6 个 `TYPE_*` | `RefreshEvent` | `TYPE_PUSH_URL/EPG_URL_CHANGE/SETTING_SEARCH_TV/FILTER_CHANGE/LIVE_API_URL_CHANGE/HOME_SOURCE_CHANGE` 全工程既无 post 也无订阅者；**保留常量的数值不动**（EventBus 按 int 分发），并在类注释里记下这些空洞编号避免复用 |
| `minHeight` 形参 | `CapsuleSegmentedButton` | 2026-09-12 引入后，随着调用方改用 `SegmentStyle`（两段各自 40dp 天然高度），两个调用页都不再传 → 形参 + `heightIn` 调用 + `Dp` import 一并删除；段高下限仍由 M3 `ToggleButton` 自带 |

**刻意保留（扫描命中但绝不能删）** —— 这三类占了"疑似死代码"的绝大多数：

1. **JNI/native 桥**：`com.p2p.P2PClass` 全部 ~23 个 `P2P*`/`xGFilm*` 方法 —— 由 `libp2p.so` 反向调用，源码里必然"零引用"。
2. **JS 爬虫 API**：`com.github.catvod.crawler.js.*`（`Global.pdfh/pdfa/rsaX/js2Proxy`、`Json.safeListElement`、`Res.getCode`、`rsa/RSAEncrypt` 等）—— 由 JS 脚本通过 `JsSpider` 注册的全局对象调用；`proguard-rules.pro:208` 有 `-keep class com.github.catvod.**`。这正是 spec §6.3「依赖裁剪不得只看宿主源码静态引用」的例子。
3. **接口/框架回调**：DLNA 的 `remoteDeviceAdded/onServiceConnected/createStreamServer…`、`Service.onBind/onStartCommand`、`TimedTextFileFormat.toSRT/toASS/…`、OkHttp `Authenticator.authenticate`、`SSLSocketFactory.getDefaultCipherSuites`、Gson `ExclusionStrategy.shouldSkipField`、弹幕 `danmakuShown/drawingFinished` 等 —— 都由框架按接口调用。

**已知但本次未删（等用户决定）**：`app` 自己的工具类里还有约 40 个"零引用"的 public 方法，例如 `StringUtils.isNotNull/isBlank/trimBlanks/getBaseUrl/isJsonType/escapeJavaScriptString`、`ImgUtil.isBase64Image/decodeBase64ToBitmap/spanCountByStyle/initStyle/getStyleDefaultWidth`、`DefaultConfig.getAppVersionCode/getAppVersionName/getFileSuffix/getFilePrefixName`、`OkGoHelper.reloadDns/mapHosts/getItvClient`、`LocalIPAddress.isNetworkAvailable/isIPAddress`、`MD5.encrypt4login`、`BaseActivity.hasPermission/getAssetText`、`Proxy.getM3U8Content`、`SearchHelper.putCheckedSources`、`PlayerHelper.getPlayerExist`、`FileUtils.clearSpiderCacheFiles`、`AppManager.appExit/isActivity`、`SourceViewModel.clearSortCache`、`ApiConfig.clearJarLoader`、`DanmakuApi.hasCustomApi/getDisplayApiUrl/setCustomApi`、`PreloadManagerHolder.hasActivePreload/setDrmSessionManagerProvider`、`PlayerUiState.playLabelVisible`、`RemoteTVBox.setAvalible`、`CustomWebReceiver.REFRESH_SOURCE`、`RequestProcess.KEY_ACTION_*`、`parser/Utils.UaMobile`、`AppTaskExecutor.setDelegate`。

为什么不一并删：`proguard-rules.pro:207` 是 `-keep class com.github.tvbox.osc.** { *; }`（**全量 keep，死代码在 release 里也不会被 shrink 掉，所以清理确实有价值**），但同一份配置说明这些类是"对外保留"的宿主面；第三方 jar 里确实出现过引用宿主 `com.github.tvbox.osc.util.*` 的写法。删 `com.github.tvbox.osc.**` 的 public 方法风险不为零且需要逐个人工判断，按项目「最小化修改」约定留待确认。

**验证**：`:app:installDebug` BUILD SUCCESSFUL 36s，已装机。

### 第二批（未做）：`com.github.tvbox.osc.**` 的 ~40 个零引用 public 方法

见上方清单。风险点：`proguard-rules.pro:207` 是 `-keep class com.github.tvbox.osc.** { *; }`（全量 keep，说明这些类是"对外保留"的宿主面），且第三方 jar 出现过引用宿主 `com.github.tvbox.osc.util.*` 的写法 → 逐个删需人工判断，风险不为零。

### 第一批（2026-09-13 已做）：private/internal + 纯 UI 层

**方法补充**：第一轮只扫了 public/protected，本轮补扫 `private`（Kotlin `private fun/val`、Java `private` 方法），判据 = 名字在全工程只出现 1 次（私有成员本就无法被外部引用，所以"只出现一次"即死）。

**已删（7 项，全部零引用 + 行为等价）**：

| 项 | 位置 | 说明 |
|---|---|---|
| `findEpisodes` | `api/DanmakuApi` | private static 辅助；同文件 `findEpisodeList` 才是被用的那个 |
| `hasCustomApi` | `api/DanmakuApi` | 旧设置页的展示辅助，新设置页直接读 `HawkConfig.DANMU_API` |
| `getDisplayApiUrl` | `api/DanmakuApi` | 同上（新设置页用 `SettingsState.danmuApi`） |
| `playPreSource` | `ui/activity/LivePlayActivity` | 切"上一个源"；兄弟方法 `playNextSource` 仍被超时换源链路调用（第 545 行），本方法是旧"快滑换源"手势的残留（该手势已于 §4.5 定稿移除） |
| `playLabelVisible` | `player/state/PlayerUiState` | 计算属性，全工程无读者；`play_label` 这个 view 在 Compose 化后已不存在（全仓仅剩这一处注释提到它） |
| `clearSortCache(String)` | `viewmodel/SourceViewModel` | 只清单个 key；实际使用的是整清 `clearRuntimeCache()` |

**扫出但"不能删 / 先别删"的三类**（这才是这轮审计的主要价值）：

1. **`@Preview` 不是死代码**：`ui/theme/Theme.kt` 的 `AVBoxThemeLightPreview` / `AVBoxThemeDarkPreview` 被扫描判为"零引用"，但它们由 **Compose 工具链（Android Studio 预览）**消费，删了就没了预览能力 → **保留**。凡是 `@Preview` 标注的函数都会这样误报。
2. **catvod 包里的 private 死方法，本轮不动**：`crawler/JarLoader.requireRecentLoader`（同文件其它方法各自内联了同样的 `loaders.get(recent)` 逻辑，是重构残留）、`crawler/js/JsSpider.createArray`（同族 `createObject/get/set` 都在用，只有它没人用）。**故意不删** —— 这两个文件属 `com.github.catvod.**` 上游面，改动会增加后续同步上游的成本。
3. **两个"断线"而非"死代码"—— 需要用户决策，本轮只报告不删**：
   - **`RemoteTVBox.setAvalible` 是 `HawkConfig.REMOTE_TVBOX` 的唯一写入点，而全工程没人调用它**。链路后果：`getAvalible()` 恒为 null → `getAvalibleActionUrl()` 恒为 "" → `PlayerHelper:267` 的 `RemoteTVBox.run(...)` 恒返回 false，且 `PlayerHelper:220` 的 `playersExist.put(13, …)` 恒为 false ⇒ **13 号"RemoteTVBox 播放器"实际永远不可用**。但**投屏功能本身是好的**：Compose 投屏 sheet（`PlayerSheets.kt:974`）绕过这套机制，直接 `post("http://" + device.id + "/action")` 用扫描到的设备地址。判断：这是 Compose 迁移时丢掉的一次调用（旧 UI 应该会在发现设备后记住 host），不是单纯的残渣 —— 要么把 `setAvalible` 接回"发现设备/投屏成功"处，要么把 `run`/`getAvalible`/`getAvalibleActionUrl`/`REMOTE_TVBOX` 与 `PlayerHelper` 的 13 号条目一起退场。删 setter 一个方法反而会让这条线索消失，故保留。
   - **`LivePlayActivity.mUpdateTimeshiftRun` 从未被 post**：它是"每秒把 `tsPosition` 刷新成当前播放位置"的 Runnable，但全文件没有 `postDelayed(mUpdateTimeshiftRun, …)`。后果：进入回看（`startCatchupReplay` 只在开始时置一次 `tsPosition`）后，**时移条的滑块与"位置/时长"文本不会随播放自动前进**，只有拖动才更新。判断：这是**缺一次接线的小 bug**，不是死代码 —— 要么在 `startCatchupReplay` 里 post、在 `backToLiveFromEpg`/退出时 remove，要么确认不需要自动刷新后删掉。本轮保留待决策。

### 两个「断线」修复（2026-09-13，用户拍板"修复 a 和 b"）

**a) RemoteTVBox host 写入接回**（`PlayerSheets.kt`）：

- **扫描发现时**：`found(viewHost, end)` 回调里，若 `RemoteTVBox.getAvalible() == null` 则 `setAvalible(viewHost)` —— 只在"尚未记住"时写，避免多台 TVBox 时把用户的选择覆盖掉；写入放在 `mainHandler.post {}` 内（回调原本就在扫描线程）。
- **投屏成功时**：`ok == true` 分支里 `setAvalible(device.id)` 覆盖式写入 —— 这是用户显式选中的设备，优先级最高。失败不写（不给 #13 留一个连不上的地址）。
- 两处都紧跟 `PlayerHelper.invalidatePlayersExistInfo()`（新增方法）。
- ⚠️ **`PlayerHelper.getPlayersExistInfo()` 是进程级缓存**：`mPlayersExistInfo == null` 才重算，此前**没有任何重置点**。所以即使接回了写入，不重置缓存的话 13 号选项在本次进程内仍然不会出现 —— 这是修复里最容易漏的一环。
- 顺带确认：`CastDevice.tvbox(host)` 的 `id` 就是 host 本身，与 `getAvalibleActionUrl()` 拼的 `http://<host>/action` 及投屏 POST 的 URL 完全一致，所以存 host（不带 scheme）是对的。

**b) 时移进度 ticker 接线**（`LivePlayActivity.kt`）：

- 新增 `startTimeshiftTicker()`（**先 remove 再 postDelayed 1000ms**）/ `stopTimeshiftTicker()`。
- `startCatchupReplay()` 末尾启动；`backToLiveFromEpg()`、`playChannel()`（`isSHIYI = false` 那处，切台即退出回看）停止；`onDestroy()` 原有的 `mHandler.removeCallbacksAndMessages(null)` 已覆盖销毁路径。
- Runnable 内加 `if (!isSHIYI) return` 兜底：任何漏掉的退出路径最多多跑一拍就自停。
- "先 remove 再 post"是必需的 —— 反复进出回看会叠加多个 ticker（每秒刷新多次）。
- 📌 旁证：活规范 §4.5 早已写明时移条是"**1s 轮询**"，即设计意图如此，只是实现漏了 post —— 这不是新增行为，是补齐既定行为。

**验证**：`:app:installDebug` BUILD SUCCESSFUL 28s，已装机（`lastUpdateTime=2026-09-13 00:35:30`）。真机待验：①投屏 sheet 扫描到 TVBox 后，播放设置里的「RemoteTVBox 播放器」出现；②投屏成功后该选项仍可用且指向所选设备；③回看时滑块与时长文本每秒前进；④退出回看/切台后不再前进。

## 切到"坏源"后首页仍显示旧源（2026-09-13，用户提问驱动的修复）

**用户问题**：「配置管理页面点播分类如果添加并使用了一个不能使用的源，此时回到首页是不是还会显示之前能用的旧源，而不是显示失败或者空白？」—— **核实结论：是的，会**。完整链路：

1. `ConfigManagePage.applyVodSource()` 写 `API_URL = 新源` → `AppBootstrap.retry()`。
2. `loadConfig(false, …)`：新源没有历史缓存（缓存文件名 = `MD5(apiUrl)`）→ 拉取失败 → `callback.error(...)`。**关键：失败路径不会调用 `parseJson()`**，而清场动作 `resetConfigData()` 只在 `parseJson()` 开头执行 ⇒ 单例 `ApiConfig` 里的 `sourceBeanList` / `mHomeSource` / `parseBeanList` **原样保留上一个源的数据**。
3. `MainScreen` 的错误弹窗是**覆盖层**（`MainContent()` 始终在渲染），背后首页照常。
4. `HomeViewModel` 只在 `Boot.Ready` 时 `loadHome()`，对 `Error` 无反应；`sources`/`currentSource` 是 init 期快照 ⇒ 首页既不刷新也不报错，继续显示旧源内容。
5. 点弹窗「取消」= `continueOffline()` → `Boot.Ready` → `loadHome()` 读到 `mHomeSource`（旧源）⇒ **旧源照旧可用**。
6. 而 Hawk 里 `API_URL` 已经是新源 ⇒ **重启后才发现新源不可用**，与运行中表现矛盾。

**修复**：新增 `ApiConfig.invalidateVodConfig()`（`resetConfigData()` + `mHomeSource = null` + `invalidateLiveConfig()`，**不动 Hawk 地址**）与 `AppBootstrap.onApiUrlChanged()`（作废旧配置 → 广播 `TYPE_API_URL_CHANGE` → `retry()`），三处调用点：`ConfigManagePage.applyVodSource()`、`SettingsPage` 接口线路、`ApiConfig.switchApiCollectionIfNeeded()`。

- 为什么"切换前就作废"而不是"失败后回调里再清"：不必给 `AppBootstrap` 加失败回调链路；成功时 `parseJson()` 本来就会重新填充，作废是无害的；失败时首页自然落到 `emptyHome` → `loadHome` 的 `loadingSourceKey = null` 分支 → `onSortResult` 置空态（未配置引导态），与错误弹窗语义一致。
- 地址未变（同源再启用）不走这条链，只 `invalidateLiveConfig()`，避免无谓整页重载。
- 广播 `TYPE_API_URL_CHANGE` 的必要性：`HomeViewModel` 早就在 `init` 里把 `sources`/`currentSource` 快照住了，只清 `ApiConfig` 不会让在屏内容变化 —— 必须让首页立即 `reload()`，否则旧内容会一直摆到下一次 `Boot.Ready`。
- 📌 与 2026-09-11 的修复同源同思路：「原实现删光后保留 API_URL，会出现『订阅列表已空、App 仍用着被删的源』的状态不一致」—— 项目既有决策是**一致性优先于便利性**。
- 已知未处理（既有问题，非本次引入）：作废窗口期内若从历史记录进入详情页，`SourceViewModel.getDetail` 里 `ApiConfig.get().getSource(sourceKey)` 可能返回 null 而 NPE（`int type = sourceBean.getType()` 未判空）。触发需要"切换后加载完成前进详情页"且该源 key 已失效，概率低，留作后续加固。

**验证**：`:app:installDebug` BUILD SUCCESSFUL 30s，已装机。真机待验：①添加一个无效链接并启用 → 首页应显示「配置加载失败」弹窗，背后为未配置引导态/空态而非旧源内容；②点「取消」后首页仍是空态而不是旧源；③把地址改回可用源 → 首页恢复正常。

## 源失效判空加固 + 卡片涟漪对齐参考项目（2026-09-13）

**A. `getSource()` 返回 null 的判空（补上一节留的隐患）**。核查全工程 17 处 `ApiConfig.get().getSource(...)`，绝大多数已有 `?.`/`== null` 保护（`DefaultConfig`、`DetailActivity` 五处、`HistoryPage` 都是安全的），**真正缺判空的是 3 处**：

| 位置 | 原风险 |
|---|---|
| `SourceViewModel.getDetail` | `int type = sourceBean.getType()` 直接解引用 → NPE。改为先判空、`postValue(createEmptyDetail(sourceKey))`（与末尾"未知 type"分支同形状，详情页走空态）；顺手把末尾那段内联构造抽成 `createEmptyDetail()` 复用 |
| `SourceViewModel.getPlayInternal` | 同上 → 改为 `postPlayResult(..., null)`，播放器按"解析失败"处理 |
| `PlayContainer.initPlayerCfg` | `sourceBean.getPlayerType()` NPE，而**该 try 块的 `catch (Throwable)` 是空的** ⇒ 静默跳过后面 `pr/ijk/sc/sp/st/et` **全部**播放器设置，配置只剩半截（症状很隐蔽：播放器选项看似没生效）。改为 `sourceBean == null ? -1 : …`，回退全局播放器设置 |
| `PlayContainer.checkVideoFormat` | `sourceBean.getType()` NPE（有 `catch(Exception)` 兜底，但会走异常控制流）→ 加 `sourceBean != null &&` 前置判断 |

触发场景（同一根因）：切源后加载完成前从历史记录点进旧源条目；或订阅源被删而历史仍在。

**B. 卡片涟漪透明度对齐参考项目**。用户要求"参考 `示例文件/android` 修改点击卡片的激活反馈涟漪"。先摸清参考项目（子代理全仓审计）结论：**它没有可复用的 pressable 组件，也没有任何自定义 `Indication`/`ripple()`/`LocalIndication`** —— 涟漪定制只有一处，在 `Theme.kt` 里**全局**把 M3 默认透明度翻倍，经 `LocalRippleConfiguration` 下发：

```kotlin
val rippleAlpha = RippleAlpha(hoveredAlpha = 2f*0.08f, focusedAlpha = 2f*0.10f,
                              pressedAlpha = 2f*0.10f, draggedAlpha = 2f*0.16f)
CompositionLocalProvider(LocalRippleConfiguration provides RippleConfiguration(rippleAlpha = rippleAlpha))
```

照此在本项目 `AVBoxTheme` 里加了同样的 `rememberRippleConfiguration()` 包装。选全局而非逐卡传 `indication`：`clickable`/`combinedClickable`/`Surface(onClick)`/`ToggleButton` 一次覆盖、风格统一；`PressableCard` 现有的显式 `indication = ripple()` 同样会读到该配置。`RippleConfiguration` 已 deprecated 且官方无替代入口，只能 `@Suppress("DEPRECATION")`（参考项目代码里也留了同样的注释）。

**参考项目其它结论（供后续取舍，本次未改）**：①按压缩放目标只有 **0.94** 一个值（0.96/0.98 不存在），经 `Modifier.scale` + `spring(dampingRatio=0.6f, stiffness=800f)`，且**必须与真实 clickable 组合**涟漪才不丢（`ExpressiveButton` 的写法）；本项目沿用自定的 0.97 与 `graphicsLayer`。②参考项目圆角卡一律 **先 `.clip(shape)` 再 `clickable`**，源码注释写明原因（否则涟漪/长按激活区溢出圆角变矩形）—— 本项目 `PressableCard` 已是该顺序。③参考项目的卡片一律 `elevation = 0.dp` 且按下不变形/不抬升，只有 `ExpressiveButton` 有 `pressedElevation = 1.dp`。④它有 `LocalReducedMotion`，但只被入场动画消费，**按下反馈路径都不读它**。

**验证**：`:app:compileDebugKotlin` + `:app:compileDebugJavaWithJavac` BUILD SUCCESSFUL；`:app:installDebug` BUILD SUCCESSFUL 27s，已装机。真机待验：①首页卡片点击涟漪明显可见（原 10% → 20%）；②切源窗口期从历史点进旧源条目不崩、进空态。

## 宿主契约审计 + slf4j/JS/Guava 修复（2026-09-12 晚；承接当晚的 zxing 崩溃）

**背景**：zxing 崩溃（见 `logs/crash_diagnosis_20260912.md`）暴露了一类系统性问题 —— 宿主必须为动态加载的爬虫 jar 提供运行期类。当晚做了一次系统性审计并把缺口补齐。

**审计方法（可复现，值得复用）**：解析 jar 的 DEX —— 用 `class_defs`（class_idx → type_ids → string_ids）取出 jar **自己定义**的类；用 ASCII 正则 `L...;` 扫常量池取出 jar **引用**的类；再扫宿主 APK 全部 dex 的引用集。三者相减：
`jar 引用 ∧ jar 未定义 ∧ 宿主缺失 = 宿主必须补的类`。
注意两个坑：① DEX 里类型描述符**自带 `L` 前缀与 `;` 后缀**，比对时必须同形态，否则结果全是噪声；② `Add-Type` 定义的 C# 类型不跨 pwsh 进程，解析与比对必须在**同一次调用**里完成。

**审计结果（对真实事故 jar `logs/jars/crash_9ebc36e2.jar`，843 定义类 / 1190 引用类）**：
- jar 依赖宿主提供：okhttp3(17 类)、gson(7)、zxing(4)、quickjs wrapper(5)、宿主 catvod 的 `crawler.Spider`/`SpiderDebug`、平台自带的 `org.xmlpull.v1.XmlPullParser`；
- jar 自带 836 个 spider 类 + 6 个 `parser` 类 + `js/Method`，**不依赖宿主的 `utils.*`/`bean.*`** → fongmi catvod 里那套 `api` 大清单（brotli/guava/sardine/smbj/preference/logger）**本 jar 零引用**，不补；
- 真实缺口：`org.slf4j.ILoggerFactory`、`org.slf4j.impl.StaticLoggerBinder`、`com.whl.quickjs.android.QuickJSLoader$Console`、`com.github.catvod.debug.MainActivity`。
- 另一支 jar `old_77b88823.jar` **不是爬虫 jar**（含 `ftyguard_v7/v8.so` + `ftyshinidie.guard`，仅 36KB dex，`test/MainActivity`），无参考价值。

**修复三项**：
1. ~~**slf4j**：`org.slf4j:slf4j-api:1.7.36` + `slf4j-nop:1.7.36` + `-keep class org.slf4j.**` + `-dontwarn org.slf4j.**`~~ —— **当晚已全部撤回**，见下方「修正」。
2. **JS 全局别名 + `http.js` 模块名**：`assets/js/lib/net.js` 原本只到 fongmi `http.js` 的第 17 行，缺 19-32 行的 `defineGlobalAlias` 块；且 fongmi 的模块名是 `http.js`，本项目只有 `net.js` → JS 源站 `import ... from 'http.js'` 会走 `FileUtils.loadModule` 找不到文件 → `isInvalidModuleContent` → `compileEmptyModule`，**静默变成空模块而非报错**（`JsSpider.java:402-424 / 464-472`、`FileUtils.java:280-305`）。修复：`net.js` 补齐别名块，并新增 `http.js`（两者与 fongmi `http.js` **逐字节一致**，SHA256 `6317D5D4…`）。
3. **Guava keep**：Guava 由 `androidx.media3:media3-common` 传递带入（APK 内 2137 个 `com/google/common` 类），但宿主代码零静态引用 → release 下 R8 会改名/裁剪，jar 引用即崩。已加 `-keep class com.google.common.** { *; }`。

**release 验证（本轮最关键的一步）**：debug 不混淆，契约缺口只在 release 暴露。执行 `assembleDebug` + `assembleRelease` 后审计 **release 产物**（`AVBox_release.apk`，R8 后 5 个 dex / 34337 个类，64.6MB；debug 80.4MB）：
- 契约项全部命中：zxing / slf4j(含 `StaticLoggerBinder`) / Guava / okhttp3 / gson / quickjs / catvod 均在；
- 对真实 jar **无新增缺口**（仍只剩 `QuickJSLoader$Console`(上游共有问题，fongmi 同版本亦然) 与 `catvod/debug/MainActivity`(jar 调试入口)）；
- `mapping.txt` 佐证 keep 生效：`com.google.common.collect.ImmutableList -> com.google.common.collect.ImmutableList`、`org.slf4j.impl.StaticLoggerBinder -> org.slf4j.impl.StaticLoggerBinder` 等均为**同名映射**（未被改名/删除）。

**脱糖（同日补，见 spec §2）**：四个模块开启 `isCoreLibraryDesugaringEnabled` + `desugar_jdk_libs_nio:2.1.5`。⚠️ 只覆盖**编译进 APK 的代码**：jar 是预编译 dex 不过 D8，其 `java.time` 引用原样保留，API 24/25 仍会 `NoClassDefFoundError` —— 脱糖**修不了 jar**。

**未完成**：真机冒烟 —— 装包验证进行到一半时设备从 USB 断开（`adb devices` 为空），`install -r` 未执行；下次接上设备后补 `assembleDebug` 装机 + 启动 40s 无崩溃确认。

### 修正（2026-09-12 深夜）：slf4j 是误判，已撤回

**触发**：当晚播放中点开详情页后日志出现（被 catch 住、走 `System.err`，未崩应用）：

```
IncompatibleClassChangeError: Class 'org.slf4j.helpers.NOPLoggerFactory' does not implement
interface 'com.github.catvod.spider.merge.Pu' in call to
'com.github.catvod.spider.merge.a9 com.github.catvod.spider.merge.Pu.yq(java.lang.String)'
	at com.github.catvod.spider.merge.mu.tF(Unknown Source:1)
	at com.github.catvod.spider.merge.SC.<init>(Unknown Source:5)
	at com.github.catvod.spider.merge.q3.yq(Unknown Source:40)
```

**机理**：`NOPLoggerFactory` 只可能来自我加的 `slf4j-nop`，因果确定。爬虫 jar 把 slf4j-api **shade 进了自己的混淆包**（`merge.mu` = LoggerFactory、`merge.Pu` = ILoggerFactory），运行期按 slf4j 老规矩去宿主找 `org.slf4j.impl.StaticLoggerBinder`：

- 宿主**没有**绑定（改动前）→ 查找失败 → jar 内部回落 NOP → 静默降级，正常；
- 宿主**提供**绑定（改动后）→ 查找成功 → 拿到实现「未混淆 `org.slf4j.ILoggerFactory`」的 `NOPLoggerFactory`，而 jar 要的是自己那套 `merge.Pu` → **ICCE**。

**处置**：移除 `implementation(libs.slf4j.*)` 两行、`-keep/-dontwarn org.slf4j.**`、version catalog 的 `slf4j` 条目；重建后 APK 内 `org/slf4j` 引用数 = **0**（已核对）。version catalog 与《proguard-rules.pro》均留下了「不要加」的说明。

**教训（写进 spec §6.3）**：**常量池引用 ≠ 宿主应当提供**。zxing 是「jar 引用了、宿主没给 → 崩」，slf4j 是「jar 引用了、宿主给了 → 崩」——同一个审计方法得出的两类相反结论，区别只在 jar 是否把该库 shade 进了自己的包。这类判断静态审计看不出来，**必须跑起来才能证伪**；换句话说，第一版审计的「宿主契约清单」应视为**候选列表**，逐项都要过一遍真机。

## 首页下拉刷新(2026-09-12,用户要求:「下拉时出现圆形加载指示器,松手后刷新,指示器大小48dp」)

- **组件**:material3 1.5.0-alpha23 官方 `Modifier.pullToRefresh`(等价 `PullToRefreshBox`,因不想给 LazyColumn 整块体加一层缩进,挂在 LazyColumn 的 modifier 上,指示器作为同 BoxScope 兄弟节点)+ `rememberPullToRefreshState()`;阈值/行程均为 M3 默认 80dp(手指需下拉 160dp 触发,`DragMultiplier=0.5`)。
- **指示器**(`HomePage.HomePullRefreshIndicator`,文件中 private;2026-09-12 二轮按用户澄清重做):**进 App 引导页(BootLoading)同款 M3 expressive `ContainedLoadingIndicator`,48dp**——**全项目圆形加载指示器(播放器页面除外)统一用它**(见 §6)。滑入/隐藏/裁剪机制复用 `PullToRefreshDefaults.IndicatorBox`,但 `shape = RectangleShape` + `containerColor = Color.Transparent` + `elevation = 0.dp` → 不产生圆底徽章与阴影,形态与引导页一致;下拉态 `progress = { state.distanceFraction }`(随牵引距离形变,>1 时整体旋转——照抄 M3 官方 `PullToRefreshDefaults.LoadingIndicator` 的 drawWithContent 实现,注意 `rotate {}` 块内必须 `this@drawWithContent.drawContent()`,隐式调用编译不过)→ 刷新态转不定态圈,`Crossfade` 过渡。⚠️ 未用 M3 自带 `Indicator`(16dp 箭头,非 md3e)与 expressive `PullToRefreshDefaults.LoadingIndicator`(它把 `containerColor` 同时喂给外层圆底徽章和内层加载器,想去掉徽章就得连加载器自己的容器底一起去掉,拿不到引导页观感)。
- **顶栏遮挡(关键坑)**:顶栏是透明覆盖层且由 Scaffold 画在内容之上,指示器若停在内容顶部(y=0)会被左上角订阅源胶囊完全盖住 → 指示器整体 `offset(y = topPadding)`(topPadding = `AppTopBarScaffold` 回调的顶栏实测总高),从顶栏下沿滑出。指示器无 pointerInput/clickable,不拦截列表触摸。
- **松手刷新**:`onRefresh` → `pullRefreshing = true` + `HomeViewModel.reload()`(清 `sortCache`/`extendCache` 后整页重载;直接用 `loadHome()` 会命中 sortCache 导致「刷新了但推荐没变」)。
- **完成判定**:`rec.state != Loading && partitions.none { it.state == Loading }`(看门狗 45s 转 Error 同样解锁),`LaunchedEffect(pullRefreshing, homeReloaded)` 复位;**未配置接口的引导态不启用**下拉刷新。
- **验证**:`:app:compileDebugKotlin -q` 退出码 0;read_lints 无诊断;真机观感待用户确认(圆容器 + 24dp 环的组合、从顶栏下沿滑入的位置、"下拉填充→松手转圈"的过渡)。

## 隧道模式 + AAC 优先(2026-09-11;二轮按 fongmi 实现重做,一轮的音频 offload 方案废弃)

- **语义修正(关键)**:fongmi/OK影视 的「隧道模式」= **MediaCodec tunneled playback**(视频+音频经硬件 AV 同步直通渲染,`MediaFormat.KEY_TUNNELED_PLAYBACK`),入口 = `DefaultTrackSelector.Parameters.Builder.setTunnelingEnabled(boolean)` —— **不是音频 offload**。一轮实现的 `TrackSelectionParameters.AudioOffloadPreferences`(audio offload/DSP 直通)在实测机被系统 `AudioManager.getPlaybackOffloadSupport()=0` 挡死,永不生效,已废弃;`isOffloadedPlaybackSupported=true` 与 `getPlaybackOffloadSupport=0` 并存(vivo 的 direct profile 与 audio policy 判定不一致)。
- **fongmi 源码考古**(`示例文件/TV-fongmi`):`setting/DecodeSetting.java`(`isTunnel`/`putTunnel`,键 `decode_tunnel`)+ `player/exo/ExoUtil.java#buildTrackSelector`(`builder.setTunnelingEnabled(DecodeSetting.isTunnelingEnabled())` + `setPreferredAudioMimeType(AAC)`)。联动规则:`putTunnel(true)` → 强制 `putRender(RENDER_SURFACE)`;`putRender(TextureView)` → 自动 `putTunnel(false)`;播放判定 `isTunnelingEnabled() = isTunnel() && render==SURFACE`(**隧道要求视频直出 Surface,TextureView 走 GPU 合成不可隧道**);非 EXO 内核隐藏两开关(mpv 时);隧道开启时禁用画质/音质设置(strings: `error_video/audio_effect_tunnel`)。
- **落地(本项目)**:①键不变 `PLAY_TUNNEL`/`PLAY_PREFER_AAC`(默认 false);②`player/ExoPlayer#applyPlaybackParameters()`(`initPlayer` super 后)= `trackSelector.buildUponParameters()` → `setTunnelingEnabled(tunnel && render==SurfaceView)` + 可选 `setPreferredAudioMimeTypes(AAC)` → `trackSelector.setParameters(...)`(**不再走** `mInternalPlayer.setTrackSelectionParameters`,避免重置 tunneling 扩展字段);③设置页双向联动:开隧道且渲染=TextureView → 自动切 SurfaceView;渲染切 TextureView → 自动关隧道。
- **自动降级**:①内核自动切 IJK(rtmp 强制/自动重试)→ 本类不实例化;②渲染非 SurfaceView → 参数不启用;③设备 codec 不支持 FEATURE_TunneledPlayback → media3 静默回退(`isTunnelingEnabled()=false`),无副作用。
- **fongmi 未搬入**:非 EXO 内核隐藏开关(本项目保持独立开关常显)、隧道时禁画质/音质设置(本项目无此功能)、DecodeTrackSelector(依赖 fongmi 对 media3 的源码魔改,不需要)。
- **生效时机**:下次开始播放(播放器重建时读 Hawk)。
- **验证(已完成,2026-09-11 真机)**:参数下发成功(`prefs tunnel=true, surfaceRender=true, preferAac=true`);本机视频轨 video/avc(avc1.640028)的 codec 不支持 FEATURE_TunneledPlayback → `tunnelingEnabled=false` = 预期自动降级,链路(开关 → 选轨 → RendererConfiguration)工作正常。排查期临时落盘日志(`ExoPlayer` 内 `avbox_tunnel.log` 通道)已按用户要求移除。

## 配置管理页(2026-09-11,用户要求;同日二轮:开关切换 + 长按删除 + 成为源管理唯一入口)

- **定位**:全 App **唯一的源添加/管理入口**(二轮用户要求原文:「将设置页面的接口配置接口历史都删除了,此后添加和管理源的入口只有配置管理」)。
- **入口**:设置 tab「配置与数据」分组首位「配置管理」行 → `ConfigManageActivity`(壳同 `ThemeSettingsActivity`;页面 `ui/page/ConfigManagePage.kt`,Manifest portrait);首页两处旧入口同步改跳此页 = 引导态按钮(文案「添加订阅」)、订阅源 sheet 末组行(「配置管理」)。
- **页面**:无边框顶栏 = 返回钮 + 大标题「配置管理」+ 右上角控件(常态 = 「添加订阅」(40dp 圆形、surfaceBright 圆底、图标 = `.tubiao/添加订阅.svg` 转换的 `ic_subscribe_add.xml`);管理模式 = 「删除」(40dp 圆钮,复用历史页 `ManageActionIcon`),两者互斥);订阅源列表 = **28dp 圆角卡片**(色 `cardContainer`,距屏幕边缘 16dp、卡间距 12dp;卡内 = 名字 titleMedium + 链接 bodyMedium onSurfaceVariant 单行省略);空态 = `LoadStateBox`「暂无订阅」。
- **开关切换(二轮用户定稿)**:卡片右侧 `Switch` = 该源是否为当前接口(`activeUrl` 与 `Hawk API_URL` 比对);打开 = 切换(写 API_URL/LIVE_API_URL + 接口&直播历史 + 非线路历史清线路 + `AppBootstrap.retry()`,Toast「已切换到:名」);关闭不动作(必须有一个源在用);整卡点击 = 同开关。**列表首个订阅源添加后自动启用**(覆盖新装/清空后场景,免一步开关)。
- **排序(四轮用户定稿)**:**正在使用的源恒置顶**(渲染层排序 `orderedItems`,存储顺序不变),其余按添加顺序 → **新添加的源追加到列表末尾 = 显示在下方**(二轮时误插到最前)。
- **长按删除(二轮 + 四轮用户定稿)**:长按卡片 → 进入管理模式并选中该卡(卡右侧开关转 `Checkbox`,顶栏「添加订阅」转「删除」);管理模式整卡点击 = 切换选中;取消全部选中 / 删除完成自动退出。**正在使用的源不可删除** —— 勾选框 `enabled=false`,长按/点选 Toast「正在使用的源不能删除」,`deleteSelected()` 再过滤一层兜底;仅当列表被删空(边界:激活源不在列表中,如旧版本升级遗留)才 `ApiConfig.clearConfig()` + `AppBootstrap.retry()` 回引导态(**2026-09-11 修复**:原实现删光后保留 `API_URL`,会出现「订阅列表已空、App 仍用着被删的源」的状态不一致,重启也照样生效)。
- **添加订阅 dialog**:Material3 `AlertDialog`(标题「添加订阅」+ 两行 `OutlinedTextField`(名字 / 链接,label 常显)+ 右下角「保存」;链接为空时保存钮禁用)。同链接重复保存 = 更新名字,位置不变。
- **数据**:Hawk `HawkConfig.SUBSCRIBE_LIST` = `"subscribe_list"`(`ArrayList<String>`,每项 `名字\t链接`,分隔符约定同 HistoryHelper 的 `API_LINE_SPLIT`)。
- **连带删除(旧入口整链)**:设置页「接口配置」「接口历史」两行 + `ApiHistorySheet`/`ApiConfigSheet`/`ApiSheetHost`/`ApiConfigSheetHost`(`ui/dialog/ApiSheets.kt` 整文件)+ `Jump.showApiDialog()` + `MainScreen` 的 `ApiConfigSheetHost()` 挂载。保留 `HawkConfig.API_HISTORY`/`LIVE_API_HISTORY` 数据键(仍被 `ApiConfig` 仓源线路逻辑与 `LivePlayActivity` 直播设置「配置切换」消费)。
- **本地文件选择(2026-09-11 三轮引入 → 五轮改系统 SAF)**:添加订阅 dialog **标题右上角**「从本地选择」控件(40dp 圆形 surfaceBright 圆底 + `ic_file_choose.xml`(源 `.tubiao/文件选择.svg`,文件夹图标))。**五轮用户要求「把这个页面删除,改为 SAF」**:自绘 `LocalFileActivity` 整页删除 + Manifest 声明删除;`LocalConfigHelper` 重写为 SAF 链 —— 调用页注册 `ActivityResultContracts.OpenDocument()`(MIME `*/*`),`startLocalConfig(launcher, onResult)` 设 pending 后 `launcher.launch(...)`,回调经 `handleLocalConfigResult(activity, uri)` 把 Uri 转 `clan://` 接口地址**回填到链接输入框**(不直接保存)。转换规则 `localConfigToApi`:有存储权限且能取到真实路径 → 直接引用原文件;否则复制到 App 外置缓存 `config/`(SAF 自带读取授权,**无需存储权限**,故原内联 `PermissionHelper.requestStorage` 申请已删)。`MainActivity` 里专为旧 sheet 挂的 `localConfigLauncher`/`launchLocalConfig` 此前已删。
- **数据层新增(2026-09-11)**:`ApiConfig.clearConfig()`(public)= `resetConfigData()` + `mHomeSource = null` + 清 `API_URL`/`LIVE_API_URL`/`HOME_API` + `HistoryHelper.clearApiLineList()`;供「订阅列表被删空」回引导态使用(激活源不可删后,该路径仅剩「激活源不在列表中」的边界情形)。安全性依据:空 `API_URL` 时 `loadConfig` 直接 `callback.error("-1")`(AppBootstrap 按「取消等待、离线继续」处理 → Ready),`getHomeSourceBean()` 有 `emptyHome` 兜底,`HomePage` 以 `sources.isEmpty()` 进引导态。
- **验证**:`:app:compileDebugKotlin -q` 退出码 0;`installDebug` BUILD SUCCESSFUL 26s 已装机(V2425A);真机待验:开关切换与全局刷新、**使用中的源置顶**、**新加源排在下方**、**使用中的源不可删**(长按/点选提示)、长按删除、添加首个源自动启用、**SAF 选择器选中文件回填链接**。

## 主题设置页(2026-09-11,用户要求"照搬示例文件/android 主题设置页的 UI、功能与图标")

- **来源**:`示例文件/android` 的 `feature/settings/.../ui/theme/`(ThemeSettingsScreen / ThemeSections / ThemePresetSeeds / ColorPicker)+ `core/designsystem/.../theme/`(ThemeState / ThemeConfig / Theme.kt)+ `core/ui` widgets(CapsuleSegmentedButton / IconContainer / StatusBarScrim / segmentedItemShape)。
- **项目适配(本项目无 Hilt / 无 MVI / 无 DataStore)**:
  - 主题状态 = 单例 `object AppThemeState`(Hawk 持久化 4 个键 `THEME_SOURCE` / `THEME_MODE` / `THEME_SEED` / `THEME_PALETTE_STYLE`;`mutableStateOf` 向全 App 广播)。**不建 ViewModel**:主题是进程级状态,页面只做"读状态 + 下发 intent",加 VM 只是转发(与 MainScreen 直读 AppBootstrap 同风格)。
  - 新增依赖 `com.materialkolor:material-kolor:5.0.1`(版本目录键 `materialKolor`,与示例项目同版本、本地 Gradle 缓存已有),用于 `PaletteStyle` 与 `dynamicColorScheme`(种子色 → 整套 M3 配色)。缓存策略同示例:主配色 `(seed,isDark,style)` 与色卡预览 `(seed,style)` 各一个 `ConcurrentHashMap`。
  - 页面 = 新 `ThemeSettingsActivity` + `ui/page/ThemeSettingsPage.kt`(走 Activity 跳转,符合 §2「不用 navigation-compose」);入口 = 设置 tab **首个分组**「主题设置」整行,值摘要 = 取色来源 · 深浅模式。
  - 新增组件:`ui/components/CapsuleSegmentedButton.kt`(胶囊分段选择器:ToggleButton + connectedShapes + 按下弹簧回弹)、`ui/components/ThemeColorPickerSheet.kt`(HSV 色轮取色器,装在既有 `AVBoxBottomSheet` 内)、`ui/components/EdgeToEdgeTopBar.kt` 追加 `TopBarActionBox`(40dp 圆形返回钮,供二级页复用)。
  - 图标(`app/src/main/res/drawable/`,逐字取自示例项目):`ic_color_palette` / `ic_brightness_auto` / `ic_light_mode` / `ic_dark_mode`。
- **状态栏/导航栏图标归属(关键设计)**:`AVBoxTheme` 新增 `manageStatusBarIcons: Boolean = true` —— 默认按**解析后的应用主题**决定图标深浅(深浅模式覆盖系统时也不会出现"深色图标压在深色栏上"),同时断言状态栏与导航栏;纯黑状态栏页面(详情页 / 直播页 / 播放器覆盖层 `ComposeVideoController`)传 `false`,继续由各自 Activity 恒白断言。`MainActivity.applyStatusBarAppearance()` 同步改为按 `AppThemeState.isDark(系统深浅)` 取值。
- **与示例的差异(未做项)**:示例在 `Application.onCreate` 预加载全部 8 色 × 9 风格的预览配色;本实现改为**按需计算 + 缓存**(色卡在 `produceState` 里走 Default 调度器),首帧可能以当前配色占位一瞬。
- **补丁①(2026-09-11,装机反馈截图:首个卡片离顶栏过近)**:顶部占位原照设置 tab 页规则给 `topBarHeight - 12dp`(设置页首个分组如此),结果「主题颜色」卡片几乎贴着「主题设置」标题。改为**二级页规则** `topBarHeight - 8dp + 28dp`(顶栏内容下沿 + 首卡间距 28dp),与栏目页(`topPadding + 28dp`)/ 搜索页 / 历史收藏一致。已 installDebug 装机。
- **验证**:`:app:compileDebugKotlin`、`:app:assembleDebug` 均 BUILD SUCCESSFUL;真机待验(主题切换即时全局生效、色卡预览、取色器、深浅模式覆盖系统时的状态栏图标)。

## 顶部应用栏无边框化(2026-09-11,三项决策经结构化提问确认)

- **需求**:顶部应用栏改无边框、内容延伸到状态栏、带"渐变模糊",参考 `示例文件/android`。**用户决策**:①范围 = 4 个 tab(首页/历史/收藏/设置)+ 二级页(搜索/栏目/本地文件);②顶栏**随滚动滚走**(照搬示例项目 `exitUntilCollapsed` 语义);③"渐变模糊" = **渐变遮罩**(示例项目顶栏的实际做法,非真模糊 —— 真模糊只用在示例项目底部导航条,依赖 kyant backdrop 库,零新增依赖优先)。
- **参考实现要点**(`示例文件/android`):顶栏容器透明(`containerColor/scrolledContainerColor = Transparent`)+ `Scaffold(contentWindowInsets = WindowInsets(0,0,0,0))` + 顶栏 `Modifier.windowInsetsPadding(statusBars)` + 内容 `contentPadding.top = padding.calculateTopPadding()` + `StatusBarScrim`(状态栏高 ×1.2,0.95→0.6→透明三档)。
- **本项目落地**:
  - 新组件 `ui/components/EdgeToEdgeTopBar.kt`:`rememberScrollAwayTopBarBehavior()`(M3 `TopAppBarDefaults.exitUntilCollapsedScrollBehavior`,需 `ExperimentalMaterial3Api`)、`rememberTopBarHeight(contentHeight = TopBarContentHeight/56dp)`(状态栏 inset + 顶栏内容高)、`ScrollAwayTopBar`(状态栏 padding + `onSizeChanged` 设 `state.heightOffsetLimit = -实测高` + `offset{}` 布局期读偏移,不触发重组)、`TopScrim`(渐变遮罩;无 pointerInput 不拦截触摸)。
  - 页面统一结构:`Box(Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection)) { 滚动内容(contentPadding.top = topBarHeight + 原顶部留白); TopScrim(); ScrollAwayTopBar { 顶栏行(56dp / 水平 16dp) } }`。
  - 接入:`MainScreen`(Scaffold `contentWindowInsets` 清零;底栏与手势条由 NavigationBar 自身承担)、`HomePage`(源胶囊 + 搜索钮作顶栏;直播 FAB 保持右下)、`HistoryPage`/`CollectPage`(大标题 + 管理控件作顶栏;加载/空态补 `padding(top = topBarHeight)` 保持居中观感)、`SettingsPage`(标题从滚动 Column 移入顶栏,内容首位插 `Spacer(topBarHeight)`,分组间距 28dp 不变)、`SearchActivity`(返回 + 搜索框作顶栏;**波浪线进度条与结果源筛选 chips 由固定改为 LazyColumn 首项**,随列表滚走)、`PartitionListActivity`(`VideoGrid` 新增 `topPadding: Dp` 参数,首卡间距 = topBarHeight + 28dp;加载/空态同上)、`LocalFileActivity`(标题 + 当前路径两行作顶栏,内容高 64dp;路径从"紧贴标题下方"改为顶栏第二行)。
  - 配套 `MainActivity.applyStatusBarAppearance()`:状态栏图标按主题深浅取反(原恒深色)—— 内容延伸到状态栏后顶栏区域即页面色,深色主题须用白色图标;init / onResume 反复断言(系统会按主题重置)。
  - **不在本次范围**:详情页 / 直播页(纯黑状态栏 + 播放器,属既有补丁⑤⑧设计)、播放器视频覆盖层。
- **坑与注意**:①顶栏滚走依赖 nestedScroll 上报 —— 内容滚动容器必须位于挂了 `nestedScrollConnection` 的容器之内(`verticalScroll` / LazyColumn / LazyVerticalGrid 均可);②`heightOffsetLimit` 必须由顶栏实测高度设置,否则 M3 默认 0 = 顶栏不动;③**`onSizeChanged` 必须排在 `windowInsetsPadding(statusBars)` 之外(更外层)** —— inset 高度由 `windowInsetsPadding` 在本层叠加,写在其内层只能测到「内容高」,顶栏最多上移内容高、残留状态栏那一段压在状态栏上滚不干净;④`onSizeChanged` 写出前先判等,避免无限重组;⑤搜索页结果态原"固定进度条/chips"需移入列表,否则内容无法延伸到状态栏。
- **补丁①(2026-09-11,装机反馈截图)**:修复「顶栏滚不干净、标题压在状态栏上」(现象 = 设置页滚动后「设置」标题停在状态栏那一条上,对照示例项目应为完全滚出)—— 即上述坑③,`ScrollAwayTopBar` 的 `onSizeChanged` 由 `windowInsetsPadding` 内层移到外层,收起上限由「内容高 56dp」修正为「状态栏 + 内容高」;已重新装机。
- **全站审查 + 补丁⑤(2026-09-11,用户要求"审查是否还有需要修正的页面")**:
  - 留白核对(首项距屏幕顶 = 状态栏 + X):首页 64(改造前 64 ✓)、历史/收藏 76(76 ✓)、设置 72(68,+4)、搜索未搜索 76(68,+8)、搜索结果 60/波浪线(52,+8)、栏目 76(68,+8)、本地文件 68(68 ✓);差异均来自"顶栏行 56dp 内内容垂直居中"(内容下移);≤8dp,判定可接受,不再逐页微调。
  - **发现并修复|顶栏记账脱节**:`ScrollAwayTopBarState` 的 `scrolledPx` 是"内容累计滚动量"记账,**内容被程序整体替换**(切源 / 换目录 / 换筛选 / 新搜索)时列表位置重置到顶部,记账仍是旧值 → 顶栏停在屏幕外而内容已在顶部。修复 = ①新增 `reset()`(公开),在这 4 个场景显式调用(`HomePage` 切源、`LocalFileActivity` `LaunchedEffect(currentDir)`、`SearchActivity.submit()`、`PartitionListActivity` 筛选确认;⚠️ 局部函数 `submit` 只能捕获**先声明**的变量,`val scrollBehavior` 须移到 `submit` 之前);②`onPostScroll` 加**边界自愈**:`consumed.y == 0 && available.y > 0`(列表已无法向下滚 = 在顶部)且记账非零 → 归零。
  - 其余核对项(加载/空态 `padding(top = topBarHeight)` 居中、滚出上限 = 实测顶栏总高、底部 padding、FAB、sheet 覆盖层)均正常。
- **补丁④(2026-09-11,装机反馈:搜索结果页留白过大)**:现象 = 搜索框下方到进度条/chips/结果分区之间空一大块。根因 = ① 结果态 `contentPadding.top` 多给了 16dp(该 16dp 原是「列表首项与上方固定 chips 的间距」,chips 移入列表后由 `spacedBy(24dp)` 承担);② 进度条与筛选 chips 由固定布局改成两个列表 item 后,被 `spacedBy(24dp)` 额外拉开(原为紧邻,间距仅 12dp 内边距)。修复 = `contentPadding.top = topBarHeight - 8dp`(不加 16dp);**进度条 + chips 合并为一个前导 item**(`key = "search_leading"`,内部 Column 保持原 12dp/0dp 内边距关系),同时避免空态下多出一个空 item。
- **补丁③(2026-09-11,装机反馈:首项卡片离顶部过远)**:现象 = 设置页首个分组被推远(比改造前多约 16dp)。根因 = 顶栏行统一 56dp、行内内容**垂直居中**会产生下行余量(标题 32dp → 12dp;40dp 控件 → 8dp),而内容留白按「整行高度」给,余量被重复计入。修复 = 内容留白改为「顶栏内容下沿」(整行高度 - 行内居中余量)+ 原留白:设置页 `topBarHeight - 12dp`、历史/收藏 `topBarHeight - 8dp + 28dp`、搜索页 `topBarHeight - 8dp(+16dp)`、栏目页 `topPadding = topBarHeight - 8dp`;首页(行内 8+40+8 显式 padding)与本地文件页(64dp 行内 60dp 内容)视觉本就与改造前一致,不动。⚠️ 顶栏整行高度仅用于滚动滚出上限(`ScrollAwayTopBar` 实测),内容留白需按「内容下沿」计算。
- **补丁②(2026-09-11,装机反馈:轻滑一下顶栏就整体跑掉)**:现象 = 内容只滚了十几 dp,顶栏已完全消失(顶栏比内容先跑);对照示例项目应为内容滚多少顶栏移多少。根因 = M3 `TopAppBarScrollBehavior` 在 **fling 结束后按 velocity 吸附**(把 `heightOffset` 动画到完全收起/完全展开,javap 反编译 `ExitUntilCollapsedScrollBehavior$nestedScrollConnection$1` 确认其 `onPostFling` 读 velocity 后写 `setHeightOffset`),不是「跟随内容滚动量」。**修复 = 弃用 M3 behavior,改为自实现 `ScrollAwayTopBarState`**(`ui/components/EdgeToEdgeTopBar.kt`):`nestedScrollConnection.onPostScroll` 只读取 `consumed.y` 累计"内容净滚动量"(`scrolledPx`,向上滚为正),`offsetPx = (-scrolledPx).coerceIn(-高度, 0)`,**不消费滚动、无吸附**;`rememberScrollAwayTopBarBehavior()` 更名为 `rememberScrollAwayTopBarState()`。顶栏位置因此严格由内容滚动位置决定,滚过顶栏高度后再回滚也能正确还原。已重新装机。
- **验证**:`:app:compileDebugKotlin -q` 退出码 0;`installDebug` BUILD SUCCESSFUL 26s 已装机(V2425A)。真机待验:①4 tab 顶栏随滚动滚走/回滚复位;②内容穿过顶部时在状态栏区域渐隐;③搜索页进度条/chips 随结果列表滚动;④深色主题下状态栏图标为白色。

## 顶栏重做:M3 官方方案(2026-09-11 晚,自研记账两轮失联后用户拍板照示例)
- **背景**:自研 `ScrollAwayTopBarState`(补丁②引入的 1:1 增量记账)产生两类装机 bug:①列表在顶部而顶栏 offset 残留 ≈ 状态栏高(标题停进状态栏区,盖住时钟);②列表滚到中间而顶栏完全没跟随(标题叠在内容上)。方向相反、概率出现——增量记账与列表真实位置是两套状态,程序性列表复位(不经 nested scroll)必然失联;旧自愈依赖用户手势、顶部哨兵(`topDetector` + snapshotFlow)也只覆盖"列表在顶"单一形态。**弃用自研,逐字照 `示例文件/android` 的 SettingsScreen/ThemeSettingsScreen 重做。**
- **新组件** `AppTopBarScaffold`(`EdgeToEdgeTopBar.kt`,自研四符号全删):`exitUntilCollapsedScrollBehavior` + `Scaffold(nestedScroll, contentWindowInsets=0, containerColor 可传)` + `TopAppBar(windowInsets=0、statusBars padding、transparent)` + 内置 `TopScrim`;content 回调 `(topPadding, bottomPadding)` 且为 `BoxScope`(首页 FAB align 用)。`ScrollAwayTopBar`/`rememberTopBarHeight`/`rememberScrollAwayTopBarState` 不复存在;`TopScrim`/`TopBarActionBox` 保留。
- **8 页迁移**:设置(纯标题)、主题设置/配置管理/栏目(返回钮;配置管理右上「添加订阅/删除」、栏目筛选钮走 actions 槽;栏目 containerColor=surfaceContainer 遮旧窗口背景)、历史/收藏(标题+管理钮 actions)、首页(源胶囊行进 titleContent、搜索圆钮进 actions、FAB 留 content 内 align)、搜索(SearchField 进 titleContent、返回钮 navigationIcon)。各页原显式 `scrollBehavior.reset()`(切源/新搜索/换筛选)删除——M3 behavior 官方管理无需手工归位。VideoGrid 的 scrollBehavior 参数随之删除。
- **留白换算**:`topBarHeight` → content 回调 `topPadding`,相对差值不变(设置 -12、历史/收藏/配置/主题 -8+28、首页 +8、搜索/栏目 -8);M3 TopAppBar 高 64dp(自研 56),整体留白 +8dp,与示例一致。
- **行为变化**:fling 吸附回归(当初补丁②否掉的"轻滑顶栏先跑"是 M3 官方行为,示例同款;用户为根除错位接受)。加载/空态 `padding(top = topPad)`、sheet 覆盖层、深色状态栏图标断言均不变。
- **验证**:`compileDebugKotlin` 退出码 0;自研符号全工程 0 引用;read_lints 9 个文件无诊断;`installDebug` BUILD SUCCESSFUL 37s 已装机。真机待验:①滚动跟手与吸附观感;②两类错位是否根除;③各页留白/64dp 顶栏视觉;④首页胶囊/搜索钮、历史收藏管理钮、配置管理/栏目右上钮位置。

## 选集网格两位集数溢出修复(2026-09-11,装机反馈;两轮定位后定稿)

  - **现象**:详情页「选集 → 全部」弹窗里,`第10集 / 第20集 / 第22~26集` 这些格子的文字**横向来回滚动**,停在中间帧时显示成 `)集 第`、`0集 身` 等错位字符;第 1~9 集、第 11~19 集不滚。用户补充"只滚三次,之后就和别的集数一样完整显示"。
  - **实测数据(用户截图逐像素量取;1260×2800 @ density 560 → 1dp = 3.5px)**:格中心间距 293.5px → 4 列格宽 **266px = 76dp**;文字墨迹宽 `第1集` 116px=33.1dp、`第9集` 123px=35.1dp、`第10集`~`第26集` **144~147px = 41~42dp**。
  - **根因(第一轮判断有误,此处为更正结论)**:`DetailActivity` 选集网格采用 `GridCells.Fixed(4)`,label 上挂了 `Modifier.fillMaxWidth().basicMarquee()`。Material3 的 chip 内边距为 `FilterChipDefaults.ContentPadding = PaddingValues(horizontal = 8.dp)`(即左右各 8dp),故两位集数可用宽度约 **44dp** 而文字需 **41~42dp** —— **确实溢出约 2~3dp**。因此 `basicMarquee` 并非"误触发":它正确检测到了这 2~3dp 的溢出,只是以"横向滚动 + 滚满 `repeatCount`(默认 3)轮后停在中间帧"的形式表现,看起来像乱滚(用户观察到的"滚三次就停"即此)。⚠️ 第一轮结论曾误判为"文字远未超宽、属跑马灯误触发"(起因是把截图中一段非 chip 的像素跨度当成了 chip 宽度),改用省略号后立刻暴露成 `第2…`,才定位到真实原因。
  - **修复(用户选定"折中"方案)**:①label 字号 `14sp → 13sp`(两位集数需宽降至约 39dp);②`contentPadding = PaddingValues(horizontal = 6.dp)`(相对默认 8dp 多出 4dp);合计余量约 9dp,两位集数完整显示;③**去掉 `basicMarquee()`,改用 `overflow = TextOverflow.Ellipsis`**(并删除已无引用的 import `androidx.compose.foundation.basicMarquee`),避免"差一点点就整行乱滚"的观感;超长文件名(如 `xxx.2024.EP01.1080p.mkv`)仍单行省略号截断。
  - **教训**:①`basicMarquee` 不适合"仅差几 dp"的边界场景 —— 溢出量很小时滚动幅度极小,观感是"文字在抖/错位",不如省略号诚实稳定;跑马灯只应用于"确实超宽且必须看全"的场景。②排查像素问题**必须先确证所量跨度的归属**(chip?格?文字?),再据此下结论 —— 本轮一次误判即源于此。

## 快搜功能删除(2026-09-09,用户要求)

- **整链删除**:`FastSearchActivity.kt`/`FastSearchEngine.kt` 两文件、Manifest 声明、SearchActivity 页内「快搜」入口按钮、设置页「快搜」开关(SettingsState.fastSearchMode/FAST_SEARCH_MODE 读写)、Jump.kt 的 FAST_SEARCH_MODE 分支(jumpToDetail 源缺失回退与 jumpToSearch 均固定走 SearchActivity)、HawkConfig.FAST_SEARCH_MODE 常量。
- **死代码连带清理**:SourceViewModel 的 quickSearchResult/detailFallbackSearchResult 两个 LiveData 及 getQuickSearch/getDetailFallbackSearch(无调用方无观察者)、xml()/json()/postEmptySearchResult/postSearchResult 中对应分支、两参 postEmptySearchResult 重载;RefreshEvent 的 TYPE_QUICK_SEARCH/SELECT/WORD/WORD_CHANGE/RESULT 五个零引用常量;SearchHelper.splitWords(仅快搜分词使用)。
- **DetailActivity 兜底候选预填通道删除**:EXTRA_DETAIL_FALLBACK_CANDIDATES 常量与 initFromIntent 的 bundle 读取、cacheFallbackCandidates(仅快搜点击结果携带候选时使用);⚠️ 详情页换源/相关推荐的**自建聚合搜索保留不动**(EventBus TYPE_SEARCH_RESULT + fallbackCandidates,与快搜无关);SourceBean.quickSearch 源配置属性保留(isQuickSearch() 被换源过滤使用)。
- **保留未动**:SearchReceiver/ServerEvent.SERVER_SEARCH 远程推送搜索(SearchActivity 自用);styles.xml→item_bg_selector_right→button_detail_quick_search 旧样式链(仍被存活对话框样式引用,与快搜页面无关)。
- **行为变化**:jumpToSearch/jumpToDetail 源缺失时一律进普通搜索页;首页长按「搜索相似内容」同。搜索历史仍由 SearchActivity 写入。

## 卡片点击分发 + 网盘目录下钻(2026-09-11,三项决策已经用户确认)

- **背景**:用户 7 源配置混了三类语义 —— 影视源(`<9.10更新>修复文采 海绵`/`玩偶哥哥|4K弹幕`/`叨观荐影|预告片`/`聚剧|四盘`/`光影|不卡`)、网盘源(`📁我的云盘|我配置`,首页 = 「云盘配置」8 张动作卡)、音乐源(`🎙易听音乐|带歌词`,首页 = 歌手/榜单卡)。原实现「action 卡走 action,其余一律跳搜索」在音乐源与网盘目录卡上是错的(音乐卡搜出来的是别的影视源同名内容;`vod_tag=folder` 的目录卡被当影片)。
- **调研先行(读上游源码)**:判定字段 = `Vod.isAction()`(action 非空)/ `Vod.isFolder()`(`vod_tag=="folder"` 或 `cate!=null`)/ `Class.isFolder()`(`type_flag=="1"`)/ `Site.isIndex()`(`indexs==1`);分发集中在 `示例文件/TV-fongmi/app/src/mobile/.../ui/fragment/TypeFragment.java:191-208`(TV 版同构,leanback HomeActivity:399-411);优先级 action > folder(openFolder 下钻) > index 站(跳搜索) > 默认详情页。**上游没有「音乐」概念** —— 音乐源卡片走默认分支进详情页,音频靠播放器 `onAudio()→setAudioOnly(true)`。
- **决策(用户选定)**:①默认策略 = **保持搜索**(改动最小,兼容 2026-09-10 定稿);②切换入口 = **订阅源 sheet 行内标记**;③网盘目录下钻**本轮做,可递归**。
- **落地**:新建 `ui/page/VodCardAction.kt`(`SourceCardPolicy` 枚举 + `VodCardTarget` 密封接口 + `VodCardPolicy`(Hawk `source_card_policy`,只登记 DETAIL 的源)+ `resolveVodCardTarget` + `Context.dispatchVodCardClick` + `openVodFolder`);`AbsJson.AbsJsonVod` 增 `cate` 字段并在 `toXmlVideo()` 归一到 `tag="folder"`;`PartitionListActivity` 增 `MODE_FOLDER` + `startForFolder(folderId, name)`(目录 id 当分类 id,复用 `PartitionListVM`;递归 = 逐级开页,返回键回上级)+ action 卡 Toast;`PartitionListVM` 增 `runAction` / `actionMessages` / `refresh`;`SettingsOptionRow` 增 `trailing` 插槽;`HawkConfig` 增 `SOURCE_CARD_POLICY`。首页与栏目二级页的卡片点击都改走分发器(搜索模式与搜索结果页保持进详情)。
- **验证**:`:app:compileDebugKotlin -q` 退出码 0;read_lints 无诊断。
- **真机验证点**:①「订阅源」sheet 里把「易听音乐」「我的云盘」标记切成「详情」;②音乐源点歌手卡应进详情页并自动播放;③网盘源点目录卡进目录页、再点下级目录继续下钻、返回键逐级回退、末级文件卡进详情;④网盘配置卡(action)仍是 Toast + 列表刷新;⑤影视源点卡片仍跳搜索(行为未变)。

## 播放器覆盖层左右边距分档(2026-09-13,用户定稿)

- **诉求**:用户问「播放器控件距屏边缘多少 / 16dp 是否合适 / 大厂怎么设计 / 横屏是否该改 24dp」。核查原值 = 16dp(`PlayerTopBar` Row `start/end`、`PlayerBottomBar` Column `start/end`、锁屏钮 `end`),属 M3 compact 标准;但横屏画布宽已到 M3 的 expanded 档(测机 `screenWidthDp ≈ 930~1070`),16dp 仅占屏宽 **1.5%**,而竖屏预览态同样 16dp 占 **3.3%** —— 同一套控件两个方向观感差一倍。
- **定稿规则(用户确认)**:`playerEdgePadding()`(`player/ui/PlayerOverlay.kt`)= `screenWidthDp >= 600 ? 24.dp : 16.dp`,对齐 M3 窗口分档惯例(compact 16dp / medium 及以上 24dp);横屏 24dp 同时覆盖横屏挖孔落在左/右边缘的系统 safeInset(实测档位约 12~15dp)。
- **落点(用户指定范围,仅这 5 处)**:`PlayerTopBar` Row 左右、`PlayerBottomBar` Column 左右(含 KDoc 更新)、`PlayerLayers` 锁屏钮右。**未动**:顶栏顶 12dp、底栏下 16dp、进度条触摸高 `vs_30`、底栏菜单按钮高度、中央控制组与各提示浮层(居中,与边缘无关)。
- **本轮否决的两个改动**:①底栏菜单按钮行整体加高到 48dp —— 进度行在菜单行**之上**,行高增加会把进度条顶高 20~40dp(超过屏高 1/5),且按压高亮药丸会从 ≈28dp 变 48dp,破坏既定「轻量化文字条目」观感;②进度行与菜单行换序(进度条贴底、菜单行在上)= B 站/YouTube/media3 官方的排列,属结构性改造,超出本轮范围,待用户决定。
- **参考基线(实测 media3 1.9.0 官方 styled controller,解本机 AAR `res/values/values.xml` + `exo_player_control_view.xml`)**:底栏高 **60dp**、进度条触摸高 **48dp**、进度条距底 **52dp**(轨道中心 ≈76dp)、底栏 `marginTop` 10dp、时间文本左右 padding 10dp、控件组左右外边距 **0dp**(贴边)。即官方思路 =「触摸目标够大 + 内部控制间距」,而不是整组内容从屏幕边缩进。
- **⚠️ 排查结论(避免误判)**:边距与「边缘手势带」无关 —— dkplayer `PlayerUtils.isEdge()`(`player/src/main/java/xyz/doikki/videoplayer/util/PlayerUtils.java:162`)对**四边各 40dp** 内的触摸直接忽略,视频手势(亮度/音量/拖动/快滑)在该带内本就不响应;而覆盖层按钮是 Compose 控件自带 `pointerInput`,不受 `isEdge` 影响,放 16dp 同样可点。
- **验证**:`.\gradlew :app:installDebug --console=plain` → BUILD SUCCESSFUL(1m 3s),已安装到 `V2425A - 16`。
- **待办(用户尚未确认)**:覆盖层触摸目标统一到 48dp(锁屏钮 24dp、进度条 ≈24dp、菜单按钮 ≈28dp 均低于 M3 下限);若要同时保观感,需先决定是否把进度行移到菜单行**下方**贴底。

## 亮度/音量手势提示改为 M3 surface 药丸(2026-09-13,用户要求,附截图对比)

- **诉求(用户原文+截图)**:「手势控制音量和亮度出现的透明圆角胶囊改成和控制进度一样的风格,半透明的 material surface」——图1 = 现状(`音量0%`:深灰底 #6C3D3D3D + 2dp 白描边 + 固定 200x100mm 大框,居中白字);图2 = 目标(seek 提示的 M3 药丸:半透明白/浅 surface、无描边、贴合内容)。
- **落地(`PlayerLayers.kt` 单文件)**:`PlayerSlideHint` 改为复用 `HintPill` 构造 —— 居中放置,HintPill 提供「`shadow(4.dp, 圆角50)` + `surfaceContainer.copy(alpha = 0.9f)` + `padding(vs_20/vs_10)`」;文字由 `Color.White` 改 `MaterialTheme.colorScheme.onSurface`,字号仍 `ts_30`(与 seek 提示一致)。**尺寸由内容自适应**(「亮度50%」/「音量50%」),不再固定 200x100mm —— 与 seek 提示形态统一。
- **连带清理**:删除仅此处使用的 `PillBg` 常量,及随之失引的 import `foundation.border` / `layout.height`(`width`、`PillShape` 仍被 Spacer/HintPill 使用,保留);文件头视觉注释从「照搬 shape_user_focus(#6C3D3D3D + 白描边)」改为「提示类浮层统一 M3 surface 药丸」。
- **未动**:暂停浮层中央播放圆钮(Black 35% 圆底,功能按钮非提示)、长按倍速浮层(`0x66000000` + 12dp 圆角,用户未提)、直播页手势提示(`LivePlayActivity` 内 `gestureHintText`,现为 Black 60% + 10dp 圆角 —— 与点播页不同源,若需统一需另开一轮)。
- **验证**:`.\gradlew :app:installDebug --console=plain` → BUILD SUCCESSFUL(24s),已装 `V2425A - 16`;read_lints 无诊断。

## 竖屏详情页:进度行播放/暂停钮 + 双击暂停(2026-09-13,用户附截图要求)

- **诉求(用户原文)**:「影视详情竖屏界面下在播放器区域进度条的左边加上暂停按钮,同时把暂停按钮、进度条和全屏按钮的高度调整到同一水平」「竖屏详情页面应该支持双击播放器区域两次把视频暂停」。
- **落地 1 - 暂停钮(`PlayerBottomBar.kt`)**:预览态(`state.previewMode`)进度行行首插入 `PreviewPlayPauseButton` —— 触摸盒 **40dp**、`player_ic_pause/play` 图形 **22dp**、`ColorFilter.tint(White 90%)`,与详情页右下角全屏入口(`DetailActivity`:40dp 盒 + 9dp padding + 90% 白 tint = 22dp 图形)完全同款;点击走 `actions.onPlayPauseClicked()`(带 500ms 防抖);图标状态判定含 `BUFFERING/BUFFERED`(同 `PlayerCenterControls`,dkplayer 缓冲结束停在 STATE_BUFFERED)。
- **落地 2 - 三者同一水平线(关键计算)**:行高从 `vs_30`(≈24dp)变 40dp(按钮盒决定),若底距仍 16dp 则行中心上移 8dp、与全屏入口错位。故预览态底距改为 `16dp + playerDim(vs_30)/2 - 40dp/2`(= **DetailActivity 全屏入口 `bottom` 偏移的同一式子**) → 行中心 = 16 + vs_30/2,与全屏入口中心、以及**改动前进度条的中心线完全一致**(进度条只是被 40dp 盒垂直居中,自身位置未动)。⚠️ 两处式子必须同步改。
- **落地 3 - 双击暂停(`ComposeVideoController.kt`)**:`onDoubleTap` 去掉 `if (previewMode) return false` 提前返回(守卫 `isDoubleTapTogglePlayEnabled && !isLocked() && isInPlaybackState()` 保留),`onTouch` 的预览态分支注释同步更新。**副作用(固有)**:GestureDetector 语义下单击显隐要等双击窗口超时(~300ms)才 `onSingleTapConfirmed`;预览态滑动/长按仍不响应。
- **验证**:`.\gradlew :app:installDebug --console=plain` → BUILD SUCCESSFUL(35s),已装 `V2425A - 16`;read_lints 无诊断。
- **真机验证点**:①竖屏详情页播放中呼出控制条 → 左下角出现暂停图标,与进度条、右下角全屏图标同一水平线;②点它切换暂停/播放且图标随状态变化(缓冲中显示暂停图标);③双击播放区暂停/播放;④单击仍能显隐控制条(会晚 ~300ms);⑤左右边距:暂停钮左边距 = 全屏钮右边距 = `playerEdgePadding()`;⑥横屏全屏不受影响(无该按钮)。

## 全屏/退出全屏旋转过渡修复 A+B(2026-09-13,用户报"16:9 视频突然拉伸铺满全屏再变竖屏")

- **定位(纯代码审查结论)**:`DetailActivity.applyFullscreen` 立即翻转 `vm.fullScreen` → `DetailScreen` 当帧按 `if (full) fillMaxSize() else fillMaxWidth+statusBarsPadding+aspectRatio(16/9)` 换形态,而系统旋转要 200~500ms 才落地;`AndroidManifest` 对 DetailActivity 声明 `configChanges="orientation|screenSize"`(不重建),**全项目没有任何 `onConfigurationChanged`**(grep 确认 0 处)。故过渡期在**旧方向的窗口**里渲染**新方向的形态**:①横屏→竖屏:16:9 在横屏窗口里装不下(宽推高 = 1.25×屏高)→ Compose `aspectRatio` 退化成按高定尺寸(≈2108×1186,还减去刚显示的状态栏)、靠 Column 左对齐 → "视频缩小 + 跳";②竖屏→横屏:当帧 `fillMaxSize()` 在竖屏窗口 = 整块竖屏 → "黑屏 + 中间小视频";③`画面缩放=填充(MATCH_PARENT)` 时 `MeasureHelper` 直接取容器尺寸 → **真的拉伸变形**(这是"拉伸铺满"最直接的来源);④连带 `setPreviewMode`/字幕字号当帧跳。同批核查了上游 fongmi(`示例文件/TV-fongmi`):`changeHeight()` = 短边×比例 + `clamp(150dp, 屏高/2)` 且 land/fullscreen/PiP 直接 return;进出全屏只改 `mBinding.video` 的 LayoutParams + 横屏时 `ChangeBounds` 150ms;`onConfigurationChanged` 只做自动旋转与沉浸重断言、不门控布局——**它的几何在任何方向都合法,所以不需要门控**。
- **落地 A(形态跟随实际方向,门控切换时机)**:`DetailViewModel.rotating: MutableStateFlow<Boolean>`,在 `setFullScreen()` 里按 `playContainerRef.resources.configuration.orientation` 与目标方向比较置位;`DetailActivity.onConfigurationChanged` 清位并 `syncFullBoxSideEffects()`;`isFullBox()` = `if (rotating) !landNow else fullScreen`(与 `DetailScreen` 的 `fullBox` 同一判定);`DetailScreen` 把播放器 Box、加载覆盖层、右下角全屏入口、页面内容四处从 `full` 换成 `fullBox`。直播页同套:`LivePlayActivity.rotating` + `isFullBox()`,替换 `LiveScreen` 背景、`LiveReadyContent` 的 PlayerArea/频道区、`PlayerArea` 的角标分支(逻辑分支 `onSingleTap`/返回/`hideSysBar` 仍用 `fullScreen`)。
- **落地 B(几何钳制)**:预览态播放区高度由 `aspectRatio(16/9)` 改为显式高度 `短边 × 16:9` + `coerceAtLeast(150dp).coerceAtMost(max(150.dp, 长边/2))`(取 `LocalConfiguration.screenWidthDp/HeightDp`,短边=竖屏宽、长边/2=高度上限,均与方向无关),点播/直播两处同算法。**未做**:B 的"切换动画"部分(Compose 里会给 `AndroidView` 逐帧 resize 触发 SurfaceView 重设尺寸,且动画发生在旋转之后、反而把注意力吸到那一跳上;fongmi 需要动画是因为它在错误方向切形态,我们已用 A 消除了那次错误切换)——如仍想要,加 `Modifier.animateContentSize()` 一行即可试。
- **无额外延迟(用户追问已答复)**:`full` 目标态仍当帧翻转(沉浸切换/返回键/按钮反馈零延迟),**只有"形态采纳"落在旋转落地那一帧**,而那几百毫秒正是系统旋转动画本身,不会出现"点了没反应"。多连点安全:`rotating` 每次按「目标 ≠ 当前方向」重算,不是恒置位;回调缺失时退化为"当前方向的自然形态",不卡死。
- **验证**:`.\gradlew :app:installDebug --console=plain` → BUILD SUCCESSFUL(38s),已装 `V2425A - 16`;read_lints 无诊断。
- **真机验证点**:①竖屏详情页点右下角全屏:点击后画面**不动**,屏幕旋过去即铺满(不再有"竖屏整屏黑一下");②横屏按返回退出:画面保持全屏样转回竖屏,落地即变顶部 16:9 + 下方内容(不再"缩小靠左上跳");③预览态播放区高度与改动前一致(竖屏仍是满宽 16:9);④直播页同样两条路径;⑤`画面缩放=填充` 下也不再出现拉伸变形。

## 首页订阅源胶囊宽度 +20dp(2026-09-13,用户要求)

- **改动**:`HomePage.kt` 顶栏订阅源胶囊 `widthIn(max = 220.dp)` → **240dp**(2026-09-12 曾按用户要求由"占满顶栏剩余宽度"收到 220dp,本次再放 20dp)。胶囊仍是 `widthIn(max)` + 内容自适应,故**源名短于上限时宽度不变**,只有超长名(如「玩偶哥哥|4K弹幕」)会多显示 20dp 内容;注释与 spec §4.1 胶囊描述同步更新(不填固定宽度,避免短名胶囊被撑长)。
- **验证**:`.\gradlew :app:installDebug --console=plain` → BUILD SUCCESSFUL(29s),已装 `V2425A - 16`。
- **备注**:若用户本意是"无论源名长短,胶囊整体都宽 20dp"(即水平内边距 12dp → 22dp),改 padding 即可;两者语义不同,当前按"宽度上限 +20dp"落地。

## 退后台暂停浮层污染任务快照修复(2026-09-13,用户报"退出应用后后台管理里显示暂停状态")

- **现象与定位**:用户截图 = 后台管理(最近任务)卡片里,预览态播放器中央出现播放 ▶ 图标、左上出现 `pauseTitle` 标题(看起来"被暂停了"),但从后台返回会**自动续播**。根因链:`DetailActivity.onPause()` → `PlayContainer.hostPause()`(退后台自动暂停,`lifecyclePaused = isPlaying()` 记下并 `mVideoView.pause()`)→ `VideoView` 置 `STATE_PAUSED` → `ComposeVideoController.onPlayStateChanged` 收起底栏 → `PlayerUiState.pauseOverlayVisible` = `paused && !controlsVisible` 成立 → 画出暂停浮层 → **系统任务快照(在退后台瞬间抓取)把这一帧拍下**,于是卡片长期显示"暂停";而 `hostResume()` 会 `mVideoView.resume()` 自动续播,所以"实际没暂停"。即:暂停浮层没有区分**生命周期暂停**与**用户暂停**。
- **修复**:`PlayerControlApi` 新增 `setLifecyclePaused(boolean)` → `ComposeVideoController` 写入 `PlayerUiState.lifecyclePaused`(新增 Compose state);`pauseOverlayVisible` 追加 `&& !lifecyclePaused`;`PlayContainer.hostPause()` 在暂停前调用 `setLifecyclePaused(true)`,`hostResume()` 复位 false。两处写入同在退后台那一帧内完成,浮层不会先画后收(no flash)。手动暂停后进后台仍照实显示(回前台 `hostResume` 复位后浮层照常)。`hidePauseRoot()` 注释同步说明(它仍是空实现,浮层纯派生)。
- **验证**:`.\gradlew :app:installDebug --console=plain` → BUILD SUCCESSFUL(30s),已装 `V2425A - 16`。
- **真机验证点**:①播放中点 Home → 最近任务卡片里播放器区应只有视频帧(无 ▶ 图标、无左上标题);②返回应用自动续播;③手动暂停后点 Home → 卡片不显示暂停浮层,返回后视频仍处暂停且浮层正常出现;④耳机/后台音频场景(音频模式 `hasAudioOnlyPlayback`)不受影响。

## 竖屏详情页标题行增加投屏入口(2026-09-13,用户要求)

- **需求**:竖屏详情页收藏图标左边加一个投屏控件,图标用 `.tubiao/投屏.svg`,点击**复用播放器界面的投屏 dialog**。
- **素材**:`.tubiao/投屏.svg`(24px / viewBox `0 -960 960 960` 单 path)→ `res/drawable/ic_detail_cast.xml`,按项目约定加 `<group android:translateY="960">` 平移、`fillColor="#FFFFFFFF"`(由 `Icon` tint 着色)。
- **入口**(`DetailContent` 标题行,收藏钮左侧):`IconButton { Icon(painter = ic_detail_cast, contentDescription = "投屏", tint = onSurfaceVariant, size = 24dp) }` → `activity.playContainer?.showCast()`;`PlayContainer` 新增公开方法 `showCast()` 直接转调私有 `showCastDialog()`,**与播放器底栏「投屏」完全同一条链路**(构造 `CastVideo` → `uiState.setCastSheet(CastSheetState(...))` → `PlayerOverlay` 的 `CastSheet`)。面板本身是 `Dialog`(独立窗口),故在竖屏详情页触发也能全屏弹出,不受播放器区域裁剪影响;无可投地址时沿用内部 Toast「暂无可投屏播放地址」。
- **验证**:`.\gradlew :app:installDebug --console=plain` → BUILD SUCCESSFUL(28s),已装 `V2425A - 16`。
- **真机验证点**:①竖屏详情页标题行右侧出现「投屏 | 收藏」两枚图标钮,投屏在左;②点击弹出与播放器「投屏」一致的设备面板(标题「投屏到设备」、刷新/取消、DLNA/TVBox 扫描);③选中设备投屏成功后播放器自动暂停(`onCastSuccess` → `mVideoView.pause()`);④未取到播放地址时给 Toast 而非空白面板。

## 依赖升级:OkHttp 3.12.11 → 5.5.0 + Okio 2.8.0 → 3.18.2(2026-09-13,用户要求)

- **版本**:`gradle/libs.versions.toml` 的 `okhttp = "5.5.0"`、`okio = "3.18.2"`(实际解析:okhttp 5.5.0 + okhttp-android(Android 变体 AAR) + okhttp-dnsoverhttps 5.5.0 + okio-jvm 3.18.2;OkGo 3.0.4 / Picasso 2.71828 / coil-network-okhttp 请求的 3.x~4.12.0 全部提升到 5.5.0,均编译与运行通过)。**升级 5.x 的直接收益:官方 3.18.1 有 base64 padding 缺陷,3.18.2 修复**。
- **fork 移除(关键)**:删除仓库内 fork 的 `app/src/main/java/okhttp3/dnsoverhttps/`(`DnsOverHttps.java`/`DnsRecordCodec.java`/`BootstrapDns.java`)—— 该 fork 依赖 OkHttp 3.x 内部结构(自定义 `lookupHttpsForwardSync`),而 OkHttp 5 的 `Dns` 接口新增了嵌套类型 `Dns.Request`/`Dns.Callback`/`onRecords`,fork 的 `implements Dns` 会**继承这些嵌套类型并遮蔽同名导入**(`okhttp3.Request`/`okhttp3.Callback`),Java 侧报"不兼容的类型: okhttp3.Dns.Request 无法转换为 okhttp3.Request"等 8 处错误,且继续维护需要 Guava 化的 PublicSuffixDatabase 兜底(内部 API 不稳定)。
- **改用官方构件**:`libs.versions.toml` 新增 `okhttp-dnsoverhttps = { group/name 同版本 ref }`,`app/build.gradle.kts` 加 `implementation(libs.okhttp.dnsoverhttps)`;`OkGoHelper` 的 import 不变(包名相同 `okhttp3.dnsoverhttps.DnsOverHttps`),仅 Builder 放宽:`DnsOverHttps.Builder().client(dohClient).url(HttpUrl.get(dohUrl)).bootstrapDnsHosts(...)`;官方 Builder 要求 **url 非空** ⇒ `dohUrl` 为空(关闭 DoH)时直接 `dnsOverHttps = null`(调用方 `OkDns`/`CustomDns` 已判空回落 `Dns.SYSTEM`)。
- **本地 `/dns-query` 端点重写**(`RemoteServer.java`):原 `lookupHttpsForwardSync`(返回拼接的 A+AAAA 原始响应)随 fork 消失。新实现 `buildDnsResponse(hostname, addresses)` 用 Okio 手写合法 DNS 应答(ID=0,flags `0x8180|rCode`,单 question + 全部 A/AAAA 答案,TTL 60s,无地址回 SERVFAIL);取地址走官方 `dnsOverHttps.lookup(name)`。⚠️ 该端点全工程无内部调用方(仅有服务端实现),如外部有依赖此接口的客户端需回归。
- **未改动**:`OkDns.lookup`/`CustomDns.lookup`/`OkHttp.string` 等调用点全部兼容(`Dns.lookup` 在 5.x 仍保留为同步便捷入口)。
- **验证**:`:app:compileDebugKotlin` + `:app:compileDebugJavaWithJavac` BUILD SUCCESSFUL;`assembleDebug`/`assembleRelease`(R8)`installDebug` BUILD SUCCESSFUL;真机 `V2425A` 冷启动进首页正常、外部 443 连接建立(网络栈可用)、首页数据正常加载(截图确认)。proguard 现有 `-keep class okhttp3.**` 覆盖新构件。

## 依赖升级:Room 2.3.0 → Room 3.0.2(androidx.room3 新命名空间)(2026-09-13,用户要求)

- **关键前提**:Room 3 **换命名空间**,不是同坐标升版本 —— `androidx.room:room-runtime/room-compiler` → `androidx.room3:room3-runtime/room3-compiler:3.0.2`;AndroidX 构件在 **Google Maven**(不是 Maven Central);`androidx.room` 组最高仅 2.8.5,3.x 只在 `androidx.room3` 组下(3.0.2/3.0.3 正式版)。用户提示看 `示例文件/android` 定位到本项目的参照用法。
- **构建改动**:`libs.versions.toml` 坐标换 room3 + 新增 `androidxSqlite = "2.7.0"`(`androidx.sqlite:sqlite-bundled`);`app/build.gradle.kts` 的 `annotationProcessor(room.compiler)` → **`ksp(...)`**(app 早已应用 KSP 插件,注释里写明"备用接入"正好用上)、新增 `implementation(libs.androidx.sqlite.bundled)`、schema 导出从 `javaCompileOptions.annotationProcessorOptions` 改为**顶层** `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`(放 defaultConfig 里无效)。
- **代码改动(8 个文件)**:import `androidx.room.` → `androidx.room3.`(批量替换);`AppDataManager` 加 `.setDriver(new BundledSQLiteDriver())`(Room 3 必须显式指定 driver,不再走 SupportSQLite);删除 5 个从未启用的 `Migration` 死代码 + 死 `Callback`(Room 3 里 `Migration.onMigrate()`/`RoomDatabase.Callback.onCreate()` 都是 **suspend + SQLiteConnection** 参数,Java 无法实现);`isOpen()` 三处判空改 `== null`/`!= null`(Room 3 移除 `RoomDatabase.isOpen()`,仅剩 internal `isOpenInternal$room3_runtime()`)。
- **实测结论(重要)**:Room 3 **支持 Java 源** —— 3 个 Java Entity、3 个 Java DAO(同步方法,非 suspend)、`@Database` 全部经 KSP 编译通过;`Room.databaseBuilder(Context, Class<T>, String)` 静态重载保留;`setJournalMode/allowMainThreadQueries` 仍在 Builder 上。**上层 `RoomDataManger`/`CacheManager` 与所有业务调用方零改动**。
- **验证**:编译 + installDebug 成功;真机冷启动正常;`databases/tvbox.v3.db.lck` 出现(= Room 3 连接池打开旧库成功,identity 校验通过)、db 本体时间戳未变(只读打开)。**真机已验证**(用户确认):历史/收藏 DAO 读写正常。

## 依赖升级:media3 1.9.0 → 1.11.0(2026-09-13,用户要求)

- **版本与约束**:`libs.versions.toml` media3 = "1.11.0"(最新稳定版已到 1.11.1);**jellyfin `media3-ffmpeg-decoder` 上游最高仍 1.9.0+1**(其 POM 编译基线 media3 1.9.0,2025-12 后未跟进)→ 保持 1.9.0+1,toml 注释写明"先保持,需实测 AC3/EAC3 软解路径"。
- **ABI 静态核对(预编译扩展 jar 与新版本的核心风险,结论:兼容)**:javap 对比 —— `DecoderAudioRenderer` 的 3 个抽象方法(`supportsFormatInternal(Format)`/`createDecoder(Format, CryptoConfig)`/`getOutputFormat(T)`)签名一字不差;ffmpeg 用到的两个构造器(`(Handler, AudioRendererEventListener, AudioProcessor...)`、`(Handler, AudioRendererEventListener, AudioSink)`)在 1.11.0 均存在;`SimpleDecoder` 的 4 个抽象方法(`createInputBuffer`/`createOutputBuffer`/`createUnexpectedDecodeException`/`decode`)一致。
- **预载现状**:1.11.0 的 `source/preload` 包仍是 `DefaultPreloadManager`/`BasePreloadManager`/`PreCacheHelper`,**仍无 `DiskPreloadManager`** —— 与 1.9.0 时代的结论一致,项目自实现的"共享 SimpleCache 写盘+播放读盘"预载方案不受影响,无需改动。
- **验证**:compileDebugKotlin/Java + installDebug 成功;真机冷启动进首页正常。AC3/EAC3/DTS 软解路径未实测(用户找不到此类片源),以静态 ABI 核对为准放行;`libs.versions.toml` 注释已同步更新为"已核对兼容"并精简全文注释(zxing/desugar/slf4j 三处长注释只留结论)。

## 依赖清理:移除 Picasso(2026-09-13,用户要求)

- **依据**:项目零业务使用(仅 `OkGoHelper.initPicasso()` 兜底,注释自认是给 Spider jar 用的)+ 参照工程 FongMi 不用 + **设备上真实第三方 jar 常量池扫描不引用它**(该 jar 自带依赖并混淆,只裸引用框架类)→ 判定兜底无实际需求。
- **改动**:`OkGoHelper` 删 `initPicasso()` 与两个 import(`Bitmap`/`Picasso`);`setMaxRequestsPerHost(10)` 非 Picasso 专属(作用于共享 defaultClient)迁到 `init()` 内 build 之后保留;`libs.versions.toml` 删 `picasso` 版本+库定义;`app/build.gradle.kts` 删依赖。
- **验证**:编译 + installDebug + 冷启动首页正常;`debugRuntimeClasspath` 依赖树已无 picasso。
- **同轮评估(OkGo,未执行)**:3.0.4 上游停更(2017 后无版本)且针对 OkHttp 3.8.1 编译,跑在 5.5.0 上属"跨大版本字节码"隐患;逐类扫描仅 `HttpLoggingInterceptor` 引用 `okhttp3/internal.*`,其余公开 API 5.5.0 全有,实测正常。移除=重写 15+ 文件(SourceViewModel/SubtitleViewModel/JsLoader/JarLoader/PlayContainer/各 Activity 等)的网络调用、tag 取消、AbsCallback 转换,列为独立待办。

## 缺陷修复:gson 升级导致 Hawk 集合数据全部读不出(2026-09-13,用户报告)

- **现象**:配置管理页添加源后只显示最后添加的一个(旧的被替换),退出重进列表空白;用户疑为 Room 3 升级所致(实测无关——源列表存 Hawk,不经 Room)。
- **根因**:**Gson 2.13+ 禁止匿名 TypeToken 捕获类型变量**(`IllegalArgumentException: TypeToken type argument must not contain a type variable`),而 Hawk 2.0.1(停更)的 `HawkConverter.toList/toSet/toMap` 正是该写法 → 所有 List/Map/Set 键的 Hawk 读取抛异常并被**静默吞掉**(`DefaultHawkFacade.get` catch 后返回默认值)。`saveSubscribe` 的"读-改-写"在读空时把已有列表覆盖成只剩新项,造成二次丢失。触发点 = gson 2.10.1 → 2.14.0 升级。
- **证据链**:Hawk2.xml 快照 diff(键全变、subscribe_list 变短)→ 探针日志(`contains=true/rawSize=NULL/putOk=true/readBack=NULL` + `Hawk.get -> Converter failed`)→ 本地 Gson 复刻测试(2.14.0 FAIL / 2.10.1 OK)。
- **处置**:先实现并验证了 Hawk 兼容补丁(`HawkCompatConverter` 改用 `TypeToken.getParameterized(...)`,已证实修复且旧数据恢复),**用户最终选择回退 gson 到 2.10.1**,补丁与全部临时诊断日志(LOG 前缀/LogInterceptor/ConfigManagePage 探针)已删除;`libs.versions.toml` 的 gson 行保留"不可升 2.13+"的约束注释。
- **验证**:回退版装机 → 配置管理页截图 4 个源完整显示;依赖树 gson 2.10.1 ✅。
- **可复用经验**:Hawk 静默失败(`put` 返 false 不抛、`get` 失败返默认值)→ 集合读写异常无提示;升级与停更库(Hawk)共存的依赖(gson)前必须核对兼容性;`Hawk.init(ctx).setLogInterceptor()` 是定位 Hawk 内部链路问题的利器。

## KV 存储迁移:Hawk → MMKV(2026-09-13,按 skill/avbox-kv-mmkv-spec.md 实施 P0–P3)

- **背景**:同日「gson 升级导致 Hawk 集合数据全部读不出」只做了回退 gson 的止血;本次按 spec 根治 —— 用 MMKV 承载全项目键值存储,彻底摆脱"停更库 + 匿名 TypeToken 推元素类型"的组合。
- **P0 门面(`util/KV`,新文件)**:
  - `gradle/libs.versions.toml` 新增 `mmkv = "2.4.2"`(`com.tencent:mmkv`);实例 = `MMKV.mmkvWithID("avbox_kv", SINGLE_PROCESS_MODE)`,**不加密**(spec §7-Q1),`App.initParams` 里 `KV.init(this)`。
  - API 对齐 Hawk:`put/get(key)/get(key,def)/contains/delete`。差异有二:① 类型推断不再靠匿名 TypeToken;② 写入失败返回 false 并打 `echo-kv` 日志(不再静默,G4)。
  - **类型编码**(`util/kvcodec/KVDecoder`,**纯 JVM 无 Android 依赖**以便单测):String 原样存;集合 / Map / JsonArray / 任意对象 → `\u0001json:` 前缀 + Gson 文本。读侧类型优先级 = 调用侧默认值的具体类型 → 注册表 → 复杂值退化为 JSON 节点树(只保证不丢值)。
  - **`util/kv/KVKeySpec` 键→显式类型表**(spec §7-Q3):集合与嵌套泛型必须登记。⚠️ 这不是迁移脚手架而是长期设施 —— 泛型擦除后 `new ArrayList()` / `new HashMap<>()` / `null` 默认值都带不来元素类型,不登记就会解出元素为 `LinkedTreeMap` 的集合(取值 ClassCastException / 写回把元素类型写坏)。**这正是旧 Hawk 的病灶**:`HawkConverter.toList/toMap` 拿 `new TypeToken<List<T>>(){}.getType()` 推元素类型,在 gson 2.13+ 直接抛异常。
  - **单测 14 例**(`app/src/test/java/.../kvcodec/KVDecoderTest.java`,`testImplementation junit 4.13.2`,纯 JVM 不需要 Robolectric):ArrayList 元素恢复为 String、JsonArray 走节点树不按 List、嵌套 `HashMap<String,HashMap<String,String>>`、未登记键 + Object 默认值的兜底、坏数据返回 null 而非抛异常、静默副本等。
- **P1 一次性迁移(`util/kv/KVMigrate`)——已实现并真机跑通,随后整体删除**:触发 = 完成标记 `kv_migrated_from_hawk`;逐键 `Hawk.get` → `KV.put`,全部成功才写标记(幂等)。**删除原因**:用户 2026-09-13 明确"应用尚未发布、没有存量用户" —— 迁移唯一的价值(保护存量数据)不存在,留着只会让 Hawk/Conceal 永久留在依赖树里。连带删除:hawk 依赖(toml + `build.gradle.kts`)、`proguard` 的 `-keep class com.orhanobut.hawk.**`、旧库与 Conceal 密钥文件。**首装即原生 MMKV,不存在"旧库"这个前提。**(该段代码在删除前暴露过一个必崩缺陷,教训见下方崩溃复盘)
- **P2 全量切换**:**33 个文件**(与 spec §1.1 盘点的 34 个文件对账一致,HawkConfig 本身只含键常量)机械替换 `Hawk.` → `KV.` + import 替换;`HawkConfig` 类名与全部键字符串**保持不变**(§7-Q4 不做键名重构,零迁移风险)。顺带把两处字面量键收敛进 `HawkConfig`(`home_hot`/`home_hot_day`/`danmu_api_use_default`),便于类型登记。`LOG.FILE_LOG_PREFIXES` 增补 `echo-kv`。
- **P3 收尾(彻底解耦)**:移除 hawk/conceal 依赖链(`debugRuntimeClasspath` 依赖树实测只剩 `com.google.code.gson` + `com.tencent:mmkv`),`proguard` keep 规则清理,gson 版本锁注释解除(Hawk 已不在,2.13+ 约束消失)。**`util/kv/KVKeySpec` 保留** —— 它是 KV 正常运行的必需件(集合元素类型登记),不是迁移脚手架。
- **gson 升级 2.10.1 → 2.14.0(2026-09-13 用户要求,验证 G3 达成)**:gson 2.10.1 是当初为绕开"gson 2.13+ 与 Hawk 2.0.1 不兼容"而锁的版本(Hawk 的 `HawkConverter` 用匿名 `TypeToken<T>` 捕获类型变量,2.13+ 直接抛 `IllegalArgumentException`,导致 List/Map/Set 键整体读不出)。Hawk 移除后该枷锁消失,直接升到最新稳定版:**编译 + 19 例单测一次通过,零代码改动** —— 因为 KV 侧只使用稳定 API(`TypeToken.get(Class)` / `TypeToken.getParameterized(...)` 的替代路径:显式 `Type` 登记),不引用任何 gson 内部实现,也不再用匿名 TypeToken 推元素类型。spec 的目标 G3「解除 gson 版本枷锁」到此闭环。
- **踩坑记录(重要,写给以后的自己)**:批量替换 + 注释改写用 PowerShell `[System.IO.File]::ReadAllText/WriteAllText` 混用编码时,**中文注释被逐字损坏**(如"策略"→"略略"、"持久化"→"久久化"、`(`→`H`)且仍是合法 UTF-8,编译器不报错、单测也照过)。发现方式 = 逐文件把工作区与「HEAD 机械替换后」的文本做 `Compare-Object`,任何多出来的差异都要人工确认。补救 = 从 HEAD 取回、只用一种明确的编码(`Get-Content -Raw -Encoding UTF8` 读 + `UTF8Encoding($false)` 写)重做替换,再复核差异集合只剩预期改动。**教训:多字节文本的批量改写,验证步骤不可省,且不要在同一批文件上叠多轮不同的替换脚本。**
- **验证**:`:app:testDebugUnitTest` 19/19 通过;`:app:assembleDebug`、`:app:assembleRelease`(R8 + 资源压缩)BUILD SUCCESSFUL;`debugRuntimeClasspath` 依赖树 = `com.google.code.gson:2.10.1` + `com.tencent:mmkv:2.4.2`(**已无 hawk / conceal**);全库 grep 已无业务侧 `Hawk.` 调用。
- **未做(需真机)**:spec §6.2 全量回归(设置项/配置管理/搜索历史/线路历史与自动换线/直播分组与直播源切换/续播/弹幕/DoH/无痕/卸载重装走默认值)—— 本轮只做到编译 + 单测 + 构建。装机命令与预期见 spec §5 第 4 条。

## 崩溃修复:KV 迁移把"旧库里没有的键"写成代表值 → 搜索页 `Semaphore(0)` 必崩(2026-09-13,用户报"有崩溃")

> ⚠️ 相关迁移代码(`KVMigrate`)已按"应用未发布、无存量用户"整体删除;本节保留是因为其中两个缺陷/教训与**现行 KV 代码**直接相关。

- **现象**:装机后**一进搜索页必崩**:`java.lang.IllegalArgumentException: Semaphore should have at least 1 permit, but had 0`(`SearchViewModel.<init>` → `Semaphore(semaphorePermits)`)。
- **抓日志**:`adb logcat -d` 拿到 FATAL 栈(`androidx.lifecycle.ViewModelProvider` 反射创建 `SearchViewModel` → kotlinx `SemaphoreKt.Semaphore`)定位到崩溃点;`adb shell run-as <pkg> cat files/preload_debug.log` 拿到本轮迁移日志 —— **`migrate-start hawkKeys=26` 对 `migrate-done kvKeys=76`**,键数不守恒,一眼看出写多了;再 `exec-out cat shared_prefs/Hawk2.xml` 核对旧库真实键集合(26 个,`search_threads` 不在其中),`files/mmkv/avbox_kv` 里 `search_threads` 存的是 `json:0`,闭环。
- **根因**:`migrateRegisteredKeys()` 用 `Hawk.get(key, 代表值)` 直读,而 **`Hawk.get(key, default)` 在键不存在时返回的就是 default 本身**(不是 null);代表值(`0`/`false`/空集合)本意只是告诉 Hawk "元素是什么类型"。于是登记表里有、旧库却没有的键全被写进 KV 且写的是代表值 —— `search_threads` 业务默认 32,被写成 **0**,`Semaphore(0)` 直接抛异常。实测多写了 50 个假键。
- **同源第二个 bug(现行代码,已修)**:`KVCodec.decode` 用 `getValueSize(key) < 0` 判存在性 —— MMKV 该 API 底层是 `size_t`,**不存在的键返回 0 不是 -1**,判断恒 false,导致每个不存在的键都带 `raw=null` 进解码器刷 `type-mismatch`,一轮启动 **2.9 万行**,把有效日志淹掉。已改 `containsKey` + `raw == null` 兜底。
- **同批加固(现行代码,已修)**:`KVDecoder.coerceNumber()` —— Gson 解析裸数字 token 一律给 `Double`,按 `int` 读会 `isInstance` 失败而静默回落默认值(值看着对、类型被换掉);现按目标数值类型收敛,越界值降级为"回落默认值"(不静默截断)。
- **最终处置**:修好并真机验证后,用户指出"应用未发布、没有存量用户",于是**迁移整套(搬运 + 纠偏 + 完成标记 + hawk 依赖 + 旧库文件)全部删除** —— 首装即原生 MMKV。设备侧旧库已随之清掉。
- **验证**:单测 19 例(含本题回归 3 例:Double→int 收敛、越界回落、`json:0` 老实读出 0 而非猜测为缺省);`assembleDebug`/`assembleRelease` 通过;修复版真机冷启动后 `echo-kv` **只剩一行** `type-registry keys=75`,无 `FATAL EXCEPTION` / 无 `Semaphore should`。
- **可复用教训**:
  ① **"取默认值"与"判存在性"是两件事** —— 拿带默认值的 getter 去判断存在与否,必然把默认值当成真值;读取层必须把二者分开暴露(`KV.contains` vs `KV.get`);
  ② 一次性逻辑的完成标记必须可升版本,否则修复无法到达已执行过的机器;
  ③ **批量搬运/写入后核对数量守恒**,"完成键数"与"旧库键数"的对比是数据写坏的最早信号(本次首装机日志里就已显现,当时没看);
  ④ 不要用 size 类 API 的返回值猜存在性(注意 `size_t` 的"0 表示不存在"语义);
  ⑤ **没发布的代码不要背迁移包袱** —— 本次为一个不存在的需求写了完整的搬运 + 纠偏 + 标记体系,最后全部删除;先确认"有没有存量数据"再决定要不要迁移,能省掉整条链路与一整个缺陷面;
  ⑥ 单元测试要覆盖**语义契约**而不只是算法:当时的 19 例全是纯解码逻辑,迁移的"键不存在不得写入"没有任何测试覆盖,所以缺陷一路走到真机。

## 代码整洁:Kotlin 编译警告 25 → 5(2026-09-13,用户要求"行为等价的纯收紧")

- **背景**:CI 日志里有 25 条 Kotlin 编译警告(不影响构建)。按"是否值得动"分四类处理,**只做行为等价的三类**,弃用 API 迁移留待单独排期。
- **类别一 · 无效注解(1 条)**:`ComposeLiveController` 的 `@JvmOverloads constructor(context: Context)` —— 构造函数没有任何默认参数,注解完全无效(HEAD 里就有的历史遗留,不是本次改动引入)。删注解;`ComposeVideoController` 那个有默认参数、注解有效,**没动**。
- **类别三 · 冗余调用(11 条)**:`data?.subtitleList` → `data.subtitleList`(编译器的非空判定是权威,这类警告可放心直接删,推断错误会变成编译错误而不是运行期问题);`response.body?.string()` → `body.string()`(OkHttp 5 的 `body` 非空);`episode?.name ?: ""` → `episode.name`(`sheet.episodes` 是 `List<VodSeries>` 非空元素);`series?.name` → `series.name`;`(key ?: "") + "|" + (id ?: "")` → `"$key|$id"`(`key`/`id` 形参非空);`(getOrNull(0) as? LiveSettingGroup)?.x` → `getOrNull(0)?.x`(`liveSettingGroupList` 已是 `List<LiveSettingGroup>`,转换冗余);`currentPosition.toLong()` → `currentPosition`(`currentPosition` 本就是 `Long`)。
- **类别四 · 平台类型收紧(4 条)**:`LivePlayActivity` 的 catchup 解析里 `Matcher.group(1)` 是 Java 平台类型 `String!`,Kotlin 2.4 起会警告"nullable receiver"且传给 `String` 形参时类型不匹配。改为**显式非空断言** `matcher.group(1)!!` —— 与文件里既有的 `epg.startdateTime!!` 风格一致;断言成立的前提(`matches()`/`find()` 已返回 true、且两个正则都有捕获组)在注释里写明,等价于原语义,且把平台类型真正收紧成 `String`。
- **顺手**:`LivePlayActivity` 里 `response.body.string() ?: ""` 去掉多余 `?: ""`(我删掉 `?.` 后它就成了"elvis 恒返回左值",编译器会新报一条警告 —— 这类"修一条冒一条"要跟着收干净)。
- **未做(类别二,弃用 API,需交互回归)**:4 处 `Slider(...)` 弃用(应迁 `rememberSliderState` + `Slider(state, ...)`,但项目多处是"松手才落盘"的自持 state 模式,得逐个验证)、1 处 `onBackPressed()` 弃用(应迁 `onBackPressedDispatcher`;spec §4.7 记录过竖屏切集 `BackHandler` 的返回键语义,动它要回归返回行为)。
- **结果**:警告 **25 → 5 条**,剩下的正好是上述 5 条弃用 API。`compileDebugKotlin` + `compileDebugJava` + 单测 32 例(20+7+5)全过。
- **可复用经验**:Kotlin 的 "Unnecessary safe call / Elvis always returns left operand / redundant cast" 这类警告**可以放心批量清理** —— 编译器既然判定接收者非空,写错会直接变编译错误,不存在"删了才炸"的风险;而 "Only safe calls allowed on nullable receiver" 属于**真的类型缺口**(Java 平台类型),要用注释写明断言前提再收紧。

## 媒体通知扩展到影视内容(2026-09-13,用户报"播放影视也和音乐一样下拉通知栏能看到")

- **需求(用户原文)**:「播放影视也和音乐一样下拉通知栏能看到」。
- **定位**:前台服务通知 + MediaSession 原先只对**纯音频**建立,判据是 `PlayContainer.updateMusicSession()` 里
  `return !trackInfo.getAudio().isEmpty() && trackInfo.getVideo().isEmpty();` —— **影视有音轨但视频轨非空,直接被否**;
  另一处 `playStateObserver` 里的 `switchPlayback` 收尾分支同样用它。
- **处置(拆成两个概念,避免连带改掉既有行为)**:
  - `hasPlayableAudio()` = **有音轨** ⇒ 决定"要不要建会话/通知",影视同样满足(本次需求);
  - `hasAudioOnlyPlayback()` = **有音轨且无视频轨** ⇒ 只决定 `hostPause()` 里"退后台是否保持播放"(维持原状);
  - 抽出 `currentTrackInfo()` 复用取轨道信息的 try/catch;`getAudioOnlyPlayback()` 这个返回 `Boolean`(可为 null 表示"取不到")的旧方法随之删除。
- **⚠️ 明确不做的部分**:**退后台保持播放仍然只对纯音频生效** —— 视频退后台照旧由 `HostPause` 自动暂停(dkplayer 的 autoPause 未启用,是应用自己在管)。所以影视的通知语义是"暂停后仍能在通知栏看到、并能从通知恢复播放",**不是后台播放视频**。若将来要后者,改的是 `hostPause` 的判据,不是通知判据 —— 这两件事已在 spec §4.4 写明分界,不要混。
- **验证**:`compileDebugJava` 通过(无 `getAudioOnlyPlayback` 残留),已 `installDebug` 到 `V2425A`。
- **真机验证点**:①播放影视内容 → 下拉通知栏出现标题/集数/封面 + 播放暂停按钮;②切集后通知里的集数跟着变;③在通知里点暂停/播放,播放器状态同步;④**退后台视频仍会暂停**(与改动前一致);⑤播放纯音频(音乐源)行为不变,退后台继续播;⑥电视模式下(UI_MODE_TELEVISION)不建通知(`MusicPlaybackService.isSupported`)。

## ⚠️ 回归修复:上面那次"通知扩展到影视"引入的「有声无画」(2026-09-13 白天,用户报"只有海报、EXO/IJK 都一样")

- **现象**:任何视频起播后**只有海报画面、声音完全正常**;EXO 与 IJK 表现一致;与分辨率/码率无关(4K 网盘源最早被发现,实际所有源都中招);release/debug 一致 —— 一度被误判为 R8 裁剪或 media3 1.9→1.11 升级所致(两者均已排除:release dex 里 `MediaCodecVideoRenderer` / `FfmpegAudioRenderer` / `TextureRenderView` / `SurfaceRenderView` 全部健在)。
- **真机定位手段(可复用)**:`adb shell uiautomator dump` + `dumpsys SurfaceFlinger --list`。视图树显示播放器容器里 `artworkView`(封面 `ImageView`,MATCH_PARENT)**与 `surfaceView` 同处 `mPlayerContainer`,且封面在其之上**;而 SurfaceFlinger 里该应用的 `ActivityRecord` z=2 > `SurfaceView(...)(BLAST)` z=-2 —— 即 **app 窗口整体压在视频层之上**,所以窗口内的封面一旦 VISIBLE 就会把视频完全盖住(而 `showVideoFrame()` 只管 `frameCover`,管不到封面)。
- **根因**:上面那次改动把起播分支写成 `audioPlayback = true`(判据从"纯音频"变成"有音轨"),而 `audioPlayback` 同时是 `updateMusicSession()` 里给 `mVideoView.setArtwork()` 的开关 → **所有带音轨的视频都会去 setArtwork,把画面压成一张海报**。`playArtwork` 由取流结果的 `artwork` 字段回填,源里没给 artwork/lyric 时它一直是空串,于是该分支每次 `updateMusicSession` 都命中。
- **修复(两处,`PlayContainer` + `MyVideoView`)**:
  ① `updateMusicSession()` 的封面分支补上 `Boolean.TRUE.equals(isAudioOnlyPlayback())`(只有**确定**是纯音频才放行)与画面未就绪 `!isStartedPlayState(...)`(兜底任何"画面已出仍显示封面"的时序)两个条件;
  ② `MyVideoView.showVideoFrame()`(画面已出的那一枪)顺手 `clearArtwork()`,把**「有画面」与「显示封面」做成互斥**,任何未来的时序回归都会被这一枪兜住。
- **⚠️ 保持不动的部分**:`audioPlayback` 仍表示"有音频轨",继续驱动会话/通知(影视也能下拉看到),**通知功能不受影响**。**教训:`audioPlayback` 这个名字下面挂了两个用途(通知 / 封面),改它的赋值语义必须同时核对两个调用点。**

### 同批修掉的两个连带缺陷(用户要求"做 1 和 2")

1. **纯音频判定恢复三态**(`hasAudioOnlyPlayback()` → `Boolean isAudioOnlyPlayback()`,返回 null = 取不到轨道信息)。
   迁移把它压成 boolean 后,**同一个概念在两处对「未知」给出不同结论**:
   - `hostPause()` 退后台是否保持播放:迁移前是 `!Boolean.TRUE.equals(getAudioOnlyPlayback())` → **null 会正常暂停**;迁移后 `!hasAudioOnlyPlayback()` 让 **null 变成"不暂停"** → 起播瞬间 `getTrackInfo()` 返回 null 时,影视退后台会继续出声。本次恢复成 `!Boolean.TRUE.equals(...)`,与迁移前一致。
   - 封面兜底:用 `Boolean.TRUE.equals(...)` —— 只有确定是纯音频才显示封面。
   ⚠️ **两个调用点的用法不同且都不能改**:一个要"只有确定是纯音频才特殊对待",另一个要"只有确定是纯音频才放行封面"。规则写在 `isAudioOnlyPlayback()` 的 javadoc 里。
2. **封面在途图片请求改为可取消**:`clearArtwork()` 原来只做 `GONE + setImageDrawable(null)`,而 Coil 3 的 `GenericViewTarget.onSuccess()` **不检查视图可见性**(读过 3.6.2 源码确认),晚到的位图照样落到 ImageView 上 —— 换集/换线/换源时可能把过期海报盖到新画面上。现在:
   - `ImgUtil.loadPlayerArtwork(url, view)` 返回 `Disposable`(新增入口,与 `load()` 的唯一区别是把请求句柄交回调用方);
   - `MyVideoView.setArtwork()` 先 `cancelArtworkRequest()` 再发新请求;`clearArtwork()` 先取消再隐藏。
   - 依据(读 `coil-core-android-3.6.2-sources.jar`):`ViewTargetRequestManager.dispose()` 会**同步**把 `currentDisposable` 置 null(`ViewTargetDisposable.isDisposed` 随即为 true)并 `post` 一个主线程取消任务;`OneShotDisposable.isDisposed = !job.isActive`。**不依赖 Coil 的 `ViewTargetRequestManager`,自己持有句柄 = 取消语义明确可证。**


## 权限梳理:补通知权限申请 + 移除 5 项零引用敏感权限(2026-09-13,用户选"权限一起重新梳理")

- **触发**:用户问"当前项目是否需要通知权限"。核查结论:清单里声明了 `POST_NOTIFICATIONS`,但**代码里从未申请** —— targetSdk 37,Android 13+ 该权限是运行时权限、默认拒绝,于是 `MusicPlaybackService` 的 `startForeground` 通知不显示(服务能起,但用户看不到播放控制,部分 ROM 还会限制无可见通知的前台服务)。
- **补申请**:`PermissionHelper.requestNotificationIfNeeded(Activity)`(`XXPermissions` 28.3 的 `PermissionLists.getPostNotificationsPermission()`,仅 API 33+ 生效,已授权则秒回)。**申请点 = 启动时**(`MainActivity.init()`,2026-09-13 用户要求"启动就弹窗通知申请");`PlayContainer.updateMusicSession()` 保留一次兜底(覆盖"启动那次拒绝、后来想开"的路径,系统在拒绝两次后不再弹窗、只会静默返回)。拒绝**不阻断任何功能**。
- **移除的权限**(全项目零引用 + manifest-merger 报告核对来源):`READ_PHONE_STATE`(唯一使用者 `ScreenUtils.checkIsPhone` 靠 `getPhoneType()`,该调用 API 23+ 需此权限)、`GET_TASKS`(Android 5+ 废弃)、`ACCESS_FINE_LOCATION`(DLNA 组播锁不需要定位)、`MOUNT_UNMOUNT_FILESYSTEMS`(来自 `com.lzy.net:okgo:3.0.4`,系统签名权限,第三方应用纯噪声)、`player` 模块里与 app 重复的 `READ/WRITE_EXTERNAL_STORAGE` 声明。
- **`ScreenUtils` 改造**:`isTv()` 原来 = `UI_MODE_TYPE_TELEVISION || (屏幕很大 && !是手机)`。本项目是纯手机定位(`abiFilters` 仅 arm64-v8a、TV/遥控适配代码已全删),真正的风险反而是**大屏手机被 `SCREENLAYOUT_SIZE_LARGE` 误判成 TV**(会莫名隐藏锁屏钮)。故收窄为只认"系统声明为 TV"这一个权威判据,电话权限随之移除。
- **保留未删**:`REQUEST_INSTALL_PACKAGES`(零引用,但将来做应用内自更新必须用它;已在清单注释里写明"不需要就删本行"的用户决策点)。
- **踩坑**:`READ/WRITE_EXTERNAL_STORAGE` 在 app 清单里已**合法声明**(带 `maxSdkVersion=32`),我又加了两条 `tools:node="remove"` → 清单合并直接 `Validation failed` 构建失败。**同一声明里不能既要又要**。删掉那两条 remove 即可(okgo 声明的那份没有 maxSdkVersion,合并会自动保留我们带上限的版本)。
- **验证**:APK 实际权限从 **20 条降到 16 条**(含 1 条 androidx 内部权限),敏感项全部消失;`aapt2 dump xmltree` 核对通过;已 `installDebug` 到 `V2425A`。清单核对命令:
  ```powershell
  aapt2 dump xmltree --file AndroidManifest.xml app\build\outputs\apk\debug\AVBox_debug.apk
  # 或看来源:app\build\outputs\logs\manifest-merger-debug-report.txt
  ```
- **真机验证点**:①设置里应用权限列表应无"电话/位置/读取手机状态";②播放音乐类内容后,下拉通知栏应出现播放控制(首次会弹通知授权);③即使拒绝通知,音乐照常播放;④DLNA 投屏扫描仍能发现设备(证明删定位没影响组播);⑤大屏/普通手机进播放页,锁屏钮照常出现。
- **可复用教训**:① **清单里声明 ≠ 已获得权限** —— 运行时权限必须显式申请,只看 Manifest 会漏;② 依赖库会通过清单合并塞权限进来,**裁权限必须看合并后的 APK 清单或 merger 报告**,不能只搜自己源码;③ `tools:node="remove"` 不能与同名声明共存,否则构建期直接失败。

## 手势修复:竖屏上下滑改为调亮度/音量,删除"竖屏上下滑切集"(2026-09-13,用户报"上下滑动都会快进,看看 fongmitv")

- **现象**:用户反馈"进度手感调钝没效果",并补充"我上下滑动屏幕都会快进",要求对照 fongmi。
- **定位(对照 `player/.../GestureVideoController.java` 与 fongmi 参考工程)**:① `onScroll` 的方向判定 `abs(distanceX) >= abs(distanceY)` 与 dkplayer 第 189 行**逐字一致**,不是问题;② 真正的元凶是 `onScroll` **开头**的竖屏切集分支: `isPortraitEpisodeSwipe` 的判据只有 `abs(Δy) > abs(Δx)`(**与 80dp 阈值无关**),命中后**无条件 `return true`** —— 于是竖屏下**所有**上下滑都被它吞掉,后面的 `changeBrightness`/`changeVolume` 分支在竖屏永远到不了,用户既调不了亮度音量、又看到画面在换。③ 该分支是本项目 `VodController` 时期的扩展;**fongmi 没有它**(其手势完全交给 dkplayer 的 `GestureVideoController.onScroll`,只有横滑进度 / 半屏亮度 / 半屏音量三种),所以 fongmi 的上下滑稳定可用。这也解释了"改灵敏度没感觉"——`SLIDE_POSITION_FULL_WIDTH_MS` 只管**横滑**。
- **处置(用户选方案 2:只调亮度/音量,去掉竖屏切集)**:整套删除 —— `onScroll` 里的切集分支、`isPortraitEpisodeSwipe()`、`portraitEpisodeSwipeThreshold()`、`showPortraitEpisodeTitle()`、常量 `PORTRAIT_EPISODE_SWIPE_DP`(80dp)与 `PORTRAIT_EPISODE_TITLE_SHOW_MS`、字段 `portraitEpisodeSwipeTriggered`(`onDown` 里的复位一并删)、`episodeTitleRunnable`(含 `onDetachedFromWindow` 的 removeCallbacks)、`PlayerUiState.portraitEpisodeTitleTemp`(删除后无任何读写方)。**竖屏与横屏手势行为自此完全一致**。
- **保留**:`VodControlListener.playNext/playPre` 与 `listener?.playNext(...)` 仍被播放完成自动下一集、键盘/远端切集等使用,不是本次删除对象。
- **顺带保留的上一轮改动**:`SLIDE_POSITION_FULL_WIDTH_MS = 240000f`(横滑调钝,用户上一轮要求)—— 它现在只作用于**横滑进度**,方向判定与边缘屏蔽不变。
- **验证**:`compileDebugKotlin` + `compileDebugJava` 通过,全库 `portraitEpisode|PORTRAIT_EPISODE|episodeTitleRunnable` 残留 0 处;已 `installDebug` 到 `V2425A`。
- **真机验证点**:①竖屏预览态上下滑出现亮度/音量药丸、数值随滑动变化(以前完全无反应);②横屏全屏同样;③"禁用手势控制"开关开启后两者都不响应;④横滑仍是进度(滑满一屏 = 4 分钟);⑤单击显隐、双击暂停、长按倍速不受影响。
- **可复用教训**:**在 onScroll 开头做"无条件 return true"的形态分支,一定会吃掉后面所有手势** —— 这类"竖屏扩展手势"必须与既有手势划清边界(要么只在真的触发动作时才 return,要么放到独立手势通道),否则表现为"某些手势莫名失效/变成别的动作"。另外:**用户报"某个常量调了没效果"时,先确认这个常量所在代码路径是否真的可达**,本次就是路径被前置分支挡住。

## 新功能:偏好设置新增「禁用手势控制」(2026-09-13,用户要求)

- **需求(用户原文)**:「在偏好设置页面无痕模式和弹幕开关中间新增加一项功能,名为禁用手势控制,小标题为开启后将禁用手势控制亮度和音量,默认关闭。功能的作用是开启后无法再播放器页面通过手势控制音量和亮度」。
- **落地**:`PreferenceSettingsPage` 在无痕模式与弹幕开关之间插入 `SettingsSwitchRow(title="禁用手势控制", subtitle="开启后将禁用手势控制亮度和音量")`;键 = `HawkConfig.GESTURE_CONTROL_DISABLED`(`"gesture_control_disabled"`,默认 false,已登记进 `KVKeySpec`);`SettingsState` 增字段并在 `loadState()` 读取;判定收口在新的 `util/GestureHelper.isControlDisabled()`(照 `HistoryHelper.isIncognito()` 的既有约定:开关判定集中一处,消费侧统一调用)。
- **⚠️ 实现要点(踩过)**:该判定**必须是独立方法 `canChangeBrightnessVolume(event)`,不能并进 `canHandleGesture(event)`**。第一版我并进去了,复查时发现点播侧 `isPortraitEpisodeSwipe()`(竖屏上下滑切集)内部第一行就是 `if (!canHandleGesture(e1)) return false` —— 并进去会**连竖屏切集一起禁掉**,超出用户要求。改为:两个控制器各自新增 `canChangeBrightnessVolume = canHandleGesture && !GestureHelper.isControlDisabled()`,只在**竖屏滑动方向确认后**、真正要调亮度/音量之前判一次;关闭时该分支静默 return(不调值、不弹提示)。
- **生效范围**:点播(`ComposeVideoController`)与直播(`ComposeLiveController`)两侧都生效(直播侧手势本就只有亮度/音量 + 左右快滑切台,后者走 `onFling` 不受影响)。**不受影响**:单击显隐控制条、双击播放/暂停、横滑进度、竖屏上下滑切集、左右快滑切台。
- **验证**:`compileDebugKotlin` + `compileDebugJava` 通过。真机验证点:①开关默认关,与弹幕开关之间显示且带小标题;②开启后点播页上下滑不再出现亮度/音量药丸、数值不变;③开启后直播页同样;④开启后**竖屏上下滑切集仍然可用**;⑤横滑进度、单击、双击不受影响;⑥重启应用后开关状态保持。

## 缺陷修复:换源后搜索被窄化到只搜得到一个源(2026-09-13,用户报"换源后有概率只能搜到玩偶4K,重启恢复正常")

- **现象**:在配置管理页切换到别的点播源后,搜索**只剩一个源**(用户看到的是「玩偶4K」)有结果,其他源一条都不出;退出应用重进即恢复。
- **定位(纯代码审查 + 源码证据)**:`SearchActivity` 的 companion 里有一份**会话级**的"勾选搜索源"缓存,注释自称"与旧 SearchActivity 静态字段一致":
  ```kotlin
  @Volatile var checkedSources: HashMap<String, String>? = null
  ```
  它只在 `== null` 时装载一次(`LaunchedEffect` 里 `if (SearchViewModel.checkedSources == null) ...`),之后**永不刷新**;而这份缓存是**按源 key 记**的,源 key 属于**具体的源集合**。搜索筛选是
  `getSourceBeanList().filter { isSearchable() && checked.containsKey(it.key) }` ——
  换源后拿"旧源 key"去过滤"新源源列表",**只有两边 key 相同的源能活下来**,于是表现为"只剩某一个源"。重启清掉静态缓存,重新按新地址从 KV 取(新地址无记录 ⇒ 回落到"全部可搜源")⇒ 恢复。
- **为什么换源链路没兜住**:换源会走 `AppBootstrap.onApiUrlChanged()`(作废内存配置 → 广播刷新 → 重载),但那条链路**没有清这份缓存**;而唯一的写入点 `SearchHelper.putCheckedSources()` **全项目 0 个调用点**(死代码),意味着缓存一旦装载就再无修正途径。
- **修复(两层,缺一不可)**:
  ① **主修**:`AppBootstrap.onApiUrlChanged()` 增加 `SearchViewModel.clearCheckedSources()` —— 换源收尾是唯一正确位置(只有点播地址真的变了才需要失效);注释同步改成"四步"。
  ② **兜底**:`SearchActivity` 的装载条件从"只判 `== null`"改为 `isCheckedSourcesStale()`,判据三条 —— 未装载 / 属于别的源地址 / **选择里的源 key 已对不上当前源列表**(`SearchHelper.isSelectionStale`)。第三条正是本 bug 的不变量:选择永远是当前源 key 的子集。另外每次 `Boot.Ready` 也重对一次基准,避免"切源后配置尚未拉完时装载到错误基准"。
  ③ 顺带删除死方法 `SearchHelper.putCheckedSources()`。
- **遗留说明(已查,非本次问题)**:`detail` 页的"快速搜索"(`DetailActivity.startSourceSearch`)**不走这份会话缓存**,而是每次 `SearchHelper.getSourcesForSearch()` 按当前地址现取,所以不受本 bug 影响。但那里只按 `isSearchable()` 过滤、又在后续按 `isQuickSearch()` 过滤,两个口径不一致 —— 属既有行为,本次不动。
- **验证**:新增 `SearchHelperTest` 5 例(纯判定与 Android 解耦后单测),含"部分匹配也必须判过期"这一用户实测形态;`KVDecoderTest` 20 例 + `SearchHelperTest` 5 例全过,编译通过。**真机复现路径待用户确认**(切源 → 直接搜索,应可搜到新源的全部可搜源)。

## 缺陷修复(全量审查 P0×4):首页限流许可泄漏 / 直播 header 失效 / 音乐封面 NPE / 本地字幕 NPE(2026-09-13,用户"先修复p0")

本轮来自一次全量代码审查(完整清单见 `.codebuddy/memory/2026-09-13.md`),按用户指示先修 4 个 P0。

1. **首页分区限流许可永久泄漏**(`ui/page/HomeViewModel.kt`):`requestPartition` 原在 `launch` **之外** `loaders.getOrPut`,排队协程在 `loadHome()` release 全部 loader 之后才拿到许可,仍向「observer 已移除」的旧 loader 发请求 → 回调永不到来 → `suspendCancellableCoroutine` 续体永不 resume → `Semaphore(2)` 许可永久泄漏。触发=首页加载中切源/下拉刷新(20 分区 × 限流 2 必然排队);后果=分区永久 Loading,看门狗转 Error 后**重试同样卡死,只能杀进程**。修复=**loader 获取移进 `withPermit` 内** + 新增 `loadGeneration`(`loadHome()` 自增,旧代次协程拿到许可后直接放弃、正常归还许可)。09-12 只修了"pending 被覆盖"分支,本条是漏掉的"release 后再 request"。
2. **直播源 header/ua 全失效(KV 迁移回归)**(`util/kv/KVKeySpec.java`):`LIVE_WEB_HEADER` 登记为 String,实际写入 `HashMap<String,String>`(`ApiConfig.loadLives`) → 读取侧 Gson 用 String 解析对象原文抛错、被 `KV.get(key)`(quiet 副本)静默吞成 null → `liveChannelHeader()` 恒 null(5 处 `setUrl` 全失 header)。修复=改注册 `TypeToken<HashMap<String,String>>` + **新增回归单测** `KVKeySpecTest.liveWebHeader_roundTripDecodesAsStringMap`(真实注册表 + KVDecoder 往返);KV spec §8 追加 R10。
3. **音乐停止后封面回调 NPE**(`player/MusicPlaybackService.java`):Coil `onSuccess` 在服务停止(`mediaSession=null`)后仍会执行 → `buildNotification()` 取 sessionToken NPE。修复=onSuccess 先判空 return + `buildNotification()` 内 `mediaSession == null ? null : getSessionToken()` 双保险(`MediaStyle.setMediaSession(null)` 是官方支持路径)。
4. **本地字幕拷贝期间退出详情页 NPE**(`ui/player/PlayContainer.java`):后台线程读字段 `mActivity`(hostDestroy 已置 null)→ NPE,且 catch 分支二次访问 → 二次 NPE(非主线程未捕获 = 杀进程)。修复=**Activity 快照到局部变量** + 回主线程后 `isAttached()` 再判一次;`queryDisplayName` 改为接收 activity 参数。

- **验证**:`assembleDebug` BUILD SUCCESSFUL;`KVDecoderTest` 20/20 + `KVKeySpecTest` 8/8(含新增 1 例)全过;read_lints 无诊断。
- **待真机**:装机被 `No connected devices` 阻断(测试机未连接)。真机验证点 —— ①首页加载中切源/下拉刷新,分区正常出数据、不再永久转圈;②配置了 header/ua 的直播源能正常播放(修复前 403/黑屏);③播放音乐切歌后立即停止/退页不崩;④选本地字幕后立即返回不崩。

## 复查:P0 修复的新问题排查 + KV 类型注册表的 R8 验证方法(2026-09-13,用户要求"检查 p0 的另外几条修复是否引入了新的问题")

- **① 首页限流(`HomeViewModel`)**:CME 回归已修(见上一条);本轮复查完整时序 —— 遍历 `loaders` 期间被唤醒的旧协程由 `loader.released` 拦下、遍历结束后被唤醒的由 `loadGeneration` 拦下,两条路径都不泄漏许可、也不请求陈旧 loader;`retryPartition/loadMorePartition/applyFilter/refreshPartitions` 均在同一代次内调用,不受影响。**结论:无新问题**。
- **② 直播 header(`KVKeySpec`)**:类型链路三方一致(写入 `HashMap<String,String>` / 注册 `HashMap<String,String>` / 读取点期望 `HashMap`);历史落盘数据(`\u0001json:{...}`)按新类型可直接解出,无需数据迁移;全项目仅 `LivePlayActivity` 一处读取。**结论:无新问题**。
- **③ 音乐 NPE(`MusicPlaybackService`)**:`onSuccess` 提前 return 不影响 `artwork` 赋值(赋值在其之前);`buildNotification()` 的 null 分支实际**不可达**(4 个调用点全部前置判空:onStartCommand 在 onCreate 之后 / handleIntent 已判空 / pauseForSwitch 仅服务活跃期 / onSuccess 已判空);`handleIntent` 的 ACTION_UPDATE 判空不误伤首次启动(onCreate 已建 mediaSession);`MediaStyle.setMediaSession(null)` 有官方 null 保护。**结论:无新问题**,且顺带堵住"服务停止后 `acquirePlaybackLocks` 复活持锁 + 通知复活"。
- **④ 本地字幕(`PlayContainer`)**:Activity 快照 + 回主线程 `isAttached()` 复判;`queryDisplayName` 唯一调用点已同步改签名;主线程串行保证 `hostDestroy` 与回调不交错(`mVideoView` 置 null 在 `mActivity` 置 null 之前,但两者同在主线程一次性执行,回调不可能观察到"mActivity 非空而 mVideoView 已空"的中间态)。**结论:无新问题**。
- **R8/泛型签名验证(方法与踩坑,重要)**:
  - `:app:testReleaseUnitTest` **任务在当前 AGP 配置下不存在**(只有 testDebugUnitTest)→ `KVKeySpecTest` 头部注释已更新为可执行的替代验证方法。
  - **正确方法**:`dexdump -a <classes*.dex> | findstr /C:"annotation/Signature"` —— 每个 `* extends TypeToken` 的匿名子类应显示 `VISIBILITY_SYSTEM Ldalvik/annotation/Signature; value={...}`。**实测(重新 `assembleRelease` 后的产物)KVKeySpec$1~$10 全部保留**,含本次新增的 LIVE_WEB_HEADER 项:`TypeToken<HashMap<String,String>>` ×2 + `TypeToken<HashMap<String,HashMap<String,String>>>` ×1。
  - **⚠️ 踩坑**:**不要用"在 dex 里搜完整签名串"判断签名是否保留** —— D8 会把泛型签名**拆成片段**存储(如 `"Lcom/google/gson/reflect/TypeToken<" "Ljava/util/HashMap<" "Ljava/lang/String;" ">;>;"`),完整字符串在字符串池里不存在,直接字节搜索必然误判为"签名丢失"(本次为此白排查一轮,差点误改 proguard 规则)。
- **验证**:`assembleRelease` BUILD SUCCESSFUL(4m10s,+本轮静态检查);`:app:testDebugUnitTest` 通过;read_lints 无诊断;临时文件(dex/dump/脚本)已清理。

## 缺陷修复:预载 headers 口径统一 + 边播缓存 key 纳入 headers(2026-09-13,用户"这两个问题是否存在,如果属实请修复")

两条均经代码级核实**属实**,已修复并装机。

1. **预载「下一集秒开」对字符串形式 header 的源永不命中**(`PreloadCoordinator` vs `PlayContainer`):
   - **核实**:`PreloadCoordinator.extractHeaders` 只处理 `JSONObject` 形态,而播放侧 `PlayContainer.getHeaders` → `appendHeaders` 还处理 **JSON 文本形态**(`"header":"{\"User-Agent\":\"...\"}"`)。源用字符串形式时预载侧 `headers=null`、播放侧 `headers={User-Agent:...}` → `PreloadManagerHolder.tryAcquire` 的 `keyOf(url,headers)` 不等 → 预载内存数据永不命中(日志持续 `echo-preload-miss: key mismatch`),仅剩磁盘兜底,预载带宽白花。
   - **修复**:提取逻辑上收为 `PlayerHelper.extractPlayHeaders(JSONObject)`(+ `appendJsonHeaders` 辅助),**预载与播放共用同一实现**;`PlayContainer.getHeaders` 与 `PreloadCoordinator.extractHeaders` 均改为委托调用,删除 `PlayContainer.appendHeaders` 死代码(连 `java.util.Iterator` import 一并清掉)。
2. **边播缓存 key 只含 uri → 跨线路串缓存**(`ExoMediaSourceHelper`):
   - **核实**:`getCacheDataSourceFactory` 未设 `CacheKeyFactory`,走 media3 默认(key=`dataSpec.uri`)。同一 URL 配不同 Referer/UA/token 的源会互相读盘命中对方数据;且与预载侧 `keyOf(url+headers)` 口径不一致。
   - **修复**:`getCacheDataSourceFactory(upstream, headers)` 新增 `setCacheKeyFactory(dataSpec -> dataSpec.uri + headerKeySuffix(headers))`;`headers` 取自 `getHeadersFrom(MediaItem)`(**归一化产物**:已过滤 `TVBox-Format`、值 trim),`headerKeySuffix` 用 `TreeMap(CASE_INSENSITIVE_ORDER)` 排序 + trim,格式与预载 `keyOf` 一致(`\nk:v;`)。
   - **两侧同源论证**:预载侧 `PreloadMediaSourceFactory` 与播放侧都走 `getMediaSource(uri, headers, isCache=true)` → 同一个 `getCacheDataSourceFactory`;headers 也都由 `buildMediaItem/getHeadersFrom` 归一化 → key 逐字符一致(前提"两侧 headers 相同"由修复 1 保证)。
   - **副作用**:旧条目(key=uri)不再被引用,随 512MB LRU 自动淘汰,**无需数据迁移**;无 headers 时保持 media3 默认行为(key=uri)不变。

- **验证**:`assembleDebug`(25s)+ `installDebug`(21s,已装 V2425A)成功;read_lints 无诊断;全量单测通过。
- **真机验证点**:①开启「下一集预载」→ 播完自动切下一集应"秒开"且有「下一集已就绪」Toast(修复前字符串 header 源只有磁盘兜底);②同一 URL 不同鉴权头的源不再互相串缓存;③开启「边播边缓存」正常播放/回拖无异常(缓存 key 变更后首次播放走冷缓存,属预期)。

## 缺陷修复:纯音频(音乐)SurfaceView 渲染洞穿 —— 快照变白/回前台透视桌面(2026-09-13,用户报三联症状)

- **现象**:竖屏详情页播音乐(易听音乐,纯音频)应用内画面黑色(正常);退后台 → 多任务卡片播放器区域**变白**;从桌面回前台 → 过渡动画中播放器区域**闪烁透视到桌面**。用户实测补充:**仅 SurfaceView 渲染有此问题,TextureView 三症状全无**。
- **根因**:SurfaceView 的画面在独立于应用窗口的合成层上(本渲染视图 `SurfaceRenderView` 用 `PixelFormat.RGBA_8888` 可透明格式),应用窗口在播放器矩形被"打洞":无视频帧的内容全靠空 Surface 垫底呈黑;退后台任务快照里 Surface 垫底消失,该区域只剩窗口底色;回前台 Surface 重建前洞完全透明透视壁纸。TextureView 画在应用窗口图层内,无帧呈黑、快照与过渡动画全部正常。
- **修复(双层)**:
  ① 起播预判:`PlayContainer.looksLikeAudioUrl(url)`(mp3/m4a/aac/flac/wav/ogg/oga/opus/wma 后缀,去 query/fragment)→ `updateCfg` 后 `mVideoView.setRenderViewFactory(TextureRenderViewFactory.create())`,补住「起播 → 轨道信息就绪」之间退后台的空窗;
  ② 轨道信息兜底:`STATE_PLAYING` 时 `ensureAudioOnlyRender()`(`Boolean.TRUE.equals(isAudioOnlyPlayback())` 且 `MyVideoView.isSurfaceRenderActive()`)→ `switchRenderToTexture()`(`setRenderViewFactory(Texture)` + fork protected `addDisplay()` 热切换,音频不中断)。
- **审查确认的关键机制**:
  - **`replay(false)` 不重建渲染视图**(fork:`keepRenderViewOnReset` 分支只 reset + `startPrepare(false)`;普通分支 `startPrepare(true,true)` 也只 rebind)—— `addDisplay()` 只在 `start()`→`startPlay` 执行 ⇒ reusePlayer(切线路/清晰度/换集续播)路径①不生效,**必须靠②在 STATE_PLAYING 后重建**;这也是双层缺一不可的原因。
  - 旧 SurfaceView 摘除后 `surfaceDestroyed` 异步回调 `setDisplay(null)`(ExoMediaPlayer → `mInternalPlayer.setVideoSurface(null)`)落在**无视频轨**的播放器上是无操作,不影响新 Texture 挂载(热切换仅音频内容触发)。
  - artworkView 永在渲染视图之上(`addDisplay` 恒插 index 0);MeasureHelper 无视频尺寸时按父容器铺满;换集/换源下次 `updateCfg` 按用户设置恢复渲染类型,影视不受影响;误判(音频后缀实为视频)无功能损失,TextureView 照常渲染。
  - IJK `getTrackInfo` 已过滤内嵌封面(`isAttachedPicture`);EXO 对 mp3/m4a/flac/ogg 的内嵌封面走 metadata 不产生视频轨 → `isAudioOnlyPlayback()` 三态判定可靠。
- **验证**:`:app:compileDebugJavaWithJavac` + `:app:assembleDebug` 通过(BUILD SUCCESSFUL)。
- **真机验证点**:①设置保持「画面渲染: SurfaceView」播音乐 → 退后台多任务卡片播放器区域黑色(不再变白);②回前台过渡不透视桌面;③音乐后台续播正常;④正常视频画面/声音/进度正常(渲染仍走 SurfaceView);⑤切线路/清晰度/换集后音乐仍正常。
- **已知限制(未覆盖)**:直播页(广播类纯音频频道)不在本次修复范围(LivePlayerManager 独立链路);无后缀的音频直链/代理地址在轨道信息就绪前有短暂 Surface 窗口(秒级)。

### 补修:纯音频热切 TextureView 后不恢复渲染类型(2026-09-13,用户核实并要求修复)

> ⚠️ 本节**更正上一节的一个错误认知**:上文"换集/换源下次 `updateCfg` 按用户设置恢复渲染类型,影视不受影响"在 **reusePlayer 路径不成立**(见下)。

- **核实(属实)**:`VideoView.addDisplay()` 是**唯一**的渲染视图重建入口(移除旧视图 + 按当前工厂新建;全部调用点仅 `startPlay():(216)` 与手动 `MyVideoView.switchRenderToTexture(:124)`),而 `replay(false)` 的两个分支(`keepRenderViewOnReset()` → `startPrepare(false)`;普通 → `startPrepare(true,true)` 只 rebind)**都不调用它**。于是:纯音频把渲染热切成 TextureView 后,换到有视频的集走 reusePlayer 路径 → `PlayerHelper.updateCfg` 只改工厂、不重建视图 → **后续视频集继续留在 TextureView 渲染**,与「画面渲染」设置不符。
- **修复(双向对齐)**:
  1. `MyVideoView.ensureRenderViewMatchesConfig()`(新增):`mRenderView == null` 直接返回(留给下次 `start()` 创建);否则比较"工厂期望类型"(`!(mRenderViewFactory instanceof TextureRenderViewFactory)`)与"实际类型"(`isSurfaceRenderActive()`),**不一致才 `addDisplay()` 重建** —— 类型一致(绝大多数场景)时零开销、无闪烁。
  2. `PlayContainer.ensureAudioOnlyRender()` 扩为双向:确定纯音频 → Texture(既有);**确定有视频轨 → `ensureRenderViewMatchesConfig()` 恢复用户设置**(新增);轨道信息未知(null)两边都不动,避免误切。
  - 调用时机仍是 STATE_PLAYING 钩子(轨道信息已就绪、`showVideoFrame()` 已先执行,层级无冲突)。
- **安全性核对**:`addDisplay()` 复用既有热切换路径(纯音频 Surface→Texture 已实测) —— 旧视图 `removeView` 触发 surfaceDestroyed→`setDisplay(null)`、新视图 attachToPlayer + surfaceCreated→setDisplay,Exo/IJK 均安全;artwork/frameCover 层级不受影响(新 RenderView 固定插 index 0,二者此时均已隐藏)。
- **验证**:`assembleDebug` + `installDebug` + 单测 BUILD SUCCESSFUL(35s);read_lints 无诊断。
- **真机验证点**:①先播一首音乐(纯音频)→ 切到有视频的剧集 → 画面应为 SurfaceView 渲染(与「画面渲染」设置一致;修复前保持 Texture);②纯音频→纯音频、视频→视频不受影响(不触发重建);③设置选 TextureView 时任何切换都不重建(期望=实际)。

## 缺陷修复:WebView 嗅探共享集合并发竞争 + 「清除缓存」不再直删在用 SimpleCache(2026-09-13,用户"一起做吧")

两条均为此前全量审查中"属实但未修"的项,一并修复并装机。

1. **WebView 嗅探回调与主线程共享集合(数据竞争)**:
   - **事实**:`shouldInterceptRequest` 的官方 javadoc 明确"在非 UI 线程调用"且**不承诺串行**;`PlayContainer` 的三个共享集合(`loadedUrls` HashMap、`loadFoundVideoUrls` LinkedList、`loadFoundVideoUrlsHeader` HashMap)同时被网络线程(写)与主线程(读/重建)访问且**零同步** → 嗅探地址丢失、header 读不一致 → 解析随机失败(极端时集合损坏抛异常)。
   - **修复**:换并发容器 —— `loadedUrls`/`loadFoundVideoUrlsHeader` → `ConcurrentHashMap`;`loadFoundVideoUrls` → `ConcurrentLinkedQueue`(字段改 `volatile Queue<>`:因 `initParseLoadFound()` 会替换引用,volatile 保证网络线程立即可见新对象);`autoRetryFromLoadFoundVideoUrls` 补 `videoUrl == null` 判空(**ConcurrentHashMap 不接受 null 键**,旧 HashMap 允许);`size() > 0` → `!isEmpty()`(O(1) 且弱一致下更准确);删 `java.util.LinkedList` import。
   - **顺带核实(非问题)**:`stopLoadWebView`(网络线程调用)内部已用 `runOnUiThread` 包住 WebView 操作;`mHandler.removeMessages` 跨线程调用是 Handler 线程安全操作;`playUrl` 走 `EventBus.post` + `runOnUiThread`。

2. **「清除缓存」不再直接删除在用 SimpleCache 目录(方案 C:下次启动清理)**:
   - **事实**:`clearCache()` 会删除 `exo-video-cache`(含 `cached_content_index.exi`),而进程级共享 `SimpleCache` 常驻不 release → 内存索引与磁盘失配(有 `FLAG_IGNORE_CACHE_ON_ERROR` 兜底不崩,但缓存命中率退化到进程结束)。
   - **方案取舍**:A.release+重建 ❌(播放中 release 会让播放器后续 `startReadWrite` 断言失败 → IllegalStateException,比现状更糟);B.跳过不删 ✅但不彻底;**C.下次启动清理(采用)**。
   - **实现**:`FileUtils.clearCache()` 改为 ①先写"待清理"标记 → ②删除内部缓存(逐项、跳过 `exo-video-cache`——外部存储不可用时 Exo 会回落到内部) → ③删除外部缓存(跳过 `config` 用户数据与 `exo-video-cache`);新增 `FileUtils.purgeExoCacheIfPending()`(无标记时仅一次 `exists()` 检查 = 零开销;有标记时删除目录,"清空才清标记,否则恢复标记待下次重试");`App.onCreate` 在 `cleanPlayerCache()` 后用后台线程调用(`exo-cache-purge`,**必须早于首次 `getSharedCache`**)。
   - **已知取舍**:点完"清除缓存"后设置页占用**仍包含** exo 视频缓存(下次启动后归零)—— 换取了"播放中清缓存不中断播放"的安全性。

- **验证**:`assembleDebug` + `installDebug` + 单测 BUILD SUCCESSFUL(30s,已装 V2425A);read_lints 无诊断。
- **真机验证点**:①需 WebView 嗅探的解析源连续使用,不再出现"获取播放地址为空"类随机失败;②设置页「清除缓存」正常;③清缓存后**下次启动**再看占用,exo 视频缓存已归零;④清缓存期间正在播放的视频不中断。

### 复查修正(2026-09-13,用户"审查一下是否引入了错误")

- **结论**:并发容器替换本身无错(编译通过、语义等价、`initParseLoadFound` 替换引用 + volatile 可见性成立),但复查发现 3 处需加固:
  1. **`poll()` 返回 null 的下游 NPE(本轮 volatile 改动放大了触发面)**:字段改 volatile 后,`add` 与 `poll` 两次读之间若被 `initParseLoadFound()` 替换引用,网络线程的 poll **必然**落到新(空)队列 → 返回 null → `CookieManager.getCookie(null)` / `playUrl(null)` 在 `url.startsWith` 处 **NPE**(WebView 网络线程未捕获 = 进程崩溃)。修复:`checkIsVideo` poll 后 `if (url == null) return null;`(放弃本次拦截;`stopLoadWebView` 已把 WebView 导航到 about:blank)。
  2. **`autoRetryFromLoadFoundVideoUrls` 判空不完整**(上一轮只护了 header 查表):调用方只判 `isEmpty()`,检查与 poll 之间队列仍可能被消费/重置 → `playUrl(null)` 主线程 NPE。修复:poll 后 `if (videoUrl == null) return;`。
  3. **`purgeExoCacheIfPending` 标记清理顺序**:原"先删标记、失败再写回"在被杀(清缓存后下次启动、删除中转瞬退出)时会丢标记,残留不再清理。改为**清理确认完成后才删标记**(失败/被杀均保留标记,下次启动继续)。
- **顺带增强**:`LOG.FILE_LOG_PREFIXES` 增加 `"echo-exo-cache"` —— purge 的 start/done/incomplete 三条事件日志可落盘 `files/preload_debug.log`(此前该前缀不在白名单,且本机 ROM 抓不到 logcat,无法真机验证该功能)。
- **验证**:`assembleDebug` + `installDebug` + 单测 BUILD SUCCESSFUL(41s,已装 V2425A);read_lints 无诊断。

## 缺陷修复:真机两起崩溃(2026-09-13,用户报"应用刚刚是不是发生了崩溃",crash buffer 抓到)

- **崩溃①(11:52,旧构建)**:`NullPointerException: JSONObject.getInt on null` ← `PlayContainer.getSavedProgress` 读 `mVodPlayerCfg.getInt("st")` 为 null(原 try 只 catch `JSONException`,NPE 直接穿透);触发链 = 详情页**中央播放键**(`PlayerCenterControls → onPlayPauseClicked → ControlWrapper.togglePlay → start() → startPlay → ProgressManager.getSavedProgress`),即 `setInitBundle` 之前的空窗期点中央播放。**修复**:`st = (mVodPlayerCfg == null) ? 0 : mVodPlayerCfg.optInt("st", 0)`(顺手用 optInt 免掉 try/catch),空窗期点击不崩、片头跳过按 0 处理。
- **崩溃②(12:39,新构建)**:`IllegalArgumentException: Key "玩偶|131202" was already used` ← 详情页**相关推荐 LazyRow**(`RelatedSection` 的 item key = `sourceKey|id`)。根因:`DetailViewModel` 聚合搜索回调里 `relatedVideos.value = relatedVideos.value + related` **多源追加不去重**,同一 sourceKey|id 出现两次(玩偶源返回重复条目)即撞 key 闪退。**修复**:追加前按 `candidateKey(sourceKey|id)` 去重(`mapTo(HashSet)` 建 seen 集 + `seen.add` 过滤,同源同 id 留第一条;每次搜索 `relatedVideos` 重置,seen 随之重建)。
- **与渲染修复无关**:两处崩溃路径均不在纯音频渲染修复的改动范围(装机构建时间线佐证:崩溃①在 12:19:32 装机前、②在其后但属既有缺陷)。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL。真机复测点:①详情页加载完成前立刻点中央播放键不崩;②滚动/等待相关推荐加载不崩(玩偶源可复现时);③相关推荐无重复卡片。

## 缺陷修复:搜索页两处竞态(2026-09-13,用户"这个问题是否属实"→"修复")

用户对全量审查清单里的两条竞态质询真伪,逐环核实**均属实**(竞态、非必现),随后修复并装机。

1. **跨实例 token 撞号 → 旧搜索的迟到结果写进新一轮列表(用户可感知)**:
   - **事实**:`token` 是 `SearchViewModel` **实例字段**(实例内自增、从 0 起步)⇒ **每个新实例的首搜 token 都是 "1"**;而结果经**进程级 EventBus** 分发(`SourceViewModel` 的 `xml/json/postEmptySearchResult` 在 `searchResult == result` 时 `EventBus.post(TYPE_SEARCH_RESULT, data)`,`data.searchToken` 即传入 token)。旧实例退出只做 `unregister` + `viewModelScope` 取消 —— **不会中断** type=3 的阻塞爬虫(`JsSpider.call` 是 `pending.get(CALL_TIMEOUT_MS)` 阻塞等待,最长约 30s;`JsLoader.stopAll()` 只是 `spider.cancelByTag()` 按 tag 取消 HTTP),跑完照常 post。新实例的 `onSearchResultEvent` 只校验 token 字符串 ⇒ A 的 "1" == B 的 "1" 通过校验 → `updateResult` 把 A 的结果写进 B 的列表(B 已出结果时被 A 覆盖,持久到下次重搜)。
   - **修复**:`token` 取值改为**进程级自增**(`companion` 内 `AtomicInteger SEARCH_SEQ`;`search()` 里 `token = SEARCH_SEQ.incrementAndGet()`)⇒ 跨实例永不复用,旧实例迟到事件的 token 校验必然失败被丢弃;同实例内 `myToken != token` 的"旧轮次作废"语义不变。
2. **旧轮 finally 误删新一轮同源表项 → 该源空转 30s、进度条不消失**:
   - **事实**:新一轮先 `complete` 旧表项 + `clear()` 再按源重新登记;旧轮协程的 `finally { pendingSources.remove(bean.key) }` **一参删除、只认 key**。旧轮某源若正卡在阻塞的 `getSearch`(许可仍在手),其 finally 会**晚于**新一轮把同源表项放回 Map 之后才执行 → 删掉新一轮的表项 → 该源结果到达时 `pendingSources.remove(sourceKey)?.complete(Unit)` 返回 null、deferred 永不完成 → 只能等 `withTimeoutOrNull(30s)` 超时 → `awaitAll` 推迟 → `running=false` 推迟(结果其实已显示,但顶部波浪进度条继续转),该源还白占一个许可。
   - **修复**:`finally { pendingSources.remove(bean.key, done) }` —— `ConcurrentHashMap` **二参删除**,只在值仍是「本轮那份」deferred 时才移除,跨轮误删不可能再发生。
3. **同类外溢一并修复:`DetailActivity` 聚合搜索(复查"是否引入新错误"时发现的既有缺陷)**:
   - 详情页可**叠加**(相关推荐卡片 → `jumpToDetail` → 普通 `startActivity`,旧实例不销毁、聚合搜索协程继续跑并 post),而它的 token 是 `"detail_"` + 实例内自增 ⇒ 两实例首搜都是 `detail_1` → 旧实例迟到结果通过新实例校验(`DetailActivity.kt:651`)→ 新实例「相关推荐」混入另一部片名的搜索结果(返回旧实例反向同样被污染)。
   - **修复**:`DetailViewModel.searchToken` 取值改进程级自增(companion `AtomicInteger SEARCH_SEQ`;`"detail_"` 前缀不变)⇒ 跨实例不复用。其 `pendingSearchDone.remove(bean.key)`(一参)因三处 `startSourceSearch()` 调用点都有"不并发"守卫、无跨轮重叠,维持不动。
- **验证**:`assembleDebug` + `installDebug` + 单测 BUILD SUCCESSFUL(页面竞态修复 28s;DetailActivity 补充修复后 30s);read_lints 无诊断。
- **真机复测点**:①搜索 A → 退出搜索页 → 重进搜 B:B 的列表不应出现 A 的结果、也不应被 A 的迟到结果覆盖(需慢源,如 type=3 爬虫);②连续快速重搜(上一轮有慢源):结果出全后顶部进度条应及时消失(不再悬挂约 30s)。

## 缺陷修复:M3U8 去广告内容改「带键槽位」(2026-09-13,用户质询"是否属实"→选"完整方案")

用户对全量审查清单 ⑥ 质询真伪,逐环核实**属实但低概率/后果有限**,随后按用户选择的完整方案修复并装机。

- **核实结论**:
  - 机制属实:`RemoteServer.m3u8Content` 是 **`public static` 非 volatile 的无参单槽**;`M3u8PurifyUseCase` 每次净化都覆盖(**连无广告走直链的集也写**,窗口比原判断更大);`proxyUrl = getAddress(true) + "proxyM3u8"` **不带任何身份参数**;服务端 `startsWith("/proxyM3u8")` 无校验直接吐当前值 ⇒ 切集后旧播放器的重试/重连/切内核重拉会拿到"最后一次净化"的新一集列表。
  - 严重性有限:`/proxyM3u8` 的**唯一消费者是本机播放器**(投屏 `getCastUrl` 会把代理 URL 换回 source URL;`/proxy?type=m3u8&url=` 那条路本就请求级无状态),旧播放器重拉又多发生在切换瞬间 ⇒ 现实后果多为一次瞬时错误,而非持续串集。
  - 可见性一条修正:无同步/无 volatile 属实(JMM 无 happens-before),但写侧是 **OkGo 回调(默认主线程)** 而非工作线程;写读之间隔着 socket I/O ⇒ 陈旲读现实不可见,属理论问题。
- **修复(完整方案)**:
  1. `M3u8PurifyUseCase.processM3u8Content`:**只在真正走代理时才写入**(无广告不再无谓覆盖在播集槽位);`proxyUrl` 拼 `?k=<key>`;
  2. `RemoteServer`:`public static String m3u8Content` → **带键槽位**(访问序 LRU,保留最近 4 条):`putM3u8Content(content)` 返回键、`getM3u8Content(key)` 按键取(同步化,顺带消除无 volatile 的可见性问题);
  3. `/proxyM3u8` 读 `?k=`:**键缺失/不匹配返 404**(旧播放器走既有失败链路),命中才吐内容。
  - **为什么用 4 槽 LRU 而非严格单键**:自动重试(autoRetry)会重放 `webPlayUrl`(带旧键的代理 URL),严格单键会让"同一集重试"在净化重入后 404 成环;LRU 保证在播集反复重拉始终命中。
- **回归面核对**:`isM3u8ProxyUrl`(等值比较,proxyUrl 双方同为带 k 的字符串)✓;`getCastUrl`(仍能识别代理 URL 并换回 source)✓;`playUrl` 的 `startsWith("http://127.0.0.1")` 直通分支不受影响 ✓;进程重启后重新净化产生新键 ✓;无任何持久化 ✓。
- **验证**:`assembleDebug` + `installDebug` + 单测 BUILD SUCCESSFUL(30s);read_lints 无诊断。
- **真机复测点**:①带广告的 m3u8 源正常去广告播放(提示"已移除视频广告 N 条");②同一集内切内核/重试仍能正常播放(旧键仍有效);③快速切集后不出现"上一集/下一集画面串台";④投屏该源仍推送原始播放地址(不经本地代理)。

## 播放服务化 P0 + P1 第一步:会话数据/派生工具迁出 PlayContainer(2026-09-14,用户"现在开始 p0 和 p1")

按 `skill/avbox-playback-service-spec.md` 执行第一阶段(照搬 fongmi 播放器所有权模型的前置重构)。**行为等价是硬要求**:只搬位置,不改逻辑。

- **P0 接口与边界(行为零变化)**:
  - 新增 `player/PlaybackSession.java`:一次播放会话的数据(vod 引用 + sourceKey + 用户手动选线标记),取代"`App.setVodInfo` 全局单槽 + `Bundle`"两个隐式通道;附 `playbackKey()` 供 P2 归属判定(Spec D6)。`App.setVodInfo` 保留(本地 HTTP 服务的弹幕接口读它取当前片名)。
  - 新增 `player/PlaybackHostApi.java`:播放指令面;`PlayContainer implements PlaybackHostApi`(方法体未改,纯接口收口)。
  - 新增 `player/PageHost.java`:页面能力(context / isPageAlive / runOnUi / toast / 本地字幕选择器 / 通知权限 / 线路耗尽换源);`DetailActivity : BaseActivity(), PageHost` 实现,`ensurePlayContainer()` 内 `setPageHost(this)`。
  - **收口的三处硬耦合**(P2 服务化的前置条件,原先播放层直接依赖具体页面类):①`openLocalSubtitleChooser()` 去掉 `instanceof DetailActivity`;②`requestDetailFallbackAfterLinesExhausted()` 同上;③通知权限兜底改走 PageHost(无 PageHost 时保留 `mActivity` 回退)。
- **P1 第一步(调度层抽离的开头)**:
  - 新增 `player/PlaybackController.java`:承载会话数据(vod / playerCfg / sourceKey / sourceBean / 进度键与字幕键 / 歌词键 / 清晰度结果 / 净化代理地址)与**纯派生工具**(进度读取与继承、线路与剧集匹配算法、取流结果过期判定、清晰度发布、投屏地址改写、请求头提取)。
  - `PlayContainer` 改为持 `private final PlaybackController scheduler`,原同名成员下沉:字段声明与方法体删除、调用点改 `scheduler.xxx(...)`、字段读写改访问器/setter。文件从 **3074 行 → 2809 行**。
  - **仍未搬迁(P1 后续步骤)**:取流/解析/嗅探(`play`/`playUrl`/`goPlayUrl`/`initParse`/WebView)、重试与换线决策(`autoRetry`/`tryNextLine`/三处超时)、弹幕与预载调度、媒体会话与通知 —— 它们与 `MyVideoView`/`ComposeVideoController` 强耦合,须连同"视图契约"一起搬(Spec §3-P1 出口条件)。
- **机械重构手法(可复用)**:走 `.codebuddy/tools/refactor_p1_playcontainer.py`(括号感知删除方法体 + 词边界改名 + 赋值改写 setter + 残留报告),代替逐块字符串替换 —— 该文件满行的行尾空白会让手写 `old_str` 匹配失败。**已踩的两个坑**:①`mVodInfo.sourceKey` 中的 `sourceKey` 是 `VodInfo` 的字段,被连带改成 `scheduler.sourceKey()`(4 处,已修);②匿名内部类里 `ProgressManager.getSavedProgress` 的**覆写声明**也被"调用点改名"命中(已回滚)。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL;`read_lints` 无诊断;**未装机**(测试机未连接,`installDebug` 报 `No connected devices`)。
- **真机回归点(P0/P1 期望零行为差异)**:①详情页起播/切集/切线路/换源(含失败回滚)/切清晰度;②本地字幕选择、字幕搜索、弹幕、歌词、封面;③投屏(TVBox 推送 + DLNA,地址改写走新 `scheduler.getCastUrl`);④边播缓存开关、预载「下一集已就绪」、进度续播(退出重进接着看);⑤线路耗尽后的换源兜底(PageHost 链路)。

## 播放服务化 P1 第二组:重试/换线/超时迁入 PlaybackController(2026-09-14)

继续 `skill/avbox-playback-service-spec.md` 的 P1。本组把"**播放失败后的自我修复链路**"整体搬到调度层 —— 这是"详情页快速换页资源风暴"里最活跃的一段(起播失败→切内核→换线路→换源兜底都在这里)。

- **新增视图契约** `player/PlaybackViewBridge.java`:`PlaybackController` 决策所需的最小动作面 = 播放/提示/状态读取/解析停止/嗅探队列消费/内核切换/配置回刷/换源兜底。页面内由 `PlayContainer` 提供**匿名实现**(不扩大容器公开 API);P2 起改由"服务 → 页面"的桥实现。
- **迁入 `PlaybackController`(语义逐字保留)**:
  - 状态:`autoRetryCount/lastRetryTime/allowSwitchPlayer/hasAutoSwitchedPlayer/autoSwitchedPlayerType/allowAutoSwitchLine/playbackStarted/playTimeoutBasePosition/triedLineFlags/userPickedLine/reusePlayerOnSwitch/releasePlayerOnSwitch`;
  - 三处超时:独立 `timeoutHandler`(MSG 101 取流超时 / 102 换线播放超时;解析超时 100 仍留页面),`startResolvePlayUrlTimeout/startSwitchLinePlayTimeout/cancelSwitchLinePlayTimeout/cancelPlayTimeout/cancelResolvePlayUrlTimeout`;
  - 决策:`autoRetry()`(嗅探地址 → 切内核重播 → 换线路)、`tryNextLineIfEnabled()`、`tryNextLine()`(集号按集名匹配)、`restoreAutoSwitchedPlayer()`、`handleResolvePlayUrlTimeout/Failed()`、`handleSwitchLinePlayTimeout()`;
  - 状态查询/标记:`markPlaybackStarted()`、`isPlaybackStarted()`、`isStartedPlayState()`、`isFirstAttempt()`、`playTimeoutBasePosition()`。
- **页面侧改为"状态开关 + 委托"**(避免逐行改写流程):`beginNewPlay()`(等价 play() 开头四处赋值)、`markStoppedForSourceSwitch()`、`consumeReusePlayerOnSwitch()`、`clearTriedLines()`、`resetAutoRetryState()`、`setUserPickedLine()`、`setAllowSwitchPlayer()`、`setPlaybackStarted()`、`setPlayTimeoutBasePosition()`;`setAutoSwitchLineEnabled()` 变为纯转发。
- **文件规模**:`PlayContainer` 2809 → **2656 行**(初版 3074);`PlaybackController` ≈ 1000 行(含注释)。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL;`:app:testDebugUnitTest` BUILD SUCCESSFUL;`read_lints` 无诊断;**仍未装机**(测试机未连接)。
- **复盘要点(可复用)**:①"整组赋值 → 命名状态开关"比逐行替换更能保住流程可读性(play()/stopForSourceSwitch/回调复位三处);②迁方法时对"字段直读"必须补 getter(本次 `autoRetryCount`/`playTimeoutBasePosition` 各漏一处,由编译器兜住);③匿名视图契约实现里 `runOnUi` 必须自带 `isPageAlive()` 守卫,否则桥在页面销毁后弹 Toast 会踩空。

## 播放服务化 P1 第三组-3a:解析/嗅探层 + 取流结果观察者迁入 PlaybackController(2026-09-14)

继续 `skill/avbox-playback-service-spec.md`(用户"继续 p1 的 1")。本组搬"**解析与嗅探**"——WebView 嗅探、OkGo JSON 解析、聚合/超级解析、解析超时、嗅探结果队列,以及把它们汇成"可播地址"的 `playResultObserver`。

- **迁入 `PlaybackController`**:`initFetch()`(建 `SourceViewModel` + 观察者 + `observeForever`)、观察者全部逻辑、`initParse/jsonParse/doParse/parseMix/rsJsonJX/stopParse/initParseLoadFound/autoRetryFromLoadFoundVideoUrls`、WebView 三件套(`loadWebView/initWebView/loadUrl/stopLoadWebView/configWebViewSys/checkVideoFormat/SysWebClient`)、`getSubtitleUrl/isLyricSubtitle/searchDanmu`、取流状态字段(`webUrl/parseFlag/webPlayUrl/webHeaderMap/webUserAgent` + 嗅探队列/线程池);解析超时(MSG 100)并入控制器 handler。
- **视图契约扩容**(`PlaybackViewBridge`):新增 `firstUrlByArray/setArtwork/showParse/checkDanmu(danmaku,Runnable)/encodeUrl/evaluateScript/newSniffWebView/attachSniffWebView/showErrorWithRetry/isSwitchStopPending`;删除已不再需要跨层调用的 `stopParse/initParseLoadFound/tryRetryFromSniffedUrls/resolvedUrl/playHeaders/cancelPlayRequest`(全部变为控制器内部调用)。
- **页面侧**:`initViewModel()` 缩到 4 行(只建预载协调器);`hostDestroy` 的观察者注销改 `scheduler.releaseFetch()`;`play()` 命中预载数据时改 `scheduler.deliverPlayResult(preResult)`;`webPlayUrl/webHeaderMap` 改访问器;**`PlayContainer` 3074 → 2656 → 1902 行**(搬迁三组共 -38%),`PlaybackController` ≈1690 行;顺带清理 40 个失效 import。
- **验证**:`assembleDebug` + `testDebugUnitTest` BUILD SUCCESSFUL;`read_lints` 无诊断;未装机(设备未连接)。
- **踩坑(脚本重构的真实代价,已修正)**:①按签名删方法时**花括号配对会因注释/字符串里的花括号误判**,本次 `configWebViewSys`/`SysWebClient` 一带删完发现两文件各缺 3 个右括号(编译器"已到达文件结尾"报出)→ 用 `fix_braces_b3.py` 按计数补回并规范尾缩进;②**注解行不属方法体**,删方法后 `@SuppressLint("SetJavaScriptEnabled")` 会残留/被误删,需单独处理;③`private` 方法迁走后原同包可访问性失效(`doParse` 需改 public);④被删字段的注释残留(3 行 WebView 并发说明)要手工清。

## 播放服务化 P1 第三组-3b:取流入口 play/playUrl/goPlayUrl/selectQuality 迁入 PlaybackController(2026-09-14)

本组把"**起播**"整条决策链搬到调度层 —— 内核对齐/外部播放器/dash 强制 EXO/纯音频渲染/进度继承/预载命中都在这里,真正操作 `MyVideoView` 的连招交给页面的一个入口 `startVideoPlayback(...)`。

- **迁入 `PlaybackController`**:`play(boolean)`(切集/换线/换源/重播唯一入口)、`playUrl`(M3U8 去广告分流)、`goPlayUrl`(外部播放器 / dash / 复用判断)、`attachProxySiteKey`、`selectQuality`、`looksLikeAudioUrl`(纯 URL 谓词);随迁状态:`switchStopPending`、`pendingInheritKey/Progress`(换源"接着看"进度)。
- **视图契约 3b 扩容**(14 个):`setTitle/stopOtherPlayers/resetDanmu/clearLyric/clearArtwork/clearVideoFrame/setSubtitleViewVisible/onNewPlayStarted/onPlaybackSwitching/applyPlayerConfigToView(forceKernel)/useTextureRenderForAudio/playExternalPlayer/playM3u8/startVideoPlayback` + 预载 4 个(`invalidatePreload/hidePreloadReadyTip/consumePreloadResult/dropPreloadData`);并**删除**已可内部化的 `play/playUrl/isSwitchStopPending`。
- **页面侧**:`play`/`selectQuality` 变成 `@Override` 纯转发(PlaybackHostApi 契约不变);`stopForSourceSwitch` 只调 `scheduler.markStoppedForSourceSwitch()` + `scheduler.setPendingInherit(...)`;`clearSourceSwitchTip` 读 `scheduler.isSwitchStopPending()`。
- **规模**:`PlayContainer` 1902 → **1691 行**(初版 3074,**-45%**),`PlaybackController` **1980 行**;`PlaybackViewBridge` 共 40 个方法(下一批会随"弹幕/预载/媒体会话"再收口)。
- **验证**:`assembleDebug` + `testDebugUnitTest` BUILD SUCCESSFUL;`read_lints` 无诊断;未装机(设备未连接)。
- **新踩的坑(值得写进脚本约定)**:①**`play(false);` 的批量改名会误伤 `replay(false);`**(子串匹配!)—— 本次把 `mVideoView.replay(false)` 改成了 `mVideoView.rescheduler.play(false)`;批量改名必须用**词边界/前置断言**(`(?<![\w.])`),或对方法名先做全量枚举核对;②方法迁走后 **`implements` 的接口方法会缺失**(`PlaybackHostApi.play`),需在页面留 `@Override` 纯转发;③同批内"先改字段名再改调用点"的顺序仍要注意(本批 `view.xxx` 内部化是收尾单独做的)。

## 播放服务化 P1 收官:预载调度 + 媒体会话迁入 PlaybackController(2026-09-14,用户"继续完成 p1")

P1 最后两组。至此**调度层(会话/取流/解析/嗅探/重试/换线/超时/预载/媒体会话)全部搬出 `PlayContainer`**。

- **预载调度(4a)**:`PreloadCoordinator` 归调度层持有,`initPreload()`(建协调器 + 注册 "下一集已就绪" `ReadyListener`)、`onPlayerStateForPreload(playState)`(STATE_PLAYING/BUFFERED 排期评估、STATE_BUFFERING 让路)、`invalidatePreload()`、`destroyPreload()`;"何时喂快照/取结果/作废"全在调度层,页面只提供 `buildPreloadSnapshot()`(需上下文 + 真实内核实例判 ExoKernel)与 Toast(`showPreloadReadyTip/hidePreloadReadyTip`)。页面侧 `preloadCoordinator/preloadReadyListener` 两个字段与 4 个桥方法整体消失。
- **媒体会话/通知(4b)**:`updateMusicSession()`(含"只有确定纯音频才给封面,绝不压影视画面"的三条件注释与 2026-09-13 回归修复说明)、`hasPlayableAudio/isAudioOnlyPlayback/currentTrackInfo`(三态语义保留)、`ensureAudioOnlyRender()`、`stopMusicSessionForFailedPlayback()`、`stopMusicSession()`、`onHostDestroy()`、`handlePlayStateForMusicSession(playState)`(切换期状态机,返回 true 时页面直接 return)、`isConfirmedAudioOnly()`(退后台判定)全部迁入;`switchingPlayback/audioPlayback/playArtwork` 随迁。
- **关键抽象:`MusicPlaybackService` 的 owner 从 `PlayContainer` 改为 `PlaybackHostApi`**(通知栏播放/暂停/上一集/下一集/拖动都只打接口)——这正是 Spec §2.1/D2 的 `MusicControl` 归属模型,服务端已完全不依赖页面类。
- **视图契约第四批**:新增 `context()/playbackHost()/duration()/mediaPlayer()/requestNotificationPermission()/switchRenderToTexture()/ensureRenderViewMatchesConfig()/buildPreloadSnapshot()/showPreloadReadyTip()`;删除 `invalidatePreload/consumePreloadResult/dropPreloadData/stopMusicSession/onPlaybackSwitching`。
- **规模**:`PlayContainer` 1902 → **1553 行**(初版 3074,**-49%**,只剩视图/控制器/挂摘/弹幕视图/字幕轨道/弹层/生命周期),`PlaybackController` **2203 行**,`PlaybackViewBridge` 41 方法;顺手清掉页面 12 个失效 import(含 `MusicPlaybackService`/`PreloadManagerHolder`)。
- **验证**:`assembleDebug` + `testDebugUnitTest` BUILD SUCCESSFUL;`read_lints` 无诊断;未装机(测试机未连接)。

## 播放服务化 P2:引擎/宿主服务持有播放器,页面只挂摘视图(2026-09-14,用户"开始 p2")

目标(Spec §3-P2):进出详情页**不再重建播放器**。落地形态与 Spec 原案有一处**有意偏差**,下面写清。

- **fork 内核挂摘 API**(`player/.../VideoView.java`):新增 `attachContainerTo(ViewGroup host)`(把渲染容器
  `mPlayerContainer` 搬到外部宿主,插 index 0 —— 弹幕/覆盖层在其上)、`detachContainerFromHost()`(摘回自身)、
  `isContainerAttachedTo(host)`;三处都幂等,**不碰系统栏、不改 `mIsFullScreen`**,与页面内全屏(容器在 DecorView)互不干扰。
  渲染/封面(`artworkView`)/黑帧(`frameCover`)全在 `mPlayerContainer` 内,搬运即整体跟随。
- **`PlaybackEngine`(新,`player/PlaybackEngine.java`)**:进程级播放引擎 —— 持有 `MyVideoView`(主题化 application
  上下文,不持有 Activity)+ `PlaybackController`,自带进度落盘、状态监听(预载时机/音乐会话/弹幕启动)、
  `initFetch/initPreload`;实现 `attach(page, slot)`/`detach(page)`/`release()` 与 `PlaybackHostApi`;
  内部 `HeadlessView` 实现 `PlaybackViewBridge`:**播放器机械动作照做、UI 动作空操作**,因此退页面后
  音频/通知/预载照常维护(`isPageAlive()` 返回 true 的语义 = "存在可服务宿主",否则控制器会跳过 `updateMusicSession`)。
- **`PlaybackService`(新,宿主)**:托管引擎生命周期(任务移除/服务销毁即 `release()`);**本阶段不做 FGS**
  (通知仍归 `MusicPlaybackService`,避免双通知;单一前台服务 + 后台档位 = P3)。
- **为什么引擎不是 Service 本体(偏差与理由)**:页面对引擎的取用必须与页面构造**同帧同步**,否则要处理
  "服务未就绪 → 控制器事后替换 → 在途取流结果/观察者双投递"的初始化竞态(以及首播排队)。故 `PlaybackService.engine(ctx)`
  同步创建/返回引擎,`startService` 只做宿主。
- **页面接线(开关 `HawkConfig.PLAYBACK_SERVICE`,默认 false)**:
  ① `view_play_container.xml` 的 `MyVideoView` 改为运行时加入的 `surfaceSlot`;
  ② 旧路径(开关关)在槽位里 `new MyVideoView` + `bindPlayerToPage()`(进度管理器/状态监听抽成方法);
  ③ 服务模式:构造期同步取引擎(`scheduler = engine.controller()`、`mVideoView = engine.player()`、`engine.attach(this, surfaceSlot)`),
  跳过本页的 `initFetch/initPreload`(随引擎),`hostDestroy` 只 `engine.detach(this)`(**不 release、不停媒体会话**),
  `mVideoView = null`;
  ④ 控制器挂载/清空:`setVideoController(mController)`(页面)↔ `detach` 时引擎 `setVideoController(null)` + `setDanmuView(null)`(防服务持有页面 View);
  ⑤ manifest 注册 `.player.PlaybackService`;`PlaybackViewBridge` 增 `startDanmuIfReady()`(状态监听搬到引擎后仍需回落到页面弹幕);
  ⑥ 控制器 `onPlayerStateForPreload` 增 `view == null` 守卫、`initWebView` 增无页面(null WebView)守卫。
- **顺手拿到的收益**:开关打开后,确认纯音频(音乐)**离开详情页继续播**(引擎继续持有会话,通知由控制器 + `MusicPlaybackService` 维护)——
  Spec 里原属 P3 的"音乐跨页续播"在 P2 结构下已自然成立(仍待真机确认)。
- **规模**:`PlayContainer` 1541 → **1605 行**(+64,双路径接线),新增 `PlaybackEngine`(≈540 行)、`PlaybackService`(≈140 行);
  `assembleDebug` + `testDebugUnitTest` BUILD SUCCESSFUL、`read_lints` 无诊断;**未装机**(测试机未连接)。
- **待真机验收(开关打开)**:① 列表→详情→播放→返回→再进→换片,播放器实例创建次数 ≤1(日志 `echo-p2`);
  ② 12 次快速进出后 hprof 对比改造前基线(ExoPlayer×36 / 249 线程);③ attach/detach 无泄漏(服务不再持有页面 View);
  ④ 全屏/退出全屏与挂摘并存无黑屏;⑤ 音乐退页面续播、通知可控。

## 播放服务化 P3:通知/媒体会话并入宿主服务 + 同片接管(2026-09-14,用户"继续 p3")

- **`MusicPlaybackService` 的职责并入 `PlaybackService`**(后者同时是 P2 的引擎宿主):
  通知频道/媒体会话(`MediaSessionCompat` + `MediaStyle`)/通知栏动作(播放·暂停·上一集·下一集·拖动·停止)/封面(coil 异步 + 软位图)/wake+wifi 锁
  全部搬过去;**FGS 语义**由 `PlaybackService` 承担 —— `updateSession()` 走 `startForegroundService`(ACTION_UPDATE),`onStartCommand`
  里立刻 `startForeground`;`pendingStart/stopWhenStarted` 那套 **AOSP 竞态防护**(start 已派发但 onStartCommand 不交付 → ForegroundServiceDidNotStartInTimeException 杀进程)原样保留。
  **关键差别**:会话结束时**不 stopSelf、不释放引擎**(旧实现 stopSelf,新实现要托管引擎跨页面复用)—— 因此 `ACTION_UPDATE` 到达时若
  `mediaSession == null` 需**重建媒体会话**(原来靠 stopSelf 后 onCreate 重建),已在 `handleSessionIntent` 中补上。
- **`PlaybackNotification`(新,过渡门面)**:控制器的 `updateMusicSession/stopMusicSession` 只依赖它 ——
  `PlaybackService.isAlive()`(引擎在)→ 合并路径;否则(回滚/旧路径)→ `MusicPlaybackService`(一字未改)。P5 清理时门面与旧服务一起下线。
- **D6 同片接管落地**:`PlayContainer.setData(session)` 在服务模式下先判 `engine.session().playbackKey()` 与目标一致
  (源 key|片 id|线路|集索引)且播放器实例仍持有、非 ERROR/IDLE → **只同步配置/UI 并在暂停时 start(),不重新取流**(再进详情页接管续播);
  不同键 = 用户显式换片 → 走既有 `play()` 复用同一实例。服务模式下会话经 `engine.setData(session)` 落到引擎(引擎侧 `startSession`)。
- **manifest**:`.player.PlaybackService` 加 `foregroundServiceType="mediaPlayback"`(权限 `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_MEDIA_PLAYBACK`/`POST_NOTIFICATIONS` 之前已具备);`.player.MusicPlaybackService` 保留到 P5。
- **行为**:退页面音频续播 + 通知/锁由合并后的服务维持;影视退页面停播且无残留画面/声音(容器摘回引擎、无页面 View 引用);通知栏控制打到当前宿主(页面在=页面,页面没了=引擎)。
- **规模**:`PlaybackService` 121 → **564 行**(引擎托管 + 会话通知),`PlaybackNotification` 47 行,`PlayContainer` 1605 → 1630 行(D6 接管);
  控制器对 `MusicPlaybackService` 的直接引用清零。`assembleDebug` + `testDebugUnitTest` BUILD SUCCESSFUL、`read_lints` 无诊断;**未装机**。
- **待真机验收**:① 音乐退详情页回首页仍播、通知可控(播放/暂停/上一集/下一集/拖动);② 重进详情页**接管续播**(日志 `echo-p3 take over same playback`,不重新取流);
  ③ 影视退页面停播、无残留声画、通知仍可下拉;④ 换片(不同 playbackKey)仍复用同一播放器实例;⑤ 播放期 wake/wifi 锁持有、会话结束释放(日志 `echo-music wake/wifi lock`)。

## 播放服务化 P4:直播页接入同一引擎(2026-09-14,用户"继续 p4")

- **共用同一 `MyVideoView`**:`LivePlayActivity.initVideoView()` 服务模式下取 `PlaybackService.engine(this).player()`
  (旧路径仍 `MyVideoView(this)`),整块 View 进直播页的 Compose 树(`AndroidView(factory = { videoView })` 不变);
  直播自己的 `ComposeLiveController`/`LivePlayerManager`(默认解码器、自动换源、时移)**逻辑一行未改**。
- **引擎"直播人格"(新)**:`enterLive()` —— 若点播页面还在栈里先 `detach(page)`(把渲染容器收回,否则容器仍在旧页面槽位、直播拿到空壳);
  点播一律 `pause`(含"确认纯音频"场景,避免与直播双声);`setProgressManager(null)`(直播无进度);
  **`setExoDiskCacheEnabled(false)`**(边播边缓存是点播特性,直播 m3u8 片用它无意义且可能影响起播);
  `clearArtwork()/showVideoFrame()`(清上一部点播的残留海报/黑帧);`controller.stopMusicSession()`(撤通知、放锁)。
  `exitLive()` 反向:清直播控制器、还回进度管理器与磁盘缓存标记;直播页 `onDestroy` 只 `exitLive()`,**不 release**(实例留给点播复用)。
- **状态监听短路**:引擎的 `onPlayStateChanged` 在 `liveMode` 下**直接 return** —— 直播不进点播的预载排期/进度落盘/媒体会话/弹幕启动
  (否则会拿上一部点播的 vod 去更新通知与预载,或把直播当点播起播)。
- **点播→直播→点播**:`PlayContainer.hostResume()` 新增 `reattachIfOwnedByOther()` —— 回来时若发现自己不再是引擎的挂载页面,
  自动 `exitLive() + attach(this, surfaceSlot) + 重设控制器`(日志 `echo-p4 re-attach after live/other page`),避免"回来一片空白"。
- **已知边界(有意保留,R10)**:直播自身的 `release()` 调用(切台/换解码器/换源等 8 处)不动 —— 那些路径仍会重建**内核进程**;
  P4 的"内核实例不增长"指**不再多出第二个 MyVideoView/播放器对象**,且点播↔直播来回不再各自新建(点播侧实例一直被引擎持有)。
- **规模**:`PlaybackEngine` 536 → 584 行,`PlayContainer` 1630 → 1647 行,`LivePlayActivity` 2709 → 2726 行(3 处接线);
  `assembleDebug` + `testDebugUnitTest` BUILD SUCCESSFUL、`read_lints` 无诊断;**未装机**。
- **待真机验收**:① 点播→直播→点播,回点播看 `echo-p4 re-attach` 且无新内核创建,画面/控制正常;② 直播起播无黑帧/无残留海报;
  ③ 直播期间不出现点播通知与预载日志;④ 退出直播后点播进度继续落盘;⑤ 直播切台/换解码器/时移与改造前一致;⑥ 直播退后台暂停、回前台恢复。

## 播放服务化 P5:清理固化(2026-09-14,用户"开始 p5")

代码层的收尾:播放层**只剩一条路径**(引擎持有播放器 + 服务托管 + 页面挂摘),回滚开关移除。

- **删双路径**:`PlayContainer` 去掉 `serviceMode` 分支 —— 构造期固定 `engine = PlaybackService.engine(activity)` +
  `scheduler = engine.controller()`;播放器固定取 `engine.player()`(不再 `new MyVideoView`);删 `bindPlayerToPage()`
  (进度落盘/状态监听只在引擎侧,页面的 `initViewModel()` 一并删除);`hostDestroy` 固定 `engine.detach(this)`
  (删 `onHostDestroy/releaseFetch/release` 分支);`setData` 固定走 `engine.setData(session)` + D6 接管判定;
  `hostResume` 的重挂判定去掉开关判断。**`PlayContainer` 1647 → 1551 行**(相对初版 3074 行 **-50%**)。
- **删直播双路径**:`LivePlayActivity` 去掉 `serviceMode` —— 固定 `PlaybackService.engine(this).also { it.enterLive() }.player()`,
  `onDestroy` 固定 `exitLive()`(不 release)。
- **删门面与旧服务**:`PlaybackNotification.java`、`MusicPlaybackService.java` 删除(含 manifest 条目);
  控制器三处会话调用直连 `PlaybackService.isSupported/updateSession/stopSession`。
- **删开关**:`HawkConfig.PLAYBACK_SERVICE` 移除(回滚通道关闭)。
- **文档/注释同步**:`PlaybackService`/`PlaybackEngine`/`ImgUtil` 里对旧音乐服务的 `{@link}` 引用改为当前实现。
- **验证**:`assembleDebug` + `testDebugUnitTest` BUILD SUCCESSFUL、`read_lints` 无诊断、全库已无 `MusicPlaybackService`/`PlaybackNotification`/`PLAYBACK_SERVICE` 残留引用、单测无相关依赖;**未装机**。
- **🔍 静态回归审查(真机功能测试通过后的复核)**:审查方式 = 逐条比对"改造前页面在做什么 vs 现在谁在做" + 挂摘/所有权/空值时序 + 残留符号扫描。
  **发现并修复 4 处真实回归**:① 直播接管时点播会话残留(P3 归属守卫拒停 → 通知+wake/wifi 锁残留进直播、点通知会叠音)→ 新增
  `PlaybackService.forceStopSession(ctx)`;② 退页面后通知按钮归属旧页面(弱引用回收后按钮失效,且已暂停时无状态变化不会刷新)→
  `detach()` 末尾补 `controller.updateMusicSession()`;③ `attach()` 调 `exitLive()` 会清掉页面构造期刚设的控制器(返回点播页后丢失手势/按键)
  → 拆出 `exitLiveState()`(只切人格);④ 引擎已释放后 `setData` NPE → 加 `engine == null || isReleased()` 守卫。
  另删死代码 `PlaybackService.stop(Context)`。
  **已确认无回归**:`new MyVideoView(` 全库只剩引擎 1 处;弹幕再绑定(`DanmuLoadController` → `setDanmuView`)OK;
  `PreloadCoordinator.scheduleEvaluate(null)` 自带空守卫;引擎长期持有的 `SourceViewModel` 是自 new 的、不含 Context/Activity 引用(不泄漏页面);
  挂摘对称、页面/直播无残留双路径。
  待复测:上述 4 处场景 + hprof 实例数复测。
- **🐞 真机 bug 修复(2026-09-14,用户反馈:P4 引入,两个症状同一根因链)**
  - **症状**:① 看直播后退出回首页,直播声音还在;② 点历史里的点播卡片进详情页,播放的却是直播画面。
  - **根因 1(P4 主因)**:直播页 `onDestroy` 把改造前的 `mVideoView?.release()` 换成了 `exitLive()`,而 `exitLive()` 只切"人格"
    (还回进度管理器/磁盘缓存标记、清控制器)**没有停流** → 直播流在引擎里继续播 → 退到首页仍有声;点播页 attach 后容器里就是这段直播。
  - **根因 2(D6 接管误判,放大成"播错内容")**:直播不经过会话通道(LivePlayerManager 直接操作播放器),`engine.session()` 仍是上一次点播的会话;
    而 D6 判定只看"播放器实例在 + 状态非 ERROR/IDLE" → 打开**同一部/同一集**的点播卡片时被判成"同片接管",**直接跳过取流** → 点播页播着直播。
  - **修复**:① `exitLive()` 增加 `videoView.release()`(停流 + 释放内核,与改造前直播页销毁语义一致,直播无后台播放);
    ② `enterLive()` 置 `session = null`(直播不走会话通道,点播会话立刻失效);③ D6 判定加 `!engine.isLiveMode()` 守卫(纵深防御)。
  - **修正后的 P4 边界**(此前文档写错):直播页销毁会**释放内核**,故"点播→直播→点播"回点播会重建一次内核(与改造前相同);
    不再出现"退到首页还有直播声 / 点播页播直播"。P4 保留的收益仍是:直播与点播**共用同一个 MyVideoView 与播放器对象**(不再多出一整套播放器)、
    直播期点播侧(进度/预载/媒体会话/弹幕)短路、进入直播时自动收回点播页面与会话。
  - 待复测:直播退出后首页无声、点播卡片进入播放的是点播、点播→直播→点播来回 3 轮、直播切台/时移照旧。
- **注意(风险)**:P5 之后**没有开关可切回旧结构** —— 若真机发现 P2–P4 的问题,只能修引擎路径或回退提交。
  Spec §3-P5 的出口条件(**全量回归 + hprof 复测归档**:12 次详情页进出后 `DetailActivity/PlayContainer/ExoPlayer`
  实例数对比改造前 ExoPlayer×36 / 249 线程)**仍未执行**,待测试机接入后按各阶段 features.md 记录末尾清单一次性回归。

## 🔍 第二轮静态审查(直播 bug 修复后,2026-09-14)

按"接管判定必须校验内容归属 + 旧销毁动作逐条对照"的思路复查,**又发现并修复 1 处同类漏洞 + 1 处边缘遗漏**:

- **① D6 接管仍有"只看实例不看内容"的漏洞(修复)**:判定原用 `engine.session().playbackKey()`,而会话在 `setData` 时就登记了 ——
  **取流失败 / 被外部播放器接走**时播放器里其实还是上一部(或已释放),此时重进同一部会被判成"同片接管"→ 播错内容(与"点播页播直播"同源)。
  修:控制器新增 `startedPlaybackKey` —— 只有**地址真正交给播放器**(`startVideoPlayback` 里 `markContentStarted()`)才记录归属,
  `startSession` 时清空;D6 判定改比 `startedPlaybackKey`,并保留 `!isLiveMode()` + 实例在 + 非 ERROR/IDLE 三重条件。
- **② "直播暂停→去点播→回直播"人格不对(修复)**:点播页 attach 会把人格切回点播(`exitLiveState`),回直播后播放中的直播流会带着
  点播的进度管理器/边播缓存继续跑。修:引擎新增 `enterLiveState()`(只切人格,不暂停/不清控制器/不释放),直播页 `onResume` 调用。
- **③ 死代码清理**:`PlaybackService.isAlive()`、`PlaybackEngine.session()` getter(P5 与本次改动后无调用者)删除。
- **确认无需处理**:外挂播放器场景 —— `playerType >= 10` 时控制器先 `view.releasePlayer()`,`getMediaPlayer()` 为空 → D6 不会误判。

**已知遗漏(非缺陷,待补)**:
① Spec §4 真机全清单 + P5 的 hprof 复测(12 次进出实例数 vs 基线 ExoPlayer×36/249 线程)**未执行**;
② 点播→直播→点播:回点播后内核已释放(黑屏),需点播放才会取流续播(与改造前一致);
③ 通知栏"上一集/下一集"在无页面(引擎宿主)时无效(旧实现 owner 也是页面弱引用,行为一致);
④ 无页面时预载暂停(旧行为一致);⑤ 影视退页面保留暂停实例(P2 收益的代价,常驻一个内核)。

## ✅ 退出播放页语义改回"退页面即停"(2026-09-14,用户拍板 A + 保留实例)

- **决定**:退出播放页(返回上一级/首页)**一律停播 + 撤通知 + 释放 wake/wifi 锁**,影视与音乐都不再后台继续;
  但**保留播放器实例**不 release,下次进详情页直接复用(保住 P2 的"跨页不重建内核")。与 fongmi 默认语义一致
  (fongmi:页面 finish 且无 PiP/后台音频/媒体 client → 服务 `shutdown()` = 停播 + 撤通知 + 停服务)。
- **实现**(`PlaybackEngine.detach` 重写):① `pause()`;② **`saveCurrentProgress()`**;③ **`stopPlaybackKeepPlayer()`**
  (PREPARING/BUFFERING 时 pause 无效,刚点播放就退出会在后台自己播起来);④ `controller.stopPlaybackForPageExit()`
  (清会话标记 + 撤在途取流与三处超时 + 停会话);⑤ `PlaybackService.forceStopSession()` 兜底撤通知与锁;
  ⑥ 清页面控制器/弹幕引用 + 容器摘回 + 桥切回 headless。
- **两个"不 release 就会漏"的点(本次补齐)**:① 改造前进度靠 `release()` 落盘 → 现在必须显式 `saveCurrentProgress()`
  (fork 新增 public 包装),否则"退出→再进"丢失续播位置;② 在途取流/解析不停会在后台把这一集播起来。
- **fork 新增**:`VideoView.saveCurrentProgress()`(包装 protected saveProgress)与 `VideoView.stopPlaybackKeepPlayer()`
  (stop 内核但保留实例,下一次起播走 reusePlayer 的 reset 路径)。
- 编译/单测通过;**待真机**:退出详情页无声 + 通知消失;再进同一部从上次位置续播且不重建内核;
  刚点播放立刻退出不会在后台响;退后台(非退出页面)仍沿用原语义(纯音频继续、影视暂停)。

## ✅ 架构评审 + 第三/四/五轮静态审查(2026-09-14,五轮收敛;全程 assembleDebug + 单测 + lint 绿)

以下修复按时间序压缩归档(完整推演见 `.codebuddy/memory/2026-09-14.md`):

- **🐞「快速返回再重新进入」显示错乱(4 处修复)**:新页 onCreate/onResume 早于旧页 onStop/onDestroy 导致
  ①标题不下发(D6 接管不走 `play()`)→ `publishTitle()` + 接管分支补 `engine.setData/markContentStarted`;
  ②新控制器拿不到已播状态 → fork `setVideoController` 挂载时回灌 `setPlayState/setPlayerState` + `startProgress()`;
  ③旧页 hostDestroy 误撤新页在途取流(共享调度层)→ 归属守卫 `stillOwner` + `saveCurrentProgress()` 提到守卫前;
  ④(后续架构评审 3 项把该收尾挪到会话边界,`stillOwner` 补丁随之删除)。
- **核查结论(选集/换线未受改造影响)**:切集链路不经过 `setData`,与 D6 无关;借机修掉 fork `stopPlaybackKeepPlayer`
  的隐患 —— 已 PAUSED 再 stop 成 IDLE 会形成"IDLE+内核仍在",此后 `start()` 走 `initPlayer()` 新建内核覆盖旧的(泄漏)。
- **架构评审第 1 项(空闲 TTL 释放引擎)**:`detach()` 后 60s(`IDLE_RELEASE_DELAY_MS`)无人取用则 `release()` +
  `PlaybackService.onEngineReleased`(清静态引用,服务不 stopSelf);配套自愈 `PlayContainer.reviveEngineIfReleased()`
  收口到所有播放入口(`playViaScheduler`/`setData`/`replay`),`DanmuLoadController.setVideoView()` 弹幕换绑。
- **架构评审第 2 项(页面不再直接 release,所有权收口)**:新增 `PlaybackEngine.releasePlayer()`(释放内核、保留引擎、
  清 D6 依据);点播页 4 处 / 直播页 8 处 `videoView.release()` 全部改走它,两条桥同一入口。
- **架构评审第 3 项(在途收尾挪到会话边界)**:新增 `PlaybackController.cancelInFlight()`(三处超时+取流+解析),
  由 `startSession()`(会话边界)与 `stopPlaybackForPageExit()`(停播)触发;页面销毁只收页面私有资源 ——
  理由:页面销毁与新页面 attach 的先后由系统决定,拿页面销毁当收尾会误撤新页面的在途动作(嗅探 WebView 因此跨页复用)。
- **第三轮审查(5 高危+若干)**:①直播接管后旧点播页被回收会停掉直播(`detach` 加 `liveMode` 守卫,且 detach 须在
  liveMode 置位**之前**);②回直播前台缺收尾(`enterLiveState` 补 detach+撤会话);③`exitLive` 无归属校验(加 `!liveMode`
  前置守卫 —— 教训:守卫不得读自己将要写的标志位,初版写在 `exitLiveState` 之后恒真,复查抓回);④引擎自释放 × 新引擎
  竞态(`onEngineReleased` 不再 stopSelf);⑤D6 接管不重置会话状态(playbackStarted/triedLines/userPickedLine/
  m3u8ProxyUrl/switchStopPending → `startSession` 统一复位)。另:`stopPlaybackForPageExit` 补 `stopParse`、
  `onHostDestroy` 补三处超时收尾、`selectMyAudioTrack/selectMyVideoTrack/initSubtitleView` 判空、
  fork 加 `getVideoController()` + 直播页 `rebindLiveControllerIfNeeded()`。
- **第四轮审查(5 处迟到回调类)**:①引擎状态监听加 `released` 总守卫(防空通知+无人释放的锁);②`PlaybackService.updateSession`
  校验 `engine != null`;③revive 换引擎前先 `scheduler.stopPlaybackForPageExit()`(防旧内容播到新播放器);
  ④EventBus 重复解注册加 `isRegistered` 守卫;⑤`release()` 末尾补 `forceStopSession`(绕过归属守卫放锁)。
- **第五轮审查(用户"审查一下播放层有没有错误和遗漏";可达性复核)**:①**exitLive 顺序污染进度(高可达)**——
  `exitLiveState()` 先还回点播 progressManager,随后 `release()` 内部 `saveProgress` 把 mCurrentPosition(已被直播位置
  刷新)+ mProgressKey(残留的点播 key)写进点播进度缓存 → **release 提到 exitLiveState 之前**(此时进度管理器仍 null);
  ②**直播页回前台恢复点播内容**(enterLiveState 只切人格,PAUSED 被 resume 恢复)→ 返回 boolean + release 停死内核 +
  直播页 `replayCurrentChannelAfterTakeover()`;**可达性修正**:经查 `MainActivity` 为 standard 启动模式,直播页后台时
  launcher/通知/多任务都回栈顶(直播页本身),该路径当前不可达,修复属防御性(画中画/深链/ROM 差异时兑现);
  ③`play()` 裸取 NPE/IOOBE(历史恢复集号越界)→ `currentSeries()` clamp + 失败走换线兜底;④HeadlessView
  `startVideoPlayback` 补复用/释放防线(顺带修 PAUSED 下 setUrl+start 播旧 URL);⑤字幕选择判空。
- **方法论教训(静态推演的可达性)**:审查报出的 bug 必须回答"用户什么操作序列能走到这个状态"——launchMode/通知
  intent/多任务是入口层事实,只看播放层代码会高估触发概率;被质疑时反向重查时序,反而挖出真正可达的 exitLive 顺序问题。

## ✅ 真机回归通过,播放服务化收口(2026-09-14,用户"真机测试没问题,更新一下文档")

- **用户确认**:五轮审查修复后的构建真机测试无问题(进出详情页播放器不重建、退页面即停+再进续播、
  点播↔直播来回、切集/换线/换源、通知/锁/弹幕/字幕等日常路径走查通过)。
- **Spec 收口**(`avbox-playback-service-spec.md`):状态行与 §3 进度块改 **P0–P5 ✅**;§4 加结果行 ——
  §4-6(实例创建日志埋点)与 §4-7(hprof 量化复测,基线 ExoPlayer×36/249 线程)**未采集数据**,如需量化归档可后补。
- **遗留(可选)**:hprof 量化复测未做;`enterLiveState` 修复中的"重播当前频道"路径当前无真实入口(见上)。
- 此后播放层不再安排新的静态审查轮次;后续改动按普通回归对待。

## 导航栏三键区半透明 scrim 根因修复(2026-09-15,用户定位)

- **症状**:vivo Android 16 三键导航,首页/设置页(两种栏模式)导航栏区域白色实心条盖住栏后内容。
  第一轮修复(`BaseActivity.onCreate` 对 API 29+ `setNavigationBarContrastEnforced(false)`/`setStatusBarContrastEnforced(false)`)**无效**。
- **根因链**:targetSdk 37 强制 E2E → BaseActivity 关闭 contrast enforcement(正确)→ 但 `MainActivity.init()` 的
  `enableTransparentEdgeToEdge()`(`ui/theme/Theme.kt`)导航栏用 `SystemBarStyle.auto(TRANSPARENT,TRANSPARENT)`,
  其 nightMode 恒为 `MODE_NIGHT_AUTO`;androidx.activity 1.13.0 `EdgeToEdgeApi29/35.setUp()` 有
  `window.isNavigationBarContrastEnforced = (nightMode == MODE_NIGHT_AUTO)` ⇒ 被设回 true,scrim 回归。
  旧补救(`ApplyAppThemeBars` 只断言 `navigationBarColor`)在 API 35 上已废弃无效,scrim 完全由
  `isNavigationBarContrastEnforced` 控制。
- **修复**(`ui/theme/Theme.kt` 两处):①导航栏样式改 `SystemBarStyle.light(TRANSPARENT,TRANSPARENT)`
  (nightMode=MODE_NIGHT_NO ⇒ EdgeToEdge 自动设 contrastEnforced=false,根因修复;状态栏保持 auto 没问题);
  ②`ApplyAppThemeBars` SideEffect 补 `isNavigationBarContrastEnforced=false` + `isStatusBarContrastEnforced=false`
  (API 29+ 守卫)运行时兜底,防 config change 重开。
- **验证**:`:app:compileDebugKotlin` exit 0;真机待验(三键导航下 scrim 应消失)。约束已固化到 spec §6.6。

## 修复:详情/直播页深色主题导航键图标不可见(2026-09-15,scrim 修复的审查连带发现)

- **根因**:`light()` 导航栏样式使 EdgeToEdge(Api26/28/29/35 字节码确认)把
  `isAppearanceLightNavigationBars` 恒设 true(深图标,不再随系统);而 Detail/Live/ComposeVideoController
  都是 `AVBoxTheme(manageStatusBarIcons = false)`(ApplyAppThemeBars 不跑),其 `applyStatusBarAppearance`
  只断言状态栏 → 深色主题竖屏导航区深键位压深色 surfaceContainer 几乎不可见(全屏沉浸系统栏隐藏不受影响;
  修复前 auto 跟随系统深浅,系统深色时碰巧正确 → 属 scrim 修复引入的可见性回归)。
- **修法**:两页 `applyStatusBarAppearance` 补 `isAppearanceLightNavigationBars = !AppThemeState.isDark(系统night)`
  (MainActivity 同式;+AppThemeState import);断言时机沿用既有 4 时机(init/onResume/旋转落地/退出全屏),状态栏恒白语义不变。
- **审查方法留档**:androidx.activity 1.13.0 字节码核查脚本 `.codebuddy/tmp/dump_activity_edgetoedge*.ps1`
  (gradle 缓存抽 AAR → classes.jar → javap),产物在 `activity-1130/`;关键结论:SystemBarStyle
  nightMode 编码 auto=MODE_NIGHT_AUTO(0)/light=MODE_NIGHT_NO(1)/dark=MODE_NIGHT_YES(2),
  Api29/35 均 `setNavigationBarContrastEnforced(nightMode == MODE_NIGHT_AUTO)`,Api35 额外用
  ProtectionLayout 画 scrim(scrim 全 0 不挂)。
- **验证**:`:app:compileDebugKotlin` exit 0(KSP 期 SQLiteJDBCLoader "Failed to delete old native lib"
  为 Temp DLL 占用无害告警);lint 0。约束入 spec §6.6。

## 修复:直播页深色模式台名黑字 + 频道列表底部不沉浸(2026-09-15,用户报)

- **台名黑字**:`ChannelInfoSection` 的频道名 `Text`(titleLarge)未给 color —— 该处无 `Surface` 包裹,
  落到 M3 `LocalContentColor` 默认值 `Color.Black`,深色主题下黑字压深色 surfaceContainer 几乎不可见
  (台号徽标/Surface 内的文本有 onPrimaryContainer 兜底;列表行与 EPG 行均有显式色,唯此一处漏)。
  修=显式 `color = MaterialTheme.colorScheme.onSurface`。
- **底部不沉浸**:`ChannelListSection` 的 `LazyColumn` 挂 `.navigationBarsPadding()` → 列表被拦在导航栏
  上方,底部留一条页面背景色(用户感知"有 padding 不沉浸";scrim 修复后导航栏已透明,更显得是洞)。
  修=去掉该 modifier,导航栏 inset 移入 `contentPadding(bottom = WindowInsets.navigationBars + 24dp)`,
  列表延伸到导航栏后且末项仍可达。
- **排错记录**:`calculateBottomPadding()` 是 `PaddingValues` 接口的成员函数而非顶层扩展,
  import 它会 `UNRESOLVED_IMPORT`,删 import 即可(成员直接可用)。
- **验证**:`:app:compileDebugKotlin` exit 0。约束入 spec §4.5。

## 文件级重构 A 档:详情页 + 播放器面板拆分为多文件(2026-09-15,用户"开始 DetailActivity.kt 拆分"→"开始 PlayerSheets.kt 拆分",随后要求审查)

- **背景**:用户问"项目有多少上帝类与空 catch、是否该拆"。结论按**粒度**分三档 —— **A 档 = 文件级移动**(同一文件里的第 2/3 个类或 Composable 群挪到独立文件,零语义变化)/ **B 档 = 类内 Extract Method** / **C 档 = 职责级抽取**(需迁移共享状态,风险高)。本次只做 A 档,挑"一个文件里塞了几块"的两个目标;`ApiConfig`/`SourceViewModel`/`PlaybackController`/`PlayContainer`/`ComposeVideoController` 维持不动(C 档不主动做)。
- **第一轮:`DetailActivity.kt` 1977 行 → 3 文件**:`DetailActivity.kt`(244 行,仅 Activity + `SYSBAR_APPEARANCE_REASSERT_DELAY_MS`)/ `DetailViewModel.kt`(866 行,`class DetailViewModel` 整体)/ `DetailScreens.kt`(850 行,全部顶层 `@Composable` + `removeHtmlTag` + 两个正则,带 `@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)`)。**可行性关键**:该文件的 UI 段本来就是**顶层函数**且已显式传参(`fun DetailScreen(activity, vm)`),故属纯移动、**零可见性变更**。
- **第二轮:`player/ui/PlayerSheets.kt` 1187 行 → 5 文件**(按文件内 7 个分区横幅切):`PlayerSheets.kt`(285,面板公共骨架 8 个 `internal` 部件 + `findActivityOrNull`)/ `DanmuSheets.kt`(269,弹幕设置+搜索)/ `SubtitleSheets.kt`(397,字幕设置+搜索)/ `CastSheet.kt`(230)/ `EpisodeSheet.kt`(124)。**唯一代码改动 = 1 行可见性**:`private fun Context.findActivityOrNull()` → `internal`(面板跨文件调用);`DANMU_SPEEDS` 随弹幕分区整块搬走、仍 `private`;6 个 sheet 保持 `fun`(public)。
- **注释精简 68 处**(详情页 46 + 面板 22):原则 = 只解释"为什么"。先核实引用是否存活 —— 旧 XML(`dialog_danmu_setting.xml`/`input_search.xml`/`player_vod_control_view.xml` 等)与旧 View 对话框类(`DanmuSettingDialog`/`SearchSubtitleDialog` 等)经核查**全部已删除**,故"照搬 xxx"属死引用:保留尺寸/颜色/交互规格,删掉文件名与过程叙事(日期、"方案 A/B"、"旧逻辑"、"BugReview #24"、"补回丢失的应用逻辑")。保留的均为回归级说明:`isFullBox()`/`fullBox` 必须同一判定、LazyRow item key 撞车、`searchToken` 跨实例不复用、SAF mime 必须通配、预览态底栏底距与全屏入口同式。
- **验证(可复现)**:①代码完整性 = **多重集 + 顺序双重比对**(忽略空行/import/注释行):详情页 1522=1522、面板 987=987 **逐行一致**,差异仅 1 行可见性 + 每文件必备的 `package` 声明;②`:app:compileDebugKotlin`、`:app:testDebugUnitTest`、`:app:assembleDebug` 全 exit 0,lint 0;③`--rerun-tasks` 全量重编抓警告:**本次 7 个文件 0 警告**(7 条 warning 全来自既有文件);④拆分改的是 Kotlin **文件门面类名**(`DetailActivityKt` → `DetailScreensKt`),故专项核查:全仓 0 处按门面名调用、0 处 `@Jvm*` 注解、`.pro` 0 处引用变动类名、未启用 EventBus subscriber index、0 处通配 import(两个同名 `EpisodeSheet` 不互相遮蔽);⑤`avbox-mobile-ui-spec.md` 引用的是类名/函数名(`DetailActivity.isFullBox()`、`DetailScreen`、`PlayerSheets.SheetLoading`)⇒ 无过期引用。
- **遗留**:①**真机未验证**(详情页 + 播放器 6 面板的运行时路径);②现有 3 个单测与本次代码无关,"单测通过"只证明没破坏既有测试;③Compose `rememberSaveable` 自动键随函数位置变化 ⇒ 极端场景(升级安装后系统恢复旧实例态)下"简介展开态 / 选集面板选中分段"回默认值,同一次安装内无影响,判断不值得处理;④**未做**:`LivePlayActivity` 的 UI 段(L2192–,~600 行 15 个 Composable,是**类成员** `private fun LiveScreen(activity)` ⇒ 移动需放宽到 `internal`)、EPG 族抽 `LiveEpgParser` + 配套单测(纯函数、零 android 依赖,可测)。
- **本机工具留档**(`.codebuddy/` 不入库):`tools/split_detail.py`、`tools/split_playersheets.py`(机械切片;后者按分区横幅切 —— 3 行横幅里有 2 行都匹配 `^// -{5,}`,需按"行号相近即同组"聚合并 assert 组数防误切)、`tools/prune_imports.py`(删未使用 import;**注释精简后必须再跑一次**,否则注释里提到的类名会掩盖真实使用;`getValue`/`setValue` 等隐式名走白名单)、`tools/verify_split.py`(多重集 + `--ordered` 顺序比对,`--ordered` 需排除 `package`/`@file:` 行)、`tools/trim_comments_*.py`(成对替换 + 命中计数)。**坑**:PowerShell 用 `Select-Object -First N` 截 python 输出会因管道关闭让脚本 `OSError:22` 中途崩溃,须重定向到文件再读;比对工具报"大面积不一致"时先看两侧行数是否相等 —— 相等即错位(如 `@file:` 行换位)而非丢行。

## 文件级重构 A 档(第三轮):LivePlayActivity 的 Compose UI 段拆出 LiveScreens.kt(2026-09-15,用户"开始 LivePlayActivity.kt 的 UI 段拆分"→"精简注释,顺便审查是否有错误和遗漏以及是否引入了新回归,逻辑是否和原代码等价")

- **承接**:上一条(详情页 + 播放器面板)遗留④的前半 —— "UI 段是类成员、移动需放宽 internal"那一项。
- **结果**:`LivePlayActivity.kt` **2791 → 2146 行** + 新增 `LiveScreens.kt` **673 行**(**13 个 Composable**:LiveScreen / LivePasswordDialog / LiveReadyContent / PlayerArea / PlayerCornerButtons / PlayerCornerButton / TimeshiftBar / ChannelInfoSection / ChannelListSection / GroupHeaderRow / ChannelRow / EpgSheet / SettingsSheet)。
- **与前两轮的关键差异:UI 段是类成员**(`private fun LiveScreen(activity)`)而非顶层函数 ⇒ 必须①改顶层②放宽可见性。本次放宽 **39 处**:37 个 `private` 成员 → `internal`(`pageState`/`mVideoView`/`epgdata`/`tsPosition`/`playState`/`epgSheetVisible`…) + 2 个被 UI 引用的**嵌套类型** `PageState`/`ChannelInfoUi`(后者是编译器强制:internal 属性的类型不能是 private-in-class)。`LiveScreen` 置 `internal`(类主体 `setContent` 调用它),其余 12 个保持 `private`。代价是封装面放宽,将来做 C 档(状态收进 `LiveUiState`)可收回。
- **3 个非 UI 声明留在 Activity**:`LiveListRow`(嵌套 `private class` → 顶层 `internal class`;JVM 名 `LivePlayActivity$LiveListRow` → `LiveListRow`,已核无其他引用)、`buildChannelRows()`(`private`→`internal`)、`isPasswordConfirmedForUi()`(本就 public,当初就是为 UI 暴露 private 判定而写)。
- **两个必须配套的改动**(编译器逼出来的):新文件要带 ①`@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)`(用了 `ContainedLoadingIndicator`);②`import com.github.tvbox.osc.ui.activity.LivePlayActivity.PageState`(嵌套类型不自动可见,否则 `Unresolved reference 'PageState'`)。
- **注释精简 21 处**(LiveScreens 9 + LivePlayActivity 12;`tools/trim_comments_live.py` 自带"只改注释行"自证 —— 逐行 diff 校验每处改动行都是注释,否则拒绝写入):去日期、"方案 A/B"、已删旧物引用(`dialog_live_password.xml`、`旧 switchChannelSnapshotOverlay`);保留尺寸/时序/陷阱说明。**数量更正:UI 段是 13 个 Composable** —— 上一条写的"~15 个 Composable"实际是 15 个声明 − `buildChannelRows` − `isPasswordConfirmedForUi` = 13。
- **等价性验证(四件套)**:①**对齐式比对**(`verify_split.py --diff`,difflib)—— 有整块搬迁时**不能**用按位置比较(会把位移误报成大面积不一致);本次输出"全部差异 = 3 处按设计的搬迁(insert+delete 成对)+ 2 行头部声明,其余 **2265 行逐字对齐**";②多重集零丢失;③编译/单测/打包 exit 0;④lint 0(唯一警告是既有 `Slider` 弃用,位置由 `LivePlayActivity.kt:2430` 变 `LiveScreens.kt:339`,非新增)。
- **等价性的例外清单(仅 5 条)**:①缩进整体减 4(无语义);②39 处可见性放宽;③`LiveListRow` 提升为顶层(改 JVM 名);④新文件重复一份 `@file:OptIn`;⑤Kotlin **internal 函数名会被 JVM 混淆**(如 `buildChannelRows$app_debug`)—— 同模块编译期解析,无外部/反射调用者。
- **专项核查(全部为空/未变)**:`app/**.pro` 0 处引用 `LivePlayActivity/LiveScreens/LiveListRow/buildChannelRows/pageState/mVideoView`;Java 侧 0 处真实引用(仅 `PlaybackEngine.java`/`VideoView.java` 注释提到类名);manifest 仍是 `.ui.activity.LivePlayActivity`(类名/包名未变)。**无过度放宽**(39 个全部有据:37 个在新文件有 `activity.x` 用法,2 个类型为编译器强制)。**本轮无跨版本恢复态顾虑**:两文件都没有 `rememberSaveable`(详情页那轮有 2 处才提过该理论差异)。
- **实施过程踩的 3 个脚本 bug(都被编译器当轮抓住)**:①可见性替换只重建匹配前缀 ⇒ 整行剩余部分(`by mutableStateOf(...)` 委托)被丢掉,报一片 `Property must be initialized or be abstract`;正确写法是只替换匹配前缀 + 保留行尾。②CRLF 下 `lines[i+1] == ""` 判空恒假 ⇒ import 块取空 ⇒ 新文件全片 `Unresolved reference`,要用 `.strip() == ""`。③`range(cls_i, len(body))` 起点应为 0 ⇒ 尾部成员扫不到。**教训:重写型脚本要"改一点、编一次",别等收工再编。**
- **遗留**:①真机未验证(三批累计 13 个文件);②9 个新文件 untracked,需 `git add`;③仍未做:EPG 族抽 `LiveEpgParser` + 配套单测(纯函数、零 android 依赖,可测)。

## 文件级重构(第四轮):EPG/回看纯解析抽 `LiveEpgParser` + 项目首个逻辑族单测(2026-09-15,用户"开始 LivePlayActivity.kt 的 EPG 族拆分"→"审查是否有错误和遗漏以及是否引入了新回归,代码逻辑是否和原本相等"→"再审查一遍,必要时可对照 git 历史")

- **承接**:上一条(第三轮)遗留③。**结果**:`LivePlayActivity.kt` **2146 → 1789 行** + 新增 `ui/activity/LiveEpgParser.kt`(**351 行 / 无状态 object / 27 个 `internal` 函数**)+ 新增 `app/src/test/java/com/github/tvbox/osc/ui/activity/LiveEpgParserTest.kt`(**23 例**)。
- **切法:只迁纯函数,stateful 一律留下**。迁 **27 个** —— EPG URL/查询名族(`buildEpgUrl`/`buildEpgQueryNames`/`addEpgQueryName`/`encodeEpgParam`/`getFirstPartBeforeSpace`)、格式判定与响应解析(`isXmlEpgAddress`/`isXmlEpgResponse`/`parseJsonEpg`/`findJsonEpgArray`/`parseJsonEpgDate`/`isTemplateEpgAddress`)、频道名与标题(`normalizeEpgChannelName`/`cleanEpgTitle`/`isUnavailableEpgText`)、XMLTV(`parseXmlEpg`/`parseXmlTvDate`/`getDayStart`/`createXmlEpgInfo`)、时移回看(`getCatchupValue`/`hasCatchupSource`/`formatCatchupUrl`/`appendCatchupUrl`/`formatCatchupSource`/`formatCatchupToken`/`formatCatchupTime`/`getCatchupDurationSeconds`/`durationToString`);留 **19 个** stateful(`getEpg`/`showEpg`/`getConfiguredEpgAddress`/`hasEpgAddress`/`requestEpg`/`onEpgRequestFailure`/`onEpgRequestResponse`/`requestDefaultEpgOnFailure`/`requestNextEpgQueryName`/`isCurrentEpgRequest`/`loadEpgAfterChannelStarted`/`hasCurrentEpgCache`/`onEpgRowClicked`/`startCatchupReplay`/`backToLiveFromEpg`/`currentChannelHasCatchup`/`currentCatchup`/`canCurrentChannelCatchup`/`buildCatchupUrl`)。**判据是"读不读页面态/单例/KV",不是"名字像不像 EPG"** —— `buildCatchupUrl` 名字最像纯函数,实际读 `currentCatchup()`(页面态)故留下;`durationToString` 名字看不出跟 EPG 有关,实际是时移条专用故迁走。
- **常量归属**:`CATCHUP_TOKEN_PATTERN`/`CATCHUP_TAG_PATTERN` 两个 `Pattern` 随使用它们的函数整块迁走(若留在 Activity 会变成"没人用又删不掉");`DEFAULT_EPG_ADDRESS` 留在 Activity(只有 stateful 函数用)。
- **可见性**:27 个 `private` → `internal`,Activity 侧 **19 处**调用点加 `LiveEpgParser.` 前缀,新文件 0 处其它改动。**顺带记一条字节码事实**:Kotlin `internal` 编译后是 **`public final parseXmlEpg$AVBox_app_debug(...)`** —— JVM 可见性 public、**名字带模块名后缀**。含义 = ①Java 侧调用不到(本项目无 Java 调用者)②反射拿到的名字带后缀(无反射)③模块名一变混淆名就变(同模块统一编译无影响)④release 下 R8 还会再改名。⇒ **`internal` 是编译期约束、不是运行时封装**,以后放宽可见性不用担心 Java 侧误用。
- **等价性验证**:①多重集(丢/增行成对,恰 19 组前缀替换)②**函数体逐字比对**(被迁 27/27、留驻 19/19,仅差 19 处前缀)③`:app:compileDebugKotlin`/`:app:testDebugUnitTest`/`:app:assembleDebug` 全 exit 0 ④lint 0、本次文件 0 新警告。
- **单测(本轮真正的新增能力)**:23 例覆盖 EPG 日期解析(多格式回退)、频道名归一化(CCTV-N/后缀/`CCTV5+`)、标题清洗、时移 URL 与时长格式化、**XMLTV 整体解析**(按 display-name 匹配/窗口外与他台排除/index 连续/坏 XML 返空不抛)。**可测边界先说清**:`app/build.gradle.kts` 只有 `testImplementation(libs.junit)`,**无 Robolectric / Mockito / `isReturnDefaultValues`** ⇒ 被测代码一碰 `android.*` 或 `org.json` 即 "not mocked";可测面只限无 android 依赖的纯逻辑(EPG 族/搜索/KV 编解码),`playerCfgForPersist`(JSONObject)与 VM/Activity 全不可测。**两次"测试抓到我错"**:`buildEpgQueryNames` 末位会补原始频道名做兜底档、`parseXmlEpg` 不清洗标题水印 —— 都是我的断言假设错、代码是对的 ⇒ 现阶段单测的第一份收益是**把假设逼到台面上**,而非防回归。
- **审查两轮共发现 4 处问题(全部是我的产物/工具,非代码逻辑)**:①抽取在文件里留下 **7 处连续 3~25 行空行**(迁移前基线实测 0 处)+ 落点注释把 banner 开口行吃掉(出现孤立 `// ====`)→ 压回 1 行空行,**1843 → 1789 行**;②**验证脚本假阳性**:裸花括号匹配被 `startsWith("{")` 里的 `{` 带偏,把后续函数吞进 `onEpgRequestResponse` 的函数体,报"19 个里 1 个差异"(同一个坑 `tools/code_audit.py` 已踩过一次)→ 加 `mask()`(注释/字符串替空格、保留长度)后 19/19 通过;③**空行压缩正则 `(?:\r?\n[ \t]*){4,}` 会吞掉下一行缩进**(末尾 `[ \t]*` 贪婪吃前导空格)→ 被脚本自身断言拦下、未落盘,改 `\r?\n(?:[ \t]*\r?\n){3,}` 只吃空行本身;④`parseXmlEpg`(被迁函数里最长、66 行)**原本没有测试** → 补 4 例。
- **第二遍审查换独立信息源(用户"必要时可对照 git 历史"—— 这轮价值最大)**:①**git 基线事实**:仓库 26 提交,**HEAD 持有未拆分的原始快照**(LivePlayActivity 2790 行),四轮拆分全在工作区未提交 ⇒ 可拿 HEAD 当权威源、绕开"自产备份本身可信度"的证明循环;对照结果 = **被迁 27/27 与 HEAD 逐字一致**、留驻 18/19 一致,唯一差异是 `startCatchupReplay` 里 **1 行注释的日期被去掉**,而该行在 `.pre_epg`(第四轮之前)里就已如此 ⇒ **来自第 1~3 步的注释精简,非本轮**。②**字节码层 `javap`**:解析器恰好 27 个业务函数 + `INSTANCE` + 2 个 `Pattern` + `parseXmlEpg$lambda$0`(setEntityResolver 的 SAM) + `static{}`;`LivePlayActivity` 残留声明 **0**;`Companion` 里 Pattern 已消失、解析器里有;无 `LiveEpgParserKt.class` ⇒ 文件里只有那一个 object、无散落顶层代码。③**R8/release(新维度)**:`isMinifyEnabled=true` + `isShrinkResources=true`,而 `.pro` 无任何一条提到新类 ⇒ 跑 `:app:assembleRelease` → **BUILD SUCCESSFUL(4m26s)**,`AVBox_release.apk` 63.4MB(此前四轮只验过 debug;唯一告警是 quickjs 模块 namespace 重复,与本次无关)。④**调用图无死代码**:27 个全有生产调用点 —— 4 个"解析器外零引用"确认是原 `private` 辅助函数(`addEpgQueryName`←`buildEpgQueryNames`、`findJsonEpgArray`←`parseJsonEpg`、`createXmlEpgInfo`←`parseXmlEpg`、`formatCatchupToken`←`formatCatchupSource`),8 个"仅测试在解析器外引用"逐个确认解析器内有真实调用。
- **两处既有物(已用测试锁定现状,未改)**:①`parseXmlEpg` **不调** `cleanEpgTitle`(JSON 路径会调)⇒ 同一水印" --免费使用"在 XMLTV 源留在标题里 —— 原有行为、可能是刻意的;**要统一属行为变更,需真机验证**;②嵌套类型 `PageState`/`ChannelInfoUi` 第三轮只放宽成 `internal`、未外移(无重复声明,字节码只有嵌套版本),想再瘦身可挪去 `LiveScreens.kt`。
- **工具留档**(`.codebuddy/`,不入库):`tools/split_epg.py`(机械切片)、`tools/verify_epg_all.py`(被迁 + 留驻双侧逐字比对)、`tools/verify_epg_vs_git.py`(自行 `git show` 取原始字节对 HEAD 重建证明)、`tools/fix_epg_blanks.py`。**坑**:①裸花括号匹配必须先 `mask` 字符串/注释(`startsWith("{")` 这类字面量会带偏配平);②`javap -classpath` 要给**根目录**而非包路径;③PowerShell `Out-File` 会污染哈希比对(BOM/转行尾)⇒ 比字节要用 `git hash-object` 或 Python 原始字节。
- **未验**:①真机(四批累计 14 个文件)——**用户 2026-09-15 决定跳过**(静态手段已覆盖"行为等价":函数体逐字等价 + 编译/单测/字节码/R8;四轮唯一跨安装敏感点实测仅 2 处 `rememberSaveable` = `DetailScreens.kt:227` 简介展开态 / `:696` 选集选中分段,且只在"升级安装 + 系统恢复旧进程存档"同时成立时才有差异,最坏结果是回默认值,无崩溃/无数据影响);②新单测只在 debug 字节码下跑过,未按 §6.3 的 `testBuildType="release"` 姿势在 R8 后复验;③`parseXmlEpg` 标题水印的行为决策;④那 1 行注释的日期是否恢复。
- **方法论沉淀**:审查要换**独立信息源**而非自产证据(快照对照 / 字节码归属 / release 构建 / 调用图);**两个独立校验法的结论不一致时就是信号**(多重集说"零丢失"、逐字比对说"有差异"⇒ 必是工具错),此时先修工具再下结论。

## SP 残留清零:2 处独立 SharedPreferences 迁入 KV/MMKV + 死代码清理(2026-09-15,用户"本项目内还有几处遗留的sp持久化方式"→"全部迁移到mmkv,删除sp和死代码")

- **背景与复核**:按多种模式全仓复核(`SharedPref*` / `getSharedPreferences|getDefaultSharedPreferences|getPreferences|PreferenceManager|MODE_PRIVATE|MODE_MULTI_PROCESS` / `prefs.edit()|.commit()` / `androidx.preference|EditTextPreference|ListPreference|SwitchPreference|CheckBoxPreference` / `EncryptedSharedPreferences|MasterKey` / `Hawk|orhanobut` / `shared_prefs|SPUtils|PREFS`),确认真 SP 仅 2 处(与 `avbox-kv-mmkv-spec.md` §1.1、`avbox-mobile-ui-spec.md` §6.7 的记载一致);`player/`、`quickjs/`、`pyramid/` 与 `res/xml` 均 0 处;`PreferenceSettingsActivity/Page`、`SettingsPage` 只是"偏好设置"页命名(读写走 KV);`KVKeySpecTest` 里的 SharedPreferences 只是注释。
- **迁 1 — `util/thunder/Thunder.java`(迅雷下载库的伪造设备标识)**:SP 文件 `rand_thunder_id`(键 `imei`/`mac`,`commit()` 同步写,"读不到就生成再写回")整体删除;改为 `KV.get(HawkConfig.THUNDER_IMEI, "")` + `KV.put(...)`,判定由 `== null` 改 `TextUtils.isEmpty`(空串即"未生成");键常量新增在 `HawkConfig`(`THUNDER_IMEI`/`THUNDER_MAC`)。调用点(`Thunder.init()` ← `parse()`/`play()`)不变。
- **迁 2 — `util/AudioTrackMemory.java`(音轨记忆)**:SP 文件 `audio_track_prefs` 删除;键加 `audio_track_` 前缀替代原 SP 文件名、避免与全局 KV 键撞名 —— `audio_track_<progressKey>_exo_group`/`_exo_track`/`_ijk_track`;值恒为 int 且调用侧带 `-1` 默认值 ⇒ 按默认值类型即可还原,**无需登记 `KVKeySpec`**(动态键本来也无法逐键登记)。
- **顺带删除的死代码(用户要求)**:①`AudioTrackMemory` 的单例(`instance`/`getInstance(Context)`)、`prefs` 字段、`PREFS_NAME` 常量、`Context`/`SharedPreferences` import ⇒ 该类变成**无状态静态工具类**;②`Thunder` 的死 import `android.util.Log`(全文无 `Log.` 调用);③`IjkMediaPlayer`/`ExoPlayer` 各自的 `private static AudioTrackMemory memory` 字段与构造函数里的 `getInstance` 赋值(调用点改 `AudioTrackMemory.save`/`ijkLoad`/`exoLoad` 静态调用);④`KVKeySpecTest` 注释里最后一处"不读 SharedPreferences"字样。
- **验证**:`:app:assembleDebug` + `:app:testDebugUnitTest` 均 exit 0(仅既有 javac 泛型/弃用提示);`read_lints` 0 诊断;全仓 grep `SharedPref` 生产代码 0 处(只剩注释里的历史溯源说明)。改动面 6 文件(+48/−52)。
- **行为差异(仅两点,均无损)**:①迅雷设备标识不导入旧值、首次重新生成(伪造值;应用未发布无存量用户 —— 与 2026-09-13 Hawk→MMKV 同一决策,未用 `MMKV.importFromSharedPreferences`);②音轨记忆重置(下次播放回默认音轨,可再生成)。设备上遗留的 `shared_prefs/rand_thunder_id.xml`、`audio_track_prefs.xml` 不再被读写(未写清理代码,卸载重装即消失)。
- **未做**:真机验证(本机残留 xml 与回归未跑);~~未在 release/R8 后复验~~(见下条补验证)。
- **审查与补验证(2026-09-15,用户"审查一下是否有错误和遗漏以及是否引入了新回归")**:逐点核对 —— ①键映射逐方法对照(含 `save(2 参)` 与 `ijkLoad` 的 `_ijk`+`_track` 组合)一致,仅统一加前缀;②`KV.get(key,-1)` 的 int 拆箱无 null 路径(`KVCodec.decode` 三处返回均兜底 defaultValue),且"未登记键 + 调用侧具体默认值"契约有既有单测覆盖(`KVDecoderTest.primitiveRoundTrip` / `callerConcreteTypeWins_forDynamicKeysOutsideRegistry`);③`javap` 字节码核对 `AudioTrackMemory` = `public final class` + 私有构造 + 4 个静态方法 + 3 个常量,无残留 `prefs`/`instance`/`PREFS_NAME`;④全仓 `memory` 在两播放器 0 残留、`.pro` 0 处引用、`rand_thunder_id`/`audio_track_prefs` 仅存于注释与文档;⑤补跑 `:app:assembleRelease`(R8 + shrinkResources)**exit 0**,`AVBox_release.apk` 63.40MB ⇒ 上一条"未在 release/R8 后复验"已消除(本次未新增 `TypeToken` 匿名子类,dex 级 Signature 复验判定无需)。**有意的行为差异 3 条(均非回归)**:①旧值不迁移;②原子性 —— 旧 SP 单 `Editor` 批量提交 vs 新两次独立 `KV.put`,半写时 `exoLoad` 因要求 group/track 同时 ≥0 而回默认音轨(安全降级);③旧 `commit()`(同步)→ MMKV `encode`(同步),阻塞语义等价且少了 `apply` 的异步落盘窗口。**遗留**:真机验证(播放多音轨视频切轨→换集/重进看记忆;一次磁力解析看迅雷初始化)。

## 本地源导入:改为"直引原文件优先"(2026-09-16,用户指令"一起动手"实施 A+A+)

- **背景(与 FongMi 对照结论)**:本地源导入链路本身完整可用,差距只在两点 —— ①Android 11+ **全项目没有 `requestStorage` 调用点**(只有 `MainActivity` 的 `<R` 分支调),`isStorageGranted` 恒 false ⇒ 永远走"复制到外置缓存"分支,而配置里 `./x.jar`/`../lib/x.js` 这类同目录引用会被重写成"配置文件所在目录"的 http 前缀,复制件旁没有兄弟文件 ⇒ **404**;②`getPathFromUri` 只认 `primary:` 与 `raw:`,从选择器「下载」分类(`msf:<id>`)或「最近」(`document:<id>`)选中的文件都解析失败。
- **A(授权引导)**:`ConfigManageActivity.launchLocalConfig` = 未授权且未引导过 → 先 `KV.put(HawkConfig.ALL_FILES_PERMISSION_ASKED, true)` 再 `PermissionHelper.requestStorage`(回调里无论授予/拒绝都继续 `startLocalConfig`)。**只引导一次**的理由:MANAGE_EXTERNAL_STORAGE 是跳系统设置页的特殊权限,不记标记则每次点「从本地选择」都要跳一次设置页。`XXPermissions 28.3` 的 `OnPermissionCallback` 只有一个方法 `onPermissionResult(grantedList, deniedList)`(源码 `PermissionRequestMainLogic:382` 核实授予/拒绝都会回调)⇒ `{ _, _ -> }` 不会出现"拒绝后按钮像坏了";标记先写还有个副作用:万一回调因异常丢失,用户再点一次就直通(自愈)。
- **A+(解析扩面,对齐 FongMi `FileChooser.getDocumentPath`)**:`getDocumentPath` 按 provider 分派 —— externalstorage(`primary:` 挂外置存储根;`XXXX-XXXX:` 挂 `/storage/<卷名>`,即 SD 卡)、downloads(`raw:` 直取;`msf:`/数字 id 查 `MediaStore.Downloads`)、media(`document:`/image/video/audio 查 `MediaStore.Files` 等)、非 document 的 `content://` 走 DATA 列。纯字符串部分抽成 `internal` 纯函数(`toClanApi`/`externalStoragePath`/`isMediaStoreDownloadId`/`downloadNumericId`/`mediaDocId`)供单测;API 29+ 调用点均有 `Build.VERSION.SDK_INT` 守卫(minSdk 24)。
- **直引前置校验**:新增 `readablePath`(存在且可读,照 FongMi `getLocalFile` —— provider 给的 DATA 列可能指向已删除/已移动的文件,直引会让本地服务取不到文件、整个源报"拉取配置失败"),再加 `toClanApi` 的"必须在 `Environment.getExternalStorageDirectory()` 下"判定 ⇒ 任一不满足即回落复制。**顺带修掉旧行为**:旧代码解析出非主卷路径时直接 `return null`(弹"读取本地配置失败"),现在统一回落复制(等价且更稳)。
- **审查与验证(同日,用户"精简注释 + 审查是否遗留错误/新回归")**:注释精简为"结论 + 必要条件",代码行零改动(用"提取非注释行逐行核对"方式自证);`:app:compileDebugKotlin -q` + `:app:testDebugUnitTest` exit 0;单测 **6 类 66 例**(新增 `LocalConfigPathTest` 8 例:直引/复制分水岭、SD 卡、三种 provider 的 docId 形态);`read_lints` 0 诊断;三个源文件行尾各自保持一致(两个 CRLF 文件未被改成 LF)。**未真机验证**(待验:内部存储树 / 「下载」分类 / SD 卡三种入口 + 带同目录 jar 的配置)。
- **已知残留(与 FongMi 同级,非本次引入)**:①`msf:` 查 `MediaStore.Downloads`,若该行 `is_download=0` 可能查不到 → 回落复制;②网盘/第三方 DocumentsProvider 无真实路径 → 复制;③复制件仍在 `cache/config/`(清缓存即失效;文件名取 DISPLAY_NAME,同名会互相覆盖)。
- **有意的行为差异(非回归)**:本地源从"永远用缓存副本"变成"有权限时指向原文件" ⇒ 原文件被移动/删除后该源失效 —— 由 `ApiConfig` 的 `filesDir/<MD5(apiUrl)>` 缓存兜底(不硬崩),这是拿到"同目录 jar/js 可用"的代价,与 FongMi 一致。

## 本地源导入:补"复制件持久化"与「下载」入口兜底(2026-09-16,用户"开始吧")

- **先更正一条结论**:此前记的"复制件在 cache ⇒ 清缓存后本地源失效"**说重了**。`AppBootstrap.awaitLoadConfig()` 走的是 `loadConfig(false)`(每次启动先重新拉取),**拉取失败才回落** `filesDir/<MD5(apiUrl)>` 快照(`saveCache` 每次成功后写);且应用内 `FileUtils.clearCache()` 只清 cacheDir + 外置 cache(跳过 `config/`),不碰 filesDir 根。⇒ 系统清缓存后真实后果 = **静默回落到旧快照**(源仍能加载,但用户改本地 json 不生效且无提示),要重新导入才能恢复。
- **改动 3(复制件持久化)**:新增 `FileUtils.getExternalFilesPath()`(外置私有 files 目录,不可用回落内部 files);`copyUriToLocalConfig` 落点由 `getExternalCachePath()/config/` 改为 `getExternalFilesPath()/config/`;文件名由 `DISPLAY_NAME` 改为 `md5(uri)_原名`(`copyFileName()`,同一文件重复导入覆盖自己、不同来源同名互不干扰)。老副本留在 cache/config 里**仍可用、不迁移**;`FileUtils.EXTERNAL_CACHE_KEEP_DIR` 保留(只保护老副本,注释已更新)。
- **改动 1(「下载」入口兜底)**:`downloadPath` 改签名收 `uri`,`msf:` 两段尝试 —— 先查 `MediaStore.Downloads`(原行为),查不到(该行 `is_download=0`,即文件是拷进 Download 目录而非 DownloadManager 下载的)再按同一 id 查 `MediaStore.Files`,**且必须过 `sameFileName(path, DISPLAY_NAME)` 校验**才采纳(id 语义不符时会指向别的文件,直引过去比复制更糟;不过就复制)。`sameFileName` 为 `internal` 纯函数,单测覆盖。
- **改动 2(网盘/第三方 provider 无真实路径 ⇒ 复制)明确不做**:唯一彻底方案是 `ACTION_OPEN_DOCUMENT_TREE` 选目录 + 持久授权 + 解析配置里所有 `./`/`../` 引用把兄弟文件一起搬 —— 属独立功能,且网盘场景本就不会有同目录 jar,投入产出比不成立。
- **验证**:`:app:compileDebugKotlin -q` + `:app:testDebugUnitTest` exit 0;单测 **6 类 67 例**(`LocalConfigPathTest` 8→9,新增 `sameFileName` 交叉校验用例);`read_lints` 0 诊断;`prune_imports.py` 对两个 Kotlin 文件报 0 未使用 import。⚠️ 该脚本对 `FileUtils.java` 报"29 个未使用 import"属**既有误报**(以 `HEAD:FileUtils.java` 跑同一脚本得到同一份清单逐项核对确认;它只认 `Name.` 形式、认不出纯类型用法),**故绝不 `--apply` 在 Java 文件上跑**。
- **待真机验证**:内部存储树 / 「下载」分类 / SD 卡三入口;带同目录 jar 的配置;导入后看副本落在 `Android/data/<pkg>/files/config/`(旧副本仍在 `cache/config/`)。

## 本地源授权引导改判:未授权则每次点都跳(2026-09-16,用户"改吧")

- **背景(真机实测 + 用户追问)**:①未开权限导入 = 复制(`files/config/4b3f912d…_tvbox-test.json`;md5 前缀与新落点同时得到实证),且首页空白 —— 兄弟文件缺(把 `local-api.json` 手动塞进副本目录后首页立刻出内容,机理 100% 证实);②打开「所有文件访问」后再导入 = 直引(地址不含 `Android/data`)⇒ A/A+ 主路径真机通过;③但用户追问"关掉权限再添加会不会跳设置页"时查明:`ALL_FILES_PERMISSION_ASKED` 已写 true ⇒ **不会再跳、静默走复制**;并推演出更严重的一条 —— **撤销权限后已保存的直引源会失效**:本地服务在 App 进程内按原始路径读文件,无 MANAGE 时读 `/sdcard` 非媒体文件 EACCES → `/file/` 返回 500 → 拉取失败 → **静默回落 `filesDir/<MD5>` 旧快照**(界面无任何提示,表现为"改了本地 json 不生效")。
- **改动**:`ConfigManageActivity.launchLocalConfig` 去掉"问过就不再问"的标记判断 ⇒ **未授权时每次点「从本地选择」都跳系统设置页**;授权成功后继续打开选择器;用户拒绝/直接返回也照常打开选择器(回落复制分支)。**删除死键** `HawkConfig.ALL_FILES_PERMISSION_ASKED`(本日刚加、无存量兼容问题)及其 import;`avbox-kv-mmkv-spec.md` 键数 76→75,`avbox-mobile-ui-spec.md` §4.7 相应改写。
- **代价与取舍(用户已知悉)**:故意不给权限的用户每次点都会被跳一次 —— 导入本地源属低频操作,判定可接受;若日后嫌烦,可改为"连续拒绝两次后不再跳"或"自有对话框 + [去开启]/[仍然选择文件]"。
- **未覆盖(待办候选)**:撤销权限后"已保存的直引源静默回落旧快照"仍无提示 —— 可在拉取失败且 URL 为 `clan://` 且当前无存储权限时,把错误文案改成明确的"本地源文件不可读:请开启『所有文件访问』后重试"。

## 本地源:补齐"下载入口兜底"与"无权限时明确报错"(2026-09-16,用户"一起做了")

- **改动 1(拉取失败不再静默回落)**:`ApiConfig` 新增 `isLocalSourceUnreadable(apiUrl)`(`clan://`/`file://` 开头 且 `PermissionHelper.isStorageGranted` 为 false)+ 文案常量 `LOCAL_SOURCE_UNREADABLE_MSG`;`loadConfig` 与 `loadLiveConfig` 的 `error` 分支在最前面加判定 —— 命中时**直接 `callback.error(文案)`、不回落 `filesDir/<MD5>` 旧快照**。依据:撤销「所有文件访问」后,本地服务按原始路径读 `/sdcard` 非媒体文件 EACCES ⇒ `/file/` 500 ⇒ 拉取失败;旧行为静默用快照,用户"改了本地 json 不生效却毫无提示"。文案:`本地源文件读不到\n请开启「所有文件访问」后重试(或重新导入本地源)`,由 `BootErrorDialog` 连 [重试] 一起展示。**有意的取舍**:牺牲"快照兜底可继续用"换取问题显性化(与 `AppBootstrap.onApiUrlChanged` 的"不留旧数据"同一哲学);网络源完全不受影响。
- **改动 2(「下载」入口第三段兜底)**:`LocalConfigHelper.downloadPath` 的 `msf:` 分支由两段扩为三段 —— `MediaStore.Downloads` → `MediaStore.Files`(带 `sameFileName` 校验)→ **`downloadGuessPath(root, DISPLAY_NAME)` 拼 `<外置存储根>/Download/<名>`**,再经 `readablePath`(存在且可读)采纳。依据:前两段失败的常见原因是该行 `is_download=0`(文件是拷进 Download 目录而非下载器下载的)。安全:第三段只取 basename 且拒绝空/`.`/`..`,故不可能指到 Download 之外;任一校验不过即返回 null 走复制。`downloadGuessPath` 为 `internal` 纯函数,单测覆盖。
- **验证**:`:app:compileDebugKotlin -q` + `:app:testDebugUnitTest` exit 0 —— **6 类 68 例**(`LocalConfigPathTest` 9→10);`read_lints` 0 诊断。**未真机验证**:①关权限后切源应看到明确错误框而非静默旧内容;②从选择器「下载」分类导入应能直引(地址不含 `files/config/`)。
- **审查修正(同日"精简注释 + 审查")**:①`isLocalSourceUnreadable` 前缀判定由 `clan`/`file` **收窄为 `clan://localhost/`/`file://`** —— 原写法会把局域网形态 `clan://<ip>/…`(TVBox 服务地址)的拉取失败也报成"请开启所有文件访问",属误导(与本地存储权限无关);②`msf:` **第三段兜底改为仅"前两段都无结果"时启用** —— 原实现里"第二段有结果但同名校验不过"会 fall-through 去猜 `Download/<名>`,而 id 已证明指向别的文件时,猜出的同名路径可能引到**另一个同名文件**(直引错文件),现在该分支直接返回 null 走复制。**审查确认非问题**:`isLocalSourceUnreadable` 只在 fetch 失败的 `error` 回调里执行,而复制分支的副本在 App 外置私有目录(无需权限即可读)⇒ fetch 会成功、判定不生效,**不误伤"复制分支 + 无权限"**。**验证**:编译 + 6 类 68 例 exit 0;lint 0;prune(Kotlin)0;`ApiConfig` vs HEAD = **24 insertions / 0 deletions**(纯插入、无行尾翻动,LF 与原文件一致);新测试文件 CRLF 与同目录既有测试一致。

## 本地源授权"拒绝后不再弹选择器"(2026-09-16,用户选 B)

- **问题(用户提出)**:XXPermissions 的回调不区分授予/拒绝(我们此前用 `{ _, _ -> }` 忽略),于是**用户明确不授权也会自动弹出选择器**;这么选下来存的是复制地址,配置含同目录引用时首页空白(静默失败)—— 用户又要踩一次刚修过的坑。
- **改动**:`ConfigManageActivity.launchLocalConfig` 的回调改判 `granted.isNullOrEmpty()` —— 授予 ⇒ 照常 `startLocalConfig`;拒绝 ⇒ Toast「未开启『所有文件访问』,已取消导入(可再点一次重试)」并**取消本次导入**,不再弹选择器(新增 `android.widget.Toast` import)。按钮仍可再点(每次都判权限 ⇒ 会再跳设置页),故不丢重试入口。
- **放弃的方案 A(备查)**:拒绝后弹自有对话框「未开启『所有文件访问』:本地源将复制到应用目录,配置里同目录引用的 jar/js 可能不可用」+ `[去开启]`/`[仍然选择文件]` —— 优点是可救回"没找到开关就返回"的用户、并把复制分支的代价变成用户知情的选择;若日后觉得 Toast 太硬可回退到 A(需 Compose dialog + 回调多一个参数)。
- **验证**:`:app:compileDebugKotlin -q` + `:app:testDebugUnitTest` exit 0(6 类 68 例);`prune_imports` 0 未使用 import。**未真机验证**:拒绝后应只弹 Toast、不进选择器。注:用户同期自行精简了 `ConfigManageActivity` 的类/方法 KDoc,行为说明以本条目与 `avbox-mobile-ui-spec.md` §4.7 为准。

## 顶栏控件液态玻璃(2026-09-16,用户"顶部应用栏的控件跟随导航栏效果")

- **需求**:首页订阅源胶囊、搜索钮、搜索页搜索框/返回钮、二级页左上角返回箭头等**顶栏控件**接入底部悬浮导航栏那套液态玻璃;做法参考 `示例文件/legado-with-MD3-main`(`AppScaffold` 的 `topBarContentBackdrop` + `LocalTopBarBackdrop` + `TopBarLiquidGlass`)。
- **采样层**:新增 `ui/components/GlassTopBar.kt`(`LocalTopBarGlassBackdrop` + `Modifier.glassTopBarSurface(shape, fallbackColor)`);`AppTopBarScaffold` 把**内容区**额外录一份进 `rememberLayerBackdrop`,并**只在 `topBar` 槽内**下发采样层。三条硬约束:①顶栏必须在采样图层**之外**(否则 `drawBackdrop` 采样到自己);②`TopScrim` 移到图层外(否则玻璃采样到遮罩自身 = 一片纯色);③采样层不下发到内容区 ⇒ 同名控件(`ManageActionIcon` 既在顶栏又在卡片内)在卡片内自动回退实心,两处外观各自不变。
- **接入点**:`TopBarActionBox` / `ManageActionIcon` / `BarActionBox`(PartitionListActivity 私有那份)/ 首页胶囊与搜索钮 / 搜索页返回钮与 `SearchField`;关闭开关或 API<31 回退原实心 `surfaceBright`/`cardContainer`(外观与改造前一致)。材质与导航栏同源(vibrancy + blur + lens + highlight + innerShadow,共用设置里的模糊/扭曲滑条);**不额外 `clip(shape)`** —— 库内部已按 shape 裁剪(真机截图确认导航栏为胶囊、形状外不外溢),自己再加会把投影一起切掉。折射带取设置值但**限制在控件短边一半以内**(40dp 圆钮套 30dp 会整块拉花)。
- **真机取样定稿的两处尺寸修正**(库的 `Shadow.Default` radius=24dp、底色 `surfaceContainer@40%` 都是给 64dp 导航栏调的):①**投影收紧**为 `Shadow(radius=8.dp, 黑 8%/16%)` —— 24dp 投影比 40dp 控件本身还大,用户报"控件区域一层半透明灰",实测灰圈半径≈80px、比背景暗 4~9 级;②**底色提亮**为 `surfaceBright@45%` —— `surfaceContainer@40%` 与页面背景同色(实测控件内 237,236,244 vs 背景 238,237,245),纯色背景上"里面"看不出玻璃、只剩外圈灰。
- **按压形变(2026-09-16 用户"怎么没有按压形变的")**:复用导航栏那套 `InteractiveHighlight`(弹簧 `pressProgress` + 白色光斑,`inspectDragGestures` 只观测不消费事件、与 `clickable` 共存;松手与被 clickable 消费 up 两条路径都会把进度弹回 0),缩放走 `drawBackdrop` 的 `layerBlock` —— 库会用同一变换**反向补偿背景采样偏移**,放大时玻璃不与被模糊的画面脱开。增量 = 竖向 4dp、横向按最长边算:圆钮(40×40)两轴同增保持正圆,搜索框这类宽控件只增高、几乎不变宽,不会压到相邻的返回钮。
- **对话框内的圆钮(同日用户"这里有遗漏")**:`AddSubscribeDialog` 标题右上角「从本地选择」改走新入口 `Modifier.glassSurface(shape, fallbackColor)` —— 对话框是**独立 window**,拿不到顶栏采样层(`LocalTopBarGlassBackdrop` 只在 `topBar` 槽内下发),跨窗口采样不做(库按 `LayoutCoordinates` 换算偏移,跨 window 无可靠语义);该入口用库的 `emptyBackdrop()`:模糊层为空 ⇒ 不绘制,只保留底色 + 高光 + 内外阴影,即"身后没有可采样内容"时的玻璃观感。按钮身后只有对话框卡片本身(纯色),真采样与空采样视觉等价。
- **开关定稿(2026-09-16 用户"将导航栏和液态玻璃效果的开关解耦"→ 再"将总开关删掉吧")**:无总开关。分组「液态玻璃效果」下两个开关,**从上往下依次是「底部导航」「应用控件」**,各自控制自己的效果、默认都开、互不干涉;模糊/扭曲两档参数**共用**。KV 键 `HawkConfig.LIQUID_GLASS_NAVBAR` / `LIQUID_GLASS_CONTROLS`(默认 true,已登记 `KVKeySpec` 布尔区);**原总开关键 `liquid_glass_enabled` 与 `LiquidGlassConfig.enabled` / `setEnabled` 一并删除**(存量值不再读取、不做迁移,与 2026-09-15 删 `ALL_FILES_PERMISSION_ASKED` 同一处理)。门控:导航栏 = `navbarEnabled && API≥31`(`MainScreen`;`FloatingBottomBar` 内部 `isBlurEnabled` 也改判 `config.navbarEnabled`,关掉时退化成实心胶囊而非玻璃);顶栏控件与对话框圆钮 = `controlsEnabled && API≥31`(`AppTopBarScaffold` 的采样层门控 + `glassSurface` 内部)。头部卡的「重置」仍只重置模糊/扭曲两个参数。
- **代价**:内容区每帧多一次整页离屏录制(与导航栏那份并存),重内容页(首页 hero)滚动开销上升;若日后要省,可只录顶栏区域或退回纯遮罩。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL;装机(vivo V2425A)后用户确认正常(含按压形变与对话框圆钮)。设置页分组标题随之由「导航栏效果」→「导航栏与顶栏效果」→「液态玻璃效果」。

## 播放器音频焦点修复(2026-09-16,排查 issue:小米12Pro天玑版"播放卡+声音都卡+状态栏媒体卡片自动暂停/开始")

- **问题来源**:外部 issue(小米12Pro天玑版/澎湃OS2,天玑9000+)。两个内核都卡、IJK 软解稍好;控制中心媒体卡片在"播放/暂停"间反复跳。先静态审查(结论见 `.codebuddy/memory/2026-09-16.md`),随后真机取证 + 修复。
- **修复前实测(vivo V2425A;adb logcat + `dumpsys audio`)**:①单次会话内 `requestAudioFocus` **仅 1 次**(起播),其后 4 次 `abandonAudioFocus` 且**再无重新请求** —— 期间用户 3 次点播放(AudioTrack 3 条 start 可证),即"暂停过一次就永远不再申请焦点";②该状态下 App 前台在播(AudioTrack active / topResumedActivity=DetailActivity)而 **Audio Focus stack 为空**(无焦点播放:系统与他应用可随时抢占,我们收不到回调) —— 这正是"自动暂停/开始"的触发条件;③vivo 通知服务日志 1.5s 内两次 `onNotificationPosted(id=1001, channel=music_playback)` 且 `actionButton3` 由"播放"→"暂停" ⇒ 状态栏图标抖动 = `updateMusicSession`→`PlaybackService.updateSession` 每次状态变化重发通知(`setPlaybackState`+`startForeground`);④上一会话 14s 内 6 个 AudioTrack 重建(同 session)。
- **根因(dkplayer fork,Exo/IJK 共用)**:①`VideoView.startPlay()` 每次 `new AudioFocusHelper(this)` **覆盖引用**,旧实例不 abandon、不置空,仍被 AudioManager 持有并对同一 VideoView 响应焦点事件(多次"起播中断退出"后累积多 listener);②`AudioFocusHelper.abandonFocus()` 不复位 `mCurrentFocus`,而 `requestFocus()` 用 `mCurrentFocus == AUDIOFOCUS_GAIN` 作"已持有"早退判据(`mCurrentFocus` 又被系统回调改写)⇒ 暂停后再播放不再请求焦点;③`AUDIOFOCUS_GAIN` 分支无条件 `videoView.start()` ⇒ 播放中收到 GAIN 会重复派发 PLAYING 状态(媒体通知无谓重发);④`VideoView.release()` 的焦点清理被 `isInIdleState()` 守卫包住 ⇒ `stopPlaybackKeepPlayer()` 置 IDLE 后再 release,listener 残留。
- **改动(3 处,均在 `player/src/main/java/xyz/doikki/videoplayer/player/`)**:①`AudioFocusHelper`:新增 `mFocusGranted`(唯一"是否持有"判据),去重改用 `mLastFocusChange`;`abandonFocus()` 复位 `mFocusGranted/mStartRequested`;GAIN 分支加 `!videoView.isPlaying()` 守卫;②`VideoView.startPlay()`:`mAudioFocusHelper == null` 才新建(只建一次);③`VideoView.release()`:焦点清理移出 IDLE 守卫(任何状态都清)。
- **修复后实测(同机同操作序列)**:request/abandon **成对出现 8 组**,**每次恢复播放都重新请求焦点**(`W/AudioManager` deprecated 提示在 38.603/42.172 再次出现为旁证);播放期间持有焦点(38.604 request → 播放 38.580~40.932 → 40.924 abandon);退出页面后 abandon + 撤通知 + 栈清空。helper 实例在 `echo-p2 release player kernel`(切源/换源 release 内核)后重建属预期 —— 每个旧实例均带 abandon;偶发同一 helper 连续两次 abandon(`pause()` 与 `release()` 各自调用),幂等无害。`:app:compileDebugJavaWithJavac` exit 0、lint 0、`:app:installDebug` 装机通过。
- **未做/待观察**:①"状态变化即重发通知"仍在(若 issue 反馈图标仍跳,可加通知去重:title/subtitle/playing 均未变时跳过 `updateSessionState`+`startForeground`);②issue 机型(小米/澎湃)修复效果待反馈;③同机 `E/qdgralloc BufferManager::ReleaseBuffer ref_count = 1` 播放期 ~50 条/秒(与渲染帧率同步),疑厂商图形栈噪声,未处理。
- **审查补丁(同日,用户"审查一下有没有错误遗漏和引入新回归")**:逐项核对 —— ①四态字段(`mFocusGranted`/`mStartRequested`/`mPausedForLoss`/`mLastFocusChange`)的写入点/读取点全量追踪;②`videoView.release()` 的 5 个调用点(`enterLiveState`/`exitLive`/`releasePlayer`/引擎释放/`PlayContainer` 旧路径)均为"内核停死"语义,无条件清 helper 正确且下次 `startPlay()` 会新建;③全仓仅 `VideoView` + `AudioFocusHelper` 两处焦点逻辑(`player` 模块无 `setAudioAttributes` ⇒ EXO 无双重焦点管理),无漏改点。**发现并修复自身缺陷 1 处**:helper 复用后,上一会话若残留 `mPausedForLoss=true`(被打断暂停且未等到 GAIN 就退出/切源),新会话收到陈旧 GAIN 时会被 `videoView.start()` 自动拉起 —— 新增 `AudioFocusHelper.onNewPlayback()`(清 `mStartRequested/mPausedForLoss`),`VideoView.startPlay()` 每次起播调用。**确认非回归**:①GAIN 分支的 `!isPlaying()` 守卫不影响"被打断后自动恢复"(该链路因 `pause()` 内即 `abandonFocus()`、系统不会再派发 GAIN,本就极少触发;守卫仅在"已在播"时跳过,那种情况无需 start);②去重字段与原 `mCurrentFocus` 同构(赋值时机未变);③`mFocusGranted` 早退仅在确实持有时短路,系统 LOSS 会纠正;④线程安全性优于原实现(`mFocusGranted` 等仅主线程读写、`mLastFocusChange` 仅回调线程读写,原 `mCurrentFocus` 是跨线程读写);⑤`mEnableAudioFocus=false` / 无 `AudioManager` 路径判空齐全,`isPlaying()` 首查 `mMediaPlayer != null` 无 NPE 风险;⑥无反射/无 proguard keep 需求。**已知边界(未动,继承原实现)**:mute 时 `pause()` 不 `abandonFocus()`(焦点保留至 GAIN);同一 helper 偶发连续两次 abandon(`pause()`+`release()`,幂等);"状态变化即重发媒体通知"与 `MediaSessionCompat` 媒体键 receiver 缺失未处理。**验证**:`:app:compileDebugJavaWithJavac` exit 0、`:app:assembleRelease`(R8 + shrinkResources)exit 0、lint 0;加固版已 `installDebug` 装机,真机复测待做。
- **第二轮审查(同日,用户"再审查一下…不要编译 release")**:`git diff` 全量核对 = 仅预期改动(两文件均 LF 行尾、无翻动、无意外行);**修掉 1 处格式噪声** —— 重写 `AudioFocusHelper.java` 时给末尾补了换行(原文件末尾无换行),已还原(32→30 行变化)以保持最小 diff;**确认唯一子类** `MyVideoView` 只 `super` 透传(`start`/`pause`/`resume`/`release`)且未覆写 `startPlay`;`startPlay()` 调用面仅 `start()` 的 IDLE/START_ABORT 分支(`replay()` 不经过)⇒ `onNewPlayback()` 恰好落在"新会话"边界;**新识别 1 处极低概率竞态(与原实现同源、非新回归)**:系统线程派发的 GAIN 与主线程用户暂停几乎同时时,排队中的 GAIN 处理可能把 `mFocusGranted` 误置 true(后果 = 该次恢复不再请求焦点,下一次暂停/播放即自愈);彻底消除需代际计数、收益低,未做。按用户要求**未再跑 release 编译**。

## 本地源导入:复制分支补齐兄弟文件 + 去掉 isDocumentUri 前置闸门(2026-09-17,用户"本地源功能还是不正常")

- **现象(用户真机)**:已授予「所有文件访问」,把 `摸鱼本地`(放在 `/storage/emulated/0/影视备份/`,内含 `config.json` + `ext/2.json`、`ext/19.json`、`img/20.gif`、`jar/1.jar`)当本地源导入后,配置管理页里保存的地址是 `clan://localhost/Android/data/com.github.avbox.osc/files/config/…`(= 走了复制分支),json 旁边没有兄弟文件 ⇒ 配置里 `"./jar/1.jar;md5;…"`、`"./img/20.gif"`、`"./ext/2.json"` 全 404;用户手动把三个文件夹放进 `files/config/` 后就正常。用户拍板:保持"能直引就直引",但**复制分支必须把该带的文件带上**。
- **根因判定(静态,未拿到真机日志)**:直引条件其实都满足(主卷内 + 在 `Environment.getExternalStorageDirectory()` 之下 + 有 MANAGE),唯一能把它打成 null 的是 `getPathFromUri` 的前置闸门 —— `DocumentsContract.isDocumentUri()` 要经 PackageManager 确认该 authority 是文档 provider(`queryIntentContentProviders`,受 Android 11+ 包可见性影响),判 false 就掉进 `getDataColumn`(文档 provider 没有 DATA 列)⇒ 解析不出、错走复制。09-16 真机验证过的直引用例是 sdcard **根目录**下的文件(可能经 `最近`/`document:` 或 `raw:` 形态),没覆盖这条分支。
- **改动**:①`getPathFromUri` 移除 `isDocumentUri` 前置判断 —— content scheme 直接按 authority 解析,未知 provider 交给新增的 `unknownDocIdPath`(`raw:`/`msf:`/`primary:`/`document:|image:|video:|audio:` 前缀兜底),`DocumentsContract.getDocumentId` 加 try/catch。②复制分支新增"带兄弟文件":`relativeRefs(配置文本)` 提取 `"./x"` 引用(去 `;md5;`/`?`/`#` 尾巴、去重、拒空/目录/含 `..`),`copyRefs` 按原相对结构复制(单文件 ≤32MB、总量 ≤64MB);源目录读得到且有引用 ⇒ 落 `files/config/<md5(uri)>/<原名>` 独立子目录(`./x` 的重写基准正是该子目录,也不会跨源撞名),否则沿用 `<md5(uri)>_原名` 并用 Toast 说明缺口;`readBytes` 加 32MB 配置上限(不再无脑把选中的任意大文件写盘)。③诊断:`LOG.i("echo-local-src copy src=… api=… uri=…")`,前缀已登记进 `LOG.FILE_LOG_PREFIXES`(debug 包落 `files/preload_debug.log`)。
- **验证**:`:app:compileDebugKotlin` + `:app:compileDebugJavaWithJavac` + `:app:testDebugUnitTest` exit 0(**6 类 72 例**;`LocalConfigPathTest` 10→14,新增 4 例覆盖引用提取/去重/拒非法/忽略非相对值);`read_lints` 0;`skill/avbox-mobile-ui-spec.md` §4.7 已更新并与 `.codebuddy/skills/android/` 同步(顺带补齐该副本此前缺的一行,现哈希 MATCH)。未真机验证、未装机、未提交。
- **待真机验证**:重新导入 `摸鱼本地` ⇒ 期望地址变成 `clan://localhost/影视备份/摸鱼本地/config.json`(直引,原目录改动立刻生效)且源可用;若仍落复制,看 Toast「有 N 个同目录引用的文件未复制」+ `echo-local-src` 行(`src` 为空 = 解析仍未成功,再按 `uri` 的 authority 查)。
- **未做(备查)**:`../x` 引用不搬(会落到 `files/config/` 之外,跨源撞名);无权限/解析不出时的终极兜底仍是 `ACTION_OPEN_DOCUMENT_TREE` 目录授权(成本约 150~200 行);`RemoteServer` 的 `/file/` 仍只服务主卷根,SD 卡上的源只能靠复制分支。

## 本地源导入第二修:两段式(选 json → 目录授权补兄弟文件)(2026-09-17,用户回"还是有 4 个同目录引用的文件未复制,链接还是复制路径")

- **用户实测反馈(上一版构建)**:Toast「有 4 个同目录引用的文件未复制」+ 地址仍是 `clan://localhost/Android/data/<pkg>/files/config/…` ⇒ `getPathFromUri`/`readablePath` 那一环**仍然拿不到路径**(`src=null`),即:直引不成立,且 File API 也读不到源目录里的文件(仓库断言"是 `isDocumentUri` 误判"至少不是全部原因;也可能是「所有文件访问」实际没生效 / 该目录 File API 受限)。两条推论:①该环境下 File API 完全不可用,直引与"File API 搬文件"都不可能;②唯一可用通道是 **SAF 文档流**(json 本来就是这样读到并复制成功的)。
- **改动(两段式导入)**:①`handleLocalConfigResult` 改为返回 boolean(还需目录授权);`localConfigToApi` 拆成 `importLocalConfig`(返回 `api` + `dir` + `missingRefs`,有引用就落 `files/config/<md5(uri)>/<原名>`);②新增 `handleLocalSourceTreeResult` + `copyRefsFromTree`/`findDocumentInTree`/`findChildDocumentId`/`copyDocument`:第二段用 `OpenDocumentTree` 拿到目录授权后,按相对路径在树里逐级找文件(`jar/1.jar` = 先找 `jar` 目录再找 `1.jar`),用 `openInputStream` 搬进同一子目录(上限仍是单文件 32MB / 总量 64MB,失败删半成品);③`ConfigManageActivity` 增加 `sourceTreeLauncher` —— 第一段返回 true 就立刻 `launch(null)`;④`copyRefs` 改为返回"没搬到的列表"(树那一段要同一份清单);⑤埋点 `echo-local-src path granted=… src=… uri=…` / `import api=… missing=N` / `tree missing=N of=M`。
- **设计取舍**:第二段只在"配置里有 `./` 引用且 File API 搬不到"时出现(普通导入零感,直引路径完全不变);用户取消目录选择 → 仍交回地址并 Toast 说明缺口(不静默,但也不硬失败)。
- **验证**:`:app:compileDebugKotlin` + `:app:compileDebugJavaWithJavac` + `:app:testDebugUnitTest`(6 类 75 例)exit 0、`read_lints` 0;`skill/avbox-mobile-ui-spec.md` §4.7 改写两段式并与 `.codebuddy/skills/android/` 同步(SHA256 MATCH);debug APK 已重建(83.17MB,已核 dex 含 `findDocumentInTree`/`copyRefsFromTree`)。未装机、未真机验证、未提交。
- **待真机验证**:重新导入 ⇒ 选 json 后应出现 Toast + 目录选择器 ⇒ 选中 `摸鱼本地`(含 config.json 的那个文件夹,不是它的上级)⇒ 链接框应变成 `clan://localhost/Android/data/<pkg>/files/config/<md5>/config.json`,且 `<md5>/` 下应有 `jar/1.jar`、`img/20.gif`、`ext/2.json`、`ext/19.json`,源可用。若第二段也失败,读 `echo-local-src` 三行定位(`granted=false` = 权限没生效;`src=null` + `granted=true` = 解析/可读性另有原因)。

## 本地源导入第三修:目录授权 + 本地服务 SAF 直读 ⇒ 不复制也能直引(2026-09-17,用户"现在正常了,但这种方式好麻烦啊,为什么无法做到直接引用",并要求"参考 TV-fongmi")

- **FongMi 对照(实读 `示例文件/TV-fongmi`)**:①`FileChooser.resolveFileUri` = `getLocalFile`(isFile+canRead)成立就 `Uri.fromFile(原文件)` 直引,否则 `materialize` 复制到 `cache/chooser/<md5>/<名>`(同样只带单文件);②地址形态 `UrlUtil.toLocalUrl` = `file://<sdcard 相对路径>`(便携),需要走服务时 `UrlUtil.convert` 变成 `http://127.0.0.1:9978/file/…`;③`Local.resolveFile` **loopback 放行绝对路径**(`Path.local(path)` 直接读盘),故它连 `/sdcard` 之外的路径也能服务;④本地 jar 走 `Path.local()`(File API)直读;**⑤关键差异 = 它是"强制权限"路线**:`SettingFragment.setConfig` 见 url 以 `file` 开头就 `PermissionUtil.requestFile(...)`,授权回调里才 `load(config)` —— 没「所有文件访问」就不加载、也不静默复制。⇒ "FongMi 能直引"的真相是它把该权限当硬前提;它的服务同样只用 File API,没有任何 SAF 读取。
- **本机环境**:File API 读不到源目录(用户两轮实测:直引不成立 + 4 个引用文件搬不过来),而 SAF 文档流可用(json 就是这样读到的)。⇒ 要"不复制也直引",必须让**本地服务也能经 SAF 读原目录**。
- **实现**:①新增 `util/LocalSourceTree.kt`:`remember`(`takePersistableUriPermission` 取持久授权 + 记 KV)/`covers`(已有授权覆盖该路径就不再要授权)/`open`(把本地服务的"外置存储相对路径"经 `relativeUnder` 映射到某个授权目录,再逐级找子文档 `findDocument` + `openInputStream`);KV 新键 `local_source_trees`(`ArrayList<String>`,已登记 `KVKeySpec`);②`RemoteServer` 的 `/file/`:File API 不存在/读不到时回落 `LocalSourceTree.open`,用 SAF 文档流返回;③`importLocalConfig` 分三路 —— 应用读得到 ⇒ 原样直引;算得出地址但读不到 ⇒ 返回 `directPath`(缺的那次目录授权),**地址不变、不写任何副本**;算不出地址(第三方 provider / 非主卷)⇒ 复制路线(保留 `copyRefs`/`copyRefsFromTree`);④`handleLocalSourceTreeResult` 分两路收尾:直引路线只 `remember` + 校验所选目录确实覆盖该文件(`relativeUnder`),取消/选错就**取消导入**(不留半成品);复制路线仍搬引用文件;⑤去重:`LocalConfigHelper` 里重复的目录树遍历改为复用 `LocalSourceTree.findDocument`。
- **验证**:`:app:compileDebugKotlin` + `:app:compileDebugJavaWithJavac` + `:app:testDebugUnitTest`(**6 类 76 例**,`LocalConfigPathTest` 16:新增 `relativeUnder` 边界)exit 0;`read_lints` 0;debug APK 重建(83.17MB);`skill/avbox-mobile-ui-spec.md` §4.7 与 `skill/avbox-kv-mmkv-spec.md`(§1.2 表 + 键总量 75→76)更新,双副本已同步。未装机、未真机验证、未提交。
- **待真机验证**:重新导入 `摸鱼本地` ⇒ Toast「本地源要直引原目录,得给一次目录授权…」→ 选 `摸鱼本地`(或其上级 `影视备份`)⇒ 地址应为 `clan://localhost/影视备份/摸鱼本地/config.json`、**`files/config/` 下不产生副本**,源可用且改原 json 立刻生效;再次导入同一源不再要授权。日志:`echo-local-src path` / `import … direct=true` / `tree direct=<所选目录真实路径>`。
- **边界**:源里"浏览本机文件"这类**直接用 File API 读盘**的站点仍需「所有文件访问」(目录授权只覆盖经本地服务的读取);授权失效(卸载重装 / 系统里撤销)后该源会 404,报错文案引导重新导入。

## 本地源"解析不出路径"的真机定论 + FileProvider Uri 解析(2026-09-17,用户"安装到我的设备"时用 adb 抓到现场)

- **现场证据(装机后只读排查)**:①`cmd appops get com.github.avbox.osc MANAGE_EXTERNAL_STORAGE` = **`Uid mode: allow`** ⇒ 权限本来就是开的,"没授权"这条假设作废;②`run-as … cat files/preload_debug.log` 里的 `echo-local-src path granted=true src=null uri=…` 暴露真正的 Uri —— **`content://com.android.filemanager.fileprovider/extfiles/%E5%BD%B1…/config.json`**(vivo 文件管理器自己的 FileProvider,不是 DocumentsProvider):没有 docId、没有 DATA 列,所以我们按 authority/docId 的解析必然全落空 ⇒ 地址算不出 ⇒ 只能复制(这就是前两轮"直引不成立 + 4 个引用文件搬不过来"的共同根因,`isDocumentUri` 那条推测作废);③`ls -la /sdcard/影视备份/摸鱼本地/` = `drwxrws--- u0_a0 media_rw` / `-rw-rw---- u0_a0 media_rw config.json`(0770/0660、属主是别的 app),`run-as com.github.avbox.osc ls -la /sdcard/影视备份/摸鱼本地/` = **Permission denied**(而 shell 自己能读)⇒ **即使 MANAGE=allow,应用进程经 File API 读这个目录仍 EACCES** ⇒ 该目录的直引只能走 SAF(provider 侧读),正是第四轮做的"目录授权 + `RemoteServer` SAF 兜底";④`cmd package query-activities -a android.intent.action.OPEN_DOCUMENT_TREE` 只有 **com.android.documentsui** 处理 ⇒ 目录授权拿得到真 tree uri ✔(而 `OPEN_DOCUMENT` 有 filemanager/zarchiver 多候选,所以选文件会落到 vivo 的 FileProvider)。
- **改动**:`LocalConfigHelper` 新增 `providerPath(segments, storageRoot)` —— 认 `extfiles`/`external_files`/`external_storage` 段名(故意不收 `external`,否则 `content://media/external/…` 会被误判成路径),取其后各段拼外置存储根;接在 `getDocumentPath`/`getDataColumn` **之后**(不抢既有解析,错判了也被 `readablePath` 挡掉)。效果:vivo 文件管理器选的 config.json 从此**算得出原目录地址** ⇒ 从"复制路线"切到"目录授权直引路线"。
- **验证**:`:app:testDebugUnitTest`(6 类 **77 例**,`LocalConfigPathTest` 17:新增 `providerPath` 的 5 个断言含 media/external 误判防护)+ `:app:assembleDebug` exit 0;`adb install -r` 成功(数据保留,versionName 1.0.4,lastUpdateTime 02:29:14);已核 APK dex 含 `providerPath`/`local_source_trees`。spec §4.7 的解析清单补该形态并同步双副本。**待真机**:重新导入 `摸鱼本地` ⇒ 期望 Toast「本地源要直引原目录,得给一次目录授权…」→ 选 `摸鱼本地`/`影视备份` ⇒ 地址 `clan://localhost/影视备份/摸鱼本地/config.json`、不产生副本、源可用;日志应出现 `import … direct=true` + `tree direct=<所选目录真实路径>`。
- **备查的 adb 排查手法(以后先用这几条,别再靠推演)**:`cmd appops get <pkg> MANAGE_EXTERNAL_STORAGE` / `run-as <pkg> ls -la /sdcard/<dir>`(以应用身份验可读性)/ `ls -la` 看 mode+属主 / `cmd package query-activities -a android.intent.action.OPEN_DOCUMENT_TREE` / `run-as <pkg> cat files/preload_debug.log`。

## 审查:本地源直引改造全链(2026-09-17,用户"现在功能正常了,审查是否有错误和遗漏、以及是否引入新回归")

- **范围**:本会话全部未提交改动 —— `LocalConfigHelper.kt`(390 行改动)/新增 `LocalSourceTree.kt`/`RemoteServer.java`/`HawkConfig.java`/`KVKeySpec.java`/`LOG.java`/`ConfigManageActivity.kt`/`LocalConfigPathTest.kt` + 文档(另含本会话之外的 `SourceViewModel.java` ThreadLocal XStream,复核无问题;`SettingsPage.kt` 是用户自己改的一行文案)。
- **发现并已修 3 处**:①**会话级授权会静默失效** —— `LocalSourceTree` 原先用 `persistedUriPermissions` 硬过滤授权目录,若 ROM 未给持久化授权(异常被吞),导入会"成功"但本地服务永远读不到该目录 ⇒ 源静默 404。改为 `open()` 不按持久化过滤(会话级授权本次照样读,读不动的自然跳过),并新增 `isPersisted()` 供导入收尾提示「目录授权没能长期保留,重启后可能失效」;②**SAF 兜底无差别开给局域网** —— `/file/` 的授权兜底原对任何客户端生效,等于把"应用自己都读不到的目录"也暴露给局域网 9978;改为只服务回环请求(`isLocalRequest`),与 FongMi `Local.resolveFile` 的 `isLoopback` 同思路(应用取流本来就是 `127.0.0.1`:`ControlManager.getAddress(true)` → `getLoadAddress()` 已确认是回环);③**文档残留旧函数名** —— spec §4.7 仍写 `LocalConfigHelper.localConfigToApi`(已拆成 `importLocalConfig`)⇒ 改为新入口名。
- **已知取舍(非 bug,需知悉)**:①本地配置 **32MB 上限**:超限直接报「读取本地配置失败」,改前是无上限流式拷入应用目录(选错文件会真搬大文件)——保留上限更安全;②`/file/` 失败语义微调:文件存在但读不到时先试 SAF,都取不到才是 500「File … not found!」(原为 500 + EACCES 文案),消费方只看状态码,无影响;③授权目录列表只增不去重除外不清理(撤权后不再服务,源报"本地源文件读不到…可重新导入");④`../` 引用复制路线不搬(落点会越出副本目录、易跨源撞名),直引路线天然可用;裸相对路径(无 `./`)两路都不处理(`fixContentPath` 本就只重写 `./`/`../`);⑤副本名兜底改 basename(`safeFileName`),只影响新导入;⑥直引路线下"直接读盘"的爬虫站点(文件浏览类)仍需「所有文件访问」;⑦SD 卡等非主卷算不出 clan:// 地址 ⇒ 仍复制;第三方网盘 provider 映射不出路径 ⇒ 复制或取消。
- **回归面逐项核对**:`handleLocalConfigResult` 返回值只有 `ConfigManageActivity` 一个调用点(已同步);`localConfigToApi` 已删且无残留引用;`/file/` 的 `..` 防护、目录列举(`isDirectory` 先判 → `fileList`)语义不变;KV 新键已登记 `ArrayList<String>` 且写入类型一致(键总量 77 文档同步);`echo-local-src` 前缀仅 debug 落盘(`FILE_LOG = BuildConfig.DEBUG`);导入仍在 ActivityResult 主线程回调内,复制有 32MB/64MB 上限;ui 层零注释规则遵守(`ConfigManageActivity` 只加 1 个 launcher)。
- **验证**:`compileDebugKotlin` + `compileDebugJavaWithJavac` + `testDebugUnitTest`(**6 类 77 例**,`LocalConfigPathTest` 17)exit 0;`read_lints` 0;APK 重建并 `adb install -r` 到设备(V2425A-16,02:34:11,数据保留);spec 与 KV spec 双副本 SHA256 MATCH。
- **未做(留待拍板)**:直引源授权失效的**自愈**(检测到拉取失败时自动再要一次授权,目前靠报错文案引导重新导入);授权列表的清理入口(源被删时顺带清对应 tree)。

## 勘误 + 复核:本地源直引的真根因是 vivo FileProvider Uri,不是"MANAGE 读不到"(2026-09-17,用户"再审查一下有没有错误遗漏和引入新回归")

- **用真机日志闭环复核(装机后 `run-as … cat files/preload_debug.log`)**:`02:29:26 path granted=true src=/storage/emulated/0/影视备份/摸鱼本地/config.json` + `import api=clan://localhost/影视备份/摸鱼本地/config.json missing=0 direct=false`;02:32:55 同;且 `files/config/` **已不存在**(用户删掉了旧副本)⇒ 当前实际状态是**纯直引、零复制、连目录授权都没用到**(route 1 命中,`direct=false` 指"不缺授权")。⇒ **`providerPath`(解析 vivo 文件管理器 FileProvider 的 `…/extfiles/<相对路径>`)才是真正解决问题的那一处修复**,前几轮"加权限/要目录授权"的路径在用户机器上根本不会触发。
- **勘误(前面第 5 轮的结论写错了)**:当时用 `adb shell run-as com.github.avbox.osc ls -la /sdcard/影视备份/摸鱼本地/` 得到 Permission denied,就断言"即使 MANAGE=allow,应用进程读盘也 EACCES,只能靠 SAF"——**错**。该目录确实古怪(`drwxrws---`/`-rw-rw----`、属主 u0_a0:media_rw),但应用进程内 `File.isFile && canRead()` 为真、本地服务能正常读出 45KB 配置 ⇒ **run-as 的 ls 不能代表应用自身的可读性**(进程域/权限判定与实际应用进程不同)。⇒ 判断"应用读不读得到"必须用应用内埋点(`echo-local-src path … src=`),别拿 run-as 的 `ls` 下结论;此教训已写进 `MEMORY.md` 与 spec §4.7。
- **仍未实测的路径(诚实记录)**:①**目录授权直引(route 2:要一次 `OpenDocumentTree`、本地服务经 SAF 读原目录)在新链路上真机没走过** —— 02:27:55 那条 `tree missing=4 of=4`(旧构建、vivo FileProvider 导致地址算不出而走复制路线)无法区分"用户取消"与"目录树走不动";要验证需临时把「所有文件访问」设为 ignore 再导入,验完恢复(设备归用户,需其同意);②应用对该源目录的**列举**能力(`listFiles()`,只影响局域网文件列表视图)未验证(读单文件已由直引成功证明);
- **本轮改动回归面复核**:`covers()` 现在要求"已持久化"、`open()` 不再按持久化过滤(会话级授权本次可读)、`/file/` 的 SAF 兜底限回环 —— 三处都是边界收窄/放宽,route 1(用户当前路径)完全不经过这些代码 ⇒ 对已完成验证的直引链路零影响;`compileDebugKotlin`/`compileDebugJavaWithJavac`/`testDebugUnitTest`(6 类 77 例)/`read_lints` 全绿,APK 已重装(数据保留)。

## 液态玻璃性能优化第 2 档:backdrop 库 vendor + 高光/阴影层跳过重复 record(2026-09-17,已装机)

- **背景**:用户要求"不改变视觉效果"优化液态玻璃性能,先做评估里的**第 2 档**(离屏层"参数未变跳过 record")。前提:把 `io.github.kyant0:backdrop-android:2.0.1` vendor 成项目内模块(评估结论 = 最大收益的改动都在库内部,库为 Apache-2.0 可改)。
- **vendor 方式**:新增 `libs/backdrop`(AGP library + Kotlin + compose 插件,namespace `com.kyant.backdrop`),源码取自本地 gradle 缓存的 `backdrop-android-2.0.1-sources.jar`(commonMain + androidMain 合并,去掉全部 expect/actual);依赖 = `platform(compose BOM)` + compose ui / ui-graphics / foundation + `io.github.kyant0:shapes:1.2.1`(新增版本目录项 `kyant-shapes`,原 `backdrop` 条目删除)+ `androidx.annotation`;`org.jetbrains:annotations` 走 compileOnly(仅 `@Language` 注解);随附 `NOTICE` 声明 Apache-2.0 来源。`settings.gradle.kts` 加 `include(":libs:backdrop")`,`app` 依赖行改 `project(":libs:backdrop")`.
- **唯一的兼容性改动**:`internal/LayerRecorder.kt` 原用 context receivers(`context(node: DelegatableNode)`),改为显式 `node` 参数(避免依赖实验性编译选项),两处调用点(`LayerBackdropModifier` / `DrawBackdropModifier`)同步。
- **性能改动(3 处,视觉不变)**:`HighlightNode` / `ShadowNode` / `InnerShadowNode` 的离屏层 `record{}` 原为**每次 draw 无条件执行** → 改为"记录输入未变则跳过 record,只 `drawLayer`(alpha/blendMode 每帧照旧设置)"。判据 = `ShapeProvider.shape.createOutline(...)` 返回的 **Outline 引用**(ShapeProvider 内部按 shape/size/layoutDirection/density 缓存,引用变化即几何变化)+ 各自参数(width/blurRadius/style、radius/offset/color)。`InnerShadow` 的 draw 阶段仍需 outline 做 `clipOutline`,故 outline 每帧照算、只有 record 被跳过。
- **验证**:`:libs:backdrop:compileDebugKotlin` / `:app:compileDebugKotlin` / `:app:assembleDebug` 全 exit 0;`read_lints` 0;`:app:installDebug` → V2425A-16 Installed。**视觉与收益待真机**:①顶栏圆钮 / 底栏玻璃外观(高光描边、阴影、内阴影、按压态)应与优化前一致;②滚动 / 切页 / 长按按压的跟手程度。
- **未做(第 1 / 3 档)**:源层局部化(收益最大,需改 `LayerBackdropNode` 的记录区域)、底栏导出层合并。**未提交**。

## 液态玻璃性能优化第 1 档:源层局部化(backdrop 记录区域,2026-09-17,已装机)


- **背景**:第 2 档验证无问题后,用户要求做评估里的**第 1 档(收益最大)**:底栏/顶栏玻璃只需采样"底部/顶部带状区域 + 边距",不必整屏记录。
- **库侧(新能力)**:`Modifier.layerBackdrop(backdrop, recordBounds: ((Size) -> Rect?)? = null)` —— 记录前把 bounds 取整(floor left/top、ceil right/bottom)并 clamp 到节点范围;**无效/空 → 回退整屏并复位 `layer.topLeft = Zero`**;局部记录 = `recordLayer(size = 区域尺寸) { translate(-left,-top) { onDraw() } }` + `layer.topLeft = IntOffset(left, top)`(让采样坐标对齐,采样端 `LayerBackdrop.drawBackdrop` 的 `layerCoordinates.localPositionOf` 换算无需改动)。`LayerBackdropElement` 的 equals/hashCode/inspector 同步带上 `recordBounds`。
- **项目侧**:①`MainScreen` 底栏源层 band = `WindowInsets.navigationBars.getBottom()` + `FLOATING_NAV_OVERLAY_DP`(76dp) + `GLASS_BACKDROP_BAND_MARGIN_DP`(64dp,新常量定义在 `ui/components/GlassTopBar.kt`);②`EdgeToEdgeTopBar` 顶栏源层 band = `padding.calculateTopPadding()`(状态栏+顶栏) + 同常量;两处 `recordBounds` lambda 都 `remember(density, bandHeight)`(避免每次重组 invalidate 源层);顺带删掉 `MainScreen` if 块里重复的 `val density` 声明(复用外层)。
- **为什么留 64dp 边距**:玻璃层从源层采样的范围 = 玻璃矩形 ± 玻璃层自身 padding(当前参数组合下最终 padding = 0)+ 按压缩放外扩(底栏第一层 scale 最多约 +16dp/2)+ lens 折射采出(distortion 30dp);超出 band 会落 Clamp 边缘 ⇒ 视觉变化,故宁可留大(收益仍 ≈ -80% 面积)。
- **调用面核对(局部化前提)**:所有 `glassTopBarSurface` / `TopBarActionBox` 调用点(HomePage 订阅源胶囊与搜索钮、HistoryPage 管理钮、ConfigManagePage/ThemeSettingsPage/PreloadSettingsPage/PreferenceSettingsPage/PlaySettingsPage 的返回钮、SearchActivity 返回钮与搜索框、PartitionListActivity 的 `BarActionBox`)都在 `AppTopBarScaffold` 的 title/navigationIcon/actions 槽内 ⇒ 必落在顶部 band;`ConfigManagePage` 的 `glassSurface(emptyBackdrop)` 无采样源不受影响;底栏 band 覆盖浮底栏(含 press scale)。
- **验证**:`:libs:backdrop` / `:app:compileDebugKotlin` / `:app:assembleDebug` / `:app:installDebug`(V2425A-16)全过;`read_lints` 0;真机静态截图两态(首页加载中 / 已加载出海报)顶栏与底栏玻璃外观正常、无错位/空白。**未做**:滚动跟随与按压动画的动态验证(留给用户实测)、第 3 档(导出层合并)。**未提交**。

## 偏好设置页:新增「禁用导航动画」+ 三项独立成组(2026-09-17)

- **需求(用户)**:①新增开关「禁用导航动画」(副标题「开启后将禁用底部导航的侧滑动画」),开启后禁用底部 `HorizontalPager` 的侧滑手势;②把「无痕模式」「禁用手势控制」提取出来,与新增项组成一个独立分组卡片,顺序 = 无痕模式 → 禁用手势控制 → 禁用导航动画。
- **改动 5 文件**:①`util/HawkConfig.java` 新键 `NAV_ANIMATION_DISABLED = "nav_animation_disabled"`(带 javadoc);②`util/kv/KVKeySpec.java` 布尔区登记(键总量 77→78);③`ui/page/SettingsPage.kt` 的 `SettingsState` 加 `navAnimationDisabled` + `loadState()` 读取;④`ui/page/PreferenceSettingsPage.kt` 原单组拆三组:组1 = 自动换线(FIRST) + M3U8 净化(LAST),组2 = 三项独立分组(FIRST/MIDDLE/LAST),组3 = 弹幕开关(FIRST) 起其余项,组间 `Spacer(28.dp)`;⑤`ui/page/MainScreen.kt`:`HorizontalPager(userScrollEnabled = navScrollEnabled)`,`navScrollEnabled` = `remember { mutableStateOf(!KV.get(...)) }` + `LifecycleEventEffect(ON_RESUME)` 重读(从偏好设置页返回即生效)。
- **语义边界(写进 spec §4.9)**:只禁"手指左右滑动手势";**点底栏 tab 仍可切换**(`animateScrollToPage` 过渡动画保留);下拉刷新/卡片点击/长按不受影响(`userScrollEnabled=false` 只吃掉 pager 自身的横向拖动)。若日后要"点也直接跳",改的是 `animateScrollToPage` → `scrollToPage`,与前者是两件事。
- **验证**:`:app:compileDebugKotlin` + `:app:compileDebugJavaWithJavac` exit 0;装机后待真机确认(开启 → 首页左右滑不动、点底栏仍能切;关闭 → 恢复)。**未提交**。
- **用户补充(同日,"有遗漏,加上禁用点击 tab 后的滑动动画")**:`MainScreen` 的 `navScrollEnabled` 更名 `navAnimationEnabled`(语义 = 导航动画总开关),**两处点击入口都改**:①玻璃 `FloatingBottomBar.onTabSelected`、②M3 `NavigationBarItem.onClick` —— 开启时 `pagerState.scrollToPage(index)`(直接跳、无过渡),关闭时 `animateScrollToPage`。⚠️ **两处缺一不可**(两种底栏模式各一处,只改一处会出现"切换底栏模式后开关失效")。验证:编译 exit 0,已装机待真机确认(开启后点底栏 tab 应瞬切、无滑动过渡)。
- **布局定稿(同日,用户"总共就两个分组卡片")**:三兄弟组从中间移到末尾、三组并为**两组** —— 组1 = 自动换线 → M3U8 净化 → 弹幕开关 → 弹幕 API → 长按倍速 → 缓冲时间 → 搜索线程(卡位 FIRST/MIDDLE×5/LAST);组2 = 无痕模式 → 禁用手势控制 → 禁用导航动画(FIRST/MIDDLE/LAST)。实现 = 重排 `PreferenceSettingsPage.kt`(仅分组与卡位变化,各行内容零改动)。

## 配置管理页空态图标补齐(2026-09-17)

- **需求(用户)**:配置管理页在没有添加源时,页面中间也要显示空状态图标 —— 即首页引导态 / 历史 / 收藏页那个 `R.drawable.ic_empty_record`。
- **改动(`ui/page/ConfigManagePage.kt`,2 处)**:①点播段空态 `LoadStateBox` 补 `emptyIconRes = R.drawable.ic_empty_record`(原先只有「暂无订阅」文字);②直播段原设计"永不为空"(首项 = 合成的「跟随点播源」卡)⇒ 直播源为空时在跟随卡**下方**追加 `item(key = "Live#empty")`:`LoadStateBox`「暂无直播源」+ 同款图标,`fillMaxWidth().height(220.dp)` 居中 —— **追加而不是替换**,保住"未配直播源时仍可开启跟随"的能力。
- **验证**:`compileDebugKotlin` exit 0;待真机确认(删空点播源 → 页面中间出现图标 + 「暂无订阅」;直播段同理)。**未提交**。

## 音乐播放页底部胶囊改版(2026-09-19)

- **需求(用户)**:①底部三个控件"设计得不好、按压也没有动效",参照 `示例文件/PixelPlayer-master` 做出一模一样的效果;②未选中态也要是"子弹形"(不要 8dp 方角);③配色不要照抄参考项目 —— 胶囊 `surfaceContainer`、段容器 `surfaceBright`;④宽度改成"和三个播放控件等宽或偏小一点点";⑤在收藏与选集之间插入一个投屏控件;⑥内部卡片改成**分段式圆角**(最左段朝外两侧大圆角、最右段朝外两侧大圆角、中间全小圆角)。
- **改动**:`ui/music/MusicPlayerScreen.kt` —— 重写 `MusicBottomActions` + `BottomActionItem`(按压 `scale 0.94` + `spring(0.45, StiffnessMediumLow)` + 涟漪;选中态 `animateColorAsState` 250ms 渐变到 `primary`/`tertiary` 实心 + 对应 on 色图标)、新增 `segmentShape(index, count)`;播放控件尺寸抽成 `SkipButtonSize`/`PlayButtonWidth`/`PlayButtonHeight`/`SkipToPlayGap`/`PlayToSkipGap` 常量并派生 `PlaybackControlsWidth`(254dp),**胶囊与播放控件共用同一组常量**,改一边必须核对另一边。`MusicPlayerState.kt` 加 `castSheet`;`MusicPlayerActivity.kt` 加 `showCast()` + `onCast` 回调;投屏面板复用 `CastSheet`(自带 Dialog,挂在音乐页自己的 Compose 树里)。选集 sheet 加 `rememberLazyListState()` + 弹出即 `scrollToItem(queueIndex)`(无动画;状态在 `if (queueVisible)` 内 `remember`,关闭即销毁,不残留滚动位置)。
- **关键决策**:①段形**不做**"未选中 8dp → 选中胶囊"的半径动画 —— 用户要求未选中也是子弹形,故统一分段式圆角;②"大圆角"用 `CornerSize(percent = 50)` 而不是写死 dp,段高变化时自动跟随(段高 50dp → 25dp,与 66dp 容器内缩 8dp 后同心);③按压手感对齐项目已有的 `CapsuleSegmentedButton`(0.94 + 弹簧),不引入第三套手感。
- **验证**:`:app:compileDebugKotlin` / `:app:installDebug`(V2425A-16)通过;真机截图确认(用户反馈后连改四轮:方角 → 子弹形、配色、宽度、投屏段、分段圆角)。**未提交**。

## 详情页新增「进入音乐播放器」入口(2026-09-19)

- **需求(用户)**:①竖屏详情页投屏控件旁增加一个控件,图标取 `.tubiao/进入音乐播放器.svg`,点击进音乐播放页;②与投屏控件调换位置(顺序 = 音乐播放器 → 投屏 → 收藏);③**影视内容**从音乐页返回时不要退到上级页面,要留在竖屏详情页。
- **改动**:①新 `res/drawable/ic_detail_music_player.xml`(转换规则同 `ic_detail_cast.xml`/`ic_music_queue.xml`:`viewBox="0 -960 960 960"` → viewport 960×960 + `<group android:translateY="960">`,fillColor 白、由 Compose tint);②`ui/activity/DetailScreens.kt` 标题行插 `IconButton`;③`ui/activity/DetailActivity.kt`:`openMusicPlayer()`(详情未解析完 Toast / 会话未建先 `playCurrent()` / 再交接)、`isAudioContent()`(从 `musicPlaybackDetected()` 里抽出的公共判定)、`handOffToMusicPlayer()` 按 `isAudioContent()` 分流是否 `finish()`、`pendingEpisodeSync` + `syncEpisodeAfterMusicPage()`;④`MusicPlayerActivity.start(context, historySourceKey)`;⑤`ui/player/PlayContainer.java` 的 `handedOver` 复位。
- **两个必须记住的连带点**(已写进 spec §4.4/§6.1):①影视内容保留详情页 ⇒ 本页会重新接管引擎,`handedOver` 必须在 `reattachIfOwnedByOther()` **和** `reviveEngineIfReleased()` 两条路径都复位,否则真正退出详情页时 `hostDestroy` 漏 `detach`、声音不停;②音乐页改的是 `session.vod`(预览副本)、详情页 UI 读 `vm.vodInfo`,是两个对象 ⇒ 不把 `playFlag/playIndex` 同步回来,选集高亮停在交接那一集、**点播放还会跳回那一集**(用标记只在交接后同步一次,避免"从历史进详情页"被残留会话覆盖)。
- **验证**:编译 + 装机通过;`stopPlaybackKeepPlayer()` 遇 `STATE_PAUSED` 直接 return ⇒ 退出音乐页后播放器停在 PAUSED 而非 IDLE,返回详情页时 `reattachIfOwnedByOther` 的 `state != IDLE` 成立、预览浮层能正常 rebind(这条是事先排查的,避免返回后黑框)。**未提交**。

## 音乐播放页不落观看历史(2026-09-19)

- **现象(用户报)**:从音乐播放器退出后历史记录不更新。
- **根因**:`RoomDataManger.insertVodRecord` 是观看历史的**唯一落库点**,而全仓唯一调用者是 `DetailViewModel.insertVod()`(只在 `preparePlaySession()` 里调)⇒ 音乐页全程不落库,历史只有详情页交接那一刻的快照(`updateTime`/`playIndex`/`playNote` 都不再更新;切歌后"上次看到第 X 首"不对、列表排序位置也不刷新)。退出时走的 `PlaybackEngine.detach → saveCurrentProgress()` 只写 `CacheManager` 的续播进度,**不碰历史表**。
- **改动**:`MusicPlayerActivity` 新增 `syncHistory()`(写 `vod.playNote` → `insertVodRecord` → 广播 `TYPE_HISTORY_REFRESH`),调用点 = 切歌成功(`playAt`)后 + `onDestroy`(刷新时间戳)。⚠️ **key 必须用详情页传进来的 `firstsourceKey`**(`start(context, historySourceKey)`,缺省回落 `controller.sourceKey()`):详情页 `insertVod` 用的是 `firstsourceKey`,而 `controller.sourceKey()` = `session.sourceKey()`,换源/兜底后两者不同,不传会写出第二条历史记录(历史合并开着时被合并掉,关着就直接重复)。无痕模式由 `insertVodRecord` 内部拦截,无需额外判。
- **验证**:编译 + 装机通过;待真机确认(切歌后返回 → 历史刷新到顶部且显示最后那首;点该历史进详情页从最后那首开始)。**未提交**。

## 歌词不显示:源给的是 ASS 字幕(2026-09-19)

- **现象(用户报)**:播放音乐时音乐页不显示歌词。
- **定位过程(可复用)**:①先查崩溃缓冲,发现更早构建有 5 次 `MusicLrc.<clinit>` 崩(`PatternSyntaxException: \{[^}]{0,40}}`,即 `braceTag` 漏了转义 `\}` —— ICU 拒绝裸 `}`,而 `MusicLrc` 是 `object`,一处写错就打挂整个类 ⇒ **本进程所有歌词全为空**);②但当前源码已补上转义、崩溃已停,于是给 `syncLyric()` 补日志,实测 `parsed: 0 lines` 且**无** `parse failed` ⇒ 类初始化正常、`load()` 正常返回但解析出 0 行;③再给 `MusicLrc.load()` 补结构日志,拿到 `format=ass` + `head=[Script Info]\nScriptType: v4.00+...` ⇒ **源把歌词做成了 ASS 字幕**,而解析器只认 SRT(`-->`)与 LRC(`[mm:ss]`),两者都不占 ⇒ 走 `parseLrc` 一行都匹配不上。
- **改动**:`ui/music/MusicLrc.kt` 新增 `LrcFormat` 枚举(判定与分派共用一处,保证日志报的格式 = 实际分支)+ `isAss()` + `parseAss()` + `assTimeMs()`;`Dialogue:` 字段按 `split(",", limit = 10)` 切(Text 本身可含逗号),时间 `H:MM:SS.cc` 用 `roundToLong`(百分秒,`12.34 * 1000` 在 double 下可能是 `12339.999…`,截断会少 1ms),文本剥离 `{...}` 覆盖块、`\N` 还原换行、`\h` 还原空格。⚠️ **ASS 判定必须"行首"**(`[Script Info]` 或行首 `Dialogue:`),用 `raw.contains("Dialogue:")` 会被歌词正文误判 ⇒ 整首 0 行。`syncLyric()` 的 `runCatching` 补日志(`lyric parsed: N lines` / `lyric parse failed` / `lyric raw empty`)—— **禁止静默吞错**:格式不认与类初始化失败在界面上完全一样。`MusicLrcTest` 加 ASS 用例 + "LRC 正文含 Dialogue: 不被误判"用例(6 个用例全过)。
- **附带结论**:歌词源只给部分歌配词是常态(`echo-lyric pick: none`),界面没歌词不一定是 bug —— 先看 `echo-lyric pick:` 与 `echo-music lyric` 两组日志。**未提交**。

## 跨会话封面残留:音乐页显示上一首的封面(2026-09-19)

- **现象(用户报)**:播放完音乐再播影视、然后进音乐播放器,"有概率"显示的不是本次影视的封面。
- **根因**:音乐页取封面顺序 = `currentArtwork() → playArtwork() → vod.pic`,而这两个控制器字段都会跨会话残留 —— `playArtwork` 是**只写一次**的(`updateMusicSession` 带 `TextUtils.isEmpty(playArtwork)` 守卫,原先**全仓没有复位点**),`currentArtwork` 只在取流结果处理里被覆盖(影视源取流失败/无 cover 时保持旧值)⇒ 影视源不带 cover 时音乐页命中上一首音乐的 `playArtwork`。影视源自带 cover 就不会复现,所以是"有概率"。
- **改动**:`PlaybackController.startSession()`(会话边界,本来就是"会话级状态统一复位"的地方)补清 `playArtwork`/`currentArtwork`,**判据必须是 `playbackKey` 变化**:同片接管(退出详情页再进同一部,`PlayContainer.setData` 的 `isSamePlaybackOwned` 分支也走 `startSession`)不能清,否则封面会白到下一次取流结果。切歌路径不走 `startSession`(是 `engine.play`),封面由取流结果的 `currentArtwork = artwork` 覆盖,不受影响。
- **验证**:编译 + 装机通过;待真机确认(音乐 → 影视 → 进音乐页 = 显示影视 `vod.pic`;退出再进同一部 = 封面不变白)。**未提交**。

## 音乐页后台播放:声音停 + 通知消失且不再重建(2026-09-19,三轮静态审查收敛)

- **现象(用户报)**:音乐播放中把 app 挂后台,过一段时间声音停了(进程未被杀);通知栏的播放控制通知消失;回到音乐页界面仍是播放页、状态是暂停;再点播放有声音,但**通知再也不出现**,且此时"离开应用会被暂停";只能退出重进(重新从卡片打开)才恢复正常 —— 重开会在播出时重新出现通知,也能正常离开应用播放。
- **根因(会话状态机里三处"单向闩锁")**:通知/前台服务会话的唯一入口是 `PlaybackController.updateMusicSession()`,它每次都在同一处决定"建会话"或"撤会话":
  1. `audioPlayback` 一旦为 false,**只有"读到音轨"才会回写 true**,而撤会话分支会把它清掉;命中 `STATE_ERROR` / `STATE_PLAYBACK_COMPLETED` 时同样撤会话。
  2. `switchingPlayback`(取流/切集期间抑制)若卡 true,`updateMusicSession` 直接 return。
  3. 退后台判定 `isConfirmedAudioOnly()` 与通知重建**共用同一份轨道信息代理**(`currentTrackInfo()` → `hasPlayableAudio()` / `isAudioOnlyPlayback()`);轨道读不到时(内核重建、播放器 ERROR、Exo `MappedTrackInfo == null` ⇒ 空 TrackInfo)实时读取返回 null ⇒ 后台播放与通知同时失效。
  于是后台一次失败(错误或轨道读取失败)之后:通知被撤、`audioPlayback` 被清,**点播放不再产生通知**(重建只认 `STATE_PLAYING` 事件),退后台也没有"纯音频"豁免 ⇒ 与用户描述的四个现象逐条对应;退出重开会走 `startSession/play` 重新确认,所以能恢复。
- **改动(全部落在会话层,不动播放内核与封面语义)**:
  1. 新增 `PlaybackController.audioOnlyConfirmed` 粘滞标记,"确认过纯音频"不因一次读取失败而翻转;复位点只有内容边界(`beginNewPlay()` = 换集/换线/换源/重播,`startSession()` = playbackKey 变化)⇒ **自动重试不清**(同一内容的确认必须留着)。
  2. `updateMusicSession()` 里 `audioPlayback` **只置位、不清零**;"读到轨道列表但 audio 为空"分支删除(Exo 在 IDLE/重取流期、音频渲染器未选中时同样给空 audio 列表)。清零点收敛到会话边界:会话维护内的撤会话分支、`play()`、`stopPlaybackForPageExit()`、`onHostDestroy()`。
  3. `handlePlayStateForMusicSession()` 的切换期 `STATE_ERROR` 分支不再清 `audioPlayback`。
  4. ERROR 时对**已确认纯音频**的会话先 `retryAfterStartedError()`(同内核同地址重播一次)**并保留会话**,无路可走才照旧撤会话。判据刻意用 `audioOnlyConfirmed` 而不是 `audioPlayback`:影视也带音轨,放宽会让本方法抢在详情页 `errorWithRetry` 之前消耗掉 `hasRetriedAfterStart`(引擎状态监听先注册 ⇒ 总是它先跑),使影视丢失"同地址重播一次"这一档。
  5. 新增 `PlaybackController.beginSwitchPlayback()`,由音乐页 `playAt()` / `replayCurrent()` 在 `engine.play()` **之前**调用:状态事件同步派发给所有监听器且引擎监听先注册 ⇒ `STATE_PLAYBACK_COMPLETED` 到达时 `updateMusicSession` 会先跑,`switchingPlayback` 若仍为 false 就按"播完"撤会话(表现:一首放完,通知消失,下一首在播却没有通知)。队列末尾因 `playAt` 越界早退而不登记,照旧撤会话(正确)。
  6. 保留诊断日志:`echo-music session gate`(每次进闸门的 state/playing/hasAudio/audioOnly/audioPlayback/audioOnlyConfirmed/switching/pos)、`session drop`(撤会话的判据组合)、`session keep`、`echo-music page onPause/onResume`、`hostPause -> pause player`、`echo-p2 stopSession/stopPlaybackSession`。
- **为什么用粘滞标记而不是改 `hostPause` 判据**:判据是"退后台是否保持播放",属于会话的**粘性事实**(这份内容有没有音轨),不该随一次实时读取失败翻转;改 `hostPause` 只会掩盖读取失败,通知侧仍会失效。
- **审查中发现自己引入的两处回归并收回**(三轮静态审查的产出,值得记住的教训):①自动重试判据原本写成 `audioPlayback` ⇒ 影视也满足,抢走详情页的重播额度(上面第 4 条的 ⚠️);②`updateMusicSession` 里曾新增"`trackInfo != null` 就清 `audioPlayback`",但"读到轨道列表"≠"读到音频轨"(Exo 会给非 null 的空 audio 列表)⇒ 比改动前更严格,等于把同一个 bug 换个入口放回来,故改为只置位。
- **同时撤回一处误报**:第 4 轮审查曾判定 `updateMusicSession` 里 `view.isPlaying()` 会 NPE —— 实际 `ExoMediaPlayer.isPlaying()` 自带 `mInternalPlayer == null` 判空返回 false(`ExoMediaPlayer.java:186-199`),`IjkMediaPlayer` 同款;另确认 `ExoMediaPlayer.mInternalPlayer` 是实例字段(非 static),不存在跨实例共享已释放播放器的引用泄漏。
- **本轮实证(对以后排查有用)**:`ExoMediaPlayer.isPlaying()` 在 `STATE_BUFFERING/READY` 时直接返回 `getPlayWhenReady()` ⇒ **网络卡死/缓冲停顿时仍返回 true**,于是 `PlaybackController.isPlaybackStarted()` 的 `view.isPlaying()` 兜底也判为"在播" ⇒ 换线超时会被取消、后台卡死**不会**触发任何超时自愈,自愈入口只有内核抛出的错误回调(即本次给纯音频补的那条重试)。
- **验证**:`:app:assembleDebug` 构建通过(多轮改动后均绿);**未真机验证**。待真机核对:①一首放完自动下一首,通知栏全程保持(不再消失后重建);②手动切歌/上一首/下一首通知跟着切;③后台久放 → 音乐继续、通知可控(点暂停/播放/切歌);④若仍出现"声音停→点播放→无通知",取 `files/preload_debug.log`(debug 包)或 `adb shell run-as com.github.tvbox.osc cat files/preload_debug.log`,看 `echo-music session gate` 的 `audioPlayback/audioOnlyConfirmed/hasAudio` 三列即可判定剩余路径;⑤影视播放中报错仍走"同地址重播一次 → 换内核 → 换线"原阶梯(本改动不得影响);⑥退后台视频仍自动暂停(纯音频才保留后台播放)。**未提交**。
- **仍未处理(有意,免被当成遗漏)**:①影视完成态不保会话(同类问题,但与本 issue 无关、改影视风险高);②架构层 `updateMusicSession` 被 `view.isPageAlive()` 门控,而后台时事实持有者是 `attachedPage`(页面若被判死则本次修复也兜不住,需真机日志确认是否构成第三种触发)。

## 缺陷修复:未授予通知权限时播放"抽搐式"卡顿(2026-09-19,用户报"不授予通知权限,app 播放影视时画面和声音非常卡顿,像抽搐";用户真机确认修复成功)

- **现象(用户报)**:不授予通知权限 → 播放影视时画面与声音剧烈卡顿(自述"像抽搐一样");一旦在系统设置里允许通知,卡顿立刻消失。
- **排查手法(用户要求"直接抓日志,先编译 debug 装到我设备,别操控我的手机")**:`adb install` 装 debug 包后仅做**被动抓取**——设备侧 `adb logcat -b main,system,events,crash -v threadtime -f /data/local/tmp/tvbox_cap.txt` 留痕,再 `adb pull`;应用私有目录用 `adb shell run-as com.github.avbox.osc` 读 `files/preload_debug.log`(debug 包的 `LOG.FILE_LOG` 落盘通道)。原始证据归档在 `.logs/capture.txt` 与 `.logs/app_debug.log`。设备 = vivo V2425A / Android 16(API 36),targetSdk 37。
- **根因(权限页风暴)**:`PlaybackController.updateMusicSession()` 里有一行 `view.requestNotificationPermission()`(2026-09-13 作为"启动那次拒绝、后来想开"的兜底加入),而 `PermissionHelper.requestNotificationIfNeeded()` 在**未授权时直接下发申请、没有提前返回**。该方法是**热路径**:由播放状态回调驱动(`PlaybackEngine` 的 `addOnStateChangeListener` → `handlePlayStateForMusicSession` → `updateMusicSession`),实测密度见下。于是 `POST_NOTIFICATIONS` 固定拒绝时,每次状态回调都拉起一个 `GrantPermissionsActivity`(该 Activity 在"已固定拒绝"下表现为**创建→立刻 finish**,约 150ms 一轮),系统窗口反复抢焦点、每次都 pause/resume `DetailActivity`,渲染 Surface 随之被反复打断 ⇒ 画面与声音"抽搐";授权成功后 `isGrantedPermission` 提前返回,风暴停,卡顿消失。
- **真机证据**:①`cmd appops get com.github.avbox.osc POST_NOTIFICATION` = **`Uid mode: POST_NOTIFICATION: ignore`**,`dumpsys package` 该权限 `granted=false, flags=[USER_SET|USER_FIXED|…]` ⇒ 未授权且已被用户固定拒绝;②logcat 里 `wm_create_activity[…GrantPermissionsActivity,android.content.pm.action.REQUEST_PERMISSIONS…]` **12:19:57.123~12:20:00.344 三秒内 22 次**(间隔稳定 150~170ms),每次伴随 `wm_pause_activity[…DetailActivity]` → `wm_resume_activity[…DetailActivity]` → `input_focus` 反复切换;另一窗口 12:19:15.309~12:19:19.482 同款 26 次,并在其后拉起 `com.android.settings/.applications.InstalledAppDetails` + `NotificationSettingsActivity`(固定拒绝后的"去设置里开"引导);③**排除叠加干扰源**:该时段 `wm_create_activity` 记录里**唯一**被拉起的活动就是 `GrantPermissionsActivity`,无其他系统窗口;④同一窗口内 `am_foreground_service_start`(`…player.PlaybackService,1,PROC_STATE_TOP,…`)与 `notification_enqueue(id=1001, channel=music_playback…)` 均正常 ⇒ **前台服务本身是成功建立的,问题不在 FGS/解码器**。
- **改动(1 个文件)**:`app/.../util/PermissionHelper.java` —— ①新增进程级一次性闸门 `notificationAsked`,**闸门先于**权限查询(热路径上每秒被调多次,`isGrantedPermission` 是一次 binder `checkSelfPermission`,已问过之后答案不可能再变,没必要每次付 IPC;放前放后分支条件等价);②未授权时提前返回,不再下发申请。**行为取舍(已知)**:启动时拒过一次后应用内不再追问(Android 13+ 在 `USER_FIXED` 后系统本就不会再弹窗);要开启只能走系统设置——`isGrantedPermission` 的读取仍在,用户开启后通知照常显示。闸门是静态字段,**进程重启即复位**,下次启动会重新申请一次。已核无"冷启动直达播放页"的路径会因此漏申请(`MusicPlayerActivity` 只从 `DetailActivity` 进,必然先过 `MainActivity.init()`)。
- **验证**:`gradlew :app:assembleDebug` BUILD SUCCESSFUL(多轮);**用户真机确认"功能正常了,修复成功"**。
- **同一模式的全项目排查(用户"审查一下是否还有类似的错误和遗漏")**:按"热路径上无条件执行非幂等重操作"的形状查了四类 —— ①**活动/弹窗类**:全项目 12 处 `startActivity` 逐一核对,全在用户点击或广播回调里,**无第二处挂在状态回调/渲染循环上**(本类问题只此一处,已封闭);②`requestStorage` 是同一模式但**不是 bug**:`MainActivity.init()` 里带 `SDK_INT < R` 守卫且 `init()` 只跑一次,另一处在用户点击里,无风暴风险;③播放热路径内部:进度落盘(`saveProgress` 只在 release/onSaveInstanceState/detach 等会话边界,无定时器)、`MyVideoView.showVideoFrame/hideVideoFrameCover/clearArtwork`(幂等)、`PreloadCoordinator.postEvaluate`(`evaluatePending` CAS 防重入)、`DanmuLoadController.startIfReady`(`startedSeq` 守卫)**均干净**;④渲染视图热切换 `switchRenderToTexture` **已有守卫**(两个调用点都要求 `isSurfaceRenderActive()`,而 `addDisplay()` 是破坏性的 —— 这条曾被静态审查当成候选,实为已守卫,勿再"修")。
- **⚑ 订正 2026-09-16 那条待办的前提(`features.md` 上一条的"未做/待观察①")**:原文写"状态变化即重发通知…可加通知去重:title/subtitle/playing 均未变时跳过 `updateSessionState`+`startForeground`"。**真机实测不支持"通知被刷屏"这一前提**:`notification_enqueue(id=1001)` 在 logcat 里**只在起播瞬间按 3 个一组爆发**(12:19:33.715/.721/.757;12:23:36.593/.603/.629;12:24:05.854/.871;12:27:15.614/.622/.660),**其后整个稳定播放期 0 次**,即通知只在内容真变化时入队。那"3 个一组"对应**一次 play 事件内 `STATE_PLAYING` 被派发 3 次**(`VideoView.java` 的 `startInPlaybackState()`:336 / `resumePlay()`:403 / `onInfo` 完成分支:640 —— `setPlayState` 对同状态**无去重**),属既有设计而非重发缺陷。**结论:该待办按"无用户可感影响"降级,不建议按原文去重**(`buildNotification()` 每次确实会 new 对象 + 5 次 `PendingIntent` + `startForeground`,但无可观测后果)。
- **实测的热路径密度(供以后判断"是否算热路径")**:`files/preload_debug.log` 的 `echo-music session gate` 行显示,起播后约 6 秒内状态派发达 **8~9 次/秒**,表现为 `state=3`(PLAYING) 与 `state=4`(PAUSED) 在 45~50ms 间隔上交替(`playing` 随之 true/false 翻转)而位置持续推进 —— 即 **HLS 起播期 IJK 在缓冲中 `isPlaying()` 报 false**,经 `updateMusicSession` 的 `view.isPlaying()` 反映到日志,**并非真的暂停**(对照时段窗口焦点 churn = 0:无 `wm_on_paused/resumed`、无权限页)。⚠️ 该密度是**现象描述**,不是本次卡顿的根因。
- **未做(有意,免被当成遗漏)**:①`PlaybackController.currentTrackInfo()` 在热路径上重复物化轨道信息(一次 `updateMusicSession` 经 `hasPlayableAudio()`/`isAudioOnlyPlayback()` 会调多次,IJK 侧走 JNI、Exo 侧每次新建 `TrackInfo` + 全部 `TrackInfoBean` 并重算显示名)——**未实测其代价**,故未改;②`PlaybackController` 里 `echo-music session gate` 诊断日志在 debug 包会**每次派发落盘一次**(`LOG.FILE_LOG_PREFIXES` 含 `echo-music`,`FILE_LOG = BuildConfig.DEBUG`)⇒ 实测约 9 次/秒的"开文件→追加→关文件",与 `LOG.java:30` 自称"不在热路径上"不符(该日志是本轮排查期间加入的)——**仅 debug 包**:release 已实测剥离,见下条;③未把 `setPlayState` 加同状态去重(动 fork 的派发语义,回归面大)。
- **F3 的 release 剥离验证(2026-09-19,用户问"f3 的 debug 落盘日志会被 release 构建剥离吗")**:实测 `:app:assembleRelease`(R8,`isMinifyEnabled=true` + `isShrinkResources=true`)后,用 `dexdump -d` 逐 dex 搜字符串常量 —— `echo-music session gate`/`echo-music wake lock acquired`/`echo-preload-skip`/`echo-music destroy` **全部未命中**;`Lcom/github/tvbox/osc/util/LOG;->i(` 的 invoke 调用点 **0 处**;`Landroid/util/Log;->i(` 亦 **0 处** ⇒ **调用点连同参数里的字符串拼接一起被整条删除,release 不落盘、不拼串**(机制:`BuildConfig.DEBUG` 在 release 的生成源码里是字面量 `false` ⇒ `FILE_LOG` 折叠为 false ⇒ `fileLog()` 首行 `if (!FILE_LOG…) return;` 恒真;调用点则由 `proguard-rules.pro:357` 的 `-assumenosideeffects LOG.i/e/longI/longE` 删除,`PlaybackController` 那行里为日志而调用的 `view.currentPosition()` 也随之一并消失)。**唯一残留是死数据**:`Lcom/github/tvbox/osc/util/LOG;` 类本身、`fileLog`/`lambda$fileLog$0`/`<clinit>` 方法体与 `FILE_LOG_PREFIXES` 的 15 个前缀字符串(含 `echo-music`)仍在 classes4.dex 中(该数组的 `<clinit>` 把字符串 load 出来再 `sput-object`,R8 未判定其为纯死代码)⇒ **无可执行影响**,但使 `proguard-rules.pro:337` 的断言"release 包内不再残留任何日志字符串常量"**不成立**(常量池里仍有前缀字面量),该注释已在源文件订正。**结论:F3 在 release 上不构成任何问题,无需清理**(如仍想清,应在 `LOG.java` 里让 `FILE_LOG` 为 `false` 时把 `FILE_LOG_PREFIXES` 也整体折叠掉,而非动 R8 规则)。

## UI 调整:直播页整页加载指示器改 64dp + 投屏面板改用 md3e 几何加载指示器(2026-09-19,用户"进入直播页面时出现的圆角加载指示器改成和首页一致的;点击投屏控件后 dialog 里的圆形加载指示器也改为和首页一致的 md3e 风格")

- **改动 1(直播页)**:`LiveScreens.LiveScreen` 的 `PageState.LOADING` 分支,`LoadStateBox` 显式传的 `loadingContent` 由 **48dp → 64dp**。原 48dp 是 2026-09-11 "全局 64dp、直播页例外 48dp" 定稿的产物(见 UI spec §6);用户要求与首页整页加载态一致,故取消该例外。**首页基准值 = `HomePage.kt:222` 的 `ContainedLoadingIndicator(Modifier.size(64.dp))`**。
- **改动 2(投屏面板)**:`player/ui/CastSheet.kt` 的"正在搜索设备..."由 `SheetLoading(size = playerDim(vs_40))`(**`CircularProgressIndicator`**,`PlayerSheets.kt:283`)改为 **`ContainedLoadingIndicator(Modifier.size(64.dp))`**,即首页 / 引导页同款 md3e 几何加载指示器。因该组件受 `@ExperimentalMaterial3ExpressiveApi` 保护,文件头补 `@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)`,并补 `ContainedLoadingIndicator` / `Column` / `dp` 三个 import。
- **顺带修掉一处布局隐患(同一处代码)**:原实现是"spinner 居中 + 文字 `align(Center)` 再 `padding(top = vs_60)`"两条独立绝对定位,二者间距靠硬编码 padding 凑;换成 64dp 指示器后该间距不再合适。改为单个 `Column`(`align(Center)` + `CenterHorizontally`)包住"指示器 + Spacer(vs_10) + 文字",间距由 Spacer 明确给出,也不会与列表区(`vs_200` 高)溢出打架。
- **刻意的取舍(与"完全照抄首页"的唯一差异,已在 spec 记录)**:首页是整页加载态,用**固定 64dp**;而播放器覆盖层(含本面板所在 Dialog)整体走 **AutoSize(mm) 档**(`playerDim`/`playerTextSize`,按屏宽等比缩放,`PlayerSheets` 头部注释明确写"换成 M3 固定 sp 会在电视上明显偏小")。本次按用户"和首页一致"的要求取**固定 64dp**、未乘 AutoSize 缩放,因此在这套 Dialog 里它相对周围的 mm 档控件会略大 —— 若在真机(尤其 TV 大屏)上观感偏大,正确做法是改为 `playerDim(vs_64)` 而非调小数值。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL(首轮因漏 import `Column`/`dp` 报 6 个错,补齐后通过;唯一警告是 `LiveScreens.kt:312` 既有的 `Slider` deprecation,与本次无关)。**未装机、未真机目视**。未动 `SheetLoading` 本身(弹幕 / 字幕面板仍在用,删不得)。按用户要求**未跑 release 编译**。
- **文档同步**:`skill/avbox-mobile-ui-spec.md` §6「加载指示器」两条目更新(取消"直播页例外 48dp"、把投屏面板列入 `ContainedLoadingIndicator` 并修正"播放器保留清单");`features.md` 顶部"最近更新"行的陈旧表述"详情页/直播页例外 48dp"同步更正。

## 缺陷修复:音乐后台自动续播后通知永久消失 + 回页面点击无反应(2026-09-19,用户报"离开应用一段时间通知就消失,回到应用播放器点击没反应";**是 bug,不是单纯被系统回收**)

- **现象(用户报 + 真机日志确认)**:音乐播放中离开应用(锁屏/回桌面),过一段时间通知栏的播放控件通知消失;回到应用后播放器点击无反应、无法继续播放。用户已授予通知权限(`POST_NOTIFICATION = allow`),**与 2026-09-19 早些时候那条"未授权通知权限"的缺陷无关**。
- **现场取证手法(仅被动,不操控设备)**:`adb logcat -b main,system,events,crash -v threadtime -f /data/local/tmp/cap2.txt`;应用私有落盘日志 `adb shell run-as com.github.avbox.osc cat files/preload_debug.log`;状态查询 `cmd appops get`/`dumpsys activity services`/`ps -A -o PID,ETIME,CMD`。原始证据归档 `.logs/capture.txt`(12:19 那次)与 `.logs/app_debug2.log`。
- **完整因果链(真机 vivo V2425A / Android 16,12:59~13:01 实证)**:
  1. `12:59:07.341` Launcher RESUMED + `12:59:07.402` MusicPlayerActivity `onPause` ⇒ App 进后台(此时在播,pos=235961,一首歌已到末尾);19 秒网络停顿用于取下一首的流。
  2. `12:59:27.073` 上一首 `STATE_PLAYBACK_COMPLETED` 到达,`12:59:27.076` **`session drop: state=5`** ⇒ 撤会话。
  3. `12:59:27.084` `am_foreground_service_stop … STOP_FOREGROUND`,`12:59:27.094` `notification_canceled |1001|` ⇒ **通知消失**。
  4. `12:59:27.777` 下一首 `state=2 PREPARED`(自动续播成立)⇒ `12:59:27.797` 重新持 wake/wifi 锁。
  5. `12:59:27.826` / `12:59:27.848` **`echo-p2 startForeground failed: Service.startForeground() not allowed due to mAllowStartForeground false: …PlaybackService`**(系统原文)⇒ 服务在跑但**没进前台**,通知再也回不来(该时段后再无 `notification_enqueue`)。
  6. `13:00:27.101/.103` wifi/wake 锁被释放 —— 且**全程没有** `echo-p2 stopPlaybackSession` / `echo-p2 idle release` / `echo-music destroy` 任何一条 ⇒ 不是应用的收尾路径,是进程失去 FGS 身份后被降级/冻结的后果。
  7. `13:01:02.691` 用户回到音乐页,`playing=false`;此后到 `13:03:39` 应用日志**再无一行**,`dumpsys activity services` 里 **`PlaybackService` 已不在运行**,而进程(pid 12232,已存活 11 分 13 秒 ⇒ 未被杀过)仍在 ⇒ 页面绑在失效的引擎/服务上,点击自然无反应。
- **根因(两层,都在应用侧)**:
  1. **撤会话时机错**(主因):`PlaybackController.handlePlayStateForMusicSession()` 在 `STATE_PLAYBACK_COMPLETED` 且 `switchingPlayback == false` 时按"播完"撤会话。而**引擎的状态监听器注册在页面之前**(`PlaybackEngine.createPlayerView` 先注册,`MusicPlayerActivity.initView` 后注册;`setPlayState` 按 `PlayerUtils.getSnapshot` 顺序同步派发),所以 COMPLETED 到达时引擎这一侧**总是先跑**,而"要续播下一集"的登记 `beginSwitchPlayback()` 在页面监听器里(`onSongCompleted → playAt/replayCurrent`),**此刻尚未执行** ⇒ 读到的必然是 false。→ 这正是 `features.md` 上一条(见其改动第 5 条)与 `beginSwitchPlayback` 文档里**已经预警过**的失败模式:那条修复只覆盖了"页面在播完之前主动切歌",没覆盖"**本集自然播完自动续播**"。
  2. **失败被静默吞掉**(放大器):`PlaybackService.startForegroundSafely()` 原本 `catch (Throwable) { LOG.i(...失败...) }` 了事。被拒后服务以"在跑但无前台身份、无通知"的状态存活,而**没有任何重试或上报**——于是"通知永久消失"和"回到页面点击无反应"都不留痕迹。
- **改动 1(`PlaybackController.java`,治本)**:播完分支不再直接 `updateMusicSession()`,改为**延后一拍**判定 —— 新增 `MSG_DROP_SESSION_AFTER_COMPLETED`(挂既有的 `timeoutHandler`,main looper)与 `handlePendingCompletionDrop()`,在**同一次状态分发结束之后**再决定是否撤会话,此时页面的 `onSongCompleted()` 已跑完。新判据三条:①`switchingPlayback` 为 true(页面登记了切歌)⇒ 不撤;②内核已进入起播态(实时读 `view.currentPlayState()`,不依赖事件顺序)⇒ 不撤;③**页面不存活**或**状态已离开 COMPLETED** ⇒ 不撤(收紧兜底,避免那条迟到消息打到新会话上,也避免与页面退出的既有收尾路径重复撤会话)。`beginSwitchPlayback()` 顺带 `removeMessages` 撤销待判消息;另在 `beginNewPlay()` / `stopMusicSessionForFailedPlayback()` / `onHostDestroy()` 三处会话边界一并清除,防止迟到消息落到新会话。
- **改动 2(`PlaybackService.java`,加固不再静默)**:①`startForegroundSafely()` 失败时置 `foregroundDenied` 并以 `LOG.i(TAG + " startForeground DENIED (service stays non-foreground, will retry on next session update): " + msg)` 明确留痕(成功时打 `startForeground recovered after denial` 并复位);②`handleSessionIntent` 的 ACTION_UPDATE 分支在 `foregroundDenied` 时留痕并借这次更新**重试** `startForeground` —— 播放状态变化会反复触发会话更新,故 App 回到前台后基本立刻能救回前台身份与通知;③`updateSession` 的"服务不在 → 拉起"分支留痕(`session update with no live service → startForegroundService (may be denied if app is in background)`),这条正是会触发后台受限启动的入口。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL(多轮,含补 `removeMessages` 清理点后);**未装机、未真机复现验证**。按用户要求未跑 release。
- **待真机验证**:①音乐页放一首歌 → 让它**自然播完自动切下一首** → 立刻按 Home/锁屏 → 观察通知是否**始终不消失**;②回应用确认点击播放/切歌正常;③日志应看到 `echo-music keep session while resolving next episode`(页面登记生效)与/或 `completion drop skipped: page registered switching`,而**不应**再出现 `session drop: state=5`;④若仍出现 `startForeground DENIED`,则应有后续 `startForeground recovered after denial` 把通知救回。
- **顺带修正一处文档/注释的过时前提**:`LOG.java:26` 注释称"本机 ROM(vivo/BBK)会吞掉第三方应用的 `android.util.Log` 输出",但本次同一条链路的 `TVBox-runtime` 日志(含 `echo-p2 startForeground failed`)在 logcat 里**正常可见** ⇒ 该前提在当前 ROM 版本上已不成立(早先搜不到 `echo-music session gate` 更可能是缓冲区被 `am_cpu`/auditd 刷掉而非被吞)。
- **仍未处理(有意)**:①进程在失去 FGS 身份后被系统冻结/降级这一层没有兜底(App 无 `onTrimMemory`/前台回归自愈路径);②`MusicPlayerActivity.hostResume()` 不检测"引擎已被释放"(对比 `PlayContainer.hostResume()` 有 `reattachIfOwnedByOther()`),若引擎真被释放,音乐页缺少重建路径 —— 本次改动 1 旨在让引擎不再被这条路径拖死,故未动它。
- **审查轮次 2026-09-19(用户"继续审查看看还有没有错误和遗漏以及是否引入新回归";含一轮独立对抗审查)**:逐条核实后**修掉 4 处 + 记录 2 处未处理**,并修正一处我方错判：
  1. **【我方引入的回归,已修】延后撤会话绕过了 `isSupported` 前置**。旧路径的撤会话经 `updateMusicSession`,那里有 `if (!PlaybackService.isSupported(context)) return;`;新方法直接调 `PlaybackService.stopSession` 会绕过它 ⇒ 在 `isSupported()==false` 的设备(电视盒子 / API<26)上变成"每次播完都 release 一个 `onCreate` 建好、与页面同生命周期的 `MediaSessionCompat` 并把 `owner` 置空"(后续用法都有判空,不会崩,但媒体键会话被无谓拆掉)。已在 `handlePendingCompletionDrop` 补同一道守卫。
  2. **【修复 2 的恢复承诺不成立,已改】** 原注释写"App 回到前台后基本立刻就能救回"——**错**。已确认纯音频的会话退后台**不会被暂停**(`MusicPlayerActivity.hostPause` 对纯音频让路),回到前台时 `hostResume()` 也不产生任何状态事件 ⇒ 拿不到 `ACTION_UPDATE` ⇒ 该场景下没有任何可重试的时机。改为:①新增 `promoteIfForegroundDenied()`,在通知栏/媒体键动作(`ACTION_PLAY/PAUSE/PREVIOUS/NEXT`)后补一次前台 —— 那一刻系统一定允许提升(应用正在响应用户动作),且这正是用户最可能做的操作;②`ACTION_UPDATE` 分支保留机会性重试但**不再打"正在重试"日志**(被持续拒绝会每次更新刷一行,与"别再静默也别刷屏"矛盾),恢复信号统一由 `startForeground recovered after denial` 那一条承担。
  3. **【契约不完整,已补】** `startSession()` 与 `stopPlaybackForPageExit()` 原先不清 `MSG_DROP_SESSION_AFTER_COMPLETED`,而代码注释声称各会话边界已覆盖。虽未构造出可达路径(消息只活一拍,且每条播放路径都会清),仍按不变量补齐 —— 现共 **6 处清理**:`startSession` / `beginNewPlay` / `beginSwitchPlayback` / `stopMusicSessionForFailedPlayback` / `stopPlaybackForPageExit` / `onHostDestroy`。
  4. **【自身写坏,已修】** 改 2 时一度写成 `if (foregroundDenied) { … } else { startForegroundSafely(); }` 两边同体(行为正确但毫无意义),已还原为单次调用 + 准确注释。
  5. **【既有缺陷,未修,留档】** `STATE_PLAYBACK_COMPLETED` 下内核**无法被重新启动**:`VideoView.start()` 只处理 IDLE/START_ABORT/`isInPlaybackState()`,而 `isInPlaybackState()` **排除 COMPLETED**(`VideoView.java:496-503`)⇒ `MusicPlayerActivity.togglePlay` 与 `PlaybackEngine.resumeFromMediaSession` 在"队列播完"后都是空操作(后者随后的 `updateMusicSession` 还会把会话撤掉)。**本次两个修复都不涉及它**,但与用户报的"点击没反应"是同一观感,需另立一修(replay 或 `seekTo+start`)。⚠️ 注意本次真机故障的"点击无反应"根因是 FGS 被拒后进程被降级(引擎失效),与本条不是同一原因。
  6. **【既有隐患,可达性未证,未修】** `stopSession` 的归属守卫在 `owner` 是另一个仍可达的宿主时会**静默拒绝**撤会话(交接窗口:`PlayContainer.handOverToNextPage → detachForHandover → MusicPlayerActivity.attach`)。本次撤会话传的是当前活动桥的 host,与旧 `updateMusicSession` 路径一致 ⇒ **非回归**。
  - **独立审查确认无误的部分(供后来人省一遍推演)**:①`handlePendingCompletionDrop` 只读一次 `view.currentPlayState()` 存入局部量(无"双读不一致");②派发顺序核实为 `setPlayState → mVideoController.setPlayState → 监听器按注册序`(`PlayerUtils.getSnapshot` 保序)——**音乐页是引擎先跑(修复前提成立),详情页是控制器先跑**(`ComposeVideoController` 的 COMPLETED→`playNext` 先置 `switchingPlayback`);③三条完成路径逐条等价:音乐自动续播(SINGLE/LIST 环绕/ORDER 有下一集)会话保住;**队列播完**(`playAt` 早退)照旧撤会话、不产生悬挂通知;影视末集(`PlayContainer.playNext` Toast 后返回)照旧撤;直播侧 `liveMode` 早退、从不进入本方法;④内核不会自行离开 COMPLETED(Exo 只映射 BUFFERING/READY/ENDED、IJK `onCompletion` 后不再派发、两内核在 COMPLETED 时 `isPlaying()` 均为 false ⇒ `pause()` 不成立);⑤重复撤会话安全(`releasePlaybackLocks`/`mediaSession`/`stopForeground` 全幂等);⑥`view` 永不为 null(五处 `setViewBridge` 均传非空),`stopSession` 不会回调控制器 ⇒ 无递归/NPE;⑦锁只在 `handleSessionIntent` 取、只在 `stopPlaybackSession` 放,延后一拍仅把释放推后一个消息循环(毫秒级),无新增泄漏;⑧消息 id 100/101/102/103 无冲突,`Handler`/`removeMessages`/`sendEmptyMessage` 用法正确,桥接口方法齐备。
  - **验证**:`:app:assembleDebug` BUILD SUCCESSFUL(含全部修正后);`adb install -r` 已装机(`lastUpdateTime=2026-09-19 13:19:47`)。**仍未真机复现验证**。

## 真机验证通过 + 死代码/死文件审计(2026-09-19,用户"暂停掉后台 log 抓取,现在功能都正常了。检查一下有没有死文件和死代码")

- **真机验证通过(用户确认"功能都正常了")**;抓取文件里另有客观证据:`13:28:46.520` `notification_enqueue [avbox.osc, 1001, flags=…|FOREGROUND_SERVICE|NO_DISMISS…]` → `13:28:46.743` `Notify_NotificPush: Start onNotificationPosted … id=1001 ;actionButton2 上一个 / 3 暂停 / 4 下一个` → `13:29:01.242` `notification_visibility [0|com.github.avbox.osc|1001|…]` ⇒ 前台通知正常入队、上屏、可见;应用侧日志该时段 **`session drop` 零次**。两次修复(撤会话时机 + 前台失败不再静默)**均真机生效**。
- **死文件**:`git status` 显示本轮 **无任何新增/删除文件**(11 个全为 `M`),即不可能由本轮引入死文件。全仓顶层目录占用盘点:`.codebuddy/` 313MB、`示例文件/` 109MB、`.logs/` 15MB 等均已被 `.gitignore` 忽略(非死文件)。本轮**自查过程中产生的分析垃圾**(`apkdex/` 解包 380MB、`jar/` 解包)已清理,`.logs/` 从 383MB 回收至 15.2MB(仅留 logcat/应用日志证据)。
- **死代码(真删)**:`res/values/dimens.xml` 移除 4 个零引用 dimen —— **`ts_22` / `ts_24` / `vs_1` / `vs_6`**。它们**早在 2026-09-16 的 lint 报告里就被标为 `UnusedResources`**,一直没清;本次用自建引用扫描(全工程 UTF-8 读取成引用集 + 单词边界匹配)复核,确认全工程**仅剩定义处 1 次出现**。`:app:assembleDebug` 删除后 BUILD SUCCESSFUL(28 行)。
- **⚠️ 扫描器的两个坑(我踩了,记下来给后来人)**:
  1. **判据必须是"0 次"而不是"≤1 次"**。资源在源码里以 `R.drawable.<名>` 形态出现,**不含字面量文件名**,所以"被使用一次"就等于"只出现 1 次";用 `<=1` 会把所有正常资源判死(我第一版扫出 51 个假死,包括 `ic_notification_music`/`media_action_placeholder` 等明显在用项)。
  2. **必须显式 `[IO.File]::ReadAllText(path, Encoding::UTF8)`**。PowerShell 的 `Get-Content -Raw` 在 Windows 默认按 ANSI 解码,本仓中文注释多,读坏后引用集跟着坏(引用集 3.35MB → 修正后 3.73MB),假死会更多。
- **扫出但"绝不能删"的三类(与 2026-09-13 审计的保留清单一致,再次确认)**:①**框架回调/清单实例化** —— `receiver/SearchReceiver`、`receiver/CustomWebReceiver` 由 `AndroidManifest.xml` 的 `<receiver>` + `intent-filter` 实例化(源码必然零引用);`Service.onBind`、`WebViewClient.onConsoleMessage/onJsAlert/onReceivedSslError`、`MediaSessionCompat.Callback.onPlay/onSeekTo/onSkipToNext…` 同属此类。②**测试类** —— `*Test` 由 JUnit 反射发现,10 个全"零引用"。③**库模块对外面** —— `player/` 的 `FFmpegApi`(JNI)、`ISurfaceTextureHolder`、`IGestureComponent`(dkplayer fork 的公开接口)。
- **顺带订正我上一轮的一处错话**:直播页/投屏指示器那轮我曾写"若在 TV 上偏大,正确做法是改成 `playerDim(R.dimen.vs_64)`"—— **`vs_64` 在本项目根本不存在**(`dimens.xml` 现有档位止于 `vs_2/5/10/12/15/20/24/30/40/50/60/120/140/200/410/480/520/640/960`),照做会**编译不过**。正确候选是 `playerDim(R.dimen.vs_60)`(60mm)或 `vs_640`。当前实现是固定 `64.dp`,不涉及该资源,故未改动代码,仅订正此建议。

## ApiConfig / 直播页结构拆分(2026-09-21)

- **前置核对(先查数据再动手)**:方案里的数字有多处不成立,逐条实测后重排了顺序 ——
  1. ⑤「18 处非 final 公开静态字段」**不成立**:`ApiConfig` 全文件含 `static` 的行恰好 18 行(是 grep 行数不是字段数),实际公开静态成员 8 个(1 常量 + 7 静态方法),**非 final 公开静态字段 0 个**;唯一非 final 静态可变字段是 `private static String jarCache`(解析暂存值)。该条已作废。
  2. ①②③④ 已完成(分别对应 `d357786` / `c88ef9e` / `de2b866` / `7f27d8a`),但方案第六节顺序表仍把 ④ 列为待办、附录仍是 `2193d39` 基线数据 —— 读方案时**必须以当前 HEAD 为准**。
  3. ⑦「381 处硬编码 URL」在 app 代码层只有 22 处(381 是仓库级,含 wheel/资源);⑧「38 个 Kotlin object」实际 18 个;⑩「80 个 wheel / 37.7MB」准确。
- **拆法(沿用 ④ 的成功路径:抽纯逻辑 + 配单测,而不是按职责建 Manager)**:⑤ 只抽了纯函数层与爬虫装载,没有建 `ParseManager` / `DanmakuManager`(各只有 2~3 个成员,建类只是类爆炸);`parseJson` / `parseLiveJson` **有意不抽** —— 它们是「写单例字段 + KV 落盘 + `VideoParseRuler`/`AdBlocker`/`OkGoHelper` 静态注册 + 触发直播加载」的编排,抽出去只是换个地方写状态,且依赖 Android 环境**无法单测**。
- **⑤ 第 1 步:`ConfigParser.java`(273 行,新增)** —— `trimJsonObject` / `isLiveJsonContent` / `extractLiveTextEpg` / `extractQuotedAttr` / `parseSites` / `parseApiCollection` / `parseLiveSettingItems` / `parseHosts` / `clanToAddress` / `clanContentFix` / `fixContentPath` / `configUrl`。配 `ConfigParserTest`(25 例)。**顺带消掉 `TempKey` 这个可变单例字段**(`configUrl` 改为返回 `ConfigUrl{url,key}`)。
- **⑤ 第 2 步:`SpiderLoader.java`(519 行,新增)** —— jar/js/py 三加载器 + jar 下载/md5 校验/一周缓存/重试链路 + `getCSP`/`getPyCSP`/`getJsCSP`/`getLiveCSP` + `setLiveJar` + 直播 spider 装载 + 代理分发原语 + 弹幕搜索 + 清理。源列表、`getHomeSourceBean`、代理时的「当前源」、KV 读写**留在 ApiConfig**(SpiderLoader 只负责"给我站点或 spider 地址,还你一个能用的 Spider")。`warmSearchSpiders` 的循环留在 ApiConfig 且**仍复用 `configLoadExecutor`** —— 预热与配置拉取共用一个单线程队列是刻意串行,没给它单开线程池。`jarCache` 从 static 降为实例字段。
- **⑥ 第 1 步:`LiveChannelNavigator.kt`(120 行)+ `LiveChannelNavigatorTest`(20 例)** —— 抽 `nextPosition` / `firstChannelByName` / `firstUnlockedGroupIndex`。⚠️ **搬出时发现并修掉一处死循环(本批唯一的行为变更)**:原 `getNextChannel` 的跨组推进是 `do { 前进 } while (带密码 || 回到当前组)`,那个 `||` 写反了 —— 本意是"绕回当前组就停",写成"绕回当前组继续转"。当**除当前组外全部带密码**或**只有 1 个分组**时条件恒为真,跨组开关打开后按上下台会**在 UI 线程上无限循环卡死**(默认 `LIVE_CROSS_GROUP=false`,所以只有开了这个开关的用户会踩到)。改为有界循环(最多走 `groups.size` 步,回到当前组即停),3 个用例锁死该行为。**这个死循环是我写测试时被挂住的 gradle 任务暴露出来的** —— 当时误以为是 gradle 卡住,实为测试用例命中了它。
- **⑥ 第 2 步:`LiveEpgController.kt`(288 行)** —— EPG 缓存(`hsEpg`)、地址配置、三级降级(模板地址按频道名逐个试 → 回落内置地址 → 清空)、切台作废判断、延迟取数。**Compose 状态(`epgdata`/`epgVersion`)与频道信息条刷新留在宿主**,控制器只回调 `onEpgListChanged`/`onEpgSettled`。`getConfiguredEpgAddress` 与 `DEFAULT_EPG_ADDRESS` 一并进控制器(`reloadAddress()`),避免同一 URL 常量两处各写一份。
- **⑥ 第 3 步:`LiveProxyLoader.kt`(130 行)+ `LiveProxyLoaderTest`(4 例)** —— 解 ext(base64)→ 地址白名单 → py/js 走 Spider(带超时作废)/ 其余走 OkGo。`isValidLiveProxyUrl` 改名 `isValidProxyUrl` 并放进伴生对象(测试不必实例化,构造里有 Handler)。⚠️ 里面 `TextUtils.isEmpty` 换成 `url == null || url.isEmpty()` —— 与 `EpisodeMatcher` 注释同一个坑:单测开了 `returnValuesDefault` 时 `TextUtils` 静默返 false,用它这条测试等于没测。
- **⑥ 第 4 步:`LiveSettingsRules.kt`(50 行)+ `LiveSettingsRulesTest`(9 例)** —— 只抽了 4 个无依赖判定:`visibleGroups`(隐藏组判据是 `groupIndex in 0..2` 而非列表下标)、`hasChannelSource`(线路下标有效性)、`currentConfigIndex`(跟随/历史 +1 偏移、找不到返 -1)、`sourceItems`。**方案原定的 `LiveSettingsHandler` 有意没做**:`clickSettingItem` 98 行依赖 14 个 Activity 成员(`playChannel`/`livePlayerManager`/`mVideoView`/`releasePlayerKernel`/`refreshLiveChannelListAndPlay`/…),抽出去只能得到一个 14 方法的 Host 接口,是耦合换地方写。
- **⑥ 第 5 步:`LivePlayViewModel.kt`(221 行)+ Activity 同名转发(用户拍板"整步搬")** —— 26 个界面状态 + `PageState`/`ChannelInfoUi` 类型(移为同包顶层)+ `liveConfigRequestId` + `onSettingClicked`(原 98 行分发)+ 14 方法 `Host`。**Activity 与 LiveScreens 的 125 处读写点一行没改**:用同名属性转发,状态所有权已归 ViewModel。⚠️ **`clickSettingItem` 搬进 VM 后依然不可测**(依赖 `KV`/`ApiConfig` 单例),搬它的收益只有"逻辑离开 Activity"。
- **审查轮次(2026-09-21,用户"审查一下是否有错误遗漏和引入新回归")**:用**逐行差集**(HEAD 的非空非注释代码行 vs 所有新文件)核对等价性 —— ApiConfig 组 128 行、直播页组 140 行"新文件里没有",逐条核对**全部可解释**(搬走/改写/删除的死代码),无未解释丢失;编译告警只多了一条既有的 `Slider` deprecation,无「never used」。修掉 2 处**编译与单测都抓不到**的问题:
  1. **【我方引入,必崩,已修】`LivePlayActivity` 一进页面就崩**。原写法 `internal var pageState by vm::pageState` 是**绑定**属性引用 —— 它会在 Activity **构造期**就求值 `vm`,`by viewModels()` 的懒加载立刻调 `getViewModelStore()`,而那时 Activity 还没 `attach()`,`ComponentActivity` 直接抛 `IllegalStateException("Your activity is not yet attached to the Application instance. You can't call ViewModelStore before onCreate.")`(已在 `activity-1.13.0.aar` 的 `ComponentActivity.class` 字节码里确认该字符串存在)。改为**非绑定**属性引用 + 自定义委托 `VmVar`/`VmVal`(只存 `LivePlayViewModel::xxx` 引用,真正取值推迟到 `getValue`/`setValue`)。**同类坑的通用判据:凡是构造期就会求值 `vm` 的写法都崩;`by viewModels()` 只能被方法体内的读取触发。**
  2. **【我方引入,已修】`onDestroy` 不再取消延迟任务**。搬出的 `LiveEpgController`/`LiveProxyLoader` 各自带 Handler,而 `onDestroy` 里只有 `mHandler.removeCallbacksAndMessages(null)`(清 Activity 自己的队列)—— 原来这些延迟任务挂在 `mHandler` 上会被一并清掉,现在退出直播页后 1.2s 的延迟 EPG 取数仍会触发、向已销毁的界面回写状态。给两类各加 `cancelAll()` 并在 `onDestroy` 显式调用。**通用判据:凡是"自带 Handler"的抽离类,宿主销毁时必须显式取消,不能指望宿主清自己的队列。**
- **验证**:`:app:testDebugUnitTest` **174 用例 / 0 失败**(本批新增 58 例:ConfigParser 25 + LiveChannelNavigator 20 + LiveSettingsRules 9 + LiveProxyLoader 4);`:app:assembleDebug` BUILD SUCCESSFUL(`AVBox_debug.apk` 84.3MB)。行数:ApiConfig 2045→1510、LivePlayActivity 1651→1273、LiveScreens 631→630;新增 7 个源文件 + 4 个测试文件。
- **同步活规范(发现两处失真,已改)**:`avbox-mobile-ui-spec.md` §2「文件布局与可测性基线」两处过期 —— ①直播页文件布局只列 3 个文件(现 8 个),已补 `LivePlayViewModel` / `LiveEpgController` / `LiveProxyLoader` / `LiveChannelNavigator` / `LiveSettingsRules`;②单测基线写"5 个测试类、**无 `isReturnDefaultValues`**",实际是 **17 类 / 174 用例**且有 `isReturnDefaultValues = true`(`c88ef9e` 为让 `LOG` 在 catch 分支打日志不炸而加)—— 该基线会把后来人的可测面判断带偏(真正的坑是**静默假值**而非 `not mocked`),已改正。另在 §6 新增「6.9 组件与生命周期」记下本轮两处坑(ViewModel 绑定属性引用 / 自带 Handler 的抽离类需显式取消)。
- **仍未处理(有意)**:①`SpiderLoader` / `LiveEpgController` / `LivePlayViewModel` 三个有状态新类**无单测**(JVM 单测覆盖不到);②`epgdata` 仍在 Activity 而 `epgVersion` 在 ViewModel(状态分裂,非 bug);③`LivePlayViewModel.Host` 14 方法的设计代价;④ViewModel 在「开发者选项 → 不保留活动」下比原 Activity 字段多活一轮(页面态可能保留而非重置);⑤方案文档本身未同步(⑤ 的错误数据、④ 已完成但表格仍列为待办、⑦⑧⑩ 依据过期);⑥方案 ⑥ 的验收标准「LivePlayActivity ≤ 300 行」未达成(现 1273 行)—— 剩余主体是播放器/频道列表编排,依赖 12~14 个 Activity 成员,要真拆得先抽播放器接口。

## 搜索页内容分区过渡动画:试了两版,用户要求全部删除(2026-09-21)

- **起因**:用户问「输入后点搜索是直接出现结果页吗?能否加过渡动画」。核对代码确认:`SearchActivity` **不换 Activity**,`submit()` → `vm.search()` 同步置 `running=true` + `results=待返回列表`,下一帧 `SearchScreen` 里的 `if/else` 就跳到结果分支 —— 硬切换、零动画(输入法同时被 `hideIme` 收起)。
- **第 1 版(fade + slide + spring)**:三分支改 `AnimatedContent(targetState = SearchStage)`,`SearchStage = NoSites/Idle/Results`;过渡 = 新内容 `fadeIn(spring(StiffnessMedium)) + slideInVertically(1/16 屏高)`、旧内容 `fadeOut(spring(...))`。**发现并处理了一个坑**:提交瞬间 `vm.search()` 会 `clearSuggest()`、`submit()` 还会刷新 `history`,淡出的旧内容若读实时值会在半程从「搜索建议」跳成「热搜榜」⇒ 加 `SearchIdleData` 快照 + `SideEffect` 只在 `stage == Idle` 时刷新。
- **用户反馈**:"切换动画效果不好,而且有些卡顿"。
- **第 2 版(纯 alpha 交叉淡入淡出)**:去掉 slide(结果区首帧只有左侧源栏 + 波浪进度条,给整屏内容做位移是"大片内容在固定顶栏下面抖");`spring` 换 `tween`(进入 170ms `LinearOutSlowInEasing` / 退出 110ms `FastOutLinearInEasing`)—— spring 无固定时长,退出要等回弹收敛,会把两棵全屏子树同时留在组合树里三四百毫秒;`sizeTransform = null` 去掉默认的逐帧尺寸动画 + 裁剪。
- **⚠️ API 教训(实际踩到)**:`AnimatedContent` **没有** `sizeTransform` 参数。先用 javap 从 Gradle 缓存的 `animation-android-1.12.1.aar` 确认签名(`AnimatedContent(targetState, modifier, transitionSpec, contentAlignment, label, contentKey, content)`),再从 `animation-android-1.12.0-sources.jar` 的 `AnimatedContent.kt` 确认 `sizeTransform` 是 **`ContentTransform` 的构造参数**(第 4 位),且其 setter 是 `internal`(字节码里是 `setSizeTransform$animation`)**不能**用 `.apply { sizeTransform = null }`,只能走构造器。**这类 Compose 新 API 不要凭记忆写,先查缓存里的 aar/源码包。**
- **用户最终决定**:"切换动画效果删除,改回来" ⇒ `SearchActivity.kt` **完全回退**到 `if/else` 直接切换(同步删除 `SearchStage` / `SearchIdleData` / 两个时长常量 / `stage` 计算 / `idleData` + `SideEffect`,并移除 `ContentTransform`、`FastOutLinearInEasing`、`LinearOutSlowInEasing`、`tween`、`SideEffect` 五个 import;`AnimatedContent`/`spring`/`fadeIn`/`fadeOut`/`slideInHorizontally`/`slideOutHorizontally`/`togetherWith` **保留**,结果区内部「横向/纵向」布局切换的 `searchResultLayout` 仍在用)。
- **回退核对方式**:`git diff` 逐符号确认残留为 0(`SearchStage`/`SearchIdleData`/`STAGE_*_DURATION_MS`/`ContentTransform`/`sizeTransform`/`tween(`/`SideEffect`/`slideInVertically`/`idleData`/`searchStage` 全部 0 次),保留的 7 个动画符号引用数均 ≥2(import + 至少一处使用),无未使用 import。⚠️ 该文件 `git diff` 里另有**非本轮**的改动(`LayoutSwitchAction` 从顶栏 `actions` 槽移到搜索框 `trailing`),属用户工作区既有未提交内容,不要误判为本轮引入。
- **环境坑**:`./gradlew` 在本机 Git Bash 下报 `找不到或无法加载主类 org.gradle.wrapper.GradleWrapperMain`(wrapper jar 本身完好、含 `GradleWrapperMain.class`)。可直接用缓存里的发行版:Gradle 9.7.1 在 `~/.gradle/wrapper/dists/gradle-9.7.1-bin/<hash>/gradle-9.7.1/bin/gradle`。
- **结论(给后来人)**:搜索页这块**不要再加过渡动画**。用户已明确否决,理由是观感 + 性能双输;`AnimatedContent` 在这里必然让两棵全屏子树并存,而首帧结果区没有可衔接的视觉主体。

## 直播页换台后列表高亮不同步(2026-09-21 修)

- **用户反馈**:「直播界面切换到 CCTV1 后,结果面板还是显示我在 CCTV2」。截图放大取色核对:选中态样式(行底色 `cardContainer` + 台名 `primary`)确实落在第 2 行(CCTV2),而频道信息区显示 CCTV1 —— 播放器切了、列表高亮没切。
- **排除索引错**:`LiveChannelItem.channelIndex` 在 `ApiConfig.loadLives` 里是**组内 0 基自增**(`setChannelIndex(channelIndex++)`,重名合并不占号),与 `buildChannelRows()` 写进 `LiveListRow.channelPos` 的下标、点击回传的 `selectChannel(group, row.channelPos)` 三者恒等;高亮比较(`channel.channelIndex == currentLiveChannelIndex`)本身没错。
- **真实原因**:`LiveScreens.kt` 的高亮判定读的是 `LivePlayActivity.currentChannelGroupIndex` / `currentLiveChannelIndex` 两个**普通 Kotlin 字段**,Compose 读它们不产生依赖;列表只在 `channelVersion` / `scrollTick`(作为 `LaunchedEffect` 的 key 被读)与 `expandedGroups` 变化时重组,而 `playChannel()` 同组切台**这两个计数一个都不加** ⇒ 高亮保持"上一次重组时"的当前台(常见情形就是切走前那个台;严格说不是"固定差一位",而是"停留到下一次重组")。这同时解释了:进页面 / 换配置 / 折叠展开分组时高亮又是对的;频道信息区不受影响(走 `channelInfoUi` 真状态 + `updateChannelInfoUi()`);切台后列表也不滚动(同一个 `LaunchedEffect`)。
- **上游对照(佐证"漏了一步")**:fongmi 版的点击处理里显式 `liveChannelItemAdapter.setSelectedChannelIndex(position)`,该 setter 内部对新旧下标各 `notifyItemChanged` 一次。Compose 迁移时这道"通知列表选中变了"的等价物没有补上。
- **改动(2 文件 3 处)**:①`LivePlayViewModel` 新增 `currentChannelGroupIndex` / `currentLiveChannelIndex`(`mutableIntStateOf`),Activity 两个字段改由 `VmVar` 同名转发(沿用 §6.9 的非绑定委托模板,调用点一行没改)⇒ 高亮真正响应式,点列表 / 左右快滑上下台 / 超时自动换台全路径覆盖;②`playChannel()` 的 `!changeSource` 分支补 `scrollTick++`,切台后把当前台滚进视野(滚动信号统一走 `scrollTick`,不再借道 `channelVersion`)。
- **"状态挪进 ViewModel"的连带语义(核对结论:不构成回归)**:这两个下标从此与 `pageState` / `expandedGroups` 一样会**跨 Activity 重建保留**(系统回收 / 开发者选项「不保留活动」;manifest 已声明 `orientation|screenSize|...`,常规旋转与进出全屏都不重建)。两条兜底已逐条核对:①`liveChannelGroupList` 是 Activity 字段,重建后为空 ⇒ 列表没有行可高亮;而 `applyLiveChannelGroups()` 是它唯一的填充入口且末尾必调 `initLiveState()`,后者在任何切台动作之前**无条件**写 `currentLiveChannelIndex = -1`(分组下标随后由 `playChannel` 写入)⇒ 不存在"保留值命中错行"。②每次切台都把当前台落盘 KV `LIVE_CHANNEL`,`initLiveState()` 按它恢复同一台 ⇒ 重建后高亮与播放器一致。
- **验证**:`:app:compileDebugKotlin -q` 退出码 0(无输出);`:app:testDebugUnitTest` **174 用例 / 0 失败 / 17 个测试类**(与既有基线一致,本次未动纯逻辑)。**真机待验(测试机由用户操控)**:点列表换台高亮立刻跟随、左右快滑与超时换台同样跟随、切台后当前台进入视野、进页面定位上次频道不受影响。
- **通用判据(已写进 spec §6.2)**:`LaunchedEffect(key)` 只订阅 key;Composable 里读宿主普通 `var` 不产生依赖。新加"会被 Compose 渲染出来"的状态时,要么放进 ViewModel 状态,要么挂进 key 并在**每个**写入点 bump 版本号 —— 少一处写入点就是一类"值变了但界面不动"的 bug。

## 设置/播放设置的部分选项由 bottom sheet 改「弹出式菜单」(2026-09-21)

- **需求(用户)**:设置页面的 DOH / 历史记录上限 / 默认启动页,与播放设置页面的 播放内核 / 画面渲染 / 画面缩放 / 解码方式 —— 这些行的选项弹窗从 bottom sheet 改为**弹出式菜单**,具体参考搜索页面的弹出式菜单。
- **参考物 = 全仓唯一的 `DropdownMenu`**:搜索页「结果展示方式」(`SearchScreens.kt` 的 `LayoutSwitchAction`:`shape = shapes.medium` / `containerColor = surfaceContainer` / `shadowElevation = 4.dp`,内容列 8dp 横向内边距 + 4dp 项间距,项 = `surfaceBright` 卡 + 选中项右侧 `primary` 对勾)。改前全仓只此一处弹出菜单,**没有可复用的通用组件**,故新增。
- **改动**:①新增 `ui/components/OptionMenu.kt` —— `AVBoxOptionMenu`(通用弹出菜单,与参考同款容器与项样式;宽度用 `widthIn(min = 156.dp)` 而非参考的固定 `width(156.dp)`:与参考同宽,标签更长时按内容撑开,避免 `TextureView` 这类长标签在**选中态(多一个对勾)**被省略号截断)+ `SettingsOptionMenuRow`(把 `SettingsRow` 与它打开的菜单封在一行内,自带 `expanded` 状态与锚点 `Box`;`enabled = false` 时**不传 onClick**,保持原「禁用行不显示 chevron」的外观)。②`SettingsPage.kt` 三行换用 `SettingsOptionMenuRow`,`openOptions` + `AVBoxOptionSheet` 留给**接口线路**。③`PlaySettingsPage.kt` 四行换用,并删掉该页的 `optionSheet` 状态 / `openOptions` / `AVBoxOptionSheet`(该页已无任何 sheet 弹窗)。
- **原映射逐条保留(易错点)**:画面渲染 `selectedIndex = 1 - state.playRender`、`onSelect` 里 `render = 1 - idx`(playRender 1=SurfaceView,与选项下标相反);画面缩放传 `indexOfFirst { it.first == state.playScale }`(-1 = 不标选中,等价原 sheet 的 `getOrNull(-1)`);播放内核候选列表改到**组合期**算(`getExistPlayerTypes().sortedDescending()`,该可用性表是进程级缓存,`getPlayerName` 本来每次组合都在读它);解码方式沿用「显示/写入当前内核那一份键」的联动与禁用态。
- **装机反馈修正:菜单贴到了行左缘(2026-09-21 同日二轮)**:用户截图问「为什么点击时全部出现在最左边」。像素核对(1260x2800 @3.5px/dp):菜单容器左缘 = 16dp(= 卡片左缘)、项左缘 = 24dp(= 16 + 8dp 内边距),即**锚点就是整行、菜单按行左缘对齐**;搜索页之所以看着在右边,只是因为它的锚点是搜索框右侧那个 ⋮ 图标(锚点在哪边菜单就在哪边)。
- **机理(读 M3 源码确认,不是猜)**:`DropdownMenu` → `MenuAnchorPosition.Below`,x 候选顺序 = `startToAnchorStart`(菜单左缘贴锚点左缘)→ `endToAnchorEnd`(菜单右缘贴锚点右缘)→ 窗口边;逐候选检查"放得下"后才采用,所以整行锚点必然命中第一个候选 = 左对齐。
- **改法**:`SettingsOptionMenuRow` 里把菜单塞进 `Box(Modifier.align(Alignment.BottomEnd))`(零尺寸锚点落在行的**右下角**)。此时 `startToAnchorStart` = 行右缘(1204px)+ 菜单宽(156dp=546px)> 屏宽 1260px ⇒ 放不下,落到 `endToAnchorEnd` = 行右缘 − 菜单宽 ⇒ **菜单右缘对齐行右缘**;y 候选 `topToAnchorBottom` = 行下缘 ⇒ 仍贴在行下方(靠底的行自动翻到行上方)。零尺寸锚点不会触发除零:`calculateTransformOrigin` 先判 `menuBounds.right <= anchorBounds.left`(= 相等)⇒ pivotX 直接取 1f,不进入除法分支。
- **未选的做法**:`DropdownMenuPopup(anchorPosition = MenuAnchorPosition.End)` 能直接指定锚点方位,但该 API 走 M3 默认容器(shape/容器色/阴影都在内部写死,不接受本项目要的 `surfaceContainer` + `shapes.medium` + 4dp),会丢掉与搜索页一致的观感 —— 故沿用 `DropdownMenu` + 挪锚点。
- **另一条已核对的 M3 前提**:菜单内容列由 M3 自己用 `width(IntrinsicSize.Max)` 测量(同文件 `DropdownMenuItemContent` 内部就用 `Modifier.weight(1f)`,故项里用 `weight` 是安全的);`DropdownMenu` 的定位器 `horizontalMargin = 0`(AndroidMenu.android.kt)。若日后要改成"行下方居中",等价做法是把锚点换成"宽 = 菜单最小宽(156dp)、对齐 `Alignment.BottomCenter`"的 Box(此时 `startToAnchorStart` = 行中线 − 78dp,恰好居中)。
- **验证**:`:app:compileDebugKotlin -q` 退出码 0(无输出);`:app:testDebugUnitTest` **174 用例 / 0 失败 / 17 个测试类**(改动全在 Compose UI 层,纯逻辑未动);`:app:assembleDebug` 成功,产物级核对 = `OptionMenuKt.class` 晚于源文件、APK 晚于 class,且包内 `classes21.dex` 命中 `SettingsOptionMenuRow` / `AVBoxOptionMenu` / `OptionMenuKt`、`classes20.dex` 命中调用方。**真机待验(测试机由用户操控)**:①七个菜单是否都出现在**该行右下角**(菜单右缘对齐行右缘、贴在行下方;靠底的行自动翻到行上方)、点空白/返回是否收起;②选中项对勾与行右侧当前值是否一致;③DOH 项数随接口配置变化、项多时菜单内可滚动;④播放设置页不再出现任何 bottom sheet。

## 播放设置 / 偏好设置三处排版微调(2026-09-21 同日三轮)

- **需求(用户,3 条)**:①删掉播放设置页「播放内核」行下方那句「部分站点使用自己声明的内核」;②播放设置页的 播放内核 / 画面渲染 / 画面缩放 / 解码方式 四项独立成一组分组卡片,下方(IJK 缓存播放 / 隧道模式 / AAC 优先 / 音乐播放页)另成一组;③偏好设置页把「历史合并」那组与「自动换线」那组卡片**整组对调** —— 该条有歧义(只换两张卡 vs 整组换位),**问过用户后确认是整组对调**,没按自己的理解动手。
- **改动**:①`PlaySettingsPage.kt` 删掉那行 `Text`(原在首张卡内、紧跟 `SettingsOptionMenuRow`;删后 `Text` import 仍被顶栏标题使用,未动 import);②同文件把一个 `SettingsGroup` 拆成两个,中间 `Spacer(28.dp)`(与设置页/偏好设置页的组间距同规格),两组各自 `FIRST/MIDDLE/MIDDLE/LAST`,行内容与回调**逐字未改**;③`PreferenceSettingsPage.kt` 把两个 `SettingsGroup` 块整体换位(28dp `Spacer` 仍留在两组之间),组内卡位形状不变 ⇒ 新顺序 = 历史合并 → 无痕模式 → 禁用手势控制 → 禁用导航动画,然后 自动换线 → M3U8 净化 → 弹幕开关 → 弹幕 API → 长按倍速 → 缓冲时间 → 搜索线程。
- **顺带修正的文档失真**:活规范 §4.9 原写「组2 = 无痕模式 → 禁用手势控制 → 禁用导航动画」,**漏了组首的「历史合并」**(该行是后加的、规范没跟上;这也说明"照规范核对"必须同时照代码)—— 本次按实际代码与新顺序改写,并把"两组各几张卡"写进规范。
- **验证**:`:app:compileDebugKotlin -q` 退出码 0;`:app:testDebugUnitTest` **174 用例 / 0 失败**。**真机待验**:①播放设置页呈两组(上组 4 个选值行、下组 4 个开关,组间 28dp);②「部分站点…」已消失且首卡高度收紧(不再多 12dp 底padding);③偏好设置页首组开头是「历史合并」、第二组开头是「自动换线」,两组 28dp 圆角卡位(首卡上圆角/末卡下圆角)正常。

## 搜索页结果展示方式默认改为竖排(2026-09-21 同日四轮)

- **需求(用户)**:"改为默认竖向展示"(上一问已确认改前默认是横排)。
- **改动只有 1 处**:`util/SearchSettings.kt` 的 `resultLayout()` —— 判据从"只有 KV `search_result_layout` 等于 `"vertical"` 才竖排、其余(含键不存在)横排",改成**与 `HomeSettings.current()` 同款**:`KV.get(KEY_RESULT_LAYOUT, VALUE_LAYOUT_VERTICAL) == VALUE_LAYOUT_HORIZONTAL` 才横排,其余竖排。默认值仍传 String 常量(不是 `""`)—— 该键**未登记 `KVKeySpec`**,读取必须带默认值、类型要能被默认值携带,换成另一个 String 常量不影响解码。
- **影响面(改默认值的通用语义)**:①**从未切过**的用户(键不存在)⇒ 跟着变竖排(本次意图);②**显式选过「横向展示」**的用户 KV 里有 `horizontal` 记录 ⇒ 仍横排(不覆盖用户显式选择);③显式选过竖排的不变。④两种排版本来都已实现(`RailResults` / `SearchListResults` 两支),不存在"竖排没渲染"的问题 —— 旧记忆里"竖排只落库,渲染未实现"是过期信息,已按代码写入规范 §4.6。
- **验证**:`:app:compileDebugKotlin -q` 退出码 0;`:app:testDebugUnitTest` **174 用例 / 0 失败**(现有 `SearchSettingsTest` 只覆盖 `isExactMatch`;KV 相关判定不在单测面内,与项目既有口径一致)。**真机待验**:①键不存在(或在「更多」里选一次「竖向展示」)时进搜索页,结果区应为**左侧站点栏 + 右侧列表**;②在「更多」里切「横向展示」应立刻变成各源分区 + 横向卡片行,重启后保持;③切换展示方式时结果源筛选应重置为「全部」。

## 配置管理页「点播/直播」分段改成音乐页底部胶囊同款(2026-09-21 同日五轮)

- **需求(用户)**:配置管理页的点播/直播分段,外层换成 `surfaceContainer` 大圆角胶囊、内部换成子弹头形状的 `surfaceBright` 段 —— 即与音乐播放页底部胶囊同一套观感。
- **参考实现(音乐页 `MusicBottomActions` / `BottomActionItem` / `segmentShape`)**:外层 `clip(CapsuleShape) + background(surfaceContainer) + padding(8dp)`(`CapsuleShape = RoundedCornerShape(percent = 50)`);段 = `clip(shape) + background(if (active) activeColor else surfaceBright)`,`elevation` 无阴影;选中态 = primary/tertiary 实心。
- **改动(2 行,几何与尺寸未动)**:①`ui/components/CapsuleSegmentedButton.kt` 的 `Track` 分支:段容器由 `Color.Transparent` 改 **`MaterialTheme.colorScheme.surfaceBright`**(选中仍走 M3 `ToggleButton` 默认的 primary 实心,两态同时有底色才对得上音乐页);②`ConfigManagePage.kt` 调用点补 **`containerColor = MaterialTheme.colorScheme.surfaceContainer`**(原来吃组件默认值 `surfaceContainerHighest`)。段的 `TrackShape = RoundedCornerShape(percent = 50)` 四角全圆 ⇒ 段本身就是"子弹头",与用户描述一致。
- **影响面核对(照 §5 的教训:改本组件必须逐页看一眼)**:全仓 `SegmentStyle` 仅三处调用 —— 配置管理页(`Track`,本次就是改它)、主题设置页(`Connected`,不显式传 `containerColor`,走 M3 默认)、搜索设置 sheet(`Separated`,显式传 `containerColor = surfaceBright`);且 `Track` 分支只有配置页在用 ⇒ **不会**复现 2026-09-12「轨道样式一度全局生效、主题设置页观感变差」的问题。
- **有意保留的差异**:内缩/段间距仍是 4dp(音乐页是 8dp)—— 配置页这个控件是"标题 + 源名"两行紧凑文字、两行内容总高 48dp;要更接近音乐页的比例只需改 `TrackPadding` / `TrackSegmentSpacing` 两个常量。
- **验证**:`:app:compileDebugKotlin -q` 退出码 0;`:app:testDebugUnitTest` **174 用例 / 0 失败**;`:app:assembleDebug` 成功并已 `adb install -r` 装机(设备回读 `lastUpdateTime` 与装机时刻一致)。**真机待验**:①外层是浅灰(`surfaceContainer`)全圆角胶囊,不再是原来的 `surfaceContainerHighest`;②**未选中段(直播)也有 `surfaceBright` 白底子弹头**(原来透出轨道底色),选中段仍是 primary 实心;③按压回弹(0.94)与"无阴影"观感正常;④**主题设置页的三段选择器外观不应有任何变化**(回归检查点)。

## 直播侧补齐多仓(仓库)支持(2026-09-21)

- **需求(用户)**:「除了自动换仓,其他都做了,参考 fongmi」—— 即补齐前面盘出的两个缺口:①直播侧识别多仓配置;②仓选择器。参考实现 = FongMi/TV `fongmi` 分支的 `VodConfig.parseDepot` / `LiveConfig.parseDepot`(以及 `bean/Depot`),而不是本地 `示例文件/上游项目`(q215613905/TVBoxOS)—— 后者只在 `95eccf4`「兼容多仓配置」里做了点播侧一半,直播侧至今没有。
- **改前的实际症状**:直播源填的是仓地址(顶层只有 `urls`、没有 `lives`)时,`parseLiveConfigContent` 走 `isLiveJsonContent` → `parseLiveJson`,`infoJson.has("lives")` 为假 ⇒ 频道分组为空 ⇒ `hasLiveConfigResult()` 为假 ⇒ 用户看到「直播配置解析失败」,仓里的源一个都进不来。
- **改动(8 文件)**:
  - 新增 `bean/Depot.java`:对齐 FongMi 的 `{url,name}`,但**手写遍历**而不走 Gson 直接映射 —— 本项目配置生态里 `urls` 有三种写法(`{"url":…}` / `{"api":…}` / 裸字符串),Gson 映射会把裸字符串整条丢掉(老写法,不能丢)。判空用**本地 `isEmpty` 而非 `android.text.TextUtils`**(见下"踩坑")。
  - `ConfigParser`:抽出公共判定 `isDepotJson(JsonObject)`(顶层有 `urls` 数组、**无 sites**、数组非空),`parseApiCollection` 改为复用它 + `Depot.arrayFrom`;点播/直播共用同一条判定,避免两边对"什么算仓"分叉。
  - `ApiConfig` 新增 `switchLiveApiCollectionIfNeeded(apiUrl, json)` / `clearLiveApiLinesIfUnmatched` / `clearLiveConfigResult`,并接进 `loadLiveConfig` 的**三条**取数路径(命中缓存、网络成功、网络失败回落缓存),与点播 `switchApiCollectionIfNeeded` 完全同构。
  - `HawkConfig` + `KVKeySpec`:新增 `LIVE_API_LINE_LIST` / `LIVE_API_LINE_SOURCE` 两个键;⚠️ 集合键**必须**在 `KVKeySpec` 显式登记元素类型,否则读回来退化成 `LinkedTreeMap`(该文件里已有 LIVE_WEB_HEADER 的前车之鉴)。
  - `HistoryHelper`:补 `isLiveApiLineUrl` / `isLiveApiLineSource` / `isLiveApiLineHistory` / `getLiveApiLines` / `clearLiveApiLineList`,与点播那四个判定一一对应。
  - `ApiConfig.refreshLiveApiHistoryItems` + 新增 `getLiveConfigEntries` / `getLiveConfigUrls` / `getLiveApiHistoryUrl(position)`:仓模式下第 1 项起列**仓里的子源**、否则列配置历史;同时修掉一个既有缺陷 —— 原实现把 `"名字\t链接"` 整行丢给 `LiveSettingsRules.currentConfigIndex` 做 `indexOf(当前地址)`,永远匹配不上 ⇒「配置切换」当前项不高亮(点播侧早就先 `getApiLineUrl` 剥过一层,直播侧漏了)。
  - `LivePlayViewModel`(第 6 组点击)/ `LivePlayActivity`(选中下标、长按删除)/ `ConfigManagePage`(`applyVodSource` / `applyLiveSource` / `applyLiveFollowVod`):改走上面几个访问器;换到仓列表之外的地址、或回到「跟随点播源」时**作废直播仓列表**,避免组里继续列出上一仓的子源。
- **与 FongMi 的一处刻意差异(为什么不做成"仓落库")**:FongMi 把每条仓 `Config.find(item, LIVE)` 写进配置表,于是"仓列表"就是现成的配置列表、不需要额外 UI;本项目直播源是 `LIVE_API_URL` 单值 + 独立的配置历史,没有等价的多配置表。改造成本高且会动到点播/直播拆分的既有语义,所以**沿用本项目既有的"仓列表 + 平行 KV"模型**(与点播 `API_LINE_LIST` 同构),保证点播/直播两侧行为一致、改动面最小。
- **踩坑(值得单记)**:首轮 `parseApiCollection` 的 4 个既有单测全红,现象是"空地址条目不丢、裸字符串条目丢"。真因有两个:①`getName()`/`getUrl()` 里 `name.trim()` 在 `name == null` 时抛 NPE,被 `catch (Throwable)` 吞掉后**静默丢弃后面所有条目**(逐条 `try` 修复);②`android.text.TextUtils.isEmpty` 在单测里**静默返回 false** —— 工程开了 `testOptions.unitTests.returnDefaultValues = true`,Android 桩方法全返默认值,于是"空地址过滤"真机生效、单测失效。这个坑 `ConfigParser` 的注释里已记过一次,本次是第二次;`Depot` 因此改用本地 `isEmpty` 并写了 `DepotTest` 钉死。
- **验证**:`:app:testDebugUnitTest` **183 用例 / 0 失败**(基线 174 + 新增 `DepotTest` 7 例 + `ConfigParserTest` 新增 `isDepotJson` 与坏 name/空地址 2 例;`KVKeySpecTest` / `LiveSettingsRulesTest` 全绿)。**真机待验(测试机由用户操控)**:①直播源填仓地址时应直接播到仓里第一条的频道,不再报「直播配置解析失败」;②打开直播设置「配置切换」,第 0 项仍是「跟随点播源」、其后应为**仓里的子源名字**,点其他仓应切换并回到同一个频道;③切到仓外的直播源后,该组应回到配置历史(不再列出旧仓子源);④仓模式下长按应提示「仓列表来自仓地址,不能单独删除」而不是删掉一行;⑤点播多仓(原有的「接口线路」)行为不变 —— 这是本次的回归检查点。

## 多仓两处真机暴露的缺陷修复(2026-09-21 同日二轮)

- **用户实测反馈(带截图)**:①「没看到你说的入口」—— 切到仓之后找不到切换仓的地方;②「切换到多仓的源后退出配置管理页面再进去,全部源都是关闭的状态」。
- **缺陷②真因(数据对不上号)**:配置管理页判"这一条源正在使用"是 `item.url == activeUrl`,而点播多仓加载会把 `API_URL` **改写**成仓里第一条子源的地址(`ApiConfig.switchApiCollectionIfNeeded`)⇒ 订阅列表里那条**仓地址**永远匹配不上当前地址 ⇒ 退出重进后所有卡片显示为未使用。同一处判定还散在列表卡片的 `inUse` 里(比 `isInUse` 还多一处),两处都漏。
  - **修法**:`HistoryHelper` 新增 `isApiLineSourceOf(url, activeUrl)` / `isLiveApiLineSourceOf(url, activeUrl)` —— 地址本身命中 **或** 它正是"当前仓的来源地址"(`API_LINE_SOURCE`,并要求仓确实处于生效态以免清场后残留误判);`isInUse` 与卡片 `inUse` 两处都改走它。顺带修掉一个连带症状:点了带"使用中"标记的仓卡,之前会因误判走完整换源流程,现在会正确短路。
- **缺陷①真因(状态只在构造时读一次)**:`SettingsState` 由 `SettingsViewModel.loadState()` 在 **ViewModel 构造时**读一次 KV,之后没有任何刷新钩子;而"当前源来自仓"是**异步**完成的 —— 切完源立刻退回设置页时 `API_LINE_LIST` 仍为空、`API_URL` 仍是仓地址 ⇒ `apiLineVisible` 为假 ⇒「接口线路」这行**永远不出现**(页面状态再也不会更新)。所以用户按文档去设置 tab 找,确实找不到。
  - **修法**:`SettingsPage` 补两条幂等刷新 —— ①`LifecycleEventEffect(ON_RESUME) { vm.refresh() }`(从配置管理页返回会触发宿主 Activity 的 ON_RESUME);②`AppBootstrap.state` 变 `Ready` 时再刷一次(多仓改写发生在 boot Ready 之前,这条覆盖"人已经停在设置页、加载才完成")。
- **仓库地址的形态提醒(排查用)**:用户截图里的仓地址是 `https://hk.gh-proxy.org/...`,该域名的根路径返回的是 HTML(实测会 302 到 `gh-proxy.com` 的网页),**必须是能返回 JSON 的具体地址**才可能被识别为仓;若填的是代理站根地址,取到 HTML ⇒ 不是 JSON ⇒ 既不会切到首仓、也就没有「接口线路」。
- **验证**:`:app:testDebugUnitTest` 183 用例 / 0 失败;`:app:assembleDebug` 通过并已 `adb install -r` 重装(设备回读 `lastUpdateTime` 晚于 APK mtime)。**真机待验**:①切到仓源后**退出配置管理再进来**,那张仓卡应仍带"使用中"开关(不再全部关闭);②从配置管理返回后进设置 tab,应出现「接口线路」行且值为仓里当前子源的名字;③点它可以换到同仓的其它子源。

## 配置管理页「换仓」入口(2026-09-21 同日三轮)

- **需求(用户)**:"换仓的入口放在配置管理页面的右上角,增加一个控件,icon 用 `.tubiao/换仓.svg`,点击弹出 bottom sheet"。
- **背景**:多仓生效后启动地址被改写成仓里的某个子源,订阅卡与「使用中」都不再指向用户当初填的仓地址 ⇒ 之前换仓只能去**直播播放页的「配置切换」组**或**设置 tab 的「接口线路」行**,而后者还依赖异步加载完成(见上一条缺陷①)。放在配置管理页是顺手的位置:用户本来就在这页管源。
- **图标**:`.tubiao/换仓.svg` 与既有 `ic_edit.xml` 的 path **逐字符相同**(都是 Material 编辑铅笔字形),但仍按用户指定新建 `res/drawable/ic_switch_repo.xml` 单独存放 —— 外观要调整时只改这一个文件,不影响管理模式的「编辑」图标;沿用本仓约定(viewBox 960 + `<group android:translateY="960">` 平移适配 VectorDrawable,`fillColor="#FFFFFFFF"` 由 `TopBarActionBox` 的 tint 覆盖)。
- **改动(3 文件 + 1 资源)**:
  - `ConfigManagePage.kt`:①顶栏常态分支由单个圆钮改 `Row`(8dp 间距),`canSwitchRepo` 为真时在「添加」左侧插「换仓」圆钮;②新增 `repoSheetOpen` 状态与 `RepoSwitchSheet` composable;③新增三个只读派生值 `canSwitchRepo` / `repoEntries` / `repoActiveUrl`。
  - `HistoryHelper.java`:补 `getApiLines()`(点播仓列表),与已有的 `getLiveApiLines()` 对称。
  - `res/drawable/ic_switch_repo.xml`:新图标。
- **可见条件(刻意收窄)**:仅当**当前源来自多仓**才显示 —— 点播看 `isApiLineUrl(activeUrl)`、直播看 `isLiveApiLineMode() && isLiveApiLineUrl(liveActiveUrl)`。不是仓源时没有可换的子源,按钮出现只会让人白点一次。
- **数据不另建状态**:列表直接取「配置切换」组用的同一份仓列表(`HistoryHelper.getApiLines()` / `getLiveApiLines()`),避免两处各维护一套仓状态(这是本类改动反复踩到的坑)。
- **切换语义复用**点播 `switchToVod` / 直播 `switchToLive`:仓列表归属判定(`isApiLineHistory`)已在其中,所以换完仓后入口仍在,不用额外处理。
- **关闭手势的一个坑**:`LocalSheetDismiss` 提供的 `dismissAnimated()` 是**动画播完才回调 `onDismiss`**(实现在 `SheetOverlay.dismissWithAnimation`)。所以点击项时**只调它、不要再自己置 `repoSheetOpen = false`** —— 否则面板先被拆掉、退出动画直接没有。与 `AVBoxOptionSheet` 的写法保持一致,并同样用 `accepted` 防连点。
- **验证**:`:app:assembleDebug` 退出码 0;`:app:testDebugUnitTest` **190 用例 / 0 失败**(本次纯 UI + 一个 getter,无新增单测面)。**真机待验**:①切到仓源后配置管理页右上应出现两个圆钮(换仓 + 添加);②点换仓弹 bottom sheet,列出仓里子源、当前项打勾、每条带地址;③点其它子源能切换且 sheet 播动画关闭、重开后选中项跟着变;④非仓源时该按钮不出现;⑤管理模式(长按卡片)下右上仍是「编辑/删除」,不受影响。

## 启动看门狗:让坏源不再把应用锁死在崩溃循环(2026-09-21 同日四轮)

- **需求(用户)**:「所有导致闪退的原因是源什么」+「会闪退两次才弹窗 toast 禁用源」+「触发了闪退,再进应用还是闪退,根本没法切换源,只能清除数据」。
- **真机实测到的根因(逐条有证据)**:
  - 崩溃栈 `UnsatisfiedLinkError: dlopen failed: ".../files/TV/.libwexproxy…" has bad ELF magic: 3c3f786d`,`at com.github.catvod.spider.GoProxy.<clinit>`;
  - 把那个"库"从设备掏出来看,内容是 313 字节的**XML 报错**:`<Error><Code>NoSuchKey</Code><Resource>/ysf/cf005a….txt</Resource></Error>`;
  - 即:第三方源附带的 spider jar 在静态初始化里从云存储下载 `libwexproxy.so`,**远端对象已被删除**,CDN 返回报错页,爬虫不校验就把报错原文当 `.so` 落盘再 `System.load`。`3c3f786d` = ASCII 的 `<?xm`。
  - 看门狗 KV 里 `boot_vod_source` / `boot_loading_jar` 坐实触发源 = **饭太硬**(`https://www.饭太硬.cc/tv`),jar = 该源配置里的 `csp/81a001fc54e256c003235b33688083ee.jar`;另一个源(王二小)后来也复现同一崩溃,说明是这类加固型 spider 的共性问题。
- **为什么它会自锁(本轮最关键的观察)**:源地址是持久化的,而该爬虫**每次冷启动都重新下载**(实测清理后 2 秒内又下一遍)再 load ⇒ 进一次崩一次,用户连"换源"都做不到。而 `System.load` 跑在爬虫自己的线程上,**不在我们的调用栈里,try/catch 接不住**。
- **机制(三轮迭代才成立,每轮都写了为什么上一轮不行)**:
  1. `FileUtils.repairBogusNativeLibs()` —— 启动时扫私有目录,凡"名字像原生库(`*.so` / `.lib*`)但 ELF 魔数不符"的文件一律删掉。判据刻意收窄(绝不按大小/时间猜),合法库与非库资源(`.wexstring`/`.wexcofig.json`)一个不碰。**只对"上次留下的自锁"有效**。
  2. `BootGuard` —— 记"正在加载哪个 jar + 此刻哪个源是启动源",进程级 `UncaughtExceptionHandler` 记崩溃,下次启动在加载任何 jar 之前判定:同一源**在启动加载阶段崩过 ⇒ 一次即停用**;否则累计装载 3 次停用。停用只清启动指针 + 仓列表,**不动订阅列表**(用户可在配置管理页重新启用或改选别的源),并弹一次 toast 说明。
- **三轮失效原因(都留在代码注释里,避免以后重犯)**:
  - **第一版**:jar 装载成功即清计数。实测爬虫 `GoProxy.<clinit>` 在**另一个线程**,装载线程先报成功、**28 毫秒后**才崩(08:01:25.512 / 08:01:25.540)⇒ 早清等于擦掉唯一证据 ⇒ 阈值永远凑不满。改由"连续存活满 10 分钟"才清。
  - **第二版**:崩溃时刻写 KV(`KV.putSync`)。实测 MMKV 是**异步写**、2.4.2 **没有同步写 flag**(只有 `SINGLE_PROCESS_MODE` 等模式位),设备上 `boot_last_crash_at` 一直停在几分钟前 ⇒ "启动阶段崩一次即停用"从未成立。改走**同步标记文件** `files/boot_crash.marker`(内容 = 崩溃时的 `elapsedRealtime`),判定全程用同源的开机计时比较。
  - **第三版(本轮审查修掉)**:`takeCrashMarkerElapsed()` 读完即删,而判定后**又调了一次** `crashedDuringStartup()` 重读同一文件 ⇒ 日志里 `startupCrash=` 恒为 `false`(判定本身没错,但排查会被带偏)。改为只算一次、把结果传进纯函数 `shouldDisable(jar, count, crashElapsed, startupCrash)`。
- **本轮审查(用户要求"审查是否有错误遗漏与新回归")另修 7 处**:见下一条"审查修复"。
- **验证**:`:app:testDebugUnitTest` **199 用例 / 0 失败**(基线 174 + `DepotTest` 7 + `FileUtilsNativeLibRepairTest` 8 + `BootGuardTest` 10);`:app:assembleDebug` 退出码 0。真机侧仅观察到"启动不再因该源自锁"(用户确认「没问题了」),其余为静态审查结论。

## 审查修复:本次改动里的 10 处缺陷(2026-09-21 同日五轮)

- **背景**:用户要求「审查一下本次对话增加的内容是否有错误遗漏和引入新回归」。逐文件通读 + 编译 + 单测复核,发现并修掉 10 处,其中最严重的两条**只有靠新写的单测/真机数据才暴露**。
- **① `BootGuard` 里 `TextUtils.isEmpty` 判空失效(高)**:单测开了 `returnDefaultValues`,`TextUtils.isEmpty("")` **静默返回 false** ⇒ "空 jar 不停用"这条守卫在单测里失效,实现里也随之失真。是**新写的 `BootGuardTest` 当场抓到的**。改局部 `isEmpty`。这是本仓第三次踩同一个坑(`ConfigParser` 注释里记过一次、`Depot` 是第二次),三次的注释现在互相引用。
- **② 崩溃记录写 MMKV 会丢(高)**:见上一条轮次说明。改同步标记文件。
- **③ 崩溃标记被读两次(中)**:同上。改"只算一次"。
- **④ `applyVodSource` 误清独立直播的仓列表(中)**:原写法 `if (followLive) clearLiveApiLineList()` 无条件清 ⇒ 用户"直播是独立仓源 + 点播换到别的源"时会把独立直播仓弄丢(直播设置「配置切换」组退回配置历史)。加 `item.url != oldFollowTarget` 守卫(跟随态下"直播当前跟着谁" = `LIVE_API_URL.ifEmpty { API_URL }`)。
- **⑤ 停用源时留下"列表非空但地址为空"(中)**:`disableRecordedSource` 只清 `API_URL`/`LIVE_API_URL`,没清仓列表 ⇒ 与 `clearVodConfig()`/`clearLiveConfig()` 的清场口径不一致,「配置切换」会在无源时列出已失效子源。补 `clearApiLineList()`/`clearLiveApiLineList()`。
- **⑥ 设置页多跑一次缓存目录全量遍历(低,性能回归)**:我把 ON_RESUME 的 `refreshCacheSize()` 换成了 `refresh()`(内含 `getCacheSize()` 的整树递归),且 boot Ready 时又刷一次 ⇒ 白跑。拆出 `refreshState()`(只重读 KV),缓存大小仍只在 ON_RESUME 刷一次。
- **⑦ 停用阈值与文档不一致(低)**:`count >= MAX_LOAD_ATTEMPTS` 让 `MAX_CRASH_ATTEMPTS` 成为死常量,且 `slowCrash_disablesOnSecondAttempt` 等用例与实现对不上(被单测抓到)。删死常量、统一到 `MAX_LOAD_ATTEMPTS`,并把阈值从 4 收到 3(一次正常启动同源装载 1~2 次,3 仍有区分度、又不必多崩一次)。
- **⑧ 原生库自检日志 `size=0` 恒为 0(低)**:删完再读 `length()` 必然 0,丢掉了"当初坏文件多大"这条排查信息(实测这个值就是 313,直接指向报错页)。改为删前先记大小。
- **⑨ `KV.putSync` 无调用方(低,死代码)**:崩溃通道改文件后它没用了,删除,并把为它拆出的 `putInternal` 还原成 `put`。
- **⑩ `BOOT_LOAD_START_AT` 成为只写不读的死键(低)**:崩溃判定全程用 `elapsedRealtime`,墙钟键没意义。删除,只留 `BOOT_LOAD_START_ELAPSED`;`KVKeySpec` 登记同步更新。
- **验证**:`:app:testDebugUnitTest` **199 用例 / 0 失败**;`:app:assembleDebug` 退出码 0。
- **仍存的已知局限(刻意保留,非遗漏)**:①看门狗是**进程级兜底**,爬虫远端一天不修好、该源就一天不可用(会被自动停用);②`repairBogusNativeLibs` 对"本次启动才下载的垃圾"无效(只打破上次留下的自锁);③停用不删订阅列表,用户可重新启用同一个(仍坏的)源、会再次被停用;④换仓入口与原生的「配置切换」列仓列表只做了编译 + 单测,未实机点过。

## 第二轮审查:又两处缺陷 + 一条"假崩溃"结论(2026-09-21 同日六轮)

- **背景**:用户报「刚刚好像产生了崩溃」,并要求继续审查。先读崩溃缓冲(只读,不动设备)定性,再做静态审查。
- **崩溃日志定性:不是新回归,而是"坏源不止一个"**。时间线:`08:25:23` 崩溃(触发源 = **潇洒** `https://qist.wyfc.qzz.io/xiaosa/api.json`)→ `08:25:26` 看门狗日志 `native-lib-repair removed bogus …/libwexproxy.so size=313` + `boot-guard: disable looping source attempt=2 startupCrash=true` → 用户切到饭太硬后又崩 `08:25:41/44` → `08:25:47` 再次自动停用。三点结论:
  - ①`size=313` 说明上一轮修的"删前先记大小"生效了(原来恒为 0),而 **313 正是"CDN 报错页"的字节数**,是根因的直接证据;
  - ②`startupCrash=true` 说明**崩溃标记文件的同步写/读链路打通了** —— 这正是第二轮修复前一直丢掉的那一环;
  - ③触发源换成了潇洒,但 jar 仍是饭太硬系那个 `csp/4b06c53fc96931d4b2e0330ef420600f.jar` ⇒ **饭太硬系(含潇洒)共用同一个带 `GoProxy` 的加固 spider**,所以"换另一个源"照样崩。
- **⑪ 换仓 sheet 点"当前已选中"那一条时关不掉(中,UX 死角)**:`RepoSwitchSheet` 的 `dismissAnimated()` 写在 `if (!accepted && url.isNotEmpty())` 守卫里,而选中当前项时切换逻辑(`switchToVod`/`switchToLive`)会直接 return —— 于是面板**卡在那里关不掉**,看起来像卡死。改为"先无条件吃掉点击 + 关面板,再只在地址有效且与当前不同时才切换";既修掉死角,也顺手不再为"点自己"白跑一遍换源流程。
- **⑫ 切换点播/直播分段时换仓 sheet 不关(低)**:`LaunchedEffect(mode)` 原本只重置 `manageMode/selected/editTarget`。sheet 列的是"当前模式那份仓列表",切模式后台面下的列表已换 ⇒ 补 `repoSheetOpen = false`(分段按钮在遮罩下点不到,防的是返回键先关 sheet 这一类时序)。
- **本轮逐调用点核对(未发现新问题)**:`isApiLineSourceOf(url, activeUrl)` / `isLiveApiLineSourceOf` 的全部 4 个调用点(`isInUse` 点播/直播分支 + 卡片 `inUse` 点播/直播分支)**实参逐一比对,均传"当前生效地址"**(点播 `activeUrl`、直播 `liveActiveUrl`),没有把两者对调;`getLiveConfigUrls()` / `getLiveApiHistoryUrl()` 的 3 个调用点一致。
- **一条能力边界(说明,非缺陷)**:这两轮修的 `HistoryHelper` 仓判定与 sheet 关闭**无法进纯 JVM 单测** —— 它们都读 KV,而 `KV.init` 依赖 `MMKV.initialize(Context)`,工程无 Robolectric、单测又开了 `returnDefaultValues`。因此这两处只能靠"逐调用点核对 + 编译 + 真机",已在上条写明核对结果。**当前能测的纯逻辑都有测试**:`Depot`(7)、`BootGuard` 决策(10)、`FileUtils` 原生库自检(8)、`ConfigParser` 多仓判定(2)。
- **验证**:`:app:testDebugUnitTest` **199 用例 / 0 失败**;`:app:assembleDebug` 退出码 0。本轮**未对设备做任何写操作**(仅 `logcat -b crash -d` 与 `run-as cat` 只读读取)。

## 播放器两处「旧内容残留」缺陷修复(2026-09-21 同日七轮)

用户真机反馈两个现象,读码定位到两处独立缺陷,根因同源:**「挂载视图」早于「确定会话」 + 退页面只停到 PAUSED 不清内容**。

**① 音乐页退出 → 进直播,一瞬间有音乐声**
- 链路:退出音乐页 `PlaybackEngine.detach()`(`:392-425`)先 `pause()` 再调 `stopPlaybackKeepPlayer()`,而 `VideoView.stopPlaybackKeepPlayer()`(player 模块 `:430-438`)**首句就是 `if (mCurrentPlayState == STATE_PAUSED) return;`** ⇒ 内核留在 PAUSED + 旧媒体项(这是 D6 同片接管刻意要的"可复用态")。
- `enterLive()`(`:230-262`)对旧内容只有 `if (videoView.isPlaying()) videoView.pause();` ⇒ 已 PAUSED 时**是空操作**;对比 `enterLiveState()` 写的是 `videoView.release()`,**两条直播入口不对称**。
- `LivePlayActivity.onResume()`(`:224-235`)→ `enterLiveState()` 因 liveMode 已 true 返回 false → 走 `mVideoView?.resume()`;`VideoView.resume()`(`:373-410`)判据 `isInPlaybackState() && !isPlaying()`,PAUSED 正好命中 ⇒ **音乐复活**,直到频道列表就绪后 `playChannel()` 的 `releasePlayerKernel()` 把它顶掉。
- "有概率"的来源 = 列表是否需异步加载:`ApiConfig.shouldReloadLiveConfig()`(空 / URL 变)或 127.0.0.1 代理源走 `loadLiveConfigOnEnter()` / `loadProxyLives()` 时才有这段网络等待窗口;列表已缓存则 `playChannel` 在 onCreate 内同步跑完,听不到。
- **修复**:`enterLive()` 改用 `releasePlayer()`(与 `enterLiveState()` 对齐)。释放放在 `setProgressManager(null)` **之前** —— 那一刻进度键还是旧内容的,正好把它的观看位置落盘。`LivePlayActivity.onResume()` 只补一行注释记录不变量(此处只可能恢复直播流),不加多余守卫。

**② 影视页退出 → 进新影视页,加载时闪上一部画面**
- 链路:`PlayContainer` 构造函数(`:96-108`)在**详情数据还没到**时就 `engine.attach(this)` → `VideoView.attachContainerTo()`(`:923-934`)把还带着上一部 SurfaceView 的 `mPlayerContainer` 搬进新页槽位;搬运触发 surfaceDestroyed/surfaceCreated,`SurfaceRenderView.surfaceCreated()`(app `player/render/` `:92-96`)**无条件** `mMediaPlayer.setDisplay(holder)` → `ExoMediaPlayer.setDisplay()`(`:246-251`)→ media3 `setVideoSurface()`;此时内核里还是上一部内容(PAUSED/PREPARED、解码器在),**media3 会把最后一帧重渲染到新 surface** ⇒ 闪上一部画面。
- 竞态:与随后 `PlaybackController.play()`(`:1377-1379`)`reusePlayer=false` 分支的 `view.releasePlayer()` 谁先谁后,取决于详情数据秒回还是要等网络 ⇒ "有概率"。另一佐证:`play()` 里**只有 reusePlayer 分支**才 `view.clearVideoFrame()`(`:1371-1376`),换片路径全程无遮黑帧动作。
- **修复**:`MyVideoView` 新增 `coverVideoFrame()`(只加黑遮罩、**不停内核** —— `clearVideoFrame()` 内部 `mMediaPlayer.stop()` 会破坏 D6 续播,不能复用),`attach()` 搬容器前 `if (!videoView.isPlaying()) coverVideoFrame()`。
- **为什么必须带 `!isPlaying()` 条件**:正在播的内容属于"本次接管"(音乐页交接 / 页面返回),遮了没人来揭就是永久黑屏;揭开统一靠既有 `STATE_PLAYING` 回调 `showVideoFrame()`(纯音频走 `hideVideoFrameCover()`)。另核对过 z-order:遮罩与海报的层级在 `releasePlayer()` 之后的常规路径里不会互相盖住(此时容器只剩遮罩,后续 `addDisplay()` 插 index 0、`setArtwork` 按 `min(1, childCount)` 追加,都在遮罩之上)。
- **顺带修掉自己引入的第二处漏洞**:揭遮罩的代码原本写在引擎状态回调的 `if (liveMode) return;` **之后**,而直播页共用同一块容器 ⇒「attach 时遮了黑 → 一直没起播 → 回直播页 → `enterLiveState()` 重播频道」这条路上 `STATE_PLAYING` 会被 liveMode 短路掉,**遮罩永远没人揭 = 直播有声无画**。修法不是再补一处调用,而是把揭遮罩提到模式短路**之前**(它本来就与点播/直播无关)。核对依据:`VideoView.setPlayState()` **不去重**(`start`/`resume`/`replay` 每次都会发 `STATE_PLAYING`,故必有一条揭开路径);`isConfirmedAudioOnly()` → `currentTrackInfo()` 是只读 + `try/catch`,直播下调用安全。
- **`resume()` 调用点全量审计(收口"谁能唤醒旧内容"这一面)**:全工程共 4 处 —— `PlayContainer.hostResume()`(`lifecyclePaused` 守卫)、`MusicPlayerActivity.hostResume()`(`lifecyclePaused` 守卫)、引擎 `HeadlessView.hostResume()`(无实际调用方,死代码)、`LivePlayActivity.onResume()`(**唯一无守卫**,其安全性现依赖「`enterLive()` 必把内核置为 IDLE」这个不变量,已在该处写注释锚定)。另核了直播页 6 处 `start()`(`:285` / `:522` / `:880` / `:996` / `:1016` / `:1055`):前 5 处一律先 `setUrl` 再 `start`(不可能唤醒残留内容),第 6 处是时移播放开关(仅在直播流在播时可达)⇒ **结论是不加"多处打标记"式守卫** —— 那种守卫要求 6 个调用点各自记得置位,本身就是新的漏点来源;正确做法是在源头(`enterLive()`)保证不变量。
- **自查复审(第三轮)发现并修掉:遮罩盖住控制器**。`setVideoController()` 是把控制器 `addView` 进 `mPlayerContainer` 的,而 `PlayContainer` 构造函数里 `initView()`(挂控制器)在 `engine.attach()`(加遮罩)**之前** ⇒ 遮罩被追加到控制器之上,加载期顶栏/手势层/直播控制层全被盖住。修法:`showFrameCover()` 里 `if (mVideoController != null) mVideoController.bringToFront()`。**为什么不用按 index 插入**:`addDisplay()` 永远把渲染视图插到 index 0,而"渲染视图尚未创建"(全新引擎 + 新详情页,容器里可能只有控制器)时 index 会算错位,`bringToFront` 不依赖顺序。
- **复审确认无问题的点(有据可查,非"看起来没问题")**:① 详情页的加载遮罩与 `PlayerTipOverlay()` 都在 `AndroidView(container)` **之后**绘制 ⇒ 在容器之上,遮罩盖不到提示;② 直播页换台快照是 Compose 层 `Image(bitmap)`,不走 `setArtwork` ⇒ 揭遮罩时新增的 `clearArtwork()` 不会误清它;③ `enterLive()` 里 `releasePlayer()` 置于 `setProgressManager(null)` 之前 ⇒ 旧内容位置按旧键正确落盘,且 `release()` 内部的 `saveProgress → markPlaybackStarted/hideTip` 在无页面桥下是空操作;④ `enterLive()` 与 `enterLiveState()` 都 release ⇒ **`liveMode == true` 现在蕴含"旧内容已释放"**(比改前更强的不变量);⑤ `attach()` 的 `!isPlaying()` 守卫不可去掉 —— 去掉会让"音乐页交接后返回详情页"(内容在播、不会再发 `STATE_PLAYING`)永久黑屏。
- **原先标注的"已知遗留"经核实为不可达,故不改**:「直播正在播 → 打开点播详情页」要求直播流在直播页不在前台时仍在播,而这是走不到的 —— `exitingLivePlay` 只在返回键 `finish()` 路径置 true(`:205`),该路径必然经 `onDestroy → exitLive() → release` 把内核置 IDLE;其余离开方式(home/切后台)`onPause` 会 `pause()`(`:240`)。两种情况下 `attach()` 时 `isPlaying()` 都是 false ⇒ 遮罩照常生效。`DetailActivity` 的唯一入口是 `ui/page/Jump.kt`(首页/搜索的卡片点击),直播页内没有跳点播详情的入口。**若将来新增"直播 → 点播详情"的入口,这条会重新成立**,届时需连同"同片接管分支(`isSamePlaybackOwned`,不发 `STATE_PLAYING`)补显式揭开"一起做。
- **遗漏排查**:确认全仓只有一份 `PlayContainer` / `LivePlayActivity` / `MyVideoView` / `PlaybackEngine`(`app/src/main`;`python`/`test` 是另外两个源集,无 flavor 变体)⇒ 不存在"改漏了一份实现"。

**验证**:`:app:compileDebugJavaWithJavac` + `:app:compileDebugKotlin` 通过;`:app:testDebugUnitTest` **199 用例 / 0 失败**;`:app:assembleDebug` 退出码 0(`AVBox_debug.apk` 已产出)。**未装机** —— 改动落在播放器/引擎层,现有纯逻辑单测(Depot/BootGuard/FileUtils/ConfigParser)覆盖不到。
**环境坑(本轮又踩,补充到环境结论)**:`gradle` 直跑时 `:pyramid:installDebugPythonRequirements` 会失败(报 `Process ... python.exe finished with non-zero exit value 1`,并伴随 `[safe-delete] SAFE_DELETE_BULK_CONFIRM_REQUIRED`);需 `export PATH` 带上系统 Python 3.10 并用 `-x :pyramid:installDebugPythonRequirements` 跳过。
**遗留**:①的窗口已消除,但 `VideoView.stopPlaybackKeepPlayer()` 的 PAUSED 早退语义本身没动(它服务于 D6 同片接管);后续若再出现"退页面后旧内容被谁恢复出来"的类同问题,优先查**新页面有没有无条件 `resume()`**。

## 「换仓」入口要退出重进才出现(2026-09-21 同日八轮)

- **现象(用户问)**:「添加好多仓源后,是不是要退出配置管理页面再进去才会在右上角显示换仓控件?」—— **是**,读码确认。
- **根因一:判定用的地址与那一刻的本地快照对不上**。`ConfigManagePage.kt` 的 `canSwitchRepo`(点播)= `HistoryHelper.isApiLineUrl(activeUrl)`,而 `isApiLineUrl` 只在 `API_LINE_LIST` 里比对**仓内子源**地址。刚添加完时 `activeUrl` 还是**仓地址本身** —— `switchToVod()` 里 `activeUrl = item.url` 是**同步**赋值,早于异步改写 ⇒ 恒为 false。
- **根因二(更本质):异步改写不触发重组**。`ApiConfig.switchApiCollectionIfNeeded()`(`:507-532`)在异步 loadConfig 里把 `API_URL` 由仓地址改写成仓内首条子源、并写入 `API_LINE_LIST`/`API_LINE_SOURCE`,**全程不产生任何 Compose 状态变化**;而 `activeUrl` 是 `remember { mutableStateOf(KV.get(API_URL)) }`,只在首次组合读一次,页面里两个 `LaunchedEffect` 也不监听配置加载 ⇒ 不重组。退出重进之所以能好,是因为 `ConfigManageActivity` 是独立 Activity,重建 `ComposeView` 让 `remember` 重跑。
- **顺带确认的同类漏改**:`repoEntries`(仓列表)也是直读 `HistoryHelper.getApiLines()` 的普通值;`isInUse` 的「使用中」标记同样依赖 `activeUrl`,所以切到仓后**页内**所有源都显示未使用(代码注释 `:216-217` 原本只解决了"重进页面"这一半)。
- **为什么需要两条刷新通道(而不是一条)**:
  - 点播仓的改写就在**本页后台**完成(`AppBootstrap.onApiUrlChanged()` → `retry()`),所以必须靠 `AppBootstrap.state` 落地 `Ready` 时重读 —— **已核 `AppBootstrap.startInit` 顺序:`awaitLoadConfig()`(含改写)→ `awaitLoadJar()` → `_state.value = Boot.Ready`,改写先于 Ready**;
  - 直播仓的改写发生在**直播页**拉取配置时(`applyLiveSource()` 只 `invalidateLiveConfig()`,真正拉取要等进直播页),所以要靠 `LifecycleEventEffect(ON_RESUME)`;这条同时覆盖"从本地文件选择器返回"。
- **改动**(`ConfigManagePage.kt` 单文件):新增 `refreshActiveSnapshot()`,只重读 `activeUrl`/`liveActiveUrl`/`liveFollow` 三份"当前态",再由上面两条通道各调一次。**刻意不重读 `vodItems`/`liveItems`**:订阅列表的增删改都同步写 KV,本地值不会与 KV 分叉,重读不会带来新信息,反而会与 `manageMode` 的 `selected` 勾选集错位。
- **刻意否掉的方案**:把可见条件放宽成 `isApiLineSource(activeUrl) || isApiLineUrl(activeUrl)`。这能绕过"仓地址 ≠ 子源地址"的错配,但**解决不了根因二** —— 异步完成时依然没有重组;更要命的是它会让按钮在 `Ready` 之前就出现,而那一刻 `repoEntries` 还是空的 ⇒ 用户点开是个空 sheet(比"暂时看不见"更糟)。
- **验证**:`:app:compileDebugKotlin` / `:app:testDebugUnitTest`(**199 用例 / 0 失败**)/ `:app:assembleDebug` 全部通过。**未装机** —— 改动是 Compose 状态刷新时序,现有纯逻辑单测覆盖不到。**真机待验**:①添加点播仓源后**停在配置管理页不动**,数秒内右上角应自行出现「换仓」圆钮,且订阅卡上的仓地址那条显示「使用中」;②切到直播段添加直播仓源 → 进直播页 → 返回配置管理页,换仓钮应出现;③管理模式下右上仍是「编辑/删除」,不受刷新影响。
- **文档同步**:`avbox-mobile-ui-spec.md` §4.7「换仓入口」新增可见性刷新条目;§6.9 补一条通用规则(「首次组合读一次 KV」+「异步写 KV」= 页面不刷新 → 标准配方 = ON_RESUME + boot Ready 双通道,且只重读"当前态"不重读用户可编辑列表)。

## 返工:换仓入口的刷新触发器选错了(2026-09-21 同日九轮)

- **现象**:上一轮按「ON_RESUME + boot 落地 Ready」补了刷新,用户真机反馈**完全没效果** —— 添加并启用多仓源后,仍要退出配置管理页再进才出现换仓控件。
- **返工前的两次误判(记下来免得重犯)**:
  - ① 先怀疑 `AnimatedContent` 的 content 不随"捕获值变化"重跑(因为 `canSwitchRepo` 是在 `AnimatedContent` 之外算好再被 content lambda 捕获的)。**去 Gradle 缓存的源码包查证后否掉**:`animation-android-1.12.0-sources.jar` 的 `AnimatedContent.kt` 里,`Transition.AnimatedContent` 在**过渡静止**(`currentState == targetState && pendingTargetState == null`)时会执行 `if (contentMap.size != 1 || contentMap.containsKey(currentState)) contentMap.clear()`,紧接着下面那段 `if (targetState !in contentMap || ...)` 因 map 刚被清空而成立,**用当前这轮的 `content` lambda 重新填充** ⇒ 每次重组都会拿到新 lambda,内容不会滞留。**方法教训:Compose 新 API 的行为不要靠回忆下结论,缓存里的 `-sources.jar` 就是权威(本项目文档里早有这条约定)。**
  - ② 一度想改成"在 content lambda 里读状态"或"放宽可见条件到 `isApiLineSource`",都是绕开根因的补丁,已否。
- **真因:触发器晚了。** `AppBootstrap.startInit()` 的 `_state.value = Boot.Ready` 在 **`awaitLoadConfig()` + `awaitLoadJar()` 两段之后**,而多仓改写(`ApiConfig.switchApiCollectionIfNeeded`)只发生在**第一段**里 ⇒ 拿 `Ready` 当"改写完成"的信号,实际要等 jar 全部下载装载完(几秒到几十秒),用户根本等不到;若加载落进 `Boot.Error` 则**永不触发**。所以第一版"看起来修了等于没修"。
- **修法:由改写点直接发信号。**
  - 新增 `util/ApiLineSignal.kt`:`MutableStateFlow<Int>` 单调自增 + `notifyChanged()`(用 StateFlow 而非 `mutableStateOf`,让 `util` 层不依赖 Compose)。
  - 发信号的位置 = **所有会改变"当前源是否来自仓"这个结论的地方**:`ApiConfig.switchApiCollectionIfNeeded`(点播仓改写)、`ApiConfig.switchLiveApiCollectionIfNeeded`(直播仓改写)、`HistoryHelper.clearApiLineList()` / `clearLiveApiLineList()`(集中在 clear 里,一次覆盖 `clearVodConfig` / `clearApiConfig` / `clearApiLinesIfUnmatched` / `applyVodSource` 四个调用点)。
  - 消费侧:`ConfigManagePage` 用 `LaunchedEffect(apiLineVersion) { refreshActiveSnapshot() }` 取代原来的 `LaunchedEffect(boot)`;`SettingsPage` 的「接口线路」行同样把 `LaunchedEffect(boot)` 换成 `LaunchedEffect(apiLineVersion) { vm.refreshState() }`。`ON_RESUME` 两条都保留(兜"改写发生在别的页面"—— 直播仓要进直播页拉配置时才改写)。
- **Java 侧踩点**:`ApiConfig` 在 `com.github.tvbox.osc.api` 包,`ApiLineSignal` 在 `util` 包 ⇒ **必须显式 import**(`HistoryHelper` 与它同包才不用)。第一次编译报 `程序包ApiLineSignal不存在` 就是这个。
- **验证**:`:app:compileDebugKotlin` / `:app:compileDebugJavaWithJavac` / `:app:testDebugUnitTest`(**199 用例 / 0 失败**)/ `:app:assembleDebug` 全通过(APK 09:34 重建)。**本机无 adb 设备,真机待验**:①添加并启用点播多仓源后**停在配置管理页不动**,应在仓 JSON 拉取完成那一刻(约一次网络往返,不必等 jar)出现「换仓」圆钮;②仓地址那条订阅卡应同时显示「使用中」;③切到直播段添加直播仓源 → 进直播页 → 返回,换仓钮应出现。
- **文档同步**:spec §4.7 与 §4.3 订正触发器(删掉"改写先于 Ready 所以能兜住"的错误结论)、§6.9 通用规则改为"信号由改写点发,不要反推加载完成"。

## 收窄:刷新路径只留一处(2026-09-21 同日十轮)

- **背景(用户)**：「怎么在设置页也刷新了？？？只保留一处刷新路径啊,放在配置管理页面的右上角就行了」。九轮把 `ApiLineSignal` 同时接给了 `ConfigManagePage` 与 `SettingsPage`,用户判定同一件事挂两条刷新路径属于多余。
- **复核结论:用户对,两处都能收窄到一条。**
  - **设置页**:它本来就是 `MainScreen` 的一个 tab,而仓改写只可能发生在配置管理页(独立 Activity)或开机阶段 —— 离开本页期间改写必然已经结束,原有那条 `LifecycleEventEffect(ON_RESUME)` 重读一次就够。删掉 `apiLineVersion` 订阅与随之失效的三个 import(`LaunchedEffect` / `collectAsState` / `ApiLineSignal`)。这条订阅是九轮顺手加的,属于过度设计。
  - **配置管理页**:连 `ON_RESUME` 一起删掉,只留信号。依据(逐条查证,不是推断):①`ConfigManageActivity` 在 manifest 里是默认 `standard` 启动模式、自身也不 `startActivity` ⇒ 每次进入都是新实例,首次组合读到的就是新值;②`loadLiveConfig` 的调用点只有 `LivePlayViewModel` / `LivePlayActivity` ⇒ 本页存活期间**唯一**会改写仓关系的只有点播那一路(`AppBootstrap.onApiUrlChanged()`),而它必发信号。
- **保留的机制**:`ApiLineSignal` 本身不动(写入方在 `api`/`util` 包、读方在 `ui.page`,`util` 里的信号对象是最省事的解耦方式),只是**只剩一个消费者**。九轮审查期已把自增改成 `_version.update { it + 1 }`(CAS),并发调用不丢计数。
- **验证**:`:app:compileDebugKotlin` / `:app:compileDebugJavaWithJavac` 通过(两个任务均实跑,非 UP-TO-DATE)。
- **文档同步**:spec §4.3「接口线路」行、§4.7「换仓入口」、§6.9 通用规则统一改为「同一页内只保留一条刷新路径」。

## 删除设置页「接口线路」行:换仓入口只留一处(2026-09-21 同日十一轮)

- **用户要求(原话)**：「设置页不要显示接口线路这个换仓入口,听不懂吗」,并附设置页截图(「接口线路」行显示 `FongMi`)。**这是对十轮那句「只保留一处刷新路径啊,放在配置管理页面的右上角就行了」的澄清** —— 用户要的是**入口只留一处**,不是刷新路径只留一条。十轮理解偏了,只删了刷新订阅,没删入口。
- **删除范围(整行 + 只为它存在的机制)**：
  - UI:那块 `if (state.apiLineVisible) { SettingsGroup { SettingsCard(SINGLE) { SettingsRow("接口线路") } } }` 单卡。
  - 状态:`SettingsState` 的 `apiUrl` / `apiLines` 两个字段与派生属性 `apiLineVisible`(`get() = HistoryHelper.isApiLineUrl(apiUrl)`);`loadState()` 里对应的两行 KV 读取。
  - 辅助函数:`currentLineName()` / `currentLineIndex()`。
  - **只为它存在**的弹层机制:`OptionSheetState` 类、`var optionSheet` 状态、`openOptions()`、以及底部 `optionSheet?.let { AVBoxOptionSheet(...) }` 渲染块 —— 全仓再无调用者(其它行都走 `SettingsOptionMenuRow` 的弹出式菜单)。
  - 失效 import:`AVBoxOptionSheet`、`ApiConfig`。
- **刻意保留(不是遗漏)**:`refreshState()` 与 `LifecycleEventEffect(ON_RESUME) { vm.refreshState(); vm.refreshCacheSize() }` —— 查 `git show e17cc0e^` 确认它们**早于九轮就存在**,服务的是"播放设置/偏好设置/预载设置等二级页改了同一批 KV,回设置 tab 要重读",与该入口无关;两者 KDoc/注释里原先写的"接口线路"理由已改写为真实理由。
- **教训**:九轮为了修这行的可见性,补了 `ApiLineSignal` 订阅 + 返工一次 + 十轮再收窄 —— **三轮工作全部白做**,因为用户从一开始就不想要这个入口。用户说"只保留一处"时,要先确认指的是**入口**还是**刷新路径**;UI 上出现"同一功能两个入口"时,先问是不是该删一个,而不是急着把第二个修好。
- **验证**:`:app:compileDebugKotlin` 通过(仅剩既有 Kotlin 插件弃用警告);`:app:compileDebugJavaWithJavac` UP-TO-DATE(无 Java 改动)。
- **文档同步**:spec §4.3「接口线路」条目改写为删除记录、分组内容去掉该行、§4.3 弹窗形态条目去掉它的 `AVBoxOptionSheet` 归属、§4.3「已删条目」补一行、§4.7 与 §6.9 里"设置页那一半"的表述改为"已随入口删除"。

## 风险源黑名单:被停用过的源在配置管理页标出来(2026-09-21 同日十二轮)

- **用户问题(原话)**:「如果在切换多仓时遇到无法使用并且会造成闪退的源,目前能否能拦截闪退,还是得先闪退一处再进入才显示禁用」。读码答复:**拦不住** —— 崩在爬虫自己的线程上(`GoProxy.<clinit>` 里 `System.load` 了一个 313 字节的 CDN 报错页),进程级 `UncaughtExceptionHandler` 只能记、不能拦;当前是"先崩一次、下次启动才停用"。并且**换仓这条路上要崩两次**:判据里的"启动加载阶段"锚在 `BOOT_LOAD_START_ELAPSED`,而它只在**本进程第一次装载 jar** 时记(`BootGuard:110-114`,有意的,防播放期崩溃被误判成启动崩溃),换仓属于会话中途装载,崩溃时刻减装载起点远大于 10 秒 ⇒ 第一次重启判不出来(只能靠"累计装载满 3 次"兜底)。
- **用户选定方向 2**:黑名单 + 界面标记 + 二次确认(而不是先改判据锚点去省掉那一次崩溃)。
- **改动**:
  - 存储:`HawkConfig.BOOT_DISABLED_SOURCES`(源地址 `ArrayList<String>`)+ `KVKeySpec` 显式登记元素类型。与 `BOOT_SAFE_DISABLED` 分工明确 —— 后者是**一次性提示**(读后即清),前者是**持久名单**。
  - `BootGuard`:停用源时顺带记入名单;对外 `isDisabledSource` / `disabledSources`(返回副本,调用方改它不会写回存储)/ `enableSource` / `forgetSources`;增删写成纯函数 `addDisabledSource` / `removeDisabledSource`(去重、空地址不记、null 入参不抛)以便单测。
  - `ConfigManagePage`:订阅卡与「换仓」sheet 打「已禁用」标记(`errorContainer` 底 + `onErrorContainer` 字 + `labelSmall` + 6dp 圆角);切源统一走 `requestSwitch(item, vod)` —— 命中名单先弹二次确认,`enableAndSwitch()` 才移出名单并切换;`deleteSelected` 连带 `forgetSources` 清记录。**`PendingSwitch` 必须带 `vod`**:列表在 `AnimatedContent` 里渲染,过渡期内外两份内容同时组合,读外层 `isVod` 会把正在退场的那份按错的模式切源(沿用原代码 `mIsVod` 的口径)。
  - `ApiConfig.firstUsableApiLine`:仓改写挑"首条子源"时跳过黑名单,全被停用则放弃改写(退回"把仓 JSON 当普通配置解析"= 空配置而非闪退)。**不加这条黑名单形同虚设** —— 停用会清掉仓列表(换仓入口随之隐藏),用户只能重新点那条仓订阅卡,而它会照旧被改写到坏子源、再崩一次。
  - `LivePlayViewModel` position 6(直播页「配置切换」组):命中黑名单直接拒 + Toast 指路,**不改 `LiveSettingItem` 模型** —— 那一组只是个切换列表,没有放二次确认的位置。
  - `MainScreen` 停用提示补「可在配置管理中重新启用」。
- **验证**:`:app:testDebugUnitTest` **204 用例 / 0 失败**(基线 199 + 黑名单 5);`:app:compileDebugKotlin` / `:app:compileDebugJavaWithJavac` / `:app:assembleDebug` 全通过(`AVBox_debug.apk` 已产出)。**未装机** —— 本机当前无 adb 设备连接,且用户已明确要求"不要操作我的手机"。
- **仍存的局限(刻意保留,非遗漏)**:①闪退本身依旧拦不住,这份名单换来的是"崩过之后不会再被自动选中 + 界面上看得见";②名单按**源地址**记 ⇒ 换仓场景标记的是**仓内子源**那一条,订阅卡上那条仓地址本身不会被标记;③同一个 jar 被多个源引用时,名单只记当时生效的那个源地址,别的源引用同一个 jar 不会被牵连。
- **顺带订正一处文档不一致**:`SpiderLoader.java:118` 的注释原写"连续存活满 **60 秒**后清",而 `BootGuard.STABLE_RUN_MS` 实际是 **10 分钟** —— 注释比代码短一个数量级,排查"计数为何不清"时会被带偏,已改为引用常量名。

## 崩溃判据锚点改为"最近一次装载":换仓切到坏源不再要崩两次(2026-09-21 同日十三轮)

- **承接十二轮**:上一轮只做了方案 2(黑名单 + 界面标记),把"换仓要崩两次"留给用户决定。用户回「继续」,按方案 1 收掉。
- **真因(十二轮读码已定位)**:`BootGuard.onJarLoadStart` 只在**本进程第一次**装载 jar 时写 `BOOT_LOAD_START_ELAPSED`。于是:进程第一次装载的起点是几分钟前,会话中途换仓那次装载**不更新**起点 ⇒ 崩溃时刻减起点 = 几分钟 ⇒ `crashedDuringStartup` 恒为 false ⇒ 只剩"累计装载 3 次"兜底 ⇒ 用户要崩两次才等到停用。
- **改法**:每次装载都覆盖起点,与 `BOOT_LOADING_JAR` 保持同一批数据(此前两者本就不一致:jar 记的是最近一次、时刻记的是第一次);删掉只为此存在的 `sProcessStartWallMs` 静态字段。
- **旧注释给的理由其实不成立(顺带订正)**:那句"启动 5 秒后播放崩了、用户马上重开不会被算成启动崩溃"在**旧写法下同样不成立** —— 启动后 3 秒播放崩掉,Δ 也小于 10 秒,照样被算成装载阶段崩溃。旧写法真正保护的是"换源后不久的非装载期崩溃"(锚在进程第一次装载时 Δ 会很大)。而这条误判的代价已从"静默清掉启动指针、用户只能清数据"降到"源被标记**已禁用** + 二次确认一键恢复"(十二轮黑名单给的)⇒ 放宽锚点是净收益。
- **回归锁**:`BootGuardTest.midSessionSwitchCrash_countsAsLoadStageCrash` —— 同一份数据同时断言新写法为 `true`、旧写法(拿进程第一次装载当起点)为 `false`,把这个语义钉住。
- **刻意不改名字**:`crashedDuringStartup` 方法与日志字段 `startupCrash=` 保留("startup" 已是历史叫法)—— 既有真机日志与 `history/` 归档里都是这个字段名,改了对不上号。已在 KDoc 写明"别按字面理解成应用启动",并把 `QUICK_CRASH_MS`、`HawkConfig.BOOT_LOAD_START_ELAPSED` 的注释由"启动加载阶段"改为"装载阶段"。
- **验证**:单测 **205 用例 / 0 失败**(上一轮 204 + 1);`:app:compileDebugKotlin` / `:app:compileDebugJavaWithJavac` / `:app:assembleDebug` 全通过。**未装机**。
- **仍覆盖不到的(说明,非缺陷)**:`onJarLoadStart` 依赖 KV,而单测无 Robolectric、`KV.init` 需要 Context ⇒ "起点是否每次都被覆盖"这一层进不了单测,靠的是纯函数那层的回归锁 + 代码审查。另外"装载后 10 秒内的非装载期崩溃会被误算"这条残留窗口没变(实测装载本身只要 28 毫秒,窗口远大于真实装载耗时)。

## 大屏自适应阶段一:窗口分档、按档解锁方向、栅格分列与限宽(2026-09-21)

- **起因**:用户在云真机上发现平板切横屏被强制压回竖屏(信箱模式)。读码 + 查官方文档定位到根因:manifest 里 11 个 Activity 全部写死 `screenOrientation="portrait"`,而项目 targetSdk=37 —— Android 16(API 36)起对 targetSdk≥36 的应用在 **sw≥600dp** 的显示屏上会忽略方向/尺寸限制,Android 17(API 37)连临时选择停用都取消了。所以锁竖屏在手机(sw<600dp,不在忽略范围内)上照旧生效,在平板上两头不讨好:老系统上被信箱化,新系统上被迫横屏但布局是竖屏假设。
- **决策(用户 2026-09-21 拍板)**:① 手机(sw<600dp)**继续锁竖屏**;② 大屏做「栅格自适应 + 内容限宽 + 侧边 Rail(含液态玻璃轴向改造)」;③ **不做**列表-详情双栏。规范写入 spec §4.11 / §5 / §6.10 / §7。
- **改动(阶段一)**:
  - 新增 `ui/WindowSize.kt`:`WindowWidthClass`(Compact/Medium/Expanded)+ 纯函数 `classify` / `shouldLockPortrait` / `scaleColumns` + `currentWindowWidthClass()`。**两个判据分开用**是这次的核心约定 —— 方向策略看 `smallestScreenWidthDp`(与设备方向无关,精确对应平台规则);布局分档看**当前窗口宽度**(分屏下窗口可能远窄于屏幕)。
  - `AndroidManifest.xml`:移除 11 个 Activity 的 `screenOrientation="portrait"`,并在首个 Activity 上方留注释说明方向策略已移到代码里(防止后人"修回去")。
  - `BaseActivity`:`applyOrientationPolicy()`(sw<600→`SENSOR_PORTRAIT`,否则 `UNSPECIFIED`)+ `orientationPolicyValue()` + `onConfigurationChanged` 钩子,在 `onCreate` / `onResume` 调用。**只在策略值本身变化时下发**(实例字段缓存)—— 否则每次配置变化都会覆盖播放器「旋转」按钮刚设过的方向;`smallestScreenWidthDp` 与方向无关,故手机旋转不会触发重复下发。
  - `DetailActivity` / `LivePlayActivity` 的 `applyFullscreen(false)`:由硬写 `SENSOR_PORTRAIT` 改为 `orientationPolicyValue()`。**这是最关键的一处** —— 不改的话用户退出一次全屏就被重新锁回竖屏(与信箱化同一症状)。
  - 栅格按档分列 + 限宽:`HomeGridLayout`(基 3)/ `CollectPage`(基 2)/ `PartitionListActivity`(基 3)统一改为 `GridCells.Fixed(WindowSize.scaleColumns(基, currentWindowWidthClass()))`,外层加 `Box(contentAlignment = TopCenter)` + `widthIn(max = 1000.dp)` 限宽居中。增量取 +1/+3,使 Medium/Expanded 的卡片宽度落在 120–160dp(单测用"卡片宽度算式"把这个区间锁住)。
  - `PartitionListActivity` 的「加载更多」由硬编码 `GridItemSpan(3)` 改为 `GridItemSpan(maxLineSpan)`(全项目其它 5 处本来就是 `maxLineSpan`)。
  - 宽屏观感两处上限:`HomeGridLayout` 的筛选 chip 等宽铺满加 `maxWidth <= 600dp` 前提(宽屏改走自然宽度左对齐);`HeroCarousel` 的 `sidePad` 加 `coerceAtMost(96.dp)`(18% 是手机档比例,宽屏下会大到看不见内容)。
- **验证**:`:app:compileDebugKotlin` / `:app:compileDebugJavaWithJavac` 通过(仅既有弃用提示);`:app:testDebugUnitTest` **213 用例 / 0 失败**(基线 205 + 新增 `WindowSizeTest` 8 例)。**未装机** —— 平板侧真机行为待验。
- **刻意没做(非遗漏)**:
  - `ComposeVideoController.onRotateClicked()` / `onBackClicked()` 设 `SENSOR_PORTRAIT`/`SENSOR_LANDSCAPE` 保留原样:那是播放器「旋转」按钮的**显式用户意图**,锁住正是用户要的结果,且退出全屏会被策略值复位。上游 dkplayer 遗留(`player/.../BaseVideoController.java`、`ControlWrapper.java`)不在当前 Compose 路径上,未动。
  - `bottomPadding: Dp` → `contentPadding: PaddingValues` 的签名重构**顺延到阶段二**:阶段一仍只有底部留白,现在改只是空转,等 Rail 引入 start padding 时一并做。
  - `DetailScreens` 选集网格的列数仍按剧集名长度算 —— 其容器是限宽 sheet,不受宽屏影响。
  - `hideSysBar()` 的强制沉浸式在多窗口/平板上偏敌对,属独立决策,未动。
- **文档同步**:spec §3「横竖屏」改写、§4.11 新增并标注实施状态、§5 补 Rail 玻璃标定、§6.10 新增、§7 未决清单更新、§2 补版本漂移与单测基线订正。

## 大屏自适应阶段二:导航栏轴向参数化 + 侧边 Rail(2026-09-21)

- **范围**:把导航栏从"只会横着放"改成"按窗口档选横条或竖条",并让 `MainScreen` 与液态玻璃跟着走。Compact 档渲染必须与改造前一致。
- **改动**:
  - **`ui/navbar/FloatingBottomBar.kt` → `ui/navbar/FloatingNavBar.kt`**(旧文件已删除,不保留双份)。新增 `NavAxis { Horizontal, Vertical }`;轴向差异全部收进 5 个 helper:`crossAxisSize` / `mainAxisLength` / `mainAxisFill` / `mainAxisPadding` / `setMainAxisTranslation`。容器抽象成 `NavContainer`(横向走 `Row`、竖向走 `Column`,内容由 `horizontalContent: RowScope.() -> Unit` 与 `verticalContent: ColumnScope.() -> Unit` 两个 lambda 分别提供)—— 之所以不能合成一个,是因为等宽分发要用的 `weight` 在 `RowScope` 与 `ColumnScope` 里不是同一个函数。两个作用域各自的 `NavTabsRow` / `NavTabsColumn` 再委托给共用的 `NavTabItem`,避免重复 tab 样式。**横向路径的 modifier 链逐字未变**(helper 在 `Horizontal` 分支返回的就是原来那个 modifier),故手机档渲染与改造前一致。
  - **液态玻璃三处按轴向重标定**:① `lens()` 按短边限幅(`min(distortionDp, size.minDimension / 2f)`,与 `GlassTopBar.glassSurface` 同款)—— 原实现没有这个保护,`DEFAULT_DISTORTION_DP = 30f` 直接用在 64dp 宽的竖条上会崩;② 按压鼓出改按主轴长度(原按 `size.width`,竖条上会横向胖出约 25%);③ 第三层的甩动拉伸轴向对调(横条拉 x 压 y,竖条拉 y 压 x)。`pressedScale = 78f/56f` 不用改 —— 它是交叉轴上的无量纲比(56→78),横竖语义相同。
  - **`MainScreen`**:按 `currentWindowWidthClass()` 选轴向(Compact→横条,其余→竖条);`band` 矩形、渐变遮罩(竖向/横向 + `BottomCenter`/`CenterStart`)、导航容器对齐与 insets 全部按轴向分支。竖条档**即使玻璃关闭也渲染悬浮导航**(此时容器色不透明),横条档保持原有的"玻璃关闭则用 Scaffold `NavigationBar`"。
  - **页面留白改为 `contentPadding: PaddingValues`**(`bottomPadding` 从 `HomePage`/`HistoryPage`/`CollectPage`/`SettingsPage`/`HomeGridLayout` 五个签名里删除)。留白统一由 `MainScreen` 算,各页把它作为**内容内边距**施加:滚动容器的 `contentPadding`、覆盖层的 `Modifier.padding`(如首页直播 FAB)、顶栏的 `topBarStartInset`。页面内不出现 `if (isRail)`。
    - ⚠️ **这一处返工过一次(真机截图确认的回归)**:中间版本是"在 `MainScreen` 的 pager 外层给**页面容器**加 `Modifier.padding(pageContentPadding)`"。用户截图显示**导航栏下方变成一块不透明的灰板、内容不再延伸到导航栏下面**。根因:容器 padding 会把页面背景一起缩掉,导航栏下方只剩外层 `Scaffold` 的 `containerColor`,玻璃取不到内容 ⇒ 退化成纯色板。**注意这两种写法数值完全等价**(`88 + (insets+76)` ≡ `(insets+76) + 88`),只有视觉不同 ⇒ **数值对账不能替代真机确认**。已改回"内容内边距"写法。
    - 顺带给 `AppTopBarScaffold` 新增 `topBarStartInset: Dp = 0.dp`:竖条档要让开 Rail 只能缩顶栏;给它传 `modifier = Modifier.padding(start = …)` 会把整个 Scaffold 连内容一起缩掉(就是上面那个回归的同一形态)。
- **刻意否掉的一处原定方案**:spec §5 原写"Rail 的 band 必须避开顶栏"。实施时发现把 band 顶部下移到顶栏之下,会让 Rail 上段落在源层之外 ⇒ 那一段玻璃取不到底、退化成纯容器色,比"左上角一小块重叠"更难解释。实测重叠只发生在 band 的 margin 区(x∈[76,140]dp),Rail 自身(x∈[0,76]dp)不会采到顶栏的玻璃面。故改为全高 band,并把结论回写 §5(标注"实施时否掉")。
- **踩到的坑(已记录进 §6.10)**:轴向 helper 第一次写反了 —— 把"主轴"当成了高度,而横条的**主轴是宽度**。这类错误不会报编译错,横条路径因为映射恰好是自己也被真机验证过,只有竖条会错位。
- **验证**:`:app:compileDebugKotlin` / `:app:compileDebugJavaWithJavac` 通过;`:app:testDebugUnitTest` **213 用例 / 0 失败**(阶段二未新增单测 —— Rail 与导航壳全是 Compose 布局代码,本项目单测是纯 JVM、无 Robolectric,这部分进不了单测);`:app:assembleDebug` 通过,产出 `app/build/outputs/apk/debug/AVBox_debug.apk`,并核对**合并后清单里 `screenOrientation` 出现 0 次**(11 个 Activity 全部不再声明方向)。**未装机** —— 但用户随后提供了真机截图,据此定位并修掉了上面那条留白回归(修完重新编译 + 单测 213/0 通过)。平板侧真机行为仍待验。
- **文档同步**:spec §4.11(实施状态补阶段二 + 留白方案含返工记录、页面留白段落改写并加 ⚠️ 红线)、§5(Rail band 结论订正)、§6.10(补轴向命名坑、留白只能加在内容上的红线、band 取舍、测试空白)、§7。

## 侧边 Rail 真机复核:三处修正(2026-09-21)

- **背景**:用户在平板(DPD2437 / Android 15)上验阶段二,报了两个问题,截图里还暴露出第三个。
- **① 关掉玻璃不回退 surface 模式(真 bug,我引入)**:原实现里"玻璃关 → 用 M3 标准 `NavigationBar`"是 **`MainScreen` 的行为**,不是导航栏组件自己的。阶段二我把竖条档写成"不管玻璃开不开都渲染悬浮导航,靠 `containerColor` 不透明兜底",等于砍掉了回退路径 ⇒ 关玻璃后仍是**胶囊形状 + 无 M3 指示器**的假 surface。
  - **修法**:把 `liquidGlassEnabled`(皮肤)与 `navAxis`(形态)彻底解耦 —— `railMode = navAxis == Vertical`、`surfaceNavVisible = !liquidGlassEnabled`;竖条档 + 关玻璃改渲染 `M3 NavigationRail`(宽 80dp、`surfaceContainerHigh`),页面 reserve 用新增常量 `SURFACE_RAIL_WIDTH_DP = 80` 而非 76。
- **② Rail 区域一块半透明白(真 bug,我引入)**:左侧渐变遮罩**方向写反**。底部那条是 `verticalGradient(0f 透明 → 1f 不透明)` = "贴屏幕下边缘不透明、往上渐隐";我照抄成 `horizontalGradient(0f 透明 → 1f 不透明)`,而在横向里 `0f` 是**屏幕左边缘** ⇒ 变成"贴左边缘透明、往内容方向越来越白",在内容侧(海报左侧)糊出一块半透明白。
  - **修法**:横向换成 `horizontalGradient(0f 不透明 → 1f 透明)`,并抽出 `scrimColor` 局部变量避免两处颜色不一致。
- **③ 分类 tab 行被 Rail 压住(截图发现,我漏掉的)**:`contentPadding` 只作用于**滚动内容**;`HomeGridLayout` 的分类 tab 行(`HomeSortTabRow`)、顶栏、FAB 都不在滚动容器里,拿不到它 ⇒ tab 行从 x=0 起、左边被 Rail 盖住("热播电影"只剩半个字)。
  - **修法**:给 `HomeSortTabRow` 加 `startInset: Dp` 参数并在 `HomeGridLayout` 传入 `navStart`(顺带把 `navStart` 从栅格 contentPadding 里的内联调用提成局部变量复用)。顶栏与 FAB 在上一轮已分别用 `topBarStartInset` / `Modifier.padding` 处理。
- **顺带清理**:`MainScreen` 里 tab 点击的三份重复实现(Scaffold 的 `NavigationBar` / `FloatingNavBar` / 新增的 `NavigationRail`)抽成一个 `selectTab: (Int) -> Unit`。
- **验证**:`:app:compileDebugKotlin` / `:app:compileDebugJavaWithJavac` 通过;`:app:testDebugUnitTest` **213 用例 / 0 失败**;`:app:assembleDebug` 通过并重新出包。**待用户再截图确认**。
- **教训(三次同类错误)**:这一轮我连错三次(容器 padding、遮罩方向、漏掉非滚动元素),共同点是**"看起来等价"的写法实际不等价,而我用推理代替了真机确认**。结论:凡是"位置/方向/谁让开谁"这类几何改动,只能靠真机截图收口;能推理的只有纯函数那一层。
- **文档同步**:spec §4.11 补"形态与玻璃正交"规则、§6.10 补三条红线(遮罩方向 / 非滚动元素要单独让开 / 形态与玻璃正交)。

## 平板第二次复核:竖条 insets 修正 + 两处"看着像 bug 其实不是"(2026-09-21)

- **背景**:用户装上一轮的包后在平板(DPD2437 / Android 15)复测并给了两张截图(首页 rail 态 + 搜索页),问"还有什么 bug"。
- **截图 1(首页)确认三处修复全部生效**:Rail 在左侧、遮罩不再发白、分类 tab 行让开了 Rail、栅格 6 列且卡宽落在目标区间。**未发现新问题。**
- **截图 2(搜索页)两处"看着像 bug 其实不是"**:
  - 结果列表上方那条**波浪形蓝线** = `SearchScreens.kt:331` 的 `LinearWavyProgressIndicator`(M3 expressive 波浪形线性进度条,`if (running)` 时显示)。截图时搜索仍在进行,属预期。
  - 源列表里"光影 | … **C**"那个 C = `SearchRailItem` 的 `pending` 指示器(`CircularProgressIndicator` 12dp、未定态为弧),在截图缩放下像字母 C。也属预期。
- **修了一处真问题(代码扫描发现,非截图)**:竖条容器的 insets 原来只用 `WindowInsets.navigationBars`。竖条是**满高**的、上下都要让,而 `navigationBars` 只覆盖底边与横屏侧边 ⇒ 在状态栏较厚或竖屏平板上会顶进状态栏。已把 `MainScreen` 两处(悬浮竖条容器 + 回退的 M3 `NavigationRail`)改为 `WindowInsets.systemBars`。横条档不变(它只贴底边,`navigationBars` 正确)。
- **刻意没修(记进 §6.10)**:竖条档下 `HomeGridLayout`/`HomePage` 的 `bottom` 里那个 `88.dp` 是给底部悬浮条留的,竖条档没有底部条 ⇒ 列表底部多 88dp 滚动余量。收口要把它从页面移到 `MainScreen`,会再动一次手机档留白路径;本轮已连出三次几何回归,**故留到平板验证通过后再做**。
- **验证**:`:app:compileDebugKotlin` 通过;`:app:testDebugUnitTest` **213 用例 / 0 失败**。
- **方法论沉淀(本轮第三次记)**:几何类改动"推理不可靠、截图才可靠"。这轮我又靠**代码扫描**(而不是截图)找出 insets 问题,说明两条路都要走:截图抓"看得见的错",扫描抓"还没显形的错"。

## 海报与分类 tab 行错位 41dp:栅格限宽是元凶(2026-09-21)

- **用户反馈**:平板截图上"影视海报没有对齐热播电影这一栏",问是不是 bug。
- **量化(直接从截图取像素)**:比例尺由"列间距 21.4px = 12dp"解出 = 1.783 px/dp,窗口 ≈ 1077dp。实测「热播电影」文字左缘 109.4dp、海报左缘 **150.3dp**,**差 41dp**。
- **根因(我引入的)**:阶段一给栅格加了 `widthIn(max = 1000.dp)` + `Box(contentAlignment = TopCenter)` 限宽居中,**但只加在栅格上、没加在整页内容上**。窗口 1077dp 时栅格被压到 1000dp 居中 ⇒ 整块右移约 58dp,而分类 tab 行仍贴左边缘。
- **修法(比"直接撤掉限宽"更彻底)**:撤掉限宽会让卡宽超标(1116dp 窗口下 6 列 → 170dp,超出 120–160dp 目标)。改为**用「可用宽度 ÷ 目标卡宽」算列数**:
  - `WindowSize.scaleColumns(档位)` → **删除**,换成 `gridColumns(availableWidthDp, minColumns)`;新增 `TARGET_CARD_WIDTH_DP = 130` / `GRID_COLUMN_SPACING_DP = 12`;删除 `GRID_MAX_CONTENT_WIDTH_DP`。
  - 三处栅格(`HomeGridLayout`/`CollectPage`/`PartitionListActivity`)改用 `BoxWithConstraints` 取 `maxWidth`,算出列数;不再限宽、不再居中。**限宽一撤,容器对齐自动恢复**(海报/tab 盒/chip 盒同在 `navStart + 16dp`)。
  - 实测卡宽:360→3列/101dp(手机档,下限兜底,与原布局一致)、600→4/133、840→5/152、1116→6→**7**/144、1280→8/145、1600→11/131 —— 全部落在 120–160dp。
- **刻意不动的一处**:tab 文字比海报左缘仍右移约 17dp —— 那是 M3 `Tab` 自带横向内边距 + 文字居中造成的,**chip 文字同理(14dp)**。容器是对齐的,别用 padding 去"凑"文字,那会把容器搞歪。已写进 §6.10。
- **验证**:`:app:compileDebugKotlin` / `:app:compileDebugJavaWithJavac` 通过;`:app:testDebugUnitTest` **22 类 / 221 用例 / 0 失败**(`WindowSizeTest` 8 例已按新公式重写,含"卡宽落在 120–160dp"的逐档校验)。
- **方法论(本轮第四次)**:这次靠**从截图取像素反推比例尺**定位,而不是靠读代码猜 —— 值得固化:几何类 bug 先把"比例尺"解出来(用已知的 dp 常量除以实测像素),之后所有偏移量都能算成 dp,不再靠目测。

## Hero 轮播在宽屏上无上限:巨大卡片 + 一条 6.8dp "黑条"(2026-09-21)

- **用户反馈**(对齐修复已确认):平板横屏的「横向展示」下,首页顶部轮播海报非常大、不协调;左边还有一条"不知道是什么"的大黑条,会跟着滚动。
- **量化(截图取像素,比例尺 1.783 px/dp)**:Hero 卡片 x 168.8→984.9dp ⇒ 宽 **816dp**,按 1.5 宽高比 ⇒ 高 **544dp**(占 757dp 屏高的 72%)。"黑条" x **76.8–83.6dp**(宽 6.8dp)、高 429dp —— 起点正好是 `FLOATING_NAV_OVERLAY_DP = 76dp`。
- **根因(同一个)**:`HeroCarousel` 是 `fillMaxWidth().aspectRatio(1.5f)`,尺寸完全由屏宽驱动、**没有任何上限**。卡片越宽,相邻页 `scaleX = 1 - 0.18d` 造成的边缘内移量越大(0.09×816 ≈ 73dp),而 peek 只有 84dp ⇒ 相邻页只在左侧缝里露出 6.8dp。**那条"黑条"就是轮播上一页的边缘**。
- **修法**:给 Hero 同时封宽与封高 —— `fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = HeroMaxWidth = 640.dp).aspectRatio(1.5f).heightIn(max = HeroMaxHeight = 340.dp)`。
  - `wrapContentWidth` 是必需的:`HorizontalPager` 用**固定宽度**约束每个 page,只写 `widthIn(max)` 压不下去(最小宽度也被顶住了),得靠它放开最小宽度再居中。
  - 封宽后相邻页的 Hero 在它自己的槽内居中 ⇒ 右缘被推到视口外(推算 −0.5dp)⇒ **黑条消失**。
  - 手机档(可用 360dp)算出来仍是 230×154dp,**与原样逐像素一致**。
- **验证**:`:app:compileDebugKotlin` / `:app:compileDebugJavaWithJavac` 通过;`:app:testDebugUnitTest` **22 类 / 221 用例 / 0 失败**;`:app:assembleDebug` 通过。
- **方法论(本轮第五次,已固化进 §6.10)**:凡是"尺寸由宽高比推导"的组件,在宽屏上都要显式封顶,且**宽高都要封** —— 只封高会把宽度留给相邻页,反而制造出新的视觉噪声。

## 本地导入 py 爬虫:自动包装成单站点配置(2026-09-21,用户"有办法做到直接导入 py 就能使用吗")

- **背景**:用户把 `一起看影院.py` 直接当订阅导入 → 「配置解析失败」(py 不是配置 JSON,`parseJson` 的 gson 直接抛)。手工方案是"自己写一份 sites JSON 与 py 同目录",用户问能否省掉这一步。
- **实现**(`util/LocalConfigHelper.kt`,纯 Kotlin):`importLocalConfig` 开头按 DISPLAY_NAME 判 `.py` → 新增 `importLocalPySpider`:复制 py 到 `files/config/<md5(uri)>/spider_<md5前8>.py`,同目录生成 `spider_<md5前8>.json`(单站点:key=`py_<md5前8>`、name=原文件名去后缀、type=3、api=`./<副本名>`、searchable/quickSearch/filterable=1),返回 clan:// 地址;站点名经 `jsonEscape`(来自文件名,可能含引号/反斜杠)。
- **关键取舍**:① 走复制路线而非直引 ⇒ 选完即用,不需要"所有文件访问"或第二次目录授权(missingRefs/directPath 均为空);② 副本文件名用 ASCII(`spider_xxxxxxxx.py`)—— 中文名进 URL 有编码风险,站点名仍保留原文件名;③ api 必须 `./` 相对引用 —— 加载阶段 `ConfigParser.fixContentPath` 只认 `"./`/`"../` 才把它改写成可访问的本机服务 http 地址,裸文件名 / `clan://` 都会在 Python 侧 `requests.get()` 阶段失败(后者 MissingSchema ⇒ "下载插件失败")。
- **验证**:`:app:compileDebugKotlin` exit0、lint 0、`:app:testDebugUnitTest --tests *LocalConfigPathTest*` 通过;未装机。
- **局限(当日解除)**:网络地址形式的 py 一开始不支持(会走配置加载 → "配置解析失败"),用户拍板"改" → 见下条。

## py 地址直接当订阅:输入框直填即用(2026-09-21,用户"改")

- **改动(3 个文件)**:新增 `util/PySourcePack.kt`(Kotlin `object` + `@JvmStatic`,单站点 JSON 形状的唯一来源,输入框直填 / 本地导入共用);`ApiConfig.fetchConfigAsync` 在读到正文后插一行 `PySourcePack.packUrl(apiUrl, result)`(Java 只做接线,判定与拼接全在 Kotlin);`LocalConfigHelper.importLocalPySpider` 改调 `packLocal`,删掉内联 JSON 拼接与 `jsonEscape`。
- **触发条件**:地址含 `.py` **且**正文不是 JSON(trim 后不以 `{` 开头)—— 名叫 `.py` 但内容是 JSON 的地址仍按普通配置解析,不误包。
- **关键顺序**:pack 必须早于 `clanContentFix` —— 包装出的 api 若是 `clan://localhost/…`,`clanContentFix` 会把它替换成本机服务 http 地址;放后面就替换不到,Python 侧 `requests.get("clan://…")` 必失败(MissingSchema)。
- **缓存说明**:包装结果是"改写后"存进配置快照的(useCache 命中直接 parseJson),与既有 `./` 引用改写后落缓存的行为一致。
- **单测**:新增 `PySourcePackTest`(6 例:JSON 形状、JSON 正文不误包、百分号编码名 + query 保留、clan 地址保留、相对 api、引号转义)。
- **验证**:`compileDebugJavaWithJavac` 通过、全量 `testDebugUnitTest` 0 失败、lint 0、`assembleDebug` + `install -r` Success。**未 commit**。

## 审查:py 包装的边界修复(2026-09-21,用户"审查一下是否有错误遗漏和引入新回归")

- **真遗漏(🔴)**:`clan://<ip>/…/y.py`(局域网 TVBox 服务地址)包装后 api 原样保留 —— 配置加载的 `clanContentFix` 只改写 `clan://localhost/`,于是 Python 侧 `requests.get("clan://192.168.x.x/…")` 抛 InvalidSchema ⇒ 源不可用。修:`packUrl` 里按 `ConfigParser.clanToAddress` 同口径先转 `http://<host>/file/<path>`(localhost 形式刻意保留 —— 交给 clanContentFix 用**运行时**本机地址替换,写死反而更差)。
- **边界(🟡)**:① 正文带 BOM 时 `startsWith("{")` 失真 ⇒ 地址含 .py + BOM 的 JSON 配置会被误包;判定前 strip BOM。② `nameFromUrl` 用 URLDecoder 处理 path 段会把字面 `+` 解成空格;改 `+ → %2B` 预处理。③ `key` 未转义、空名(文件名恰是 `.py`)会产出无名站点;统一 `jsonEscape` + 空名兜底 "Python源"。
- **防错(🟢)**:`packLocal` 三个同类型 String 参数顺序易错 ⇒ 调用处改具名参数。
- **回归核查**:普通配置(地址不含 .py)恒返回 null、行为逐字不变;`.py` 地址但正文是 JSON 不包;本地导入产出 `spider_<md5>.json`(不含 `.py`)不会被二次包装;`loadLiveConfig` 刻意不包(直播 py 是 liveContent 语义,包成点播站点是错的)。
- **测试**:单测补 4 例(局域网 clan 转 http / BOM 正文 / 名字兜底 / 加号保留),共 8 例;其中一条断言最初按错误预期写(URL 以 `/` 结尾时末段退化成 host 而非空)导致 1 失败,已按真实行为改写并明确记录该行为。
- **已知边界(未做)**:直播源输入框填 py 仍不支持;本地选中的 `.py` 内容其实是 JSON 时会按站点包装(极小概率)。两条都留作后续。
- **验证**:`compileDebugJavaWithJavac` ✓、全量 `testDebugUnitTest` **229 例 0 失败** ✓、lint 0 ✓、`assembleRelease`(R8)✓、`assembleDebug` + `install -r` ✓。**未 commit**。

## 删除订阅时清理它的本地副本(2026-09-21,用户问"删订阅后应用数据目录也会删除吗"→"做吧")

- **背景**:此前 `deleteSelected()` 只改 KV / 看门狗黑名单,副本(`files/config/<md5>/{py,json}` 或 `config/<md5>_原名`)留在盘上变孤儿文件。
- **实现**(`util/LocalConfigHelper.kt`,纯 Kotlin):新增 `localCopyUnit(apiUrl, storageRoot, copyRoot)`(根路径可注入 ⇒ 纯 JVM 可测)与 `removeLocalCopy(apiUrl)`;`ConfigManagePage.deleteSelected()` 在**后台单线程**(`Executors.newSingleThreadExecutor`,与 LiveProxyLoader 同风格)对 `removedUrls` 逐个调用,主线程不做 IO。
- **安全边界(核心)**:必须 `clan://localhost/` 前缀 **且**真实路径落在 `files/config/` 内;删除单位只认两种 —— ① `config/<32 位 md5>/…` 整棵目录;② `config/<md5>_原名` 单文件(名字前缀必须是 32 位小写 hex)。其余情况(config 之外的用户原文件、非 md5 约定的子项、`clan://<ip>`、普通 http/null)一律返回 null 不碰;`;md5;` 尾巴先剥离。
- **单测**:`localCopyUnitOnlyRemovesGeneratedCopies`(真临时目录,7 条断言:整目录 / 单文件+尾巴 / config 外 / 非 md5 子目录 / 非 md5 单文件 / 局域网 clan / null)。
- **未做**:不清配置快照(`filesDir/<md5(apiUrl)>`)—— 重新添加该源会重新拉取覆盖,留着无害。
- **验证**:全量单测 0 失败、lint 0、`assembleDebug` + `install -r` Success(第一次安装被手机端"User rejected permissions"拒绝,重试成功)。**未 commit**。

## 审查:副本清理的两处加固(2026-09-21,用户"审查一下是否有错遗漏和引入新回归")

- **🔴 跨模式误删**:`isInUse` 只看**当前模式**(点播页不查直播激活源),而副本地址允许跨模式重复添加 ⇒ 在点播页删掉一个"正被直播侧当激活源"的地址会连带删副本,直播源直接坏。修:`deleteSelected` 清理前加两道跨模式闸门 —— `activeInEitherMode`(读 `API_URL`/`LIVE_API_URL` + 两侧仓来源判定,覆盖"激活地址不在订阅列表"的多仓子源情形)与 `referencedBySubscribes`(任一模式列表里仍有该地址则不清)。**只挡清理、不放宽删除保护** —— 删除订阅的既有行为逐字未变。
- **🟡 新引入的线程泄漏**:`Executors.newSingleThreadExecutor()` 核心线程默认不超时 ⇒ 每次删除留下一个永久空闲线程;改为 `execute` 后立即 `shutdown()`(LiveProxyLoader 的同款既有用法未动,不在本次范围)。
- **行为确认(非缺陷,留档)**:① 编辑订阅改地址后旧副本成孤儿(刻意不清 —— 改错了还能改回来);② 配置快照 `filesDir/<md5(apiUrl)>` 不清(重新添加会重新拉取覆盖);③ 删除入口全仓唯一(`deleteSelected`),无"清空全部订阅"入口 ⇒ 不存在绕过闸门的批量路径。
- **`localCopyUnit` 边界复核**:`..` 注入会因"parent 必须正好等于 copyRoot"的判定失败而自然拒删(fail-safe);`File.startsWith` 按路径组件比较,`/a/bc` 不会命中 `/a/b`;`target == root` 明确拒绝(防删整个 config)。
- **验证**:全量单测 0 失败、lint 0、`assembleDebug` + `install -r` Success。**未 commit**。

## 多语言(i18n)适配评估 + Spec 立项(2026-09-21,用户"分析难度"→"写一份 spec 文档")

- **评估(无代码改动)**:产出审计脚本 `.codebuddy/tools/i18n_hardcoded_scan.py`(状态机剥离注释与字符边界,统计含中文的字符串字面量),实测:全仓 941 处(含单测 265),**生产 645 处 / 去重 453 条** —— UI 层(`ui/`+`player/ui/`)43 文件 435 处、非 UI 32 文件 213 处;`R.string.` 引用全仓 **0 处**,`strings.xml` 仅 `app_name`;layout XML 无硬编码文本。结论:中等偏下难度,8–13 人日。
- **红线盘点(6 条,翻译只动显示)**:①R1 `"硬解码"/"软解码"` 全仓 35 次(硬 26/软 9)= KV 值(`IJK_CODEC`/`EXO_DECODE`)+ 播放配置 JSON 值 + 逻辑判据(`LivePlayerManager.equals/ComposeVideoController` 写回);②`SourceViewModel:235` `endsWith("搜")`;③`LiveEpgParser:155` `contains("未提供"/"暂无")`;④`FileUtils:536` `contains("模板.js")`;⑤`PlaybackController:1232` `contains("歌词")`;⑥`Trans` 字表 + 弹幕 `t2s`。
- **已有资产**:`crawler/js/Trans.java`(约 2000 对简繁字符映射;出口 = 爬虫 `Global.s2t/t2s` + 弹幕搜索 `DanmakuApi.t2s`),启用判据 `Locale.getDefault().getCountry().equals("TW")` **构造时固化**、港区不生效 ⇒ spec 规划改为跟随应用语言(仅解决字形,不解决港台用词差异)。
- **Spec 交付**:新增 `skill/avbox-i18n-spec.md`(草案,未实施;内容 = 现状盘点 / 资源组织与命名 / `LanguageManager`+KV 方案与三处 Context 包裹 / `Trans` 门控 / D1–D6 决策点 / P0–P4 阶段与出口 / 验收清单 / 风险 / 附录文件级实施清单);`skill/SKILL.md` 文档地图已登记该文档;双副本 `.codebuddy/skills/android/` 同步(两份 SKILL.md 存在既有分叉 —— 副本含本机构建说明 —— 故只做"同位置同内容"的新增行,不整份覆盖)。
- **技术要点(供实施)**:默认资源 = 简体(`values/` 承接 453 条原文)+ `values-en` + `values-b+zh+Hant`(港台共用,差异再拆 `values-zh-rTW`/`zh-rHK`);语言切换自研 = KV 键 `app_language` + `App`/`BaseActivity`/`PlaybackService` 三处 `attachBaseContext` 包裹 + 遍历 `AppManager` 快照 `recreate()`;**不用 `AppCompatDelegate.setApplicationLocales`**(API<33 会写 SharedPreferences,与"全仓无 SP"红线冲突、且形成双真值源);关键坑 = `attachBaseContext` 早于 `Application.onCreate`,必须在 `App.attachBaseContext` 内先 `KV.init(base)`(幂等,`onCreate` 那处保留)。

## Spec 修订:多语言改为"按语言四步"渐进实施(2026-09-21,用户"渐进式适配,分四步,对应四个语言,每完成一个语言及时更新")

- `skill/avbox-i18n-spec.md` 修订(仍为草案,未实施):①§3.1 资源表增"实施步"列并重定义繁体结构 —— 第 3 步 `values-b+zh+Hant` 为繁体基础层(用词取台湾),第 4 步 `values-zh-rHK` 只放港台差异条目(未覆盖条目回落基础层);②§4.1 `LanguageManager` 增 `available()` 语言白名单(设置页入口按步放开,未交付语言不出现,避免半成品误报);③§5-D3/D4 改为港台资源结构与分步交付;④**§6 由 P0–P4 五阶段改为四步表**(第 1 步 简体 = i18n 框架 + 全部 645 处文案外置;第 2 步 英语;第 3 步 繁体台 + `Trans` 门控改造;第 4 步 繁体港)+ 进度表(初始"待开始",每步完成回写状态/日期)+ "每步完成固定动作"(回写进度表 / `history/features.md` 追加 / 双副本核哈希 / 口径变更就地更新);⑤§7 增"按步取用"说明与 `values-zh-rHK` **子集**校验规则;⑥§8/§9 同步(新增坑 8:语言入口按步放开);⑦文末新增修订记录节。
- 设计要点:**第 1 步必须承载全部外置**(外置是逐文件机械动作,拆散会长期处于"资源/硬编码"双体系;简体即默认资源,外置 = 完成简体),后三步只新增 `values-*` 资源文件,不动代码(仅 `Trans` 门控与布局微调例外)。
- 双副本 `.codebuddy/skills/android/avbox-i18n-spec.md` 覆盖同步,哈希已核一致。

## i18n 第 1 步(简体)实施:框架落地 + 33 文件 ≈465 处文案外置(2026-09-22,用户"现在开始第一步";**进行中,未 commit**)

**① i18n 框架(已完成,可编译/打包/单测/R8 验证)**
- 新增 `util/LanguageManager.kt`:`AppLanguage`(System / zh-Hans / en / zh-Hant-TW / zh-Hant-HK)+ `current/set/available/resolve/isTraditional/wrap/localized`;KV 键 `app_language`(未登记 `KVKeySpec`,读取带默认值);`delivered` 白名单**按步放开**(当前仅"跟随系统 + 简体"),未交付语言不进设置页入口。
- 三处 Context 包裹:`App.attachBaseContext`(先 `KV.init(base)` 再 wrap —— attachBaseContext 早于 `onCreate`)、`BaseActivity.attachBaseContext`、`PlaybackService.attachBaseContext`;`wrap()` 在「跟随系统」时**原样返回 base**(默认路径与改造前逐字节一致,避免把 99% 用户拖进"额外 Configuration 层 + AutoSize 叠加"的风险面)。
- 语言入口 = 偏好设置页顶部新组 `LanguageRow`(`SettingsOptionMenuRow`):`set()` + `AppManager.snapshot().forEach { it.recreate() }`(不重启进程、不丢页面栈);`AppManager` 新增只读快照方法。
- **关键修正(审查发现)**:Application / Service 的 base 只在创建时挂一次,切语言后不重挂 ⇒ 直接 `App.getInstance().getString(...)` 会**停在旧语言**(命中 ApiConfig 源加载提示/直播组名、PlayerHelper 播放器名与缩放名、通知按钮文案)。新增 `LanguageManager.localized(app)`(按语言包裹并缓存,`set()` 失效)作为数据层/长生命周期组件取文案的唯一入口。

**② 文案外置(33 文件 ≈465 处;`values/strings.xml` 344 条,值 = 原文案原文)**
- 已完成文件:设置系(SettingsPage 37 / ConfigManagePage 35 / PlaySettingsPage 20 / ThemeSettingsPage 19 / PreferenceSettingsPage 17 / HistoryPage 14 / PreloadSettingsPage 8)、播放系(PlaybackService 6 / PlayContainer 16 / DanmuSheets 20 / SubtitleSheets 19 / PlayerBottomBar 10 / CastSheet 13 / PlaySettingsPage)、页面系(DetailScreens 20 / LiveScreens 21 / LivePlayActivity 11 / HomePage 15 / MainScreen 9 / SearchActivity 13 / SearchScreens 7 / SearchSettingsSheet 10 / CollectPage 7 / HomeGridLayout 7 / ThemeConfig 17 / ThemeColorPickerSheet 5 / VodCardMenu 7 / MusicPlayerScreen 11 / DetailViewModel 8)、数据/工具系(ApiConfig 33 / PlayerHelper 17 / SourceViewModel / HistoryHelper / HistoryPage)。
- 红线:R1–R5 原样保持并在值点打 `// i18n: keep`;第 1 步实测**新增登记 R7–R13**(`超级解析` 参与 DEFAULT_PARSE 持久化、`推送`/`播放$` 结构化数据、`网络请求错误` 仅进日志、`[集期]` 与 DetailViewModel 的 msg `数据列表`/集数正则);显示侧另用资源覆盖(`硬解码/软解码` → `player_decode_*`、`跟随点播源` → `live_follow_vod_source`、`源N` → `live_source_index_name`)。
- 手法(可复用):**非 Composable 的名字表**(`ThemeConfig.PresetSeeds/PaletteStyles`、`MainScreen.AppTab` 枚举构造参数)改为**存资源 id**,由 UI 侧 `stringResource` 解析;**用文案当分支键**的菜单(`VodCardMenu` 的 `when (option) { "加入收藏" -> }`)必须让显示值与分支键同源,否则英文下整条菜单失效;同文案必同 key 逼出重命名(`player_notification_play/pause` → `common_play/common_pause`);无 Context 的 Java/Kotlin 统一 `str()` 走 `LanguageManager.localized`。

**③ 验证**:每批 `:app:compileDebugKotlin :app:compileDebugJavaWithJavac` 绿;`assembleDebug` + `assembleRelease`(R8/资源收缩)绿;全量单测 **23 suites / 230 例 / 0 失败**;新增审计工具四件套(见 spec 附录 B),卡口 `i18n_gate.py` 实测 ui 层 273 → **39 处**。

**④ 遗留(第 1 步未完成)**:ui 层 39 处/16 文件 + 非 ui 层 138 处/25 文件 = **177 处**(`PlaybackController` 25 / `ComposeVideoController` 19 / `PlayUrlResolver` 15 / `Thunder` 13 …);直播设置面板组名在**解析期**取文案,切语言需重解析直播配置才刷新;通知渠道名 Android 侧建后不改名(改名需删渠道重建);真机未验(语言入口切换、全量重建、播放不中断、AutoSize 字宽自洽);`values/strings.xml` 仍为追加顺序(收尾时按 key 排序)。

## i18n 第 1 步(简体)收口:ui / 非 ui 卡口归零(2026-09-22,承接"第二批 273→39")

**本轮完成**
- ui 层 31 处外置 + 8 处 keep:`PlayerLayers`/`PlayerOverlay`/`PlayerTopBar` 的 contentDescription、`DetailActivity`/`PartitionListActivity`/`ConfigManageActivity`/`VodCardAction` 的 Toast·顶栏·空态、`FilterSheet`(标题带参/清除/确定)、`MusicPlayerState`(enum → `@StringRes labelRes`)、`LivePlayViewModel`/`HomeViewModel`(VM 加 `str()` 走 `LanguageManager.localized`)、`HeroCarousel`(评分带参)、`MusicPlayerActivity`;keep = `LiveEpgParser`(R3 + " --免费使用" 清洗)、`VodCard` 评分正则、`Theme` 的 `@Preview` 名。
- 非 ui 层 54 处外置 + 39 处 keep:`PlayUrlResolver` 15(14 外置 + 1 keep)、`Thunder` 13(`status` 文案经 `view.showTip`/线路名直显,非日志)、`LocalConfigHelper` 11(3 条拼接合并为带参资源)、`SpiderLoader`/`DanmakuApi` 错误提示、`M3u8PurifyUseCase`(Toast 带参)、`DLNACastManager`、`IjkMediaPlayer`("视轨" 复用 `player_menu_video_track`);keep = R1(`LivePlayerManager`)/R4/R6/R13、日志类(`RSAEncrypt`/`Proxy`/`KV`/`SourceViewModel`)、数据默认值(`DefaultConfig`/`TxtSubscribe`/`ConfigParser` "线路N"/`OkGoHelper` DNS)。
- `PlayerUiState.timeStartText/timeEndText` 默认值改空串(控制器已写入资源);`OkGoHelper.dnsHttpsList[0]` 保持 "关闭" 索引锚点,`SettingsPage` 显示侧按 index 0 映射 `common_off`。

**验证**:`assembleDebug` + `testDebugUnitTest`(23 suites/230 例)全绿;`i18n_gate.py` = ui 0 / 非 ui 0;`i18n_check_keys.py` = 420 声明 = 420 引用(无重名/未用/重复值/空白)。

**踩的坑**
- **Kotlin-only 编译绿灯掩盖 Java 红灯**:上一轮把 `PlaybackController` 字面量换成 `str(R.string.x)`,但既没定义 `str()` 也没 `import com.github.tvbox.osc.R`(该类在 `com.github.tvbox.osc.player` 包下,裸 `R` 解析到 player 模块的 R)。`:app:compileDebugKotlin` 不编译 Java ⇒ 必须单跑 `:app:compileDebugJavaWithJavac` 才能发现(本次 17 个错误)。
- `ConfigParser.parseLiveSettingItems` 的"线路N"外置会让 `ConfigParserTest` 失败(纯 JVM 无 App ⇒ `str()` 返回空串),该处回归字面量 + keep(数据默认名 + 单测锁定)。**纯 JVM 单测碰到 Android 资源就是红灯**。
- 批量替换脚本要按"先按行号 keep 打标、后做会增删行的字符串替换"的顺序;`replace_in_file` 直接改多行块极易被缩进拌住(本次 `LocalConfigHelper` tip 块失配一次)。

**遗留**:真机走查(语言入口/全量重建/播放不中断/AutoSize 字宽);`values/strings.xml` 仍为追加顺序;直播设置面板组名与默认线名在解析期取文案,切语言需重解析;英语步须复查中文残留(默认线名、"硬解/软解" 短名等)。

## i18n 审查:gate 盲区补齐(`\u` 转义显示值 + LOG 正则过宽)(2026-09-22)

**发现与修复**
- `\uXXXX` 转义的 CJK 字面量是 `i18n_gate.py` 的**盲区**(状态机只认原字符):全仓 118 处,其中**显示值**未外置 —— `IjkMediaPlayer` 音轨/字幕前缀、`ExoPlayer` 三类轨道前缀 + 声道标签。已外置(复用 `player_menu_audio_track/video_track/subtitle`,新增 `player_channel_mono/stereo/count` 3 条),转义数 118→100。
- 剩余 100 处转义均为**数据/判据**:语言映射表(`ExoPlayer.getLanguage`、`IjkMediaPlayer.getFriendlyLanguage`)、`DanmakuApi` 电影/国语/粤语判据、`EpisodeMatcher` "第"、`SourceViewModel` "豆瓣"、`ExoPlayer` "未知" 过滤 —— 按 keep 处理(gate 不可见故未打标记),英语步前复核。
- `LOG_HINT` 正则含过宽模式 `KL`/`TAG,`(任意含此串的行都豁免日志):收紧后严格模式对拍 **0 假阴性**(无被误豁免的真实文案)。

**复核**:卡口仍 ui 0 / 非 ui 0;`check_keys` 423=423;`assembleDebug` + 230 单测全绿;`res/*.xml`(除 strings)无文案;`player/` fork 模块 0 处非日志中文字面量;`localized()` 全部调用点传 `App.getInstance()`(无 Activity 泄漏);`PlaybackService` 取文案走 `app` 而非 `this`。
**观察项(未改)**:① 54 个文件 CRLF/LF 混用(历史遗留;`git diff`/提交时 git 自动归一化为 LF,功能无影响);② `PlayerUiState.timeStart/End` 默认空串 —— 首次 `setPlayerConfig` 前为空,正常流程底栏此时不可见;③ `LanguageManager.localized` 的缓存不区分 base(当前调用点全传 Application,若将来传 Activity 会缓存泄漏,建议维持约定)。

## 语言切换改为"重启后生效"(2026-09-22,用户拍板)

**背景(用户问"现在切换语言后能立刻生效吗?立刻生效有没有 bug / 是不是更复杂")**:现有"立刻生效"机制完整(三处 `attachBaseContext` 包裹 + `AppManager.snapshot()` 逐个 `recreate()` + `LanguageManager.localized()`),但评估出 4 类确定性缺陷/风险:①解析期取文案进 bean 的位置(直播设置组名 / `源%d` / 默认线名)不刷新,需重解析直播配置;②通知渠道名建后不可改(Android 机制);③`recreate()` 丢页面临时状态(非 saveable `remember`、`DetailActivity.fullScreen` 等普通字段 ⇒ 播放页可能从全屏退回预览);④播放页重建后的重挂路径、AutoSize × 包裹 Configuration 叠加只能真机验。维护成本上,"立刻生效"要求团队记住一整套取文案规则(长生命周期组件必须 `localized()`、禁进程级缓存…),而"重启生效"只需启动时包裹一次。

**改造**
- `PreferenceSettingsPage.LanguageRow`:选中语言 → 弹 `AlertDialog`(内容 `settings_language_restart_message` =「重启后生效」,右下「确认」/ 左下「取消」,即 M3 默认 `confirmButton`/`dismissButton` 位置);**取消 = 不写 KV、语言不生效**;确认 = `LanguageManager.set(lang)` + `restartApp(context.applicationContext)`。
- 新增 `util/AppRestart.kt`:`AlarmManager.set(RTC, now+200ms, PendingIntent.getActivity(launchIntent, NEW_TASK|CLEAR_TASK))` 把启动登记到系统,再 `Process.killProcess` + `exitProcess(0)` —— 进程死后由系统拉起。**不用 "startActivity 后立即 kill"**(新页面会先在旧进程里创建、随即一起被杀);**不用 `setExact`**(API 31+ 需 `SCHEDULE_EXACT_ALARM`,本应用未声明);`getLaunchIntentForPackage` 取启动 Intent(不依赖类名)。
- 新增资源 `settings_language_restart_message`;`LanguageRow` 不再遍历 `recreate()`(`AppManager` import 一并移除)。

**验证**:`compileDebugKotlin` + `compileDebugJavaWithJavac` 绿;卡口 ui 0 / 非 ui 0;`check_keys` 424=424。**真机待验**:选语言 → 弹窗;取消不生效;确认后应用自重启且首帧新语言(重启回首页、播放中断属预期)。

**口径反转(文档)**:spec §9-7 原文 = "切语言**不要**重启进程",本次反转为"要重启进程"并保留历史方案说明(§4.3);§7-C 验收同步改口径。

## 语言重启实测修复:由 AlarmManager 改为"startActivity 后 kill"(2026-09-22,真机反馈)

**现象(用户真机)**:切换语言确认后**黑屏 + 重启两次**。

**根因**:原 `restartApp` 用 `AlarmManager.set(now+200ms, PendingIntent)` 登记启动后 `killProcess` —— ①进程已死而闹钟未触发的那段空档没有窗口 ⇒ 可见**黑屏**(in-exact 闹钟会放大空档);②闹钟触发 + 部分 ROM(vivo)在进程被杀后先自动恢复一次 ⇒ **两次启动**。

**修复**:照搬参考项目 `示例文件/android`(`LanguageSettingsScreen.kt` 的重启段)—— **先** `startActivity(getLaunchIntentForPackage + CLEAR_TOP|NEW_TASK)`(同步 binder,返回即已在 AMS 登记),**再** `Process.killProcess(Process.myPid())` + `exitProcess(0)`;flags 由 `CLEAR_TASK` 改 `CLEAR_TOP`(清任务会让 AMS 失去要恢复的 ActivityRecord)。`AppRestart.kt` 重写。

**验证**:`assembleDebug` 绿;待真机复测(选语言 → 取消不生效 → 确认后**单次**启动、无黑屏空档)。

## 语言重启审查:KV 异步写 → 延迟 kill(2026-09-22,用户"审查是否有错误遗漏和引入新回归")

**发现并修复 4 处**
1. 🔴 **写 KV 后立即 kill 会丢语言设置**:`KV.java` 自带注释(实测结论)= MMKV 异步写、约 1s 才落盘、2.4.2 无同步写 flag ⇒ `restartApp` 改为 `Handler.postDelayed(1200ms)` 后再 `startActivity + killProcess`(延迟期间界面仍在,不引入黑屏)。spec §4.3/§9-7 增对应坑。
2. 🟡 `AppManager.snapshot()` 成死代码(原为 recreate 方案新增,改造后全仓无人调用)⇒ 删除;并修 `LanguageManager.set()` 的过期注释(原写"页面重建由调用方遍历 snapshot 逐个 recreate")。
3. 🟡 `restartApp` 的 null 分支:原写法"launchIntent 为 null 也照杀"⇒ 会变成"退出而不重启";改为 null 直接 return(不杀,语言已写 KV,下次冷启动生效)。
4. 🟡 spec 修订记录那行仍写 AlarmManager 方案(与 §4.3 冲突)⇒ 更新为最终做法 + 实测反例。

**复核**:`assembleDebug` + 230 单测绿;卡口 ui 0 / 非 ui 0;`check_keys` 424=424;`KV.put` 走 `instance.encode`(MMKV,失败留 `echo-kv` 日志)。
**观察项**:`AppManager.appExit()` 全仓无调用点(历史遗留,非本轮引入,未删)。

### 追加修正:回到"立即重启",对齐参考项目流程(2026-09-22,用户"怎么不是和示例文件一样,切换完就立刻重启")

- 用户指出与 `示例文件/android` 不一致。核对后确认差异**根源在写入时机**:参考项目在"**选中语言**"时就 `settings.set` 持久化(ViewModel 里),点重启时只剩重启本身 ⇒ 无落盘顾虑、可立即重启;我们原流程是"确认时才写 KV + 立即 kill"(写与杀间隔≈0),才需要 1.2s 延迟兜底 —— 这是把参考项目的行为做"走样"了。
- 改为对齐参考项目:**选中即 `LanguageManager.set`(提前写,落盘时间由"弹窗出现 + 用户点确认"这段交互覆盖)+ 记下原语言;取消/点外部/返回键 → `set(原语言)` 回滚;确认 → `restartApp()` 立即执行(无 `postDelayed`)**。`AppRestart.kt` 去掉延迟。
- 与参考项目保留的语义差异:它"取消"后 KV 仍是已选值(下次冷启动生效);我们按用户要求"**取消必须回滚**"。
- 验证:`assembleDebug` + 230 单测绿;卡口 ui 0 / 非 ui 0;`check_keys` 424=424。

### 追加修正:确认后等弹窗关掉再重启(2026-09-22,用户"点完确认后怎么弹窗还在")

- **现象**:点确认后弹窗仍显示(停在最后一帧)。
- **根因**:`pending = null` 只是**请求**重组,弹窗要**下一帧**才从屏幕移除;原实现同帧内紧接着 `startActivity + killProcess` ⇒ 进程死在弹窗关闭之前,屏幕上保留弹窗最后一帧。
- **修复**:`confirmButton` 只置 `pending = null; restarting = true`,由 `LaunchedEffect(restarting)` + 两次 `withFrameNanos`(≈32ms,无感)后调 `restartApp` —— 弹窗先绘制消失再重启;`restartApp` 另加 `runCatching { startActivity }` 失败即 return(不启动就不杀,避免"没重启却退出")。
- **验证**:`assembleDebug` + 230 单测绿;卡口 ui 0 / 非 ui 0;`check_keys` 424=424。

## i18n 第 2 步(英语):资源与语言入口落地(2026-09-22,未 commit)

**产物**:新增 `app/src/main/res/values-en/strings.xml` **424 条** —— key 集合与 `values` 件件对应(无多无少、无重名),占位符(含 `%%` 与是否带位置)逐条一致;`LanguageManager.delivered` 由「跟随系统 + 简体中文」放开为「+ English」,设置页 `LanguageRow` **不需要改** —— 选项列表与显示名都走 `LanguageManager.available()` + `languageLabelRes()`,英语分支第 1 步已就位。

**翻译口径(供第 3/4 步复用,spec §3.3 已落表)**
- 术语统一:点播=`VOD`、源/站点=`source`/`site`、线路=`line`、解析=`parse`/`resolve`、弹幕=`Danmaku`(**保留日语借词,不译 bullet comment**)、解码=`Hardware`/`Software Decoding`(播放器底栏短标签 `HW`/`SW`)、预载=`preload`、投屏=`cast`、节目单=`TV Guide`(节目单数据本身不翻)、配色风格用 Material 官方名(`Tonal Spot`/`Vibrant`/`Fruit Salad`…)、片头尾=`Opening`/`Ending`。
- 语言名条目保留各自语言的自称(endonym):英文档里 `settings_language_zh_hans` 仍是「简体中文」、`zh_hant_tw/hk` 仍是「繁體(台灣)/(香港)」,与系统语言选择器一致(**不**译成 "Simplified Chinese"/"Traditional Chinese")。
- 资源里**不写裸撇号**:全部改写(`cannot`、避免属格);双引号改弯引号 `“ ”` —— Android 字符串裸 `'` 会构建失败(`Apostrophe not preceded by \`)。
- 同一中文 key 在英文里出现同值属正常(中文区分粒度更高:`上一个`/`上一首`/`上一集` 均为 "Previous")⇒ 第 2 步校验不做重复值检查。

**校验工具(新增,第 2/3/4 步通用)**:`.codebuddy/tools/i18n_align.py <lang>` = key 集合 + 占位符(含 `%%`、是否带位置) + 空值/首尾空白 + 译文 CJK 残留(白名单 = 三个语言名 endonym key)。
**验证**:`i18n_align.py en` = 424 = 424 **PASS**(0 缺失/0 多余/0 重名/0 占位符失配/0 CJK/0 空白);`i18n_check_keys.py` 仍 424=424;`i18n_gate.py` 仍 ui 0 / 非 ui 0;`assembleDebug` 绿(资源链接与 R 表正常)。
**遗留**:①英语逐页走查(重点 = 顶栏、`SettingsCard` 设置行、播放器底栏与各面板的截断/挤压,spec §7-D 高风险区)待用户真机;②数字口径修正 —— 第 1 步落地后新增 `settings_language_restart_message`,`values/strings.xml` 实为 **424 条**(此前文档写 423);③`values-en` 未提交,等走查后再定稿。

## i18n 第 3+4 步(繁體台灣 + 繁體香港):语言包与 `Trans` 门控(2026-09-22,未 commit)

**产物**
- `app/src/main/res/values-b+zh+Hant/strings.xml` **424 条**(繁体基础层,用词取台湾);`app/src/main/res/values-zh-rHK/strings.xml` **53 条**(香港差异层,未覆盖条目自动回落基础层;占比 12.5%,低于 spec §3.1 的 30% 阈值 ⇒ 维持"基础层 + 差异层"结构)。
- `LanguageManager.delivered` 放开 `TraditionalTW` + `TraditionalHK` ⇒ 设置页语言入口 = 跟随系统 / 简体中文 / English / 繁體(台灣) / 繁體(香港);`PreferenceSettingsPage` **无需改代码**(`languageLabelRes` 分支第 1 步已就位)。

**翻译口径(台湾 / 香港用词,已落 spec §3.3 + §9-12/13)**
- 台湾:搜尋 / 設定 / 預設 / 儲存 / 快取 / 執行緒 / 佇列 / 清單 / 導覽 / 儲存庫 / 應用程式 / 控制項 / 網路 / 軟體 / 網際網路 / 畫質 / 逾時 / 位址 / 取得 / 資訊 / 本機 / 資料夾 / 隨選 / 收合 / 內建 / 當機 / 裝置 / 投放 / 節目表 / 載入 / 重新整理;标点按台湾习惯:全角括号 + 全角冒号(`已切換到：%1$s`、`解析來自：%1$s`、`來源：%1$s`)。
- 香港(只覆盖差异):網絡 / 軟件·互聯網 / 緩存 / 隊列 / 列表 / 導航 / 控件 / 視頻 / 音頻 / 屏幕·全屏 / 點播 / 超時 / 地址 / 獲取·信息 / 文件夾·數據 / 本地 / 線程 / 訪問·項目 / 縱向。
- **繁体文案不能靠 `Trans` 字表生成**(字表只解决字形,不解决用词)⇒ 424 条全部人工按用词撰写;`i18n_align.py` 新增 `S2T-SUSPECT`(从 `Trans.java` 反推 s2t 映射,提示"含可转换字形"的条目)兜底漏转 —— 实测繁体层仅 1 条命中 = 语言名 endonym「简体中文」(预期)。

**`Trans` 门控改造(spec §4.5,数据层唯一接线点)**
- 原:`trans = Locale.getDefault().getCountry().equals("TW")`,构造时固化(港区不生效、与应用内语言无关)。
- 改:`Trans.get()` 先读 `LanguageManager.isTraditional()` 得目标档位,与 `Loader` 缓存的签名比对,不一致才新建实例(`trans=true` 才 `init()` 字表 ⇒ 简体 / 英语档零开销)。用方案①(签名懒重建)而非 `reset()`:重建入口有三处(爬虫 `Global` / 弹幕 `DanmakuApi` / 后续调用),自校验不依赖调用时序;`Trans` 首次使用可早于 `KV.init` ⇒ 依赖 `LanguageManager.current()` 的容忍语义(读不到 = 跟随系统,不抛异常),KV 就绪后自动自愈。
- `t2s` 语义不变(弹幕搜索恒转简体);`Global.s2t/t2s` 行为与改造前一致(非繁体档表不构建 ⇒ 转换 no-op)。

**校验**
- `i18n_align.py b+zh+Hant` = **MODE=full 424 = 424 PASS**(0 缺失/0 多余/0 重名/0 占位符失配/0 空白;S2T-SUSPECT 1 条 = endonym);`i18n_align.py zh-rHK --subset` = **MODE=subset target=53 PASS**(0 多余/0 占位符失配/0 S2T);`i18n_check_keys.py` 仍 424=424;`i18n_gate.py` 仍 ui 0 / 非 ui 0;`assembleDebug` 绿(含 `Trans.java` 改动 ⇒ javac 一并过)。
- 工具:`i18n_align.py` 增 `--subset`(地区差异层只允许少)与分层 CJK 口径(只对 `en` 查 CJK 残留;繁体层改 `S2T-SUSPECT` 提示)。

**遗留**:①四语真机走查(用户操控;繁体重点 = 选「繁體(台灣)/(香港)」重启后首帧、`zh-TW`/`zh-HK` 系统语言命中链路、源数据(片名 / 站点名)在繁体档确实转繁体、香港差异条目与未覆盖条目的回落行为);②`values-zh-rHK` 53 条为人工盘点(对照台湾层逐条筛),走查阶段可能再补;③未 commit。

## i18n 升级兼容审查 + `isTraditional()` 修正(2026-09-22,用户"用户从旧版本升级上来不会出问题吧")

**升级路径逐项核对(结论:数据层与默认路径安全)**
- KV/数据层:新键 `app_language` 未登记 KVKeySpec、读取带默认值 `System`,老用户没有该键 ⇒ 读到默认(跟随系统),且**不回写**;MMKV 无 schema / 无迁移(`KV` 注释:项目未发布、Hawk 旧库已移除)⇒ 升级无数据风险。
- 默认渲染路径:「跟随系统」时 `wrap()` 原样返回 base(不叠加额外 Configuration)⇒ 与改造前逐字节一致;`App.attachBaseContext` 提前 `KV.init` 幂等,`onCreate` 里那处保留不动。
- 预期内行为变化:系统语言为英文 / 繁体(zh-TW·zh-HK)的设备,升级后 UI 跟随系统语言(旧版恒中文);非中英系统语言(日 / 俄…)回落简体。属 i18n 目标;若要"升级后仍中文"需另加一次性默认写入(未做)。
- 已知限制(非本轮引入):通知渠道名建后不可改 —— 旧版已建渠道的用户,渠道名仍是旧文案(切语言也不变)。

**发现并修复 1 处真回归**
- 🔴 `LanguageManager.isTraditional()` 原实现 = `current().tag?.startsWith("zh-Hant") == true` ⇒ **「跟随系统」档(tag = null)恒 false**;旧版 `Trans` 判据是系统国家 == `TW`(台湾设备默认转) ⇒ 台湾 / 香港用户**未在应用内选过语言**时:UI 命中繁体资源(`values-b+zh+Hant`),源数据却不再转繁体(片名 / 站点名仍简体) —— 比旧版差,属回归。
- 修复:显式档按 tag;「跟随系统」回落系统 locale = `Locale.getDefault()`(script == `Hant` 或 region ∈ `{TW, HK, MO}`);显式「简体中文」即使在 TW 系统下也不转(严格按所选语言)。顺手清掉 `LanguageManager` 注释里两处规范章节引用(违反注释红线)。
- 验证:`compileDebugKotlin` + `compileDebugJavaWithJavac` EXIT=0;`i18n_gate.py` ui 0 / 非 ui 0;`i18n_align.py b+zh+Hant` PASS;`i18n_check_keys.py` 424=424;spec §4.5 补坑注 + 修订记录加行。

## i18n 四语审查(错误/遗漏/回归)与修正(2026-09-22,用户"继续审查是否有错误遗漏和引入新回归")

**HK 差异层:补漏 15 条 + 修 2 条 + 删 2 条冗余(53 → 66)**
- 补漏(盘点时漏掉的台港用词差异):「記錄」vs 台「紀錄」⇒ `search_history` / `search_history_clear` / `search_history_empty` / `settings_history_limit` / `history_clear_message` / `history_delete_title` / `history_delete_message`;「項」vs 台「筆」⇒ `settings_history_limit_subtitle` / `settings_history_limit_value`;「從本地選擇」vs 台「從本機」⇒ `config_pick_local`;「暫無熱搜數據」vs 台「資料」⇒ `search_hot_empty`;「超時換源」vs 台「逾時換源」⇒ `live_group_timeout`;「收起」vs 台「收合」⇒ `detail_collapse`;「死機」vs 台「當機」⇒ `toast_source_auto_disabled` / `dialog_source_disabled_message`。
- 修自造不一致:`player_get_info_error` / `player_getting_info` 的「信息」改回「資訊」(港台通行,避免半港半台)。
- 删冗余:`toast_local_grant_not_persisted` / `toast_local_refs_missing` 与台湾基础层逐字相同(差异层只放差异)。

**代码:状态冗余合并(1 处)**
- `Trans.get()` 原用"实例 + 独立 `Loader.traditional`"两处状态(双 volatile,有"读到新实例 + 旧签名"的多余重建窗口)⇒ 合并为只读实例字段 `current.trans`(final 字段 + volatile 发布 = 安全发布),`Loader` 只留 `INSTANCE`。

**核查通过(无问题)**
- KV:未登记的 String 键走 MMKV 原生分支(`KVDecoder.decode` 里 `wanted.isInstance(raw)` 直接返回)⇒ 不打日志、不依赖 `KVKeySpec` 登记;
- 全仓无 `Locale.setDefault`(不会污染 `isTraditional()` 读到的系统 locale);
- 组件:`Service` 仅 `PlaybackService`(已包裹);`SearchReceiver` / `CustomWebReceiver` 不取 string、无用户可见文案。

**工具**:`i18n_align.py --subset` 增 `REDUNDANT-VS-UPPER`(差异层里与上级层同值的条目,应删或改写)+ subset 模式不再打印超长 MISSING 清单。

**验证**:`compileDebugKotlin` + `compileDebugJavaWithJavac` EXIT=0;`i18n_align.py b+zh+Hant` 424=424 PASS;`zh-rHK --subset` 66 条 PASS(0 多余/0 冗余/0 占位符失配);`en` 424=424 PASS;`i18n_check_keys.py` 424=424;`i18n_gate.py` ui 0 / 非 ui 0;spec 条数口径 53→66 + 修订记录加行。

## 打包期语言过滤:androidResources.localeFilters(2026-09-22,用户问"是否需要在 app/build.gradle.kts 加这段")

- **先测量再改**:`aapt2 dump configurations` 显示 APK 内实际带 **27 个语言变体**(依赖库的 ar / de / fr / ja / ko / ru / es / pt / it / pl / nl / tr / hi / vi / th / ms / ne / in + en 各区域变体 + es-rUS / fr-rCA / pt-rBR / pt-rPT + zh-rCN / zh-rTW),确有可剥离项。
- **坑(实踩)**:`localeFilters` 的值被 AGP **原样透传**给 `aapt2 -c` ⇒ 脚本限定必须写 **`b+zh+Hant`**;照 BCP-47 写 `zh-Hant` 直接构建失败(`invalid config 'zh-Hant' for -c option`)。
- **配置**:`androidResources { localeFilters += listOf("en", "zh", "zh-rCN", "b+zh+Hant", "zh-rTW", "zh-rHK") }` —— 保留四语(简体=默认资源,无需列)+ 依赖库的中文资源(zh-rCN / zh-rTW),剥离其余 ≈22 个语言变体。
- **验证**:debug 与 release(R8 + `isShrinkResources=true`)均 EXIT=0;`aapt2 dump configurations` 两 APK 都只剩 `b+zh+Hant / en / zh-rCN / zh-rHK / zh-rTW` —— 四语资源齐全、无多余语言。
- **收益与定位**:体积收益 **KB 级**(debug 83.96 → 83.959 MB;APK 大头是 .so / Python / QuickJS,不是语言资源)⇒ 该配置的价值是"显式声明交付语言 + 防止将来依赖引入多语言资源",**不是瘦身手段**;且它**不会新增语言支持**(列表里写 ja/ko/de… 但没有 `values-*/strings.xml` 不会生效)。

## 首页首屏加载闸门收窄（2026-09-22）

**背景（用户原话）**：「进入首页后转圈圈然后才出现影视海报，能让这个加载速度更快一些吗」。

**排查（先只读，未改代码）**：首屏转圈由 `HomeViewModel.pageLoading` 控制，原关闭条件是 `bootReady && sortsLoaded && rec 非 Loading && 所有分区非 Loading` —— 而每个分区各自要发一次 `getList`（横向布局还受 `Semaphore(2)` 分批），于是**最慢的那个分类决定整屏首帧**；分区骨架屏代码（`PartitionSection` / `HomeGridLayout`）早已存在，只是被闸门挡着从未在首屏露过面。另发现 `AppBootstrap` 冷启动固定 `loadConfig(false)`（网络优先，本地快照只在失败时兜底）、jar 与配置串行装载 —— 经评估属"并行 / 缓存"议题（P2），本轮不动。

**对照上游（`示例文件/TV-fongmi`，只读参考）**：`VodFragment.showProgress()` 只覆盖 `homeContent`（分类 + 推荐）一跳，`setAdapter()` 一到就 `hideProgress() + showContent()` 并给 ViewPager 装 adapter（**此时才创建当前分类页**），分类页在 `TypeFragment` 里自己 `progressLayout.showProgress()` —— 即上游转圈本就不含各分类首屏。顺带核实：上游**没有**做配置缓存（`Decoder.getJson` 每次真网络，catvod `OkHttp` 未配 disk cache、`Config.json` 字段闲置）也没有 jar 并行（`initSite` 内同步 `parseJar`），所以它的"快"只来自闸门更窄。

**改动（对齐上游口径，2 文件，纯展示层）**：
1. `HomeViewModel` 闸门收窄为 `bootReady && sortsLoaded && rec != Loading`（去掉 `partitions.none { Loading }`），同时**去掉开闸时的 `watchdogJob?.cancel()`** —— 看门狗必须继续活着，它才是"分区超时 → 该分区 Error + 提示"的唯一出口，取消即退化。
2. `HomeGridLayout` 新增 `HomeGridSkeleton()`：竖向骨架在 2:3 海报下**预留 Stacked 卡片标题行高**（`6dp + titleSmall.lineHeight`），否则分区数据到达时网格每行下移约 26dp。

**口径取舍（都写进 spec §4.1）**：推荐位 `rec` **仍计入**闸门 —— 横向布局的 Hero 与推荐属同一块首屏，若不等它，`getHomeRecList` 那次额外请求回来时 Hero 骨架会被替换（宽度 0.78 屏宽 → 真 Hero 0.64 屏宽）或整块消失。未改动项：横向 Hero 骨架尺寸、分区骨架等宽 ≈112dp（真卡 110dp，差值 2dp）、下拉刷新仍是"整屏转圈"（沿用原行为）。

**验证**：`assembleDebug` EXIT=0；`adb install -r` 到 vivo V2425A（`10AF1J04JX0016G`）成功，待用户真机走查（冷启动转圈时长、竖向骨架→海报无跳动、下拉刷新 / 切源 / 切布局回归）。

**遗留**：①"切布局到横向"时的 Hero 骨架尺寸未对齐；②`loadConfig(false)` 网络优先、jar 与配置串行、sorts 未落盘 —— 继续提速属 P2，需用户拍板（会碰数据层或"首屏先出旧内容"的取舍）。

## 配置快照优先：冷启动跳过配置下载（2026-09-22）

**背景**：承接同日「首页首屏加载闸门收窄」——转圈缩短后，链路里剩下的第一跳（每次冷启动都重新下载订阅 JSON）成为可省项。

**核实**：`AppBootstrap.awaitLoadConfig` 固定传 `loadConfig(false)` ⇒ `ApiConfig.java:169` 的 `if (useCache && cache.exists())` 永不成立，本地快照只在**网络失败**时兜底（`:220`）。缓存文件路径 = `filesDir + MD5.encode(apiUrl)`（与 `ApiConfig` 同一算法），且只在 fetch 成功后才写。

**改动（1 文件，纯 Kotlin）**：`AppBootstrap` 新增 `useCachedConfig()`：地址是 `http/https` **且** 快照在 `CONFIG_CACHE_TTL_MS`（12h）内 → 传 `true`。本地 / 局域网源一律不吃快照（其改动必须立即生效）；TTL 保证最多 12h 陈旧，过期即回网络刷新并写回新快照（网络失败仍由既有回落分支兜底）。**不做 TTL 会让快照永久冻结** —— 服务端更新源后再也不会生效，这是本轮特意避开的回归。

**没做（两条实锤结论，已写进 spec §6.11）**：① 会话中热刷新配置 —— `parseJson` 第一行 `resetConfigData() → clearSpiderCache() → jarLoader.clear()` 会销毁所有 spider 与 DexClassLoader，而重装 jar 只发生在 `AppBootstrap`（`getCSP` 在 loader 为空时只返回 `SpiderNull`）⇒ 中途重解析 = 所有 spider 源失效到下次启动；要做"只落盘刷新"必须另开入口（`ApiConfig` 新增方法，待拍板）。② jar 并行预装 —— `JarLoader.load(MAIN_KEY, …)` 开头 `if (loaders.containsKey(key)) return true` 早退，预装旧 URL 的 jar 会让真实配置到达后**静默沿用旧 jar**，且预装本身会被那次 clear 清掉；收益仅 0.1~0.5s，不划算。

**验证**：`assembleDebug` EXIT=0；`adb install -r` 装机成功。待真机对比：冷启动转圈时长、改订阅地址是否立即生效、仓（合集）切换是否正常。

## 审查：P1 / P2 两轮改动的错误与回归排查（2026-09-22，修 4 处）

**第一轮（用户「审查是否有错误遗漏和引入新回归」）**

1. 🔴 **看门狗反而误伤（P1 引入）**：P1 删掉开闸时的 `watchdogJob?.cancel()` 后，看门狗变成 `loadHome()+20s` 无条件触发 —— 用户若在这 20s 内切 tab（`ensureLoaded`）或触发 `loadMorePartition`，请求还在飞就被判「部分超时」、该分区被打成 Error + toast，随后数据到达又覆盖成内容（骨架 → 错误闪现 + 多余 toast）。修：抽出 `armWatchdog()` 由 `requestPartition` 重新武装，改成**按请求计时**；`loadHome()` 仍武装一次兜住 homeContent 挂死。⚠️ 不能在开闸时 `cancel()`：首屏分区还在飞，取消掉它永远没有 Error 出口。
2. 🔴 **用户主动重载会吃快照（P2 step1 引入）**：换源 / 改地址 / 启动失败重试都走 `AppBootstrap.retry()`，而快照判据只看「地址 + 12h」不看触发者 ⇒ 重选一个 12h 内用过的源会直接解析旧快照，看起来像「重载没生效」。修：`retry()` 跳过快照；冷启动仍吃快照。

**第二轮（用户「继续审查是否还有错误遗漏和新回归」）**

3. 🟡 **看门狗计时点仍在"入队"而非"开跑"**：`armWatchdog()` 原先放在 `requestPartition` 顶部，而被 `loadSemaphore` 限流排队的请求还没真正发起（横向布局 8 个分类、并发 2）⇒ 排队分区可能刚开跑就被判超时。修：`armWatchdog()` 移进 `withPermit` 之内，按"拿到许可、真正发起"计时。
4. 🟡 **`freshConfig` 是跨线程共享可变字段**：主线程 `retry()` 写、IO 线程读并清零；连点两次 retry 时旧协程可能抢先清零，导致第二次不走网络。修：去掉字段，改为 `startInit(forceFresh: Boolean)` 参数透传（零共享状态）。

**核实通过（未改）**：快照路径算法与 `ApiConfig` 一致（`filesDir + MD5.encode(apiUrl)`）；快照分支与网络分支的副作用只差一个 `saveCache`（`clearApiLinesIfUnmatched` / `switchApiCollectionIfNeeded` / `parseJson` 都在）；快照损坏 → 异常被 catch → 落网络路径；网络失败 → 仍回落快照；仓（合集）首次切仓仍走网络、之后按子源地址命中快照；`-1`（未配置）与本地 / 局域网源（clan 等非 http）一律不吃快照；`useCachedConfig()` 跑在 IO 线程（不是主线程 I/O）；闸门收窄只会「更早开闸」，找不到"更晚开"或"卡死不撤转圈"的新路径；`sorts` 与 `partitions` 都在 `sortsLoaded = true` 之前赋值 ⇒ 无空 tab 窗口；竖向骨架高度 = 2:3 海报 + `6dp + titleSmall.lineHeight`，与 Stacked 卡逐项一致；`i18n_gate` 0/0（无新增硬编码文案）；`pageLoading` 全仓只有 `HomeViewModel` / `HomePage` 两处引用；`LoadConfigCallback.notice` 全库无调用点（不构成"快照路径漏通知"）。

**发现（既存问题，未动）**：直播侧 `LivePlayActivity.loadLiveConfigOnEnter()` 用 `loadLiveConfig(true)`，即**快照优先且没有 TTL** —— 快照存在就永不刷新，只有用户切直播源（`LivePlayViewModel` 传 `false`）或删掉快照文件才会重新拉。本次给点播侧加的 12h TTL 比它严格；要不要给直播侧补同样的过期策略属另一个待拍板项。

**验证**：`testDebugUnitTest`（230 例）+ `assembleDebug` 全绿；装机 Success；spec §4.1 / §6.11 已同步（看门狗按请求计时、用户主动重载走网络、标志走参数透传）。

**第三轮（用户「再审查一遍」）**

5. 🟡 **骨架预留高度依赖了未验证的假设**：`Spacer(6.dp + titleSmall.lineHeight.toDp())` 是否正确取决于 `TextUnit.toDp()` 是否按 `fontScale` 换算，而离线无法核实（Gradle 缓存里没有 compose `ui-unit` 的 sources jar）。修：改用 `rememberTextMeasurer().measure("M", style = titleSmall).size.height` **实测一行高度**后下传给 `HomeGridSkeleton`（与同文件 `HomeFilterChipsRow` 的用法一致；一次测量、不在每个骨架项里测量），彻底去掉该假设 —— 系统大字体下同样精确。

**更正上一轮的记录（不删旧文，在此更正）**：第一轮写的「下拉刷新仍是"整屏转圈"（沿用原行为）」不准确 —— 下拉刷新确实仍走整屏 spinner（`reload()` → `pageLoading = true`），但**撤 spinner 的时机跟着闸门一起提前了**：现在只等「配置 + 分类 + 推荐位」，随后由各分区骨架逐个填内容。也就是说下拉刷新与冷启动现在共用同一套渐进呈现，不再是"刷完一次性全出"。

**既存观察（非本次引入，未动）**：`HomePullRefreshIndicator` 的 `isRefreshing` 全库恒为 `false` ⇒ 下拉松手后指示器停在 `distanceFraction = 0` 的静态环上，而页面此时已被整屏 spinner 取代，顶部会同时存在一个静态环与中央转圈；属 2026-09-12 改版的遗留。

**第三轮核实通过**：本地源红线未受影响 —— `isLocalSourceUnreadable` / `isLocalSourceMissing` 只认 `clan://localhost/` 与 `file://` 两种"本机文件"形态，两者都不是 `http/https`，因此永远不会走快照分支（"本地源拉取失败不静默回落旧快照"的语义完整保留）；局域网 `clan://<ip>` 同样不吃快照（且它本来就快）；超时 toast 不会串页 —— `pageErrorEvents` 是 `replay = 0` 的 SharedFlow，而 `HorizontalPager` 默认 `beyondViewportPageCount = 0`，用户切到其它 tab 后 `HomePage` 已离开组合、收集者被取消，弹窗不会打扰其它页面，分区的 Error 状态仍留在 VM 里（切回来看得到「重试」）；`useCachedConfig()` 读的 `HawkConfig.API_URL` 与 `loadConfig` 用来算缓存文件名的 KV 键一致；`BootGuard.disableBootLoopingSource()` 在 `startInit` 之前同步执行 ⇒ 快照判据用的是"已被看门狗处理过"的地址，不会算出错误的缓存路径。

## 修复:切音轨报「视频播放出错」(media3 双音频渲染器时钟冲突)及四处同族缺陷（2026-09-22）

**现象(用户报告)**："播放视频时点击切换音轨,会出现视频播放错误";必现,伴随「播放出错,自动重试」与同地址重播,重播后再切必再崩。命中面 = **两条音轨落在不同音频渲染器**的片源(实测 AAC + 杜比 DDP/E-AC3 的 `…DDP2.0.2Audios.mp4`)。

**取证(本机 vivo ROM 吞 App 自身 tag 的 logcat)**：清空缓冲区后 dump 7770 行,属本应用进程的仅 **1 行**(同窗口 fongmi 的 AudioTrack 行却在),系统侧 MediaCodec/AudioFlinger 亦无输出 ⇒ **logcat 这条通道在本机不可用**。可用通道 = App 自己的文件日志 `files/preload_debug.log`(debug 包 `LOG.FILE_LOG=BuildConfig.DEBUG`,`adb shell run-as com.github.avbox.osc cat` 可读)。把切轨链路与播放错误落盘后,一次复现即拿到异常本体:

```
echo-exo-player-error: code=ERROR_CODE_UNSPECIFIED, msg=Unexpected runtime error
  | cause[0]=IllegalStateException: Multiple renderer media clocks enabled.
```

设备渲染器实测(新增一次性诊断 `echo-setTrack renderers:`):`[0]MediaCodecVideoRenderer [1]ExperimentalFfmpegVideoRenderer [2]MediaCodecAudioRenderer [3]FfmpegAudioRenderer [4]TextRenderer [5-8]MetadataRenderer [9]CameraMotionRenderer [10]ImageRenderer`。

**根因(media3 1.11.1 源码级)**：① 设备 MediaCodec 不认的编码,其轨组被 media3 映射到 ffmpeg 扩展渲染器(AAC→renderer 2,E-AC3→renderer 3);② `ExoPlayer.setTrack` 只清/设**目标**渲染器的 `SelectionOverride`,而 `DefaultTrackSelector.findDefinitionForType` 只取"第一个"同类 definition、**从不清其余渲染器** ⇒ 两路音频渲染器同时 enable;③ 音频渲染器都提供 media clock(`DecoderAudioRenderer` / `MediaCodecAudioRenderer.getMediaClock()→this`;`BaseRenderer`/`NoSampleRenderer`→null,视频/字幕渲染器不覆写),`DefaultMediaClock.onRendererEnabled` 直接抛上述异常;④ 异常经 fork `ExoMediaPlayer.onPlayerError → onError()` → `STATE_ERROR` → `ComposeVideoController` → `PlayContainer.errReplay()` → `errorWithRetry("视频播放出错")` → `PlaybackController.retryAfterStartedError()`(释放内核 + 同地址重播);又因切轨前已写入音轨记忆,重播后 `loadDefaultTrack` 把 override 钉回 ⇒ 必复现。

**修复(7 文件 +153/−24)**：

1. `player/ExoPlayer.setTrack`:同一 track type 只允许一路渲染器持有选择(设目标前清掉同类其余渲染器的 override;清掉即自动 disable,刻意**不写** `rendererDisabled` 粘性状态,以免影响后续换集自动选轨)。按类型而非"仅音频"—— 本机视频也有两路渲染器,双视轨虽不抛该异常,但同属"两个渲染器同时解码"的错误状态。
2. **音轨记忆带渲染器**:`util/AudioTrackMemory` 新增 `_exo_renderer` 键(渲染器/组/轨三元组);还原时用记忆里的渲染器,旧键(无该键)或渲染器表变化时回落第一个音频渲染器;`save/exoLoad/ijkLoad` 加空 `playKey` 守卫(不再生成 `audio_track_null_*` 垃圾键)。
3. **IJK 切音轨 NPE**:`IjkMediaPlayer.setTrack(index, playKey)` 的 `!playKey.isEmpty()`(`progressKey()` 是 `@Nullable`)抛 NPE 冒到 `PlayContainer` 的 catch ⇒ 紧随的 `seekTo/start()` 全不执行、**画面永久停在暂停**;守卫下沉到 `AudioTrackMemory`。
4. **fork `VideoView`**:① `release()` 原把释放整段挂在 `!isInIdleState()` 下,而 `stopPlaybackKeepPlayer()` 在 PREPARING/BUFFERING(刚点播放就退出)会留下「IDLE + 内核仍在」⇒ 释放退化成空操作、旧实例被 `initPlayer()` 覆盖且无人 release;② `startPlay()` 补"旧实例必先释放",把该类泄漏从"依赖调用方自觉"变成结构上不可能(引擎与页面桥的"防线"都调用同一个 state-gated `release()`,防不住)。

**审查(用户"是否有错误遗漏和引入新回归")**：

- **软/硬解切换、内核切换不会产生该错误**:两者都"释放内核 → 新建播放器实例 → 全新 `DefaultTrackSelector`(Parameters 从零)",而该异常的必要条件是"同一实例内两路同类渲染器同时被选中";两条路径的生效机制另已核对(EXO `requireKernelRebuild` + 重建;IJK `setCodec()` 后 `reset()→setOptions()`)。
- **审查自查发现并修掉 2 处(其一为本轮引入)**:① 切轨诊断里读了 IJK 位置,而该 runnable 在 try 块之外执行、fork `IjkPlayer.getCurrentPosition()` 无保护且 `release()` 是**后台线程异步**释放 ⇒ 200ms 内内核被释放即可崩主线程(已改为只读播放状态);② 上述 `startPlay()` 覆盖旧实例。
- **核实未改**:清 override 不会反而触发自动选轨(`findDefinitionForType` 取到非空即跳过自动选轨,其余渲染器因无 selection 被 disable);旧记忆兼容(读出 -1 → 回落);KV 动态键契约(调用侧 -1 默认值,无需登记 `KVKeySpec`);白名单新增 5 个前缀所命中的**全部**日志无热路径;`STATE_ERROR` 诊断在 ERROR 下走 `isInPlaybackState()==false` 分支、不碰已释放内核;fork `release()` 全部调用点都不依赖旧的"IDLE 时 no-op"。
- **验证**:`testDebugUnitTest` 230 例 0 失败;`assembleDebug` / `assembleRelease`(R8 + shrink,64.75 MB)全绿;真机 vivo V2425A:修复前 `applied` 后 126ms 即 error,修复后音轨 **2→1→2→1 四次来回切 0 error** 且位置持续推进(18141→364334→366050→375257→377086 ms);退出重进 `applied renderer=3 … mime=audio/eac3 playKey=`(空 playKey = 记忆还原);切内核 EXO↔IJK、切解码 硬解↔软解 与冒烟(起播/换集/切轨/退出重进)通过。

**实测背景(解释命中面;本 bug 非 E-AC3 专属)**：本机 ROM 把 AC3/EAC3/DTS 硬件解码器整段注释(`/vendor/etc/media_codecs_vivo_c2_audio.xml` 的 ac3+eac3 块、`media_codecs_vivo_audio.xml` 的 ac3+eac3 块),AOSP 配置亦不含 `eac3` ⇒ 系统无 `audio/eac3` 解码器,E-AC3 只能走 App 内置 ffmpeg 软解,因而与 AAC 分属两路渲染器;对照 AAC 因有 AOSP `c2.android.aac.decoder` 仍走 MediaCodec。故命中面是"两条音轨落在不同音频渲染器"的任意片源(AAC+DDP / AAC+DTS / AAC+AC3)。

**遗留(已登记,未动)**：① fork `IjkPlayer.getCurrentPosition()/getDuration()` 无保护,任何"释放后读位置"的调用点都可能崩;② `IjkMediaPlayer.setTrack(int)` 用音轨/字幕轨下标挡**视轨**切换,下标相同时静默不切(视轨与内置字幕共用该方法);③ `ExoPlayer.setPreferSoftwareDecode` 是进程级静态位(点播/直播/音乐页共用;每次起播前都会推一次,当前安全但耦合较紧);④ 音轨记忆仍按 (渲染器,组,轨) **下标**存,同片内同编码分多 group 且换集顺序变化时可能落到同渲染器内另一条轨(按格式/语言记才是彻底解)。

**更正(同日)**：上条落盘时改动面记为 7 文件 +153/−24;随后按 `SKILL.md` 注释红线(不写日期与过程叙事、单条 ≤2 行)精简了本轮新增的代码注释,最终为 **+122/−25**,功能与验证结论不变 —— 代码里只保留「不写会再踩的坑」,过程叙述以本条目为准。

## 修复:小窗转全屏后底栏那一排控件字号突变(mm 档 × 方向补偿不同步)（2026-09-22,方案 A 已落地）

**现象(用户报)**:竖屏详情页小窗(预览态)播放中切全屏,底栏菜单行字号明显变小(约 1/2.2);收起底栏再呼出、或之后某次重组又能恢复,故看着像"时好时坏"。

**根因(读码 + 真机日志)**:覆盖层字号 = `playerTextSize` = `AutoSize 换算出的 mm px` × `portraitCompensation()`(竖屏 屏高/屏宽,横屏 1)。两个因子来源不同 ——
① 补偿只由 Compose 的 `LocalConfiguration` 驱动,方向一变**当帧**就变;
② mm→px 只由 `AutoSizeConfig.screenWidth` 决定,而它有**两个写入者**:AutoSize 1.2.1 在 `AutoSizeConfig.init()` 里给 Application 注册的 `ComponentCallbacks`(`ScreenUtils.getScreenSize(application)` = **显示**宽),以及 `BaseActivity.refreshAutoSize()`(`getDefaultDisplay().getMetrics()` = **窗口**宽,只在 `onResume`/`onWindowFocusChanged`/300ms 延迟里跑)。窗口≠显示(分屏/自由窗口/桌面模式)时两者分歧;小窗↔全屏/旋转时 mm 值可能仍是旧方向/旧窗口的 ⇒ 尺寸差最大 2.2 倍。
**且错了不会自愈**:`App.java` 关了 supportDP/SP ⇒ AutoSize 只改 `xdpi`,Compose 的 `LocalDensity` 不变,改它不产生任何状态失效;而 `playerTextSize` 已把结果烙进 `TextUnit`(底栏只在 `controlsVisible` 由假变真时整棵重建 ⇒ "收起再呼出就正常")。spec §6.10 早前已把"旋转后约 300ms 内 mm 档仍是旧值"记为坑,这里是它的必然结果。

**日志证据**:同机(vivo V2425A,1260×2800)同一次抓取里同时出现 `targetDensity = 0.984375`(=1260/1280,窗口宽)与 `2.187500`(=2800/1280,显示宽);`.logs/cap2.txt` 整场 105 次适配全是 `0.984375`,而 12:52:51 / 12:54:20 确有 `ViewRootImpl: AppSizeAfterRelayout size: Point(2800,1260), rotation: ROTATION_90`;库的 Application 级回调确实被调用(旋转那一刻打了 `initScaledDensity = 3.5 on ConfigurationChanged`)。`.logs/audio_switch_capture.txt` 里 `Point(1260,2800), rotation: ROTATION_90`(竖屏形小窗挂在横屏显示上)配上 `2.1875`/`0.984375` 两个值,即"小窗偏大 → 全屏掉一半"的直接对应。

**修法(最终 4 文件)**:`PlayerOverlay.kt` 的 `playerDim`/`playerTextSize` 不再读 AutoSize 换算后的 px,改为 `原始 mm 数值(TypedValue 取,判 COMPLEX_UNIT_MM)× 窗口长边 / 1280`;比例由 `playerMmScale()` 提供(`LocalWindowInfo.containerSize` 优先、首帧回落 `Configuration`+`LocalDensity`,容器一变必然重组重算);删掉 `portraitCompensation()`。**稳态与旧实现等价**(旧式 = mm×屏宽/1280×屏高/屏宽 = mm×长边/1280;仅取整顺序不同,≤1px;该等价与设备/方向/密度无关,手机·平板·电视·分屏一致)。非 mm 单位(dp/sp)回落系统换算。`EpisodeSheet` 列数启发式里 3 处直读 `resources.getDimension*` 一并改走同一套 helper(否则估宽与实际按钮宽度错位、列数算错)。`DetailScreens`/`PlayerBottomBar` 两处 `playerDim(vs_30)` 推导的 padding 加 `.coerceAtLeast(0.dp)`。注释按红线只留"别改回 `getDimension*()` + 方向补偿"这一条坑。

**刻意不动**:`ComposeVideoController.initNativeSubtitleViews` 的原生字幕 padding 仍走 AutoSize —— 对齐长边口径会把竖屏 padding 放大 2.2 倍(5→11 / 15→33 / 20→44 px),属可见变化且与本 bug 无关,留给"AutoSize 退役"专项。

**验证**:`compileDebugKotlin` ✓、`assembleDebug` ✓、`testDebugUnitTest` 230 例 0 失败(均 `--offline`)。

**⚠️ 装机后事故与更正(同日)**:首版装机后**每次进详情页必崩** —— `IllegalArgumentException: Padding must be non-negative @ DetailScreens.kt:190`(该行是 `16.dp + playerDim(vs_30)/2 - 20.dp`,Compose 要求 padding 非负)。抓 logcat 得 `raw=1.0769E-41` / `dp=0.2857`:根因是我用 `TypedValue.getFloat()` 取 dimen 原始值 —— 维度值的 `data` 是**定点编码**(`30mm` → `data=0x1E05` = 尾数<<8 | 单位),`getFloat()` 只是把这段位模式按 IEEE 浮点重解释 ⇒ 1e-41 ⇒ `playerDim` 全变 ~0.29dp ⇒ 式子变负。正确 API = **`TypedValue.complexToFloat(tv.data)`**(返回原始单位数值 30.0f)。旧代码走 `getDimension*()` 所以从没踩到,这是本次新引入的坑。已修 + 真机确认详情页恢复正常(不再闪退)。

**连带伤害(BootGuard,必须告诉用户)**:反复闪退被 `BootGuard` 判成"这个源把进程崩掉",两个远端源被写进持久黑名单(`HawkConfig.BOOT_DISABLED_SOURCES`)、配置管理页标「已禁用」—— 用户看到的"所有源都不能用"实为此事,不是源/网络坏了。恢复路径:配置管理页点该源行 → 二次确认弹窗 → 确定(`BootGuard.enableSource()`)。

**防御性加固**:两处依赖 `playerDim(vs_30)` 的算式(`DetailScreens.kt` 全屏入口底距、`PlayerBottomBar` 预览态底距)加 `.coerceAtLeast(0.dp)` —— 长边 < ~342dp 的小窗口下该式子本来就会变负并直接崩 Compose(与本次 bug 无关,是既有脆弱点)。

**教训(已写进 spec §6.10)**:① 读 dimen 原始值只能用 `TypedValue.complexToFloat`,不能用 `getFloat()`;② 任何由尺寸推导出来的 `padding/size` 都要钳非负 —— Compose 对负值是**抛异常**,不是忽略;③ 这类改动必须真机走一遍(本次编译/单测全绿仍然崩,离线无法发现)。

## 弹窗壳统一:app 内覆盖层对话框 + 播放器面板动画 + 删除僵尸选集面板(2026-09-22,未 commit)

**批次(30 文件,+590/−417)**:

- **app 内对话框去平台窗口化**:新增 `ui/components/Dialogs.kt` —— `AVBoxDialog`/`AVBoxAlertDialog` 走应用窗口内覆盖层(与 `AVBoxBottomSheet` 同一套宿主路由/遮罩/动画),观感照抄 M3 `AlertDialogDefaults`/`AlertDialogContent`(形状/配色/24dp 内边距/按钮排布);进场 0.90→1.0 缩放 + 淡入 220ms、遮罩 `BottomSheetDefaults.ScrimColor.copy(alpha = 0.6)` 与面板同一进度;键盘弹起面板自己上移(`imePadding`,覆盖层没有 dialog window 帮忙避让 IME);收弹窗时显式 `clearFocus(force = true)` + `keyboard.hide()`(平台 dialog 是"窗口没了键盘跟着没")。`BottomSheet.kt` 抽出 `OverlayRequest` 统一路由(有 `SheetHost` 槽位就投窗口根、否则就地渲染)+ `SheetVariant.BOTTOM/CENTER` + `SheetHostScaffold` + `LocalSheetDismissThen`。**7 处平台 `AlertDialog` 全部替换**(MainScreen 启动失败 / PreferenceSettings 语言重启 / ConfigManage 源停用 + 新增订阅 / HistoryPage 删除确认 / SettingsPage 文本编辑 / LiveScreens 密码),**8 个独立 Activity 全包 `SheetHostScaffold`**。
- **播放器侧保留平台 Dialog、只加窗口内动画**:`PlayerSheets.PlayerDialog` —— `Dialog(usePlatformDefaultWidth = false)` 不动(独立窗口天然屏蔽播放器手势、隔离返回键),内容 0.92→1.0 缩放 + 淡入 220ms,退场先播完再回调;面板内关闭入口一律 `LocalPlayerSheetDismiss`(只关闭)/`LocalPlayerSheetDismissThen`(先播退场 → 执行动作 → 关闭;弹幕设置→搜索用它避免两个窗口重叠);自铺一层遮罩收"点面板外空白"(平台 `dismissOnClickOutside` 在满屏内容下**永不触发** —— `DialogLayout.isInsideContent` 比的是整屏 Box 的实测尺寸);遮罩**变暗**仍是平台窗口 dim(瞬现,Compose 拿不到)。6 个面板(弹幕设置/搜索、字幕设置/搜索、投屏、轨道选择)全部改走该壳。
- **删除僵尸选集面板**:`player/ui/EpisodeSheet.kt`(右半屏面板 + 左半屏点击关闭)连同 `EpisodeSheetState` / `PlayerUiState.episodeSheet` / `VodControlListener.showEpisodeDialog` / `PlayerActions.onNext|PreLongClicked` 一并移除 —— 它唯一的触发链(长按底栏「上一集/下一集」)在 Compose 化后没有任何 UI 绑定,属僵尸路径(旧的 Paint 测宽 1~4 列自适应逻辑随之退场);播放器不再有选集入口,选集回竖屏详情页选集行。
- **详情页**:排序按钮图标与文案同向(都表达"点一下会切到什么":`reverseSort=true` → 新增 `ic_episode_order_asc`(向上箭头)+「正序」,否则 `ic_episode_reverse` +「倒序」);选集网格底部 `contentPadding` = `navigationBars` insets + 16dp(面板底色仍铺到屏幕最底,只把收尾行抬起来)。
- **防御**:`PlayerOverlay.playerTextSize` 对换算结果加 `isFinite()/coerceAtLeast(0f)`(TextUnit/尺寸为负或非有限时下游布局/排版直接抛异常)。

**关键坑(已写进代码注释)**:

- **阻断式弹窗不能走退场动画**:`dismissible = false`(启动失败必须重试/离线二选一)时点遮罩/返回键只调 `onDismissRequest`、不置 `dismissing` —— 否则"播了退场却没人清状态"会让面板隐身留场(`dismissing = true` 还会吞掉后续关闭入口),覆盖层继续吃掉整屏触摸 = 用户卡死;带动作的关闭(重试/离线)必须照常走,否则按钮变死键。
- **组合销毁兜底分两种**:`plainDismissPending` 只补"点遮罩/返回键"路径的 `onDismissRequest`;带动作的关闭(`after != null`)**绝不能补** —— 它的收尾不是 `onDismissRequest`(语言切换对话框"取消=回滚",补调会把用户刚确认的动作反过来)。播放器侧 `PlayerDialog` 6 处 `onDismiss` 都是"置 null"幂等,才敢统一兜底。
- **退场 220ms 的自保**:面板还挂着时加一层吃触摸的盖子(旧实现 `onDismiss` 当场摘状态,没有这个窗口),否则连点两个选项触发两次动作;`closing` 兼作防重入,退场期间重复关闭直接吞。
- **覆盖层内容必须 `fillMaxWidth`**:`AVBoxAlertDialog` 的内容列靠它拿到"面板右侧",按钮的 `align(End)` 才有可对(我们的壳层 Surface 不传 M3 `propagateMinConstraints` 的 280dp 最小约束),否则按钮贴到左边(装机反馈)。
- **槽位唯一 = 后提交者顶替**:面板里点出确认对话框时(ConfigManage 仓面板 → 停用源确认)必须同步收掉面板状态(`if (pendingSwitch != null) repoSheetOpen = false`),否则对话框关闭后面板会"复活";`SheetHost` 用 `key(req.id)` 重建覆盖层,避免复用上一个请求的 `Animatable/dismissing` 状态被退场动画带走。
- **就地渲染的隐蔽性**:契约是"有槽位投窗口根,否则就地渲染" —— 就地渲染时 `Box(fillMaxSize)` 会被调用点容器吃掉。装机事故:`PreferenceSettingsActivity` 没提供槽位,其"切换语言"对话框被渲染进设置列表**卡片内部**且没有遮罩 ⇒ 独立 Activity 一律套 `SheetHostScaffold`(新增弹层若写在行内/列表内尤其必须)。

**验证与审查**:

- `:app:compileDebugKotlin` + `:app:compileDebugJavaWithJavac`(Java 侧单跑;本批改到 `PlayContainer.java`/`PlayerControlApi.java`)exit 0;`i18n_gate` 0/0(无文案变更)。
- 审查(用户"查看 git 历史,未提交的代码是否有错误遗漏和引入新回归"):4 类删除符号全仓 0 残留(详情页同名 `EpisodeSheet` 是另一实体)、平台 `AlertDialog(` 0 残留、player/ui 无绕过退场动画的直接 `onDismiss()`、6 处面板 `onDismiss` 幂等 + 全部对话框确认动作自清状态逐条核对(含 `enableAndSwitch`)、弹层顶替场景仅 ConfigManage 一处且已修、8 个有弹层的 Activity 全已套 host(Play/PreloadSettings 无弹层)⇒ 结论 = 代码层无错误/明确回归。
- **待真机走查**:① 密码弹窗(LivePlayActivity)/弹幕 API(PreferenceSettings)/新增订阅(ConfigManage)三个 Activity 未声明 `windowSoftInputMode=adjustResize`(仅 MainActivity/Search 有),新覆盖层靠 edge-to-edge 的 IME insets 避让键盘 —— 验证"键盘弹起面板上移、收键盘回位、输入框不被挡";② 播放器面板"点面板外空白关闭"是新增交互(220ms 退场期间不双触发、不穿透);③ 观感差异(非 bug):仓面板→停用源确认时面板被同步清状态、退场动画被绕过;`PlayerDialog` 0.92 vs `AVBoxDialog` 0.90 而注释写"同参数"。
- 文档:spec 同步更新(文件布局行去掉 `EpisodeSheet.kt`、`PlayerDialog` 归入 `PlayerSheets.kt`、播放器对话框段落改为"保留平台 Dialog + 面板动画"、§6.5 新增"独立 Activity 必须套 `SheetHostScaffold`"规则);本节为过程记录,随审查一并补齐双副本同步。

## 搜索设置面板新增「首页海报」标题(2026-09-22,未 commit)

- **需求(用户,附截图)**:搜索设置 sheet 里「横向展示 / 竖向展示」分段的左上角增加标题「首页海报」,注意多语言。
- **改动(4 文件)**:`ui/components/SearchSettingsSheet.kt` 的 `headerContent` 里、`CapsuleSegmentedButton` **之前**插入一个 `Text`(`titleMedium` + `onSurface` + `padding(start/end 16dp, top 8dp)`)。`AVBoxBottomSheet` 的标题区顺序是"把手 → headerContent → title",故新标题落在分段正上方且左对齐,字号/颜色与 sheet 的 title(「搜索设置」)同款;`headerContent` 的两个兄弟节点由外层标题区 `Column` 直接纵向排列,无需额外包 `Column`。资源 `search_home_poster` 三层齐备:`values`「首页海报」/ `values-en`「Home posters」/ `values-b+zh+Hant`「首頁海報」;港差异层按"与基础层同值不进差异层"不新增(回落基础层)。
- **验证**:`:app:compileDebugKotlin -q` exit 0;`i18n_gate` ui 0 / 非 ui 0;`i18n_check_keys` `declared=referenced=425`(424→425,无重名/未用/未声明/同值多 key);`i18n_align` `en` / `b+zh+Hant` / `zh-rHK --subset` 全 `RESULT: PASS`(港层 `REDUNDANT-VS-UPPER=0`);4 个改动文件行尾未混(kt 与 en/Hant 层 CRLF、`values` 层 LF,均保持原样式)。
- **待真机**:标题与把手/分段的间距观感(8dp)、英文 `Home posters` 是否被截断。
- **顺手发现(未动)**:`.codebuddy/tools/check_line_endings.py` 的文件清单仍列着已删除的 `player/ui/EpisodeSheet.kt`,直接跑会 `FileNotFoundError` 中断(上一批删文件后未更新脚本)。

## ✅ 订阅源兼容性对齐:站点级 4 字段 + 3 项 A 级修复(2026-09-23,用户"四项都做了";五轮静态审查收敛,未 commit)

- **背景**:用户要求把订阅源(配置 JSON)支持面与 fongmi/影视TV 5.6.3(`示例文件/TV-fongmi`,只读对照)对齐,并明确"只要订阅源支持情况,过滤掉播放器/DLNA/Android Auto 等无关功能"。流程 = 字段级差异清单 → 按"改动量 × 副作用"分级 → 做 A 级 3 项 + B 级站点级 4 项。**评估后否决**:顶层 `headers`(唯一注入点 `OkGoHelper` 的全局 client 会波及配置拉取/jar 下载等十余处流量,且需处理与 `LIVE_WEB_HEADER`/播放结果头的覆盖顺序)、直播配置顶层 `proxy/rules/ads` 合并(`VideoParseRuler`/`AdBlocker` 是全局单份且 `AdBlocker.clear()` 零调用点)、`lives[].core/groups` 等 tvbus 字段(无引擎,解析无意义)。
- **A 级 3 项**:①`rules[].exclude` 按 fongmi `Sniffer.isVideoFormat` 语义(URL `contains` 或正则 `find` 命中即否决,优先于内置嗅探正则)新增 `HOSTS_EXCLUDE`,解析分支带 `isJsonArray` 类型守卫;②`lives[].type` 未知取值**保持拒载**(与上游 TVBox 同款;fongmi 的 `Live` 无 type 字段),只补 `resetLiveKvOnUnsupportedLine()` 复位 EPG/播放内核/UA + 诊断日志 `echo-live-unsupported-type`;③hosts 双输 bug:`parseLiveJson` 原先无条件 `new HashMap<>()` 抹掉点播 hosts,且 `OkGoHelper.myHosts` 只在点播侧刷新 ⇒"点播被清、直播没用";改为 `vodHosts`/`liveHosts` 分离 + `getMyHost()` 合并视图(点播优先)+ `refreshHosts()` 唯一刷新出口。
- **B 级 4 字段**:`indexs` 进 `SourceBean`、**删除** `SourceIndexFlags.kt` 与它的单测(原实现二次读配置缓存文件取标记);`hide` 只作用于源切换列表(当前首页源例外)+ 首页兜底源 `firstVisibleSite()` 跳过 hide,搜索侧与 fongmi 一致不过滤;`danmaku:0` 只拦自动搜弹幕(`DanmakuApi.canSearch(SourceBean)`,手动搜索与 spider 自带弹幕不受影响);`header` 覆盖 11 处 type 0/1/4 站点请求(`SourceViewModel.siteGet()` + `RemoteTVBox.post` 带 header 重载处理超长 extend 的 POST 分支)+ 播放结果兜底头(`mergeSiteHeaders` 4 处:type3 / type0-1 / type4 / 直连播放)。
- **五轮静态审查修出的自引入回归(教训,三条都是"抄既有写法"踩的坑)**:①`exclude` 缺类型防御 → 字段写成字符串时 `getAsJsonArray` 抛异常,**整份配置报"解析失败"**(改动前该字段被忽略、配置可正常加载);②规则表清空点放进 `resetConfigData()` → 换源**之前**就清空,换源失败(走 `callback.error`,不进 `parseJson`)时规则真空 → 在播内容广告回归、click 脚本失效;③`mergeSiteHeaders` 直接 `optJSONObject("header")` → 会把源返回的**文本形态** header 整块覆盖(源自带的 Referer/UA/token 全丢),改为先用 `PlayerHelper.extractPlayHeaders` 归一化再"只补缺键"。
- **两处高危面(同轮修)**:①非法 header 名/值会让 OkHttp 在**构造请求时**抛 `IllegalArgumentException`,而站点请求分支**都没有 try/catch** = 可复现崩溃。对照 fongmi:它也不校验,但所有 `SiteApi` 调用都包在 `ViewModelTaskRunner.execute` 的 FluentFuture 里,只表现为"该源空结果" ⇒ 在 `parseHeaderObject` 收口过滤(名 0x21-0x7e、值 tab+0x20-0x7e),单测 `parseSites_dropsIllegalHeaders` 锁定;②`firstVisibleSite` 打破"首页源 == 站点列表第 0 项"后,`isFirstSource`(豆瓣首页源绕过 sort 缓存的判据)失准 ⇒ 改比 `getHomeSourceBean()` 的 key 并更名 `isHomeSource`,顺带给 `mHomeSource` 加 volatile。
- **撤回两处早期误判(审查纠正)**:"要兼容 `sites[].logo`"—— fongmi 5.6.3 的 `Site` 既无 `logo` 也无 `icon`,官方字段表里站点无图标项(文档里的 `logo` 是顶层 App Logo 与直播默认 Logo 模板);"site `type=4` 不支持"—— `SourceViewModel` 的 getSort/getList/getSearch/getPlay/getDetail 都已有 `type==4` 分支。
- **验证**:`testDebugUnitTest --tests ConfigParserTest` 29 tests / 0 failures(新增 `parseSites_readsHideIndexsDanmakuAndHeader`、`parseSites_dropsIllegalHeaders`)+ `assembleDebug` BUILD SUCCESSFUL;`read_lints` 无诊断。**未装机**。
- **§7 遗留项"其它配置驱动的 header 未过滤"一并做掉(用户"一起做了")**:把字符集护栏抽成 `util/HeaderGuard.kt`(Kotlin object + `@JvmStatic`,口径 = 名 0x21-0x7e、值 tab+0x20-0x7e,**刻意取最严**:高字节区间各 OkHttp 版本行为不一致),`ConfigParser` 里原来的两个私有方法删掉改调它;接入 **5 类来源共 7 处** —— `sites[].header`、`lives[].header`、`channels[].header`、`parseBean.ext` 的两个解析分支(type 0 走 WebView/UA、type 1 走 OkGo)、`parses[].ext` 的聚合解析分支(`JsonParallel.getReqHeader`,`Headers.of` 会抛 IAE 且被自己的 catch 吞成"该 jx 静默失效")、`push://` 的 `@Headers=` 标记头。第六轮审查顺带补三处类型防御:live/channel 的 header 循环加 `isJsonPrimitive()`、`lives[].header` 加 `isJsonObject()`、`lives[].ua` 改 `safeJsonString`(原写法在字段写成非标量时抛异常 → 整条直播线路被静默丢弃);live/channel 的跳过日志前缀也拆开(原为同一个 `echo-live-header-skip`)。新增 `HeaderGuardTest`(纯 JVM:空名/null 值/空间名/中文/CRLF/0x7f 边界)。验证:`ConfigParserTest` 29 + `HeaderGuardTest` 3 全绿 + `assembleDebug` SUCCESSFUL。
- **文档落位**:仍生效的硬约束写进 `avbox-mobile-ui-spec.md` **§6.12**(8 条,含"必须保留"的护栏);§7 保留三项未做项(解析/嗅探链路不带站点头、`mergePushHeaders` 同款文本形态覆盖缺陷、"只补缺键"与 fongmi"整块为空才兜底"的口味差异)。

## 流程约定:审查收敛判据 + 验证前置(2026-09-23,用户"怎么每轮都审查出问题的"→"写进文档吧")

- **触发**:订阅源兼容性对齐这一轮工作累计 6 轮静态审查,每轮都有新发现,用户追问为什么。
- **对账(约 20 处发现)**:本次自引入 5(非法 header 崩溃面、`mergeSiteHeaders` 覆盖文本形态 header、规则表清空点放错、`exclude` 缺类型守卫、`isHomeSource` 不变量失准);既有坑约 10(hosts 双输、`lives[].type` 串味、`lives[].ua`/`lives[].header` 缺类型防御、`JsonParallel` 漏接、`mergePushHeaders` 同款缺陷、`AdBlocker` 只增不减、嗅探/代理头未过滤、解析/嗅探链路不带站点头);被审查纠正的早期误判 5(`sites[].logo` 是伪需求、site `type=4` 其实已支持、`parses[].type` 语义差异撤回、`lives[].type` 风险分级下调、顶层 `headers` 由"做"改"跳过")。
- **根因**:①证据面逐层打开(第一轮问"支不支持",第五轮才问"字段写错类型会怎样"),每层都必然带出新发现;②"照抄既有写法"会把既有缺陷一起复制(`mergeSiteHeaders` 抄 `mergePushHeaders`、`exclude` 抄 `doh` 分支);③跨文件隐式不变量只有做"改动点 × 全库调用方"碰撞才暴露;④前 3 轮没有编译兜底,发现的都是"会崩 / 会失效"级,跑了 `assembleDebug` + 单测后降到错误防御 / 日志级。
- **结论与落位**:严重度曲线(阻断 → 中 → 低)即收敛证据,不以"零发现"当停止标准;两条仍生效的约定写进 `SKILL.md`「交付验证与审查收敛」。剩余低优先项照旧挂 `avbox-mobile-ui-spec.md` §7(`M3u8PurifyUseCase` 出口护栏、`mergePushHeaders` 同款缺陷)。
- **未做**:未再跑一轮验证(用户选择收尾);`M3u8PurifyUseCase` 出口护栏仍是唯一"有明确收益但未做"的低优先项。

## 启动看门狗:崩溃栈过滤(界面 bug 不再把源停用)(2026-09-23,用户"直接改吧";一轮静态审查修 1 处)

- **需求(用户)**:「比如说一些 ui 问题导致的应用崩溃,会不会把源给禁用了」—— 会:看门狗只看"崩溃时刻距上次 jar 装载的毫秒差"与"同源装载计数",不读崩溃栈 ⇒ 界面 bug 崩在装载后 10 秒内会被判成装载阶段崩(一次即停用),连环崩 3 次还会走 `count >= 3` 兜底。
- **落地**:`BootGuard.install()` 只在 `looksSourceRelated(Throwable)` 为真时才写崩溃标记;判定遍历 cause + suppressed 全链的帧,**全部**落在白名单内才算"无关"。判不出(无帧 / null / 过滤自身抛错)一律按"有关" —— 漏判会让坏源重新把应用锁进启动崩溃,比误禁更难救。
- **白名单**:`android.` `androidx.` `java.` `javax.` `kotlin` `dalvik.` `libcore.` `com.google.android.` `com.github.tvbox.osc.ui.` `com.github.tvbox.osc.base.`;**刻意不含 `com.github.catvod.`**(jar/js/py 装载器与爬虫都在这条链上)。已否决的更省事写法:只匹配 `com.github.catvod.spider.` 帧(自定义包名 jar / js / py 会漏判)、按崩溃线程过滤(漏掉"爬虫崩在主线程")、"爬虫调用深度"计数器(要十几处插桩)。
- **本轮审查(用户"根据 SKILL.md 文档审查是否有错误遗漏和引入新回归")修 2 处(1 中 1 低)**:过滤只解决"写不写标记",但 `BOOT_LOADING_COUNT` 每次装载都 +1(界面崩 3 次也会把它推到 3),之后**任何一次**被判"有关"的崩溃(播放器 / okhttp / `util` 栈)都会在下次启动停用正常源 —— 触发条件比改前更隐蔽。修复 = `disableBootLoopingSource` 在**没有崩溃标记**时把计数清零,语义改为"连续 N 次**因与源有关的崩溃而重启**";同步 `MAX_LOAD_ATTEMPTS` 与类注释、`shouldDisable` 的口径描述。低 = 白名单原写裸前缀 `kotlin`(会顺带放过任意 `kotlin*` 开头的第三方包),改精确为 `kotlin.` + `kotlinx.`,并在 UI 用例里补 `kotlinx.coroutines` / `kotlin.coroutines` 两帧锁住。
- **复核无问题(有据可查)**:①`shouldDisable` 的 `crashElapsed <= 0` 守卫使计数永远无法单独触发停用;②标记每次启动读后即删,不存在多标记共存;③崩溃路径只遍历**已捕获**的栈(Android 在抛异常时填栈),无额外捕获开销,`LOG.i` 的 `boot-guard:` 前缀不匹配 `FILE_LOG_PREFIXES` ⇒ 只落 logcat;④无新增 KV 键 ⇒ 不涉 `KVKeySpec` 登记;⑤新增日志串无中文 ⇒ 不触发 i18n 卡口;⑥`FileUtils.repairBogusNativeLibs` 只认 `*.so`/`.lib*`,不会误删标记文件。
- **未决(已落 spec §6.13 / §7)**:白名单只覆盖平台 + `ui`/`base`,栈里带 `util` / `viewmodel` / 播放器包装帧的界面 bug 仍可能被判"与源有关"。收口 = 放宽到全部 `com.github.tvbox.osc.`(代价:播放内核包装崩溃不再算源的问题),属独立决策。
- **顺带发现(既有,非本次引入)**:`.codebuddy/skills/android/` 与 `skill/` 两份文档不同步(`SKILL.md`、`avbox-mobile-ui-spec.md`、`history/features.md` 三处 DIFF),而 skill 加载器读的是前者(缺「交付验证与审查收敛」、§6.12/§6.13)⇒ 后续会话可能按旧规则动手。本次只改 `skill/`(文档地图与检索约定指向的那份)。同步与否待用户定。
- **验证**:`:app:testDebugUnitTest` **238 用例 / 0 失败**(其中 `BootGuardTest` 20 = 基线 15 + 新增 5)+ `:app:assembleDebug` 绿;`read_lints` 无诊断。**未真机验证** —— 需造一次界面栈崩溃确认不禁源、一次爬虫崩溃确认照旧禁源(logcat 关键字 `boot-guard:`)。**测试空白(说明,非遗漏)**:计数清零与标记文件读写依赖 KV/文件,纯 JVM 单测覆盖不到。

## 观看历史改为"真在播才落库"(2026-09-23,用户"这不合理吧,正确的方式应该怎么做";静态审查两轮修 2 处)

- **需求(用户)**:先问"保存历史是看过了才保存,还是只要点开加载好就保存" —— 核查确认是**点开就保存**(详情页数据加载成功即落库),用户判定不合理;给出上游做法后选定"收紧为真在播才落库"。
- **原实现的三处提前落库(全部早于取流成功)**:`DetailViewModel.preparePlaySession()`(详情页加载完自动起播的会话登记)、`syncPlayingVodInfo()`(被 `PlaybackController.play()` 开头的 `TYPE_REFRESH(vod())` 触发,即"请求播放"),以及 `is Int` / `is JSONObject` 两个用户动作分支。后果:误点进详情页即留痕;`updateTime` 每次重写 ⇒ 只看一眼也把旧剧顶到历史最前;历史条目与进度条对不上(进度只在真播放时写,于是出现"有条目无进度"的空壳);线路全挂也留痕。
- **落地**:新增 `RefreshEvent.TYPE_PLAYBACK_STARTED`(=22,不复用已废弃编号);`PlaybackProgress` 兼作落库信号源 —— 位置真的推进过(判据见下方"实机反馈修正")、非直播、本会话本集未发过才 post 一次,`token = sourceKey|vodId#playFlag#playIndex`(带集与线路,切集/换线重发以更新"看到第几集"),`flush()` 收尾清标记(历史被删后重看仍能重新入库);`DetailViewModel.onPlaybackStarted()` 收信号后落库并校验在播内容与本页一致(id + sourceKey + playFlag + playIndex);`preparePlaySession()` 与 `syncPlayingVodInfo()` 里的 `insertVod()` 删除。判据复用进度写入的同一前提(时长 > 0),历史条目与进度条从此一一对应。
- **判据为什么放播放层**:会话只是"登记要播什么",取流可能失败、也可能没起播就退出;上游 fongmi 本身是 `saveVisit`(浏览)/`save`(观看)分离的模型,原实现等于把 saveVisit 塞进了 save 的口子。
- **连带面(改动前自查的隐式约定)**:①`insertVodRecord` 仍是观看历史的唯一落库点(无痕模式拦截在此,未动);②历史消费方仅 `HistoryPage`(列表)与 `DetailViewModel.onDetailResult`(续播恢复)⇒ 收紧后"没看过就没有记录",续播从第 1 集开始,符合直觉;③`App.vodInfo` 是全局单值、只在 `preparePlaySession()` 设置,信号靠它做归属判定 ⇒ 校验四元组而非仅 id;④`insertVod()` 顺带刷新 `info.playNote`(字幕搜索默认词),移走时必须补回。
- **审查修出的自引入回归(2 处:1 中 1 中)**:①`insertVod()` 从 `preparePlaySession()` 移走后,它顺带刷新 `info.playNote` 的副作用一起丢了 ⇒ 首次播放 `preview.playNote` 为空,`PlayContainer.openSubtitleSearchSheet()` 的字幕搜索默认关键词变空串(标题/通知不受影响,`publishTitle()` 走 `currentSeries().name`)。修法 = 提取 `refreshPlayNote(info)`,`preparePlaySession()` 与 `insertVod()` 都调(try/catch 防御一并保留)。②归属校验只比 id/sourceKey 时,音乐页接管影视内容后改的是**同一份 session**(`controller.vod() === session.vod() === App.vodInfo`,内容 id 不变)⇒ 详情页的迟到写入会把记录覆盖回交接那一集;校验加上 `playFlag`/`playIndex`。
- **实机反馈修正(用户"修复好像没有效果啊,只要加载完开始出现转圈圈下方是网络然后就会保存记录,哪怕这个视频显示加载失败")**:首版判据是"位置 > 0",而**起播的起始位置本身就来自上次进度** —— `player/src/main/java/xyz/doikki/videoplayer/player/VideoView.java:222` 读 `mProgressManager.getSavedProgress(...)`、`:321` `mMediaPlayer.setStartPosition(mCurrentPosition)`(另有 `onPrepared()` 里 `!isStartPositionApplied()` 的回退 `seekTo`)交给内核 ⇒ 缓冲期拿到的位置就是上次看过的非 0 值(取流失败时 `STATE_IDLE` 的 `flush()` 也带着它),"没播成也进历史"。最终判据 = **只累计平滑推进**:`stepAdvanceMs(position, last)` 只接受 `1..MAX_STEP_MS(10s)` 的步进(回拖 ≤0、seek/起始位置落位造成的跳变一律不计入),同一会话累计过 `MIN_ADVANCE_MS(1s)` 才发信号;换集/换线重置采样,`flush()` 收尾清采样与已发标记。采样点仍只有 `onProgress`(内核进度定时器,节流前)与 `flush`(暂停/播完/释放,兼作定时器早停时的兜底采样)。**副作用(可接受)**:真实播放不足 1s 即退出不留痕(符合"看过"语义);`MIN_ADVANCE_MS` 是唯一旋钮,调大即更保守。
- **第二轮审查(用户"审查一下是否有错误遗漏和引入新回归")修 2 处**:①**中·本次引入** —— 中间版本用的"首样本立基线、之后位置相对基线推进 ≥1s"仍会被**跳变**骗过:若起始位置"请求了但内核还没落位",首个采样是 0、下一采样直接跳到上次看过的位置(如 90 分钟),这个 seek 跳变会被算成"推进"⇒ 改为按**逐次步进**累计,跳变不计入(即上一条的最终判据),并补 `stepAdvanceMs_ignoresJumpAndRewind` 锁口径;②**低·本次引入** —— `shouldMarkWatched` 的 `isLive` 参数在唯一调用点恒为 `false`(直播已在 `markWatched` 开头提前 return),属误导性参数 ⇒ 去掉该参数,直播守卫留在 `markWatched`(纯 JVM 覆盖不到,单测注释已注明)。
- **已知代价与残留(说明,非缺陷)**:①详情页预览会自动起播 ⇒ "点开详情页且真的播过 1 秒"仍会留痕(这是"真在播"判据的定义使然;要更保守只需调大 `MIN_ADVANCE_MS`)。②`flush()` 清标记 ⇒ 暂停/继续、退后台回前台会多写一次记录(有界,用户动作驱动)。③信号被接收方丢弃时标记已消费,最坏情况是写入推迟到该集暂停/播完/退出,数据最终一致。④跳变只"不计入"、不清零已累计量(语义 = 累计真实观看推进量)。⑤既有问题(非本次引入,未动):`is Int` 分支全仓无发送方(死代码);若直播上报非 0 时长,`PlaybackProgress` 仍会把进度写到上一部点播的 key 上(本次的 `isLiveMode` 守卫只挡落库信号,不挡进度写入);`preview.playNote` 在播放器内切集时不更新(只在 preparePlaySession 时机刷新)。
- **顺带修复(构建脚本,与本功能无关)**:`pyramid/build.gradle.kts` 的 `buildPython` 原写死 `D:/Programs/Python/Python38/python.exe`(别的机器路径)⇒ 本机 chaquopy 报 "Couldn't find Python 3.10"。改为「`-PbuildPython` / `CHAQUOPY_BUILD_PYTHON` 优先 → Windows 标准安装位置(`user.home` 相对,不写死盘符/用户名)兜底 → 都没有则交给 Chaquopy 自行探测」。
- **验证**:静态审查两轮 + 实机反馈后一轮,共修 5 处(2 中 3 低)。`:app:assembleDebug` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL**(不带 `-PbuildPython` 也通过,验证了构建脚本的默认分支),单测 **243 用例 / 0 失败 / 0 跳过**(基线 238 + `PlaybackProgressTest` 5 例:平滑步进计数 / 停滞不计 / 跳变与回拖不计 / 累计阈值 / 每集只发一次),`compileDebugKotlin`、`compileDebugUnitTestKotlin`、`assembleDebug`、`testDebugUnitTest` 均为**实执行**(非 UP-TO-DATE)⇒ 改动确实过了编译器与打包。待验清单:点开详情页秒退不留痕、**续播进入详情页但取流失败不留痕/不刷新记录**、正常看几秒留痕且进度条同步、切集后历史集数更新、音乐页交接后切歌不回退、无痕模式下不落库、字幕搜索默认词非空。
- **未做**:音乐页 `syncHistory()` 仍是"点歌即写"(用户显式动作,且它是纯音频页唯一写入口 —— 详情页可能已 finish 而无人接收信号),未纳入本次收紧。

## 排查:冷启动后第一次点底栏 tab 掉帧(2026-09-23,真机实测定位,未改代码)

- **现象(用户报)**:冷启动进应用后第一次点液态玻璃底栏 tab 掉帧;概率性;只在开启侧滑动画时出现。
- **复现与量化(V2425A,1260×2800 @120Hz)**:`dumpsys gfxinfo framestats` 抓到 —— 冷启动后第一次点,burst 前 ~15 帧每帧丢 1-2 个 vsync(≈200ms 顿挫);同进程第二次点同一动作只剩前 3 帧丢(整段仅丢 4 个)。**稳态 8.3ms/帧不掉帧** ⇒ 玻璃本身不是"一直太重"。
- **三条被实测否掉/降级的假设**:①**色散折射着色器首编译**(初判主因)—— 冷/热差异是"~10 帧平台期 + 组合/主绘/GPU 三相位同时均匀抬高 ~10ms",不是单帧尖峰 ⇒ 降级为"候选之一,无独立证据";②**JIT 阻塞主线程** —— 主线程 `Lock contention on Jit code cache` 在点击窗口内出现 159 次,但**实际等待总耗时仅 ~0.5ms** ⇒ 否掉;③**玻璃稳态超预算** —— 稳态 8.3ms/帧 ⇒ 否掉。
- **实测到的真正成因**:①ART 把首次执行的动画链路现编现用 —— `Compiling baseline` 在点击窗口内 90ms(冷启动阶段累计 1.58s),代码处于解释/未优化态时每帧在三个相位各多花 ~3-4ms;②**触屏触发刷新率提升**:`ViewRootImpl#setFrameRateCategory high hint, reason touch` ⇒ 面板从 60Hz 跳到 120Hz,预算当场 16.7ms→8.33ms,而这一刻正好是冷态动画;③burst 期间主线程跑在 CPU4 的 **614-1017MHz**(芯片上限 3.1GHz),同样的活要多花约 3 倍时间(触摸那一帧在 CPU1@2.9GHz)。
- **结论**:不是逻辑 bug,也不是库缺陷。库的真实份额是**余量不足** —— 120Hz 下单帧 GPU 4.8-7.2ms + `eglSwapBuffers` 4.0-4.5ms 已吃掉 8.33ms 预算的大头,留给"首次执行"的余量几乎为零(60Hz 面板上这个现象会不可见)。
- **待决修法**:①`isTabSwitching()` 期间再降一档效果(现仅保留 `blur()`,blurDp 默认 20dp→70px 半径,很贵),把 GPU 从 ~7ms 压到 ~4ms 即可让前 10 帧落进预算,且只发生在 300ms 过渡里、视觉几乎无感;②冷启动空闲帧跑一次 1px 的 pager `scrollBy`(不可见)预热 pager 滚动 + 源层重录 + 玻璃层 GPU 程序(按压路径无法不可见地预热);③不修。
- **抓取方法论(踩过的坑,复用前必看)**:①`dumpsys gfxinfo framestats` 表头是**新版 24 列**(`Flags,FrameTimelineVsyncId,IntendedVsync,Vsync,InputEventId,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,FrameDeadline,FrameStartTime,FrameInterval,WorkloadTarget,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,…`),按老版 14 列解析会得到满屏"456 秒"的假数据;②判丢帧要用**相邻帧 `IntendedVsync` 间隔 ÷ `FrameInterval`**;`FrameCompleted - IntendedVsync` 是流水线延迟(稳态也有 15-20ms),拿它判 jank 会全错;③gfxinfo 自带的 `Janky frames` 会被抬高的 deadline 洗掉(热态那次只报 1/101)⇒ **不可作判据**;④atrace 文本解析:comm 名可含空格(`Jit thread pool`),正则要用 `(.*?)-\d+`,别用 `(\S+)`;ART 的 JIT 切片名是 `Compiling baseline`/`Compiling optimized`,**不是** `JitCompile`;⑤`/proc/uptime` ≠ `SystemClock.uptimeMillis()`(差的是深睡时间),换算墙钟别拿它当基准。

## 实施 B:冷启动导航动画不可见预热(2026-09-23,已装机,真机 A/B 验证有效)

- **动机**:上一条排查的成因①(ART 把首次执行的动画链路现编现用)。目标 = 把这份成本从"用户第一次点击"挪到冷启动后的空闲帧,且**不产生任何可见变化**。
- **改动 4 处(纯新增 50 行,无删除)**:①`DampedDragAnimation` 新增 `warmUp()` + 文件级 `internal const PRESS_WARMUP_PROGRESS = 0.01f` / `PRESS_WARMUP_MS = 32`;②`InteractiveHighlight` 新增 `warmUp()`(复用上面两个常量,避免两份魔法数);③`FloatingNavBar` 在 `interactiveHighlight` 定义之后加 `LaunchedEffect(dampedDragAnimation, interactiveHighlight) { withFrameNanos×2 → dampedDragAnimation.warmUp(); interactiveHighlight?.warmUp() }`;④`MainScreen` 加 `LaunchedEffect(pagerState) { withFrameNanos×2 → pagerState.scrollBy(1f); scrollBy(-1f) }`(预热 pager 滚动 → 页面测量 → 玻璃源层重录那条链路)。
- **为什么用"极小幅度"而不是"藏起来真按一下"**:把导航栏 alpha 置 0 再真按会让整条导航栏闪 1-2 帧,等于制造一个新的可见变化。改用 0.01 的按压进度:换算到形变是栏 scale 1.00014、指示层 1.0039、色散折射 0.35px —— 全部不足 1px,但弹簧/缩放/高光/色散着色器都真的执行了一次。
- **验证(同协议各 3 样本)**:`input swipe X Y X Y 120` 同坐标按住 120ms 可稳定复现底栏点击,配合 `force-stop → am start → sleep 9 → gfxinfo reset → swipe → dump framestats` 全自动跑多轮。tab 坐标(1260×2800,density 3.5):首页 x=210 / 历史 x=449 / 收藏 x=729 / 设置 x=1029,y=2583。
  - 前 19 帧丢 vsync:无 B 20/21/20(均值 20.3)→ 有 B **13/12/13(均值 12.7)**,约 **-37%**
  - 整段丢 vsync:无 B 22/23/22 → 有 B **13/12/13**,约 **-43%**
  - 前 12 帧均值:组合+测量 8.6-9.0 → 7.7-8.0ms;主绘 9.3-9.9 → 7.8-8.4ms;GPU 8.1-8.5 → 6.7-6.9ms
  - 尾段:无 B 仍零星丢帧,有 B 全 0(干净收敛到 90Hz)
  - `:app:assembleDebug` + `:app:testDebugUnitTest` → BUILD SUCCESSFUL,243 用例 / 0 失败(与基线一致);已 `adb install -r` 装机。
- **判据坑(务必记住)**:`FrameInterval` **逐帧变化**(同一次动画里 120Hz 与 90Hz 混着),判丢帧必须用**该帧自己的 FrameInterval**;拿第一帧的 8.33ms 当全局预算会把 90Hz 的正常帧全判成丢帧 —— 我先用错判据算过一轮,数字全废。
- **中途的一次错误结论(如实记录)**:在自动化协议建立前,我用"单样本 + 缓冲区含冷启动帧"的数据得出过"B 比基线更差",当场纠正。**跨条件比较必须同协议 + 多样本**,设备侧波动足以把结论带反。
- **未根治 + 未做**:B 之后仍有约 12-13 个丢帧 —— 与成因②(触屏把面板抬到 120Hz、预算腰斩)和③(burst 期间主线程只有 614-1017MHz)一致,这两条不在应用可控范围。**A(过渡期降模糊半径)未做**,因为它会改观感,等用户拍板。**未提交**。

## 首页订阅源 sheet 打开即定位到当前选中源(2026-09-23,用户"能否让首页左上角订阅源胶囊的 bottomsheet 弹窗一打开就出现在当前选中的站点上,就像选集 bottomsheet 弹窗那样")

- **原实现为什么"停不下来"**:源列表是**单个 `LazyColumn` item** 里的 `SettingsGroup { sources.forEachIndexed { … } }` —— 一个 item 内部没有可定位的落点,`scrollToItem` 对它无效;想给这一 item 套 `verticalScroll` 也会被 LazyColumn 的无限高度约束掐死。⇒ 必须先把源改成**逐项 lazy item**。
- **落地(只改 `ui/page/HomePage.kt` 一处)**:`rememberLazyListState()` + `selectedIndex = sources.indexOfFirst { it.key == currentSource?.key }`;`LaunchedEffect(Unit) { if (selectedIndex > 0) listState.scrollToItem(selectedIndex) }` —— **无动画、一次到位**,与选集 sheet(`gridState.scrollToItem(playIndex)`)/ 音乐页队列 sheet(`queueListState.scrollToItem(queueIndex)`)同款。键用 `Unit` 而不是 `selectedIndex`:每次弹出都是新组合(`if (showSourceSheet)` + `SheetHost` 的 `key(req.id)`),所以"开一次定位一次";若键成 `selectedIndex`,用户换源时列表会在眼皮底下跳。
- **视觉零改动,但两处间距必须手工接住**(逐项化会带走 `SettingsGroup` 的 `spacedBy(2.dp)` 与 LazyColumn 的 `verticalArrangement = spacedBy(20.dp)`):①源之间 2dp ⇒ 每个源项 `padding(bottom = 2.dp)`(末项 0dp);②"源列表 / 配置接口"组之间 20dp ⇒ 配置项改 `SettingsGroup(modifier = padding(top = 20.dp))`。**不能**把 `verticalArrangement` 加回 LazyColumn:源之间的 2dp 会被一起放大到 20dp。
- **顺带的收益**:源列表从"一次性组合全部行"变成懒组合(配置里 100+ 站点不罕见)。
- **刻意没做**:①不动 `SettingsCardPosition` 的 FIRST/MIDDLE/LAST 圆角逻辑;②不传 `key`(源 key 万一重复,LazyColumn 会直接抛 `IllegalArgumentException` 崩掉弹窗;sheet 生命周期短、打开期间列表不增删,位置标识足够);③空源 / 单源下 `selectedIndex` 为 -1 / 0,自然不滚动。
- **验证**:`compileDebugKotlin`、`assembleDebug`、`testDebugUnitTest` 全部 **BUILD SUCCESSFUL**(纯 UI 改动,无单测覆盖)。**待真机验证**:选中项在中间 / 末尾时打开即定位;选中项是第 0 项时不跳动;空源与单源不异常;打开过程中下拉手势仍只挂在把手 / 标题区(内容自带滚动容器,`isScrollable = false` 未变)。
- **顺带发现的文档漂移(未改,待用户确认)**:`avbox-mobile-ui-spec.md` §4.1「源级策略的由来与切换」仍写着订阅源 sheet 每行右侧有「搜索 / 详情」标记(`CardPolicyPill`),但该功能在 `6ad3deb`(2026-09-21)已被删除,HEAD 全仓无 `SourceCardPolicy` / `source_card_policy` ⇒ 规范该段(含 §4.1 卡片点击分发的第 3 条"源级策略")与实际代码不符。

## 首页订阅源 sheet 加站点查找(2026-09-23,用户"有些订阅源有几十个站点,打开弹窗后一个一个找很麻烦,如果能直接搜索就好了")

- **需求与定稿(用户三选)**:①搜索框**始终显示**(不做"站点数 ≥ N 才出现");②匹配**站点名 + 接口地址**;③范围**只做首页订阅源弹窗**(抽公共组件但不改配置管理页 / 换仓 sheet)。UI 方案先出图给用户过目(两张状态:打开即定位 / 输入关键词过滤)再动手。
- **落地 4 处**:
  1. `util/SiteSearch.kt`(新增):`filter(sources, query)` —— `query.trim()` 为空则原样返回(同一实例),否则 `name.contains(q, true) || key.contains(q, true)`。**保持原顺序**(不按相关度重排,否则选中项位置乱跳)。`SourceBean.getKey()/getName()` 经 `safeString` 恒非 null,过滤侧不需要判空。
  2. `ui/components/SheetSearchField.kt`(新增):单行就地过滤输入框(全圆角 / `surfaceBright` + `outlineVariant` 描边 → 聚焦 `primary` / 48dp / 前置放大镜 / 有内容时右侧 40dp 清空钮 / `ImeAction.Search` 只收键盘)。**不自动聚焦**(弹窗首要用途是选源,一开就弹键盘更烦)。
  3. `ui/components/BottomSheet.kt`:`SheetOverlay` 面板容器的 `imePadding()` 由"仅居中对话框"改为**两档都挂** —— 底部弹层此前没有输入框所以没暴露;这次必须加,否则键盘盖住列表。**不需要自己算"屏高 − 键盘高"**:`imePadding` 收窄子级约束,面板自然顶不到屏幕上沿。⚠️ 连带行为 = 底部弹层可用高度随键盘收缩,固定项(输入框)先占位、滚动区吃剩余空间。
  4. `ui/page/HomePage.kt`:搜索框放**内容区顶部**(不放 `headerContent` —— 那里挂着下滑关闭手势,会与输入手势抢触摸);列表改用 `filtered`;无命中 = `LoadStateBox` 空态(160dp + `ic_empty_record`,照抄配置管理页的 `errorText = ""` / `retryText = ""` 写法);「配置接口」入口不参与过滤。
- **与"打开即定位当前源"的协作**:`LaunchedEffect(query.isEmpty())` + 守卫 —— 打开(空词)定位当前源、打字期间**不动列表**(跟着跳没法用)、清空后回到定位。空词时 `filtered === sources`,选中下标不变。
- **文案**:`home_site_search_hint`("搜索站点名称或地址" —— 把匹配范围写进 hint,省得用户猜能不能搜地址)、`home_site_search_empty`("没有匹配的站点"),入 `values` / `values-en` / `values-b+zh+Hant` 三份;**`values-zh-rHK` 是差异层,港台用词相同故不加**。清空钮复用已有的 `common_clear`。
- **单测**:`SiteSearchTest`(4 例:名/地址双命中、忽略大小写与首尾空格、空词原样返回、保持顺序 + 无命中)。
- **验证**:`:app:assembleDebug` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL**,单测 **247 用例 / 0 失败 / 0 错误**(基线 243 + 新增 4);并校验了 APK 内容(dex 含 `SiteSearch`、`resources.arsc` 含新文案)。**未真机验证**(用户明确要求不要操控其设备)。
- **待真机确认**:①键盘弹起后面板是否稳在键盘之上、列表能否正常滚到底;②输入时列表不跳、清空后回到当前源;③无命中时"配置接口"入口仍可点;④点搜索框以外的内容区能否正常拖动关闭(下滑手势只在把手/标题区);⑤英文/繁体下 hint 不截断。
- **本轮两轴审查(用户要求每轮改动后自查)**:
  - **错误遗漏 / 本次引入(低-中,已修)**:引导态(未配订阅接口)下首页胶囊仍可点,而 `sources` 为空 ⇒ 弹窗在**没输入关键词**时也显示"没有匹配的站点",语义错。修法 = 空词分支改用 `config_empty_subscribe`("暂无订阅"),非空词才用 `home_site_search_empty`(一行 `stringResource(if …)`,与详情页 `detail_order_asc/desc` 同一写法)。触发路径已核对:`ApiConfig.sourceBeanList` 初始为空 ⇒ `getSwitchSourceBeanList()` 返回空表。
  - **既有问题(未改,非本次引入)**:①站点行标题 `bean.name ?: bean.key` 的 `?:` 是死代码 —— `SourceBean.getName()` 走 `safeString` 恒非 null ⇒ 无名站点显示**空白行**;搜索按地址能搜到它,但那一行是空的(本次改动让它更容易被撞见)。②`SheetSearchField` 在 `sources` 为空时仍显示(用户明确选了"始终显示",未改)。
  - **口味差异**:清空钮出现/消失会让输入区宽度跳一下(40dp);搜索框没有独立语义节点(hint 是与它并列的 `Text`)。
  - **确认无回归**:①`imePadding` 两档化对无输入框的弹层无感(无键盘时 inset 为 0);②过滤后 FIRST/LAST 圆角按下标重算,单条 = SINGLE;③`query` 每次弹出重新 `remember`(新组合)⇒ 不残留上次关键词;④打字期间 `LaunchedEffect(query.isEmpty())` 不触发滚动;⑤空词时 `filtered === sources`,选中下标与"打开即定位"行为不变。
- **刻意没做**:拼音首字母搜索(项目无拼音库,加依赖或搬工具类都超出最小改动);相关度排序;配置管理页订阅源列表与「换仓」sheet 的子源列表(同一痛点,`SheetSearchField` 已可复用,留待用户点头)。

## 订阅源 sheet 搜索框改为"搜索页同款"(2026-09-23,用户"这个搜索框控件的设计风格改成和搜索页一样的效果,跟随液态玻璃或者surface")

- **动机**:首版的 `SheetSearchField` 是自绘输入框(`surfaceBright` 底 + `outlineVariant`/聚焦 `primary` 描边、48dp、40dp 清空钮),与搜索页顶栏那枚胶囊搜索框不是一套观感。
- **做法(不是"再抄一遍样式",而是共用同一个组件)**:把 `SearchScreens.kt` 里的 `internal fun SearchField` **提到 `ui/components/SearchField.kt`**(`fun`,加一个 `hint` 参数,默认 `search_field_hint`),删除自绘的 `SheetSearchField.kt`,两处调用同一份实现 —— 从根上消除"两处样式漂移"。连带:搜索页调用点(`SearchActivity`)补 import,`SearchScreens.kt` 清掉 8 个因此失效的 import(`BasicTextField`/`KeyboardActions`/`KeyboardOptions`/`Icons.filled.Close`/`Search`/`SolidColor`/`glassTopBarSurface`/`ContinuousCapsule`),行为零改动。
- **视觉差异(相对首版)**:形状 `RoundedCornerShape(percent = 50)` → `ContinuousCapsule`;去掉描边;高 48dp → **40dp**(内层 `Box(height(40.dp))`);前置图标 18dp → **24dp**;清空钮 40dp 触摸区 → 18dp 图标 + 4dp padding(与搜索页一致,**低于 48dp 规范**,但用户要求"和搜索页一样",故照抄)。`cardContainer` 实测就是 `surfaceBright`(见 `ui/theme/Color.kt`),所以底色本来就没差,差的是描边/高度/玻璃感知。
- **⚠️ 弹层里"跟随液态玻璃"只能跟随到一半(如实记录)**:`glassTopBarSurface` = `glassSurface(LocalTopBarGlassBackdrop.current, …)`,而该 CompositionLocal **只由 `AppTopBarScaffold` 在 TopAppBar 范围内提供**;弹层被 `SheetHost` 提到窗口根渲染 ⇒ 在订阅源 sheet 里它恒为 `null` ⇒ `glassEnabled = false` ⇒ 走 `.clip(shape).background(cardContainer)` 实底。**搜索页顶栏那枚是真玻璃,弹层这枚是实底 surface**。要弹层也真玻璃有两条路(均未做):①给 `SheetOverlay` 单独铺 `LayerBackdrop`(要解决"采样谁",且 §6.10 明确同一 `LayerBackdrop` 不能挂两个 `layerBackdrop` 节点);②改用 `Modifier.glassSurface(shape, color)`(`emptyBackdrop()` 非 null ⇒ 跟随玻璃开关,但采样为空,效果 = 45% `surfaceBright` + 高光/内外阴影;项目已有先例 = 配置管理页选文件按钮 `ConfigManagePage.kt:924`)。
- **验证**:`:app:assembleDebug` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL**,**247 用例 / 0 失败**(与改前一致);APK 内容校验:dex 内 `SearchField` 14 处、`SheetSearchField` **0 处**,`resources.arsc` 含新文案。**未真机验证**(观感类改动,只能上眼)。
- **搬迁等价性是比对过的(不是"看着一样")**:`git show HEAD:…SearchScreens.kt` 取出旧函数与新文件 `diff`,只出现 3 处预期差异 —— `internal fun` → `fun`、新增 `hint: String = stringResource(R.string.search_field_hint)`、`text = stringResource(…)` → `text = hint`;其余逐字节一致 ⇒ 搜索页行为零改动。
- **本轮自查又修 1 处(低,本次引入)**:共用组件时**调用方漏了 `fillMaxWidth()`**(旧的自绘组件把 `fillMaxWidth()` 写在内部,换组件后就丢了)。`BasicTextField` 的 `weight(1f)` 会让 Row 撑满、肉眼看不出来,但那是依赖隐式行为 ⇒ 已补上与搜索页一致的 `Modifier.fillMaxWidth().padding(…)`。

## 修:订阅源 sheet 收起键盘时"闪一下"(2026-09-23,用户"收起键盘的时候弹窗往下收缩时会闪烁一下,很奇怪的感觉")

- **成因(上一版自己引入的)**:为了让搜索框不被键盘盖住,给 `SheetOverlay` 的**面板容器**挂了 `imePadding()` —— 而那层容器**同时包着遮罩与面板**。键盘弹起时容器内缩,遮罩跟着缩到键盘之上(键盘遮住的那块**没有遮罩**);键盘收起时遮罩要在**一帧内**长回整屏,而键盘自己是用 250ms 滑走的 ⇒ 遮罩区域的变化与键盘的揭示不同源,观感就是"闪一下"。**这是把遮罩和面板挂在同一层 inset 上的必然结果,不是偶发。**
- **修法(2 处)**:
  1. 遮罩移出 `imePadding` 那层 —— 外层改 `BoxWithConstraints(fillMaxSize)` 里先铺**恒满屏**的遮罩,键盘只顶内层的面板(面板行为与上一版一致:仍被顶到键盘之上)。
  2. 面板高度上限由 `LocalConfiguration.current.screenHeightDp` 改为 `BoxWithConstraints` 的 `maxHeight`(实际布局高度):`LocalConfiguration` 会在窗口尺寸变化时**整帧换值**,与键盘 inset 不同源,是同一类"跳一下"的隐患(顺带在多窗口/折叠屏上更正确)。
- **验证**:`:app:assembleDebug` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL**,**247 用例 / 0 失败**。**待真机复测**(观感类改动,按约定不自行操控用户设备)。
- **若仍闪:下一步方案(已想清,未做)** = 彻底切断面板与键盘 inset 的耦合 —— 面板不再被 `imePadding` 顶起,改为「聚焦搜索框时把面板撑到固定高(保证输入框落在键盘之上)+ 列表底部按键盘高度加 `contentPadding`(保证最后几行能滚出来)」,键盘只是**盖住**列表、面板一动不动 ⇒ 收起键盘时**零位移**,任何"闪/跳"都无从产生。代价:要给共用的 `SearchField` 加一个 focus 回调,并引入"键盘高度预留"常量(可用「聚焦期间见过的最大键盘高度」闩住,收起时不缩回)。这也是搜索页的行为(键盘只盖住结果、顶栏不动)。
- **诊断信息(留给复测)**:若改后仍闪,需要区分「遮罩闪(整屏一起暗一下)」与「面板跳(弹窗瞬间位移/变高)」,以及「短源列表(面板矮,会被键盘完全盖住)是否比长列表更明显」——前者指向遮罩/图层,后者指向面板的 inset 耦合。
- **第一轮修法(遮罩移出 inset 层)**:当时用户回「正常了」,但**后续复测发现 bug 仍在**,并给出了关键观察 ——「弹窗收回时底部会闪出下方的首页背景(影视海报 + 底部导航栏)」。⇒ 第一轮判断**不完整**:遮罩那层确实是缺陷(该留),但真因不止于此。
- **真因(由用户观察定位)**:`imePadding` 挂在**包着遮罩 + 面板的那层容器**上 ⇒ 面板是被"顶上去"的,它的**底边 = 屏高 − 键盘高**;键盘一收,底边往下追,中间就漏出下方页面(底栏那块最显眼)。判据是"**哪条边会动**",不是"遮罩颜色对不对" —— 第一次只盯遮罩,漏掉了"面板是被顶起而非贴底"这个更基础的事实。
- **最终修法(1 行,结构上根治)**:键盘让位从**外层容器**挪到**面板内部** —— 贴底弹层的 `Column` 挂 `imePadding()`,容器只给居中对话框挂。于是**面板底边恒贴屏底、只有顶边随键盘升降**,漏底在结构上不可能发生;即使 inset 动画与键盘滑走不同步,最坏也只是露出一条**面板自身底色**(不是下方页面)。代价:键盘弹起时面板整体变高(列表可见高度略减,`0.9 × 屏高` 上限照旧)。
- **验证**:`:app:assembleDebug` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL**,**247 用例 / 0 失败**;待用户复测(观感类,不自行操控其设备)。

## 补:订阅源 sheet 搜索框跟随液态玻璃开关(2026-09-23,用户"搜索框怎么没有液态玻璃效果,要跟随设置页的液态玻璃应用控件开关啊")

- **原因**:`glassTopBarSurface` 读 `LocalTopBarGlassBackdrop`,而该 local **只由 `AppTopBarScaffold` 在 TopAppBar 范围内提供**;弹层被 `SheetHost` 提到窗口根渲染 ⇒ 恒为 null ⇒ 退化成 `.background(cardContainer)` 实底,与玻璃开关无关。
- **先试过、又否掉的方案(记下来省得再走一遍)**:给 `SheetOverlay` 铺一块 `LayerBackdrop`(面板当底,照 `AppTopBarScaffold` 的 `onDraw = { drawRect(panelColor); drawContent() }`)并把 local 提供给面板内容。**否掉的理由**:①面板**不透明**,玻璃能采样的只有一块纯色 ⇒ 视觉与"空底"一致,却多付一层 `LayerBackdrop` 每帧录制;②玻璃控件就在被录制的那棵子树里(面板 Column 内)⇒ 玻璃会采到**自己**,项目里另两处先例(`AppTopBarScaffold` 录的是顶栏**下方**的页面内容、`MusicPlayerScreen` 录的是封面)都刻意把玻璃排除在录制范围外;③实现时还因多插一层 `Box` 与 `Column` 撞出重复花括号(编译报 `Expecting '}'`),白跑一轮构建。
- **最终做法(3 行,改在共用的 `SearchField` 里)**:有顶栏 backdrop ⇒ `glassTopBarSurface`;取不到 ⇒ **`glassSurface`(空底玻璃)** —— 仍跟随「液态玻璃应用控件」开关,只是没有可折射的内容可采样(效果 = 45% `surfaceBright` + 高光边 + 内外阴影,面板色透过半透明填充可见)。先例 = 配置管理页选文件按钮(`ConfigManagePage.kt:924`)用的就是这一路。搜索页顶栏行为**零改动**(它仍有 backdrop,走原分支)。
- **验证**:`:app:assembleDebug` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL**,**247 用例 / 0 失败**。**待真机看观感**(我按约定不碰设备;若觉得"空底玻璃"太透或太淡,可换成给面板铺 backdrop 那条路 —— 代价见上)。

## 液态玻璃效果扩充:厚度感 / 边缘色散 / 通透度(2026-09-23,用户"123都加上")

- **背景**:用户问"液态玻璃库除了模糊和扭曲是不是还支持其他效果,比如 iOS 27 样式的液体玻璃"。逐文件读完 `libs/backdrop` 后确认能力面远大于现状 —— 四层共 24 个可调入口,项目只接了 12 个;其中两个最标志性的 iOS 光学特征几乎躺着:**色散**只在底栏选中指示层开了、**厚度纵深**全仓零使用。用户看完对照表后要求把先前提的三条建议**全部实施**。
- **能力盘点(供后续复用,不必再读一遍库)**:库的公开 API 共 **30 个**(含重载),可独立改动的**取值参数 42 个**。分层:
  - **①采样源 `Backdrop`(5 种实现,结构性选择)**:`rememberLayerBackdrop`/`rememberCombinedBackdrop`(2/3/vararg 三重载)/`emptyBackdrop`(**已用**)、`rememberCanvasBackdrop`、**`rememberBackdrop`**(把一个 backdrop 包一层 `onDraw` 再当 backdrop 用;⚠️ 第一轮盘点漏了这个,2026-09-23 补) —— 后两者未用。
  - **②`effects { }` 链(8 入口 / 13 取值)**:`blur`(radius、edgeTreatment)、`lens`(refractionHeight、refractionAmount、depthEffect、chromaticAberration)、`colorControls`(brightness、contrast、saturation)、`opacity`(alpha)(**已用**);`colorFilter`、`effect(RenderEffect)`、`runtimeShaderEffect(自定义 AGSL)`(未用)。`vibrancy()` 是 `colorControls(saturation=1.5f)` 的预设,不重复计。
  - **③装饰层(3 类型 + 3 样式 / 21 取值)**:`Highlight`(width、blurRadius、alpha、style)、`HighlightStyle.Default`(color、blendMode、angle、falloff)、`HighlightStyle.Ambient`(intensity)、`HighlightStyle.Plain`(color、blendMode)、`Shadow`(radius、offset、color、alpha、blendMode)、`InnerShadow`(同上 5 项)。**项目只用了 8 项**(Highlight.alpha/style、Shadow.radius/color/alpha、InnerShadow.radius/offset/alpha)——**这是最大的未开发区域**。
  - **④绘制相位与导出(6 取值)**:`onDrawSurface`、`layerBlock`(**已用**);`onDrawBehind`、`onDrawBackdrop`、`onDrawFront`、`exportedBackdrop`(未用)。⚠️ `ui/components/Skeleton.kt` 的 `onDrawBehind` 是 `drawWithCache` 的,不是库的参数,别误算成"已用"。
  - **⑤形状与采样范围(2 取值)**:`shape`、`layerBackdrop(recordBounds)`(均**已用**)。
  - **结构性选择(不计入 42)**:采样源 5 选 1;Modifier 入口 `drawBackdrop` / `drawPlainBackdrop` 2 选 1。
  - **项目设置页暴露 7 个**(2 开关 + 5 参数),代码里实际用到 **21 / 42**。
  - 另有平台查询 `isRenderEffectSupported`/`isRuntimeShaderSupported` 与工具 `lerp(InnerShadow)`。
- **iOS 27 的事实核查(WebSearch,9/15 正式版)**:iOS 27 **没有换材质模型**,是"纠错式迭代" —— 上一代透明度过高、文字可读性差被批,故新增 **Liquid Glass 全局透明度无级调节滑块**(覆盖控制中心/文件夹/状态栏,深色模式自动降透明度)。⇒ "iOS 27 样式" ≈ iOS 26 光学栈 + 一个全局透明度滑杆 + 更保守的可读性标定。本库对应能力 = `opacity()` / `colorControls()`,但**产品形态**(那个滑杆)项目侧此前没接线。
- **改动一 · 厚度感(常开)**:两处主体玻璃的 `lens()` 加 `depthEffect = true` —— `GlassTopBar.glassSurface` 与 `FloatingNavBar` 底栏主胶囊。shader 里把 SDF 梯度与"指向中心"的单位向量按 `depthEffect` 混合,边缘呈凸起透镜感;只多几条 ALU,零风险。**未加**的两处:底栏选中态指示层(`tabsBackdrop` 源层)与第三层指示器 —— 它们是薄片 Clear 语义,且指示层本来就自带色散。
- **改动二 · 边缘色散(新增开关,默认关)**:新增 KV `LIQUID_GLASS_DISPERSION` + 设置开关「边缘色散」,映射到两处主体的 `lens(chromaticAberration = config.dispersion)`。**为什么默认关**:色散走 `RoundedRectRefractionWithDispersionShaderString`,采 7 段光谱 = **7 倍纹理采样**,而主体玻璃是**常驻**渲染(不像指示层只在按压时出现),默认开启会直接吃掉先前实测已经很紧的 120Hz 余量。⚠️ 它与普通折射是**两个不同的 shader key**,开启后首次绘制要现场编译(先前排查"冷启动首次点 tab 掉帧"时,色散 shader 的首次编译时机就是候选之一)。
- **改动三 · 通透度(新增滑杆,默认 0.5)**:对齐 iOS 27 的全局透明度滑杆。`LiquidGlassConfig` 新增派生量 `containerAlphaScale = 1.25f - translucency * 0.5f`,乘在玻璃底色 alpha 上(`FloatingNavBar` 的 `surfaceContainer@0.4`、`GlassTopBar` 的 `surfaceBright@0.45`);**0.5 ⇒ 系数 1.0,与加此项前逐像素一致**,这是"默认零视觉变化"的锚点。同时新增 `contentBrightness`/`contentContrast` 派生量,用 `colorControls(brightness, contrast, saturation = 1.5f)` 做可读性补偿(越透越压暗采样内容 + 提对比)。
  - ⚠️ **`colorControls(saturation = 1.5f)` 与 `vibrancy()` 数学等价** —— `vibrancy()` 内部就是 `colorFilter(VibrantColorFilter)`、而 `VibrantColorFilter = colorControlsColorFilter(saturation = 1.5f)`,同一个函数同一组参数 ⇒ 同一个 ColorMatrix。所以这是"**换实现不换观感**",且**不新增离屏层**(仍是一层 `ColorFilterEffect`),顺带让 `padding` 行为不变(`blur()` 的 `padding = radius` 依赖 `renderEffect != null`,两条路径都会置非 null)。唯一差别是 `ColorMatrixColorFilter` 实例不再被缓存复用,而它只在 effects 重算时构造,不在每帧路径上。
  - 底栏**选中态指示层**也一并换掉了 `vibrancy()`,因为它与主体共用同一个 `containerColor` 变量 —— 只改主体会让两者在非默认通透度下脱节。
- **顺手修的一处既有隐患(低,非本次引入)**:`KVKeySpec` 里 `LIQUID_GLASS_BLUR` / `LIQUID_GLASS_DISTORTION` 登记在 **int 区**(`register(..., 0)`),但它们按 `Float` 读。读 `KVDecoder.decode` 确认:第 99-104 行 `wanted` 取自**调用侧默认值**,`resolveType` 在 `wanted != null` 时直接 `TypeToken.get(wanted)`、**根本不查登记表** ⇒ 该登记项对这些键是**死数据**,功能上无害但会误导(若有人日后用不带默认值的 `KV.get(key)` 读,登记表就会把它当 Integer 返回)。已把三个 Float 键一并挪到 float 区(`register(..., 0f)`)。
- **新增单测 `app/src/test/java/.../ui/theme/LiquidGlassConfigTest.kt`(3 例)**:锁 `LiquidGlassConfig` 的三个派生量 —— ①**默认通透度必须是恒等点**(`containerAlphaScale == 1f` / `contentBrightness == 0f` / `contentContrast == 1f`),这是"默认零视觉变化"唯一可执行的保证(改系数时若打破它,必须显式改这个测试而不是悄悄漂移);②`containerAlphaScale` 随通透度单调、两端 1.25 / 0.75;③越透越压暗采样内容 + 提对比。测试直接构造 `LiquidGlassConfig` 而不碰 `LiquidGlassState`(后者 `load()` 会读 KV,单测环境无 `KV.init` 会抛 `IllegalStateException`)—— 引用 `LiquidGlassState.DEFAULT_TRANSLUCENCY` 是安全的,`const val` 会被内联、不触发 object 初始化(已实测通过)。
- **验证**:`:app:compileDebugKotlin` / `:app:assembleDebug` + `:app:testDebugUnitTest` 全 **BUILD SUCCESSFUL**,**250 用例 / 0 失败 / 0 错误**(基线 247 + 新增 3,无回归);APK 产出 `app/build/outputs/apk/debug/AVBox_debug.apk`(84.4 MB);已校验 `packaged_res` 内 `theme_translucency` / `theme_dispersion` 两条新文案进包(仅 3 个语言文件有 `theme_blur`,已全部同步;`values-zh-rHK` 是差异层,港台用词相同故不加)。
- **未验证 / 待真机**:①厚度感的观感(边缘凸起是否自然、是否与高光边打架);②色散开启后的观感与**帧率代价**(7 倍采样,须实测 120Hz 下是否掉帧);③通透度全量程的观感与"越透越压暗采样内容"的补偿量是否合适(当前系数是估的,`0.06` / `0.24` 两个幅度没有实测依据);④默认值下是否真与改动前逐像素一致(数学上等价,但未经截图比对)。**按约定不自行操控用户设备**。
- **行为变化需知**:头部「重置」由"只重置模糊/扭曲"变为**重置后四项效果参数**(模糊/扭曲/通透度/色散),仍**不动**「底部导航」「应用控件」两个启用开关。

## 修:液态玻璃"没有立体感"(2026-09-23,用户"感觉没有 ios27 那种 3d 的立体感")

- **背景**:上一轮加了 `depthEffect = true` 后用户实机反馈仍无立体感。回读库源码定位到**两条独立成因**,都不是 `depthEffect` 能解决的 —— 它只改折射**方向**,不改**明暗**;而人眼读"厚度"主要靠明暗。
- **成因一(主因)· 内阴影实际不可见**:`InnerShadow` 的 `color` 默认 `Black@0.15`、`alpha` 是**乘在 color 之上的图层不透明度**(`shadowLayer.alpha = shadow.alpha`),项目传的 `alpha = 0.1f` ⇒ 实际不透明度只有 `0.15 × 0.1 = 1.5%`,等于没画。**这是个容易看漏的乘算关系,不是简单的"调小了"。**
- **成因二 · 内阴影方向反了(读成"凹进去")**:`InnerShadowNode` 的画法是「用 color 填满形状 → `canvas.translate(offset)` 后 `BlendMode.Clear` 掉平移过的同一形状」,剩下的月牙落在**平移的反方向**。默认 `offset = DpOffset(0, +radius)`(下移)⇒ 月牙留在**上缘**。而外层 `Shadow` 是往下投的(元素悬浮)⇒ 上缘暗 + 下方投影 = **内凹**观感。悬浮的玻璃应是「上缘受光、下缘厚而暗」,故 `offset` 改 `-radius` 把厚度带翻到下缘。
- **成因三 · 折射带过宽(没有"透镜环")**:`lens(refractionHeight, refractionAmount)` 原来两个参数传同一个值 ⇒ 在 64dp 高的底栏上衰减深度 = 30dp = **整条栏的 47%**,变形铺满整条栏、只剩"整体糊"。新增常量 `REFRACTION_DEPTH_RATIO = 0.4f`,只缩**衰减深度**、保留**位移量**(位移仍由「扭曲效果」滑杆控制)⇒ 同样的位移被压进边缘窄带,透镜环清晰。
- **改动(3 个文件)**:
  - `ui/theme/LiquidGlassConfig.kt` 新增两个可调常量:`REFRACTION_DEPTH_RATIO = 0.4f`、`GLASS_THICKNESS_DP = 5f`(注释各 1 行)。
  - `GlassTopBar.kt` / `FloatingNavBar.kt` 主体:`lens(refraction * REFRACTION_DEPTH_RATIO, refraction, ...)`;`innerShadow` 由 `InnerShadow(radius = 4.dp, alpha = 0.1f)` 改为 `InnerShadow(radius = GLASS_THICKNESS_DP.dp, offset = DpOffset(0.dp, -GLASS_THICKNESS_DP.dp), alpha = 0.8f)`(实际不透明度 0.12)。
  - 顺带对齐同一组件内另外两处:`FloatingNavBar` 的 `tabsBackdrop` 源层(它的 lens 也加 ratio)与第三层指示器(按压态内阴影 `offset` 同样翻到下缘)—— 否则按压时指示器的暗带在上、主体在下,同屏自相矛盾。
- **未动**:外层 `Shadow`(2026-09-16 曾因 24dp 投影过大被用户投诉"控件区域一层半透明灰",已收紧到 8dp,不再放大);`Highlight`(库的 `DefaultHighlightShaderString` 用 `pow(abs(d), falloff)`,`abs()` 使上下缘强度恒等 ⇒ **无论 `angle` 取何值都做不出"只亮上缘"**,这是库的限制,见下条"后续可做")。
- **后续可做(本轮未做,已想清)**:①**方向性高光** —— 需改本地 fork,给 `HighlightStyle.Ambient` 加 `angle` 参数(`AmbientHighlightShaderString` 已有 `step(0.0, d)` 的受光/背光二分,但 `angle` 在 Kotlin 侧被硬编码成 45°,且它给出的"亮侧"是右下、不是上方);②**中心厚/边缘薄的非线性厚度映射** —— 现在 `containerColor` 是纯色平铺,可换成 `Brush.verticalGradient`(需 `remember` 避免每帧建 Brush);③`refractionDepthRatio` / `glassThickness` 若用户想自己调,可提成滑杆。
- **验证**:`:app:compileDebugKotlin` / `:app:assembleDebug` + `:app:testDebugUnitTest` 全 **BUILD SUCCESSFUL**,**250 用例 / 0 失败 / 0 错误**(无回归);APK 重建 84.4 MB。**观感待用户实机判断**(我不自行操控设备);`0.4` / `5dp` / `alpha 0.8` 三个值是估的,无实测依据。

## 修:内阴影过重导致下缘"偏色暗带"(2026-09-23,用户"下半部分有很明显的偏色阴影,感觉不协调" + 附作者 B 站演示截图)

- **反馈**:上一轮加了立体感后,用户实机截图显示顶栏控件下缘出现一条**明显偏色的暗带**;同时给出库作者 Kyant 的 B 站演示(`安卓液态大玻璃 / iOS 27 样式玻璃新参数`,2026-06-12),说"像这种玻璃就很舒服"。
- **根因(上一轮自己引入)**:我把 `InnerShadow.alpha` 从 0.1 提到 0.8 ⇒ 实际黑度 `0.15 × 0.8 = 12%`。黑色压在**半透明玻璃底**(`surfaceBright@0.45`,Material 默认配色下带紫调)上时,不是"变暗"而是**把玻璃自身的色相压了出来** ⇒ 读成"偏色暗带"。**判据不是"暗度够不够",而是"会不会把玻璃底色调出来"** —— 这个量必须克制。
- **修法**:内阴影降到 `GLASS_THICKNESS_DP = 4f` + 新增常量 `GLASS_THICKNESS_ALPHA = 0.3f`(实际约 4.5% 黑,较上一轮 -62%)。折射带收窄(`REFRACTION_DEPTH_RATIO = 0.4f`)与 `depthEffect` 保留 —— 立体感的另一部分来自它们,用户也认可"已经有立体感了"。
- **作者 demo 的参数档(重要参考,已同步 spec §5)**:演示里六个滑杆 = 模糊不透明度 66% / 调色强度 100% / 阴影强度 74% / **环境光强度 29%** / **折射强度 50%** / **色散强度 0%**。
  - **环境光强度 = `HighlightStyle.Ambient.intensity`** —— 项目当前用 `Highlight.Default`,**没启用 Ambient**;作者 demo 用的是它。⚠️ 但 `Ambient` 在 2.0.1 里 `angle` 被**硬编码 45°**,其 shader 的 `step(0.0, d)` 给出的是"**右下亮 / 左上暗**"(不是 iOS 的"上亮下暗"),且 `Ambient` 的 `intensity` 只改 `color`、而 shader 会覆盖 color ⇒ **开 shader 时 intensity 可能不生效**。要真正对齐 demo 需改本地 fork 给 `Ambient` 加 `angle` 参数 —— **本轮未做**。
  - **色散强度 0%** —— 作者自己演示里色散也是关的,与项目"默认关"的选择一致(印证了性能判断)。
  - **折射强度 50%** —— 作者只开到一半;而项目 `DEFAULT_DISTORTION_DP = 30f` 恰是滑杆量程上限(0~30)⇒ 默认即 100%。**已提示用户可把「扭曲效果」拉一半试试**(改默认值对已装机用户无效,他们的 KV 里存着旧值)。
- **验证**:`:app:assembleDebug` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL**,**250 用例 / 0 失败 / 0 错误**;APK 重建 84.4 MB。**观感待用户实机判断**;`0.3` 是估的,若"立体感变弱"优先回调它。

## 补:接入「模糊不透明度」= 库的 `opacity()`(2026-09-23,用户"那个模糊不透明度呢")

- **起因**:上一轮用户给了作者 demo 截图,六个滑杆里的「模糊不透明度 66%」当时没展开。用户追问后确认它对应的是**第一轮能力盘点里标为"未使用"的 `opacity(alpha)`**。
- **⚠️ 关键区分(容易混,已写进 spec §5)**:
  - **通透度**(上一轮做的)= 调**玻璃底板** `containerColor` 的不透明度 ⇒ 底板越淡,透出来的**模糊内容**越多。
  - **模糊不透明度**(本次做的)= `opacity(alpha)` 调**采样到的背景内容本身**的不透明度 ⇒ 它越低,玻璃越薄,**未模糊的原背景**按 `1 - alpha` 透出来。
  - 两者都让玻璃"更透",但**透出来的是不同的东西**。
- **实现**:KV `LIQUID_GLASS_BLUR_OPACITY`(float,默认 1)+ `LiquidGlassConfig.blurOpacity` + 设置页滑杆「模糊不透明度」(0~100%,显示百分比)。调用点三处(`GlassTopBar.glassSurface`、`FloatingNavBar` 主体与 `tabsBackdrop` 源层),都写成 **`if (config.blurOpacity < 1f) opacity(config.blurOpacity)`** —— ⚠️ **`opacity()` 没有 alpha==1 的短路**,无条件调用会在默认路径上白加一层离屏 `ColorFilterEffect`,所以必须由调用点守。`1f` 是恒等点,已加单测 `defaultBlurOpacity_isIdentity` 锁住。
- **效果链位置**:放在 `effects` 块**最前面**(`opacity` → `colorControls` → `blur` → `lens`)。理由是 `blur()` 的 `padding = radius` 依赖 `renderEffect != null`,放最前可确保这个前提不依赖 `colorControls` 的行为;视觉上与放最后等价(alpha 是均匀标量,与线性的 blur 可交换)。
- **⚠️ 已知副作用(需实测确认)**:调低会产生**"双重影像"** —— 玻璃节点上原本盖着 100% 的模糊内容(把下面的原图完全挡住),降到 66% 后剩下 34% 是**未模糊的原图**,与模糊层叠加。观感可能是"更薄更透的真玻璃"(作者 demo 大概就是这效果),也可能像渲染重影。**这正是它和「通透度」最需要用实机区分的点。**
- **验证**:`:app:assembleDebug` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL**,**251 用例 / 0 失败 / 0 错误**(基线 250 + 新增 1);APK 重建 84.4 MB。默认 100% 时**逐像素等于加此项之前**。

## 玻璃三改:撤除模糊不透明度 / 色散默认开 / 方向性高光做立体感(2026-09-23,用户"将模糊不透明度范围滑块删掉吧,默认开启边缘色散,再增强一下液态玻璃控件和导航栏的立体感")

### ① 整项撤除「模糊不透明度」

- **上一轮刚接的 `opacity()` 功能按用户要求整项删掉**,不是只藏滑杆:滑杆 + `LiquidGlassConfig.blurOpacity` 字段 + KV `LIQUID_GLASS_BLUR_OPACITY`(含 `KVKeySpec` 登记)+ `HawkConfig` 常量 + 三处 `if (blurOpacity < 1f) opacity(...)` 调用点 + `effects.opacity` import + 单测 `defaultBlurOpacity_isIdentity` + 三语言 `theme_blur_opacity` 文案,**全部清除**。
- 推断原因 = 上一轮预告的**"双重影像"**(调低后未模糊的原图透出来)。**不要再按作者 demo 的「模糊不透明度 66%」去实现**(spec §5 已注明)。

### ② 边缘色散改为默认开

- `LiquidGlassState.DEFAULT_DISPERSION` 由 `false` 改 `true`(KV `LIQUID_GLASS_DISPERSION` 仍在,开关保留)。用户已装的机器只要**没手动关过**该开关,键不存在 ⇒ `KV.get(key, true)` 直接返回新默认值,无需重装或迁移。
- ⚠️ **保留的性能警告**:色散是 7 倍纹理采样且主体常驻渲染,是全项目最贵的一处效果。先前实测底栏在 120Hz 下余量本就紧(稳态 8.3ms/帧),**若报掉帧优先关它做 A/B**。

### ③ 立体感:改用方向性高光(本轮唯一动 fork 的改动)

- **思路**:上一轮靠"加深下缘内阴影"做立体感翻车了(偏色暗带)。这轮换成**加光而不是加暗** —— 用 `HighlightStyle.Ambient` 做**亮上缘 + 暗下缘**的方向性高光。关键优势:下缘暗部是 **0.5dp 细线**(Highlight 的 stroke 宽度),不是上一轮那种 4dp 模糊宽暗带 ⇒ 结构上不会重犯"偏色暗带"。
- **fork 改动(第 4 处偏离)**:`HighlightStyle.Ambient` 新增 `angle: Float = 45f`,把原本硬编码在 `createShader` 里的 `45f` 提成参数。**默认 45f 与上游逐字节一致**(不传即行为不变)。上游 `Ambient` 的 shader 本来就有 `step(0.0, d)` 的受光/背光二分,但 45° 给出的是"右下受光",要"上亮下暗"必须传 `-90`。
- **为什么不能靠 `Highlight.Default`**:`DefaultHighlightShaderString` 用 `pow(abs(d), falloff)`,**`abs()` 让上下缘强度恒等** ⇒ 调 `angle`/`falloff` 都出不来方向性;而且 45° 时四条直边恰好同为 `|d| = 0.707`,就是那条"均匀白描边"。
- **顺带确认的一件事**:`internal/Paint.setRuntimeShader` 设的是 `frameworkPaint.shader`,**Skia 下 paint 的 alpha 会调制 shader 输出** ⇒ `Ambient.intensity` 是生效的(此前不确定,已验证)。
- **实现**:新增常量 `GLASS_AMBIENT_INTENSITY = 0.55f` / `GLASS_LIGHT_ANGLE = -90f`;`GlassTopBar.kt` 里定义 `internal val GlassHighlight`(`Highlight.Ambient.copy(style = HighlightStyle.Ambient(intensity, angle))`),**底栏四层玻璃全部改用它**(主体 / `tabsBackdrop` 源层 / 选中指示层,原来都是 `Highlight.Default`),顶栏控件同样。抽成共享 val 而不是各写一遍,避免将来调参漏改。
- **验证**:`:app:assembleDebug` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL**,**250 用例 / 0 失败 / 0 错误**(撤掉 blurOpacity 那条后回到基线);APK 重建 84.4 MB。**观感待用户实机判断**;`0.55` / `-90` 两个值是估的,优先调它们。

## 修:冷启动崩溃 `ConcurrentModificationException`(2026-09-23,用户"刚刚发生崩溃了";**既有 bug,非本轮玻璃改动引入**)

- **现象**:装机后 3 秒(11:13:17)冷启动崩在 main。`logcat -b crash` 原文:
  ```
  java.util.ConcurrentModificationException
      at java.util.ArrayList$Itr.checkForComodification(ArrayList.java:1111)
      at com.github.tvbox.osc.ui.page.SettingsPageKt.SettingsPage$lambda$6$0$1$3(SettingsPage.kt:720)
  ```
- **⚠️ 踩到的第一个坑:栈里的行号是假的**。`SettingsPage.kt` 只有 **502 行**,根本没有 720 行,一度怀疑装的包和工作区源码不一致。核对栈里另外两个行号(`SettingsPage.kt:296` → `SettingsGroup(...)`、`:340` → `SettingsCard(...)`)与当前文件**逐行吻合** ⇒ 包里就是这份源码。**真相 = Kotlin 内联 `mapIndexed` 的 lambda 行号被误归因**(真实位置 = `SettingsPage.kt:348`)。**教训:Compose/内联 lambda 的栈行号超出文件总行数时,不要据此怀疑构建产物,改按"哪一行在遍历集合"去反查。**
- **根因链(跨线程数据竞争)**:
  ```
  AppBootstrap:36  scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)   ← 后台
    └─ startInit → awaitLoadConfig → ApiConfig.loadConfig → parseJson:905
         └─ OkGoHelper.setDnsList() → dnsHttpsList.clear() + add()           ← 原地改写
  主线程: SettingsPage:348 → OkGoHelper.dnsHttpsList.mapIndexed { }          ← 同时遍历
  ```
  `OkGoHelper.dnsHttpsList` 是 `public static ArrayList<String>`,**写方是启动期 IO 协程、读方是主线程 Compose 重组**。冷启动会组合全部 4 个 tab(`MainScreen` 的 `beyondViewportPageCount = 3`),所以设置页在**启动时**就被组合 —— 不是"进设置页才崩"。
- **归属判定**:`OkGoHelper.java` 与 `SettingsPage.kt` 在 git 里**均未修改**(`git status` 无输出),`示例文件/上游项目` 里是同一份写法 ⇒ **继承自上游的既有 bug**,与本轮玻璃改动无关(本轮只改了渲染,不碰这条链路)。触发是概率性的,安装后的冷启动刚好撞上窗口。
- **修法(只改 `OkGoHelper.java` 一个文件)**:`dnsHttpsList` 改 `public static volatile List<String> = Collections.emptyList()`,`setDnsList()` 与 `initDnsOverHttps()` 都改成**先在局部 `ArrayList` 建好、最后一次性赋值**(atomic swap)。读者永远看不到半成品列表 ⇒ **无需加锁**;顺带修掉一个隐患:原写法 `clear()` 在 try 内,异常中断会留下"只剩 `关闭` 一项"的半清理状态,新写法异常时保留旧列表。
- **同类排查(已扫)**:`OkGoHelper` / `ApiConfig` 里的静态可变集合只有 `dnsHttpsList`(已修)、`myHosts`(已是 `volatile`)、`setProxyList`(已是 `synchronized`);`SettingsPage` 读 OkGoHelper 的地方只有那两处 DOH 行 ⇒ **无同类遗留**。口径已写进 spec §6.2。
- **验证**:`:app:assembleDebug` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL**,**250 用例 / 0 失败 / 0 错误**;APK 重建 84.4 MB。`logcat -b crash` 复查无新增崩溃(仍只有 11:13:17 那一条)。**真机待验**:连续冷启动多次不再崩。

## 修:BootGuard 误禁正常源(2026-09-23,用户"崩溃明显不是源的问题,应用还把正常能用的源给我禁用了,明显是误禁";**既有 bug**)

- **现象**:上一次崩溃(设置页的 CME)被 `BootGuard` 判成"与源有关",崩在装载后 10s 内 ⇒ 一次即停用 ⇒ 用户**正常的源被误禁**(`API_URL` 被清空 + 源地址进黑名单)。
- **根因(判据恒真,保护从未生效)**:`BootGuard.IGNORABLE_FRAME_PREFIXES` 里有 `android.` / `androidx.` / `java.` / `kotlin.` / `com.google.android.` / `com.github.tvbox.osc.ui.` / `.base.`,**唯独漏了 `com.android.internal.`**。而任何**主线程**未捕获异常的栈尾必然是:
  ```
  at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:675)
  at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:1002)
  ```
  这两个类不以任何已列前缀开头 ⇒ `isIgnorableFrame` 返回 false ⇒ `looksSourceRelated` 对**每一次主线程崩溃**都返回 `true`。**「界面崩溃不参与停用判定」这条保护等于从未生效过。**
- **⚠️ 单测为什么没拦住(最有价值的一条教训)**:`BootGuardTest.uiCrashIsNotSourceRelated` 早就存在,但它用的是一条**理想化的假栈** —— 结尾写的是 `java.lang.Thread.run`(在 `java.` 白名单里),而真实主线程崩溃栈的结尾是 `com.android.internal.os.*`。**假栈把 bug 的触发条件绕过去了 ⇒ 单测长期绿着、线上判据恒真。** 写"崩溃栈过滤"这类用例时,**假栈必须按真实崩溃的形态写全**(本次直接抄了真机 `logcat -b crash` 的帧序列)。
- **修法**:白名单加 `com.android.internal.`(框架内部包,任何应用/爬虫代码都不会在这里;只影响栈尾,不影响真正的 culprit 帧)。同时把 `uiCrashIsNotSourceRelated` 的假栈换成真机帧序列,并新增两条:
  - `frameworkCrashTailIsNotSourceRelated` —— 最小复现(只留 `com.android.internal.os.*` 尾巴)
  - `uiStackWithSpiderFrameIsStillSourceRelated` —— **反向锁**:白名单放宽后,界面帧里混进一帧爬虫仍必须判"有关"(防止放宽过头把真坏源放过)
- **反向验证(做了)**:临时删掉 `com.android.internal.` 重跑 ⇒ 上述 2 条**立刻转红**;恢复后 252 用例全绿。**这条"去掉修复是否转红"的自证步骤值得固定下来** —— 本次正是它证明了新用例真的能抓 bug,而不是又一条"绿着却无效"的测试。
- **用户侧恢复(源没丢)**:`disableRecordedSource()` 只清 `API_URL`(当前源指针)+ `API_LINE_LIST`/`API_LINE_SOURCE`(仓列表)+ 把地址记进 `BOOT_DISABLED_SOURCES`;**订阅列表 `API_HISTORY` 不动**。恢复路径 = 配置管理页 → 被禁源会带 `disabled` 标记 → 点它弹二次确认(`dialog_source_disabled_confirm` =「仍要启用」)→ `ConfigManagePage.enableAndSwitch()` 调 `BootGuard.enableSource(url)` 移出黑名单并切换。黑名单**不**过滤源选择列表(只在 `ApiConfig.firstUsableApiLine` 换仓改写、与 `LivePlayViewModel` 切直播源两处生效),所以源一定还能选中。
- **验证**:`:app:assembleDebug` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL**,**252 用例 / 0 失败 / 0 错误**(基线 250 + 新增 2)。**真机待验**:恢复被误禁的源后连续冷启动不再被禁。

## 修:方向性高光的下缘暗线太生硬(2026-09-23,用户"液态玻璃下缘那条暗线太丑了很生硬")

- **成因(上一轮自己引入)**:`HighlightStyle.Ambient` 的 shader 用 `step(0.0, d)` 把轮廓分成受光/背光两侧,而**背光侧被输出成 `alpha = intensity` 的纯黑**(`half4(t, t, t, 1.0) * intensity`,t=0 时 rgb 全 0、alpha 仍是 intensity)。配合 `angle = -90°`(正上方受光),背光侧正好落在**下缘**;而 Highlight 只有 **0.5dp 宽 + 0.25dp 模糊** ⇒ 就是一根又细又硬的暗线。**调 `intensity` / `angle` 都消不掉它** —— 那是 shader 输出形态决定的,只能改 shader。
- **修法(fork 第 5 处偏离)**:`AmbientHighlightShaderString` 的 `half4(t, t, t, 1.0)` → **`half4(t, t, t, t)`**,即 **alpha 也跟着 `t` 走**。效果:①背光侧 `(0,0,0,0)` **全透明**,暗线消失;②过渡区从"rgb 变灰、alpha 不变"变成"**白色降透明度**",比上游更干净(上游过渡区其实是一段灰带)。
- **为什么选"去掉暗侧"而不是"把暗侧调淡"**:立体感的主要来源是**亮上缘**(`intensity = |dot(法线, 光源)|`,从正上方沿两侧平滑衰减),暗侧只是附加的纵深暗示;而用户对"下缘发暗"已连续两次负面反馈(第一次是内阴影 12% 黑的"偏色暗带",这次是这根硬线)。**底部纵深改由那条柔和的内阴影承担**(`GLASS_THICKNESS_DP = 4f` / `ALPHA = 0.3f`,4dp 模糊),它是渐变而非硬线。
- **验证**:`:app:assembleDebug` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL**,**252 用例 / 0 失败 / 0 错误**。⚠️ **shader 改动无单测覆盖**(AGSL 字符串无法在纯 JVM 里跑),只能真机看观感。
- **未做(若用户还嫌不够亮)**:背光侧去掉后整体高光变淡,`GLASS_AMBIENT_INTENSITY` 可能要从 `0.55f` 往上调 —— 单常量,好调。

## 改:直播 FAB 迁入导航栏中央(2026-09-23,用户"把首页的直播 fab 融合进底部导航栏,放在历史和收藏中间,类似图二那样")

- **用户参照物**:今日头条 App 的底栏 —— 首页 / 数码 / **[+ 实心圆]** / 发现 / 我的,中间那颗是"动作"而不是第 5 个目的地。用户先问"改动大吗",随后切到 Agent 模式要求直接做。
- **动手前的勘查结论(为什么"看着小、实际要动四套渲染")**:直播入口原先只有首页一处(`HomePage` 的 `FloatingActionButton`,`align(BottomEnd)`,点击 `LivePlayActivity`),而**导航栏有四套渲染分支**:玻璃横条 / 玻璃竖条(`FloatingNavBar` 同一组件按 `NavAxis` 分支)/ 关玻璃的 M3 `NavigationBar` / 关玻璃的 M3 `NavigationRail`。删掉 FAB 后,任何一套没接上 = 那类用户**彻底没有直播入口**。故动作钮抽成 `ui/navbar/NavActionButton.kt`,四套共用同一份 `GlassTabItem`。
- **核心设计(槽位数 ≠ 页面数)**:动作槽占一格但不占一个页面。`FloatingNavBar` 原本所有几何都建立在 `tabsCount` 上(步长 `(totalStride-8dp)/tabsCount`、胶囊宽 `contentStride/tabsCount`、胶囊位移 `value * singleTabStride`),直接改成 5 会把页面当成 5 个。做法 = 新增 `slotCount = tabsCount + 1` 与 `actionSlot = tabsCount/2`,**步长/胶囊宽/胶囊位移一律走槽位空间**,页面下标只用于 `pager` 与选中态;`dampedDragAnimation` 的 `valueRange` 仍是页面空间 `0..tabsCount-1`(拖动一格 = 切一页)。
- **⚠️ 最容易做错的一处:胶囊映射必须连续插值,不能取整**。直觉写法是 `slotIndex = if (page < actionSlot) page else page + 1`,但那是**阶跃函数**:页面 1→2 时胶囊从槽位 1 直接跳到 3,拖动经过中间时肉眼可见"闪一格"。改用 `pageValue + (pageValue - (actionSlot - 1)).coerceIn(0f, 1f)` —— 在页面 1→2 区间线性加 0→1,胶囊**平滑滑过**动作槽(1 → 2 → 3)。反查函数 `tabIndexOfSlot` 只需整数点正确。
- **判据集中到 `NavMetrics`(而不是留在 `FloatingNavBar` 私有)**:该文件本就是"导航壳几何常量与纯函数"的家,且已有 `NavMetricsTest`。新增 `actionSlotFor(tabCount)` / `slotPosition(pageValue, actionSlot)` / `tabIndexOfSlot(slot, actionSlot)` + 常量 `ACTION_BUTTON_DP = 44`,**顺手补 7 条单测**(单调不减 / 四个页面恰好落在槽位 0·1·3·4 / 整数点互逆 / 无动作槽时是恒等 / 按钮放得进 64-8=56dp 内沿)。这类"算错不崩、只在真机上显形"的几何,正是本文件立单测的原始理由。
- **玻璃条的染色层要占位**:`FloatingNavBar` 是"容器层 + 染色层(`alpha=0` + `primary` tint,靠胶囊透出)+ 胶囊层"三层叠画。动作钮只在容器层渲染,染色层在动作槽位置放 `Spacer` —— 否则胶囊滑过中间时,会把动作钮染成一块纯色圆。
- **M3 `NavigationBar` 回退怎么插**:先怀疑"插进去会不会破坏等宽分发",去 gradle 缓存里翻到 material3 1.5.0-alpha28 的 sources jar 确认 **`NavigationBarItem` 内部就是 `Modifier.weight(1f)`**(`NavigationBar.kt:230`),于是在 Row 里插一个同权重 `Box` 即可天然等宽居中;若用固定宽度的占位会整条左移。`NavigationRail` 更简单,直接在第 3 项前插一个 `NavActionButton`(Column 自带 `spacedBy`)。
- **动作钮形态**:实心 `primary` 圆 + `onPrimary` 图标(沿用 `ic_live_fab.xml`)、**无文字标签**、直径 44dp。刻意与两侧"图标+文字"不同形 —— 它是动作不是目的地,不加标签才不会被读成第 5 个 tab。保留 ripple(项目全局 ripple 是 M3 默认 2 倍);玻璃条两侧 tab 走 `indication = null` 是另一套逻辑(靠胶囊按压高光),不照抄。
- **顺带清掉的**:`HomePage` 的 FAB 及其 `FloatingActionButton` / `LivePlayActivity` 两个失效 import;规范里两处"覆盖层(FAB)"的举例改为"(浮层)"(已无 FAB)。`navStart`/`navBottom` 仍被内容内边距使用,未动。
- **验证**:`:app:assembleDebug` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL**,**259 用例 / 0 失败 / 0 错误**(基线 252 + 新增 7);APK 重建 `app/build/outputs/apk/debug/AVBox_debug.apk`。⚠️ **纯 Compose 布局改动无渲染层测试**,只能真机看;`git bash 的 ./gradlew` 会报 `ClassNotFoundException: GradleWrapperMain`,用 `.\gradlew.bat`(PowerShell)正常。
- **待用户判断**:①44dp / 实心 primary 圆的视觉重量是否合适(单常量,好调);②是否要给动作钮加文字标签;③拖动经过中间时胶囊滑过动作槽的观感是否可接受(若要避免,只能把动作槽移出胶囊可及的路径)。

### 二轮:动作钮改胶囊 + 浅色容器(同日,用户"不要弄成纯圆形,形状改成和图二那个绿色的一样,颜色浅一点,改成和设置页的 icon 图标的容器的颜色一样")

- **先量化参照物再动手(这次最有价值的一步)**:把用户给的今日头条截图裁剪放大,用 PIL 逐像素量出那颗绿钮的形态 ——
  - **不是正圆,是胶囊**:绿色区域 171 × 122px,逐行量水平跨度(顶部 80 → 中部 171 → 底部 71)与"半径 = 高/2 的胶囊"理论曲线吻合(误差 ≤5px),而小圆角矩形会在顶行留下很宽的平边。
  - **宽高比 1.40**、**占槽宽 0.80**;5 个 item 的中心间距 209/219/218/209px ⇒ **动作钮确实占一整格**,与已实现的五等分槽一致(这条验证了上一轮的核心设计)。
  - 由标签字高(26px)反推屏幕密度 ≈2.75x,得图标 ≈25dp —— 与本项目 24dp 的 tab 图标几乎一致,说明两边导航条的内容尺度可直接类比。
  - ⚠️ **凭肉眼把那张图读成"绿圆"会直接做错形状**;分辨率低的截图里胶囊与正圆很难区分,量一下成本极低。
- **改动**:`NavActionButton` 由 `size(44.dp)` + `CircleShape` + `primary`/`onPrimary` 改为 **`size(52.dp × 37.dp)` + `ContinuousCapsule` + `primaryContainer`/`onPrimaryContainer`**(`NavMetrics.ACTION_BUTTON_DP` 拆成 `ACTION_BUTTON_WIDTH_DP` / `ACTION_BUTTON_HEIGHT_DP`)。形状用 `ContinuousCapsule` 而不是 `RoundedCornerShape(h/2)`:项目里导航条/源胶囊/搜索框都走这个连续胶囊,视觉语言一致。
- **配色取"设置页的 icon 容器"= `SettingsIconBadge`**(`ui/components/SettingsGroup.kt:139`):40dp `CircleShape` + `primaryContainer` 底 + `onPrimaryContainer` 图标(设置页分组卡头、配置管理页、搜索页都在用它)。用户要的是它的**颜色**,不是它的形状 —— 形状按参照物走。
- **尺寸约束写进单测**:高 ≤ `BAR_CROSS_DP - 8`(两层各 4dp padding 后的内沿);宽 ≤ 最窄常见档槽宽 `(320 - 32 - 8) / 5 = 56dp`。另加一条 `actionButton_isAPillNotACircle`(宽 > 高)把用户"不要纯圆形"这条要求钉死,防止以后被"顺手改回正圆"。
- **验证**:`assembleDebug` + `testDebugUnitTest` **BUILD SUCCESSFUL**,**260 用例 / 0 失败 / 0 错误**。⚠️ 形状/配色仍无渲染层测试,只能真机看。

### 三轮:去掉容器,动作槽做成普通 tab 外观(同日,用户"不要容器了行不行,直接像其他 icon 图标一样在导航栏,下面是文字直播")

- **用户要求**:不要任何容器,直接像其余四个 tab 那样裸图标 + 文字「直播」。
- **改动(净删代码)**:删掉 `ui/navbar/NavActionButton.kt` 整个文件;删掉 `NavMetrics.ACTION_BUTTON_WIDTH_DP` / `_HEIGHT_DP` 两个常量与对应的两条单测(胶囊形状/尺寸约束那两条随之失效)。**外观改为直接复用 tab 组件**:
  - 玻璃条:`NavActionSlot` 里 `NavTabItem(tab = actionItem, selected = false, onClick = onActionClick, role = Role.Button)` —— 与两侧 tab 同一个组件、同一套配色(`onSurfaceVariant`)与按压缩放(`LocalNavTabScale`)。
  - 关玻璃横条:插一个 `selected = false` 的 `NavigationBarItem`。
  - 关玻璃竖条:插一个 `selected = false` 的 `NavigationRailItem`。
- **`NavTabItem` 新增 `role: Role = Role.Tab` 参数**:动作槽传 `Role.Button`(它跳独立 Activity,不是切换目的地),普通 tab 走默认值。⚠️ M3 的 `NavigationBarItem` 内部把 role 写死成 `Role.Tab`(`NavigationBar.kt:225`),关玻璃档改不了 ⇒ 只影响读屏播报用词,已在 spec 里记明是刻意留的不一致。
- **染色层仍放 `Spacer`**:动作槽永远不是"选中项",胶囊滑过时不该把它点亮成 primary。这一条与外观无关,三轮都保留。
- **保留不变的部分**:五等分槽、`slotPosition` 连续插值、`tabIndexOfSlot`、`actionSlotFor` 及它们的 7 条单测全部照旧 —— **这三轮的反复只在"长什么样",几何与映射一行没动**,说明上一轮把判据抽进 `NavMetrics` + 立单测的收益是真的。
- **验证**:`assembleDebug` + `testDebugUnitTest` **BUILD SUCCESSFUL**,**258 用例 / 0 失败 / 0 错误**(260 − 2 条随胶囊尺寸一起删掉)。⚠️ 仍无渲染层测试。

### 四轮:修"长按胶囊划过直播槽一片空白"(同日,用户实测发现)

- **用户报**:「长按圆形指示器划过直播控件时为什么一片空白」。
- **根因 = 上一轮自己埋的 `Spacer`**(当时还写进了 spec 说是"刻意"):我判断"动作槽永远不是选中项,胶囊滑过时不该把它点亮成 primary",于是在染色层给动作槽放了 `Spacer`。
- **机制(值得记住,因为它是这套导航栏的核心)**:**胶囊不是实心色块,是"开在染色层上的一扇窗"**。三层叠画 = ①容器层(玻璃 + 真实图标,正常配色)②染色层(整层 `alpha = 0` + `ColorFilter.tint(primary)`,单独看完全不可见)③胶囊(`drawBackdrop(rememberCombinedBackdrop(backdrop, tabsBackdrop))`,其中 `tabsBackdrop` 就是挂在染色层上的 `layerBackdrop`)。**"选中项变 primary"= 胶囊透出染色层里那一格的 primary 副本**,不是切换图标颜色。⇒ 染色层那一格是空的,胶囊就"透"出一片空白,只剩模糊的页面内容。
- **修法**:染色层照常渲染动作槽(去掉 `Spacer` 分支),**顺带把 `tinted` 参数从 `NavSlotsRow`/`NavSlotsColumn` 整个删掉**(两层现在渲染完全一样,参数已无意义),`NavActionSlot` 辅助函数也随之消失 —— 动作槽就是一次普通的 `NavTabItem` 调用。`Spacer` import 一并移除。
- **⚠️ 教训(比修复本身值钱)**:**"刻意的不一致"要先想清楚它在渲染链路上会变成什么**。我当时只推演了"胶囊会把动作槽点亮成 primary(不想要)",**没有推演"胶囊本身是窗、窗后没东西会怎样"** —— 前半段推理没错,漏的是后半段,结果把一个"看起来更克制"的选择做成了肉眼可见的缺陷。**判断这类"透出/叠画"效果时,必须把每一层单独过一遍"这一层为空时上层会看到什么"。**
- **验证**:`assembleDebug` + `testDebugUnitTest` **BUILD SUCCESSFUL**,**258 用例 / 0 失败 / 0 错误**。⚠️ 观感仍只能真机看。

### 五轮:胶囊落位改成回弹弹簧(同日,用户"将长按指示器的动画改为弹簧效果")

- **先纠正一个前提**:项目里的 `DampedDragAnimation.valueAnimationSpec` 本来就是 `spring(1f, 1000f, visibilityThreshold)` —— **它已经是 spring,但阻尼比 1 = 临界阻尼 = 零回弹**,观感就是普通缓出。所以"改成弹簧效果"的实质是**把阻尼比降到 1 以下**。
- **先查了上游**:`示例文件/android/core/ui/.../animation/DampedDragAnimation.kt` 的参数与本项目**逐字相同**(`spring(1f, 1000f, …)`),确认这是继承来的无回弹版本,不是本地改坏的 ⇒ 本次属**有意偏离上游**(与玻璃高光 shader 那几处同类)。
- **改动**:`spring(1f, 1000f, visibilityThreshold)` → **`spring(0.6f, 800f, visibilityThreshold)`**。过冲 = `exp(-πζ/√(1-ζ²))` ≈ **9.5%**,峰值 ≈0.14s,稳定 ≈0.24s。选 0.6/800 而非更软的参数,是为了和 spec §5 里已经存在的 `spring(0.6f, 800f)`(卡片按压缩放的参照值)保持同一套手感。
- **⚠️ 连带项一(必须做,否则有可见缺陷)**:弹簧会**冲过目标值**,而胶囊的平移量是 `slotPosition(value) * stride` —— 不钳的话 0→4 这种长距离跳转会过冲约 0.4 格(≈24dp),把胶囊顶出玻璃壳。修法:**钳渲染位置,不钳动画值**(动画得能过冲才有回弹):在 `FloatingNavBar` 两处(胶囊平移、`InteractiveHighlight` 位置)对 `slotPosition(...)` 的结果加 `.coerceIn(0f, maxSlotPosition)`,`maxSlotPosition = slotCount - 1`。
- **⚠️ 连带项二(不用改,但要知道)**:`DampedDragAnimation.release()` 里"等 value 追上 target 再收回按压缩放"靠 `|value - target| < 阈值` 判定;弹簧会**穿过**目标值,所以这个条件命中的时机从"稳定时"提前到"过冲峰值时" ⇒ 按压收回略早于原来。观感更连贯,刻意保留。
- **没动的地方**:`pressProgressAnimationSpec` / `scaleXAnimationSpec` / `scaleYAnimationSpec`(阻尼比 1 / 0.6 / 0.7)与 `velocityAnimationSpec`(0.5)**本来就是有回弹的**,只有位置动画是无回弹的那个 —— 这也是为什么此前"按下去有弹性、松手落位却很平"。
- **验证**:`assembleDebug` + `testDebugUnitTest` **BUILD SUCCESSFUL**,**258 用例 / 0 失败 / 0 错误**。⚠️ 弹簧手感无单测可覆盖(纯渲染),只能真机拖一下看;调参入口只有一个 spec。

### 六轮:修"滑过直播时会加速"(同日,用户实测报"滑过直播时会加速,这是bug吗";顺带对齐 legado 的拖动手感)

- **用户报的是真 bug,而且是我三轮引入的**。机理:`value += dragAmount / slotStridePx` 把手指位移换算成**页面空间**的值,而渲染位置是 `slotPosition(value) * stride`;`slotPosition(v) = v + clamp(v-1, 0, 1)` 在 `v∈[1,2]`(正好跨过动作槽)的**斜率是 2** ⇒ 那一段胶囊视觉位移 = 手指的 **2 倍**。从历史拖到收藏(相邻两页)手指走 1 格、胶囊走 2 格,就是"突然加速"。
- **教训**:三轮我为了"避免胶囊跳格"选了连续插值,**只盯着"不要跳",没检查它的导数**。凡是"把输入经过一个函数映射成位置"的动画,都要算一下**这段映射的斜率**;斜率 ≠ 1 就是变速。**消掉跳变不等于手感对。**
- **对齐参照**:用户要求"手感改成和 legado-with-MD3 一样"。查了 `示例文件/legado-with-MD3-main` —— 它用的是**同一个 `DampedDragAnimation`(参数逐字相同)**+ 同源 `FloatingBottomBar`,关键差异只有一处:legado 的胶囊位置是 **`progressOffset = value * singleTabWidth`**,`InteractiveHighlight` 位置是 **`(value + 0.5f) * tabWidthPx`** —— **纯线性,没有任何映射函数**。⇒ 改法明确:把拖动搬回"槽位空间"。
- **改动**:
  - `NavMetrics.slotPosition`(连续插值)**删除**,换成离散的 `slotIndexOfTab(tab, actionSlot)`(页面→槽位,`tab >= actionSlot` 时 +1);`tabIndexOfSlot` 保留。
  - `FloatingNavBar`:`valueRange = 0f..(slotCount-1)`、`initialValue = slotIndexOfTab(selectedTabIndex())`、`onDrag` 的 `coerceIn` 上界改 `slotCount-1`、`onDragStopped` 先 `round()` 得槽位再 `tabIndexOfSlot` 换页面、`snapshotFlow { currentIndex }` 里也经 `slotIndexOfTab` 换算。
  - 两处渲染位置改回纯线性:`value.coerceIn(0f, maxSlotPosition) * singleTabStride` 与 `(value.coerceIn(...) + 0.5f) * slotStridePx`(钳位只用于兜住弹簧过冲)。
  - `dampedDragAnimation` 的 `remember` keys 补 `slotCount`(valueRange 依赖它)。
- **等价性**:拖动起点在胶囊上(手势挂在胶囊 Box),位置 = 初值 + 累计 delta ⇒ 手指移 1 格步长、值 +1、胶囊移 1 格,**严格 1:1**,与 legado 完全一致。
- **⚠️ 顺带发现的差异(没改,判断为对本项目无实际影响)**:legado 的 `DampedDragAnimation` 多一个 `canDrag` 守卫,在每次 `onDrag` 里检查"手指是否还在条内",不在就不应用这次 delta。但 `updateValue` 本来就 `coerceIn(valueRange)`,两端早已夹住 ⇒ 对本项目无可感差异;且我们 fork 的 `示例文件/android` 版本本来就没有它。**若以后要做"手指移出条外就冻结胶囊",照 legado 那段抄即可。**
- **⚠️ 弹簧与参照不一致(刻意保留,已告知用户)**:legado 的 `valueAnimationSpec` 是 `spring(1f, 1000f)`(无回弹),而本项目在上一轮按用户要求改成了 `spring(0.6f, 800f)`(有回弹)。用户本轮说的"滑动手感"指拖动跟手,不含松手落位,故保留回弹;若要完全对齐 legado,把那一行改回 `spring(1f, 1000f, visibilityThreshold)` 即可。
- **验证**:`assembleDebug` + `testDebugUnitTest` **BUILD SUCCESSFUL**,**258 用例 / 0 失败 / 0 错误**(映射类单测重写:删掉"单调不减 / 连续插值"那 4 条,换成 `slotIndexOfTab_skipsTheActionSlot` / `slotIndexOfTab_roundTripsBackToTheSamePage` / `slotIndexOfTab_withoutActionSlot_isIdentity` / `actionSlotIsTheOnlySlotWithoutAPage`,净数不变)。⚠️ 跟手手感只能真机拖。

### 七轮:点击 tab 的胶囊"瞬移" + 预设色卡在平板上被放大 + 首屏右下角空格(同日,用户三个问题一并处理)

- **用户问(导航栏)**:「首页的液态玻璃底部导航栏点击 tab 后感觉那个圆形的激活反馈指示器会跳过去,速度太快了,这是 bug 吗」。
- **结论:不是缺陷,是弹簧的固有性质 —— 但确实该改。** 位置动画 `spring(0.6f, 800f)` 的**稳定时间几乎与距离无关**,峰值速度却与距离成正比:数值模拟(ζ=0.6 / k=800,终止条件 `|x|<0.001` 且 `|v|<1`)1 格 0.238s / 峰值 ≈14.1 槽/秒,2 格 0.238s / ≈28.2,3 格 0.371s / ≈42.3,**4 格(首页→设置,槽位 0→4)0.371s / ≈56.4 槽/秒** ⇒ 跨 4 格时胶囊是"飞"过去的,看起来就是瞬移。
- **第二层原因(顺带查明,未改)**:框架 `PagerState.animateScrollToPage` 里 `updateTargetPage(targetPage)` 在动画**开始前**就执行(读 `foundation-android-1.13.0-alpha01-sources.jar` 的 `PagerState.kt` 确认)⇒ `targetPage` 立即变目标页、胶囊与页面同时起跑;且 `MaxPagesForAnimateScroll = 3`,**距离 ≥3 页时先 `snapToItem` 预跳**再动画最后一段 —— 4 tab 下"首页↔设置"距离正好 3,本来就是瞬间换页。
- **改法(刻意只改点击、不动拖动)**:`DampedDragAnimation.animateToValue(value, animationSpec = null)` 加可选 spec;`FloatingNavBar` 的点击链路(`snapshotFlow { currentIndex }.drop(1)` → `animateToValue`)传 `clickMoveSpec(距离)` = `tween(140 + 90×格数 ms, FastOutSlowInEasing)`,上限 520ms。**拖动松手仍走 `valueAnimationSpec`(弹簧)** —— 上一轮用户要的回弹手感不受影响。
- **⚠️ 取舍(已告知用户)**:换成 tween 后点击落位不再有回弹;若更想要"回弹 + 不瞬移",把 `clickMoveSpec` 换成低刚度弹簧(刚度按 `1/距离²` 降)即可,入口只有这一个函数。
- **用户报(主题设置页)**:「预设色卡里面的色卡好像没有自适应,被拉伸的很奇怪」。**根因**:色卡是 1:1 正方形、卡宽由 `weight(1f)` 决定、列数写死 4 ⇒ 平板上单张被等比放大到 ≈250dp(手机 65dp),10dp 的条状色块细成发丝、中间空出一大片,观感就是"被拉伸"。**改法**:`BoxWithConstraints` 里按可用宽度在 **4 / 8 列**间切换(8 列要求每张 ≥80dp,即卡内可用宽 ≥724dp),**8 = 预设色卡总数、两行排满不留缺口**;末行不满时用等宽 `Spacer(weight(1f))` 顶住(否则 `weight` 会把末行卡片摊宽)。⚠️ 参照项目 `示例文件/android` 的实现与本项目**逐字相同**,同样没有宽屏适配 —— 这是它作为手机应用的固有缺口,不是移植走样。
- **用户报(首页)**:「平板模式横屏下右下角的海报不刷新」「就是不会自动刷新,往下滑就刷新了」。**定位**:不是图片加载问题,是**首屏没排满**。竖向网格的"加载更多"靠列表末尾的哨兵项(`item(key = "more_$tabId")` 里的 `LaunchedEffect`)触发,哨兵压在视口外时**根本不组合** ⇒ 只按需加载的设计下首屏永远只有第一页;源每页 20 张、平板 7 列时正好 = 2 整行 + 第 3 行 6 张,**右下角第 7 格空着**,看起来就是"那张海报没刷新出来"(截图里该格确实是背景色)。下滑把哨兵带进视口才补上。**改法**:在栅格外套 `snapshotFlow { tabGridState.layoutInfo }` —— 最后一个可见项离末尾不足一行(`lastVisible >= totalItemsCount - 1 - gridColumns`)就取下一页;⚠️ **实际用 `first` 不是 `collect`**(每次内容变长只预取一次):源报 `maxPage=0` 且翻到空页时 `hasMore` 永远为真,`collect` 会被"响应→重组→重新布局→再次命中"套成连环请求。手机上首屏 12 格、离末尾远,行为不变。
- **⚠️ 编译坑**:这条 `LaunchedEffect` 一开始写在 `when (state)` 分支里 ⇒ 那段代码属于 `LazyGridScope` 的 content lambda(**不是 @Composable**),直接报 `COMPOSABLE_INVOCATION: @Composable invocations can only happen from the context of a @Composable function`。**栅格外层**(`BoxWithConstraints` 内)才是可组合上下文。
- **把判据抽成纯函数 + 立单测(同轮补)**:`shouldPrefetchNextPage(lastVisibleIndex, totalItemsCount, columns)` 提到 `HomeGridLayout.kt` 顶层 `internal`(与 `ratingBadgeText` / `NavMetrics` 同一套做法),新增 `HomeGridPrefetchTest` **5 例**(平板首屏有空格的 20/22/7 → true;手机首屏 11/22/3 → false;取完第二页 20/42/7 → false;走到末尾 → true;空列表 → false)。收益:列数/页大小以后怎么改,这条口径都有回归网 —— 上一轮 `NavMetrics` 的同类收益已经验证过一次。
- **验证**:`assembleDebug` + `testDebugUnitTest` **BUILD SUCCESSFUL**,**263 用例 / 0 失败 / 0 错误**(258 + 新增 5)。⚠️ 三处都是观感 / 首屏行为,单测覆盖不到,只能真机看。
- **两轴审查(本轮)**:错误遗漏 —— ①`animateToValue` 全项目只有 2 个调用点(`onDragStopped` 走默认弹簧、点击链路走 tween),API 改动已封闭;②横向布局(首页另一种排布)的"加载更多"哨兵在**行尾右侧**、行被裁在卡片中间不留空格,故不需要同样处理;③导航动画关闭时 `scrollToPage` 是瞬跳,胶囊仍会按 tween 扫过去(行为变化,已记)。引入回归 —— ①平板首屏现在会**多取一页**(40 张),换来右下角不留空格;条件在取完后自动失效,不会连环拉取;②4 格跳转的胶囊时长 500ms,若嫌慢调 `ClickMovePerSlotMs`;③`DampedDragAnimation` 的公开方法多了一个默认参数,默认值 = 原弹簧 ⇒ 既有调用点行为不变。
- **同类缺陷一并修(查全部消费方时发现)**:栏目二级页 `PartitionListActivity.VideoGrid` 是**同一副骨架**(自适应 `gridColumns` + `itemsIndexed` + 全宽"加载更多"哨兵),同样会在宽屏首屏末行留空格 ⇒ 接同一条 `shouldPrefetchNextPage`(`enableLoadMore` 为 false 的搜索结果入口自动跳过;`PartitionListViewModel.loadMore()` 本就有 `state==Ready && hasMore && !loader.busy` 守卫,不会连环拉)。`CollectPage` 是本地收藏列表、无分页哨兵,**不需要接**。
- **⚠️ 顺手纠正一条过时的活规范**:spec 里"`PartitionListActivity` 的'加载更多'用了硬编码 `GridItemSpan(3)`,改列数前必须先统一"是**过时记录** —— `git log -S "GridItemSpan(3)"` 显示 `e4baccf` 就已经把它改成 `maxLineSpan` 了(该提交里能直接看到 `- GridItemSpan(3)` / `+ GridItemSpan(maxLineSpan)`),而且 spec §8 的"阶段一 已完成"那条**自己已经写了"已修正"** —— 两处互相矛盾。已按实际代码改写。**教训:活规范里"未修/待修"的条目要标时间点并定期对账,否则会误导后来人去做一件已经做完的事。**

## surface 模式底栏换成 M3 短变体(2026-09-23,用户选方案 b;已编译+单测,**未装机**)

- **动机**:用户「surface 模式下的导航栏能否改为和 legado 一样的高度」。查证:两边 material3 版本**相同(1.5.0-alpha28)**,但组件不同 —— 我们用 `NavigationBar`(tall 变体,`NavigationBarTokens.TallContainerHeight` = **80dp**),legado 的 `AppNavigationBar` M3 分支用 **`ShortNavigationBar`**(`NavigationBarTokens.ContainerHeight` = **64dp**)。⚠️ 顺带查明 spec §3 记的「高度 56dp」**从未在代码里落地**(全仓无 `NavigationBarDefaults`/`ContainerHeight`/`height(56.dp)`,`git log -S` 在这两条路径上也查不到痕迹),已按实际改写。
- **改动**:`ui/page/MainScreen.kt`(+6/-5)—— 关玻璃横条档 `NavigationBar` / `NavigationBarItem` → `ShortNavigationBar` / `ShortNavigationBarItem`,imports 同步替换;**竖条档 `NavigationRail` 不动**(用户问的是底部横条)。
- **已核对「不变」的两项(从 M3 源码与 aar 字节码)**:
  - **配色不变** —— 两个变体的默认色取自**同一组 token**(`ItemActiveIconColor` / `ItemActiveLabelTextColor` / `ItemActiveIndicatorColor` / `ItemInactiveIconColor` / `ItemInactiveLabelTextColor`),且都走 `MaterialTheme.colorScheme.default*`(主题感知)。
  - **a11y 不变** —— 两个 item 共用内部 `NavigationItem`(`NavigationItem.kt:378` 的 `selectable(role = Role.Tab)`)⇒ "动作槽被读成标签页"那处既有不一致照旧。
- **会变的是几何**:短变体的指示器形状/宽度(`NavigationBarVerticalItemTokens.ActiveIndicatorWidth` + `ItemActiveIndicatorShape`)、`arrangement`(默认 `EqualWeight`)、图标与文字间距 ⇒ item 变成 M3 expressive **短样式**(即 legado 那个样子)。`Scaffold` 的 `innerPadding` 自动跟着变 ⇒ 页面底部留白少 16dp,**无需手改任何 padding**。
- **连带修正 spec §4.11(重要)**:「关玻璃的横条怎么插」原文写「`NavigationBarItem` 内部就是 `Modifier.weight(1f)`(已核对 `NavigationBar.kt:230`)」—— 短变体**不是 `RowScope` 扩展、没有 `Modifier.weight`**,等宽改由 `EqualWeightContentMeasurePolicy` 按 `width / itemsCount` 平分(`ShortNavigationBar.kt:379`)⇒ 已按实际改写(插入方式不变:直接在 content 里插一个 `selected = false` 的 `ShortNavigationBarItem` 仍天然等宽)。另 §3 / §4.11 / §6 共 8 处 `NavigationBar` 引用同步更新(残留的 `NavigationBar` 字样都是系统导航栏的 `isNavigationBarContrastEnforced` / `isAppearanceLightNavigationBars`,无关)。
- **验证**:`:app:compileReleaseKotlin` **BUILD SUCCESSFUL**;`:app:testDebugUnitTest` **BUILD SUCCESSFUL,263 用例 / 0 失败 / 0 错误**。⚠️ 两条命令都必须加 `-x :pyramid:installReleasePythonRequirements`(或 Debug 那个),否则 Chaquopy 的 pip 安装会被宿主机 safe-delete 策略拦(pip 清缓存 + 批量删除确认)——**环境问题,与本次改动无关**。
- **未做**:装机(按用户口径"明确说安装到我的设备时才装");短样式的 item 几何只能真机看。

## 底栏 tab 文字改为"只在激活时出现"(2026-09-23 四轮,与 legado 对齐;已编译+单测,**未装机**)

- **用户要求**:「把底部导航的控件改成和 legado 一样,在没有激活时只出现图标,点击后图标往上抬,然后下面是文字,包括液态玻璃」。
- **查到的 legado 判据**:玻璃条在 `MainScreen` 里 `if (showLabel && (alwaysShowLabel || selected)) { AppText(label) }`;关玻璃档 `AppNavigationBarItem` 把 `label = if (m3ShowLabel && (m3AlwaysShowLabel || selected)) { … } else null` 交给 `ShortNavigationBarItem`(其中 `showLabel = !isUnlabeled`、`alwaysShowLabel = (labelVisibilityMode == "labeled")`,默认档即"仅选中显示")。⇒ **两套都是普通 `if`、瞬间切换、无动画**。
- **改动 2 处**:
  - `ui/navbar/FloatingNavBar.kt` 的 `NavTabItem`:把 `Text` 包进 `if (selected) { … }`(图标仍 24dp、列仍 `spacedBy(1.dp, CenterVertically)` ⇒ 文字出现时图标自然上抬)。
  - `ui/page/MainScreen.kt` 的关玻璃档:`ShortNavigationBarItem(label = if (selected) { { Text(…) } } else null)`,动作槽传 `label = null`。⚠️ M3 的 `ShortNavigationBarItem` **没有 `alwaysShowLabel` 参数**,只能靠"不给 label"实现 —— 与 legado 同法。
- **机制核对(material3 1.5.0-alpha28 源码)**:`ShortNavigationBarItem` → 内部 `NavigationItem` 的 `TopIconOrIconOnlyMeasurePolicy` 是 `if (hasLabel) placeLabelAndTopIcon(…) else placeIcon(…)` 的**硬分支**(`NavigationItem.kt:711`)⇒ 无过渡;`NavigationItem` 仍是 `selectable(role = Role.Tab)` ⇒ **a11y 不变**。
- **连带(已告知用户)**:动作槽(直播)永远未激活 ⇒ **两套里都只剩图标、不再有「直播」文字**;这与三轮定稿的"裸图标 + 文字"不一致,已按四轮修正写进 spec §4.11。
- **刻意不做**:不加 `AnimatedVisibility` / 缓动 —— 用户要的是"和 legado 一样",legado 就是瞬间切换;要缓动另开一轮。
- **验证**:`:app:compileReleaseKotlin` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL,263 用例 / 0 失败 / 0 错误**。⚠️ 两个命令都要加 `-x :pyramid:installReleasePythonRequirements -x :pyramid:installDebugPythonRequirements`(理由同前:Chaquopy 的 pip 安装被宿主机 safe-delete 策略拦,环境问题)。
- **未做**:装机。
- **⚠️ 真机 bug + 修正(同轮)**:用户报「surface 模式下从首页点击 tab 到设置页,中间的**历史和收藏图标会往上闪烁一下**」。**根因**:关玻璃横条的 `selected` 误用 `pagerState.currentPage` —— 它是"滚动途中离吸附点最近的那页",跨 3 页滚动时会**依次经过历史、收藏** ⇒ 中间页被短暂判成选中、文字一闪、图标被顶上去。**改法**:`val selected = pagerState.targetPage == index`(legado `MainScreen.kt:437` 与我们的玻璃条 `selectedTabIndex = { pagerState.targetPage }` 本来就用 targetPage,只有这条写错了)。⚠️ **竖条档 `NavigationRailItem` 仍是 `currentPage`,没改** —— 它的 label 常显 ⇒ 不会闪文字,只有"指示器扫过中间项"的差别,等用户确认再对齐。
- **文档**:spec §4.11 的「tab 文字可见性」小节已补 `targetPage` 这条坑与竖条档的现状说明。

## CI:加入 Dependabot 配置 + 自动合并工作流(2026-09-24,用户"参考示例文件的两个yml,那是正确的")

- **来源**:`示例文件/dependabot.yml` 与 `示例文件/dependabot-auto-merge.yml`(用户指定为正确参照)。落地为 `.github/dependabot.yml` 与 `.github/workflows/dependabot-auto-merge.yml`(新增 2 个文件)。
- **dependabot.yml 的适配(不是照抄)**:①`directory` 由示例的 `/android` 改为 **`/`** —— 本项目 Gradle 根就是仓库根(`settings.gradle.kts` + `gradle/libs.versions.toml` 都在根,模块是 `app` / `player` / `quickjs` / `pyramid` / `libs:backdrop`);②`groups` 按**我们的实际依赖**重写为 **13 组**(compose / media3 / room / testing / agp / ksp / chaquopy / kotlin / androidx 兜底 / coil / ui-libs / network / misc),保留示例里那条关键顺序约定「具体分组必须排在 `androidx.*` 之前,否则被兜底组抢先匹配」;③保留 material3 的 `ignore`(`>=1.5.0-alpha.24, <1.5.0`),但把理由改成本项目的:已停在 alpha28、自研 SheetOverlay 与液态玻璃都建立在这版行为上,alpha 回归只在运行时可感知、会被 auto-merge 合入;④commit-message 前缀用 `deps`(示例是 `deps(android)`,那是因为它有 `android/` 子目录)。
- **auto-merge 工作流的适配**:①去掉 `working-directory: android`(根目录即 Gradle 根);②编译验证命令改为 `./gradlew compileDebugKotlin -x :pyramid:installDebugPythonRequirements --no-daemon -q` —— **必须 -x**:`compileDebugKotlin` 在本项目会依赖 Chaquopy 的 `installDebugPythonRequirements`(联网装 pip 包),那步失败会把整条验证误报成"依赖更新有问题",而它跟"Kotlin 能否编译"无关;③其余照抄(JDK 21 + `gh pr review --approve` + `gh pr merge --auto --squash`,都用 `secrets.PR_APPROVE_TOKEN`)。
- **⚠️ 用户需手动做两件事(否则 auto-merge 不生效)**:①**PAT 必须存到「Dependabot secrets」,不是 Actions secrets** —— 官方 *Troubleshooting Dependabot on GitHub Actions* 明确写:「Dependabot 事件触发的 workflow 只有 **Dependabot secrets** 可用,GitHub Actions secrets **不可用**;而且这类运行被当作来自 fork,默认拿到的是**只读 `GITHUB_TOKEN`**」。所以路径是仓库 `Settings → Secrets and variables → Dependabot → New repository secret`,名字 `PR_APPROVE_TOKEN`(与 workflow 一字不差);PAT 权限:fine-grained 要 `Contents: Read and write` + `Pull requests: Read and write`,classic 勾 `repo`(公开仓库 `public_repo` 即可)。②仓库 Settings → General 勾 **Allow auto-merge**。③建议给 `main` 加 branch protection + *Require status checks to pass before merging*,让 auto-merge 等 CI 绿了再合。
- **验证**:两个 YAML 都用 PyYAML 解析通过(`updates` 2 条、gradle 条目 13 个 group;工作流的 `on:` 被 YAML 1.1 解析成布尔键属正常现象,GitHub 照常识别)。
- **⚠️ 查官方文档后修正/确认的两点(2026-09-24)**:
  - ①**github-actions 的 `directory` 必须写 `/`** —— 官方 *Dependabot options reference* 原文:「For GitHub Actions, use the value `/`. Dependabot will search the `/.github/workflows` directory, as well as the `action.yml/action.yaml` file from the root directory.」示例文件里写的是 `/.github/workflows`,已改为 `/` ✓。
  - ②**`ignore.versions` 的多条是「或」关系,原示例那条会变成"永久忽略"** —— 官方示例:`versions: ["4.x", "5.x"]` 注释是"ignore all updates for version 4 **and** 5" ⇒ 多条取并集。所以 `[">=1.5.0-alpha.24", "<1.5.0"]` = 「≥alpha.24 **或** <1.5.0」= **任何版本都命中** ⇒ 实际效果是**永久忽略 material3,连 stable 1.5.0 也收不到**,与示例注释声称的"保留 stable 及以后"不符。**待用户选择改法**:A) 单条区间 `["[1.5.0-alpha.24,1.5.0)"]`(区间内部是「与」语义,stable 会照常收到;官方也给了 `[1.1,)` 这种括号区间示例);B) 直接删掉 `versions`,只留 `dependency-name`,等 stable 出来手动删行。
  - 另确认:`dependency-name` 在 **Gradle 必须用 `groupId:artifactId`**(官方表格明确),我们写的 `androidx.compose.material3:material3` ✓ 正确;`schedule` 的 `time` 默认按 UTC,配 `timezone: Asia/Shanghai` 后 `time: "09:00"` = 北京时间 09:00 ✓;Dependabot 只从**默认分支**读 `dependabot.yml`,且对版本更新**默认有 3 天 cooldown**(新版本发布 3 天后才考虑,security update 不受限)。
- **⚠️ 液态玻璃库是本地 fork,机器人升不了它(用户 2026-09-24 提出,确认成立)**:`libs/backdrop` 是**仓库内源码模块**(`settings.gradle.kts:41` 的 `include(":libs:backdrop")` + `app/build.gradle.kts:192` 的 `implementation(project(":libs:backdrop"))`),不是 Maven 依赖 ⇒ **Dependabot 没有版本可升**;`libs/backdrop/build.gradle.kts` 头部注释也早写了「本地 fork 自 `io.github.kyant0:backdrop` 2.0.1 …**上游升级需手工合并**」。**机器人能升的是它周边的 Maven 依赖**:`io.github.kyant0:shapes`(fork 的运行时依赖,lens 的圆角 SDF 形状)+ `io.github.kyant0:capsule`(App 侧的连续曲率胶囊)+ fork 里硬编码的 `org.jetbrains:annotations:26.1.0`。
- **⚠️ 由此带出的 auto-merge 风险(待用户决定)**:`shapes` / `capsule` 是**fork 的直接依赖**,升级它们**编译能过、但观感可能变**(连续曲率胶囊的圆角算法就在 capsule 里)⇒ 建议把 `io.github.kyant0.*` 排除出 auto-merge(PR 照开、只 approve 不自动合)。可选做法:A) auto-merge workflow 加 `dependabot/fetch-metadata`,依赖名含 `io.github.kyant0` 就跳过 merge;B) 把 `io.github.kyant0.*` 从 `ui-libs` 组单列 + 打标签,workflow 见标签跳过;C) 直接 `ignore` 掉这两个库(不跟踪,但也就不知道上游更新了)。
- **为什么单独 `ignore` material3(2026-09-24 用户追问后逐条核实,结论:要排除,但理由不是示例那条)**:
  - **示例给的理由不适用于我们** —— 示例是因为 **alpha27 的 `ModalBottomSheet` 行为变更**(SheetState 构造不再预置动画规格)导致底部抽屉弹出动画失效才忽略的;而我们的 `ModalBottomSheet` **早已删除**(改成自研 `SheetOverlay`,`grep -rn ModalBottomSheet app/src/main` **零命中**)✓。
  - **我们真正暴露在 alpha 变动下的是这些**:
    1. **material3 版本本来就是"用户指定、显式覆盖 BOM"** —— spec §2 记着 `material3 = 1.5.0-alpha23(用户指定,显式覆盖 BOM)`(实际已 alpha28)⇒ 这个版本号是**有意为之的决定**,不该被机器人自动改。
    2. **我们用的全是 alpha 才有的 expressive API**:`ContainedLoadingIndicator`(5+ 文件)、`ShortNavigationBar`/`ShortNavigationBarItem`(今天刚加)、`pullToRefresh`、`ToggleButton`(`CapsuleSegmentedButton`)—— 全带 `@ExperimentalMaterial3ExpressiveApi` ⇒ alpha 之间改签名/改行为是常态;**编译破坏 CI 能挡,但"尺寸/动画/形状变了"编译挡不住**。
    3. **自研弹层仍依赖 material3 的默认值**:`BottomSheetDefaults.ScrimColor` / `ContainerColor` / `DragHandle()`(spec 还专门记了"弹层维持 `BottomSheetDefaults.ScrimColor`(0.32)不变")⇒ 换 alpha 一样可能改观感。
    4. **已经被 alpha 咬过一次(实证)**:spec 记着 `material3 1.5.0-alpha23` 的 `rememberModalBottomSheetState` 被弃用,当时的弹层封装被迫改用 `rememberBottomSheetState`。
  - ⇒ 结论:**排除是对的**(真正理由 = "用户显式指定的版本 + 全押在 expressive API 上 + 自研组件依赖 M3 默认值",不是 ModalBottomSheet)。
  - **✅ 已按方案 A 修掉「排过头」的问题(2026-09-24,用户"改吧")**:原写法 `versions: [">=1.5.0-alpha.24", "<1.5.0"]` 因为多条是**「或」**关系,实际把 **stable 也一起排除**了(等于永久忽略)。现改为**单条 Maven/Gradle 区间** `versions: ["[1.5.0-alpha.24,1.5.0)"]` —— 区间内部是**「与」**语义 ⇒ 只忽略 `[1.5.0-alpha.24, 1.5.0)`,`1.5.0` stable 发布后**自动放行**,不用手动解禁。⚠️ 注意 YAML 里这个字符串以 `[` 开头,**必须加引号**(已加);已用 PyYAML 校验为「单元素 list、内容为字符串」✓。

### 工作流跟示例对齐:加 `concurrency` 串行化 + `gh pr merge` 重试(2026-09-24 00:29,用户"改成示例文件里的dependabot-auto-merge.yml")

- **背景**:用户更新了 `示例文件/dependabot-auto-merge.yml`(mtime 00:26),新增两处改进 —— ①**`concurrency: {group: dependabot-auto-merge, cancel-in-progress: false}`** 全局串行化(注释原话:"避免多个 Dependabot PR 并发调用 auto-merge API 导致竞态失败");②**`Enable auto-merge` 加 3 次重试**(每次间隔 10s,全失败 `exit 1`)应对并发/rebase 的瞬时失败。⇒ 这也回答了我上一轮问的"要不要加并发约束"。
- **落地**:`.github/workflows/dependabot-auto-merge.yml` 按示例重写,**只保留两处本仓库必须的差异**(已写进文件注释):
  1. **删掉 `working-directory: android`** —— 本仓库 **Gradle 根就是仓库根**(`settings.gradle.kts` 在根),**没有 `android/` 子目录** ⇒ 照抄会让该 step 直接报 "directory does not exist" 而失败。
  2. **编译命令加 `-x :pyramid:installDebugPythonRequirements`** —— 否则 `compileDebugKotlin` 会去跑 Chaquopy 的 pip 安装(要联网 + Python 3.10),那步失败会把整条验证**误报**成"依赖更新有问题"。
- **验证**:`diff` 确认与示例**只差上述两处**(+ 一行注释);PyYAML 解析通过(`concurrency` 正确读为 dict、5 个 step 名称齐全)。
- **未提交**(等用户指示;上一批 CI 文件已于 00:26 提交推送为 `604a7d3`)。

## 修:液态玻璃导航栏「拖动切页多次后全进程掉帧」(2026-09-24,用户"把液态玻璃导航栏关掉之后变成surface导航栏,怎么切换tab都不会触发卡顿了")

- **现象与定案**:开启液态玻璃时,长按指示器拖动切页若干次后**全进程任何动画都掉帧**(会"转移"到当下在播的动画上,例如首页订阅源 sheet 的弹出动画),退出应用重进恢复。禁用「导航动画」(关 pager 侧滑)仍复现 ⇒ 与 pager 动画无关;**关掉「底部导航」玻璃开关退回 surface 导航后完全不卡**(用户实测)⇒ 根因在玻璃导航栏的渲染链路上。⚠️ 期间曾把"关闭导航动画"误读成"关闭玻璃",一度错判为"与玻璃无关"。
- **根因**:`effects{}` 里读到的任何 State 变化都会让整条 renderEffect 链重算,按压/拖动期间 `pressProgress` 逐帧变化 ⇒ **每帧**新建一串 native `RenderEffect`(colorControls 的 ColorMatrixColorFilter + blur + lens 的 RuntimeShaderEffect + 逐层 chain)并逐帧 `setRenderEffect`,RenderThread 侧管线反复重建;`InnerShadow(radius = 8.dp * progress)` 另加每帧 `BlurEffect`。native churn 累积表现成全局掉帧,而窗口重建(切后台)会把这类资源释放掉 ⇒ "退出重进就好"。
- **改动(库层 3 + app 层 2,观感零变化)**:
  1. 新增 `libs/backdrop/.../internal/RenderEffectCache.kt`:`BackdropEffectScopeImpl` 持有一份,`apply()` 前 `begin()` 按调用序对齐槽位;blur(半径/TileMode)、colorFilter、chain(内外引用)在"输入未变"时复用上次实例。
  2. ⚠️ **runtimeShader 型 effect 必须带"uniform 值签名"才可复用** —— Skia 的 RuntimeShader filter 在**创建时快照 uniform**(与 `RuntimeShaderBrush` 那种"绘制时实时读"的路径不同);`lens()` 的签名覆盖 `size.width/height`、`padding`、`cornerRadii`、`refractionHeight/refractionAmount/depthEffect/chromaticAberration`。
  3. colorFilter 的"值"取不到(`ColorMatrixColorFilter.colorMatrix` 是 private)⇒ 签名由调用侧传:`colorControls`/`opacity` 传参数值,通用 `colorFilter()` 入口传 null 退化为身份比较(不劣于改前)。
  4. `FloatingNavBar` 按压折射强度改**量化驱动**(`GLASS_PRESS_LENS_STEPS = 4` + `Float.quantizePressProgress()`):折射只在跨档时换 effect ⇒ 从"每帧重建"降到"每次按压约 3 次";4 档在 300ms 弹簧里每档 ~75ms,观感等同连续(常量可调,设 1 等于去掉渐变)。
  5. `DrawBackdropNode.updateEffects()` 加"引用未变不写 RenderNode"(`onAttach`/`onDetach` 复位该记录);`InteractiveHighlight` 的 `ShaderBrush` 提为字段(每帧新建会让画笔缓存失效)。
- **未改(如实记录)**:①`FloatingNavBar` 第三层 `InnerShadow(radius = 8.dp * progress)` 仍逐帧新建 `BlurEffect` 并写 layer(不在 effects 链里,只影响该层;要消除需把"厚度渐入"改成固定半径 + alpha 变化,观感有差,待用户拍板);②`DampedDragAnimation`/`InteractiveHighlight` 每个 move 事件 4 次 `launch` 的协程风暴未动。
- **验证**:`:app:assembleDebug` **BUILD SUCCESSFUL**(APK `app/build/outputs/apk/debug/AVBox_debug.apk`,82.7 MB);`:app:testDebugUnitTest` **263 用例 / 0 失败**;IDE 诊断零新增。**未装机**。
- **待真机确认**:①长按拖动切页 20 次后是否仍累积掉帧;②按压/释放时折射的 4 档递进有无台阶感(`GLASS_PRESS_LENS_STEPS` 可调);③静态观感与改前一致(blur/colorControls 复用后理论上逐像素相同)。

## 缺陷修复:高刷屏单击屏幕「弹幕提速几秒」(2026-09-24,用户报"横屏播放影视时点击一下屏幕,弹幕速度会突然变快,过几秒后恢复正常")

- **现象**:横屏点播单击(显隐控制层)后弹幕明显提速,约 1~2 秒自行恢复;播放本身无异常。
- **定位(真机打点实测,非推理)**:临时在 `MyVideoView.updateTimer(DanmakuTimer)`(库的每帧回调,原本空实现)按 250ms 窗口打 `Δ弹幕时钟/Δ墙钟`,并在单击显隐处打点对齐时序。结论 = **弹幕库固有缺陷在高刷屏上暴露**,与点击逻辑无关(单击只翻控制层显隐,不 seek/不 pause/不改速度)。
  - 库默认 `updateMethod=0`(Choreographer 跟随屏幕刷新率),但每帧时钟推进下限写死 16ms(`mFrameUpdateRate` ← `averageFrameConsumingTime = 16`)⇒ 倍率恒为 `16ms ÷ 实际 tick 间隔`。
  - 实测:60Hz 窗口 `frames=15~16/250ms`(16.6ms/tick)→ ratio **0.96**;点按后 20ms 内翻成 `frames=29~31/250ms`(8.3ms/tick)→ ratio **1.87~1.94**,且 `dTimer` 恒等于 `frames×16ms`(推进被钉在下限),持续 1~2 秒后回落。全程无帧数骤降 ⇒ 排除"主线程卡顿/库内追帧"的猜想(曾误判一版)。
  - 触发源:本机面板默认 120Hz(支持 120/144/90/60),平时播放走 60Hz 档,**触屏后 ROM 提频到 120Hz 并维持约 1~2 秒** ⇒ 该窗口内弹幕快 ~1.9x;"几秒后恢复" = 面板回落。
- **改动(1 文件 2 行)**:建 `DanmakuContext` 后置 `updateMethod = 2`(库自带的 handler 自定速模式,也是 API 16 以下的回落路径)⇒ 循环按 ~16ms 自定速、与刷新率解耦。已核对全部消费方(`DrawHandler.prepare` / UPDATE 分发 / `notifyRendering` / `waitRendering`)与 `mFrameCallback` 空值保护;`DanmakuContext` 与 `danmuView.prepare` 全仓各只有一处调用点。
- **验证**:修复后同法复测 —— 点按后 tick 仍 60Hz、ratio 0.88~1.22(窗口抖动,无 1.9x 长尾);用户真机确认"可以了"。`:app:assembleDebug` BUILD SUCCESSFUL;`:app:testDebugUnitTest` **263 用例 / 0 失败**;IDE 诊断零新增;干净包(无调试日志)已装机。
- **代价(如实记录)**:120Hz 面板上弹幕**绘制**帧率从跟随面板降到 ~60fps(运动平滑度略降),换速度正确;60Hz 面板无变化。顺带修掉修复前 60Hz 下恒 0.96x 的偏差(每 tick 慢 0.66ms)。
- **遗留(既有,非本次引入)**:弹幕时钟仍锚墙钟(`mTimeBase = uptimeMillis() - pausedPosition`)、不与播放器位置同步 ⇒ 缓冲卡顿/拖动/非 1.0 倍速后弹幕与画面错位且不自动纠正;根治需另立改动(库 `setDanmakuSync` 只在 draw 前对齐,或 non-block 模式 + 用播放器位置驱动时钟)。

## 播放器选集入口:横屏全屏底栏「选集」+ 右侧滑出面板(2026-09-24,用户要求)

- **背景**:2026-09-22 删除僵尸选集侧边面板后,播放器没有任何选集入口 —— 手机档(`smallestScreenWidthDp < 600`)运行期锁竖屏、全屏播放又只在横屏,换集只能退出全屏回详情页的选集行。用户要求:横屏全屏播放页点击后从右向左弹出选集面板。
- **入口**:底栏菜单行「片尾」与「投屏」之间加「选集」(`PlayerActions.onEpisodeClicked`),可见性由 `PlayerUiState.episodeBtnVisible` 控制 —— `PlayContainer.updateEpisodeBtnState()` 在 `prepared()` 判定「当前线路剧集数 >1 或线路数 >1」,单集单片不显示(避免点了是空面板)。
- **链路**(选 A:复用详情页面板,不新建播放器侧选集状态):`ComposeVideoController` → `VodControlListener.showEpisodes()` → `PlayContainer`(listener 实现) → `PageHost.showEpisodeSheet()`(新增页面能力) → `DetailActivity`(守卫 `vm.vodInfo != null`) → `vm.showEpisodeSheet()` → 详情页既有 `EpisodeSheet`。选集内容 / 线路切换 / 倒序 / 分组 / 定位当前集全部复用,数据零跨层传递。
- **面板形态**:`AVBoxBottomSheet` 新增 `slideFromEnd`(映射到 `SheetVariant.END`),`EpisodeSheet` 传 `slideFromEnd = fullBox` —— 竖屏详情页仍贴底(行为不变),横屏全屏贴右全高滑出。实现要点:位移 `translationX`、拖拽改 `Orientation.Horizontal` 且主轴尺寸取面板宽、`widthIn(max = min(640dp, 窗口宽 × 0.42)) + fillMaxHeight`、圆角只留左侧两角、不吃 `imePadding`(无输入场景)、不放横条把手(避免误导拖拽方向)、标题顶到面板上缘 16dp。网格适配:列数上限 4 → 2、宽度改 `weight(1f)` 撑满全高。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL;`:app:testDebugUnitTest` 263 用例 / 0 失败;IDE 诊断零新增。**真机行为待走查**,重点三项:①面板打开时右侧边缘上下滑(音量)是否被遮罩吃掉;②横屏刘海屏下面板右侧安全区(当前靠内容自身 16dp 内边距兜);③面板开着时系统旋转 / 折叠导致的 variant 突变(未主动关面板)。
- **已知取舍**:竖屏贴底面板的 `imePadding` 与网格高度逻辑原样保留;`values-zh-rHK` 缺 `detail_episodes` 会回落简体(既有缺口,非本次引入)。
- **补(同日,用户要求「弹窗内不要显示线路,只显示集」)**:`EpisodeSheet` 的线路 chips 行加 `!slideFromEnd` 守卫 —— 侧滑形态只列当前线路的剧集;入口可见性随之收紧为「当前线路剧集数 >1」(单集多线路时按钮不再出现,面板里没有可选项)。竖屏贴底面板保持原样。`:app:assembleDebug` BUILD SUCCESSFUL、`:app:testDebugUnitTest` 263 用例 / 0 失败、已装机(`lastUpdateTime=2026-09-24 04:19:38`)。

## 侧滑面板加宽 + 预设色卡内部色块比例化(2026-09-24,用户要求)

- **侧滑面板宽度 42% → 45%**(用户:「面板能不能长一点,让里面的集数显示长一点,百分之 45 吧」):只改 `SHEET_END_WIDTH_FRACTION` 常量,贴底/居中两个变体不受影响。
- **预设色卡卡内色块改为按卡片边长取比例**(用户:「平板界面的预设色卡这里里面的色块没有自适应」):2026-09-23 那次只切了列数(4/8 列),卡内色块仍是固定 dp(主/次/第三色块高 10dp、内边距 6dp、间距 4dp、勾选圈 18dp)⇒ 卡片尺寸一变(平板 8 列卡比手机卡大),色条相对比例就漂,观感"细成发丝"。改法:`PresetSeedCard` 内套 `BoxWithConstraints` 取 `unit = maxWidth`(卡片 1:1),`barHeight = unit × 0.14`、内边距 `× 0.08`、间距 `× 0.05`、小圆角 `× 0.04`、勾选圈 `× 0.25`;主色条宽度仍是 60%(设计语言不变),文字仍用固定 `labelSmall`(可读性优先)。手机档下换算值与原固定值几乎一致(≈10dp / 6dp / 3.6dp),平板档色块随卡片一起长大。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL、`:app:testDebugUnitTest` 263 用例 / 0 失败、IDE 诊断零新增、已装机(`lastUpdateTime=2026-09-24 04:26:18`)。真机观感待用户确认。

## 修复:竖屏全屏态误用侧滑选集面板(2026-09-24,用户报「竖屏播放界面为什么还是从侧边弹出来的」)

- **现象**:大屏设备(平板)竖屏下点全屏进入全屏态,底栏「选集」弹出的面板从右侧滑出,而不是贴底。
- **根因**:侧滑判据用的是 `fullBox`(= `vm.fullScreen`),而**窗口是否横屏是另一回事** —— Android 12L+ 起,`sw≥600dp` 的大屏设备默认忽略应用声明的方向限制(`requestedOrientation = SENSOR_LANDSCAPE` 不生效),于是窗口仍竖屏、`fullBox` 却为 true ⇒ 竖屏窗口里按横屏形态渲染(实测 = 视频 letterbox + 全屏布局 + 底栏菜单行可见,正是该机型上的现象)。项目里 `isFullBox()` 也是同一套判据,所以 `previewMode` 一并是 false(菜单行可见,用户能点到「选集」)。
- **改法**:判据收紧为 `fullBox && 窗口横屏`(`DetailScreen` 已有 `isLandscapeNow`):只有"横屏 + 全屏"才侧滑;竖屏(含大屏被忽略方向的伪全屏态)与平板横屏但非全屏都保持贴底。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL、`:app:testDebugUnitTest` 263 用例 / 0 失败、已装机(`lastUpdateTime=2026-09-24 04:28:04`)。真机待用户确认。
- **附带观察(既有,非本次引入)**:同一机型上点全屏不会旋转窗口(平台忽略方向限制),所以它在竖屏下进入的是"全屏态但竖屏窗口",由用户自己旋转设备才是真横屏 —— 这是大屏不锁方向的既定策略,本次只让面板形态跟随真实窗口,不改方向策略。


## 修复:影视加载期唤不出播放器控件与进度条(2026-09-24,用户报「加载的时候无法唤出控件,缓冲时没有这个问题」)

- **现象**:从点集数/点播放到首帧起播之间,单击屏幕完全唤不出顶栏/底栏/进度条;同一动作在缓冲(BUFFERING)时正常。
- **根因(源码定位,非推理)**:加载/错误提示层当时画在**详情页 Compose 层**(`DetailScreens.PlayerTipOverlay`),位于整个 `PlayContainer` 之上 ⇒ 把控制器自己的顶栏/底栏一起盖住。而遮罩没有 `pointerInput`,**不拦触摸**(Compose 互操作视图通过 layout node 上的 `pointerInteropFilter` 收触摸,命中测试会继续落到下层;核对过 ui-android-1.12.0 源码:`AndroidComposeView.dispatchTouchEvent` 返回 `dispatchedToAPointerInputModifier`,互操作视图靠 `Modifier.pointerInteropFilter(view)`,绘制走 `drawBehind { drawAndroidView }` 跟随 Compose z 序)⇒ 单击照常执行 `toggleControls()`、`controlsVisible=true` 静默翻转。三个可复现后果:①加载超过 10s 空闲计时(`idleHideMillis`)时底栏在用户看到它之前就已收起 = "根本唤不出";②加载较快结束时底栏在起播瞬间"凭空冒出";③那次不可见翻转会让 `onBackPressed` 先消费一次返回键(横屏全屏要按两下才退出)。缓冲期无此遮罩(转圈是控制器层的 `PlayerLoadingLayer`),故无此问题。
- **改法(8 文件)**:①`PlayerLayers.PlayerTipLayer` 承接遮罩并放在 `PlayerOverlay` 的**最底、顶栏/底栏之前**;②`PlayerTipBridge` 增 `TipStateListener`(`@Volatile`,set/clear 按实例身份摘除,覆盖 `PlaybackController` 绕过页面的直接 `setTip`),`PlayContainer.onTipStateChanged` 回主线程桥入 `PlayerUiState.applyTip` 并转给弹幕;③`PlayerUiState` 增 `tipMsg/tipLoading/tipErr` + `tipVisible`;④`DanmuLoadController` 增 `overlayHidden`(弹幕视图在 `surfaceSlot` 之后、位置在控制器之上),可见性收口到唯一出口 `setViewVisible`;⑤`DetailScreens` 删掉那层遮罩;⑥`PlayerLoadingLayer` 在遮罩期间不画(避免两层转圈);⑦遮罩在屏时让路的浮动层逐个加守卫:暂停浮层、中央网速(IDLE 正是解析期)、倍速提示 —— **不靠调 z 序**,挪层会连带改掉倍速药丸与中央三键的既有叠放。
- **审查(按 SKILL.md 收敛约定)**:阻断 0;高 1 —— `onPlayPauseClicked` 守卫初版只判 `tipLoading`,漏了**错误态**(`errorWithRetry` 后 playState 仍是 IDLE),此时点中央播放键会 `togglePlay → start() → startPlay()` 按内核里残留的上一集地址起播,改判 `tipVisible && !isInPlaybackState()`;中 1 —— 5 处注释带日期/"旧实现…现在…"演进叙事(违反 SKILL.md 注释红线),已清;低 2 —— `applyVisibility` 未尊重 `temporarilyClosed`(用户临时关弹幕后经一次遮罩周期可能被重新显示,已补判定)、错误态中央播放键成为"点了没反应"的死键(保留上一集/下一集入口,故不收紧中央组;要修得往 `PlayerUiState` 复制播放态判据,判定为口味差异不收尾)。导出文档同步:`view_play_container.xml` 注释与 `avbox-mobile-ui-spec.md` §6.1 新增该层级不变量。
- **复核(遮挡带来的"新可点"动作)**:刷新/切内核/解码 → `replay(false)` 本身就是"停掉当前解析再重启";音轨/视频轨/字幕 → 全程判空只 Toast;片头/片尾 → duration=0 时写回 0;选集/弹幕/字幕/投屏 → 页面级面板。均已确认为安全。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL;`:app:testDebugUnitTest` **265 用例 / 0 失败**;`compileDebugKotlin`/`compileDebugJavaWithJavac` 对当前源码 UP-TO-DATE。**真机待走查**:①加载期单击能否唤出并操作底栏;②进度条本体在拿到时长前仍不可拖(既有 `duration > 0` 守卫);③切集复用路径下遮罩上不再飘上一集弹幕、起播后弹幕恢复;④只有一层转圈;⑤错误遮罩下可用「刷新」重试;⑥返回键/竖屏预览态不受影响。
- **遗留(既有,非本次引入)**:①加载期没有时长可 seek,想"拖进度条"需另立设计(把拖动解释成取消加载/换线路);②详情页 `pageState is Loading` 的页面级黑覆盖层同样位于控制器之上,但那段窗口没有播放会话、仅在竖屏预览框内绘制,未纳入本次范围。
- **补(同日,用户报「唤出控件后立刻退出应用,多任务界面里控件和进度条消失了,只有干净画面」)**:这不是 bug,是两条既有机制的合谋 —— `DetailActivity.onPause → PlayContainer.hostPause`(暂停内核 + `setLifecyclePaused(true)`)→ dkplayer `pause()` 发 `STATE_PAUSED` → `ComposeVideoController.onPlayStateChanged` 的 PAUSED 分支 `hideBottom()` 收起菜单;`pauseOverlayVisible` 又显式排除 `lifecyclePaused`(注释写明"避免任务快照拍到已暂停假象"),所以任务快照只剩画面 + 原生字幕。**但顺这个操作查出一个本次改动新开的口子**:加载/解析期(dkplayer `pause()` 要求 `isInPlaybackState() && isPlaying()`,IDLE/PREPARING 下直接空操作)不产生 PAUSED 事件 ⇒ PAUSED 分支的 `hideBottom()` 不触发,而遮罩搬到控制条之下后控制条不再被它盖住 ⇒ 在**加载窗口内唤出控制条再退后台**,快照会留下控制条(改动前那层遮罩恰好盖住了它,属于"歪打正着")。修法 = `setLifecyclePaused(true)` 时直接 `hideBottom()`(与 PAUSED 分支同一语义,幂等)。覆盖范围 = 影视视频路径;纯音频会话(`hostPause` 对 `isConfirmedAudioOnly()` 让路)本就不发 PAUSED,属既有行为,未纳入。`:app:assembleDebug` BUILD SUCCESSFUL、`:app:testDebugUnitTest` 265 用例 / 0 失败。

## 修复:缓冲中旋转出现"闪烁 + 短暂白屏"(2026-09-24,用户报,画面渲染=Surface)

- **现象**:竖屏详情页缓冲期间立刻进横屏,有概率闪一下后播放区变浅色(截图里的浅色= #F3EDF7)。
- **定位(源码 + 项目既有记录)**:默认"画面渲染"= Surface(`PlayerHelper.java:45` 的 `KV.get(HawkConfig.PLAY_RENDER, 1)`,1=SurfaceView,0=Texture),渲染层是自绘 `player/render/SurfaceRenderView`(SurfaceView + `PixelFormat.RGBA_8888`)。旋转会销毁重建 Surface,而**缓冲期没有已解码帧可立即重绘**,该空窗里露出的是应用窗口底色/应用层底色 —— `MyVideoView.switchRenderToTexture()` 的 KDoc 早已记录同族现象("无帧时只剩窗口底色(多任务卡片变白)""回前台过渡动画透视桌面"),并给出规避手段 TextureView("无帧呈黑、快照与过渡全部正常(实测)")。可注意两点:浅色 `@color/window_background`(#F3EDF7)与 M3 浅色 `surfaceContainer`(详情页根底色)**同色**,单看截图分不出是哪一层;另外纯音频路径已由 `PlaybackController.java:1560-1566` 预判改 Texture,不涉及本条。
- **改法(2 处,均只在全屏生效、常显态不可见)**:①`DetailActivity.applyPlayWindowBackground()`:进全屏把窗口底色压黑(进入前 `window.decorView.background` 快照,退出原样还原,不硬编码颜色);②`DetailScreen` 根 `Column` 底色在 `fullBox` 时压黑(旋转中间帧"预览尺寸→全屏"二次重排时露出的就是这一层)。
- **不改什么**:不碰主题资源与 `@color/window_background`(改它会波及所有页面,包括音乐页)、不碰渲染层与 Surface↔Texture 切换。**音乐播放不受影响**:`MusicPlayerActivity` 是另一 Activity 自己的窗口;纯音频的 TextureView 热切(`ensureAudioOnlyRender`)、后台保持播放、媒体会话全未触碰;全屏+音乐的组合只会"空窗露黑",与 Texture 规避同向。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL、`:app:testDebugUnitTest` 265 用例 / 0 失败。**真机待确认**,并附判别法:若把"画面渲染"临时切成 Texture(0) 后白屏消失 ⇒ 说明浅色来自 Surface 打洞(露出应用窗口**背后**的桌面/启动器),则本次两处改动无效,根治只能走 TextureView 或旋转前冻结当前帧(直播页 `doScreenShot` 同款);若切 Texture 后**仍然**闪白 ⇒ 浅色来自应用自身层,本次改动即对症。
- **遗留(既有)**:旋转中间态还有第二个来源 —— `DetailViewModel.kt:140` 的 `rotating` 与 `DetailScreens.kt` 的 `fullBox = if (rotating) isLandscapeNow else full` 会让窗口已横但未切全屏时出现"预览尺寸黑条 + 下方详情"的中间帧。**不要为它盲改判据**,会回归 2026-09-24 记录的"大屏不锁方向"那条。
- **补二(同日,用户实测「不行没效果」→ 机制确认 + 换正确修法)**:上一版把窗口底色与详情页根底色在全屏态压黑,**真机无效**。这个结果本身就是证据:Surface 的"打洞"是窗口级透明区,**只被画在渲染视图*之上*的不透明视图减掉**;窗口底色、详情页根底色都在渲染视图*之下*,减不掉那个洞 ⇒ 洞里露出的是应用窗口**背后**的桌面(浅色启动器 ⇒ 均匀浅色,正好对上截图),而控制器浮层(转圈/网速,在渲染视图之上)照常可见 —— 与截图完全一致。上一版的 5 行(窗口底色快照/还原 + 根 Column 压黑)**已回退**,只留真正闭合洞的那一层。
- **正确修法(本次)**:新增过渡黑罩 `MyVideoView.showTransitionCover()/hideTransitionCover()` —— 追加到渲染视图之上(与 `frameCover` 同一层级做法,含 `mVideoController.bringToFront()`),`DetailActivity.applyFullscreen()` 在改方向前调用;揭开信号 = 引擎"画面已出"(`showVideoFrame()`/`hideVideoFrameCover()`,前者即 `STATE_PLAYING`,后者是纯音频分支)+ 1500ms 兜底超时(画面迟迟不出时不能把黑罩永远留着)。**与 frameCover 分开维护**:`isVideoFrameCleared()` 兼作引擎"画面被遮着"的判据(决定 tip 何时收起),复用会把那条语义带偏。进/出全屏两个方向都压罩。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL、`:app:testDebugUnitTest` 265 用例 / 0 失败。**真机待确认**;若仍闪白,则说明浅色不是打洞空窗而是**系统旋转动画**(应用窗口被缩放/旋转、四周露出桌面),那种情形应用侧无解,只剩"接受"或用 TextureView 观察是否改变表现。**已知未覆盖**:设备自身旋转(非全屏按钮)时不会调用 `applyFullscreen`,没有压罩时机。
- **补三(同日,用户决定「回退回退,别修这个白色闪烁的问题了」)**:过渡黑罩方案(`MyVideoView.showTransitionCover()/hideTransitionCover()` + `PlayContainer` 透传 + `DetailActivity.applyFullscreen` 调用)**已全部回退**,`MyVideoView.java`/`DetailActivity.kt` 回到 HEAD。活规范里那条"关闭打洞空窗"的不变量随之删除,改为 §7 未决项(记明机制、两次实测无效的修法、以及若要再修优先考虑 TextureView 及其代价)。本次保留下来的改动只有加载期唤不出控制条那条(遮罩层迁移 + 配套)。

## 新增:导航栏隐藏直播(2026-09-24,用户要求「隐藏底部导航栏的直播控件,偏好设置页禁用动画下方加开关」)

- **入口**:偏好设置页组1 末尾(「禁用导航动画」下方,用户指定卡位)= `SettingsSwitchRow`,标题「导航栏隐藏直播」,副标题「开启后导航栏不再显示直播入口」;值存 KV `HawkConfig.NAV_LIVE_HIDDEN`(`"nav_live_hidden"`,默认关,登记进 `KVKeySpec` 布尔区)。原「禁用导航动画」卡位由 LAST 改 MIDDLE,新卡 LAST(组1 由 4 张卡变 5 张卡)。
- **生效点(三处,必须同改)**:①surface 模式底部 `ShortNavigationBar` 的动作槽插入;②玻璃 `FloatingNavBar` 的 `actionItem` 传 `null`(组件本就支持 null:内部 `actionSlot`/`slotCount` 都会跟着算);③surface 模式 `NavigationRail` 的动作槽插入。状态与「禁用导航动画」同一套读法:`MainScreen` 内 `remember { KV.get(...) }` + `ON_RESUME` 重读(从偏好设置页返回即生效)。
- **范围决定**:开关文案是"导航栏",故**两条轴一起隐藏**(底部横条 + 宽屏竖条);只隐藏一种会留下"开关开了但某个形态还在"的半开状态。用户口语说的是"底部导航栏",若日后要按轴区分,只需给竖条那处单独判据。
- **不变量(改动前自查)**:直播是**动作槽不是页面**(进独立 Activity、不占 pager 页),隐藏它不改页面数量与顺序;实现走 `actionItem = null`,即 `NavMetrics.slotIndexOfTab/tabIndexOfSlot` 的 **null 分支**,该分支已有单测锁为恒等映射(`NavMetricsTest` 两例),所以页面索引、选中态、玻璃胶囊拖动落点都不受影响。⚠️ 别改成"把直播从 `tabs` 里删掉"那把动作槽当页面的写法(§4.11 的反面教材)。
- **i18n(四语)**:`settings_nav_live_hidden` / `_subtitle` 落 `values`(简体)、`values-en`、`values-b+zh+Hant`(繁体基础层,用「導覽列」),香港差异层 `values-zh-rHK` 覆盖为「導航欄」(与既有 `theme_nav_bar` 的港台用词差异一致);`localeFilters` 与打包不受影响。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL、`:app:testDebugUnitTest` 265 用例 / 0 失败。**真机待走查**:①设置页开关位置/文案(四语);②开启后底部横条与宽屏竖条都不再显示直播;③关闭后恢复、可点进直播页;④切换底栏模式(玻璃/标准)与横竖屏后仍不显示;⑤从偏好设置页返回即时生效。
- **补(同日,用户报「开启隐藏后再关闭就无法拖动指示器切换 tab,重进应用才好」—— 我上一轮审查漏掉的回归)**:根因 = **指针手势的键用了常数**。`DampedDragAnimation.modifier` 是 `Modifier.pointerInput(Unit)`、`InteractiveHighlight.gestureModifier` 是 `pointerInput(animationScope)`,两者都把"当前实例"闭包固化在**首次建立**的指针节点里;而这两个实例的 `remember` 键含 `slotCount`/`slotStridePx` ⇒ 本特性改变槽位数(5↔4)会**重建实例**。Compose 的 `SuspendPointerInputElement.update` 只在键变化或 lambda 的**类**变化时才 `resetPointerInputHandler`(同一调用点的 lambda 类相同,刻意不因"lambda 是新实例"而重启)⇒ 手势继续驱动旧实例:旧实例的 `value` 变了但 UI 读的是新实例 ⇒ 拖动无响应;点 tab 仍由新参数驱动 ⇒ 只有拖动坏。重进 App = 新组合 = 新节点 ⇒ 恢复。**修法** = 两个键都改成实例身份 `pointerInput(this)`(换实例即重启,手势永远驱动当前实例)。**这是既有隐患被本特性点着**:此前 slotCount/步长在运行时基本恒定(4 页 + 常驻动作槽),只有分屏/密度/方向变化才可能触发,而主 Activity 旋转会重建所以没暴露。
- **⚠️ 审查为什么漏了这条(诚实记录)**:上一轮我只验证了"实例重建后胶囊会重新对位"(确实会),就把"实例会被重建"当成安全,没有按 SKILL.md 的"触碰唯一/第 0 项这类隐式约定时先列出**全部消费方**"去数**实例身份的消费方** —— 手势节点正是这样一个消费方(首次组合固化闭包)。教训已写进 `avbox-mobile-ui-spec.md` §4.11:改"以前运行时恒定、现在可变"的东西前,先排查 `pointerInput(key)`/`rememberDraggableState`/`Modifier` 属性这类首次组合固化闭包的消费点。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL、`:app:testDebugUnitTest` 265 用例 / 0 失败。真机走查请补一条:开启→关闭后**拖动指示器**仍能切 tab(不重启 App)。

## 修复:小米/澎湃上本地源"目录授权"死循环(2026-09-24,用户转来反馈)

- **现象(反馈者)**:小米 14 / 澎湃 3,添加本地包每次弹「本地源要直引原目录,得给一次目录授权:请选择该配置所在的文件夹」,点授权后失败;手机设置里已开「所有文件访问」,重装无效;其 vivo 设备无此问题(1.0.x 各版本都能复现)。
- **根因(两段合谋,均为源码定位)**:
  1. **直引判据用错了对象** —— `LocalConfigHelper.importLocalConfig` 只在 `PermissionHelper.isStorageGranted()`(= `Environment.isExternalStorageManager()`)为真时才直引,否则把用户推进目录授权;而该查询与"文件真读得到"并不是一回事(小米对未上架应用不真正落地 `MANAGE_EXTERNAL_STORAGE`,开关看着开着、查询仍为 false),于是读得到的文件也被判成直引不了。对照 `示例文件/TV-fongmi`:`PermissionUtil.hasStoragePermission()` 在 Android 13+ 无条件返回 true(不问权限),`FileChooser.resolveFileUri` 的判据就是 `isFile() && canRead()`,读不到才复制进自己的 cache —— 它没有目录授权这条路,所以从不出这个问题(代价是读不到时静默复制)。
  2. **兜底路线在用户的落点上不可能成立** —— 目录授权走 `ACTION_OPEN_DOCUMENT_TREE`,而 Android 11+ 对 targetSdk≥30(本项目 37)禁止授权存储根 / Download 根 / `Android/data`;`LocalSourceTree.treePath` 又只认 `primary:<相对路径>` / `XXXX-XXXX:<相对路径>`,卷根本身、`raw:`、绝对路径 docId 一律映射成 null ⇒ 即便 ROM 的选择器让选也报「没拿到该配置所在文件夹的授权」。此外 `ApiConfig.isLocalSourceUnreadable` 只看权限查询,授权成功后加载失败仍会报「本地源文件读不到 / 请开启『所有文件访问』」——正是反馈者"我明明开了"的那句话。
- **改法(4 文件)**:
  1. `importLocalConfig`:直引判据改为"应用此刻真读得到原文件"(`readablePath` = `isFile + canRead`);权限查询降级为埋点字段(`echo-local-src path granted=… src=…`,仍用于区分"查询为 false 却读得到"与"真读不到")。
  2. `ConfigManageActivity`:去掉选文件前的权限预检(读得到的设备不再白跳设置页);新增 `settleUnreachableSource(uri)` —— 读不到时**先争一次「所有文件访问」**,拿到就用同一 uri 重试导入(多半直接直引成功),拿不到(用户拒绝 / ROM 不给)才用上一次已挂起的结果接着要目录授权。存储根 / Download 根这类落点只有靠权限才救得回来。
  3. `LocalSourceTree`:新增 `serves()`(会话级授权也算的"此刻读得到吗",与 `open()` 同口径,供 `ApiConfig` 判读不到时排除授权兜底);`treePath` 改走新纯函数 `treeDocPath()`,补上卷根本身(`primary:` / `XXXX-XXXX:`)、`raw:<绝对路径>`、直接拿绝对路径当 docId 的 provider。
  4. `ApiConfig`:`isLocalSourceUnreadable` 改为「权限查询为 false」**且**「`File.canRead` 为 false」,有授权兜底(`serves`)时不算;`isLocalSourceMissing` 在有授权兜底时不判存在性(`File.exists` 结论不可信,会把"读不到"误报成"已删除")。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL;`:app:testDebugUnitTest` **267 用例 / 0 失败**(新增 2 例锁 `treeDocPath` 的 docId 口径)。
- **真机待确认 + 判别法**:看埋点 `echo-local-src path granted=… src=…`(logcat `-s TVBox-runtime`)—— `granted=false` 且 `src=/storage/…` ⇒ 属"查询为 false 却读得到",本次改动即对症;`src=null` ⇒ 真读不到,先看「所有文件访问」能否开启,仍不行则是存储根 / Download 根落点,需要下一轮补"请把配置放进自建子目录(如 `手机存储/AVBox/`)"的引导。
- **未做(留给下一轮)**:①受限目录(存储根 / Download 根 / `Android/data`)的专门提示文案(需补齐四语);②目录型本地源(`clan://localhost/TVBox/`)的 `RemoteServer.fileList` 仍只有 File API、无授权兜底 —— 这类源在无权限 ROM 上即使授权了也列不出目录。
- **补(同日,对照上游 `示例文件` 之外的 LocalSend PR #3365 做的同类审计)**:那条 PR 修的是"厂商 DocumentsProvider 不守 SAF 契约"引起的选择器崩溃/误报(受影响机型表:vivo 已确认,小米/OPPO/荣耀大概率)。逐条比对结论 —— 全项目 provider 游标只有 4 处,3 处本来就是对的(`PlayContainer.queryDisplayName` 字幕、`LocalConfigHelper.getDataColumn`/`getDisplayName` 都走 `getColumnIndex` + `>= 0` 守卫),`takePersistableUriPermission` 早就 `try/catch (Throwable)` 包住(比 PR 的 SecurityException+IllegalArgumentException 更宽,且我们是常量传 READ 标志,不存在 PR 那个 `takeFlags == 0` 分支),选择器取消本就不弹提示。**实际命中 1 处 + 同族变形 1 处,本次补掉**:
  1. `LocalSourceTree.findChildId` 用 `cursor.getString(0)/getString(1)` 硬编码列下标(投影要的是「docId + 显示名」两列)。厂商 provider 按自己列序返回时,名字比对永远不相等 ⇒ `findDocument` 返回 null ⇒ 本地服务 404 ⇒ 源加载失败 / `./x.jar` 404,**且外层 `catch (Throwable)` 把它吞掉、用户看不到任何提示**(与 LocalSend 的崩溃形态不同,我们更隐蔽);这条路径正是上一轮扶正为"无权限 ROM 上唯一救得回来"的目录授权兜底,受影响机型与 PR 表高度重合。改为 `getColumnIndex` 现查 + 缺列直接放弃,不再靠异常兜底。
  2. `LocalConfigHelper.getDisplayName` 的兜底是固定常量 `"local_config.json"`,而名字还决定"选中的是不是 py 爬虫"(`importLocalConfig` 用后缀判),provider 答不上 `DISPLAY_NAME` 时会把 `.py` 当 json 配置导入。改为退到 `Uri.lastPathSegment`(对 vivo 那种 `content://pkg.fileprovider/extfiles/影视/config.json` 形态能取回真名),**故意不退到整串 Uri** —— 那串要当文件名用,`?` 等字符在 FAT/exFAT 上写不进去。
- **验证(补)**:`:app:assembleDebug` BUILD SUCCESSFUL、`:app:testDebugUnitTest` 267 用例 / 0 失败。**这两处无法用 JVM 单测锁**(`findChildId` 依赖 `ContentResolver`、`getDisplayName` 依赖 `Uri`/`Cursor` 的 Android 实现,单测里是 stub),只能构建验证 + 真机走查:重点是在 vivo/小米这类 ROM 上"已经授权成功但源仍 404"的场景是否消失。PR 的 size 兜底(`AssetFileDescriptor.length`)对我们不适用(导入流式读 + 限长,不消费 size)。
- **补二(同日,用户追问「你确定这样能让小米设备能用吗」之后补的出路)**:查证结论是**不能保证** —— `Environment.isExternalStorageManager()` 查的是 AppOps `MANAGE_EXTERNAL_STORAGE` 的 mode,而澎湃的设置页开关与 appop 可以不同步(小米官方文档只规定"申请标准/需商店审核",未说明未上架应用开关是否真生效);若 appop 实为 default/ignore,scoped storage 照常生效、按路径读仍被拦(埋点 `src=null`),「可读性判据」救不了它。此时唯一不依赖权限的引用路是 **SAF 目录授权**,而它的前提是配置不在系统永不允许授权的目录里。本次补:纯函数 `isUngrantableDir`(存储根 / `Download` 根 / `Android/data|obb` / 副卷卷根;子目录不受限)+ 四语文案 `toast_local_tree_forbidden`,只在"授权失败且配置父目录命中该判定"时替换原来的「没拿到授权(可再点一次重试)」—— 不改流程、不阻断任何可能成功的路径(权限与选择器顺序原样)。`isUngrantableDir` 有 2 例单测锁口径。`:app:assembleDebug` BUILD SUCCESSFUL、`:app:testDebugUnitTest` **269 用例 / 0 失败**。
- **待真机定论(小米 14 / 澎湃 3)**:①`adb shell appops get com.github.avbox.osc MANAGE_EXTERNAL_STORAGE` → `allow` 则本次「可读性判据」即对症;`default`/`ignore` 则确认"权限拿不到";②埋点 `echo-local-src path granted=… src=…` → `src=/storage/…` 说明按路径读其实通,`src=null` 说明真被拦。两者组合决定下一步:若"真被拦 + appop 给不了",则只能靠"挪进自建子目录 + 目录授权"这条不依赖权限的路(已在本轮提示里给出),或回到用户不接受的复制路线。

## 调整:加载/解析/取流期隐藏中央三控件(2026-09-24,用户要求「全部都隐藏,解析和取流也隐藏中央三控件」)

- **背景**:上一轮(同日)把遮罩搬到控制条之下后,加载期单击已能唤出顶栏/底栏;用户随即确认中央三键在加载期的口径 —— 一律不出现,包含解析/取流期(不是只隐藏 `PREPARING`/`BUFFERING`)。
- **改动前的实际行为(两段相反,属既有不一致)**:①解析/取流期(黑遮罩在屏、内核状态停在 `IDLE`)中央三键**会**显示,且因 `PlayerCenterControls` 在 `PlayerOverlay` 里声明在 `PlayerTipLayer` 之后而**压在黑遮罩上**;中间那颗播放/暂停被 `onPlayPauseClicked` 的 `tipVisible && !isInPlaybackState()` 短路成死键,只有上/下一集可点。②内核准备期(`PREPARING`,转圈)与缓冲期(`BUFFERING`)三键隐藏 —— 旧守卫 `!controlsVisible || locked || loadingVisible` 里 `loadingVisible` 只覆盖这两个状态,"加载"与"缓冲"在代码里是同一个开关。
- **改法(2 文件 + 新单测)**:①`PlayerUiState` 增派生属性 `centerControlsVisible`(= `controlsVisible && !loadingVisible && !tipVisible && !locked`),与 `pauseOverlayVisible`/`loadingVisible`/`netSpeedCenterVisible` 同处集中管理;②`PlayerOverlay.PlayerCenterControls` 改为只读该属性(原内联守卫删除),KDoc 同步(不再写"loading 时让位给转圈",改为"加载/解析期一律隐藏")。
- **为什么不再按 `playState` 判定"是否在加载"**:解析/取流期内核状态是 `IDLE`,在屏标志是 `tipVisible`;只判 `loadingVisible` 必然漏掉这一段 —— 这正是改动前的不一致来源。判据收进 `PlayerUiState` 后仍是一处,新增状态(如以后再来一种遮罩)不会散落在 UI 里漏改。
- **功能代价(已确认可接受)**:加载期失去"跳过等待换下一集"的中央入口,只剩绕道入口 —— 全屏走底栏菜单行的「选集」(`episodeBtnVisible` 判可见性),竖屏预览态那行菜单本就不显示,由详情页的选集列表承接。播放/暂停在 `PREPARING` 下 `togglePlay` 本为空操作,隐藏它没有实际损失。
- **未动**:`onPlayPauseClicked` 的 `tipVisible && !isInPlaybackState()` 短路**保留** —— 预览态(竖屏详情页)底栏进度行左侧那颗播放/暂停钮在遮罩期仍可达,它是这条守卫现在的唯一受益者。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL;`:app:testDebugUnitTest` **276 用例 / 0 失败**(新增 `PlayerUiStateVisibilityTest` 7 例:解析遮罩 / 错误遮罩 / PREPARING / BUFFERING 均隐藏,播放中与预览态仍显示,锁定与未唤出隐藏,遮罩清空后恢复显示 —— 最后一例防"三键永久消失")。**真机待走查**:①解析与取流期唤出控件只见顶栏/底栏、中央无三键;②起播后单击唤出三键正常(含预览态);③错误遮罩下三键不出现、底栏「刷新」可用。

## 修复:详情未就绪时进全屏只剩纯黑(2026-09-25,用户报"图一状态点进全屏变成图二")

- **现象(用户截图)**:竖屏详情页预览态加载中(黑框 + 页面级转圈)点右下角全屏 → 全屏一片黑,只剩播放器控件层(中央三键、`0 X 0`、进度 `00:00/00:00`、`0B/s`)和一颗灰色药丸「本接口免费分享！切勿上当！」。
- **定位(源码取证 + 交叉核对)**:①那颗药丸是 **Android Toast,不是播放器浮层** —— 该文案全仓 grep 不到,来自运行期输入:详情返回带 `msg` 时 `DetailViewModel`(`absXml.msg` 分支)`toastEvent.value = absXml.msg` 并 `enterEmpty(msg)`,即"这条源只回了公告、没回剧集" ⇒ **本次根本没起播**;`0 X 0`(顶栏取 `wrapper.videoSize`)+ `00:00/00:00` + `0B/s` 全是空态渲染,不是全屏弄坏的。反证:遮罩 `PlayerTipLayer` 会画自己的居中转圈 + 文案,且(按同日改动)会连带隐藏中央三键,而图二三键在 ⇒ 它不是遮罩。②**真正的缺陷 = 全屏里没有任何加载/失败反馈**:页面内容整段 `if (!fullBox)`、加载覆盖层又叠一个 `!fullBox`,而播放器侧此刻没有会话(遮罩由取流链路的 `setTip` 驱动,起点在拿到详情之后)⇒ 纯黑且分不清"在加载"还是"坏了"。
- **改法(按用户选定方案"没内容就别让进,点了就 Toast 说明原因",3 文件 + 1 单测)**:①`DetailScreens` 右下角全屏钮的渲染条件加 `pageState is Ready`;②`DetailViewModel.setFullScreen(true)` 拒进并经 `toastEvent` 说明原因 —— 这一处才是唯一写 `fullScreen` 的入口(画质点击 `onQualityClick` 也走它),只藏图标拦不住别的入口;③判据抽成纯函数 `DetailFullScreenGate.refusalReason()`(未就绪返回原因 / 就绪返回 null;源返回的 `msg` 优先透传,空结果用 `detail_empty_source`、加载中用 `detail_content_not_ready` —— 两条都是既有文案,**零新增字符串、四语无需补**);④`DetailFullScreenGateTest` 4 例锁真值表(加载中 / 空结果带 msg / 空结果无可用 msg(含空白串)/ 就绪放行且不取文案)。
- **为什么判据要抽成纯函数(踩过)**:第一版把单测写在 `DetailViewModel` 上,**构造就挂** —— `init` 里 `EventBus.register` + `sourceViewModel.detailResult.observeForever` 触发 `LiveData.assertMainThread` → `Looper.getMainLooper()` 在纯 JVM 单测里是 null(`unitTests.isReturnDefaultValues` 只兜住"方法返回默认值",兜不住"拿 null 再解引用")。项目既有惯例是这类规则抽 `internal object XxxRules`(`LiveSettingsRules`)后纯 JVM 测,本次照办;判据留 3 个分支的 `when`,文案以 `() -> String` 惰性传入,放行路径不产生取资源开销。
- **"说明原因"的文案分流(审查时改的)**:初版对 `Empty` 也复用"内容还没加载好",但空结果(`enterEmpty()` 无 msg 的 3 个调用点)是"不会再有内容",让用户干等是误导 ⇒ 改为空结果用 `detail_empty_source`("暂无片源,可尝试换源或搜索")。顺带确认 `Empty(msg)` 确实可达(换源 `switchSource` → `loadDetail` → 新源返回 msg/空结果,此时旧会话已在 `stopPlaybackForSwitch` 停掉,拦下是对的)。
- **为什么不加"超时退出全屏"兜底**:全屏态下 `pageState` 不会退回 `Loading` —— 换源 `switchSource` 走 `loadDetail` 只在非全屏可达,且 `applyFullscreen` 在全屏时会 `setAutoSwitchLineEnabled(false)` 关掉自动换线。
- **未确认的一点(留给用户)**:图二控件为何是唤出的。代码里没有"进全屏自动唤出控件"的路径(`applyFullscreen` 只做方向/系统栏/自动换线开关,`showBottom()` 全仓仅 `toggleControls()` 一个调用方)。判别法:进全屏后手指不动,看控件是否自己出现 —— 若自己出现,则是那次点击的触摸被播放器手势层也吃到了一次,属另一条线,需再查 Compose 互操作的命中顺序。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL;`:app:testDebugUnitTest` **280 用例 / 0 失败**(新增 `DetailFullScreenGateTest` 4 例)。**真机待走查**:①数据未就绪时预览框右下角没有全屏钮;②就绪后按钮出现且进全屏正常;③VM 守门是兜底路径(图标已隐藏,只可能在"图标渲染那一帧状态恰好翻转"时命中),故提示文案的真机验证优先级低。

## 修复:换源把用户设置的 DoH 重置成"关闭"(2026-09-25,用户报"设置成应用自带的腾讯 DoH,切换源后会被重置")

- **现象**:设置页把 DOH 选成内置「腾讯」,去配置管理页换源(或重载源)后,DOH 行变回「关闭」。
- **根因(源码定位)**:`DOH_URL` 存的是**下标**(0 = 关闭,n = 合并列表第 n 项),而 `ApiConfig.parseJson` 那段用**整串 JSON 比较**判断"接口的 doh 变了":一变就 `KV.put(DOH_URL, 0)` 并覆盖 `DOH_JSON`。三个后果:①变动判据是整串字符串,接口只调顺序/加个字段也会命中;②重置**无条件**,不区分用户选的是接口项还是内置项 —— 而内置三项恒在合并列表最前(`getDohConfigArray`),下标含义根本不会变,选内置项的用户纯属误伤(用户报的正是这一档);③"无 doh 字段"分支只清 `DOH_JSON` 不清下标,行为依赖重建列表时的越界钳制 = 同一件事由两处代码决定。
- **改法(2 文件 + 1 单测)**:①`ApiConfig.parseJson` 只负责取出新配置的 doh 串(缺字段即空串)并调 `OkGoHelper.applyDohConfig(dohJson)`,自己不再写 `DOH_URL`/`DOH_JSON`;②`applyDohConfig` 在一个方法里自上而下完成"按 url 记忆":先记下当前选择对应的 url(`getDohUrl(KV.get(DOH_URL,0))`)、再写 `DOH_JSON`、然后重建合并列表并按 url 找新下标(`indexOfDohUrl`,key 规则与 `appendDohItems` 一致:url 缺失退 name),找到写回 `下标+1`,找不到才回"关闭";取不到原选择(本来就关着 / 该条没 url)时保留原来的越界钳制(判据等价换算为 `DOH_URL > merged.size()`)。**"读旧 url 必须在写 DOH_JSON 之前"这条隐含顺序做成结构性的**(同一个方法里自上而下),不再依赖注释提醒;顺带把原先"有/无 doh 字段"两分支各自写 KV 的不对称也收掉了。`applyDohConfig` 全仓仅 `ApiConfig` 一处调用。
- **行为变化**:选内置项 + 换源 = **保留**(修的就是这条);选接口项且该 url 仍在新列表 = 保留;该 url 消失 = 关闭(与旧行为一致);本来就关着 = 不动。顺带:控制层 `ControlManager` 读 `DOH_URL > 0` 决定 IJK dot port 的那处,现在也会跟着"保住选择"而保持一致。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL;`:app:testDebugUnitTest` **285 用例 / 0 失败**(新增 `OkGoHelperDohTest` 5 例:内置项在列表增删前后都找得到、接口项改序后按 url 重定位(下标 4→3)、选中项消失返回 -1、空/null url 返回 -1、无 url 项按 name 兜底)。**真机待走查**:设置页选腾讯 → 配置管理页换源 → 回设置页应仍是「腾讯」,且实际生效的就是 `doh.pub`。
- **取舍(未做)**:只有 name 没有 url 的接口 doh 项仍靠越界钳制兜底 —— 这类项在 `initDnsOverHttps` 里本来也取不到 url(等于选不了),不值得为它加 name 记忆;若将来要支持,把传入的 url 换成"key(`url ?: name`)"即可,`indexOfDohUrl` 已按这套 key 规则实现。

## 退后台任务快照改为保留控制条与进度条(2026-09-25,用户以 YouTube 卡片为据推翻 9-13 口径)

- **口径翻转**:9-13 那次为让快照"只剩画面",在 `setLifecyclePaused(true)` 里主动 `hideBottom()` 并抑制暂停浮层。用户以 YouTube 的后台卡片(画面 + 顶栏片名 + 中央 ⏸ + 时间/进度条 + 底栏操作键全在)指出:**快照就该是离开前的样子**;而且"生命周期暂停期间画 ⏸"才是准确的 —— `hostResume()` 必然续播,此时拍成 ▶ 反而与真实状态不符。据此撤回那层收起(撤回的是"收起控制条",不是"抑制暂停浮层")。
- **改动(5 文件)**:①`ComposeVideoController.setLifecyclePaused(true)` 不再 `hideBottom()`,改 `removeCallbacks(idleHideRunnable)` 冻结 10s 自动收起 —— 否则计时在后台把控件收掉,回前台与离开时不一致;传 false 时 `keepControlsAlive()` 按 `controlsVisible` 重新计时。②`onPlayStateChanged` 的 PAUSED 分支在 `lifecyclePaused` 时不清顶栏(`topLeftVisible`/`netSpeedTopRightVisible`)、不收菜单;手动暂停仍走原逻辑(收菜单 + 画暂停浮层)。③"按播放中渲染"的判定原先在中央控制组与预览态按钮各写一份,收口为 `PlayerUiState.playbackActive`(含 PLAYING/BUFFERING/BUFFERED + `lifecyclePaused`),两处图标改读它,连带删掉两个文件里已无用的 `VideoView` import。④**顺带修掉一个判据漏洞**:`PlayContainer.hostPause` 原先无条件 `setLifecyclePaused(true)`,于是"用户手动暂停后离开"也被当成生命周期暂停 ⇒ 暂停浮层被误抑制、中央键误画 ⏸,而这条路径回前台并不会续播。改为传 `mVideoView.isPlaying()` 的结果 —— 该标记的语义就此钉成"回前台会续播"(与 `hostResume` 的续播判据同一来源)。
- **保留不变**:暂停浮层仍被 `pauseOverlayVisible && !lifecyclePaused` 抑制 —— 它是"标题 + 中央播放图标"的独立浮层,语义是"已暂停,点击播放",而回前台会续播,拍进快照才是真假象。纯音频会话不走 `PlayContainer.hostPause`(让路给 `isConfirmedAudioOnly`),音乐页有自己的 host 实现,未受影响。
- **预期内的连带变化(不再是缺陷)**:加载/解析期退后台(IDLE/PREPARING 下 `pause()` 是空操作、不产生 PAUSED 事件)现在快照会带控制条 + 加载转圈 —— 用户离开时控制条确实开着,这正是"保存退出前状态"的结果。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL;`:app:testDebugUnitTest` **285 用例 / 0 失败**。**真机待走查**:①全屏播放唤出控件后立刻回桌面 → 卡片含顶栏/进度条/中央三键(中间 ⏸),回前台自动续播且控件仍在、10s 后自动收起;②手动暂停后回桌面 → 卡片中央 ▶ + 暂停浮层(未续播,回前台仍暂停);③加载中转圈时退后台 → 卡片带控制条与转圈;④纯音频(音乐页)退后台行为无变化。

## 本地源导入不再被临时 SAF 授权骗过(2026-09-25,真机取证)

- **现象**:添加本地源(「从本地选择」选 json)不再申请「所有文件访问」;导入后当场能用,重启应用后源"还在"但内容永不更新,手动刷新才报「本地源文件读不到」。
- **根因(源码定位)**:`LocalConfigHelper.importLocalConfig` 的直引判据是 `readablePath()`(即 `File.canRead()`),而 `OpenDocument` 给**本次选中文档**的临时 SAF 授权会让它当场为真 ⇒ 走直引分支、`directPath` 保持 null ⇒ `handleLocalConfigResult` 返回 false ⇒ 唯一会申请权限的 `settleUnreachableSource` 从不执行。取证:导入埋点 `granted=false src=/storage/…` 紧跟 `direct=false`(`src=` 就是那个假可读);同一路径 `GET /file/…` 重启前 200、`am force-stop` 后 500,同刻 `Android/data/…` 仍 200。
- **改法(2 文件)**:①`LocalConfigHelper` 直引判据改为 `granted || LocalSourceTree.covers()`(持久可读性),不成立就把路径交回调用方争授权;②`ApiConfig` 两个 `useCache` 快照分支加 `isRemoteSource` 守卫(本地/局域网源不吃快照)。
- **行为变化**:未开「所有文件访问」的设备导本地源会先跳一次设置页(拿到即直引、不再多要目录授权;拿不到退目录授权);已持久可读(权限在手 / 目录授权已覆盖)的设备行为不变、不会多弹。真机走查:改后埋点 `direct=true` → 授权后 `granted=true` + `direct=false`,重启后本地服务对该文件仍返 200(改前 500)。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL;`:app:testDebugUnitTest` **285 用例 / 0 失败**;真机(vivo V2425A / Android 16)按上述流程走通;约束已登记 spec §6.15。
- **遗留(未改)**:`toast_local_direct_grant_hint` 文案只说"请选择该配置所在的文件夹",而新流程多数人先看到「所有文件访问」设置页,措辞与紧随其后的界面不一致(次要不一致,需动四语文案)。
