---
name: 状态管理整体统一单向数据流（UDF）重构计划
status: **片 0–5 + §8 第三组 + §8.2 第二组（含残留收口）完成（2026-10-24）**——ApiConfig 不可变投影、AppBootstrap/SearchSourcesState/BootGeneration 归位 api 包、PlaybackPorts 接口化、eventbus 依赖删除（真机冷启动待补验）；第二组：#10 系统效果通道、#8 直播门面清零、#7 写权限（VM 60 条流私有化 + PlayerUiState 68/68 字段结构只读 + MusicPlayerState 17/17）、#9 音乐页状态入 VM 且页面零直写；#14 已退役、O3/O7 关闭、O5 收口（r7 升阻断）。gate：**r1–r7 全 0（2026-10-24 #5 指令面收敛后 r7 归零）**；构建+1426 单测全绿。**§8.1 引擎会话层 6 项已于 2026-10-24 全部收口（#1 AttemptState、#2 playerCfg、#3 AudioOnly、#4 会话代际化、#5 指令面收敛、#6 VodInfo bean 不可变化——宪法 C1–C5 全部结构化达成）**。改动停留工作区未 commit，**等用户提交**。**实施纪律（2026-10-24 用户指令，全计划有效）：没有用户允许不得 commit，不可污染 git 历史**
source: 2026-10-24 用户指令：「目标是把状态管理整体统一成单向数据流，以现代化和可维护性为目的，而不是保持现状。暂时不动代码，等我审批」
---

# 结论摘要

- 目标形态 = **全仓状态管理统一为单向数据流**（不是对现状的最小修补）：状态自上而下（唯一所有者 → UI），事件自下而上（UI → 所有者的事件方法），跨组件传播只走显式状态流（StateFlow）与一次性命令流（Channel/SharedFlow effect）。完成判定见 §3.1 宪法 C1–C5。
- 现状 = **两套风格并存的过渡态**：Live/Search/Home/Following 一侧已基本符合 UDF（§2.10 范本表，重构应对齐它们而非外部范式）；Detail 页、音乐页、直播页 Activity 侧、播放引擎会话层、全局单例一侧仍是旧式「可变共享 bean + 手工同步 + 隐式总线」。
- 最大病灶：**VodInfo 一图多主**（§2.1）——「正在播哪条线路、哪一集」的真身是可变 Java 风格 bean 的字段，同一实例被 5 处持有、6 类写者、直改 + EventBus 双路径传播。`bumpRevision()` 手工失效计数、`@Suppress("UNUSED_EXPRESSION")` 骗重组、`engineOwnsDetailContent` 三重容错比对、各类 stale-token 校验，全部是为多副本不同步打的补丁（§2.9 补丁文化登记）。
- 分 **6 片**实施（§4）：片 0 防回归门 → 片 1 Compose/VM 层双写消除 → 片 2 搜索与直播页状态收口 → 片 3 VodInfo 所有权收敛（核心片）→ 片 4 引擎会话面收敛 → 片 5 全局清理与遗留退役。片 1/2 低风险（构建+单测+人眼走查闭环）；片 3/4 受 `avbox-playback-service-spec.md` 管制，**必须真机走查**；片 5 是清理退役。
- 原则：**重构 = 语义等价，不借机改行为**（两处显式登记的例外见 §5 R4）；不引入新框架（不做 MVI 库）；契约层 `com.github.catvod.**` 全程不触碰；承重约定（`dispatchPlayState` 顺序、`KernelReusePolicy` 判定点、ISO 包装层 C1–C3）不顺手破坏（§5.1）；**未经用户允许不得 commit、不得污染 git 历史（2026-10-24 用户指令）——改动停留在工作区，由用户审阅后自行提交**。
- 已知用户可见缺陷顺带修复登记（实施时须在改动说明中显式标注行为变化）：搜索源设置面板切源后陈旧（§2.5）、音乐页返回后选中集可能越界（§2.2）。

# 1. 与既有文档 / 登记项的关系

| 既有文档 / 登记项 | 关系 |
| --- | --- |
| `skill/avbox-playback-service-spec.md` | 片 3/4 的管制文档。§2.3「指令面 = PlaybackHostApi → PlaybackController 单向」现状被 PlayContainer 的 scheduler 直写网格违反（§2.4）——片 4 完成后该口径由「约定」变「结构保证」，spec 相应改写。§2.3「dispatchPlayState 先 controller 后 flow 的顺序是承重约定」默认不动（§6 O3）。§4 真机走查清单 1–20 是片 3/4 的验收基线。§2.3 记载的「App.getVodInfo 单槽是详情页 B 覆盖 A 的隐式通道，已清一半」——片 3 清另一半（RemoteServer、DetailViewModel 仍直读直写）。 |
| `skill/avbox-mobile-ui-spec.md` | 「状态管理：单向数据流、状态集中管理、UI 由状态驱动、避免重复状态」条款的全面落地计划。宪法 C1–C5（§3.1）落地后该规范引用本计划作细则。§6.12/§6.13 的配置解析与崩溃标记口径不受本计划影响。 |
| `skill/review/refactor-plan-20261014.md` | 数据面已收口为 Repository + Flow（History/Collect/Follow/Cache 四接口），本计划直接复用其成果：片 1 的收藏/历史 UI 收敛建立在其 Flow 之上；片 5 的 KV/配置单入口（LIVE_API_URL 等）与其 Repository 体系风格对齐。其 D6「迁移=语义等价，不借机改线程模型」口径被本计划沿用为通用原则。 |
| `skill/avbox-kv-mmkv-spec.md` | 片 5 的 KV 写收敛（LIVE_API_URL/API_URL/搜索源键）不改变键名与编码，只收敛写入口；复杂键类型登记要求不变。 |
| `skill/avbox-iso-playback-spec.md` | 片 4 不触碰 ISO 包装层任何代码（C1–C14 不变量，尤其 `endTracks` 语义与 TrackOutput 不得扣数据）；片 4-9 的 IsoSessionSignal 归属调整只动信号持有位置，不动 ISO 解析链。 |
| `skill/avbox-kotlin-migration-spec.md` | 片 3/4 把 VodInfo/LiveChannelItem 等 bean 改不可变时，须按其 Gson×data class 规范核对动态 jar 与序列化消费方（§5 R7）。 |
| `skill/avbox-code-review-spec.md` | 本计划落地后，审查清单的「状态管理」维度可直接引用 udf_gate 结果作为硬判据。 |
| `AppThemeState` / `HomeSettings` / `PictureEffects`（代码内既有模式） | 片 5 设置类 KV Flow 化的对齐范本（KV 单入口 + Compose State/Flow 暴露）；`MusicSettings` 仅有 KV getter/setter 无 Flow，片 5 一并对齐。 |

# 2. 现状基线（2026-10-24 三路全仓盘点）

## 2.0 盘点方法与口径

- 方法：三路并行全仓探索（UI 层 / 播放器栈 / 全局状态与数据层）→ 汇总成稿 → **二次逐条核查修正**（2026-10-24 完成，全部引用经第二轮独立核对；修正记录：6 处行号/文件名勘误、2 处定性修正、补 12 项遗漏）。实施时行号会漂移，以符号定位为准。
- 路径约定：下文 `ui/…`、`player/…`、`base/…` 等均相对 `app/src/main/java/com/github/tvbox/osc/`。
- 「单向数据流」判定口径：①状态有唯一所有者；②UI 只读状态、只上抛事件；③跨组件传播走显式状态流/一次性命令流。违反任意一条即计入。纯 UI 状态（滚动位置、sheet 局部选中、输入框文本）留在 Composable 是**正确**的，不计入。边界项暂不纳入：TMDB 元数据在 Composable 层 remember+LaunchedEffect 获取（DetailHero/DetailTmdbSections/DetailEpisodes，有全局缓存兜底）与 VM 对 ApiConfig 的只读查询（如 HomeViewModel init）风险低，若后续统一再并入各 VM。

## 2.1 主题 1：VodInfo「当前播放内容」一图多主（最高优先）

「正在播哪条线路、哪一集」的状态真身是可变 bean `bean/VodInfo.kt` 的 `@JvmField var playFlag/playIndex` 与 `VodSeries.selected` 等字段，同一实例被多方共享直改。

**持有链（同一实例 5 处）**：

| 持有者 | 位置 |
| --- | --- |
| DetailViewModel 展示态 | `ui/activity/DetailViewModel.kt:82`（`var vodInfo: VodInfo?`） |
| App 全局单槽 ✦ | `base/App.kt:143-148`（`setVodInfo/getVodInfo`，进程级可变槽） |
| 播放引擎会话 | `player/PlaybackController.kt:35,80`（字段声明与 `vod = session.vod()` 赋值） |
| 播放器 UI 状态容器 | `player/state/PlayerUiState.kt:67`（`sessionVod`，由 PlayContainer 3 处手工播种） |
| 音乐页 | `ui/activity/MusicPlayerActivity.kt:102`（`controller.vod()`） |

**共享引用建立点** ✦：`DetailViewModel.preparePlaySession`（`ui/activity/DetailViewModel.kt:998-1016`）把 `preview.seriesFlags = info.seriesFlags; preview.seriesMap = info.seriesMap` **按引用共享**（非拷贝），随后 `App.getInstance()?.setVodInfo(preview)` 入全局槽——从此 Detail 页、音乐页、引擎改的是同一对象图。

**写者清单（6 类）**：

| 写者 | 位置 |
| --- | --- |
| VM 内多处直改字段 | `ui/activity/DetailViewModel.kt:435-451, 764-772, 813-831, 843-845, 939, 972-977` |
| 引擎自动换线路直改 ✦ | `player/PlaybackRetryDelegate.kt:475-476`（`vod.playFlag/playIndex = …`） |
| UI 监听器直改 | `ui/player/PlayContainerControlListener.kt:74`（`vod()?.playerCfg = …`） |
| 音乐页直改 | `ui/activity/MusicPlayerActivity.kt:295-306`（playIndex/selected/playNote） |
| Activity 直改后手动失效 | `ui/activity/DetailActivity.kt:366-367`（改 playFlag/playIndex 再 `vm.bumpRevision()`） |
| EventBus 回写 | `TYPE_REFRESH(VodInfo/JSONObject)` → `ui/activity/DetailViewModel.kt:926-948` 再写同一 bean |

**双路径传播** ✦：`ui/player/PlayContainerControlListener.kt:72-76` `updatePlayerCfg()` 先直写 `vod()?.playerCfg`、紧接着 `EventBus.post(TYPE_REFRESH, persistCfg)` 让 DetailViewModel 再写一遍——同一数据写两遍、两个接收端，顺序不确定。

**为什么该改**：任何一处漏 `bumpRevision()` 或 token 校验即 UI 与播放器不一致；换线路、音乐页回传、自动重试三个场景互相踩；`DetailActivity.kt:250-264` 的 `engineOwnsDetailContent` 甚至要靠容错比对承认「controller 的 sourceKey 可能取自上次会话」——这是多副本不同步的直接症状。

## 2.2 主题 2：Detail 页双份状态 + UI 直写状态源

| 问题 | 位置 | 说明 |
| --- | --- | --- |
| fullScreen 双份 ✦ | `ui/activity/DetailActivity.kt:54`（`private var fullScreen`）+ `ui/activity/DetailViewModel.kt:63`（`MutableStateFlow(false)`） | 靠 `LaunchedEffect` 单向搬运，但 Activity 的 `hideSysBar()/keepStatusBarHidden()/isFullBox()`（:94-98,430-433）读自己那份、Compose 侧读 VM 那份；转场期间两份存在帧级偏差，back 路径（:120-146）混读两份 |
| Activity 直写 StateFlow | `ui/activity/DetailActivity.kt:422`（`vm.rotating.value = false`） | 绕过事件方法 |
| 两条平行同步入口 | `ui/activity/DetailActivity.kt:359-369`（`syncEpisodeAfterMusicPage` 直改 vodInfo 字段 + `bumpRevision()`）vs `ui/activity/DetailViewModel.kt:962-981`（`syncPlayingVodInfo`） | 两者规则不一致：VM 版有集数匹配 `findSameEpisodeIndex`、flag 合法性校验、selected 维护；Activity 版全没有——**音乐页返回后选中集可能越界**（已知缺陷，片 1 顺带修复并登记行为变化） |
| revision 计数器替代状态流 ✦ | `ui/activity/DetailViewModel.kt:243-245`（`bumpRevision`）+ 同款空读标记共 3 处（`DetailContent.kt:58`、`DetailEpisodes.kt`、`DetailSections.kt`，udf_gate r4 实测） | 可变 bean 非 observable 的直接代价；全文件 bumpRevision 调用 9 处，漏一处即 UI 静默不刷新 |
| 业务轮询在 UI | `ui/activity/DetailScreen.kt:172-188`（`while(true){ delay(300) }` 音乐识别轮询 + 两个 remember 标志） | 业务触发条件（播放信号 + resumed + 音频内容）散在 Composable |

## 2.3 主题 3：直播页「共享可变黑板」

`LivePlayUiState` 的**存储**设计是全项目范本（单一私有 MutableStateFlow + 不可变 data class 分域），但**写路径完全发散**：

- **属性门面**：`ui/activity/LivePlayActivity.kt:65-224` 30+ 个 getter/setter 直通 `vm.updateXxx`，回调里以赋值语法写状态（:337-345, :435-437, :453）。
- **三个子控制器三向节点**：`LiveOverlayController`、`LiveCatchupController`、`LiveChannelSourceLoader` 各自「读 VM + 写 VM + 直写引擎」（`LiveOverlayController.kt:45-258` 多处、`LiveCatchupController.kt:99-123`、`LiveChannelSourceLoader.kt:59-226`）；VM 的 `onSettingClicked`（`LivePlayViewModel.kt:234-337`）除写 VM 外还直写 LIVE_CONNECT_TIMEOUT 等 5 个设置键 KV（:255-263）并回调 Host 十余个方法驱动 Activity 侧引擎。
- **Host 接口含可变 var，子组件反向写 Activity 字段** ✦：`override var logoUrl`（`LivePlayActivity.kt:261-265`）、`override var changeSourceTimes`（:281-285）；被 `LiveCatchupController.kt:41,54`、`LiveChannelSourceLoader.kt:192,217` 跨对象赋值。
- **频道列表同一可变对象图多处持有 + 原地改** ✦：同一批 LiveChannelGroup/LiveChannelItem 实例**按引用**穿透 ApiConfig → Loader → Activity 的 `liveChannelGroupList`（:173）→ VM 的 `channelGroups`（`LivePlayViewModel.kt:133,151-162`，双重持有非拷贝）→ 派生 rows（只包装不拷贝）；加载器经 `host.groups().clear(); host.groups().addAll(groups)`（`LiveChannelSourceLoader.kt:133-134`）原地改 Activity 列表再回调通知 VM 重建——严格依赖 clear→addAll→onChanged 顺序，半更新状态可被读到。
- **会话状态写进数据层 bean**：`bean/LiveChannelItem.kt:57` 的 `sourceIndex`（var，且 `url` getter 依赖它）被 `LivePlayViewModel.kt:243` 与 `LiveChannelSourceLoader.kt:177` 双写；重载直播配置后随旧实例丢弃，靠 `pendingLiveRefreshSourceIndex` 旁路变量（`LiveChannelSourceLoader.kt:53,143-145,189-190`）续命。
- **游离在 VM 之外的 Activity 业务状态**（9 个）：`liveChannelGroupList`、`currentLiveChangeSourceTimes`、`logoUrl`、`exitingLivePlay`、`liveSettingGroupList`、`nowday` 等（`LivePlayActivity.kt:170-223`）。

## 2.4 主题 4：页面直写播放引擎的 setter 网格

- **绕过指令面直写引擎内部状态** ✦：`ui/player/PlayContainer.kt:771-795` `stopForSourceSwitch` 一口气调 `scheduler.markStoppedForSourceSwitch()/setPendingInherit()/setWebPlayUrl(null)/setWebHeaderMap(null)/initParseLoadFound()`；同类直写散布 `setData`（:544-547）、`playNext/playPrevious`（:682-705）、`PlayContainerControlListener.kt:82` 等 10+ 处。`player/PlaybackAttemptState.kt:3-105` 是 30+ 个 `@JvmField var` 大杂烩，复位靠 `beginSession/beginNewPlay/userSelfRescue/clearSessionFlags` 四份手工清单（漏登记 = spec 反复修的「会话标志残留」类 bug）。
- **成对/三重复制的标志**：`ownedPlaybackKey`（PlayContainer:89）↔ `startedPlaybackKey`（PlaybackProgressTracker:31,101）↔ `engineOwnsDetailContent` 三重比对；`castPrepareOnly` 两份（PlayContainer:91 / controller:416-420，双写双读）；`previewMode` **三份**（PlayContainer:98 / VideoPlayerController:176 / PlayerUiState:68，同步三写）；`lifecyclePaused` 两份（PlayContainer:88 / PlayerUiState:112）。
- **playerCfg 可变 JSONObject 三位一体**：同一实例同时是引擎配置源（`PlaybackController.kt:36,258-260`）、UI 面板数据源（`controller/PlayerConfigDelegate.kt:196-296` 原地 `cfg.put(...)`）、VodInfo 持久化字段（`PlayContainerControlListener.kt:74`）；UI 每次 apply 都是「原地改 JSON → 手工刷派生 → 反向持久化 + 发总线」三连。
- **起播编排 4 份重复副本**：`consumeKernelRebuildRequired → KernelReusePolicy.decide → saveCurrentProgress → setProgressKey → markContentStarted → setUrl → skipPositionWhenPlay` 的顺序敏感编排，在 `PlayContainerViewBridge.kt:175-220`、`PlaybackEngine.kt:632-656`（HeadlessView）、`MusicPlayerActivity.kt:438-472`、`LivePlayActivity.kt:566-587` 四处几乎逐行重复且已漂移（音乐页/无头页不设 trackMemoryKey，直播页不落 progressKey）。
- **playState 三副本**：真源 `PlaybackStateMachine`（`player/state/PlayState.kt`）→ `AppPlayerView.dispatchPlayState`（:544-550）→ `ComposeLiveController.kt:59,66-69` 自存副本 → Activity setter → VM（`LivePlayActivity.kt:70-74`）；重挂控制器时靠 `AppPlayerView.kt:602-607` 补推。
- **其余**：Tip 双源（静态 `PlayerTipBridge` 单槽 listener + PlayerUiState，`PlayerTipBridge.kt:17-45`）；当前地址三副本（controller `webPlayUrl` / `AppPlayerView.mUrl` / `VideoPlayerController.contentUrl`，靠 `PlayContainer.kt:550` 播种补丁对齐，spec §7.4 2026-10-08 记录的切页进度链路缺陷「进度条闪回上一部」正是这两份不同步所致）；position 多副本多节奏（`VideoPlayerController.onProgressTick:404-430` 轮询写 state、`AppPlayerView.mCurrentPosition` getter 写回 :417、`PlaybackService.position` 经 ACTION_UPDATE intent extras 透传 :45/:575-581、拖动乐观写，五份各按各节奏）；controller 在状态回调里反向下发业务命令（`VideoPlayerController.applyPlayState:358-377` ERROR→errReplay、COMPLETED→playNext，onProgressTick 的 skipEnd 自动连播——事件回路）；`PlaybackEngine.onPlayStateChanged:160-169` 在 flow collect 里反向调 `controller.markPlaybackStarted()` 并直改 UI（view→engine→controller→view 回写环）；`AppPlayerView.setPlayerState:558-561` 直推控制器的第二条同步通道；指令面双入口（`VideoPlayerController` 直打 videoView 与 `PlayContainer.ensurePlaybackActive:251-260` 并存）；音频 only 四重奏（`MusicSessionDelegate.kt:36,79-89` + `PlaybackAttemptState.kt:77-80` + 内核 audioOnlyMode + 页面标志）；`PlaybackEngine.session` 与 `controller.currentSession` 双持有（清理时机不同步）；`IsoSessionSignal`/`SlowOpenSignal` 静态 object 被 UI 直读（`DetailActivity.kt:325`、`MusicPlayerActivity.kt:261`）；`LivePlayerManager` 配置三副本（两份 JSON + KV，`LivePlayerManager.kt:10-14,49-85`）。

## 2.5 主题 5：全局单例与 KV 散写

| 问题 | 位置 | 说明 |
| --- | --- | --- |
| LIVE_API_URL 键 8 处写、无统一入口 | `api/ApiConfig.kt:186,193`、`api/ConfigLoader.kt:348,368`、`ui/page/AppBootstrap.kt:98`、`ui/page/ConfigManageViewModel.kt:442,449`、`ui/activity/LivePlayViewModel.kt:299` | 每处手工「put + clearHosts + invalidate + (可选)重载」组合拳且组合不一致（ConfigManageViewModel 不重载、LivePlayViewModel 重载）；`shouldReloadLiveConfig()`（ApiConfig:130-133）本身就是为补这种不一致而存在。`API_URL` 键同病（4 处写） |
| 「已选搜索源」三份 | KV `SOURCES_FOR_SEARCH` + `SearchViewModel` companion 静态 `checkedSources`（`ui/activity/SearchViewModel.kt:81-117`，靠 `isCheckedSourcesStale()` 轮询 KV 判过期，UI 还直调静态 `loadCheckedSources()` ✦ `SearchScreen.kt:88-108`）+ `SearchSettingsSheet` 本地 selected 且直读 ApiConfig（`ui/components/SearchSettingsSheet.kt:61` `remember { ApiConfig.get().getSourceBeanList()... }` 无失效键） | **切源后 sheet 显示陈旧源列表**（用户可见缺陷，片 2 顺带修复并登记） |
| 搜索历史三入口 ✦ | `ui/activity/SearchScreen.kt:81`（remember 本地副本）、:117-125（submit 后手动重读 KV）、:182-189（UI 直接调 HistoryHelper 删/清再重读）+ `SearchViewModel.kt:229`（VM 写 KV）；resultLayout 同模式（UI 直读写 SearchSettings，`SearchScreen.kt:86,141-142`） | 同一持久状态 3 个写入口 + 1 份本地副本 |
| PlaybackPorts 全局可变 lambda 注册表 | `data/PlaybackPorts.kt:5-14`；写者 `base/App.kt:56`、`player/PlaybackService.kt:495-497`；调用者 `data/WatchProgressStore.kt:146-253`、`data/PlaybackProgress.kt:119` | 数据层经 lambda 反向调播放器层，服务定位器形态，依赖运行期注册时序 |
| AppBootstrap 越层 | `ui/page/AppBootstrap.kt`：直写 KV（:96,98）、调 `ApiConfig.invalidateVodConfig()`（:83）、调 UI 层 VM 静态 `SearchViewModel.clearCheckedSources()`（:85） | 全局业务协调者放在 ui.page 包且直写多层数据；其 state 被 HistoryViewModel/HomeViewModel/FollowingViewModel/CollectPage 跨层 collect，启动状态源倒置在 ui 层 |
| ApiConfig 可变暴露 | `api/ApiConfig.kt:875-876` `channelGroupList` getter 直接返回内部可变 List；`liveSettingGroupList` public var 被 `LivePlayActivity.kt:738-742` 直读直改 | 数据层缓存对象与页面共享同一批 item 实例 |

## 2.6 主题 6：EventBus 误用与死代码

- **死代码**：`event/ServerEvent.kt` 一个类三个事件常量全仓无任何 `@Subscribe`（推送搜索链路已断）；`TYPE_SUBTITLE_SIZE_CHANGE`/`TYPE_DANMU_REFRESH` 只有 handler 无 poster（`ui/player/PlayContainer.kt:297,302`）；`player/PlaybackStarter.kt:278` post 的 String 载荷 TYPE_REFRESH 无消费者（TYPE_REFRESH 全仓共 3 个 post 点，唯一处理器 `DetailViewModel.onRefreshEvent` 只认 VodInfo/Int/JSONObject）。
- **搜索结果双通道**：`sourcedata/SourceResultParser.kt:136,197` 对 searchResult 走 EventBus、对其他 list 走 channel（`postSearchResult` :142,203）；`DetailViewModel.kt:559,563`、`SearchViewModel.kt:282,287`、`HomeViewModel` PartitionLoader 各自收发，token 校验逻辑写两份。
- **设置页广播改别人状态**：`ui/page/PreferenceSettingsPage.kt:86,103,114` 发 `TYPE_COLLECT_LAYOUT_CHANGE`/`TYPE_HISTORY_REFRESH` → `ui/page/CollectPage.kt:127-131`、`HistoryViewModel.kt:73` 收到后回头再读 KV——KV 已是持久状态，又用总线当「KV 变更通知」，两套机制叠床架屋。
- **UI→UI 总线**：`player/ui/DanmuSheets.kt:72` → `PlayContainer.kt:300-301`，同在播放器内部却走全局总线。
- **数据层发事件**：`data/HistoryWriter.kt:47,57`、`data/PlaybackProgress.kt:131`、`player/PlaybackController.kt:157,160`（TYPE_PLAY_QUALITY → `DetailViewModel.kt:585-586`）——数据层/引擎 → VM 的状态更新绕开显式状态源。

