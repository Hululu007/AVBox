# AVBox 功能迭代实施记录（历史归档）

> 本文件是 `skill/history/` 归档的一部分：2026-09-09 起各项功能改造的**实施过程、历史补丁与排查记录**。
> 活规范见 `../avbox-mobile-ui-spec.md`，通用规则见 `../SKILL.md`。
> 用途：**仅在需要追溯「当初怎么做的、为什么这么做、踩过什么坑」时按需检索**，不要通读。
> 说明：文内 `§x` 引用沿用归档前的旧编号（§4.x 未变；旧 §5/§6/§7/§8 已重组，见 SKILL.md 文档地图）。

---

## 归档时的项目状态（2026-09-12）

- **状态**:Step 7 已完成(assembleDebug 通过,真机回归待装包验证);快搜功能已删除(2026-09-09,见下)
- **最近更新**:2026-09-11(★ **主题设置页**(照搬 `示例文件/android`:取色来源/深浅模式/预设色卡/HSV 取色器/配色风格,新增 materialkolor 依赖与 `AppThemeState` 全局状态,设置 tab 入口,见下方「主题设置页」);**顶部应用栏无边框化**(4 tab + 二级页;内容延伸至状态栏 + 随滚动滚走 + 顶部渐变遮罩,见 §6 与下方「顶部应用栏无边框化」;首页/栏目页**卡片点击分发**+ 网盘目录下钻 + 源级「搜索/详情」策略,见 §4.1「卡片点击分发」与本页「卡片点击分发 + 网盘目录下钻」;详情页 UI 补丁:⑦ 线路/清晰度卡片化、⑧ 状态栏图标外观断言,见 §8 Step 4 补丁;加载指示器全局改用 `ContainedLoadingIndicator` 并定稿 64dp(详情页/直播页例外 48dp),见 §6;搜索结果页 = 波浪线进度条 + 结果源筛选 chips,见 §4.6;**配置管理页**(2026-09-11 二轮:设置 tab 新入口 = 源添加/管理唯一入口,订阅源开关切换 + 长按删除,见下方「配置管理页」;隧道模式 + AAC 优先(见下方小节);2026-09-12:首页**下拉刷新**(48dp 圆形指示器 + 松手整页重载,见下方「首页下拉刷新」);**点播 / 直播配置拆分 + 配置管理页分段**(2026-09-12 晚,见下方同名小节))
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
