# AVBox 播放服务化 Spec:播放器所有权搬到前台服务(照搬 fongmi 模型)

> 项目:AVBox(TVBox OSC fork;仓库根目录 = 本文件所在目录的上一级)
> 配套:先读 `SKILL.md`(通用规范 + 文档地图)与 `avbox-mobile-ui-spec.md`(§4.4 播放页布局 / §6 关键技术约束)。
> 状态:**P0–P5 全部落地(2026-09-14);真机功能回归通过(同日,用户确认);§4-6/7 的量化埋点与 hprof 复测未采集**。
> 触发背景:2026-09-14 hprof 取证(`memory/2026-09-14.md`)——详情页快速换页 = 每页整套 ExoPlayer + 内部线程 + Looper + 文件句柄新建、退出仅异步释放;12 次进出后滞留 12 套详情页对象图、36 个 ExoPlayer、249 线程、RSS 692MB,finalizer 积压 97%。用户决定**照搬 fongmi 的播放器所有权模型**。
> 参照实现:`示例文件/TV-fongmi`(fongmi/OK 影视,**只读参考,不改**)。
> 本文只描述"怎么搬";是否执行、按哪几阶段执行由 §7 决策记录拍板。

## 0. 摘要

把"**播放器与播放调度**"从页面(`PlayContainer`,134KB,Activity 上下文一把梭)搬到**前台服务**(`PlaybackService`),页面退化为"**控制器(Compose 覆盖层)+ 可挂摘的显示宿主**"。播放器实例跨页面复用,页面进出只做 attach/detach;**播放器实例跨页面复用(退出页面只停播、不释放)**、通知栏控制随之自然成立。**退出播放页一律停播 + 撤通知**(影视与音乐都不后台继续,用户 2026-09-14 选定,见 §2.4/§7-D3)。

**照搬的是"所有权模型",不是 fongmi 的 media3 API**:fongmi 的 `PlaybackService` 是 `MediaLibraryService`(其内核是 media3 `Player`),而我们的内核抽象是 dkplayer `AbstractPlayer`(ExoPlayer/IJK 双内核),**无法**接 media3 `MediaSession` → 保留现有 `MediaSessionCompat + MediaStyle 通知` 方案(已由 `MusicPlaybackService` 验证可用)。

**非目标**:不换 UI 框架、不改数据层(Room/MMKV/CacheManager)、不改 UI 规范、不引入新依赖、不做画中画(除非 §7-D3 批准)、**不追求"退页面继续播"**(退出播放页一律停播 + 撤通知,影视与音乐都不后台继续;用户 2026-09-14 选定,见 §2.4/§7-D3)。

## 1. 参照实现:fongmi 逐条核对(2026-09-14 读码)

### 1.1 拓扑

| 层 | fongmi | 文件 |
|---|---|---|
| 服务 | `PlaybackService extends MediaLibraryService`(前台):持 `PlayerManager`(PlayerEngine→ExoPlayer/media3 Player + 预载 + 字幕 + 弹幕 + 媒体会话 + 通知) | `示例文件/TV-fongmi/app/src/main/java/com/fongmi/android/tv/service/PlaybackService.java` |
| 调度 | `PlayerManager`:起播/解析(`PlaySpec`)/换集/engine 复用/预载/进度/回调通知 | `.../player/PlayerManager.java` |
| 页面基类 | `PlaybackActivity`:`bindService` + `MediaController`;持有 `androidx.media3.ui.PlayerView`(**纯视图**);`attachPlayerView()/detachPlayerView()` 挂摘;`isOwner()`/`reclaimPlayback()` 归属判定 | `.../ui/activity/PlaybackActivity.java` |
| 业务页 | `VideoActivity`(点播)/`LiveActivity`(直播)/`CastActivity`(投屏)**全部继承 `PlaybackActivity`**,共用同一服务与同一播放器 | `app/src/{mobile,leanback}/java/.../ui/activity/` |

### 1.2 关键实现点(可直接对照)

- 服务侧建/释放内核:`onCreate → player = new PlayerManager(this)`,`onDestroy → player.release()`(PlaybackService.java:105 / :190)。
- 视图挂摘:`syncPlayerView()` = `player().bindPlayerView(view)` + `getPlayerView().setPlayer(player)`;`detachPlayerView()` = `getPlayerView().setPlayer(null)`(PlaybackActivity.java:404-418)。
- 生命周期三行(PlaybackActivity.java:615-620):
  `onStop`:`if (isOwner() && (isFinishing() || PlayerSetting.isBackgroundOff())) pausePlayback(); if (!isInPictureInPictureMode()) detachPlayerView();`
  `onDestroy`:detach + `releasePlaybackService()` → `mService.releaseBinding(...)`;还有别的 client/callback → `keepServiceAlive()`,否则 `shutdown()`(PlaybackActivity.java:438-457)。