## 2.7 主题 7：无 ViewModel 页面与绕分层

- `ui/activity/MusicPlayerActivity.kt` **完全无 VM**：状态散在 Activity 字段（:81-92），`MusicPageBridge` 子对象反向写父 Activity 的 `lyricSource` 私有字段（:594-604）；播放队列索引双份真相（`vod.playIndex` ↔ `ui.queueIndex`，playAt 手工同步两处、refreshMeta 再写第三处，:295,300,399，任一遗漏即分叉）；Activity 直接调数据层 `WatchProgressStore.clear`（:293 与 replayCurrent 内 :326）、`EpisodeTotals.putFromVod`/`HistoryWriter.write`（:307-308）。
- `ui/activity/LiveChannelSourceLoader.kt`、`LiveProxyLoader.kt`：Activity 的 helper 直编排 `ApiConfig.loadLiveConfig/loadLives`（:102-125 / :100-123）；`LivePlayActivity.kt:747,836-856` 直调 `ApiConfig.refreshLiveApiHistoryItems()` 并直改 KV `LIVE_API_HISTORY`。
- `ui/components/VodCardMenu.kt:26-33,59-67`：通用菜单组件在 Composable 协程里直写收藏数据库（绕开 CollectViewModel 的 flow 体系），收藏结果无反馈。
- `ui/activity/SearchViewModel.kt:139-168`：VM 内直接 `Http.get` 豆瓣热搜并写 KV（爱奇艺 suggest :179-193 亦直连但不写 KV；网络层职责进 VM，轻微）。

## 2.8 主题 8：UI 层散项（低风险快速收敛）

| 问题 | 位置 |
| --- | --- |
| Composable 直写 VM 公有 var ✦ | `ui/page/MainScreen.kt:142-155`（`homeViewModel.defaultLiveLaunched = true`）、:164-173（`lastBackTime`）；声明于 `ui/page/HomeViewModel.kt:107-108` |
| Composable 直写 VM StateFlow | `ui/page/ConfigManagePage.kt:187,399`（`vm.editTarget.value = …`） |
| 「当前分类」双份双向同步 | `ui/page/HomeGridLayout.kt:118,122-132`（selectedSortId ↔ `vm.activeSortId`，`HomeViewModel.kt:104-105` public var） |
| 筛选选择写进可变 bean | `ui/page/HomeViewModel.kt:454-455`、`ui/page/PartitionListViewModel.kt:157-161`（`sort.filterSelect = HashMap(...)`，SortData 实例被 Partition 持有） |
| 列表元素原地改字段再假刷新 | `ui/page/HistoryViewModel.kt:86-125`（改 info.sourceName 等再 `items.value = list.toList()`） |
| 同型页面状态归属分裂 | `ui/page/RecordsPage.kt:77-80,109-121`（编辑/选择在 Composable + effect 剪枝补丁）vs `ui/page/FollowingViewModel.kt:39-41,126-130`（在 VM + 统一 prune） |
| 页面形态双份 | `ui/activity/PartitionListActivity.kt:87-88,162-168`（mode 由 Activity 自持并手工从 VM 栈推导） |
| public MutableStateFlow 普遍暴露（写权限泄漏） | `DetailViewModel.kt:61-77`（17 个）、`HomeViewModel/SearchViewModel/HistoryViewModel/FollowingViewModel/ConfigManageViewModel` 同；已实际被 UI 直写 3 处 |
| ParseBean.isDefault 当状态位 | `api/ApiConfig.kt:818-826` 改列表内 bean 标志 + `PlayerActionsDelegate.kt:287` `parseListVersion++` 手工失效 |
| VM 直写 KV 缓存键 | `ui/page/HistoryViewModel.kt:122`（resolveSourceNames 内写 SOURCE_NAME_CACHE） |
| 全局监听器槽 | `player/PreloadManagerHolder.kt:23-60,112-138`（object 持 @Volatile sReadyListener；限 player 层内部，低） |

## 2.9 补丁文化登记（多副本不同步的症状，随片退役）

`bumpRevision`/`revision`（主题 1）｜`@Suppress("UNUSED_EXPRESSION")` 空读标记（DetailContent/DetailEpisodes/DetailSections 共 3 处，udf_gate r4）｜`engineOwnsDetailContent` 三重比对（DetailActivity:250-264）｜`shouldReloadLiveConfig`（ApiConfig:130-133）｜`pendingLiveRefreshSourceIndex`（LiveChannelSourceLoader:53）｜`isCheckedSourcesStale` 轮询（SearchViewModel:115-117）｜`contentUrl` 播种补丁（PlayContainer:550）｜`rebindLiveControllerIfNeeded` 补推（AppPlayerView:602-607）【2026-10-24 审计判定：转正保留——非三副本补丁，而是 resume 绑定一致性自愈守卫；补推对 live 的剩余消费 = setPlayState→VM 同步（setPlayerState/startProgress 在 C-5a 后为空实现）；详见 §11】｜ 各类 stale-token 校验两份（DetailViewModel:563 / SearchViewModel:287）。这些补丁在对应主题收敛后应随片删除——**它们的存在本身就是该处违反 UDF 的证据**。

## 2.10 已符合 UDF 的范本对照表（重构对齐目标，均为项目内既有代码）

| 范本 | 位置 | 亮点 |
| --- | --- | --- |
| **LivePlayViewModel + LiveScreen(state, actions)** | `ui/activity/LivePlayViewModel.kt:100-131`、`LiveScreens.kt:60-141` | 单一私有 `MutableStateFlow<LivePlayUiState>` + `asStateFlow()` + 不可变 data class 分域 + 事件包上传。**首选范本**（存储侧；写路径发散是片 2 对象） |
| SearchViewModel 结果流 | `ui/activity/SearchViewModel.kt:297-305` | 不可变 SourceResult + map/copy 列表分区更新 |
| HomeViewModel Partition 状态迁移 | `ui/page/HomeViewModel.kt:409-437` | 分页/重试/筛选的状态机式 copy 更新 |
| FollowingViewModel 选择状态 | `ui/page/FollowingViewModel.kt:39-41,88-130` | editMode/selected 入 VM、乐观更新、统一 prune |
| ConfigManageViewModel.tmdbState | `ui/page/ConfigManageViewModel.kt:113-150` | 私有流 + 只读访问器 + 回调包 |
| HistoryViewModel 数据库驱动 | `ui/page/HistoryViewModel.kt:39-82` | flow + reloadTick flatMapLatest；信号流替代共享可变标记 |
| DetailViewModel.playbackCommands | `ui/activity/DetailViewModel.kt:79-80` + `DetailScreen.kt:155-166` | 一次性命令的正确通道（Channel → receiveAsFlow → LaunchedEffect collect） |
| PlayerUiState | `player/state/PlayerUiState.kt` | 状态容器 + 派生 getter（overlayPanelOpen/loadingVisible 等） |
| PlaybackStateMachine | `player/state/PlayState.kt` | StateFlow 单一真源 + 纯函数派生 |
| 单一写入口/纯函数 | PlaybackProgressSampler、KernelReusePolicy、ProgressUiGate、ProgressTickDelay、VideoSizeGate | spec 已登记的收敛成果 |
| KV 单入口 + Flow | AppThemeState、HomeSettings、PictureEffects（MusicSettings 仅 KV 单入口无 Flow，片 5 一并对齐） | 片 5 设置类 KV 的对齐形态 |

# 3. 终态设计

## 3.1 单向数据流宪法（C1–C5，完成判定）

- **C1 状态单一所有者**：每个可观察状态有且仅有一个持有者（VM / 引擎状态机 / Repository Flow / 设置单入口对象）。同一状态的任何第二份副本必须由第一份**派生**（投影、map、derivedStateOf），禁止独立维护。
- **C2 UI 只读状态、只上抛事件**：Composable / Activity 不得直写任何非自有的状态——包括 VM 的 `MutableStateFlow.value`/公有 var、共享 bean 的字段、引擎 scheduler/controller 的内部标志。一切写操作收敛为所有者暴露的事件方法（`vm.onEpisodeClick(...)`）或意图方法（`controller.requestSourceSwitch(...)`）。VM 对外只暴露 `StateFlow`（private `_x` + `asStateFlow()`）。
- **C3 显式通道**：跨组件状态传播只允许两类通道——持续状态走 StateFlow（订阅方自动对齐），一次性命令走 Channel/SharedFlow effect（`playbackCommands` 模式）。**禁止**：EventBus 携带状态、全局可变单槽（App.setVodInfo 型）、跨对象可变 var 接口属性（Host `var logoUrl` 型）、回调接口反向写调用方字段、数据层经 lambda 注册表向上调用。
- **C4 数据不可变**：跨层交接的数据模型为不可变 data class（构造后不再被赋值字段）；「选中/当前项/当前线路/进度」类会话状态**不得**写入共享 bean 字段（VodInfo.playIndex、LiveChannelItem.sourceIndex 型反模式清零）。列表状态更新用 map+copy 产生新实例。
- **C5 引擎会话单点迁移**：`PlaybackAttemptState` 类引擎会话状态由唯一 reducer（controller 内单一 transition 方法族）迁移并**集中复位**；页面只能发意图方法，不得 set 内部标志；复位清单只有一份。

**完成判定**：宪法全条满足 + §2.9 补丁清单全部退役 + udf_gate（§3.3）全仓绿。

## 3.2 各状态域终态归属表

| 状态域 | 终态唯一所有者 | 状态形态 | 写入口（事件/意图方法） | UI 消费 |
| --- | --- | --- | --- | --- |
| 详情页展示态（playFlag/playIndex/reverse/related/collected/follow/collected 状态） | DetailViewModel | 不可变 `DetailUiState` data class 入 StateFlow（替换 vodInfo+revision） | vm.onEpisodeClick/onFlagClick/toggleReverse/toggleCollect… | collectAsState |
| 播放会话选择（正在播的 flag/index/线路/播放器配置） | PlaybackController | `StateFlow<PlaybackSelection>` + 不可变 PlayerConfig | controller.requestEpisodeSwitch/requestSourceSwitch/applyPlayerConfig…；引擎内自动换线同走 transition | DetailViewModel 订阅映射进 UiState；音乐页订阅 |
| 引擎会话标志（attempt state、归属键、投屏/预览/音频 only、ISO/慢启信号） | PlaybackController（reducer 单点） | 私有不可变状态 + transition | 意图方法族；复位集中一份 | 不直接暴露页面 |
| 内核播放态 playState / 进度 | PlaybackStateMachine / 采样器（已收敛，保持） | StateFlow（已是） | — | 全员 collect playStateFlow（删 controller 自存副本与 intent extras 透传） |
| 直播页全部状态（含现 Activity 自持 9 项 + 频道列表 + 当前线路） | LivePlayViewModel | LivePlayUiState 扩展（范本已立） | LivePlayActions 事件包 | LiveScreen(state, actions)（已立） |
| 音乐页 | MusicPlayerViewModel（新） | 不可变 UiState | actions | Screen(state, actions) |
| 搜索页（query/suggest/结果/源选择/历史） | SearchViewModel | UiState + StateFlow（吸收 checkedSources 静态与 KV 直读写） | vm.setSourceSelection/addHistory/removeHistory… | collectAsState |
| 当前配置源（API_URL/LIVE_API_URL/源列表/直播组/解析器） | ConfigRepository（ApiConfig 收敛形态） | Flow + 单一 switch 方法 | repository.switchVodSource/switchLiveSource（内部完成 KV+hosts+失效+历史+重载编排） | VM 订阅 |
| KV 设置类（布局/主题/播放器偏好…） | 各 Settings 单入口对象 | State + Flow（AppThemeState 模式推广） | put(key, value) 单入口 | collectAsState |
| 收藏/历史/追更 | 既有四 Repository + Flow（20261014 成果，不动） | Flow（已是） | repository 方法 | VM 订阅（VodCardMenu 上抛回调由页面 VM 执行） |
| 一次性命令（导航/toast/全屏动画/换线提示） | 产生方 VM/控制器 | Channel/SharedFlow effect | send | LaunchedEffect collect |

## 3.3 防回归门 udf_gate.py（片 0 交付）

仿 `null_assert_gate.py` / `i18n_gate.py` / `playstate_gate.py` 模式放 `.codebuddy/tools/`，基线 JSON **只允许下调**（`--update` 拒绝上调），行级例外须登记理由。扫描规则：

1. `EventBus.getDefault().post(` / `@Subscribe` 出现点计数（片 5 终态归零，中途基线锁定防新增）。
2. VM/Repository 中 public `MutableStateFlow`/`MutableState` 暴露（要求 private + asStateFlow/公开只读）。
3. `ui/` 包文件中对 `*.value =` 的直写（Flow 写权限泄漏启发式）。
4. `@Suppress("UNUSED_EXPRESSION")` 零容忍（revision hack 类）。
5. 接口中的 `override var`（Host 可变属性模式）出现点计数。
6. `App.setVodInfo` / `App.getVodInfo` 引用计数（片 3 后归零）。
7. （基线观察项，不阻断）`scheduler.set`/`scheduler.mark` 在 `ui/` 包的调用计数（片 4 后归零）。

# 4. 分片实施

每片独立构建/单测/走查、独立 commit、独立回退。通用验收：`.\gradlew :app:assembleDebug` + `.\gradlew :app:testDebugUnitTest` 全绿（PowerShell；不跑 release）；udf_gate 对应基线条目下调；改动涉及字段取值口径的补单测锁口径。

## 片 0：防回归门与基线登记

- **对象**：`udf_gate.py` + 基线 JSON；§2 清单登记为基线快照。
- **验收**：gate 可运行，当前全仓按基线绿（不要求清零，只锁死不新增）。
- **回退面**：纯工具，零代码影响。
- **执行记录（2026-10-24，已完成）**：`.codebuddy/tools/udf_gate.py` + `udf_gate_baseline.json` 交付并首冻，校验 PASS。基线实测：r1=23（18 post + 5 @Subscribe）、r2=63（7 个 VM 文件，DetailViewModel=17 与 §2.8 一致）、r3=3（即 §2.8 登记的 3 处 UI 直写）、r4=3（**比 §2.2 原盘点多出 DetailEpisodes/DetailSections 两处，已回写勘误**）、r5=2（logoUrl/changeSourceTimes）、r6=6（App.kt 3 + RemoteServer 1 + DetailViewModel 2；注意 `HistoryRepository.getVodInfo(key,id)` 为同名仓库方法、非单槽消费，判定已排除——片 3 改造时勿混淆）、r7=21[观察]（PlayContainer 15 / ControlListener 4 / Overlays 1 / ViewBridge 1）。首冻用 `--update --accept-new`，此后只允许 `--update` 下调；r2 判定域限 VM 持有者文件（PlayerUiState/MusicPlayerState 容器除外）、r3 接收者带限定符或 `this.` 剥离后为同文件自有属性时不计——口径见脚本 docstring。未 commit。

## 片 1：Compose/VM 层双写消除（低风险）（2026-10-24 已完成，见本节末执行记录）

1. fullScreen 双份消除：删 `DetailActivity.fullScreen` 字段，`hideSysBar()/keepStatusBarHidden()/isFullBox()` 改读 `vm.fullScreen.value`。
2. DetailActivity 直写收敛：`vm.rotating.value = false` → VM 事件方法；`syncEpisodeAfterMusicPage` 收进 VM 统一走 `syncPlayingVodInfo`（**连带抄 VM 版防御**：集数匹配、flag 校验、selected 维护——顺带修复「音乐页返回选中集越界」，登记行为变化）。
3. MainScreen 直写 `HomeViewModel` 公有 var → `markDefaultLiveLaunched()` / `onBackPressedOnce(): Boolean` 事件方法。
4. ConfigManagePage 直写 `editTarget.value` → `vm.openEdit(target)/closeEdit()`。
5. 搜索历史入 SearchViewModel：StateFlow + add/remove/clear 方法；删 SearchScreen 本地 remember 与 KV 手动重读。
6. 已被直写的 public MutableStateFlow → `private val _x + val x = _x.asStateFlow()`（先改已发生直写的 DetailViewModel/ConfigManageViewModel，其余随片推进全量统一）。
7. 「当前分类」单一化：`activeSortId` 升 StateFlow 唯一源，HomeGridLayout 只 collect + `vm.selectSort(id)`，删双向 LaunchedEffect 搬运。
8. RecordsPage 编辑/选择状态入 VM（对齐 FollowingViewModel，pruneSelection 统一，删 effect 剪枝补丁）。
9. （可选项，若交互面大可延片 2）DetailScreen 音乐识别轮询移入 VM。
- **验收**：构建+单测+人眼走查（Detail 换集/换线路/全屏进出/音乐页返回、Home 分类切换、Records 编辑多选、搜索历史增删清）。
- **回退面**：9 个子项各自独立 commit。
- **执行记录（2026-10-24，子项 1–8 完成）**：
  - 子项 1：`fullScreen` 镜像字段删除，读取面（hideSysBar/keepStatusBarHidden/back 处理/applyVideoOrientation/isFullBox）全部改读 `vm.fullScreen.value`；`applyFullscreen` 的去重守卫改为 `appliedFullscreen` 私有簿记（view 副作用幂等去重，非业务状态副本——保留"跳过首次 false 调用"的原语义，避免借机改变启动期 orientation 行为）。
  - 子项 2：`vm.rotating.value = false` → `vm.settleRotation()`；`syncEpisodeAfterMusicPage` 收敛为 `vm.syncEpisodeAfterMusicReturn()`（保留 id 与 flag/index 短路守卫，同步本体走 `syncPlayingVodInfo`）。**登记行为变化（§5 R4）**：音乐页返回同步现在连带 VM 版防御（flag 合法性校验、findSameEpisodeIndex 集数匹配、selected 标志维护、sourceKey 一致性守卫）——顺带修复「音乐页返回选中集越界/selected 脱节」与「换源后跨源索引污染」两个已知缺陷。
  - 子项 3：`defaultLiveLaunched`/`lastBackTime` 改 `private set` + `markDefaultLiveLaunched()`/`onBackPressedOnce(): Boolean`（保留"确认退出那一次不刷新时间戳"的原语义）。
  - 子项 4+6：`editTarget` → `private _editTarget + asStateFlow()` + `openEdit/closeEdit()`（VM 内两处清空一并走 `closeEdit()`）；`rotating` 同步私有化。
  - 子项 5：搜索历史三入口收为 SearchViewModel 单入口（`searchHistory: StateFlow` + search 内更新 + `clearSearchHistory/removeSearchHistory`）；SearchScreen 删本地 remember 与 KV 手动重读及 3 个失效 import。
  - 子项 7：`activeSortId` 升 `StateFlow` 唯一源；删 HomeGridLayout 的两个双向搬运 LaunchedEffect（`selectedSortId` 本地镜像删除，`onSelect` 直发 `vm.ensureLoaded`）；VM `onSortResult` 对账与 `sorts.value` 同步写入——顺带消除 sorts 到达时本地镜像先取 first 的一帧错选闪变。
  - 子项 8：RecordsPage 的 history/collect 编辑与选择状态分别入 HistoryViewModel（Set&lt;String&gt;）与 CollectViewModel（Set&lt;Int&gt;），`enterEdit/exitEdit/toggleSelected/pruneSelection` 对齐 FollowingViewModel；**新增流按宪法 C2 用 private+asStateFlow 新写法**（不照抄 FollowingViewModel 的公有暴露旧式，避免 r2 上调——实施中自查纠正过一次）。剪枝 effect 只保留"items 变化 → vm.pruneSelection"单向调用。
  - 子项 9（可选）：**延至片 3**——判定依赖 `engine.attachedPage() !== container` 等 Activity 侧归属检查，片 3 建立 PlaybackSelection 流后该轮询可自然变为流式订阅，现在迁移会造出一次性接口。
  - 验证：`assembleDebug` 3 轮全绿（最后一轮 38s）；`testDebugUnitTest` 1399 例 0 失败 0 错误；`udf_gate` 基线下调 r2 63→61（DetailViewModel 17→16、ConfigManageViewModel 11→10）、r3 3→0（DetailActivity/ConfigManagePage 清零），复验 PASS；r4/r6 留待片 3、r1 留待片 5 按计划退役。
  - 改动面：12 个文件（DetailActivity/DetailViewModel/SearchScreen/SearchViewModel/CollectPage/ConfigManagePage/ConfigManageViewModel/HistoryViewModel/HomeGridLayout/HomeViewModel/MainScreen/RecordsPage），全部停留工作区未 commit（实施纪律）。人眼走查项（§7 片 1 行）待真机执行。
  - **自查审查轮（2026-10-24，全量 diff 通读 + 四项专项核查，结论：零回归、零遗漏）**：
    1. **子项 7 分区加载触发链**：初始/换源的分区请求由 VM `onSortResult` 末尾对 Loading 分区直接 `requestPartition`（HomeViewModel:383）发起，与被删的 `LaunchedEffect(selectedSortId)` 无关（该 effect 在初始路径本就因分区非 Idle 而 early-return 空转）；用户切 tab 走 `onSelect → vm.ensureLoaded`（Idle→Loading + 请求）与旧行为逐字等价；横竖屏切换走 `onLayoutChanged`。触发链完整。
    2. **子项 5 唯一写者成立**：`setSearchHistory` 全仓唯一调用点即 SearchViewModel 自身，clear/remove 亦然——迁移后无外部写者会导致流陈旧。
    3. **子项 8 影响面**：`CollectTab`/`HistoryTab` 全仓唯一调用方是 RecordsPage；CollectPage.kt:154 的 `editMode/selected` 是 CollectTab 形参（与 VM 新属性无冲突）；MainScreen 收藏 tab 走完整 CollectPage，不受影响。
    4. **子项 2 sourceKey 守卫不阻断合法同步**：handOffToMusicPlayer 成功路径必经 `preparePlaySession`（preview.sourceKey = info.sourceKey），返回时引擎 vod 即该 preview，`syncPlayingVodInfo` 的 sourceKey 守卫恒通过；音乐页无换源入口，无同 id 异源会话。
    5. 语义微差登记（非回归）：sorts 清空（换源失败/空源）时旧代码把本地选择重置为 ""，新代码保留 stale id——下游空列表渲染路径等价（HomeSortTabRow 对空 sorts 早退、Crossfade 查表落空渲染空态）。

## 片 2：搜索与直播页状态收口（中风险）（2026-10-24 已完成，见本节末执行记录）

1. `checkedSources` 静态退役：SearchViewModel 持源选择 StateFlow，订阅 AppBootstrap.state 失效重建；SearchSettingsSheet 改读 VM、`putSourcesForSearch` 移入 VM（**顺带修复切源后 sheet 陈旧，登记行为变化**）。
2. LivePlay Activity 自持业务状态入 VM：`logoUrl/currentLiveChangeSourceTimes/exitingLivePlay/liveSettingGroupList/nowday` 进 LivePlayUiState。
3. Host 接口 `var` → 只读查询 + 命令方法（logoUrl、changeSourceTimes 由 VM/Activity 单点折算，子控制器不得反向写字段）。
4. 频道列表唯一归属 VM：不可变 List StateFlow；删 Activity `liveChannelGroupList` 与 `host.groups()` 原地 clear/addAll；Loader 改产出 List 交 VM。
5. `LiveChannelItem.sourceIndex` 退役：当前线路收进 VM Selection 状态（`preferredRefreshSourceIndex` 旁路随之删除）。**实施前核对 bean 消费方**（§5 R7）。
6. VodCardMenu 直写收藏库 → `onCollectToggle` 上抛回调，各页面 VM 执行。
7. AppBootstrap 去 UI 层静态依赖（checkedSources 退役后自然消除）；归位出 `ui.page` 包可一并做或留片 5。
8. LivePlayActivity 属性门面 setter 收敛为 vm 事件方法（30+ 个，可按控制器分 3 小批）。
- **验收**：构建+单测+人眼走查（直播换台/换线路/密码组/EPG/时移/设置面板/字幕、搜索源选择与切源新鲜度、收藏卡片）。
- **回退面**：按子项/控制器分 commit。
- **执行记录（2026-10-24，组 A/B/C 完成，C-4 延后）**：
  - **组 A（搜索源三份收敛）**：新建 `ui/page/SearchSourcesState.kt`（进程级单源 `StateFlow<Ui(sources, pickedKeys)>` + `refresh()/applySelection()` 单入口，仿 HomeSettings.layoutFlow 范式）；`SearchViewModel` companion 的 checkedSources/checkedSourcesApiUrl/clearCheckedSources/loadCheckedSources/isCheckedSourcesStale 四件套静态全部退役；SearchScreen 两处直调静态的 LaunchedEffect 删除；SearchSettingsSheet 参数化（sources/selected/onApplySelection 由调用方注入，组件不再直读 ApiConfig、`remember` 无失效键的陈旧问题随之消除），HomePage 接线；AppBootstrap 在订阅切换与两处 Boot.Ready 挂 `SearchSourcesState.refresh()`，对 `SearchViewModel.clearCheckedSources()` 的 ui.page→VM 静态依赖消除（本片第 7 项达成）；SearchViewModel init/search 改读单源。**登记行为修复（§5 R4）：切源后搜索设置面板源列表陈旧**。exactMatch 开关留在 sheet 本地（开面板即读、写即时生效，无陈旧路径，不扩范围）。
  - **组 B（VodCardMenu 上抛）**：`rememberVodCardMenuState(lookupCollected, onCollectToggle)` 注入式回调，组件不再 import AppGraph/Dispatchers，`toVodInfo` 保留为共享转换；4 个页面 VM（Home/Search/Detail/PartitionListVM）各加 `isVideoCollected/toggleVideoCollect`，4 个调用点接线。收藏行为等价（原组件直写亦无 UI 反馈）。
  - **组 C-1（Activity 自持字段入 VM）**：`logoUrl`→`LiveEpgUi.logoUrl`；`currentLiveChangeSourceTimes`→`LivePlayerUi.sourceSwitchAttempts`（reset/bump 方法）；`liveSettingGroupList`→VM 私有副本（`updateSettingGroups` 深拷贝，`updateCurrentSourceItems` 整组替换 group[0]——不再原位改 ApiConfig 的 bean，解除数据层引用共享）。**口径修正（登记）**：`exitingLivePlay`/`nowday` 复核后留在 Activity——纯生命周期/常量簿记、无共享读者，不构成 UDF 违规（片 1 `appliedFullscreen` 同类先例）。
  - **组 C-2（Host var 归零）**：`LiveOverlayController/CatchupController/ChannelSourceLoader` 三 Host 接口的 `var logoUrl`/`var changeSourceTimes`/`groups()`/`onChannelGroupsChanged()` 全部移除，子控制器反向写 Activity 字段的通道关闭（catchup 改调 `vm.updateLogoUrl`，loader 改调 vm 方法/直供 `vm.onChannelGroupsChanged(groups)`）；**udf_gate r5 2→0**。
  - **组 C-3（频道列表唯一归属 + sourceIndex 退役）**：`LiveChannelItem` 删除 `sourceIndex`/`url`/`sourceName` getter 与 `nextSource/preSource/set-getSourceIndex`（全仓无外部调用者），新增 `urlAt(index)`；"当前线路"唯一归属 `LiveChannelListUi.playingSourceIndex`；`pendingLiveRefreshSourceIndex` 旁路删除（换源偏好经 VM 状态跨越重载）；Loader 的 `host.groups().clear/addAll` 原地改删除；Activity 的 `liveChannelGroupList` ArrayList 删除（getNextChannel/密码判定/设置源全部改读 `vm.channelGroups()`）。`LiveChannelItem.equals/hashCode` 由 `url`（依赖会话可变 sourceIndex，含隐性缺陷——同一对象在换线后 hashCode 漂移）改为 `channelUrls` 基（**登记行为修复**）。单测 LiveSettingsRulesTest/LiveSettingsSnapshotTest 同步新签名（hasChannelSource/of 增加 sourceIndex 参数）。
  - **组 C-4（30+ 属性门面收敛）：延后**——门面是 VM 单存储之上的语法糖（每个 setter 只写 VM，无双状态），机械收敛 diff 大而收益主要为风格统一；登记留待片 3 LivePlay 联动改造时一并处理或单开小批。
  - **自查修正 1 处**：initLiveState 的"频道名未找到回退 CCTV1/首组"路径，初版会残留换源偏好线路（旧代码回退条目线路恒 0），已改为除"找到且偏好有效"外一律归 0，与旧行为严格等价。
  - 验证：`assembleDebug` 全绿；`testDebugUnitTest` 1399 例 0 失败 0 错误；`udf_gate` r5 2→0 下调、复验 PASS（r1=23/r2=61/r3=0/r4=3/r6=6 不变，按计划留待片 3/5）。改动面：主代码 29 文件 + 测试 2 文件 + 新增 SearchSourcesState.kt，全部停留工作区未 commit。人眼走查项（§7 片 2 行：直播换台/换线路/密码组/EPG/时移/设置面板/字幕、搜索源选择与切源新鲜度、收藏卡片）待真机执行。
  - **自查审查轮（2026-10-24，全量 diff 通读 + sourceIndex 迁移/设置组副本专项推演，抓出 2 个真实回归并当场修复）**：
    1. **回归①（已修）：换台线路残留**——单一线路索引收敛后，`playChannel` 换频道（`!changeSource`）未重置线路，会沿用上一频道的线路号起播（旧 bean 模型是每频道独立、新频道默认线路 0）。修复：VM 增 one-shot 槽 `pendingSourceIndex`（`setPendingSourceIndex/consumePendingSourceIndex`，私有单写单读），initLiveState 装载恢复值（含密码弹窗异步窗口跨持），`playChannel` 换台分支消费（默认 0）。**登记语义变化**：单选模型不保留"每频道粘性线路"（旧 bean 在同一载入会话内记住各频道线路，切换回退频道恢复原线路；现在一律回线路 0，换源偏好恢复语义不变）。
    2. **回归②（已修）：设置面板历史组陈旧**——设置组改为 VM 深拷贝副本后，`ApiConfig.refreshLiveApiHistoryItems()` 原地改的是 ApiConfig 自己的 [5]/[6]，VM 副本不再天然跟随（旧代码 Activity 字段就是 ApiConfig 同一引用）。修复：`openSettingsSheet` 与 `removeConfigHistory` 在 refresh 后调 `initLiveSettingGroupList()` 重新拷贝 + `loadCurrentSourceList()`。
    3. 其余核查通过：换源偏好跨重载链路（refresh→reload→restore 的时序与 clamp 口径）、`bumpSourceSwitchAttempts` 与旧 `times++/==sourceNum` 语义逐值等价（含 sourceNum=0 边界）、`getLiveChannels` 密码组空表、`onChannelGroupsChanged(emptyList())` 清场、`equals/hashCode` 改 channelUrls 基对 Compose 列表 diff 无碍、SearchSourcesState 三处 refresh 挂点覆盖旧静态的全部触发路径（进搜索页/Boot.Ready/订阅切换）、sheet 打开期间选择变化经流实时回显（优于旧本地 state）、VodCardMenu 注入回调的 lambda 生命周期（VM 稳定引用，remember(scope) 不重建）。
    4. 修复后复验：`assembleDebug` 全绿、1399 例 0 失败、gate PASS（各规则计数不变）。
  - **自查审查轮 2（2026-10-24，换角度复查：验证轮 1 两修复自身边界 + 密码弹窗/代理加载/Compose 消费面等轮 1 未覆盖路径）**：
    1. 轮 1 两处修复复核通过（槽装载/消费时序、换源 clamp、设置组重拷贝挂点）。
    2. **发现并修复 1 处低级边界**：密码弹窗取消路径（`onPasswordDismiss`）只清 `passwordDialogTarget` 不清 pending 槽——「换源偏好线路>0 + 目标频道在密码组 + 用户取消弹窗」后槽残留，下次手动换台误用该线路。修复：取消时 `vm.setPendingSourceIndex(0)`。
    3. 其余核查通过：LiveProxyLoader 回调全部经主线程 Handler（StateFlow 更新无线程问题）；LiveScreens/LiveChannelRows 不直接读 `playingSourceIndex`（消费全部经由 overlay 快照/Activity 门面，无 UI 面新增耦合）；`replayCurrentChannelAfterTakeover` 恢复路径读取 VM 持久索引与旧 bean 行为等价；SearchSourcesState 的 `build()` 在对象装载时执行（首个消费者触碰时配置必已就绪，三处 refresh 挂点覆盖旧静态全部触发路径）。
    4. 复验：全绿（构建/1399 单测/gate PASS）。按审查收敛口径：轮 1 抓 2 个回归（高/中），轮 2 抓 1 个边界（低），严重度单调下降——若下一轮无新的中高级发现即达终止线。

## 片 3：VodInfo 所有权收敛（核心片，受 playback spec 管制）（2026-10-24 已完成，见本节末执行记录）

**前置**：重读 `skill/avbox-playback-service-spec.md` §2/§3；本片触及挂摘协议与进度落盘语义。

1. 定义不可变 `PlaybackSelection(flag, index, lineKey…)`；PlaybackController 持 `StateFlow`，引擎内直改点（PlaybackRetryDelegate 等）改经唯一 transition 方法。
2. `seriesFlags/seriesMap` 深拷贝交接：`preparePlaySession` 不再按引用共享；Detail 页与音乐页各自消费快照。
3. `App.setVodInfo/getVodInfo` 退役：`RemoteServer`、`WatchProgressStore`、`DetailViewModel` 改读 controller 投影（经既有 `PlaybackService.peek()` 通道）；`PlaybackPorts.currentVod` lambda 一并收编（§6 O6 定形态）。
4. TYPE_REFRESH 回写路径删除：`updatePlayerCfg` 只留一条路径；`PlaybackStarter.kt:278` 死事件删除；画质列表（TYPE_PLAY_QUALITY）改 controller 状态流。
5. DetailViewModel 展示态提取为不可变 DetailUiState 字段入 StateFlow；`revision/bumpRevision` 与 `@Suppress("UNUSED_EXPRESSION")` 退役（§2.9 补丁销账）。
6. MusicPlayerActivity 直改 `vod.playIndex/series.selected/playNote` 消除（改引擎意图方法或订阅 selection 流）。
- **验收**：构建+单测+**真机走查**：点播起播/换集/换线路/自动重试换线、音乐页往返、后台播放恢复、历史续播、投屏起播（对 playback spec §4 清单挑相关项）；udf_gate 第 6 条归零。
- **回退面**：6 小步各自可回退；最大风险是 App 单槽拆除后的交接窗口（§5 R2）。
- **执行记录（2026-10-24，6 小步全部落地）**：
  - **3-1 Selection 唯一 transition**：`PlaybackController` 增 `PlaybackSelection(flag, index)` StateFlow 与 `updateSelection()`（唯一写入点：更新引擎私有 vod 镜像 + 派生 playNote + 发射；startSession 播种初值）。三个写者全部路由：`PlaybackRetryDelegate` 自动换线（Host 新增 `updateSelection`）、`PlayContainer.playNext/playPrevious`（`vod.playIndex++/--` → `scheduler.updateSelection`）、`MusicPlayerActivity.playAt`。画质与持久化配置同步收编为流：`publishQuality` 改发 `quality: StateFlow<JSONObject?>`（原 EventBus×2）；新增 `persistedPlayerCfg: StateFlow<String?>`（`publishPersistedPlayerCfg` 单点写 vod.playerCfg + 发射，替代 ControlListener「直写 + 总线回写」双路径）。
  - **3-2 深拷贝交接**：`preparePlaySession` 的 `seriesFlags/seriesMap` 改经 `copySeriesFlags/copySeriesMap`（逐项重建 VodSeriesFlag/VodSeries 实例）；**顺带修复隐性共享缺陷**——详情页倒序（`Collections.reverse`）与 selected 标志不再穿透引擎/音乐页副本。
  - **3-3 App 单槽退役**：`App.setVodInfo/getVodInfo/vodInfo` 字段全删；`PlaybackPorts.currentVod` lambda 改投引擎（`PlaybackService.peek()?.controller()?.vod()`）；`RemoteServer` 弹幕参数、`DetailViewModel.onPlaybackStarted` 改同一投影。**登记行为差异（边界）**：服务进程销毁后 `currentVod` 返回 null（旧槽保留最后值）——仅影响"服务已死且进度条目数超限"时驱逐保护不再豁免最后播放项；服务存活期间语义不变（§5 R2 交接窗口经真机走查 §4-19/20 验证）。
  - **3-4 TYPE_REFRESH 全路径删除**：`PlaybackStarter:155`（VodInfo 载荷；DetailViewModel 改订阅 `controller.selection` 流，身份守卫沿用 `syncPlayingVodInfo` 的 id/sourceKey/flag/index 校验）、`:278`（String 死事件）、`PlayContainerControlListener`（JSONObject 载荷；改 `persistedPlayerCfg` 流订阅：info.playerCfg 同步 + insertVod + 失效）三路径全删；`onRefreshEvent` 的 VodInfo/Int/JSONObject 分支删除（Int 分支本就无 poster，死代码一并清除），仅保留 TYPE_PLAYBACK_STARTED（片 5 与总线清理一并处理）。
  - **3-5 revision 退役**：`revision` 流删除，代之以 `vodUi: StateFlow<VodUiSnapshot(info, rev)?>`（`publishVodInfo` 统一发射，`bumpRevision` 内部化为 rev+1，9 处调用点 API 不变）；DetailContent/DetailEpisodes 改消费 `vodUi?.info`，DetailSections 删除无效订阅（chips/related 自有流驱动）；revision 参数链与 3 处 `@Suppress("UNUSED_EXPRESSION")` 全部拆除——**r4 归零**。
  - **3-6 音乐页直改消除**：`playAt` 的 `vod.playIndex` 直写与 `series.selected` 循环删除（经 `controller.updateSelection`；selected 循环无人消费引擎副本，直接删除）。
  - DetailViewModel 新增 `observeEngineState()`：250ms 轮询等待引擎就绪后订阅 selection/quality/persistedPlayerCfg 三流（引擎出现即停，VM 生命周期内一次，登记为实现取舍）。
  - 验证：`assembleDebug` 全绿（3 轮编译迭代，含括号失衡/Host 缺 override 两处实施中自查修正）；`testDebugUnitTest` 1399 例 0 失败；`udf_gate` 基线下调 **r4 3→0、r6 6→0、r1 23→18、r2 61→60**，复验 PASS。**真机走查（§7 片 3 行：点播起播/换集/换线路/自动重试、音乐页往返、后台播放恢复、历史续播、投屏、预载）为发布前置，待执行**。改动停留工作区未 commit（累计 42 个文件）。
  - **自查审查轮（2026-10-24，全量 diff 通读 + 「流生命周期 vs 会话生命周期」专项推演，抓出 2 个跨会话污染回归并当场修复）**：
    1. **回归①（数据污染，严重，已修）**：`persistedPlayerCfg` 流不随会话重置——详情页 B 的 VM 订阅时立即收到上一会话 A 的持久化配置，guard `info.playerCfg != cfg` 成立 → B 的 vodInfo.playerCfg 被 A 的配置**覆盖并写库**（旧总线仅在配置变化时通知，无跨页泄漏）。修复：startSession 末尾播种 `_persistedPlayerCfg.value = vod?.playerCfg`（B 订阅首发射即自身配置，guard 短路）。
    2. **回归②（陈旧画质，已修）**：`quality` 流跨会话残留——B 页订阅即得 A 内容的画质列表，选集面板画质胶囊显示错误选项；顺带发现 `qualityResult` 在 startSession 从不清空的存量隐患（玩家面板同受影响）。修复：startSession 清空 `qualityResult/_quality`（画质本属会话级，换集后由解析重发）。
    3. **登记语义变化（窄窗口，可接受）**：起播后的剧集倒序不再影响引擎「下一集/上一集」顺序（旧共享引用会传播后续倒序；深拷贝仅在会话创建时快照当前顺序，选集点击/新会话仍取最新倒序状态）。
    4. 其余核查通过：`updateSelection` 三调用点的 vod 非空前置换挡成立；selection 流对 VM 自发选集 no-op（等值守卫）、自动换线现在在**切换时刻**即同步详情页（旧为下一次 startPlay 才发总线，时序提前属改进）；StateFlow 合并对快速换集取终态正确；`onPlaybackStarted` 投影与旧槽为同一 preview 实例；`updateQualityOptions(null)` 沿旧 null 载荷路径安全；DetailHero 的 `vm.vodInfo` 直读由 pageState 重组驱动无陈旧；音乐页 `syncHistory` 的 playNote 重写与 updateSelection 派生同值（幂等保留）；r7 计数不变（`updateSelection` 不匹配 set*/mark* 模式）。
    5. 修复后复验：构建全绿、1399 例 0 失败、gate PASS（r1=18/r2=60/r3=0/r4=0/r5=0/r6=0/r7=21）。
  - **自查审查轮 2（2026-10-24，针对"有严重漏网"的定向深查：两修复自身的正确性 + startSession 全调用路径 + 广播面身份守卫）**：
    1. 轮 1 两修复闭环验证通过：`startSession` 仅由 `PlaybackEngine.setData` 触发，而 `publishQuality` 只从解析结果（PlaybackFetch.handlePlayResult）发出且**每次 setData 必重取流**（重播路径不经 setData、画质保留）——会话清空画质安全；`initPlayerCfg` 不回写 `vod.playerCfg`（构建本地 JSONObject），cfg 播种值与 VM info 同源同值、guard 必短路，后续 persist 发规范化串与旧行为一致。
    2. **发现并修复 1 处存量缺陷（本片流版本原样继承，登记为顺带修复）**：`persistedPlayerCfg`/`quality` 为进程级广播，双详情页叠放时（A 在下层存活）B 调播放器设置会把 B 的配置**写进 A 的库记录**（旧 EventBus TYPE_REFRESH(JSONObject) 同样无身份守卫广播，缺陷同源）。修复：两个 collect 均加 `playing.id/sourceKey == info` 身份守卫（selection 的 syncPlayingVodInfo 原有守卫已覆盖）。
    3. 其余核查通过：`updateQualityOptions(null)` 沿旧 null 载荷路径安全（空列表短路）；`peek()` companion `engine` 无 @Volatile 的跨线程读与旧 `App.vodInfo` 裸字段同竞争特征（不引入新风险，深度修复涉引擎线程模型归 spec 管制，登记不动）；音乐页 next/prev 读引擎镜像时序正确（transition 先于 play）。
    4. 复验：构建全绿、1399 例 0 失败、gate PASS。真机走查新增一条：**双详情页叠放（A→B 经相关视频），B 内调播放器设置/画质，返回 A 检查 A 的设置与画质胶囊未被 B 污染**。
  - **自查审查轮 3（2026-10-24，新角度：「引擎生命周期 × 订阅存活性」——流化相对进程级总线的特有陷阱）**：
    1. **发现并修复 1 处真实回归（中-高，已修）**：`controller` 是每个 engine 实例的 `private val`，而引擎存在"释放置 null → 按需重建"路径（`releaseEngine`/`onEngineReleased` 空闲自释放 → `engine(context)`/预热新建）。旧实现订阅首个见到的 controller 后即终——详情页退后台期间引擎自释放（预热关）、回前台重建后，VM 仍订阅已释放引擎的旧 controller，selection/quality/persistedPlayerCfg **三流静默失联**（详情页不再同步换集/换线/设置；旧 EventBus 进程级广播无此问题）。修复：订阅循环改为「引擎身份监视 + 重建即重订阅」（500ms 身份轮询，StateFlow 重订阅立即补发当前值，不丢状态）。
    2. 其余核查通过：`vodInfo` 直赋残留仅 `publishVodInfo` 内部一处（统一入口成立）；DetailActivity 对 `vm.vodInfo` 的四处读取均为一次性事件驱动（点击/生命周期），无订阅需求；`startSession` 调用点唯一（`PlaybackEngine.setData`）、`publishQuality` 仅从解析结果发出、`initPlayerCfg` 无回写——轮 1/2 修复的闭环在引擎重建语义下依然成立（重建后新 controller 各流由重订阅补发初值）。
    3. 复验：构建全绿、1399 例 0 失败、gate PASS。真机走查再增一条：**详情页退后台超过空闲释放阈值（预热关）→ 回前台起播 → 换集/自动换线后详情页选中态仍能同步**。
    4. 收敛记账：轮 1 抓 2（高/中）、轮 2 抓 1（存量顺带修复）、轮 3 抓 1（中-高，流化特有陷阱）——仍有中高级发现，终止线未达；但三轮发现的性质收窄（存量缺陷→流化生命周期陷阱），下一轮若无新发现或仅剩口味级即收尾。
  - **自查审查轮 4（2026-10-24，新角度：Compose 重组正确性逐条 + 事件投递时序等价 + 数据键时序；结论零新发现，终止线达成）**：
    1. **时序等价**：selection 流在 Main.immediate 上的发射对同线程收集者近似同步投递，与旧 EventBus MAIN threadMode 的同步投递特征一致；且 `syncPlayingVodInfo` 为纯 UI 状态同步（无 insertVod/DB 写）——自动换线时同步时机从「下次起播」提前到「切换时刻」不产生任何副作用（登记为改进）。`onPlaybackStarted`（TYPE_PLAYBACK_STARTED 仍走总线）与 selection 流的竞态方向正确：提前同步使等值守卫更一致。
    2. **数据键时序**：音乐页 playAt 的进度清理顺序（clear 旧 progressKey → updateSelection → play 设新 key）与旧代码逐字一致。
    3. **Compose 重组面**：DetailScreen 无 revision 残留；DetailContent/EpisodeSheet 各自订阅 vodUi 独立重组（不依赖父级重组）；DetailSections.SourceSection 移除订阅后由 chips/related 自有流驱动；hero 区由 pageState 驱动（轮 3 已核）。quality 胶囊点击链路（switchQuality→SelectQuality→engine quality()）在会话清空/重发语义下自洽。
    4. **残留核查**：`vodInfo` 直赋仅 publishVodInfo 内部；RemoteServer 的 App import 仍服务于 getDashData（合法保留）。
    5. **收敛判定**：连续一轮零 阻断/高/中 级发现——按审查收敛终止线，片 3 代码审查收尾。剩余风险面移交真机走查（§7 片 3 行 + 三轮新增的三条场景：跨会话配置/画质不串台、双详情页叠放不污染、引擎重建后同步存活）。

## 片 4：引擎会话面收敛（最重走查片）（2026-10-24 批次 A+B+C′ 完成；C″ 剩余项延后，见执行记录）