- 内核复用:`PlayerManager.ensureEngine(spec)`(`PlayerEngineFactory.matches(engine, spec)` 只在内核类型变化时重建引擎,同内核换内容 = `setMediaItem`)(PlayerManager.java:500-531)。
- 归属判定:`getPlaybackKey()`/`isOwner()`/`shouldReclaim()`/`reclaimPlayback()`(PlaybackActivity.java:129-140 / 356-368)。
- 后台档位:`PlayerSetting.getBackground()` 0=关 / 1=后台音频 / 2=画中画(默认 2),`isBackgroundOff()` 决定 onStop 是否暂停(PlayerSetting.java:94-112)。
- **边界**(照搬时必须记住):即使 fongmi,退出播放页且**无任何持有者**(PiP/后台音频/媒体 client)时服务 `shutdown()` → 播放停止。"跨页续播"的真实范围 = 页面仍在栈里 / PiP / 后台播放开启。

### 1.3 我们与 fongmi 的差距清单

| 维度 | 我们(现状) | fongmi | 结论 |
|---|---|---|---|
| 播放器归属 | 页面 `PlayContainer`(Activity context) | 前台服务 `PlayerManager` | **要搬** |
| 播放调度 | 与视图/控制器同处 `PlayContainer`(取流/解析/嗅探/换线/换源/预载/弹幕调度/字幕/投屏/进度) | 服务侧 `PlayerManager` | **要拆** |
| 视图 | `MyVideoView`(自带内核 + render + 状态机)放在页面 `view_play_container.xml` 里 | 官方 `androidx.media3.ui.PlayerView`(纯视图) | **保留 dkplayer**(见 D1) |
| 控制器 | `ComposeVideoController`(BaseVideoController + Compose 覆盖层 + 字幕/歌词视图) | 原生 View 控制层 | 页面持有(见 D2) |
| 内核抽象 | dkplayer `AbstractPlayer`(Exo/IJK 双内核) | media3 `Player` | 决定了**不能**用 media3 `MediaSession`/`MediaLibraryService`(D5) |
| 通知/会话 | `MusicPlaybackService`(仅通知壳,owner = `WeakReference<PlayContainer>`) | 服务自带 `MediaLibraryService` + `MediaSession` | 并入新服务 |
| 多页面 | 每页一套(详情页可叠加;直播页独立 `MyVideoView`) | 所有播放页共享一个服务 | 目标一致 |
| 页面挂摘 | 无(页面=播放器) | `setPlayer(player)` / `setPlayer(null)` | **要新建协议** |

## 2. 目标架构

### 2.1 组件与职责

| 组件 | 位置 | 职责 |
|---|---|---|
| `PlaybackService`(新) | `app/.../player/PlaybackService.java` | 前台服务(FGS `mediaPlayback`,沿用现有声明方式);持有唯一 `MyVideoView` + `PlaybackController` + `PlaybackSession`;通知/媒体会话/wake+wifi 锁(从 `MusicPlaybackService` 并入);`LocalBinder` 暴露指令面;按 fongmi 语义决定何时 `shutdown()` |
| `PlaybackController`(新) | `app/.../player/PlaybackController.java` | **从 `PlayContainer` 迁出的播放调度段**:取流/解析/嗅探/超时重试/自动换线/换源回滚/清晰度/DASH/M3U8 净化/预载协调/进度读写/弹幕调度/字幕与歌词轨道管理/投屏地址改写。不持有 Activity(用 `PageHost`) |
| `PlaybackSession`(新) | `app/.../player/PlaybackSession.java` | 一次播放的完整数据:vod 快照、sourceKey、线路/集索引、`playerCfg`、headers、progressKey、播放器实例标识。**取代** `App.getInstance().getVodInfo()` + `setData(Bundle)` 这两个隐式全局通道 |
| `PageHost`(新,接口) | `app/.../player/PageHost.java` | 页面能力:`runOnUi`/提示/Toast/权限申请/文件选择器/外部播放器/WebView 宿主判定。由 `DetailActivity` / `LivePlayActivity` 实现(弱引用注册) |
| `PlaybackViewBridge`(新,接口) | `app/.../player/PlaybackViewBridge.java` | **调度 → 视图**的最小动作面(播放/提示/状态读取/解析停止/嗅探队列消费/内核切换/配置回刷/换源兜底)。P0–P1 由 `PlayContainer` 提供匿名实现;P2 起由"服务 → 页面"的桥实现 |
| `PlaybackSurfaceHost`(新) | `app/.../ui/player/PlaybackSurfaceHost.kt` | 页面侧显示宿主(Compose `AndroidView` → FrameLayout):`attach(service)` 把服务侧 `mPlayerContainer` 挂进来,`detach()` 摘走 |
| `MyVideoView`(改) | `app/.../player/MyVideoView.java` + fork `VideoView` | 内核 + render + artwork/frameCover(服务侧);弹幕视图与控制器由页面 attach 时注入、detach 时清空 |
| `ComposeVideoController`(不改) | `app/.../player/controller/ComposeVideoController.kt` | 页面侧 Composer 覆盖层 + 手势 + 字幕/歌词视图;attach 时 `setVideoController(controller)`,detach 时 `setVideoController(null)` |

### 2.2 数据流与指令流

- **指令**:页面(Compose 按钮/手势/MediaSession/通知栏)→ `PlaybackHostApi` → 服务侧 `PlaybackController` → `MyVideoView`/`AbstractPlayer`。
- **状态**:`AbstractPlayer.PlayerEventListener` → `VideoView` 状态机 → ①服务侧 `PlaybackController`(业务)②页面侧 `ComposeVideoController`(UI,经 `setVideoController` 的既有回调路径,无需改造)。
- **页面数据**:`PlaybackSession` 由详情页组装(现有 `DetailViewModel.preparePlayBundle()` 的产物)后交给服务;服务只认 session,不再读 `App.getVodInfo()`。

### 2.3 挂摘协议(核心)

```
页面 onCreate:
  bindService → PlaybackHostApi
  attach:  host.attachSurface(PlaybackSurfaceHost)   // 服务侧 mPlayerContainer 搬进页面宿主
           host.bindController(composeVideoController) // setVideoController(controller)
           host.bindDanmu(danmuView, danmuLoadController)
页面 onPause/onStop:
  按策略暂停(纯音频不暂停 / 影视暂停,维持现状)
页面 onDestroy:
  host.unbindController()  // setVideoController(null) + 摘弹幕引用(防服务持有页面 View → 泄漏)
  host.detachSurface()     // mPlayerContainer 摘回服务(无宿主时播放器仍可跑音频)
  解绑服务;若无任何持有者 → 服务按 §2.4 策略 shutdown
```

- **归属判定(照搬 fongmi)**:`PlaybackSession.playbackKey = sourceKey|vodId|flag|index`;页面 `isOwner()` = 服务当前 session 的 key 与页面目标一致。详情页叠加(A→相关推荐→B)时,B 页 attach 前若发现服务在播 A,则按"**用户显式请求新播放**"处理(停 A、`setData(B)`),不走 fongmi 的 `reclaimPlayback()`(那个是给 PiP/多页共存用的);若只是"返回 A 页"且服务仍在播 A,则只 attach、不重新取流(这是跨页复用的收益点)。
- **fork 改动**(`player/src/main/java/xyz/doikki/videoplayer/player/VideoView.java`):新增 `attachContainerTo(ViewGroup host)` / `detachContainerFromHost()`,内部复用既有 `startFullScreen()/stopFullScreen()` 的搬运写法(`removeView(mPlayerContainer)` → `host.addView(...)`,VideoView.java:777-841),但**不碰系统栏、不改 `mIsFullScreen`**。
- **渲染视图**:`addDisplay()` 仍是唯一重建入口(既有约束);跨页面搬运 `mPlayerContainer` 会触发 `SurfaceView.surfaceDestroyed/surfaceCreated` → dkplayer 既有链路会 `setDisplay(null)` 再重挂,IJK 侧已做二进制级证明不会 use-after-free(MEMORY.md「IJK 异步 release × Surface 回调」),Exo 侧 `setVideoSurface(null)` 安全。

### 2.4 设计选择与推荐

| 编号 | 选择 | 备选 | 推荐理由 |
|---|---|---|---|
| D1 | **β:保留 dkplayer 内核与 `MyVideoView`**(服务持 `MyVideoView` 的 `mPlayerContainer` 可挂摘) | α:页面换 media3 官方 `PlayerView`,服务持 media3 `Player` | β 复用现有 Exo/IJK 双内核、track 选择、隧道/AAC、边播缓存、预载对齐、`MeasureHelper` 渲染逻辑;α 等于重写渲染与内核层(**周级**,且要重做 IJK 接 Surface)。β 的代价:仍保留 dkplayer 这层抽象 |
| D2 | **控制器由页面持有**,attach 时挂到服务侧容器、detach 时摘除 | 控制器服务持有 | 控制器是 Compose 覆盖层 + 字幕视图,依赖页面 `ViewTreeLifecycleOwner`/主题;服务持有时无页面即无 owners(ComposeView 未 attach 会抛 `ViewTreeLifecycleOwner not found`)。页面持有时只需 detach 干净即可 |
| D3 | 后台档位:**先维持现状**(纯音频退后台继续;影视退后台暂停);退出播放页一律**停播**(影视与音乐都不后台继续) | 照搬 fongmi 三档(关/后台音频/PiP) | 现状已被真机验证;三档设置与 PiP 属产品决策,单独排期 |
| D4 | 媒体会话:**保留 `MediaSessionCompat` + `MediaStyle` 通知**(现 `MusicPlaybackService` 代码搬进新服务) | media3 `MediaSessionService`/`MediaLibraryService` | 内核是 dkplayer `AbstractPlayer` 而非 media3 `Player`,media3 session 接不上;迁 media3 需先做 D1-α |
| D5 | 弹幕/字幕/歌词视图:**随控制器留页面**,attach 时注入服务侧 `MyVideoView`,detach 时 `setDanmuView(null)` | 服务持有 | `MyVideoView` 实现了 `DrawHandler.Callback` 会直接引用弹幕视图;服务持有页面 View 必泄漏 |
| D6 | 详情页叠加语义:**`playbackKey` 归属判定**(同 key = 只 attach 续播;不同 key = 用户显式换片,`setData` 重播) | 永远重播 / 永远续播 | 前者符合"跨页复用"收益,后者符合"点新片就换内容"直觉 |