1. 起播编排下沉：`controller.startVideoPlayback(url, headers, opts)` 单一入口（opts 显式携带 trackMemoryKey/进度口径差异）；四处副本（PlayContainerViewBridge/HeadlessView/MusicPlayerActivity/LivePlayActivity）改调，页面桥只留 UI 副作用。
2. scheduler setter 网格 → 意图方法族（`requestSourceSwitch(request)` 内部折算 markStoppedForSourceSwitch/setPendingInherit/…）；`PlaybackAttemptState` 改不可变 data class + 集中单一复位（C5）。
3. 成对/三重标志单一归属：`ownedPlaybackKey`、`castPrepareOnly`、`previewMode`（三份→PlayerUiState 一份）、`lifecyclePaused`、`playbackStarted`；`contentUrl/progressPhase` 的陈旧判定改**会话代际（gen）**，删字符串比对与播种补丁。
4. playerCfg 不可变化：applyXxx 产生新实例 + 单一 `onPlayerCfgChanged` 事件；删 `updatePlayerCfg` 回调链与 `vod.playerCfg` 双写（衔接片 3-4）；持久化由 controller 在事件内完成。
5. playState 副本链收敛：ComposeLiveController 删自存副本、订阅 `playStateFlow`；`AppPlayerView.setPlayerState` 直推通道与 `dispatchPlayState` 双通道**默认不动**（承重，§5.1），仅评估（§6 O3）。position 面同步收敛：`state.position` 副本链与 `PlaybackService` intent extras 透传改为引擎侧单点填发，UI 统一从单一 flow 取值（onProgressTick 轮询保留为心跳源）。
6. Tip 双源消除：唯一归 PlayerUiState，删静态 `PlayerTipBridge` 单槽 listener（音乐页交接窗口一并消除）。
7. 音频 only 四重奏 → 引擎侧单一 `AudioOnlyState`（OFF/FORCED/CONFIRMED）事件驱动迁移。
8. `PlaybackEngine.session` 双持有消除（统一经 controller.currentSession 或 playbackKey Flow）。
9. `IsoSessionSignal/SlowOpenSignal` 并入会话状态（只动持有位置，**ISO 解析链零触碰**）。
10. LivePlayerManager 配置三副本并入 LivePlayUiState.settings 快照，manager 退化为无状态应用器。
11. 指令面双入口与反向命令收敛：`VideoPlayerController` 直打 videoView 与 `PlayContainer.ensurePlaybackActive` 并存的两个内核入口统一经 HostApi/controller；controller 状态回调里的业务命令（ERROR→errReplay、COMPLETED→playNext、skipEnd 自动连播）上移引擎侧判定（PlaybackRetryDelegate 已有大部分），控制器只展示；`PlaybackEngine.onPlayStateChanged` 的回写环一并解除。
- **验收**：构建+单测+**真机走查 playback spec §4 清单 1–20 全量**。
- **执行记录（2026-10-24，批次 A+B 落地；批次 C 按裁量延后）**：
  - **批次 A（独立收敛，已完成）**：
    - **4-6 Tip 双源消除**：`PlayerTipBridge` object（全局 Compose state + 单槽 listener）删除；`PlayerTipState` 移入 PlayContainerOverlays.kt 顶层；点播页 tip 唯一归 `PlayerUiState.applyTip`（overlays 直写，绕行全局的 listener 转发机制删除）；音乐页 tip 归 `MusicPlayerState.tip`（桥经 `activity.ui` 写入，MusicTipOverlay 参数化）——**音乐页↔详情页交接期 tip 互踩窗口随全局单例消失**。
    - **4-8 session 双持有消除**：`PlaybackEngine.session` 字段删除；controller 暴露 `currentSession()/clearSession()`；enterLive/enterLiveState 的"只清引擎侧"双持有不同步从结构上消失（`clearSession()+clearStartedContent()` 单点）；attach 日志与 discardStartedContentOf 改经 controller。
    - **4-10 LivePlayerManager 三副本收敛**：两份同步 JSONObject（default/current，实际恒同值）删除；收敛为 `sessionDecode`（会话覆盖值）+ KV 兜底（`effectiveDecode()`）——**实施中自查纠正一次**：初版把解码切换写 KV 会泄漏到全局 EXO_DECODE 影响点播（旧语义为会话级内存值），已改为语义严格等价；scale 维持旧的持久语义（KV 直写）。manager 退化为无状态应用器（外部接口签名不变），设置面板快照机制（LiveSettingsSnapshot 打开时重建）本就位。
    - **4-9 静态信号并入：调整至批次 C**（信号的自然归位是 AttemptState，与 4-2 reducer 化合并处理更安全）。
  - **批次 B（标志单一归属，已完成）**：
    - `previewMode` 三份→`PlayerUiState.previewMode` 一份（PlayContainer/VideoPlayerController 字段删除，6 处读点改经 state：showLockView/手势/字幕字号×2/动作判定/歌词字号×2）；
    - `lifecyclePaused` 两份→`PlayerUiState.lifecyclePaused` 一份（hostPause/hostResume/日志改经 controller 单点读写）；
    - `castPrepareOnly` 两份→`PlaybackAttemptState` 一份（beginCast/endCast/setData 判定/日志 5 处改经 `scheduler.isCastPrepareOnly()`）。
    - **核查判定（登记修正）**：`ownedPlaybackKey`↔`startedPlaybackKey` 非重复状态——前者是页面接管归属标记、后者是进度已起播键，`ownsEngineContent` 刻意比对两者，保留双值（实施中误删已即时恢复）；`contentUrl/progressPhase` 代际化**延后**——属 ProgressUiGate 承重语义（spec §7.4 修复，有单测锁口径），改代际是行为重设计，留真机走查稳定后单独评估。
  - **批次 C（4-1 起播编排下沉 / 4-2 AttemptState 不可变化 / 4-4 playerCfg 不可变化 / 4-5 playState 副本链与 position 面 / 4-7 AudioOnlyState / 4-11 指令面与反向命令 / 4-9 信号并入）：延后为独立后续批次**——以上均为结构性重编排且受 spec §4 走查 1–20 全量管制；在批次 A/B 未真机验证前硬推将放大回归风险且审查困难，按"每小步独立可走查"原则拆批（建议 A/B 走查通过后逐项推进，4-2/4-9 合并、4-1 优先）。
  - 验证：批次 A/B 各自 `assembleDebug` 全绿（3 轮编译迭代，含音乐桥 receiver/误删恢复/残留读点三处自查修正）；`testDebugUnitTest` 1399 例 0 失败；`udf_gate` PASS（各计数不变：r1=18/r2=60/r3–r6=0/r7=21——批次改动不触 set*/mark* 模式）。真机走查：spec §4 清单 1–20 全量为片 4（含 C 批完成前已改部分）的发布前置，重点补 A/B 相关项（tip 显示与音乐页交接、直播进退/接管、预览模式进出、后台暂停恢复、投屏只解析）。
  - 改动面：累计 52 个文件（+642/−550），停留工作区未 commit。
  - **自查审查轮（2026-10-24，全量 diff 通读 + tip 交接/enterLive 清理/LivePlayerManager 等价/hostResume 时序四专项，抓出 1 个高严重度回归并当场修复）**：
    1. **回归（高，已修）：后台恢复播放失效**——`hostResume` 开头既有的 `setLifecyclePaused(false)`（:168，旧代码原有）会先清 state，而本批把恢复条件改读 `state.lifecyclePaused`——读取时恒为 false → `view.resume()` 永不执行（后台暂停回前台不续播）。修复：在 :168 清理**之前**捕获 `resumeFromLifecycle` 标记，语义与旧容器字段严格等价。根因：收敛时只看了字段读写点、漏看同方法内既有的 state 清理调用——"沿用既有写法须连带抄其调用序"的自查项再犯，登记。
    2. 其余核查通过：**tip 交接语义**——跨页残留 tip 随全局单例消失（旧：详情页 tip 会残留到音乐页显示；新：各页自持，登记为改进）；`onTipStateChanged` 的 mHandler.post 投递语义与旧 listener 链一致。**enterLive 清理语义**——`clearSession()` 同时清 controller 侧（旧只清引擎侧、controller 侧残留旧会话到下次 setData）；消费面核查（tracker 的 startedPlaybackKey 独立、discardStartedContentOf 在 live 态旧值同为 null）等价，且新语义更正确。**LivePlayerManager**——逐值等价（init/getter/change/buildConfig 与旧双 JSON 恒同值推导一致），sessionDecode 与旧 currentPlayerConfig 同为 Activity 级生命周期。**previewMode**——`ensurePlayContainer` 的 `setPreviewMode(true)` 在构造完成后调用（mController 已就绪），null 守卫为遗留防御不触发；6 处读点路由完备（编译器验证无残留）。**castPrepareOnly**——5 处判定经 `scheduler.isCastPrepareOnly()` 与旧双源 OR 语义等价（两源恒同值同置）。
    3. 复验：`assembleDebug` 全绿、1399 例 0 失败、gate PASS（计数不变）。真机走查重点补：**后台暂停 → 回前台续播**（本轮修复的直接验证项）。
  - **自查审查轮 2（2026-10-24，新角度：轮 1 修复全路径验证 + LivePlayerManager 重载路径深挖 + 日志口径；零新缺陷，终止线达成）**：
    1. 轮 1 修复（`resumeFromLifecycle` 先捕获后清理）全路径验证通过：hostPause 只在 `view.isPlaying` 时置位（audio-only/预览退出路径不置位）、连续 hostResume 不重复 resume、与旧容器字段语义逐路径等价；**顺带修正日志口径**——`echo-p2 hostResume` 的 lifecyclePaused 字段原打印清理前值，收敛后在清理后读取恒 false，改打 `resumeFromLifecycle`（避免走查诊断误读）。
    2. **登记行为变化（低，方向更一致）**：旧 `LivePlayerManager.init` 在频道列表重载（切换直播源经 initLiveChannelList→initPlayer）时把 default 的 exo 覆写回 KV 值并重置 current——**直播解码的会话内选择被重载清除**；新实现 `sessionDecode` 存活于重载（Activity 重启仍回 KV 默认，与旧一致）。
    3. 其余核查通过：tip 消费端（详情页渲染读 PlayerUiState.tipMsg 系、音乐页 MusicTipOverlay 经调用点读 state.tip 订阅完备）；`mHandler == null` 边缘（旧全局态同步更新 vs 新丢弃——仅容器未初始化/销毁期可达，可忽略）；VideoGestureActionsImpl/PlayerActionsDelegate 的 `host.state` 可见性；引擎 attach 日志在首次附着前 currentSession 为 null（旧同为 null）。
    4. 收敛记账：轮 1 抓 1（高，已修）→ 轮 2 零新缺陷（1 处日志口径微修 + 1 项行为差异登记）——片 4（A+B 批）代码审查达终止线；剩余风险移交真机走查（spec §4 全量 + 本片专项：后台恢复续播、tip 交接、直播换源后解码选择保留）。
  - **批次 C 执行记录（2026-10-24，C-1/C-5a 落地；C-2′+C-9 核查后登记不做；C-4/4-7/4-11 延后）**：
    - **C-1 起播编排下沉**：`PlaybackController.startVideoPlayback(view, url, headers, forceExoPlayer, releaseKernel, trackMemoryKey, renderRebuildGuard, startLineTimeoutIso)` 单一入口统一"内核与进度主干"（render 检查/kernelErrored 检查/consumeKernelRebuildRequired/KernelReusePolicy.decide/release/saveProgress/setProgressKey/setTrackMemoryKey/markContentStarted/setUrl/换线超时/skip/replay|start）；三个 VOD 副本（PlayContainerViewBridge/HeadlessView/MusicPlayerActivity.startPlayback）改为薄转发，**页面 UI 副作用按计划留在各桥**（hidePauseRoot/hideTip/onContentUrlSet/resetSpeed）；差异经参数显式化（trackMemoryKey：页面真实/headless 与音乐空串；render 检查：页面恒查/音乐带 audioOnly 守卫/headless 不查；换线超时：页面 isoHeaders/音乐 false/headless 不启动）。**直播版（LivePlayActivity.playChannel）保持页面侧**——其编排语义正交（无进度面、有快照切换、reuse 判定输入不同），登记不强行并入。副带收益：`udf_gate` r7 观察项 21→20（页桥的 scheduler.markContentStarted 直调随下沉消失，基线已下调）。
    - **C-5a ComposeLiveController playState 副本消除**：自存副本删除；`setPlayState` 纯转发 listener；手势门控（`gesturePlaybackState`，副本唯一消费点）改读 `videoView.playState`（spec 认可的内核同步面单一真源读法）。`AppPlayerView.setPlayerState` 直推通道与 `dispatchPlayState` 双通道按 §5.1 承重约定**未动**（O3 维持开放）。
    - **C-2′+C-9 核查后拆分处置（2026-10-24 用户质询后修正）**：§2.4 登记的信号违规实为两半——①"静态 object"（写点侧）：`IsoSessionSignal` 写点在 ISO 解析链（BdavDataSource/IsoResolver，IO 线程）、`SlowOpenSignal` 写点在内核播放线程与 OkHttp 数据源层，移入主线程域 AttemptState 意味着写者接线穿过引擎构造链（触碰 C1–C14 雷区）+ 跨线程写主线程域对象（AttemptState 现仅 castAborted 一个 @Volatile，并入要加五六个跨线程字段，线程故事恶化）——object 恰是跨层探测信号的正确载体，**保持现状**；②"**被 UI 直读**"（读取侧）：初版审查把两半捆在一起"保持现状"属过度保守，已补修——controller 新增 `isIsoEscalated()` 投影（controller 本就读取该信号），DetailActivity/MusicPlayerActivity 两处 UI 直读改走投影、删除对信号类的 import，**§2.4 的 UI 直读违规面清零，ISO 链零触碰**。AttemptState 四个复位方法（beginSession/beginNewPlay/userSelfRescue/resetAutoRetryLadder）是**层级化复位**（会话层/播放层/用户救援层/重试梯层级，字段出现在多个方法=多层级语义而非重复清单），清单"合并"要么丢层级语义要么变成字段×层级矩阵（更复杂）；完整不可变化 reducer 重写涉及全引擎数百个 `st.xxx =` 赋值点（spec §3.3 反复修的"会话标志残留"bug 的敏感面），且重写并不能消除"新标志要决定归属哪些复位层级"的原始负担（那由审查纪律+走查清单承担）——**保持现状，完整 C5 归长期**。
    - **C-4（playerCfg 不可变化）/C-7（AudioOnlyState）/C-11（指令面双入口与反向命令上移）：延后**——分别涉及配置面大面积重写、音乐页交接链路（spec §3.8 敏感）、错误处理编排重构；片 3 已消除 playerCfg 双写路径，剩余项在真机走查稳定后逐项评估。
    - 验证：C-1/C-5a 各自编译全绿；最终 `assembleDebug` 全绿、1399 例 0 失败、`udf_gate` PASS（r7 21→20 下调）。改动面累计 54 文件，零 commit。真机走查：spec §4 清单 1–20 全量 + C 批专项（**换集/换线/自动重试的起播路径**——编排统一直接受影响面；直播手势门控）。
  - **批次 C 自查审查轮（2026-10-24，全量 diff 通读 + 编排逐项等价 + 注入链核查；零新回归，1 项存量缺陷候选登记）**：
    1. **编排统一逐项等价验证通过**：三副本的分支结构（else-if release/save → 独立 if 在 sameContent 蕴含 reuse 下等价）、参数三态映射（trackMemoryKey/render 检查含 mediaPlayer 前置/换线超时）、进度与键序（progressKey→trackKey→markStarted→setUrl→timeout→skip→replay|start）与旧逐字一致；页面副作用的时序偏移（hideTip/onContentUrlSet 移到编排返回后）无行为影响（同步代码无插入窗口，进度回调不可能在 replay 与返回之间触发）。清理 4 个未用 import（页桥/音乐的 KernelDecision/KernelReusePolicy）。
    2. **顺带发现存量缺陷候选（登记不动）**：`ComposeLiveController.videoView`（private var）**全仓无赋值点、HEAD 亦然**——恒 null 使 `gesturePlaybackState()` 恒 false，直播页亮度/音量手势门控疑似从未生效（onFling 换台路径待真机确认）。C-5a 改写与旧代码在现实行为上逐字等价（两者同被 null 短路）；启用手势（注入 videoView）属行为变化，超出语义等价边界——**登记，真机验证直播手势是否工作后再定**（若失效，修复为 `initVideoView` 注入一行）。
    3. **日志口径漂移登记**：headless 起播的 rebuild 日志前缀由 `PlaybackEngine: rebuild errored kernel on start (headless)` 统一为 `echo-kernel-error`——走查日志判读时注意。
    4. 复验：全绿（构建/1399 单测/gate PASS，r7=20）。
- **回退面**：10 子项独立；2/3 建议放本片最后。

## 片 5：全局清理与遗留退役（2026-10-24 EventBus 全退役完成；配置单入口/音乐 VM 化/杂项延后，见执行记录）

1. EventBus 死代码删除：ServerEvent 三个事件与 post 点、两个死常量、String 载荷死事件。
2. 搜索结果单通道：TYPE_SEARCH_RESULT 改 SourceChannel/SharedFlow，token 校验合并为一份。
3. 设置类广播退役：PreferenceSettingsPage 两事件 → KV Repository Flow（AppThemeState 模式）；CollectViewModel/HistoryViewModel 改订阅。
4. DanmuSheets→PlayContainer UI→UI 总线改方法/共享状态。
5. `LIVE_API_URL/API_URL` 收敛 ConfigRepository 单入口 `switchVodSource/switchLiveSource`（内部完成 KV+hosts+失效+历史+重载编排）；`shouldReloadLiveConfig` 补丁退役。
6. MusicPlayerActivity 抽 MusicPlayerViewModel（历史/进度/收藏写入经 VM；MusicPageBridge 反向写消除）。
7. LiveChannelSourceLoader/LiveProxyLoader 下沉 LiveConfigRepository/VM；`ApiConfig.channelGroupList` 改不可变投影，不再暴露内部可变 List。
8. `VideoPlayerController` 容器外散落字段归位（纯 UI 派生入 PlayerUiState；门控类随片 4-3）；`PlayerUiState` 内嵌行为 lambda 改 actions 对象（状态只存数据）；`LiveCatchupController/LiveChannelSourceLoader` 控制器间互持解除（时移动作并入单一 actions 入口）。
- **验收**：构建+单测+配置切换三场景走查（订阅切换、直播源切换、搜索页）；udf_gate EventBus 条目归零。
- **执行记录（2026-10-24，EventBus 全退役落地 = 片 5 核心验收达成；5-6/5-7/5-8 延后）**：
  - **新建 `event/UiEvents.kt`**（`SharedFlow<UiChange>` 显式流事件源，密封类型 CollectLayoutChanged/HistoryChanged/PlaybackStarted）——替代总线的进程级一次性事件通道，data 层与 ui 层均可依赖（event 包无方向问题）。
  - **搜索结果单通道（5-2）**：`SourceResultParser` 三处 `EventBus.post(TYPE_SEARCH_RESULT)` → `searchResult.postValue(data)`（该字段本就是 SourceChannel，与对称分支一致——总线是历史绕路）；SearchViewModel/DetailViewModel 的 `@Subscribe` 改为 init 订阅各自 `searchResult.flow`（token 守卫原样保留）；HomeViewModel 的 PartitionLoader 通道本就分离，零改动。
  - **设置类广播退役（5-3）+ 数据层事件流化（5-5）**：PreferenceSettingsPage 3 post、HistoryWriter 2 post、PlaybackProgress 1 post → `UiEvents.post(...)`；CollectPage（columns 重读）、HistoryViewModel（sweepDuplicates+reloadTick）、DetailViewModel（onPlaybackStarted）改订阅 `UiEvents.changes` 过滤各自类型；三处 EventBus register/unregister 全删。
  - **DanmuSheets 回调化（5-4）**：`DanmuSettingSheetState` 增 `onSettingsApplied` 回调，打开点（PlayContainerControlListener）注入 `container.applyDanmuSettings`，sheet 内 post 改直调（与既有 onOpenSearch/onReset 同形态；PlayerUiState 回调 actions 化归 5-8）。
  - **死代码删除（5-1）**：`ServerEvent.kt`、`RefreshEvent.kt` 整类删除（零引用后）；SearchReceiver 保留 backActivity 分支仅删 post；RemoteServer 两处装饰性 post 删；PlayContainer 的 @Subscribe（含两个死常量分支）与 register/unregister 删。**greenrobot.eventbus 依赖本体保留**（删依赖需评估动态 jar 面，登记后续）。
  - **测试同步**：`SourceResultParserRoutingTest` search 分支断言反转为"searchResult 通道投递"（新口径锁定）。
  - **5-6（配置 Repository 单入口）/5-7（音乐页 VM 化）/5-8（容器字段归位、lambda actions 化、互持解除）：延后为片 5 后续批次**——本批已触及 15 文件，追加将放大审查面，按"每批独立可走查"拆批。
  - 验证：`assembleDebug` 全绿、1399 例 0 失败；**`udf_gate` r1 18→0 下调、复验 PASS**。真机走查重点：搜索多源并发聚合、设置页改列数/历史合并/无痕后对应页即时刷新、弹幕设置应用、历史写入后列表刷新、详情页"已观看"标记。
  - **5-6 执行记录（2026-10-24 补充批次，配置源单入口落地）**：新建 `api/ConfigRepository.kt`（`vodUrl()/liveUrl()` 读 + `switchVodSource(url): Boolean`（KV+Api 历史+followLive 联动+线路列表条件清理，返回同址标志供 AppBootstrap 分支）+ `switchLiveSource(url)`（KV+线路历史/收藏条件清理+clearLiveHosts+invalidateLiveConfig——状态一致性仪式单点化））；三个用户切换写点改调：AppBootstrap.switchVodSubscription（bootstrap 编排保留原处）、ConfigManageViewModel.applyLiveSource/applyLiveFollowVod（两者仪式差异在空/非空公式下自然统一）、LivePlayViewModel 设置内换源（重载编排+BootGuard 守卫留调用方——重载策略差异是有意语义）。**实施中自查修正 1 处并顺带修复 1 处既有漂移**：统一公式的线路收藏保留判定初版取窄版（isLiveApiLineUrl），会让 ConfigManage 路径误清线路收藏；统一取宽版（isLiveApiLineHistory = 线路源主址或列表成员）——ConfigManage 行为不变，**LivePlayVM 路径修复"切回线路源主址时误清线路收藏"的既有 bug**（两写点原各用一版判定，正是仪式漂移的实证）。装载器写点（ConfigLoader 落盘/ApiConfig 清空）保留为装载器内部状态同步（登记）；**`shouldReloadLiveConfig` 保留**——它兜的是"装载失败/进程重启后内存与 KV 脱钩"而非仅用户切换，其退役依赖写点收敛后的运行时证明（真机验证后评估）。
  - **5-6 自查审查轮（2026-10-24，逐项等价比对 + 判定口径消费面核查；零新回归）**：① vod 段读序（oldApi/oldFollowTarget 先读→setApiHistory→put）与 followLive 读取时机（switchVodSource 之前，避免读到新值）与原实现逐字一致；② 三写点仪式映射等价（setLiveApiHistory 与 put 的顺序无关性成立——两者操作不同 KV 键；applyLiveFollowVod 的无条件清列表 = 空目标公式分支）；③ 唯一行为差异 = 登记的窄→宽判定修复，且**宽口径获装载器佐证**——ConfigLoader:383 清理判定本就是 `!isLiveApiLineUrl && !isLiveApiLineSource`（宽判定的展开式）；④ `isLiveApiLineUrl` 其余 4 处消费面（isLiveApiLineMode 显示判定/装载器/ConfigManagePage/BootGuard）均为读取/显示路径，不受统一口径影响；⑤ import 卫生：清理 LivePlayViewModel/AppBootstrap 的未用 HistoryHelper import。复验：全绿（构建/1399 单测/gate PASS）。
  - **5-7 执行记录（2026-10-24，音乐页 VM 化——按计划边界：数据层写入经 VM + Bridge 反向写消除，未做全状态搬迁）**：新建 `MusicPlayerViewModel`（`isCollected` suspend 读、`toggleCollect`（IO 协程+容错）、`clearWatchProgress`/`syncHistory`（**同步调用保持与原时序零差**——WatchProgressStore.clear 本身是入队 submit，主线程直调等价））；MusicPlayerActivity 的 5 类数据层调用（收藏读/写、进度清理×2、EpisodeTotals/HistoryWriter）全部改经 VM，Activity 数据层引用清零；`lyricSource` Activity 私有字段删除、入 `MusicPlayerState.lyricSource`（片 4-A1 tip 同模式），`MusicPageBridge.clearLyric` 的反向写私有字段消除（改写 `activity.ui.lyricSource`）。**范围登记**：播放编排/引擎交互/其余 Activity 状态未迁 VM（`MusicPlayerState` 已是集中容器，全量搬迁收益边际化、触碰面全文件——完整 VM 化归长期）；实施中括号切分失误截断类体一次，编译器即时暴露已修复。
  - **5-7 自查审查轮（2026-10-24，等价比对 + 修复完整性核查；零新回归）**：① toggleCollect 方向等价（collected=true→delete 的分支映射）与乐观翻转时序一致；clearWatchProgress/syncHistory 同步转调与原时序零差；② 括号失误修复完整性——函数数 67/67 与 HEAD 一致，净 −46 行全为删除的仪式代码，无内容丢失；③ `lyricSource` state 化的重组影响面核查——UI 不直接读该字段（纯歌词去重键），mutableStateOf 写无订阅者触发，行为与原 var 等价；:398 协程内读的跨线程特征与原 var 相同（无恶化）；④ Bridge 经 `activity.ui` 访问的可见性（同类体内嵌套 private class）合法。复验：全绿（构建/1399 单测/gate PASS）。
  - **5-8 执行记录（2026-10-24，裁剪执行：时移双入口合一 + 死字段删除；lambda actions 化与散落字段整体归位登记延后）**：
    - **时移双入口合一**：LiveCatchupController 的两个纯转发方法（onTimeshiftSeek/onTimeshiftTogglePlay → overlay）删除，Activity 同名方法改直调 overlay——时移 seek 从此单一入口（LivePlayActions），消除"既可从 Activity 也可从 catchup 进"的分裂；catchup 构造的 overlay 依赖保留（startCatchupReplay 的 ticker/scheduleOverlayHide 是真实使用，非转发）；LiveChannelSourceLoader 的 overlay 依赖保留（hideSwitchChannelSnapshot/showTime/showNetSpeed 为单向编排 UI 副作用，无环——登记）。
    - **showParseFlag 死字段删除**：核查发现该字段写而无读（:513 唯一写、全仓零读点）——片 2 盘点时的"与 state.showParseRow 双写"实为死写，直接删除。
    - **PlayerUiState 行为 lambda 改 actions 对象（登记延后）**：该重构与 PlayerUiState 整体纯化（60+ public mutableStateOf，r2 基线）属同一工程，单独迁移 lambda 是半吊子；改动面 10+ 文件、收益为架构纯度——与容器整体纯化一并归长期（片 5-4 新增的 onSettingsApplied 维持与同 sheet 既有回调的同形态，不扩大不收窄）。
    - **VideoPlayerController 其余散落字段（登记延后）**：speedOld/skipEnd/isClickBackBtn/gestureSwitch/canChangePosition/enableInNormal 为控制器内部簿记（无跨组件读者，非共享状态），入 state 收益低；contentUrl/progressPhase 已随片 4-3 延后（承重）。
    - 验证：`assembleDebug` 全绿、1399 例 0 失败、gate PASS。真机走查补：直播时移拖动/暂停恢复（单入口直调路径）。
  - **5-8 补充批次（2026-10-24，用户质询后重做延后判定：散落字段迁移落地；lambda actions 化摸底后维持原判、论据升级为实证）**：
    - **散落字段入 PlayerUiState（落地）**：`canChangePosition/enableInNormal/gestureSwitch/speedOld/skipEnd/isClickBackBtn` 六字段迁入 state（VideoPlayerController 容器外声明删除，setter 实现/手势门控/进度回调/返回键去抖改读写 state）；**消除两处 delegate 直写宿主字段**（PlayerConfigDelegate:200 `host.speedOld = value`、PlayerActionsDelegate:271 `host.isClickBackBtn = ...` → 均改 `host.state.xxx`）；`activityCache` 保持私有（Activity 引用缓存，入 Compose 状态容器属泄漏反模式——登记）。这些字段无 UI 读者，mutableStateOf 迁移无重组开销差。
    - **PlayerUiState lambda actions 化（维持原判，摸底实证）**：①回调实现者 6/7 在**容器侧**（PlayContainer/Subtitles/Overlays 构造并注入容器逻辑），而 PlayerActions 实现者（PlayerActionsDelegate）挂 controller、无容器通道——迁移须新开 controller→容器通道，调用链从「渲染→state 回调→容器」变为「渲染→actions→host→listener→容器」，**变长**；②渲染链双参数（state, actions）仅达 PlayerOverlay 层，全部 sheet 子组件（PlayerParamsSheet/DanmuSheets/SubtitleSheets/CastSheet 渲染）无 actions 参数，迁移还需三层穿参；③唯一 controller 侧构造的 Picture/Params 组（PlayerConfigDelegate）迁移可直达，但 equals 语义将开始生效（lambda 参与 data class equals，现状每次重建即每次重组）——行为变化需真机验证重组时序。综合：容器侧维持"actions 随状态下发"的既有形态（该形态在片 5-4 已按同构扩展），controller 侧组留待 PlayerUiState 整体纯化一并评估——**维持 5-8 原判，论据由"同工程捆绑"修正为上述三条实证**。
  - **片 5 总结（2026-10-24 全部完成）**：5-1～5-8 落地（5-6/5-7 补充批次 + 5-8 裁剪执行），核心验收 **EventBus 全退役（r1=0）**、配置源单入口、音乐页数据层入 VM、时移单入口；三项延后登记（lambda actions 化/容器字段整体归位/contentUrl 代际）。
  - **自查审查轮（2026-10-24，全量 diff 通读 + 总线→流时序语义专项 + replay 重放面 + 删除面孤儿调用核查；零新回归）**：
    1. **时序语义等价验证**：EventBus MAIN（主线程 post=同步、IO 线程=Handler 异步投递）与 SharedFlow+`Dispatchers.Main.immediate` collector（主线程 tryEmit=同步 resume、IO 线程=dispatch 异步）逐场景等价；FIFO 顺序两者一致。
    2. **`SourceChannel.replay=1` 重放面核查（关键）**：迟订阅者会重放最后一条——逐消费点确认 SearchViewModel/DetailViewModel 的 `searchResult` 均为 **VM 实例新建的 SourceViewModel 的新通道、init 即订阅**，无旧值重放窗口；HomeViewModel 分区通道行为未动。
    3. **删除面孤儿调用核查**：`applySubtitleTextSize`（4 处活调用：字幕 sheet 回调/接管路径）与 `checkDanmu`（取流结果/起播/桥转发 4 处活调用）均不依赖被删 handler——死分支删除无孤儿；`onSettingsApplied` 构造点唯一且已注入；`libs/quickjs` 等动态 jar 面对两个删除的事件类零引用。
    4. **登记语义微差（低）**：`UiEvents` 缓冲 16（tryEmit 满则丢）vs EventBus Handler 队列无界——三事件类型均为低频（历史写入手动保存/设置点击/起播），实际不可达上限；若未来出现高频事件源再评估 DROP_OLDEST。
    5. 复验：构建全绿、1399 例 0 失败、gate PASS（r1=0）。

# 5. 风险与反模式

## 5.1 承重约定（不得顺手破坏）

| 承重约定 | 出处 | 本计划口径 |
| --- | --- | --- |
| `dispatchPlayState`「先 controller 同步回调、后 flow emit」顺序 | playback spec §2.3 | **默认不动**（片 4-5 仅加流式订阅面）；若最终合并须单独立项并改写 spec |
| `KernelReusePolicy` 判定点位置 | playback spec / 20261014 计划 | 片 4-1 只下沉编排调用方式，判定点与判定顺序不挪动 |
| ISO 包装层不变量 C1–C14（endTracks 扣留、TrackOutput 不得扣数据、声明轨必须有 Format…） | iso-playback spec §4 | 片 4 **零触碰** ISO 代码；IsoSessionSignal 只改持有位置 |
| 进度面单一串行队列契约 | 20261014 计划 | 片 3-3 WatchProgressStore 改读投影不改队列语义 |
| 挂摘协议 / 服务回收 / 媒体会话 | playback spec §3 | 片 3/4 每小步对照 §4 清单走查 |
| 契约层签名逐成员不变 | kotlin-migration spec §3.2 | `com.github.catvod.**` 全程不触碰 |

## 5.2 风险登记

- **R1 承重约定破坏**：对策 = §5.1 表 + 任何触及承重约定的子步单独立项。
- **R2 交接窗口**（片 3 拆 App 单槽）：后台播放、服务回收（§4-19）、投屏「只解析」（§4-20）、预载场景下投影读取时序——真机走查必含这四项。
- **R3 大爆炸**：六片 + 子项 commit 粒度，每片过构建/单测门；片间可暂停。
- **R4 行为等价例外**：两处顺带修复（搜索 sheet 陈旧、音乐页返回选中集越界）属**显式登记的行为变化**，commit 说明必须标注；其余一律语义等价改写。
- **R5 真机依赖**：片 3/4 无法纯单测闭环；无设备时该片不启动。
- **R6 bean 消费方核对**：VodInfo/LiveChannelItem/SourceBean 改不可变或删字段前，核对动态 jar、Gson 序列化、KV 存量数据的消费面（kotlin-migration spec Gson×data class 规范）。
- **R7 Compose 重组回归**：revision 退役与列表 copy 更新涉及重组范围变化，走查关注 Detail 页滑动/换集闪烁与列表性能。
- **R8 gate 误报**：白名单沿用 baseline + 只准下调 + 行级例外登记理由模式。

## 5.3 反模式禁令

- 不引入新框架（不做 MVI/Redux 类第三方库，全部用既有 StateFlow/Channel 手段）。
- 不为统一而统一：纯 UI 状态留 Composable 是正确的（§2.0 口径），不迁 VM。
- 不借重构改行为/加功能/动文案（i18n 门禁不受影响）；全库零注释规则持续有效。
- 不做跨片联动大 commit；每片结束跑 gate 并下调基线。

# 6. 过渡登记与开放问题

## 6.1 需随片同步改写的 spec 口径

| 口径 | 位置 | 触发片 |
| --- | --- | --- |
| 「指令面 = PlaybackHostApi → controller 单向」由约定变结构保证 | playback spec §2.3 | 片 4 |
| 「App.getVodInfo 单槽已清一半」→ 全清 | playback spec §2.3 | 片 3 |
| 状态管理条款引用本计划宪法 | mobile-ui-spec 通用原则 | 片 1 起逐步 |
| `PlayerTipBridge`/`IsoSessionSignal`/`LivePlayerManager` 相关 as-built 描述 | playback spec §3 组件职责表 | 片 4 |
| 若 O3 决定合并 dispatchPlayState 双通道 | playback spec §2.3 | 另立项 |

## 6.2 开放问题

- **O1** EventBus 终态：本计划倾向片 5 后清零（gate 归零）；若发现遗漏消费方则保留 RefreshEvent 壳并登记。
- **O2** MusicPlayerActivity VM 化时机：定在片 5；若片 3 交接复杂度超预期可提前。
- **O3** `dispatchPlayState` 双通道是否合并：承重，默认不动；待片 4 流式订阅面上线后的真机数据再定。
- **O4** `LiveChannelItem.sourceIndex` 是否被 jar/序列化消费：片 2-5 实施前核对（R6）。
- **O5** udf_gate 第 7 条（scheduler setter 计数）是否升级为阻断项：片 4 后评估。
- **O6** PlaybackPorts 替代形态：显式接口注入 vs player 层订阅数据层 Flow——片 3-3 实施时二选一定案。
- **O7** `PlaybackService` companion 静态可变单例（engine/owner/pendingStart/instance + peek()）：playback spec §2.1 有意设计（同步取用避免竞态），本计划默认不动；片 4 完成后若仍有收敛需求再单独立项。

# 7. 走查清单汇总（发布判据）

| 片 | 构建与单测 | 人眼走查 | 真机走查 | gate 判据 |
| --- | --- | --- | --- | --- |
| 0 | — | — | — | gate 运行、基线绿 |
| 1 | assembleDebug + testDebugUnitTest | Detail 换集/换线路/全屏进出/音乐页返回；Home 分类切换；Records 编辑多选；搜索历史增删清 | — | 规则 2/3 基线下调（流私有化 + UI 直写消除） |
| 2 | 同上 | 直播：换台/换线路/密码组/EPG/时移/设置/字幕；搜索：源选择、切源后 sheet 新鲜度；收藏卡片 | — | 规则 5（override var）下调 |
| 3 | 同上 + 投影读取单测 | — | 点播起播/换集/换线路/自动重试；音乐页往返；后台播放恢复；历史续播；投屏起播；预载（对应 playback spec §4 相关项） | 规则 4/6 归零（revision hack 与 App 单槽销账） |
| 4 | 同上 | — | playback spec §4 清单 1–20 全量 | 规则 7（ui 包 scheduler setter）归零 |
| 5 | 同上 | 订阅切换、直播源切换、搜索页三场景 | — | 规则 1（EventBus）归零 |

每片完成立即跑构建与单测（不得攒到片尾）；涉及取值口径变化的补单测锁口径。全计划完成的发布判据 = 宪法 C1–C5 满足 + §2.9 补丁清单销账 + udf_gate 全仓绿 + 走查表全绿。

# 8. 终态差距清单（2026-10-24 走查收官后盘点）

片 0–5 完成后，数据流的**方向性**问题（谁写状态、走什么通道、是否单源）已全部解决；以下 15 项是距"教科书式 UDF"（不可变状态 + 唯一 reducer + 状态无行为 + 事件单向）的剩余**纯度**差距。总规模约 60–70 文件、4500–6000 行，≈ 再做一个同等规模的计划。

## 8.1 引擎会话层（6 项，30–40 文件，2500–3500 行，高投入高风险；#1/#2/#3 已收口，见 §8.9）

| # | 差距 | 现状 | 规模 |
| --- | --- | --- | --- |
| 1 | PlaybackAttemptState 完整不可变化 | **已收口（2026-10-24，§8.9）**：31 字段改 `data class` + 四个命名 transition 保留分层语义、写走 `updateAttemptState` 单一通道（60 处写点） | 全清单最大单项 |
| 2 | playerCfg 不可变化 | **已收口（2026-10-24，§8.9）**：唯一写入口 `commitPlayerCfg(mutate, persist)`（克隆-改写-替换 + 推 UI + 单点持久化） | 中大 |
| 3 | AudioOnly 四重奏 | **已收口（2026-10-24，§8.9）**：`player/state/AudioOnlyState`（OFF/FORCED/CONFIRMED + `AudioOnlyRules` 纯函数），内核死链已删 | 中 |
| 4 | contentUrl/progressPhase 双副本 | **已收口（2026-10-24，§8.10）**：身份判定迁会话代际（内核 `AppPlayerView.contentGen` 每次 setUrl 自增 + 控制器 `adoptCurrentContent()` 两协议点原子武装）；字符串比对与接管播种补丁退役；ProgressUiGateTest 5 例重锁新口径 | 中 |
| 5 | 指令面双入口+反向命令 | **已收口（2026-10-24，§8.9）**：20 处 scheduler 直写收编为意图方法族（r7 归零）；COMPLETED/ERROR/片尾 et 三条链路由引擎侧派发（顺带修复中间批引入的三项断线回归）；死副本与死成员清除 | 中大 |
| 6 | VodInfo bean 本体不可变 | **已收口（2026-10-24，§9）**：data class 全字段 val + copy 化写点（实例替换 + session 槽 adopt）；Gson roundtrip 15 例保持绿；selected 冗余状态删除；键派生语义逐字节保持 | 大（20+ 文件） |

## 8.2 容器/UI 层（4 项，~20 文件，1600–2200 行，机械低险；2026-10-24 第二组已收口，见 §8.7/§8.8）

| # | 差距 | 现状 |
| --- | --- | --- |
| 7 | PlayerUiState 写权限+行为 lambda | 60+ public mutableStateOf（r2=60 即此）+ sheet 状态内嵌约 25 个回调（actions 化的摸底结论见片 5-8 补充批次记录） |
| 8 | LivePlayActivity 属性门面 | 30+ setter 语法糖直通 vm.updateXxx——存储单点、写路径发散 |
| 9 | MusicPlayerActivity 完整 VM 化 | 数据层写入已入 VM（5-7），播放编排/引擎交互仍在 Activity |
| 10 | 手势层直写系统状态 | VideoGestureActionsImpl 直改 window brightness/AudioManager（低危，教科书要求经 effect 通道） |

## 8.3 全局架构（5 项，8–10 文件，400–500 行，小活）

| # | 差距 | 现状 |
| --- | --- | --- |
| 11 | AppBootstrap 位置 | 全局启动协调器在 ui.page 包、直写 KV |
| 12 | PlaybackPorts lambda 注册表 | 数据层经可变 lambda 槽反向读播放器层（应显式注入） |
| 13 | ApiConfig 可变暴露 | channelGroupList getter 返回内部可变 List、liveSettingGroupList public var（"不可变投影"未做） |
| 14 | shouldReloadLiveConfig 补丁 | **已退役（2026-10-24，§8.8）**：写点成对性静态证明后删除 |
| 15 | greenrobot 依赖删除 | 代码引用已清零，build 依赖仍在 |

## 8.4 两处实证例外（不达标但正确，不算欠账）

- `IsoSessionSignal`/`SlowOpenSignal` object：写点在 ISO 解析链/内核播放线程/IO 线程，静态跨层信号是正确载体（并入 AttemptState 会引入跨线程写并触碰 C1–C14 雷区——片 4-C 摸底记录）。
- `ownedPlaybackKey`↔`startedPlaybackKey`：页面接管归属 vs 进度已起播键，语义不同的两个标记（`ownsEngineContent` 刻意比对）——片 4-B 判定记录。

## 8.5 实施建议（性价比分层）

- **第三组随手做**（各半天级）；**第二组挤牙膏**（#7 每收敛一个流 gate r2 基线下调一个，进度天然可见，可随日常迭代分批）；**第一组立项做**（压在 spec §3.3 最敏感面，需真机回归能力兜底，等真实回归问题或大版本窗口）。
- 若一步到位，顺序：#13 → #3 → #7 分批 → #2 → #5 → #4 → #8/#9 → #1 → #6。
- 收益性质：防未来腐化（结构挡住新的 `st.xxx =` 直写），非修当前缺陷。

## 8.6 第三组执行记录（2026-10-24 完成：#11/#12/#13/#15 落地，#14 维持）

- **#13 ApiConfig 不可变投影**：`channelGroupList` getter 改返回 `Collections.unmodifiableList(liveChannelGroupList)`（类型 MutableList→List）；`liveSettingGroupList` public var → 私有可变字段 + 只读投影 getter；外部写点经片 2/片 5 已清零（核实全仓仅读）。实施中自查修正一次正则误替换（getter 定义自我覆盖），编译器即时暴露。
- **#11 AppBootstrap/SearchSourcesState/BootGeneration 位置归位**：三文件从 `ui.page` 搬至 `api` 包（全局启动协调器/搜索源状态/boot 代际工具归配置域）；9 个 ui.page 同包静默引用文件补 import + 2 个全限定引用文件 + 1 个测试文件。同包静默引用经两轮编译暴露逐轮补齐。
- **#12 PlaybackPorts lambda 槽→有名接口**：`PlaybackDataPorts` 接口（currentVod/isLiveMode/discardStartedContentOf/onProgressKeysCleared）；App 启动挂全量实现（桥接 PlaybackService 静态 peek），PlaybackService.installDataPorts 幂等兜底；7 处调用点 `?.invoke()` 改接口调用。实施中自查修正：初版 App 只挂 currentVod 槽致接口未实现编译错——改为 App 全量注册。
- **#15 greenrobot eventbus 依赖删除**：`app/build.gradle.kts` + `libs.versions.toml` 条目删除；源代码零引用。**真机冷启动验证待补**（模拟器已关闭；构建+1399 单测全绿佐证，下次模拟器可用时装包冷启动+进源播放一次即闭环）。
- **#14 shouldReloadLiveConfig：维持登记**——运行时证明前置未足（真机直播换源仅部分覆盖），写点收敛（5-6）后需实际使用期观察再评估退役。
- 验证：`assembleDebug` 全绿（3 轮编译迭代）、1399 例 0 失败、`udf_gate` PASS（计数不变）。本组约 20 文件（含 3 文件搬迁+import 波及）。**第三组收官。**
  - **第三组自查审查轮（2026-10-24，unmodifiable 视图写审计 + 搬迁残留 + 时序等价；零新回归）**：① **关键审计通过**——unmodifiable 视图上的结构性写（.clear/.add/.addAll/.remove 经 getter）全仓为零（内部 clearLiveChannelGroups 是字段替换而非视图写；消费者全部即取即用，无跨替换持有旧视图的路径——最担心的 UnsupportedOperationException 运行时风险不存在）；② liveSettingGroups 重命名完整性——ApiConfig 内 liveSettingGroupList 仅剩 getter 定义一处；旧包路径零残留；③ PlaybackPorts 时序等价——原实现 3 数据端口在首次 engine() 后可用（此前 null 跳过），新实现 App 启动即全量挂（peek() null 时方法返回安全默认）——引擎未建期的行为逐方法等价（跳过/默认值）；单一注册者（App 挂全量 + Service 幂等兜底）；@Volatile 跨线程 ✓；④ eventbus 唯一残余风险 = 动态爬虫 jar 运行时引用（宿主源码零引用、生态惯例 spider jar 仅依赖 gson/okhttp/js）——冷启动+进源播放验证待补（已登记）。

## 8.7 第二组执行记录（2026-10-24 完成：#10/#8/#7/#9 落地，残留已登记）

- **#10 手势层直写系统状态 → effect 通道**：新建 `player/controller/SystemEffectDispatcher.kt`（`SystemEffect` 密封类型 `Brightness`/`Volume` + 单写者 dispatcher）；`VideoGestureActionsImpl` 只产出效果对象（`dispatch(SystemEffect.Brightness(target))`），window attributes 与 `AudioManager.setStreamVolume` 全部收敛到 dispatcher 单点。逐值等价：clamp 区间、`lastApplied*` 去重、读取 window 属性/音量流的时机与方法内早退顺序（`playerActivity()==null` 先于 height 检查）全部保持原序。
- **#8 LivePlayActivity 属性门面清零**：删除 **13 个死门面**（`pageState/snapshotVisible/snapshotBitmap/resolutionText/resolutionVisible/showTimeOn/showNetSpeedOn/timeText/netSpeedText/gestureHintText/tsPosition/tsDuration/channelInfoUi`——门面写点已随片 2 的控制器直写 VM 而失效，仅剩声明）；**16 个活门面**拆成「只读投影 `val ... get() = vm.state.value...`」+ VM 具名事件方法，新增 26 个方法（`onPlayStateChanged/setFullscreen/setRotating/setLandscapeNow/setOverlayVisible/toggleOverlay/setTimeshiftBack/setShiyi/setLookBackIndex/setEpgList/setEpgChannelName/setCanCatchup/showEpgSheet/hideEpgSheet/hideSettingsSheet/setPlayingGroupIndex/setPlayingChannelIndex/setPlayingChannel/setTappedGroupIndex/expandChannelGroup/collapseChannelGroup/requestChannelScroll/confirmPasswordGroup`）；Activity 内 15 处 `updateXxx { copy }` 直写同步改调，`selectedChannelGroupIndex` 等 4 处读写面改投影。**语义等价**：每个事件方法体与原 setter 的 `updateXxx{copy}` 逐字同构，`toggleOverlay()` 返回新值替代「先写后读」。
- **#7 写权限收敛（两半）**：
  - **VM 公有可变流清零（r2 60→0）**：7 个 VM 的 60 条 `val x = MutableStateFlow(...)` 全部改 `private val _x` + `val x = _x.asStateFlow()`，内部写点 208 处（Detail 51 / Search 16 / ConfigManage 52 / Following 14 / History 15 / Home 55 / PartitionList 5）随之改 `_x`。用一次性 codemod 执行 + 编译器逐点校验（唯一漏网 `this.title.value` 手工修正）——错配只会编译失败、不会静默改义；字符串模板内的 `${sources.value...}` 一并正确改名。
  - **overlay/sheet 子状态结构私有**：`PlayerUiState` 的 9 个 sheet/对话框字段与 `MusicPlayerState.castSheet` 加 `private set`（编译期阻断外部赋值），新增 24 个方法（`openSelectDialog/dismissSelectDialog/openParamsSheet/dismissParamsSheet/selectParamsTab/.../openCastSheet/dismissCastSheet/setEpisodeSheetVisible`）；24 处写点全部改方法调用——**渲染层直写 9 处（PlayerOverlay 8 + MusicPlayerScreen 1）归零**，其余 15 处在容器/控制器侧（状态所有者）。`openParamsSheet` 接受可空（`buildParamsSheet()` 本就返回可空，`refreshParamsSheet` 的 null 早退语义不变）；`setEpisodeSheetVisible` 命名避开与属性 setter 的 JVM 签名冲突。