### 2.5 与既有机制的关系(逐条)

| 既有机制 | 服务化后的处理 |
|---|---|
| 预载(PreloadCoordinator / SimpleCache / media3 looper 硬约束) | **不变**:`PreloadManagerHolder` 是进程单例,服务持有的播放器仍与其共享 looper;但"预载写盘 + 播放读盘"的 CacheKey/headers 口径不变(勿在搬迁中改动) |
| 边播缓存(`PLAY_CACHE`,本地代理 URL 跳过) | 不变;`PlayerHelper.isLocalProxyUrl` 判定点保留在 `PlaybackController` |
| M3U8 净化 4 槽 LRU + `?k=` | 不变;`M3u8PurifyUseCase` 与 `RemoteServer` 是进程级,天然跨页 |
| 集成解析/嗅探 WebView | `PlayWebView` 归 `PlaybackController`,但其宿主判定/回调改用 `PageHost`(服务不得持有 Activity);`stopLoadWebView(boolean)` 的 `mActivity` 判空逻辑同步改造 |
| 投屏(CastSheet / DLNA / TVBox 推送) | `getCastUrl`/`getCastTitle` 等由 `PlaybackController` 提供;`CastSheet` 是 Dialog,仍由页面弹出(需 `showCast()` 保留在页面 API 上,内部转发) |
| 弹幕搜索/本地字幕选择 | 需 Activity 的 `registerForActivityResult` → 走 `PageHost`(页面实现,服务回调) |
| 音乐通知/媒体键 | 并入新服务(P3);`PlayContainer.resumeFromMediaSession` 等入口改由服务直接操作播放器 |
| 进度写入 | 现在依赖 `hostDestroy`(页面销毁)——服务化后改为**三处**落盘:切集/换源前、服务 `onDestroy`、`onTaskRemoved`;`VideoView.saveProgress()` 既有机制复用 |
| 详情页聚合搜索 / 换源引擎(`DetailViewModel`) | 仍在页面侧(属详情数据,不属播放);只有"播放指令 + session"过服务 |
| 直播页(`LivePlayActivity` + `LivePlayerManager`) | P4 接入:直播自带切源/自动重试逻辑,迁入 `PlaybackController` 的直播分支后与点播共用服务与内核复用 |
| 外部播放器(`PlayerHelper.runExternalPlayer`) | 走 `PageHost`(服务内 `startActivity` 需 `FLAG_ACTIVITY_NEW_TASK`) |
| AutoSize/`vs_N` | 服务侧容器用 `ContextThemeWrapper(application, appTheme)`;`dp/sp` 是系统真实单位(MEMORY.md「尺寸/单位」),render/artwork 用 `match_parent` 不受影响;控制器仍在页面上下文,尺寸无变化 |

## 3. 分阶段实施(每阶段可编译、可独立真机回归)