- **#9 音乐页 VM 化（按 spec 边界裁剪）**：`MusicPlayerState` 容器从 Activity 移入 `MusicPlayerViewModel.ui`（Activity 改只读 getter 引用）；播放模式初始化/轮换（KV 写入）与**播完决策**（SINGLE 重播 / LIST 环绕且单曲重播 / ORDER 顺延，含空队列早退）收进 VM（`initPlayMode/cyclePlayMode/songCompletedAction` 返回 `SongAction`），收藏开关（写库 + 乐观翻转）整条进 VM。**写入面同步迁移**（容器入 VM 的必然要求，否则 Activity 换成违规写者）：新增 `applyTick/applyPlayState/setPlaying/setPosition/setQueueIndex/applyMeta/beginLyric/applyLyric/clearLyric/setTip/clearTip/setArtwork/refreshCollected`，`positionTick`、播放态映射、`refreshMeta`、`syncLyric`、Bridge 的 tip/artwork/歌词回调全部改调——**Activity 对 `ui.*` 的 30+ 处直写清零**（`grep '\bui\.\w+\s*='` 结果为空）。**范围登记**：引擎 I/O（`startVideoPlayback`/`play`/`detach`/`PlaybackHostApi` 实现/职位心跳）按 playback spec §3 挂摘协议**留在 Activity**——页面是引擎挂载宿主，迁入 VM 会把 View 引用带进 VM（反模式），且触碰 spec 最敏感面；`refreshMeta` 仍在 Activity 读取 controller/vod 快照后经 `applyMeta` 上交。**行为差异登记（低，方向更好）**：状态容器随 VM 存活，Activity 因配置变更重建时 UI 状态不再归零（`onDestroy` 仍清 tip）。
- 验证：`assembleDebug` 全绿（4 轮编译迭代，含 `private set` 引发的 15 处所有者写点与 1 处命名冲突）；`testDebugUnitTest` **1399 例 0 失败 0 错误**；`udf_gate` **r2 60→0** 已写入基线、复验 PASS（r1=0/r3–r6=0/r7=20）；全量重编译无新增未用 import/未用变量告警；行尾核查抓出新建文件 `SystemEffectDispatcher.kt` 首建为 CRLF、已转 LF（其余全 LF）。本组约 22 文件，停留工作区未 commit。
- **自查审查轮（2026-10-24，等价性逐点 + codemod 安全面 + VM 生命周期）**：
  1. **codemod 误改面判定**：改名只作用于 `name.value/.update/.compareAndSet`——若同名局部变量被误改，必然出现「未解析 `_x`」编译错（已全绿）；`asStateFlow()` 首建即定型，读侧 `.value`/`collect`/`combine` 语义不变，`private` 化不改变实例同一性（订阅者持有的是同一 StateFlow 视图）。
  2. **门面映射等价**：逐条比对写点的 `updateXxx{copy}` 与原 setter 体，字段与层级一一对应（Activity 侧 39 处事件方法调用覆盖原 setter 全部写点）；`playChannel` 内 `setPlayingChannel` 后立即回读 `currentLiveChannelItem` 成立（`_state.update` 同步，主线程单次投递）；返回键三分支（EPG→设置面板→全屏）判定顺序与旧一致。
  3. **包裹式改写的括号核对**（`openXxx(XxxState(...) { lambda })`）：TrackSelector/PlayContainerSubtitles 三处的尾随 lambda 归属与 `})` 位置逐行确认，未把 try 块或外层函数体圈进去。
  4. **`private set` 边界**：`PlayerUiState` 现由 9 个字段的编译期只读 + 59 个仍可写字段并存；`MusicPlayerState` 全字段仍公有可写（写者已全部收敛到 VM，属约定而非编译保证）——两处**残留已登记**（§8.2 #7 剩余面），与 §8.5「挤牙膏」口径一致。
  5. **音乐页状态随 VM 存活**：`init()` 每次重建都会重读 playMode/collected，`onDestroy` 清 tip，`positionTick` 由 Activity 重挂——重建后不残留播放进度假值；未发现依赖「Activity 重建即清空」的消费方。
  6. **本轮抓到并已修的结构遗漏（中）**：首版只把容器搬进 VM、写入仍留在页面——等于把 30+ 处原本合法的写入改成 C2 违规面。修法＝写入面同步迁移（见 #9 记录），并复核迁移等价：`positionTick` 的读序、`applyPlayState` 的 when 分支集合与 duration 赋值次序、COMPLETED→`onSongCompleted` 的先后、`beginLyric` 的「同源早退 + 先置 source 再异步装载」、`clearLyric` 的无条件幂等（`mutableStateOf` 同值不触发重组）逐条与旧实现对齐。
  7. 收敛判定：修复后无阻断/高/中 级残留，3 项低风险登记（VM 存活语义、#9 引擎交互留页、#7 剩余字段）——第二组代码审查收尾，风险移交真机走查。

## 8.8 第二组残留收口（2026-10-24 完成：#7 剩余字段、#9 状态字段、#14 退役、O5 升阻断）

- **#7 收尾：PlayerUiState 可写字段 59→0**：68 个字段全部 `private set`，新增 **55 个 `updateXxx` transition**（与既有 `open*/dismiss*/setEpisodeSheetVisible` 同构）；外部写点 **93 处**全部改调（`InfoOsdText`/`PlayerActionsDelegate`/`PlayerConfigDelegate`/`VideoGestureActionsImpl`/`VideoPlayerController`/`PlayContainer`），测试源 18 处 + 3 个 `PlayerUiState().apply {}` 构造块一并改调；顺手删死字段 `showing`（全仓零读写）。
  - **命名口径（必守）**：transition 一律 `update` 前缀——`private set` 仍会生成私有 setter，`fun setXxx` 与之 JVM 签名冲突（本轮在 `MusicPlayerState` 实测撞了 5 处：`updatePlaying/updatePosition/updateQueueIndex/updateArtwork/updatePlayMode/updateCollected`）。
  - **实施面**：一轮 codemod（声明加 `private set` + 生成 transition + 改写写点）+ 编译器兜底，5 处手工修正：尾随大括号的 RHS（`lazy { Runnable { ... } }`）、两处多行 RHS、`parseListVersion++` 自增、方法块一度误插到文件末尾的另一个类（由编译错误定位后搬回）。