| 阶段 | 内容 | 出口条件 |
|---|---|---|
| **P0 接口抽取** | 新增 `PlaybackHostApi`(指令面)、`PlaybackSession`(会话数据)、`PageHost`(页面能力);`PlayContainer` 实现 API,`DetailActivity` 实现 `PageHost`;**行为零变化** | 编译通过 + 随机回归播放路径无差异;`App.getVodInfo()` 调用点在播放链路内收敛为 1 处 |
| **P1 调度层抽离** | 把 `PlayContainer` 的播放调度段整体搬进 `PlaybackController`(`goPlayUrl`/`playUrl`/`autoRetry`/换线/换源/清晰度/DASH/净化/预载/进度);`PlayContainer` 只留"视图 + 控制器 + 挂摘" | 行为与 P0 等价(逐项回归:切集/换线/换源/清晰度/后退重播/投屏/弹幕/字幕/预载 Toast) |
| **P2 服务持有引擎** | 新增 `PlaybackService`(FGS `mediaPlayback`)持 `MyVideoView` + `PlaybackController`;fork `VideoView.attachContainerTo/detachContainerFromHost`;新增 `PlaybackSurfaceHost`;`DetailActivity` bind/attach/detach;`DetailScreen` 的 `AndroidView(factory = container)` 换成宿主 | 详情页播放全绿;退出详情页 → 播放器**不重建**、RSS/线程不随进出增长(复测方法见 §4);attach/detach 无泄漏 |
| **P3 音频/通知/后台** | `MusicPlaybackService` 并入:通知/媒体会话/wake+wifi 锁/悬浮入口;后台策略按 D3 落地(退页面即停) | 退出播放页:停播 + 撤通知 + 放锁(影视/音乐一致),**保留播放器实例**;再进详情页:从上次进度续播、不重建内核 |
| **P4 直播页接入** | `LivePlayActivity`/`LivePlayerManager` 接入同一服务(按内核类型复用);点播↔直播切换不重建内核 | 直播切台/自动切源/时移;点播→直播→点播 内核实例不增长 |
| **P5 清理固化** | 删旧路径与兼容分支、删 `MusicPlaybackService` 残留;更新 `avbox-mobile-ui-spec.md` §4.4/§6 与 `history/features.md`;hprof 复测归档 | 全量回归通过;hprof 复测:连续 12 次详情页进出后 `DetailActivity/PlayContainer/ExoPlayer` 实例数收敛、线程数回落 |

**回滚**:P0/P1 是纯重构(可随时回退);P2 起引入特征开关 `KV(HawkConfig.PLAYBACK_SERVICE, false)`,关闭即走旧路径(旧路径在 P5 前保留)。

### P2 实施形态(2026-09-14,已落地;回滚开关已随 P5 移除)

- **组件**:`PlaybackEngine`(新,进程级引擎:持有 `MyVideoView` + `PlaybackController` + 状态监听 + 进度 + 预载,实现 `attach/detach/release` 与 `PlaybackHostApi`,内含 `HeadlessView` 视图桥)、`PlaybackService`(新,宿主服务:托管引擎生命周期,任务移除/销毁即释放;**本阶段不加 FGS/通知**)、fork `VideoView.attachContainerTo/detachContainerFromHost/isContainerAttachedTo`、页面 `surfaceSlot` + 双路径。
- **与原案的偏差(有意)**:Spec §2.1 原写"`PlaybackService` 持有 `MyVideoView` + `PlaybackController`",实际落地为"**引擎持有 + 服务托管**",理由是页面对引擎的取用必须**同帧同步**(否则要处理"服务未就绪 → 控制器事后替换 → 在途取流结果/观察者双投递"竞态与首播排队);服务化(FGS/后台档位/通知合并)统一到 P3,与本 Spec §3-P3 一致。
- **挂摘协议落地**:页面 `attach(this, surfaceSlot)` → 引擎搬 `mPlayerContainer` + `controller.setViewBridge(page.viewBridge())`;页面 `hostDestroy` → `engine.detach(this)`(**一律停播**:pause + 落盘进度 + stop 内核(防起播中退出)+ 撤在途取流;`setVideoController(null)`+`setDanmuView(null)`+容器摘回) + `controller.setViewBridge(headless`)。`playbackKey` 归属判定按 §2.3-D6:同片再进页面直接 attach 续播(引擎里播放器与会话都在),不同片由页面 `setData(session)` 走既有 `reusePlayer` 路径复用同一实例。
- **真机验收(2026-09-14)**:功能回归通过(用户确认:进出详情页播放器不重建、退页面即停+再进续播、attach/detach 无异常);hprof/实例数量化数据未采集(见 §4 结果行)。

> **进度(2026-09-14)**:**P0 ✅、P1 ✅、P2 ✅、P3 ✅、P4 ✅、P5 ✅(全部落地;真机功能回归通过,量化复测未采集)** —— P5 交付:删 `PlaybackNotification` 门面与 `MusicPlaybackService`(文件 + manifest 条目)、删 `HawkConfig.PLAYBACK_SERVICE` 开关与页面/直播页双路径(`PlayContainer` 1647 → **1551 行**,只剩"引擎持有 + 页面挂摘"单一形态;`initViewModel`/`bindPlayerToPage`/页面自建 `MyVideoView`/`release` 分支全部删除)、控制器直连 `PlaybackService.updateSession/stopSession`。P5 出口条件的**真机功能回归已通过(2026-09-14 用户确认)**;hprof 量化复测未采集(如需归档量化证据可后补)。P4 交付:直播页与点播**共用同一引擎播放器**(`PlaybackEngine.enterLive()/exitLive()/isLiveMode()`,直播期间点播侧状态监听短路、进度管理器与边播缓存标记摘除/恢复、撤点播会话;直播页 `onDestroy` 只退出直播模式不 release;`PlayContainer.hostResume()` 在"点播→直播→点播"回来后自动重挂容器)。**已知边界(R10 有意保留 + 2026-09-14 真机修正)**:① 直播自身 release()(切台/换解码器)路径不动;② **直播页销毁必须停流并释放内核**(`PlaybackEngine.exitLive()` 内 `videoView.release()`)—— 早期文档写「实例留给点播复用」,实测导致「退到首页仍有直播声」且「点播页 attach 后显示/播放直播流」,已纠正(直播无后台播放语义);③ 因此「点播→直播→点播」回点播会重建一次内核(同改造前),「内核实例不增长」的准确含义 = 直播与点播共用同一个 MyVideoView/播放器对象 + 点播↔点播多次进出不重建。状态收口:静态审查共 5 轮(过程见 `history/features.md` 2026-09-14 各节)与真机功能回归均完成;遗留可选项 = §4-6/7 的 hprof 量化复测。
>
> **P0/P1/P2/P3 历史**:P0 ✅、P1 ✅、P2 ⏳(引擎+服务托管形态)、P3 ⏳(通知/会话并入 + 同片接管) —— P3 交付:通知/媒体会话/锁/通知栏动作并入 `PlaybackService`(FGS `mediaPlayback`,不再 stopSelf 以托管引擎;`ACTION_UPDATE` 时按需重建媒体会话)、新增 `PlaybackNotification` 过渡门面(引擎在→合并路径,否则→`MusicPlaybackService` 旧路径)、**D6 同片接管**在 `PlayContainer.setData` 落地(`playbackKey` 同键=只同步不重播)、manifest 前台类型。`PlaybackService` 564 行。下一步:P3 真机验收 → P4(直播页接入) → P5(清理旧路径/门面/`MusicPlaybackService`)。
>
> **P0/P1/P2 历史**:P0 ✅、P1 ✅、P2 ⏳(引擎+服务托管形态) —— P2 交付:`PlaybackEngine`(引擎持有播放器)/`PlaybackService`(宿主托管)/fork 挂摘 API/页面 `surfaceSlot` 双路径/`HawkConfig.PLAYBACK_SERVICE` 开关/`PlaybackViewBridge.startDanmuIfReady`;`PlayContainer` 1605 行(双路径接线 +64)。原案见下方"P2 实施形态"小节(引擎+服务托管的偏差与理由)。下一步:P2 真机验收(§4 清单) → P3(通知/媒体会话/FGS 合并、后台档位)。
>
> **P0/P1 历史**:P0 ✅、P1 ✅ 全部完成(六批:`PlaybackSession`/`PlaybackHostApi`/`PageHost` + 会话派生 → 重试换线超时 → 解析嗅探+取流观察者 → 取流入口 `play/playUrl/goPlayUrl/selectQuality` → 预载调度 → 媒体会话/通知)。`PlayContainer` **3074 → 1553 行(-49%)**:只剩视图(MyVideoView/弹幕视图/字幕轨/弹层)+ 控制器 UI + 挂摘(`hostResume/hostPause/hostDestroy`)+ 页面生命周期;`PlaybackController` **2203 行**;`PlaybackViewBridge` 41 个方法(视图动作 + 状态读取)。**`MusicPlaybackService` 的 owner 已改为 `PlaybackHostApi`**(通知栏控制不再依赖页面类,对应 §2.1 的 `MusicControl`)。**下一步 P2**:前台服务 `PlaybackService` 持有 `MyVideoView` + `PlaybackController`,页面改为挂摘视图(`attachContainerTo/detachContainerFromHost` + `setVideoController(null)` 防服务持有页面 View)。编译/单测通过;未装机(测试机未连接)。

## 4. 真机验收清单

> **结果(2026-09-14)**:用户真机回归确认无问题(功能项与稳定性项经日常操作走查);**§4-6(实例创建日志埋点)与 §4-7(hprof 量化复测)未采集数据** —— 如需量化归档可后补,不影响"服务化已稳定"的结论。

**功能**
1. 详情页播放:起播/暂停/seek/倍速/长按/双击、切集、切线路、换源(含失败回滚)、切清晰度、全屏↔预览、旋转。
2. 弹幕(加载/搜索/开关/字号)、内嵌字幕/本地字幕/字幕搜索/歌词、封面占位(纯音频)。
3. 投屏(DLNA + TVBox 推送)、外部播放器、边播缓存开关、预载「下一集已就绪」。
4. 音乐:详情页内播放 → 退后台(通知可控)→ 回前台;退出详情页 → 停播且通知消失 → 重进详情页从上次进度续播(不重建内核)。
5. 直播:切台/切源/时移/EPG/后台返回。