- **#9 状态字段收口：MusicPlayerState 可写字段 17→0**：全部 `private set` + 18 个 transition（`applyTick/applyPlayState/applyMeta/beginLyric/applyLyric/clearLyric/applyTip×2/clearTip/updateArtwork/updatePosition/updateQueueIndex/updatePlaying/updatePlayMode/updateCollected` + cast sheet 开合）；VM 保留同名门面委派，**页面侧 API 零变化**；引擎交互仍留 Activity（playback spec §3 边界，登记不变）。
- **#14 退役**：删 `ApiConfig.shouldReloadLiveConfig()` 与 `LiveChannelSourceLoader.initLiveChannelList` 里配套的冗余前置判定（其第二道 `list.isEmpty()` 本就是唯一有效语义）。**静态证明**：`LIVE_API_URL` 的全部写点均已成对——`ConfigRepository.switchLiveSource`（→ `invalidateLiveConfig`）、`ApiConfig.clearVodConfig/clearLiveConfig`（→ 同）、`ConfigLoader` 两条派生路径（→ `clearLiveConfigResult()` + `loadedLiveConfigUrl=""`）；进程重启时列表本就为空白内存态。故 URL 比较项不可能独立命中，删除即语义等价。真机走查补：直播页三态（配置已加载 / 切源后 / 清空源后）进入即播。
- **O5 收口：r7 观察项升阻断**：`udf_gate.py` 的 `OBSERVE_RULES` 清空、规则说明改写；当前 r7=20 与基线一致（PlayContainer 15 / ControlListener 4 / Overlays 1），此后任何新增直写即 FAIL，片 4 剩余批次推进到归零。gate 复验 PASS。
- **O3 收口（维持不合并）**：`dispatchPlayState`（playState 域：机 + controller 同步 + flow 发射）与 `setPlayerState`（容器形态域 NORMAL/FULL_SCREEN）是**不同状态域**，不存在副本合并；真正承重的是前者内部「先 controller 后 flow」的次序，属 §5.1 不动项。结论：关闭，若日后合并须单独立项并改写 playback spec §2.3。
- **O7 收口（维持不动）**：`PlaybackService` companion 静态单例（engine/owner/pendingStart/instance + `peek()`）是同步取用避免竞态的有意设计（spec §2.1），片 4 完成后无新的收敛需求。结论：关闭。
- 验证：`assembleDebug` 全绿（4 轮编译迭代）、`testDebugUnitTest` **1409 例 0 失败 0 错误**（+10 例新增口径锁）、`udf_gate` PASS（r1=0/r2=0/r3–r6=0/**r7=20 阻断**）；全文件 LF 且无 BOM。本批约 21 文件，停留工作区未 commit。
- **自查审查轮（2026-10-24，等价性脚本核对 + 自愈面追踪 + 测试语义；零新回归）**：
  1. **#14 自愈面核实通过（关键）**：`loadedLiveConfigUrl` 的取值即 `getEffectiveLiveUrl()`（`runLoadLiveConfig` 首行 `val liveApiUrl = apiUrl`），且装载失败路径 `error → setEmptyLiveChannelList → clearLiveChannelList → ApiConfig.clearLiveChannelGroups()` 会清空列表——下一次进入必走 `isEmpty()` 重载；「解析抛异常留下半更新列表」这一唯一理论缺口也被同一条 error 清空覆盖。删除 URL 比较项**无自愈缺口**。
  2. **脚本核对 100% 一致**：55 个 `updateXxx(value:)` 的方法名 ↔ 赋值字段逐一比对零错配；无零调用死方法；无字符串字面量被误改；字段总数 67（原 68 减去已删死字段 `showing`）与 `private set`、transition 覆盖一一对应。
  3. **编译告警面**：本批文件全量重编译无新增未用 import / 未用变量。
  4. **测试语义**：既有 3 个测试文件的 `PlayerUiState().apply {}` 构造改走 transition 后断言语义不变（断言的是派生 getter）；新增 `MusicPlayerStateTest` 10 例锁住迁入口径——ORDER 顺延 / ORDER 末尾仍请求下一集（越界由 `playAt` 守卫）/ LIST 环绕 / 单曲重播 / 空队列 None / SINGLE 重播 / `applyPlayState` 分支与 duration 赋值 / `applyMeta` 播种 queue+waveSeed / 歌词同源去重与陈旧源拒绝 / `applyTick` 快照。
  5. 实施期误伤 1 处并当场修复：新增测试文件缺 `LyricLine` import（编译即暴露）；文件首建为 CRLF，核查后转 LF。
  6. 结论：本批无新回归，收敛。

## 8.9 第一组执行记录（2026-10-24 起）

### #3 AudioOnly 四重奏 → 单一 AudioOnlyState（完成）

- **新建 `player/state/AudioOnlyState.kt`**：`OFF / FORCED / CONFIRMED`。**FORCED = 页面意图**（音乐页/详情页交接前的提前量），**CONFIRMED = 内容已确认为音频专用播放**（音乐页且有音轨，或轨道本身无视频）。状态由 `MusicSessionDelegate.audioOnlyState()` 单点投影（`CONFIRMED` 优先、其次 `FORCED`），意图与确认只作为两个输入（新播放只能清确认、不能清意图），不再是四处各存一份。
- **收归单点**：`MusicSessionDelegate.musicPage`（意图）→ `audioOnlyIntent`；`PlaybackAttemptState.audioOnlyConfirmed` → delegate 私有 `audioOnlyConfirmed`；`PlaybackAttemptState.audioPlayback` → delegate 私有 `audioSessionActive`（音乐会话在跑，与「音频专用」是两个维度，旧代码也用它守通知会话）。`PlaybackAttemptState` 删两字段，`clearSessionFlags()` 只剩 `switchingPlayback`。
- **复位点迁入所有者**：`startSession → onNewContent()`、`beginNewPlay → onNewPlayStarted()`、页面退出/宿主销毁 → `resetAudioSession()`、失败播放/完成丢弃/会话 drop 三处自清。**登记一处时序位移**：`st.audioPlayback = false` 原在 `PlaybackStarter.play` 起播前置位，现随 `beginNewPlay` 复位；唯一差异是 `vs == null` 提前返回路径不再复位，该路径由 `handleResolvePlayUrlFailed → stopMusicSessionForFailedPlayback` 覆盖。
- **内核 audio-only 模式（第 4 个副本）删除**：`PlayerEngine.setAudioOnlyMode/isAudioOnlyMode/audioOnlyRequested`、`ExoPlayer`、`PlaybackViewBridge`+两个实现、`PlayContainer` 全链。真实调用点为零（`2c8e6f6` 引入即未接线），删除零行为影响；`applyRendererEnablement` 只剩 `videoOutputInvalid` 一个输入，日志前缀由 `echo-music audio-only mode=` 改为 `echo-surface renderers`（**走查日志口径变更登记**）。
- **保留**：`PlaybackPage.isAudioOnlyPage()` 是页面能力（引擎 attach 时决定要不要搬渲染容器），不是会话状态，不入状态机；`isAudioOnlyContent()`（纯轨道判定，供详情页决定是否守着小窗）保持为纯查询。
- 验证：`compileDebugKotlin` + `assembleDebug` 全绿、1409 例 0 失败、`udf_gate` PASS（r1–r6=0、r7=20）。

### #2 playerCfg 不可变化（完成）

- **唯一写入口 = `PlaybackController.commitPlayerCfg(mutate, persist)`**：克隆当前 JSONObject → 在副本上 mutate → 整体替换字段 → 推给视图（`view.applyPlayerConfig` → `setPlayerConfig` → 派生 UI 状态）→ 按需持久化（`playerCfgForPersist()` 单点 → `publishPersistedPlayerCfg`）。**存储实例从此不再被原地改写**；`updatePlayerCfg(mutate)` 为「用户改配置（要持久化）」的便捷入口。
- **写点全部收编**（原 9 处原地 `put`）：`PlayerConfigDelegate` 的 speed/scale/player/decode/timeMark、`PlayerActionsDelegate.onTimeResetClicked`、`PlaybackRetryDelegate` 的自动换人/换解码恢复与硬软解回退、`PlaybackConfigDelegate.syncDecodeFromGlobal`。UI 面板侧经 `VodControlListener.updatePlayerCfg(mutate)` 上抛，`PlayContainerControlListener` 转 `scheduler.updatePlayerCfg`；**`updatePlayerCfg()` 回调链与 `PlaybackController.playerCfgForPersist()` 公开面删除**（持久化由 controller 在事件内完成）。
- **顺序等价性**：`applyPlayer`/`applyDecode` 仍先 `setAllowSwitchPlayer(false)` / `setAllowDecodeFallback(false)` 再提交——`playerCfgForPersist()` 会读这两个标记做覆盖，顺序反了会把自动切换值写进用户选择。`switchDecode` 的推送时机由「释放内核后」提前到「释放前」（推送只改 Compose 状态，不碰内核）。
- `initPlayerCfg` 仍走 `setPlayerCfg`（会话初始化构造新实例，非共享改写）。
- 验证：`assembleDebug` 全绿、1409 例 0 失败、`udf_gate` PASS。

### 第一组自查审查轮（2026-10-24，逐条重读 diff + 状态机全迁移路径 + 快照生命周期专项）

1. **修（低，S1）**：`AudioOnlyState.kt` 首建为 CRLF（7 对）——按项目口径转 LF；新增 `AudioOnlyRulesTest.kt` 同样首建 CRLF，一并转。
2. **修（低，S1）**：`isConfirmedAudioOnly()` 原实现「先探测轨道再或确认」——`updateMusicSession` 的日志里因此会把同一份轨道信息读两次（`getTrackInfo` 每次分配）。改为经 `AudioOnlyRules.state(forced, confirmed, trackAudioOnly)` **单点派生**、确认命中即短路；抽出纯函数并补 8 例单测锁口径（意图不清、确认可清、确认跨页面意图释放保持、短路不探测）。
3. **修（中，S2，跨页快照）**：`#2` 改为「替换实例」后，UI 侧 `VideoPlayerController.playerConfig` 变成快照——若提交发生在别的页面（音乐页、或无页面 headless 桥，如 `syncDecodeFromGlobal` / 自动解码回退），回到详情页且**不重放**时面板会读到旧快照（旧实现靠同一实例原地改天然同步）。修法：`PlaybackEngine.attach` 在 `setViewBridge` 之后把当前 cfg 推给新桥（语义 = 接管即对齐；首次 attach 时 cfg 为 null 自动跳过）。
4. **修（中，S2，既有漏点顺带收口）**：`DetailActivity.handOffToMusicPlayer` 的 `playContainer == null` 分支、`MusicPlayerActivity.init` 的 `vod == null` 提前 `finish` 分支，都未复位音频专用意图（旧 `musicPage` 同源漏点：意图残留会让**下一次普通起播被判成音频专用 ⇒ 有声无画**）。两处各补 `setMusicAudioOnly(false)`。
5. **登记（低，行为位移）**：`PlayContainer.alignInstanceConfigOnTakeover` 调 `controller.beginNewPlay()` 时，现在会连带清 `audioSessionActive`（旧实现只清确认、不清「音乐会话在跑」）。窗口内若恰好触发一次 `updateMusicSession`，会掉一次媒体会话并在下一次 started 重建——走查观察「音乐页返回详情页后通知/媒体会话是否闪断」。
6. **登记（低）**：`syncDecodeFromGlobal` 由静默原地改写改为「提交 + 推送」（每次起播多一次 `setPlayerConfig`/`updatePlayerCfgState`，无功能差）；`switchDecode` 的推送时机由释放内核后提前到释放前（推送只写 Compose 状态）。
7. **登记（文档同步）**：`avbox-playback-service-spec.md` §3.8 已改写「内核 audio-only 模式长年零调用、已随本批删除」。
8. **复核通过、未改动**：`PlaybackAttemptState`/`musicPage`/`setAudioOnlyMode`/`isAudioOnlyMode` 全仓零残留（只剩 delegate 内部字段与日志键名）；`PlaybackViewBridge` 仅剩一处 `applyPlayerConfig` 实现调用（controller 提交点）；`PlaybackAttemptState.clearSessionFlags()` 的三个原调用点均改为「clearSessionFlags + resetAudioSession」成对（退出页面 / 宿主销毁 / 失败播放）；`applyPlayer`/`applyDecode` 的 allow 标记仍先于提交（否则自动切换值会覆盖用户选择）；`initPlayerCfg` 未被提交路径污染；proguard/R8 规则零引用已删成员；本批文件全 LF、无 BOM、无新增未用 import（`prune_imports.py` 全 0）；gate 计数不变（r1–r6=0、r7=20）。
9. 验证：`assembleDebug` 全绿、**1417 例 0 失败**（+8 新口径）、`udf_gate` PASS。收敛判定：本轮抓到 4 项（2 低 / 2 中）全部当场修复，遗留 3 项均为已登记的行为位移，无阻断级发现。

### 第一组自查审查轮 2（2026-10-24，换角度：**线程归属 + 提交调用方矩阵**）

1. **修（中，S2，跨线程 UI 写）**：`syncDecodeFromGlobal` 的真实调用路径包含**解析线程**——`PlayUrlResolver` 的 type-2 解析在 `pool.execute { ... host.playUrl(...) }` 里回调（`PlayUrlResolver.kt:339/359`），而 `PlaybackStarter.playUrl` 第一行就是 `host.syncDecodeFromGlobal()`。旧实现只原地改 JSON（与本类既有跨线程特征一致），新实现会经 `view.applyPlayerConfig` 把 Compose 状态写到解析线程，**违反"引擎/视图/控制器 = 主线程"的既定线程模型**。修法：`commitPlayerCfg` 判 `Looper.myLooper()`——主线程直推，非主线程 `runOnUi` 且**在执行期读最新 `playerCfg`**（避免连续提交时旧值覆盖新值）；`playerCfg` 加 `@Volatile` 保证可见性。
2. **修（低，S1）**：`syncDecodeFromGlobal` 由「每次 playUrl 都克隆+推送」改为**值相等即早退**（`exo` 与全局值相同则不提交）——旧实现 `put` 同值本也是空操作，等价且去掉热点链上的无谓克隆/推送；真正变更（用户改全局默认解码）仍会提交。
3. **复核通过、未改动**：提交调用方线程矩阵——面板五处 + 时间清零（主线程）、retry 三处（主线程）、`syncDecodeFromGlobal`（可能解析线程，已按第 1 条处理）；`persist=true` 的调用方全部在主线程（`publishPersistedPlayerCfg` 写 `vod.playerCfg` 不外泄到工作线程）；`PlaybackEngine.attach` 的推送在主线程且读引擎当前值（不存在用旧值覆盖新值）；`AudioOnlyRulesTest` 不依赖 Android；`git diff --check` 无空白错误。
4. 收敛判定：本轮仅 1 项中级（跨线程写）+ 1 项低级（热点链空转），**均为可修且已修**；线程/时序面已穷举到调用方矩阵，无新面。按审查终止线（连续一轮无阻断/高/中），下一轮若仍只剩口味差异即收尾。验证：`assembleDebug` 全绿、1417 例 0 失败、`udf_gate` PASS。

### 第一组自查审查轮 3（2026-10-24，新角度：**读者面穷举 + 写入面收窄 + 告警面**）

1. **收窄（结构，非缺陷）**：`publishPersistedPlayerCfg` 由 public 改 private——持久化写入全仓只剩 `commitPlayerCfg` 一个入口（原公开面是为 `PlayContainerControlListener` 保留的，已随轮 1 删除）。
2. **防御（低）**：`persist` 分支改为经 `scope.launch`（`Dispatchers.Main.immediate`）发布——当前 `persist=true` 的调用方全在主线程（主线程下 `launch` 同步执行、时序不变），但结构上保证「未来非主线程误用」也不会写 `vod.playerCfg`；`view` 同 `playerCfg` 一并加 `@Volatile`（提交可能从解析线程读取桥引用）。
3. **复核通过（本轮新面）**：
   - **面板读取面穷举**：`updatePlayerCfgState` / `buildParamsSheet` / `decodeDisplayedIndex` / `applySpeedWhenReady` / `onProgressTick` 的 `et`（skipEnd 自动连播）全部读推送快照，无遗漏读者；
   - **`mController` 生命周期**：`initView` 创建，`onServiceStopped` / `hostDestroy` 均不置空 ⇒ 面板可见期间推送必达（不会静默丢弃）；
   - **音乐页桥**：`applyPlayerConfig` 委托 headless（空实现），音乐页无面板，推送零影响；
   - **既有单测语义**：`PlaybackCastAbortStateTest` 只断言 `castAborted`，与删除的 `audioPlayback`/`audioOnlyConfirmed` 无关；
   - **告警面**：本批文件零新增编译告警；`git diff --check` 干净；
   - **探测成本**：`audioOnlyState()` 的额外 `getTrackInfo()` 仅出现在「视频内容」路径（音乐页/纯音频在确认后短路），每次状态变化一次，判定可接受。
4. 收敛判定：**本轮零阻断 / 零高 / 零中**（仅 1 项结构收窄 + 2 项防御），符合审查终止线——第一组 #2/#3 的代码审查收尾；剩余风险全部是需真机验证的行为位移与既有面。验证：`assembleDebug` 全绿、1417 例 0 失败、`udf_gate` PASS。

### #1 PlaybackAttemptState 完整不可变化（完成，2026-10-24，用户选 A 方案）

- **形态**：31 字段改 `data class`（全 `val` + 默认值）；四个命名 transition（`beginSession` / `beginNewPlay` / `userSelfRescue` / `resetAutoRetryLadder`）**原样保留**——片 4-C 反对的是"把四份清单合并成一份"，不是一个状态值；分层复位语义因此零损失。另有 `stoppedForSourceSwitch` / `onLineSwitched` / `clearSessionFlags` / `abortCastSession` / `linesExhausted` / `withTriedLine` / `setReuseIntent` / `setReleaseIntent`。
- **需要返回值的"消费型"转移**：`consumeReuseIntent` / `consumeRefreshKeepsProgress` / `clearCastAbort` 返回 `Transition<T>(state, value)`，把"取旧值 + 清标志"压成一次原子投影，避免调用方手工两步。
- **唯一写通道**：`PlaybackController.updateAttemptState(block: (PlaybackAttemptState) -> PlaybackAttemptState)`（内部 `st = block(st)`），`attemptState()` 只返回快照；`st` 加 `@Volatile`（`castAborted` 等仍可能被非主线程读）。五个协作方 Host（MusicSession/Config/Retry/Starter/ProgressTracker）各加一个 `updateAttemptState`，控制器侧统一委派。
- **写点改造**：全仓 **60 处**（主代码 54 + 测试 6）全部迁移；`triedLineFlags` 由可变 Set 改 `Set` + `withTriedLine`（`tryNextLine` 改为本地 `tried` 累加，语义逐字一致）。
- **实测规模修正**：文档原估"数百个 `st.xxx =`"偏高 3–5 倍，实际 54 处——这也是本轮能一次做完的原因。
- **自查抓出 2 个静默回归并当场修复（关键）**：`host.attemptState().clearSessionFlags()`（失败播放路径）与 `host.attemptState().resetAutoRetryLadder()`（线路切换关闭路径）在不可变 API 下属性变化被丢弃 → 前者导致 `switchingPlayback` 不再复位（音乐会话门控卡住），后者导致重试梯子不重置。两处改 `updateAttemptState { ... }`；随后用「全仓 transition 调用点是否都接到新状态」的专门检索复核，无其它丢弃点。
- **读后写复核**：逐方法核对 `val st = host.attemptState()` 快照的生命周期——凡"写后再读同字段"的点改成执行期重读（`autoRetry` 的 `hasRetriedSameUrlOnBoot`/`allowSwitchPlayer`、`tryNextLine` 的 tried 集合、`handleSwitchLinePlayTimeout` 的 `isoSession`、`goPlayUrl` 协程内的三个标志）。
- **测试**：三个既有状态测试改到不可变 API（语义逐条保留），新增 2 例（`triedLineFlags` 被梯子重置清空、`withTriedLine` 不改源实例）。
- 验证：`assembleDebug` 全绿、**1419 例 0 失败**（+2）、`udf_gate` PASS（r1–r6=0、r7=20）。真机走查关注：起播/换集/换线/自动重试梯子、投屏收摊后重播、音乐页会话保持（这些路径的复位节奏现在是"单次 state 投影"）。

### #5 指令面收敛（完成，2026-10-24，用户批准立项方案后实施）

- **批 5-1（意图方法族，r7 20→0）**：ui 包 20 处 `scheduler.set*/mark*` 直写全部收编——`setViewBridge→attachViewBridge`（对齐引擎 attach/detach 词汇，player 层 4 调用点随改）；`setCastPrepareOnly(true)→beginCastPrepare()`（与 `closeCastPrepare` 成对）；setData 接管分支两写→`onSessionTakenOver(userPickedLine)`、新播分支两写→`onNewPlayArmed(userPickedLine, reusePlayerOnSwitch)`（**严格等价**：reuse=false 时不触碰 switchIntent，与原 `if (sameVodSwitch)` 逐字一致）；playNext/playPrevious 编排下沉→`playAdjacentEpisode(forward)`（吸收 clearTriedLines+末集判定/toast+updateSelection+两标志+play；toast 经 bridge，边界文案按方向区分；**ControlListener.playNext 的 preKey 进度清理时序留在原处不动**）；`stopForSourceSwitch` 四连写→`beginSourceSwitch(position)`（吸收 cancelPlayTimeout+stopParse+markStopped+stopMusicSession+setPendingInherit+清 webPlayUrl/webHeaderMap+initParseLoadFound；**重排登记**：清 web/parse 提前到挂起折叠内——switchStopPending 已保证迟到结果丢弃，且 PlaybackStarter.play 开场本就重清）；`replay` 三写→`prepareUserReplay()`、`changeParse` 三写→`requestParse(bean)`；`setM3u8Urls→onM3u8ProxyResolved`、`setPlayDanmu→onDanmuChecked`（事件命名）；**allow 双守卫折入 `commitPlayerCfg` 新参数 `suppressAutoSwitch/suppressDecodeFallback`**（守卫置位在 commit 内首步、先于 clone/mutate——保持 playerCfgForPersist 读标记的承重顺序 §5.1；`updatePlayerCfg` 中继与接口签名同步参数化，mutate 移至尾参保住全仓尾随 lambda 风格）；`VodControlListener.setAllowSwitchPlayer/setAllowDecodeFallback` 两中继成员删除；`setAutoSwitchLineEnabled` 自 HEAD 起零调用的死链整删（PlaybackHostApi 成员 + Engine 空实现 + PlayContainer/MPA 转发 + controller 方法；`st.allowAutoSwitchLine` 字段保留，retry 内部消费、现状恒 true 登记）。**gate r7 20→0 基线下调，复验 PASS。**
- **批 5-2（反向命令接线 + 三条断线恢复）**：`PlaybackEngine.onPlayStateChanged` 在音乐会话守卫后新增引擎侧派发——COMPLETED→`controller.onPlaybackCompleted()`（HEAD 有而中间批丢失的自动连播恢复）、ERROR→`controller.onKernelPlayError()`（错误梯子+终态提示恢复）、片尾 et 跳过经 `onNearEndProgress(pos, dur)` 上抛（VPC.onProgressTick 快照处）→ controller 以 `NearEndRules` 纯函数 + `PlaybackAttemptState.skipEndPending` 一次性闩锁判定，触发同 onPlaybackCompleted 语义。**派发守卫 = `attachedPage()?.isAudioOnlyPage()==true 则跳过`**（headless/音乐页不派发；HEAD 无页面时本也无处理，等价；`handlePlayStateForMusicSession` 对 COMPLETED 返回 false 已实证不兜底，页面级守卫更稳健）。死代码清除：`PlaybackController.ensurePlaybackActive(kernelView)` 死副本、`VodControlListener.errReplay` 成员+实现、`PlaybackAttemptState.toggleReuseIntent`、`PlayerUiState.skipEnd/updateSkipEnd`；skipEnd 闩锁复位点由 VPC.resetSpeed（挂桥 startVideoPlayback）迁至 `beginNewPlay`（两者均随每次内容起播触发，等价且更早）。
- **重大发现（本批修复的前提）**：HEAD 对照证实 `VPC.applyPlayState` 的 `ERROR→errReplay`(:368)/`COMPLETED→playNext(true)`(:370-372)/skipEnd(:418-422) 三分支与替代方法（`onPlaybackCompleted`/`onKernelPlayError`，HEAD 无此二者）在工作区**失联**——上一批准备了引擎侧替换但从未接线、UI 侧分支已删，即"播完自动下一集/内核错误提示重试/片尾 et 跳过"三条链路静默断线（单测无覆盖）；且工作区在 #5 动手前**已无法编译**（`controller.isMusicSessionActive()` 门面调用的委托方法在 MusicSessionDelegate 不存在、VPC when 缺 ERROR 分支非穷尽）——断线批次未跑过构建验证。#5 一并接通并修复编译。**实施修正（对批准方案的偏离，均向更忠实方向）**：①ERROR 路径弃"经 bridge 新增回调"改用现成 `onKernelPlayError`——其内部梯子与 `overlays.errorWithRetry` 逐段同构（started→retryAfterStartedError/autoRetry→stopMusicSessionForFailedPlayback+terminalErrorText+setTip 同 flags），零新增桥面；②`isMusicSessionActive` 门面无消费者且委托缺失，删除（守卫用页面级 `isAudioOnlyPage`）。
- **登记行为变化（R4，顺带修复）**：自动连播/内核错误面/片尾跳过三条链路恢复 = 行为恢复至 HEAD 口径；音乐页内核 ERROR 的处理与 HEAD 可能存在差异（HEAD 走页面 listener 链，现守卫跳过、音乐页自管）——真机走查覆盖。
- **测试**：新增 `NearEndRulesTest` 5 例（et=0/负值不跳、零值守卫、窗内、阈值含等号边界、窗外）+ `PlaybackAttemptStateTest` 闩锁 2 例（默认武装且消费不改源、beginNewPlay 复位；首版断言方向写反被单测当场拦截）。
- 验证：`assembleDebug` 全绿；`testDebugUnitTest` **1426 例 0 失败**（+7）；`null_assert_gate` PASS；`udf_gate` **r1–r7 全 0**。改动约 17 文件（含 2 新建），停留工作区未 commit。真机走查增补：播完自动下一集、片尾 et 跳过、播放中内核错误→提示、音乐页播完一首不触发 VOD 换集、换源挂起→新源起播进度继承、投屏准备/收摊、画质/播放器/解码切换重播、m3u8 代理净化、弹幕开关。
- **#5 自查审查轮（2026-10-24，全量 diff 通读 + 顺序等价/线程/守卫三专项；零 阻断/高/中 发现）**：
  1. **顺序等价专项**：①`beginSourceSwitch` 重排——position 读取提前（kernel 读，与超时/解析取消无交集）、web/parse 清理提前至挂起折叠（switchStopPending 先置挡迟到结果 + PlaybackStarter.play 开场本就重清三件套）；②`onSessionTakenOver` 使 setUserPickedLine 提前于 publishTitle/clearTriedLines——三写互不依赖（tracker 键/userPickedLine/标题派生/triedLineFlags 四域独立）；③`playNext/playPrevious` 的 reviveEngineIfReleased 提前到编排前——活性引擎下逐字等价（revive 即返回），released 引擎下状态写落在复活后 controller（旧代码写废弃实例后丢弃，新行为更一致，登记为改进）；④`onNewPlayArmed` 对 reuse=false 不触碰 switchIntent（与原 `if (sameVodSwitch)` 逐字一致，实施中从无条件调用修正）。
  2. **线程与守卫专项**：引擎派发运行于 `stateScope=Main.immediate`，与 HEAD 的 `dispatchPlayState→VPC(主线程)` 派发线程一致；`onKernelPlayError` 与 `errorWithRetry` 内部梯子逐段同构（started→retryAfterStartedError/autoRetry→stopMusicSessionForFailedPlayback+同文案同 flags 的终态 tip）；COMPLETED/ERROR 派发守卫用页面级 `isAudioOnlyPage()`（liveMode 早退在其前；`handlePlayStateForMusicSession` 对 COMPLETED 实证返回 false 不兜底）；skipEnd 闩锁消费后至 `beginNewPlay` 复位间的 tick 被闩锁挡住（starter.play 同步路径内复位，窗口极窄且方向安全）。
  3. **桥接面**：Host 两个 `commitPlayerCfg(mutate, persist)` 桥接走新参默认值（无守卫）＝原 2 参语义；`bridge.toast` 实现即 `Toast.makeText`（与原容器直调等价）；`PlayerActionsDelegate.onTimeResetClicked`/`applySpeed`/`applyScale` 等 4 处尾随 lambda 调用点因 mutate 尾参化**零改动**；et 读取源由 VPC 推送快照改为 controller `playerCfg`（同一数据源的源头，等价）。
  4. **登记（低）**：`PlaybackController.setPlaybackStarted` 包装现仅剩 `prepareUserReplay` 一个内部调用者（保留为 player 层 API，口味项不改）；`MusicPlayerActivity:419` 的 `controller.markStoppedForSourceSwitch()` 为 gate 正则域外（标识符非 `scheduler`）的同形态直写——与 PlaybackEngine 自身 PlaybackHostApi 实现同构，登记观察不入本批；skipEnd 闩锁复位点由 VPC.resetSpeed 迁至 beginNewPlay（均随每次内容起播触发，等价且更早）。
  5. **复验**：`assembleDebug` 全绿、1426 例 0 失败、`udf_gate`（r1–r7 全 0）/`null_assert_gate`/`i18n_gate`（ui 硬闸门 0）/`playstate_gate` 四门禁 PASS；新增文件全 LF。
- **#5 自查审查轮 2（2026-10-24，用户指令独立复查：调用面穷举 + 新角度语义推演；零 阻断/高/中 发现）**：
  1. **调用面穷举通过**：`VodControlListener` 全仓唯一实现方（接口变更全含，测试源零引用）；`commitPlayerCfg` 全部调用点为 positional 2 参（mutate, persist）——retry/config delegate 的自动切换路径不吃 suppress＝原语义；Host 双桥走新参默认值；守卫方法（config.setAllowSwitchPlayer/setAllowDecodeFallback）现仅剩 commit 内部调用；`onTimeResetClicked`/`applySpeed`/`applyScale` 等 4 处尾随 lambda 因 mutate 尾参化**零改动**。
  2. **新角度语义推演通过**：①**音乐页/音频内容 near-end 误触发**——ProgressUiGate 的 contentUrl↔currentUrl 比对挡住（音乐接管后 VPC.contentUrl 与内核 currentUrl 不匹配→快照 null→不上抛），与 HEAD skipEnd 同构；②**COMPLETED 派发 × pendingCompletionDrop**——arm 发生在守卫返回 false 路径（派发前已存在），派发只增不改，音乐页由页面级守卫跳过；③**StateFlow 无同步重入**——Main.immediate collect 内 tryEmit 排队续投（HEAD 的 VPC 同步重入反而消失）；④**toast 线程**——media session 路径旧代码同样容器内直接 Toast，bridge.toast 等价无新风险；⑤**takeover 后闩锁状态**——HEAD 的 VPC.skipEnd 同样不复位（takeover 不走 startVideoPlayback/resetSpeed），等价；⑥直播（liveMode 早退在前）与投屏准备态（无守卫）均与 HEAD 一致。
  3. **发现并当场处理（低）**：①流程缺口——ControlListener 的未用 R import 清理晚于上轮全量构建，**已补终验**（本轮 assembleDebug+1426 例+四门禁全绿覆盖该编辑）；②`avbox-mobile-ui-spec.md` REDRAW 叙事残留 `STATE_ERROR → errReplay` 旧链名，改写为 `onKernelPlayError` 并注明收敛日期（行为描述不变，同一重试梯子）。
  4. **登记不改（口味/域外）**：scheduler.updatePlayerCfg（mutate 在前）与 VodControlListener.updatePlayerCfg（mutate 尾参）参数序不一致——各自局部动机（后者保尾随 lambda 风格）；`setPlaybackStarted` 包装仅剩 prepareUserReplay 内部调用者；`MusicPlayerActivity:419` 的 `controller.markStoppedForSourceSwitch()` 为 gate 正则域外同形态直写（与 PlaybackEngine 自身实现同构）；`playAdjacentEpisode`/`beginSourceSwitch` 为已测 transition 之上的薄编排，无独立单测（编排在真机走查面覆盖）。
  5. 复验：`assembleDebug` 全绿、1426 例 0 失败、`udf_gate`（全 0）/`null_assert_gate`/`playstate_gate`/`i18n_gate`（ui 硬闸门 0）PASS。**收敛判定：连续两轮零 阻断/高/中——#5 代码审查达终止线**，剩余风险移交真机走查。
- **#5 自查审查轮 3（2026-10-24，用户指令复查"上一工作区问题是否解决"；抓出 1 项中级链间竞态并当场修复）**：
  1. **上一工作区问题逐条验证在位**：三条断线（COMPLETED 自动连播/ERROR 错误面/片尾 et 跳过）接线证据链齐全（engine :171/:174 派发、VPC tick→listener→scheduler 上抛）；两处编译破损（isMusicSessionActive 无委托门面、VPC when 缺 ERROR 非穷尽）已修（门面删除、ERROR 入 no-op 组）。
  2. **发现并修复（中，链间竞态）**：COMPLETED 引擎派发与片尾 tick 在**换集解析窗口**内会双跳集——恢复接线的实现为"beginNewPlay 复位闩锁 + 推进入口不消费"，把 HEAD 既有的一处竞态（COMPLETED→playNext 与 tick skipEnd 并发）放大为两处（beginNewPlay 在 play 同步路径内重开窗口）。修复 6 处：①`beginNewPlay` 不再复位 `skipEndPending`；②`onPlaybackCompleted` 入口消费闩锁；③`playAdjacentEpisode` 入口消费闩锁（用户推集作废待触发近尾跳过，无守卫不拦手动操作）；④`startVideoPlayback` 复位闩锁（与 HEAD 的 resetSpeed 同位——桥 startVideoPlayback 时机）；⑤VPC tick 上抛加 `view?.isPlaying == true` 门（近尾跳过语义上只在播放中发生，顺带关掉"出错内容近尾误推进"与"完成后残余 tick"两条 HEAD 既有边）；⑥单测改锁新口径（`skipEndLatch_notRearmedByBeginNewPlay`）。**登记（顺带修复，严格优于 HEAD）**：HEAD 的"COMPLETED 先到 + 残余 tick 后至"双跳集竞态与"出错内容近尾误推进"边一并关闭。takeover 场景闩锁陈旧消费态的边界（近尾不触发但 COMPLETED 路径仍兜底推进）登记不改。
  3. 复验：`assembleDebug` 全绿、1426 例 0 失败、四门禁 PASS（实施中 2 处编译错当场修正：playAdjacentEpisode 智能转换断链、VPC 可空 view 安全调用）。spec §2.3 闩锁复位点描述同步改写。
- **#5 自查审查轮 4（2026-10-24，用户指令"不要埋雷"；并发面 + 残余边界 + 工作区卫生；再修 1 项中级竞态）**：
  1. **并发写入面**：`startVideoPlayback` 确有解析线程到达路径（PlayUrlResolver pool → playUrl 链），新增的 `st.copy(skipEndPending = true)` 与同方法既有 tracker 写入同类（既有引擎线程模型口径，`st` 为 @Volatile）；误并发方向（复位×消费竞速）被 `isPlaying` 上抛门 + 新内容位置≈0 规则双重挡住——登记不改。
  2. **调用方矩阵闭合**：`onPlaybackCompleted`（engine 派发 + onNearEndProgress 路由）、`playAdjacentEpisode`（container playNext/playPrevious）全部调用点穷举；无反射/动态 jar 面（PlaybackHostApi/VodControlListener 均宿主内部接口，R6 干净）。
  3. **发现并修复（中，用户推集×窗口内自然播完双跳集）**：用户在片尾 et 窗口内按"下一集"后，旧集仍在播、若在换集解析窗口内自然播完，COMPLETED 派发会再次推进（连跳两集）——HEAD 既有竞态的第三条边（轮 3 只收口了 COMPLETED×残余 tick 一条）。修法：`onPlaybackCompleted` 加闩锁武装守卫（语义 =「闩锁武装 ⇔ 本内容尚未被推离」，consumed 即拒推进），`onNearEndProgress` 去掉预消费改纯路由（否则守卫自挡）。**附带改进（登记）**：末集边界"按下一集→到集尾"不再双重 toast（HEAD 既有）；takeover 陈旧消费闩锁场景下自动连播不再由 COMPLETED 兜底（可达性趋近零——需"近尾已推进后原地重挂同一内容"，且宁可少推进不误推进，方向安全）。三条推进路径（COMPLETED/近尾/手动推集）自此互斥。
  4. **工作区卫生**：本次产生的 `_p5c11_*` 构建日志全部落在 gitignore 域（`.codebuddy/tools/`），不入库；spec 修订记录中过时的"复位点迁至 beginNewPlay"句已修正为 `startVideoPlayback`（与 §2.3 as-built 一致）；无遗留调试日志/TODO。
  5. 复验：`assembleDebug` 全绿、1426 例 0 失败、四门禁 PASS。**收敛判定：轮 3/4 连续抓到的均为同一竞态族的边，本轮修复后推演矩阵（正常完集/近尾先行/完集先行/手动推集×3 种窗口时序/出错内容/重播/takeover/音乐页/headless 九类时序）全部单推进**；下一轮若无新发现即终线。

# 9. §8.1 #4 执行记录（2026-10-24）

### #4 contentUrl/progressPhase 会话代际化（完成，2026-10-24，用户批准立项方案后实施）

- **内核 gen**：`AppPlayerView` 新增 `@kotlin.jvm.Volatile mContentGen`（setUrl 可达解析线程，#2 同款加固），`setUrl(url, headers)` 无条件自增（同 url 重播也计——身份 = "第几次 setUrl"），暴露 `val contentGen`。
- **控制器武装**：`VideoPlayerController.contentUrl` 删除 → `armedContentGen = -1`（-1 = 未武装哨兵）；`PlayerControlApi.onContentUrlSet(url)` 改名 **`adoptCurrentContent()`**（无参——gen 语义下 url 是死载荷）：实现保留原 null 守卫（`videoView ?: return` + `view.currentUrl == null → return`），武装值 = `view.contentGen` 原子拷贝 + `progressPhase = ACTIVE`。**双副本漂移从结构上消失**（武装值永远是内核当前值的拷贝，无独立维护）。
- **两协议点**（播种补丁转正）：①`PlayContainerViewBridge.startVideoPlayback` :181 每次起播 adopt（**同内容重播的 refreshKeepsProgress 放行语义由此保持**——setUrl 已自增 gen、adopt 立即重取，同线程相邻无 tick 插窗）；②`PlayContainer.setData` 接管分支（原 :508 播种）改 `adoptCurrentContent()`。
- **gate 与死面**：`ProgressUiGate.accept(phase, armedGen, kernelGen)` = ACTIVE + `armedGen >= 0` + 相等；`progressSnapshot` 传参改造；`PlaybackViewBridge.onContentUrlSet` 成员 + `HeadlessView` 空桩（含注释）+ `PlayContainerViewBridge` :57-59 转发实现三件死面删除（全仓零调用方，grep 复核 `onContentUrlSet|contentUrl` 残留为零）。progressPhase 相位机（三写点）不动。`echo-progress-tick` 日志增 `armed=/kernelGen=` 两值（走查判读口径变更登记）。
- **单测**：`ProgressUiGateTest` 5 例重锁 gen 口径（相位优先于代际 / IDLE 拒 / 匹配放行 / 双向错配拒 / 未武装拒），意图与旧 5 例一一对应。
- **时序等价九场景**（立项方案表全验证）：正常起播放行、同内容重播放行（重武装）、A→B 换内容 A 残余 tick 拒（武装 gen 落后）、B 容器未起播拒（-1）——§7.4"闪回上一部"保护保持、同片接管 adopt 放行、全新内核 gen=0+null 守卫拒、解析窗口放行（同线程相邻，与旧两串已同等价）、直播插曲后回 VOD 新起播重武装、音乐页/无头不接入。
- **文档**：`avbox-mobile-ui-spec.md` §6.22 四处 as-built 改写（门条件/诊断日志/失败出口/写策略维度元组，历史窗口叙事保留并注明旧口径）；`avbox-playback-service-spec.md` §7.4 追加 2026-10-24 迁移行（§7.4 既有两条判负历史记录原样保留）。
- 验证：`assembleDebug` 全绿、`testDebugUnitTest` 1426 例 0 失败、四门禁 PASS（r1–r7 全 0）；`prune_imports` 全 0。改动约 10 文件，停留工作区未 commit。
- **#4 自查审查轮（2026-10-24，全量 diff 通读 + 读者面穷举 + 时序专项；零 阻断/高/中 发现）**：
  1. **读者面穷举**：`currentUrl` 全仓读者全景——`AppPlayerView.currentUrl` 属性现存唯一消费点 = `adoptCurrentContent` 的 null 守卫（gen 化前是 gate 字符串比对与播种载荷，两处均随本批退役）；`PlaybackViewBridge.currentUrl()` 接口成员 + 两实现（页面桥/无头桥）为**既有死面**（零调用方，早于本批存在、非本批孤儿化）——按范围纪律登记不删，留提交前清理批或 #6 一并处理；`LiveSettingsRules.currentUrl` 为直播配置历史匹配，无关。
  2. **时序专项**：adopt 落点两处核对无误（起播桥在 `scheduler.startVideoPlayback` 返回后、`resetSpeed` 前——setUrl 自增 gen → replay/start → adopt 重取，同线程相邻无 tick 插窗；接管分支 adopt 时内核必在播，null 守卫不触发）；失败态（armed==kernel 同内容）快照放行 = 旧口径等价；`onNearEndProgress` 走 AttemptState 闩锁、与进度门 gen 面无交互。
  3. **线程与容量**：`mContentGen` @Volatile（setUrl 解析线程可达路径）、Int 容量（每 setUrl 一次，不现实溢出）；gen 与直播侧 6 处直调 setUrl 共享计数——直播期间 VOD 控制器不 tick、回 VOD 必经新起播重武装，无影响（立项方案预演，代码复核一致）。
  4. **卫生**：grep `onContentUrlSet|contentUrl` 全仓零残留；`prune_imports` 5 文件全 0；无新增调试代码；新测试文件 LF。
  5. 复验：`assembleDebug` 全绿、1426 例 0 失败、四门禁 PASS（r1–r7 全 0）。**收敛判定：零 阻断/高/中，唯一登记项为既有死面候选——#4 代码审查达终止线**，剩余风险移交真机走查（换集加载期进度条、同片接管续显、同内容重播连续性、换集闪回回归）。
- **#4 自查审查轮 2（2026-10-24，用户指令独立复查：内核内容切换旁路穷举 + 风格对齐；零 阻断/高/中 发现）**：
  1. **旁路穷举（本批核心风险面）**：内核内容切换的全部入口逐一核实——①`AppPlayerView.setUrl` 无子类 override（MyVideoView 未覆写，gen 自增不可绕过）；②VOD 唯一触点 = `startVideoPlayback`（setUrl 后 adopt，同线程相邻）；③**m3u8 净化流旁路排除**——`PlaybackStarter:288 → bridge.playM3u8 → M3u8PurifyUseCase` 全部出口（直连 url/净化回放/代理 url/上游 forward）都经 `callback.startPlayUrl → goPlayUrl → starter → startVideoPlayback → setUrl`，净化用例不触碰内核视图；④`view.replay` 直调仅 startVideoPlayback 内部（setUrl 之后）与直播（不涉 VOD 门）；⑤直播侧 6 处 setUrl 推高 gen 但 VOD 控制器不 tick、回 VOD 必经新起播重武装（立项预演，代码复核一致）。
  2. **交互面复核**：takeover 分支 adopt → `alignInstanceConfigOnTakeover` 可能触发 `replay(false)` → replayCurrentAddress → 新起播重武装（链路自洽）；失败态 armed==kernel 同内容快照放行 = 旧口径等价；内核释放重建后新视图 gen=0 + 未武装 → 拒（正确方向）；`onNewPlayStarted(LOADING)` → adopt(ACTIVE) 顺序与旧一致。
  3. **修正（口味）**：`@kotlin.jvm.Volatile` 全限定 → 项目统一的导入式 `@Volatile`（对齐 JarLoader/JsLoader 等既有风格），补 import；复验全绿。
  4. **哨兵边界**：`armedGen=-1` 与内核 gen=0（从未 setUrl）无碰撞可能——adopt 的 `currentUrl == null` 守卫保证武装值 ≥ 1；内核 gen=0 时 tick 必拒（armed=-1 ≠ 0）。
  5. 复验：`assembleDebug` 全绿、1426 例 0 失败、四门禁 PASS。**收敛判定：#4 两轮审查零 阻断/高/中**（轮 1 既有死面 currentUrl() 登记不删），代码审查达终止线；剩余风险移交真机走查（换集加载期进度条、同片接管续显、同内容重播连续性、m3u8 代理片进度）。

# 10. §8.1 #6 执行记录（2026-10-24，UDF 计划收官）

### 批 6-A：VodSeries/VodSeriesFlag.selected 冗余删除（完成）

盘点实证 selected 为"写后只被深拷贝搬运、零渲染消费"（高亮 = `flag.name == playFlag` / `index == playIndex` 推导，DetailContent:147 / DetailEpisodes:154/160/458）；两内嵌类不参与 Gson（外层序列化排除）。删除 DetailViewModel 5 处写点 + copySeriesFlags/copySeriesMap 的 also 回写 + 两内嵌类字段。构建+1426 例全绿。

### 批 6-B：VodInfo 全 val 化（完成）

- **VodInfo → data class 27 字段全 val（默认值全保留，Gson 字段反射契约不变）**；`setVideo` → companion `fromVideo`（含 urlBean 判空/≤5 线路反转的逐字迁移，assert 弱化删除——与 `?: continue` 冗余）；`reverse()` 原位保留（C4 按"构造后字段不被赋值"口径，集合引用 val、内容反转原位，避免每次拷贝数百集深拷贝）。
- **会话写点 = 实例替换**：`updateSelection`（copy 三字段 + `vod =` 替换 + `currentSession?.adoptVod` + 桥推送 + `_selection` 发射）；`publishPersistedPlayerCfg`（copy playerCfg + 替换 + adopt）；**`PlaybackSession.vod` private val → private var + `adoptVod`**（controller 域内的可变槽指向不可变值，"会话键活跟随选集"的原语义逐字节保持——键派生/startedPlaybackKey/sameContentRestart 全部不受影响）。
- **DetailViewModel**：`commitVodInfo(info)` helper（vodInfo 换实例 + `_vodUi.update { it?.copy(info=, rev=+1) }`）承载"写+bump"模式；onEpisodeClick/onFlagClick/toggleReverse/syncPlayingVodInfo/persistedPlayerCfg collect 全部 copy 化；`refreshPlayNote` → 纯函数 `playNoteOf(info, flag, index)`；loadDetail 组装链改 `var info = fromVideo(...)` + 顺序 copy（record 还原/反转/回退/keepFlag/夹取，restoredSelection 返回 Pair 替代原位变异）；`restoreFallbackEpisode` → `restoredSelection`；**previewVodInfo 缓存退役**（preview = `info.copy(playNote=, seriesFlags=深拷贝, seriesMap=深拷贝)` 每次新建——片 3-2 解耦语义保持；preview 字段由 12 个变全字段超集，历史 JSON 从音乐页路径多带元数据字段，兼容，登记）。
- **读者保鲜**：`MusicPlayerActivity.vod` 在 playAt 的 updateSelection 后同步刷新（音乐页唯一选集来源，无 collect 需要）；`PlayerUiState.sessionVod` 经**新增 `PlaybackViewBridge.onSessionVodUpdated`**（HeadlessView 空桩 + 页面桥转 `syncSessionVod`）跟随全部选集来源（含自动换线），episodeBtnVisible :229 保鲜。
- **持久化面**：`HistoryRepository.parseRecord` 回填 → `parsed.copy(sourceKey=, updateTime=)`（getVodInfo 无回填，零改动）；`HistoryViewModel.resolveSourceNames` → mapIndexed + copy（listChanged 语义保持）；`VodCardMenu.toVodInfo` → 命名参数构造。
- **陷阱防御**：`VodUiSnapshot(info, rev)` → `(rev, info)` 字段序换位——VodInfo data class 化后若 info 先比，StateFlow 每次发布深比较 seriesMap O(n)；rev 在前且 bump 必变 → 廉价短路。
- **R6 实证**：catvod 零引用/零 Java/KV 零 VodInfo 对象/Room schema 不动；`BeanSerializationRegressionTest` vodInfo 15 例 roundtrip **绿**（JSON 字节级契约保持）；Gson Unsafe 非空 String 缺键注入 null 的既有特征不变（键恒在，登记不改）。
- **测试迁移**：BeanSerializationRegressionTest/VodInfoReverseTest/EpisodeMatcherTest/HistoryWriterTest 四文件构造器化（assert 语义逐条保留）。
- 验证：`assembleDebug` 全绿（3 轮编译迭代：Engine 缺 import/测试源 4 文件）；`testDebugUnitTest` **1426 例 0 失败**；四门禁 PASS（r1–r7 全 0）；prune_imports 全 0；主代码 VodInfo 可变字段写点 grep 清零（Controller:358/MusicPlayerState:113 为同名异类字段）。
- **登记（行为差异，均良性）**：①preview 携带全字段 → 音乐页写历史 JSON 多带元数据字段（兼容超集）；②fromVideo 删除冗余 assert（-ea 下无行为差）；③data class equals/hashCode/copy 新增 public API（全仓无实例级 ==/哈希容器，审计通过）；④Gson Unsafe 缺键 null-in-nonnull 既有特征保留。
- 真机走查增补：历史卡片进度/集数标签（val 还原读取）、换源回退、选集/换线高亮（selected 删除后推导驱动）、音乐页队列与播放模式、换源后 playerCfg 持久化、自动换线后选集按钮可见性（sessionVod 桥推送）。
- **#6 自查审查轮（2026-10-24，全量 diff 通读 + 写点穷举 + 时序专项；零 阻断/高/中 发现）**：
  1. **写点穷举收口实证**：`compileDebugKotlin` 绿 = 写点矩阵完整（val 不可赋值，任何漏网写点必然编译失败）；主代码 `.playFlag =/.playIndex =/.playNote =/.playerCfg =/.seriesMap =` grep 清零（Controller:358 = 控制器自有 playerCfg JSONObject、MusicPlayerState:113 = 音乐容器自有 sourceName，同名异类）；`VodInfo()` 无参构造 main 清零；`VodInfo.setVideo` 清零（余量全为无关同名渲染方法）。
  2. **时序专项**：①`updateSelection` 实例替换后 `play(false)` → starter 经 `host.vod()` 读新实例 → progressKey/startedPlaybackKey 派生自新 flag/index（键语义保持的关键路径）；②session adopt 与 controller.vod 同步替换，`currentSession().playbackKey()` 活跟随语义逐字节保持；③toggleReverse 的 `reverse()` 原位 + copy 顺序与原一致（list.size 在反转前后不变、镜像用原 playIndex）；④onFlagClick 的 `manualLineSwitchTargetIndex = newIndex` 与原"写后读"等价（newList 空 → 原索引）；⑤syncPlayingVodInfo 的 cfg 三元（null 保留旧值）以 `?:` 等价；⑥loadDetail 的 token 守卫位置不变（var info 捕获于 launch 外，Kotlin 闭包捕获 var 合法）。
  3. **别名与共享面**：copy 不深拷贝集合 → toggleReverse 的反转对旧快照同样可见（旧可变世界本就同别名，等价）；preview 深拷贝经 copy 参数保持（片 3-2 解耦不回退）；restoredSelection 纯函数化后 loadDetail 无中途 publish ✓。
  4. **修正（口味）**：无新增；`assert(list != null)` 冗余删除已登记。
  5. **收敛判定**：#6 两批（A/B）零 阻断/高/中——**UDF 计划 §8.1 全部六项收口，宪法 C1–C5 达成结构化保证；代码审查终止线达成**。剩余 = 全计划真机走查（清单已并入各批记录）与用户审阅提交。
- **#6 自查审查轮 2（2026-10-24，用户指令独立复查：copy-on-write 陈旧引用面穷举；修 1 项中级陈旧引用 + 1 项方案符合性）**：
  1. **发现并修复（中，音乐页 vod 陈旧）**：音乐页 `vod` 为 lateinit 缓存，若音乐播放中触发自动换线（RetryDelegate.tryNextLine → updateSelection → 实例替换），`PlaybackViewBridge.onSessionVodUpdated` 推送经 **MusicPageBridge（Kotlin 委托到 headlessBridge）落到空桩**——音乐页收不到刷新，refreshMeta/queueList/onSongCompleted 读旧实例（HEAD 的共享可变 bean 会自动新鲜）。修法：音乐页 init 的 scope 增 `controller.selection.collect { controller.vod()?.let { vod = it } }`（与 playStateFlow collect 同域同调度）；playAt 内的同步刷新保留（syncHistory 紧随 updateSelection 需即时新鲜）。
  2. **发现并修复（口味，方案符合性）**：内嵌类 `VodSeries/VodSeriesFlag` 的 name/url 在批 6-B 实现中保留了 `@JvmField var`（方案原文为 val 化）——改为主构造 `val`（默认值保持无参构造）；编译器证实全仓零重赋值点。两内嵌类不参与 Gson（外层序列化排除），无序列化影响。
  3. **switchSnapshot 语义复核（本轮新面）**：`SwitchSnapshot.vodInfo` 捕获活 bean——旧实现快照别名可变实例，捕获后 syncPlayingVodInfo 等写入会污染"回滚"目标（旧潜在缺陷）；不可变化后快照冻结真正的切换前状态，rollbackManualSwitch 的 `publishVodInfo(snapshot.vodInfo)` 恢复语义**更正确**——登记为改进。
  4. **其余核查通过**：HistoryViewModel `_items` 收敛语义（listChanged 门保持，data class equals 只在变更时发布）；loadDetail 的 var info 闭包捕获与 token 竞态位置不变；toggleReverse 镜像算术顺序等价；restoredSelection 三出口与原早退逐一对位；VodUiSnapshot 消费者命名访问不受字段序影响；跟随读点（sampler/fetch/ports/ownerOf/token）全部逐调用新鲜读取。
  5. 复验：`assembleDebug` 全绿、1426 例 0 失败、四门禁 PASS（r1–r7 全 0）。**收敛判定：#6 两轮审查，轮 2 修复 1 中 1 口味后无残留——代码审查达终止线**；音乐页自动换线刷新列入真机走查。
- **#6 自查审查轮 3（2026-10-24，用户指令再复查：data class 生成语义面 + 字段对账；零发现）**：
  1. **toString 落日志风险排除**：data class 自动生成的 `toString()` 含全字段（含数百集 seriesMap）——全仓字符串拼接穷举证实无任何 VodInfo 实例整体进日志/字符串（命中全部为字段级拼接或无关同名变量），日志量与旧 `VodInfo@hash` 时代无差。
  2. **fromVideo 字段对账**：与原 setVideo 逐字段一致（17 元数据字段 + seriesFlags/seriesMap；dt 原本就不赋值、会话字段归默认值）；`assert(list != null)` 冗余删除已登记。
  3. **loadDetail 组装链复读**：token 守卫先于全部写入；record 还原 → reverse（fromVideo 新建列表，无别名）→ 回退 → keepFlag → restoredSelection → 夹取 → publish(rev 0) → bump(rev 1) 与原序列逐位对位；var info 闭包捕获合法、每次 loadDetail 独立实例无跨构建串扰。
  4. **收敛语义复核**：`_items`（HistoryViewModel）data class equals 只在 listChanged 时发布且必有元素差异；`_vodUi` rev 前置短路成立；`_selection` 小值对象；无 distinctUntilChanged/去重容器对 VodInfo 的依赖。
  5. **持久化线程面（本轮新认知，登记为改进）**：VodInfo 不可变化后，HistoryWriter 若在后台线程 Gson 序列化，主线程并发写不再构成数据竞争（旧可变 bean 时代存在该窗口）。
  6. 复验：`assembleDebug` 全绿、1426 例 0 失败、四门禁 PASS。**#6 三轮审查收敛：轮 2 修 1 中 1 口味，轮 3 零发现——终止线达成**。

# 11. §2.9 残留审计：rebindLiveControllerIfNeeded（2026-10-24，用户指令）

**判定：转正保留（代码不动），§2.9 该项销账为"防御性自愈守卫"。**

- **形态**：`LivePlayActivity.onResume` → `rebindLiveControllerIfNeeded()`——`view.videoController !== liveController` 时重绑（`setVideoController(controller)`，日志 `echo-p4 re-bind live controller`）。
- **补推机制本身通用且承重**：`AppPlayerView.setVideoController`（:598-616）做四件事——controllerView 摘挂/挂载、补发 playState、补发 playerState、非 IDLE/ERROR 启动 startProgress。**对 VOD（VPC）是承重路径**（内核重建/换绑后面板相位恢复），不可动。
- **对 live 的剩余消费（C-5a 后）**：`ComposeLiveController.setPlayState` 纯转发 `listener → onPlayStateChanged → vm`（真实）；`setPlayerState`/`startProgress` 已是空实现（C-5a 删自存副本后无消费）——补推对 live 的价值收敛为一条 VM 状态同步。
- **触发面**：正常导航（live → home → detail）下 live 必先 finish，绑定被夺路径不可达；服务丢失视图重建窗口在 init/resume 相邻处不存在。触发面趋零但语义合理（resume 自愈），与片 4"previewMode null 守卫遗留防御不触发"同类判例——防御成本 8 行，删除为趋零收益冒 resume 回归风险，不符重构触发条件。
- **走查点**：live 退系统 Home → 回前台：绑定应一致（无 `echo-p4 re-bind` 日志）；若出现该日志，检查直播状态恢复（VM 同步走补推路径）。
- **§2.9 清单至此全部处置完毕**：bumpRevision（机制合法化为 vodUi rev/commitVodInfo，纯 UI 刷新 bump 保留）、UNUSED_EXPRESSION（r4=0）、engineOwnsDetailContent（重定义为所有权守卫）、shouldReloadLiveConfig（#14 退役）、pendingLiveRefreshSourceIndex（片 2 删）、isCheckedSourcesStale（片 2 删）、contentUrl 播种（#4 adopt 转正）、rebindLiveControllerIfNeeded（本节转正）、stale-token 校验两份（片 3-4 转正为跨会话身份守卫）。

# 12. 响应式事件流收尾（2026-10-24，用户指令）

- **toastEvent/finishEvent 转正为事件语义**：`MutableStateFlow<String?>/Boolean` 杂交（全仓仅有的两处"事件建模成状态流"）→ `MutableSharedFlow`（extraBufferCapacity=1 + DROP_OLDEST——忠实于原 StateFlow 的 last-wins 合并语义）；消费侧 `collectAsState` + 手工 ACK 管线（`clearToast()`/`consumeFinish()`）整体退役 → DetailScreen 两条 `LaunchedEffect(Unit) { vm.toastEvent/finishEvent.collect { ... } }` 直驱（Toast 展示/activity.finish 行为逐字节保持）；15+2 写点 tryEmit 化；reset 路径的 null/false 清除行删除（事件无"清空"语义）；`absXml.msg` 可空守卫（null 不发射 = 旧流程存 null 后 UI 跳过渲染的等价改写）。
- **诚实修正**：上一轮审查称"写消息后无人清除 → 重放 bug"——不实，消费侧存在 `clearToast()/consumeFinish()` ACK（重放窗口仅剩"写入后、ACK 前"的重建窄窗）。正确评级 = **手工 ACK 管线冗余**（其余 VM 均为 SharedFlow 直发），而非活跃缺陷；本次收尾消除的是管线与其余三处事件的形态不一致。
- **Home/PartitionList 公开 MutableSharedFlow 收口**：`pageErrorEvents`/`actionMessages` 改 `private _x` + `asSharedFlow()` 只读暴露（与 VM StateFlow 同规矩）；写点 tryEmit 随改；外部 collect 不变。
- **门禁 r8 新增**：`r8_public_sharedflow`——VM 持有者文件中 public MutableSharedFlow 暴露（r2 只数 StateFlow 的盲区补上），基线首冻 0，新增即 FAIL。判定体与 r2 共用（VM 文件域 + private/set 豁免 + remember 豁免）。
- **载荷密封化维持触发制**：页面级 toast 为纯展示文案，String 合理；待事件类型增长再并入 UiEvents 密封族。
- 验证：`assembleDebug` 全绿、1426 例 0 失败、五门禁 PASS（r1–r8 全 0）、prune_imports 清理 HomeViewModel 残留 import 后复绿。走查点：详情页 toast（换源失败/取消收藏/跟随保存/无可播内容）、换源失败回退的 finish 流程。
- **§12 自查审查轮（2026-10-24，独立复查：replay=0 丢弃时序穷举 + r8 探测器实弹验证；零新缺陷）**：
  1. **订阅时序穷举（本轮核心面）**：SharedFlow replay=0 下"订阅前发射即丢"——穷举全部 tryEmit 写点的可达时机：`initFromIntent → loadDetail` 的同步前缀（先于 `setContent`）**无任何 toast/finish 发射**（第一挂起点 :516 在所有发射之前）；:336 的无可播内容 toast 归属 `onPlayRequested`（运行时动作，组合后）；:581 finish 在换源失败回退路径（组合后）。**当前丢弃窗口为空**；登记约束：未来新增 init 期 toast 需知 replay=0 会丢（届时用 state 或上移发射点）。
  2. **r8 探测器实弹验证**：正例（VM 文件公开 SharedFlow=1）/private 豁免（同文件私有跳过，总数只计公开那个）/负例（非 VM 文件且无 ViewModel 类=0）/r2 回归（公开 StateFlow=1、私有=0）——四例全过。第一版测试脚本"非 VM 命中应 0"的预期是脚本口径错误：门禁的文件域设计为"VM 文件名或文本含 ViewModel 类"二者其一（内嵌 VM 类须抓），门禁行为正确。
  3. **残余扫描**：其余 `MutableStateFlow<Boolean>/String?>`（fullScreen/rotating/collected/_persistedPlayerCfg 等）均为持久语义的合法状态，无事件残余；事件建模成状态流的杂交已清零。
  4. **收口面**：Home/PartitionList 外部 collect 不变、无外部写者（编译背书）；DetailScreen 删除变量无残余引用；absXml.msg 可空守卫等价性（旧流程存 null → UI `toast?.let` 跳过渲染；新流程不发射 = 等价）。
  5. 复验：`assembleDebug` 全绿、1426 例 0 失败、五门禁 PASS（r1–r8 全 0）。**收敛判定：§12 收尾批审查终止线达成**；走查点（详情页 toast 五场景 + finish 回退）并入总走查清单。

# 13. 真机走查记录（进行中，2026-10-24 起MuMu 实测）

## 已验证通过

- 冷启动 + greenrobot 依赖删除（欠账闭合）：零 NoClassDef/EventBus 残留
- 进源播放全链路：解析/硬解/历史写入/进度采样落盘（5s 节奏、键正确）
- 换集全链路：进度 UI 清零 → 解析 → 起播 → 选集同步（echo-detail sync 真机出现）
- 进度门 gen 化：armed==kernelGen 全程相等（含同进程订阅切换的 gen 3→6 热场景）
- 订阅切换（同进程热切换）：99 源枚举/直播 URL/jar 重载/蜘蛛预热/首页分区重载，零错误
- **自动连播**：真机闭环（99.98% 自然播完 → COMPLETED → updateSelection(+1) → 新集起播 → 历史写入）
- **片头跳过 / 片尾跳过**：触发确认（用户实测）
- 进度门 LOADING 相位拒判：换集加载期 accepted=false ✓
- 直播硬软解切换：HARD↔SOFT 交替、每次完整内核重建（renderer 四拍 + 新播放线程）、快速连切鲁棒
- 进出直播页**不重建内核**（挂摘复用，attach/detach 窗口零 codec-init）✓

## 走查中裁决（用户拍板，防误修）

- **直播解码记忆 = Session 级，维持不动（2026-10-24 用户裁决"那就不动了"）**：软解 → 退出（Activity 销毁）→ 重进回 KV 默认硬解，为 HEAD 起的既有语义（片 4 自查轮 1："sessionDecode 与旧 currentPlayerConfig 同为 Activity 级生命周期"）。系统 Home 退出（Activity 存活）与换源重载场景下软解保留 = #4-A3 改进点。若未来要跨进页记忆：须按 KVKeySpec 登记直播专用键，禁止写全局 EXO_DECODE（会泄漏到点播）。

## 走查项撤销（2026-10-24 用户拍板）

- **双详情页叠放 = 撤销，不出现在走查清单**。依据：①原入口"相关视频"栏已于 2026-10-14 从 UI 删除，`jumpToDetail` 调用方穷举仅剩收藏/追更/历史/卡片五个首页层页面，详情页自身零入口——**场景经正常操作不可达**；②底层风险面（进程级 playerCfg/quality 广播跨页污染）由片 3 审查轮 2 的身份守卫静态覆盖（`persistedPlayerCfg`/`quality` collect 与 `syncPlayingVodInfo` 均有 id/sourceKey 匹配校验）。若未来恢复相关视频入口，此项随之恢复。
- **生产包 logcat 静音门控（2026-10-24 用户拍板"加一行门控"）**：`LOG.i/d/longI` 加 `if (!BuildConfig.DEBUG) return`——release 包静音信息/长文本通道（每秒 10 条的 tick/bar 噪音源归零，调用点字符串拼接成为剩余成本）；**`e/longE` 保留无条件输出**（生产包错误是唯一排障生命线，静音它得不偿失——与"禁止静默忽略错误"同向）；`debugI` 原有门控不变。debug 包零行为变化（DEBUG 恒真，走查日志照常——重装后实 测 130 条 echo- 正常流动）。BuildConfig.DEBUG 为编译期常量，release 下 R8 可消除死分支。走查日志判读不受影响。
- **门控自查审查轮（2026-10-24，独立复查；零错误遗漏）**：
  1. **门控影响面如实声明**：catvod 契约层（JsLoader/JarLoader）的 `LOG.d` 在 release 同被静音——签名不变（R6 ✓），加载失败仍有上层 LOG.e 兜底；`debugI` 双重门控无害；应用内无日志文件读取/上传功能（FILE_LOG_NAME 仅 LOG.kt 内部使用），门控不影响任何功能；BootGuard 走 ApplicationExitInfo 不读 logcat。
  2. **完整性缺口（登记不处理）**：三宿主文件绕过 LOG 门面直连 `android.util.Log` 共 18 处（MD5 6×Log.e 罕见错误路径、SubtitleLoader 4×Log.d、DefaultSubtitleEngine 8 条字幕调试）——release 静音的漏音源。判定：既有代码不在本次改动面、量级偶发非热路径（主目标 tick/bar 已由 LOG.i 门控达成）、subtitle/** 为第三方派生文件（改动增加上游对齐成本）。触发条件：字幕引擎日志刷屏时统一改走 LOG 门面。
  3. 判据补充：`LOG.e(tag, msg, tr)` 的 `tr.printStackTrace()` 未门控（stderr，既有行为）；`debugI` 调 `i()` 后受双重门控（冗余无害）。
  4. 验证：`assembleDebug` 全绿；MuMu 重装实测 debug 包 130 条 echo- 正常流动（门控 DEBUG 恒真零行为变化）。
- **门控收尾（2026-10-24 用户拍板，覆盖上一条的"登记不处理"）**：三个直连文件中的 **d 级日志 9 处迁移 LOG 门面**（SubtitleLoader 4 + DefaultSubtitleEngine 5——`Log.d(TAG,…)` → `LOG.d(TAG,…)`，debug 输出变为 `TVBox-runtime: SubtitleEngine: …` 双标签，登记；release 由 LOG.d 门控静音）。**e/w 级 9 处保留直连**（MD5 6×Log.e + 字幕 Log.e×1/Log.w×2——错误/警告通道，与门面 e/longE 不静音的裁决同向）。SubtitleLoader 的 android.util.Log import 随迁移删除。MD5.kt 未动（纯 Log.e 错误路径）。验证：构建全绿、i18n PASS、prune_imports 0、已重装 MuMu。
- **字幕日志迁移自查审查轮（2026-10-24，全景对账；零错误零遗漏）**：
  1. **迁移后全景**：SubtitleLoader 4×LOG.d（import 已删）；DefaultSubtitleEngine 5×LOG.d + Log.w×2/Log.e×1 保留 + **既有 LOG 门面用法浮出**——该文件本就有 `LOG.i("echo-sub drop stale/ready")`/`LOG.e("echo-sub fail")` 三处 echo-sub 日志（HEAD 即已导入 LOG，:38），本次迁移的 Log.d→LOG.d 与文件自身"门面管宿主日志、直连 Log 管派生引擎内部"的既有混合模式合流，方向一致。
  2. **文件日志行为逐字等价**：迁移前后 msg 均不匹配 FILE_LOG_PREFIXES（echo-*）→ 两态都不写文件 ✓；logcat 侧 debug 输出变双标签（`TVBox-runtime: SubtitleEngine: …`，登记）；release 由 LOG.d 门控静音 ✓。
  3. **级别保真**：d→d、w/e 原样保留——第三方派生文件不做级别重设计；引擎内部失败日志保持原有 d/w 级（release 静音 = "release 不输出"的字面达成）。
  4. **直连终态对账**：全仓残余 `import android.util.Log` = catvod×3（契约层不触碰）+ DefaultSubtitleEngine（w/e 保留）+ MD5（Log.e 错误路径保留）+ LOG.kt 自身——与声明的终态完全一致，SubtitleLoader 已脱离直连名单。
  5. 复验：构建全绿、i18n PASS、prune_imports 0、MuMu 已重装。**字幕日志迁移审查终止线达成**。