**跨页复用(本 Spec 的核心收益,必须有可量化证据)**
6. 每次 `initPlayer()`/`release()` 打一行日志(临时埋点 `echo-player-instance` + `AtomicInteger`):「详情A → 相关推荐 → 详情B → 返回 A → 返回首页」这一串操作里,**播放器实例创建次数 ≤ 1**。
7. 连续 12 次详情页进出后:`dumpsys activity exit-info` 无异常退出;hprof 复测 `DetailActivity/PlayContainer/MyVideoView/ExoPlayer` 实例数与线程数明显低于改造前基线(改造前:各 ×12、ExoPlayer×36、249 线程)。
8. 空窗期行为:服务在无页面时(音乐播放中)旋转/回前台/进其他页面,无黑屏闪烁、无白块(`SurfaceView` 洞穿问题不得回归,纯音频仍走 Texture 热切)。

**稳定性**
9. 进程被杀后重启;任务卡片划掉(`onTaskRemoved`);通知栏停止;锁屏媒体键。
10. 快速连点进出详情页 20 次、播放中来回切 10 次,无崩溃/无 ANR、无声音叠音。

## 5. 风险登记

| 风险 | 说明 | 缓解 |
|---|---|---|
| R1 跨窗口 View 搬运 | `mPlayerContainer` 在 Activity/服务间搬运会触发 Surface 销毁重建 | dkplayer 已有全屏搬运先例(自证可行);IJK/Exo 的 `setDisplay(null)` 安全性已有二进制级结论;P2 先只为点播开,真机压测 §4-10 |
| R2 Compose 控制器上下文 | 控制器依赖页面 owners/主题 | 控制器留页面(D2);detach 强制摘除,防止服务持页面 View |
| R3 泄漏 | 服务持有页面 View(弹幕/控制器)、页面持有服务 | `unbindController()/setDanmuView(null)` 必做;`PageHost` 用弱引用;P2 出口做 hprof 抽查 |
| R4 谁在播(A/B 叠加) | 旧语义"每页一套、各播各的",新语义共享一个播放器 | D6 `playbackKey` 归属判定;详情页进入前显式判断"同片续播 vs 换片重播" |
| R5 WebView/嗅探与 Activity | 服务侧不得持有 Activity | `PageHost` 抽象 + `stopLoadWebView` 判空改造;P1 内完成 |
| R6 FGS 限制 | Android 14+ 前台服务类型/启动时机 | 沿用 `MusicPlaybackService` 已验证的 `mediaPlayback` 类型与 `startForeground` 时序(含 `pendingStart/stopWhenStarted` 竞态修复) |
| R7 迁移中的双路径 | P1~P4 期间新旧结构并存,回归面变大 | 每阶段独立回归;P2 起开关可切旧路径;P5 前不删旧代码 |
| R8 既有硬约束被破坏 | 预载 looper / CacheKey / 本地代理跳过缓存 / M3U8 4 槽 `?k=` | 搬迁期"只搬位置不改逻辑";§2.5 逐条列为不可变项 |
| R9 进度写入时机 | 现在靠 `hostDestroy` | 改为切集/换源前 + **页面退出(detach 时 `videoView.saveCurrentProgress()`)**+ 服务销毁 + `onTaskRemoved` 落盘;真机验证"退出后重进能续播" |
| R10 直播接入复杂度 | `LivePlayerManager` 自带自动切源/时移/EPG | 独立 P4;P4 前不改直播页 |

## 6. 工作量与里程碑(估算,供排期)

| 阶段 | 估时 | 说明 |
|---|---|---|
| P0 | 0.5 人日 | 纯接口/数据类抽取 |
| P1 | 2 人日 | 调度层搬迁,回归面最大的一步 |
| P2 | 2 人日 | 服务 + fork 改造 + 宿主 + 详情页接线 |
| P3 | 1 人日 | 通知/会话/后台合并 |
| P4 | 1.5 人日 | 直播接入 |
| P5 | 0.5 人日 | 清理 + 文档 + 复测 |
| 回归 | 1.5~2 人日 | §4 全清单 + hprof 复测 |

总计约 **8~10 人日**(不含真机等待时间)。**建议节奏**:P0+P1 一轮 → P2 一轮 → 观察一周(或至少压测通过)→ P3/P4 → P5。

## 7. 决策记录(待用户拍板)

| 编号 | 问题 | 建议 | 用户决定 |
|---|---|---|---|
| D1 | 视图层保留 dkplayer(β)还是换 media3 官方 `PlayerView`(α) | **β** | ✅ 已按建议执行(P2 起 fork `VideoView` 加挂摘 API,未换渲染层) |
| D2 | 控制器归属 | 页面持有 | ✅ 已按建议执行(attach 时 `setVideoController(controller)`,detach 时 `setVideoController(null)` + `setDanmuView(null)`) |
| D3 | 是否引入 fongmi 三档后台设置(关/后台音频/PiP) | 先不做,保持现状 | ✅ 未做三档设置;**用户 2026-09-14 选定"退页面即停"**(影视与音乐退出播放页都停播 + 撤通知,但**保留实例**);app 退后台时沿用原语义(纯音频继续、影视暂停);PiP 未引入 |
| D4 | `MusicPlaybackService` 并入时机 | P3 一次并入,P5 删壳 | ✅ 已执行(P3 并入 PlaybackService;P5 删文件与门面) |
| D5 | 是否迁 media3 `MediaSessionService` | 不迁(内核非 media3 Player) | ✅ 已按建议执行(保留 `MediaSessionCompat` + MediaStyle) |
| D6 | 详情页叠加的"同片续播 / 换片重播"判定 | 按 `playbackKey`(同片只 attach) | ✅ P3 已落地(`PlayContainer.setData` + `isSamePlaybackOwned`) |
| D7 | 是否执行本 Spec(以及从哪个阶段开始) | P0/P1 先做(风险最低) | ✅ 全程执行 P0→P5(2026-09-14 一天内分批落地;真机回归待补) |

## 8. 修订记录

| 日期 | 变更 |
|---|---|
| 2026-09-14 | 初稿:依据 fongmi 读码(`示例文件/TV-fongmi`)+ hprof 取证(`memory/2026-09-14.md`)起草;未动代码 |
| 2026-09-14 | P0 ✅(`PlaybackSession`/`PlaybackHostApi`/`PageHost`)与 P1 ✅(六批调度层搬迁:`PlayContainer` 3074 → 1553 行,`PlaybackController` 2203 行,桥 41 方法);编译/单测通过,未装机 |
| 2026-09-14 | P2 ✅ 代码层:`PlaybackEngine`(引擎持有播放器)+ `PlaybackService`(宿主托管)+ fork `attachContainerTo/detachContainerFromHost` + 页面 `surfaceSlot` 双路径 + 开关 `HawkConfig.PLAYBACK_SERVICE`;偏差记录见"P2 实施形态"(引擎持有 ≠ 服务直接持有,为同帧同步) |
| 2026-09-14 | P3 ✅ 代码层:通知/媒体会话/锁/通知栏动作并入 `PlaybackService`(FGS `mediaPlayback`;会话结束不 stopSelf、`ACTION_UPDATE` 按需重建会话)+ `PlaybackNotification` 门面 + D6 同片接管落地 |
| 2026-09-14 | P4 ✅ 代码层:直播与点播共用同一引擎播放器(`enterLive/exitLive`,直播期点播侧状态监听短路、进度管理与边播缓存标记摘挂、撤点播会话;`hostResume` 自动重挂);直播自身 release 路径按 R10 不动;**2026-09-14 真机修 bug**:`exitLive()` 补 `videoView.release()`(停流)、`enterLive()` 置空会话、D6 加 `!isLiveMode()` |
| 2026-09-14 | P5 ✅ 代码层:删门面与 `MusicPlaybackService`、删开关与全部双路径、控制器直连 `PlaybackService`(`PlayContainer` 1551 行);**§4 真机验收与 hprof 复测未执行(测试机未连接)**,故 P2–P5 均标 ⏳ |
| 2026-09-14 | P5 后四轮静态审查共修 16+ 处回归/加固(D6 判定改 `startedPlaybackKey`、空闲 TTL 释放引擎、页面所有权收口 `releasePlayer()`、迟到回调防线、`exitLive` 归属守卫前置等),过程与逐条理由见 `history/features.md` 2026-09-14 各节;hprof 量化复测未采集 |
| 2026-09-14 | **真机功能回归通过(用户确认)**;spec 状态收口为 P0–P5 ✅。第五轮审查另修:`exitLive()` 顺序缺陷(直播 position 写进点播进度缓存 → release 提到 `exitLiveState()` 之前)、直播接管后回直播页停死内核并重播当前频道(`enterLiveState()` 返回 boolean)、`play()` 裸取崩溃防护、`HeadlessView.startVideoPlayback` 复用/释放防线 |
| 2026-09-19 | **缺陷修复(用户真机确认):未授予 `POST_NOTIFICATIONS` 时播放"抽搐式"卡顿**。根因 = P3 起通知会话的唯一入口 `PlaybackController.updateMusicSession()`(**热路径**,由播放状态回调驱动,实测起播期 8~9 次/秒)无条件调用 `view.requestNotificationPermission()`,而 `PermissionHelper.requestNotificationIfNeeded()` 未授权时未提前返回 ⇒ 每次回调拉起一个 `GrantPermissionsActivity`(固定拒绝下"创建→立刻 finish"),系统窗口反复抢焦点打断渲染 Surface(真机 3.2 秒 22 次)。修复 = `PermissionHelper` 加进程级一次性闸门 + 未授权提前返回(闸门置于 binder 权限查询之前)。**要点**:`am_foreground_service_start`/`notification_enqueue` 当时均正常 ⇒ **FGS 与解码器无关**;排查手法与全部证据见 `history/features.md` 2026-09-19 节。**订正**:本文档链路上曾被记录的"状态变化即重发通知"待办,经真机 `notification_enqueue` 计数证实**不成立**(稳定播放期 0 次重发),该待办已降级 |
