---
name: 包循环依赖拆解 · S0 + 刀③ + 刀⑤ + 刀② + 刀①（全部落地）
status: 全部落地，已提交（2026-10-25；SCC-4 已散、SCC-10 已散：最大 SCC 10/53 → 2/53，达到方案预期终态；待真机走查 5 项）
source: 2026-10-25 用户指令「先加载项目规范，然后开始 s0，没有我的允许不可 commit」「继续刀3」「继续刀5」「继续刀2」「继续刀1」
---

# 结论摘要

- **SCC-4 已经散掉**：`player.core ↔ player.effect ↔ player.effect.anime4k ↔ player.state` 四包环消失，`pkg_cycles.py` 环簇 5 → 4。
- **纯搬移、零行为变更**：`PlayState.kt` 整文件从 `player/state` 迁 `player/core`，文件内容只改 `package` 一行；无逻辑改动、无签名改动、无新增断言。
- **`player.core` 成为纯底座**：项目内出边归零（原唯一出边 `core→state` 随迁移消失），`state` 剩 `→ core` / `→ effect` 两条合法下行边。
- 构建与单测全绿（1428 用例 / 0 失败）；`scc_full.py` 的最大 SCC 仍是 10/53，说明本刀不动 SCC-10，符合方案预期。

# 1. 改动

## 1.1 搬迁

| 项 | 内容 |
| --- | --- |
| 文件 | `player/state/PlayState.kt` → `player/core/PlayState.kt`（`git mv`，重命名历史保留） |
| 内容改动 | 仅第 1 行 `package` |
| 随迁符号 | `PlayState` 枚举、`KernelPlayback`、`deriveKernelPlayState`、`PlaybackStateMachine` |
| 随迁理由 | 后三者为内核侧状态机与派生函数（`KernelPlayer` 回调签名就用 `PlayState`），不是 UI 态；`state` 包留给 `PlayerUiState` / 屏幕形态 / 音频态等 UI 面 |

## 1.2 引用改写（36 个文件）

| 类别 | 数量 | 说明 |
| --- | --- | --- |
| 显式 import 改写 | 30 | `player.state.PlayState` → `player.core.PlayState`；`ExoPlayer` 另含 `PlaybackStateMachine`/`deriveKernelPlayState`，`PlayerEngine` 另含 `KernelPlayback` |
| 同包隐式引用补 import | 5 | `PlayerUiState`（main）+ `PlayStateTest`/`PlaybackStateMachineTest`/`KernelPlayStateDerivationTest`/`PlayerUiStateVisibilityTest`（test） |
| 同包冗余 import 删除 | 1 | `player/core/KernelPlayer.kt` 原来的 `import ...player.core.PlayState` 归位后变成同包冗余 |

# 2. 验证

| 项 | 口径 | 结果 |
| --- | --- | --- |
| 构建 | `:app:assembleDebug` | BUILD SUCCESSFUL |
| 单测 | `:app:testDebugUnitTest` | 188 类 / **1428 用例 / 0 失败 / 0 错误 / 3 跳过** |
| 空断言门禁 | `null_assert_gate.py` | PASS |
| 依赖环 | `pkg_cycles.py` | 包 54 / 边 248 / 互导对 6 / **环簇 4**（原 5，SCC-4 消失） |
| 巨环基线 | `scc_full.py` | 最大 SCC 仍 10/53（本刀不处理 SCC-10） |
| 旧路径残留 | `grep player\.state\.(PlayState\|KernelPlayback\|PlaybackStateMachine\|deriveKernelPlayState)` | 0 处 |
| 反射/序列化面 | `"PlayState"` 字面量、`PlayState::class`、`PlayStateKt`、proguard 规则、KV/Gson 登记 | 均无引用，搬包无兼容风险 |
| 源集覆盖 | `main` / `test` / `python`；无 `androidTest` | 全量覆盖 |

边数 247 → 248 的 +1 已逐对核对，属良性：新增 `ui.activity→core`、`ui.music→core` 两个目标对（这两个包原本只从 `state` 取 `PlayState`），同时 `player.engine→player.state` 整条消失（`PlayerEngine` 只从 state 引过 `KernelPlayback`），叠加 `core→state` 反转为 `state→core`，净 +1。

# 3. 两条执行口径（后续刀次沿用）

1. **搬包必须先按旧包名反查目录，而不是只扫 import**。同包引用没有 import 行，`grep 'player\.state\.PlayState'` 一条都扫不到。本次因此漏了 5 个文件，第一次构建直接编译失败。正确做法：列出所有 `package <旧包>` 的文件，逐个查裸符号引用。
2. **批量改写脚本必须用 bytes 或 `newline=""` 模式**。本次用 `Path.read_text()` + `write_text()` 文本模式，通用换行把 30 个 LF 文件整体写成 CRLF；而 `core.autocrlf=input` 会在读工作区时归一化，`git diff` 完全看不见这种污染（只有逐字节数 `\r\n` 才查得出）。已按字节还原为 LF。

# 4. 遗留

- **`player.core` 语义边界**：`PlaybackStateMachine`（带 `MutableStateFlow`）随迁入核心包，`core` 比"契约层"原本的定位宽了一格。若要更纯粹，可把 `PlayState.kt` 拆两份（枚举 + 派生函数留 `core`，状态机留 `state`）；不影响任何环，属口径偏好，未做。
- **历史 CRLF 残留**：`ZeroStartGate.kt`、`core/EffectCanvas.kt`、`core/HttpHeaders.kt`、`core/DecodeKindRule.kt`、`VideoGestureHandler.kt`、`ScallopShape.kt`、`ThemeColorPickerSheet.kt`、`EpisodeTotalsTest.kt`、`ZeroStartGateTest.kt`、`PictureProfileTest.kt` 共 10 个文件仍是 CRLF，为更早批次工具留下的，与本次无关，未处理。
- **后续刀次**：按方案顺序 `⑤ → ② → ①`，每刀独立提交、独立复测。

# 5. 刀③：断 `sourcedata → base`

## 5.1 改动（1 文件 4 行）

`PushDetailResolver.kt` 是 `sourcedata` 包唯一引用 `base` 的文件，两处用法都只要 `Context`、不碰 `App` 的成员：

| 位置 | 改前 | 改后 |
| --- | --- | --- |
| `:9` import | `com.github.tvbox.osc.base.App` | `com.github.tvbox.osc.util.AppContextHolder`（并入 `util.*` 组） |
| `:150` | `requireNotNull(App.getInstance()) { "app instance not ready" }` | `requireNotNull(AppContextHolder.context()) { "app context not initialized" }` |
| `:206` | `val app = App.getInstance()` | `val context = AppContextHolder.context()` |

语义等价依据：`AppContextHolder.context()` 返回 `Context?`，与 `App.getInstance()` 的可空性一致；`App.onCreate` 里 `instance = this` 与 `AppContextHolder.install(this)` 是相邻两行，运行期不存在"实例有值但上下文未装"的窗口。`:206` 的 `if (null) ""` 兜底原样保留 —— 那是既有静默降级，改成 `requireNotNull` 属行为变更，超出拆环范围。

## 5.2 验证

| 项 | 结果 |
| --- | --- |
| 构建 / 单测 | BUILD SUCCESSFUL；1428 用例 / 0 失败 / 3 跳过（与 S0 后持平） |
| `null_assert_gate.py` | PASS |
| `pkg_cycles.py` | 边 248 → **247**；`sourcedata` 出边已不含 `base`；`base` 入边表已无 `sourcedata`；环簇仍 4 |
| `scc_full.py` | 最大 SCC 仍 10/53 —— 符合预期，刀③ 只砍回边，SCC 要等刀①/②/⑤ 才散 |
| 残留检查 | `sourcedata/**` 对 `osc.base` 零引用 |

# 6. 刀⑤：断 `player.usecase → sourcedata`

## 6.1 改动（2 文件 3 行）

`PlayerSwitchUseCase.stopOther()` 里的 `Thunder.stop(false)` 是 `usecase` 包对 `sourcedata` 的唯一依赖，且 `stopOther()` **全仓只有一个调用方** `VideoPlayerController.stopOther()`（`ui/player` 经 `PlayContainer.mController` 进来）。

| 文件 | 改动 |
| --- | --- |
| `VideoPlayerController.kt` | `stopOther()` 里先 `Thunder.stop(false)` 再调 `PlayerSwitchUseCase.stopOther()`；新增 `sourcedata.Thunder` import |
| `PlayerSwitchUseCase.kt` | 删 `Thunder.stop(false)` 与 `sourcedata.Thunder` import，`stopOther()` 只留 `setDashData(null)` |

**与方案 §2.4 的差异**：方案写"经构造注入的回调/接口上提"。实际 `PlayerSwitchUseCase` 是 Kotlin `object` + `@JvmStatic`，**没有构造器**，回调只能是可赋值的全局字段（还要解决谁在什么时候赋值、未赋值时静默 no-op）—— 比现在的写法多两层间接、零收益。既然 `stopOther()` 只有一个调用方，直接把调用上提到那个调用点即可，等价且更短。

代价：新增 `player.controller → sourcedata` 一条包对（`Thunder` 是 data 层，controller 是覆盖层）。判定可接受：`player → sourcedata`、`ui.activity → sourcedata`、`ui.player → sourcedata` 都已存在，该方向无环；净边数不变（−1 旧边 +1 新边）。

## 6.2 验证

| 项 | 结果 |
| --- | --- |
| 构建 / 单测 | BUILD SUCCESSFUL（40s）；1428 用例 / 0 失败 / 3 跳过 |
| `pkg_cycles.py` | 边 247 → 247（−1/+1 抵消）；环簇 4；SCC 9 → **8**，`sourcedata` 脱离 |
| `scc_full.py` | 最大 SCC **仍 10/53** |
| 残留检查 | `player/usecase/**` 对 `osc.sourcedata` 零引用 |

## 6.3 ⚠️ 两个工具在这一刀上给出了相反的结论（重要）

`pkg_cycles` 报 SCC 9→8（看起来"大环松了"），`scc_full` 报仍 10/53。**后者才对**，原因是 `server → player` 这条回边是 FQN 调用、没有 import 行，`pkg_cycles` 根本看不见它。用 FQN 口径重算，`sourcedata` 的返回路径是：

```
sourcedata → catvod.crawler   (DetailLoader.kt import SpiderReaper)
catvod.crawler → server       (JarLoader.kt 内联 com.github.tvbox.osc.server.ControlManager，无 import)
server → player               (RemoteServer.kt:286 PlaybackService.peek()，FQN)
player → sourcedata           (PlaybackController.kt import SourceHelper/SourceViewModel)
```

即**大环是被刀①（`server→player`）扣住的，不是刀⑤**。刀⑤ 的真实战果是打掉本次分层归位新引入的三包三角 `api → player.usecase → sourcedata → api`（这一条 `pkg_cycles` 看得到，也确实散了）。

结论：**SCC-10 的验收只能看 `scc_full.py`**；`pkg_cycles.py` 只适合看 import 级环（如已完成的 player SCC-4）与包对明细。方案 §1.3 用 `pkg_cycles` 验 SCC-4 没问题，但 §2.6 的总验收口径需要按此修正。

# 7. 刀②：删冷启动兜底 ports（断 `base → player`）

## 7.1 改动（1 文件，删 10 行）

`App.onCreate` 里那份 `PlaybackPorts.ports = object : PlaybackDataPorts { ... }`（5 个 `PlaybackService.peek()` FQN 实现）整块删除，连带删掉 `data.PlaybackDataPorts` / `data.PlaybackPorts` 两个 import。删除后 `base` 包对 `player` 零引用（import 与 FQN 双口径）。

## 7.2 等价性核对（方案 §2.2 要求的消费方表）

| 消费方 | `ports == null` 时 | 改前（ports 非空、engine 可能为 null） | 结论 |
| --- | --- | --- | --- |
| `AppBootstrap.kt:160` `hasAttachedPage()` | `?.hasAttachedPage() == true` → false → 不 defer | `peek()?.attachedPage() != null` → false | 等价 |
| `PlaybackProgress.kt:120` `isLiveMode()` | `== true` 不成立 → 继续标记已看 | `peek()?.isLiveMode() == true` → false | 等价 |
| `WatchProgressStore.kt:146/147/186/187/253` `discardStartedContentOf`/`onProgressKeysCleared` | `?.` 跳过 | `peek()?.` 内部全 no-op | 等价 |
| `WatchProgressStore.kt:246` `currentVod()` | `?.currentVod()?.let{}` → null | `peek()` 为 null → 同样 null | 等价 |

全部 8 个消费点统一是 `PlaybackPorts.ports?.` 形式，**没有一处 `!!` 或非空形参**，所以"null ports"与"非空 ports 但内部 peek() 为 null"在行为上完全一致。

新不变量：`ports != null ⟺ PlaybackService.engine != null`。`installDataPorts()` 只在 engine 首次创建的两条路径上调用（`engine(context):500` 与 `ensurePrewarmed():552`，两处都在 `engine == null` 分支内），因此 ports 的安装时机与 engine 的生命周期严格同步 —— 而这正是 App 侧那份兜底实现的语义（`peek()` 为 null 时全 no-op）。

## 7.3 验证

| 项 | 结果 |
| --- | --- |
| 构建 / 单测 | BUILD SUCCESSFUL（53s）；1428 用例 / 0 失败 / 3 跳过 |
| 唯一注册点 | `grep "PlaybackPorts.ports *="` 只剩 `PlaybackService.kt:510-511` |
| `base` 出边（FQN 口径） | catvod.crawler / boot / data / io / net / server / ui / util —— **已无 player** |
| `scc_full.py` | 最大 SCC 仍 10/53（预期：`base` 仍经 `base→server→base` 扣着，等刀①） |
| `pkg_cycles.py` | 边 247、环簇 4、SCC 8 均不变（`base→player` 是 FQN 边，它本来也看不见） |

# 8. 刀①：server 出口反转（SCC-10 收口）

## 8.1 改动（新建 1 文件，改 4 文件）

| 文件 | 改动 |
| --- | --- |
| `server/ServerQueryPorts.kt`（新） | `interface ServerQueryPort { dashData(): String?; proxyLocal(params): Array<Any?>? }` + `object ServerQueryPorts { @Volatile var ports }`，与 `data.PlaybackPorts` 同构 |
| `server/RemoteServer.kt` | ① `:218` `App.getInstance()?.getDashData()` → `ServerQueryPorts.ports?.dashData()`；② `:274` `ApiConfig.get().proxyLocal(params)` → `ServerQueryPorts.ports?.proxyLocal(params)`；③ `:286` `PlaybackService.peek()?.controller()?.vod()` → `PlaybackPorts.ports?.currentVod()`；删 `api.ApiConfig` / `base.App` import，加 `data.PlaybackPorts` |
| `server/ControlManager.kt` | `com.github.catvod.Proxy.set(RemoteServer.serverPort)` → `onProxyPortReady?.invoke(RemoteServer.serverPort)`；companion 新增 `@Volatile var onProxyPortReady: ((Int) -> Unit)?` |
| `api/AppBootstrap.kt` | 新增 `installServerPorts()`：注册 `ServerQueryPorts.ports`（`dashData` 取 `App.getDashData()`、`proxyLocal` 取 `ApiConfig.proxyLocal`）并把 `ControlManager.onProxyPortReady` 设为 `Proxy::set`；在 `start()` 里**同步**调用（启动协程之前），并补一次 `startedPort()` 对账 |

`server` 包出边最终只剩 `bean / data / io / net / util`，对 `api` / `base` / `player` / `sourcedata` / `catvod` 全部为零（import 与行内 FQN 双口径均已核）。

## 8.2 三个设计取舍

1. **端口注册放 `AppBootstrap.start()`（api 包），不放 `App.onCreate`（base）**：`base→api` 一旦新增就是 `api↔base` 新双包环（`api→base` 本来就存在）。而 `AppBootstrap` 已有 `api→server` / `api→base` / `api→data` 三条边，注册零新增依赖。
2. **`Proxy.set` 用回调而非"调用上提"**：`startServer()` 有两个触发源 —— `AppBootstrap` 显式调用，以及 `getAddress()` 内部按需起服（`if (mServer?.isStarting() != true) startServer()`）。若把 `Proxy.set` 移到 `AppBootstrap`，后一条路径会漏设端口。回调挂在 `ControlManager` 上，两条路径都能覆盖，且"最终成功端口才 set"的次序不变（仍在 `server.start()` 成功之后、`break` 之前）。
3. **冷启动窗口按方案 A 降级，并补一次对账**：复核时发现一条真实竞态 —— `ControlManager.init`（`App.onCreate`）把 `LocalAddress.provider` 指向 `getAddress(true)`，任何早于 `AppBootstrap.start()` 的 `LocalAddress.get()` 都会把服务先起起来。那时 `onProxyPortReady` 还是 null，若只按方案 A 处理，`catvod.Proxy` 会**整场会话都拿不到端口**（`startServer()` 早退，回调不会再触发），表现为站点代理全挂。故：
   - `installServerPorts()` 从启动协程里移到 `start()` 里**同步**执行（`LaunchedEffect` / `onCreate` 调用即注册，不再等协程调度）；
   - 注册后追加一次 `ControlManager.get().startedPort()`（新增方法：服务已在跑则返回当前端口，否则 0）并调 `Proxy.set` —— 即使服务被提前起起来，也会被这条对账补上端口。
   `ports` 未注册时 `dashData()` 返回 null（原路径 `App.getInstance()?.getDashData()` 同样可能为 null，`Base64.decode(null)` 抛异常后走 `INTERNAL_ERROR` 分支），`proxyLocal()` 返回 null（`getProxy(null,...)` 返回 500 "empty proxy result"）—— 与方案 A 的降级口径一致。

## 8.3 验证

| 项 | 结果 |
| --- | --- |
| 构建 / 单测 | BUILD SUCCESSFUL（54s）；1428 用例 / 0 失败 / 3 跳过 |
| **`scc_full.py`** | 最大 SCC **10/53 → 2/53**（`util, util.kv`）—— SCC-10 散了 |
| `pkg_cycles.py` | 互导对 **6 → 4**（`api↔server`、`base↔server` 消失）；环簇 4，全部是 2 包环 |
| 边数 | 247（−2 `server→api`/`server→base`，+2 `server→data`/`api→com.github.catvod`，相抵） |
| 终态环清单 | `util↔util.kv`、`catvod.crawler↔catvod.crawler.js`、`subtitle.format↔subtitle.model`、`ui.activity↔ui.page` —— **与方案 §2.6 预期终态逐项一致** |

## 8.4 需真机走查（本刀唯一的行为面）

四条回边全走"有外部请求时才触发"的路径，单测覆盖不到：

1. 冷启动后首页正常起服，本地地址可访问（`getAddress` 两条路径都仍能起服）；
2. 推送 / 投屏详情（`/dash/` 路径 → `dashData`）正常；
3. 弹幕代理（`/proxy?do=danmu` → `normalizeDanmuParams` 读 `currentVod`）在播状态下正常，`vodName`/`vodIndex` 能补上；
4. 站点代理（`/proxy?do=...` → `proxyLocal`）正常取流；
5. `catvod.Proxy.set` 拿到的端口与 `ControlManager` 实际监听端口一致（起服重试后仍一致）。

# 9. 五刀合并自审（2026-10-25）

## 9.1 覆盖度

审查了本批全部改动（S0 + 四刀 + 文档），共 5 次构建、4 次依赖复测。检查项：引用完整性、空安全与门禁、初始化时序与线程、依赖方向、文档一致性、未跟踪文件范围。

## 9.2 发现与处置

| # | 严重度 | 归属 | 内容 | 处置 |
| --- | --- | --- | --- | --- |
| 1 | **中** | 本次引入 | 服务可能在 `AppBootstrap.start()` 之前被 `LocalAddress.get()` 提前拉起；此时 `onProxyPortReady` 为 null，`catvod.Proxy` 整场会话都拿不到端口（`startServer()` 早退不会补调），站点代理全挂 | **已修**：注册改为 `start()` 内同步执行；新增 `ControlManager.startedPort()`，注册后补一次 `Proxy.set` 对账 |
| 2 | 低 | 本次引入 | `player.controller → sourcedata` 为刀⑤ 新增的包对 | 接受：方向与既有 `player/ui.*→sourcedata` 一致，无环；已在 §6.1 登记 |
| 3 | 低 | 本次引入 | `api → com.github.catvod`（根包）为刀① 新增的包对（`import com.github.catvod.Proxy`） | 接受：`api→catvod.crawler` 本来就存在，同族；无环 |
| 4 | — | 既有 | `RemoteServer` 的 `/dash/` 分支在 `dashData == null` 时会把 null 当响应体传下去 | 不修：与改造前行为一致（`App.getInstance()?.getDashData()` 同样可为 null），属既有缺陷，登记不动 |

## 9.3 逐刀等价性复核结论

- **S0**：纯搬移（文件内容只改 `package`）；34 文件引用改写，14 个 HEAD 干净文件逐一比对等于预期变换；`core` 出边归零。
- **刀③**：`Context?` 可空性与原 `Application?` 一致；`:206` 的 `if (null) ""` 兜底原样保留，无行为变更。
- **刀⑤**：`PlayerControlApi` 全仓**唯一实现**就是 `VideoPlayerController`，调用顺序（先停迅雷、再清 dashData）与调用时机不变。
- **刀②**：8 个消费点全为 `?.`，无 `!!`；`PlaybackService` 的 engine 创建点仅 `:499`/`:551` 两处且都紧跟 `installDataPorts()`，新不变量 `ports != null ⟺ engine != null` 成立。
- **刀①**：`server` 出边只剩底座方向；三条读边全部 `?.` 降级，与方案 A 口径一致。

## 9.4 验证汇总（最终态）

```
assembleDebug + testDebugUnitTest : BUILD SUCCESSFUL，1428 用例 / 0 失败 / 3 跳过
null_assert_gate.py               : PASS
i18n_gate.py                      : ui 层硬闸门 0 处（非 ui 存量 44 处未变）
scc_full.py                       : 最大 SCC 2/53（util, util.kv）
pkg_cycles.py                     : 互导对 4、环簇 4（全 2 包环）
```

## 9.5 遗留（不在本批范围）

- 历史 CRLF 文件 10 个（更早批次工具留下）；
- 活规范中 3 处历史叙述段的旧路径（`avbox-kotlin-migration-spec.md:635/859`、`avbox-okgo-removal-spec.md:135`）；
- `player.core` 装状态机的语义边界问题：已判定不拆（见 2026-10-25 记忆与 spec `core/*` 描述）。

## 9.6 第二轮复审（2026-10-25，因上一轮有中级而重跑）

**复核上一轮修复**：`installServerPorts()` 已同步化；`startedPort()` 的判定链为 `isStarting()` → `RemoteServer.serverPort`，与 `startServer()` 成功分支写入的是同一份状态；回调与对账两条路径互不冲突（同一端口重复 `Proxy.set` 幂等）。修复成立。

**本轮新发现（低，本次引入的读）**：

`ControlManager.startedPort()` 在主线程读 `mServer`，而 `startServer()` 跑在 `AppBootstrap.scope`（`Dispatchers.IO`）——`mServer` 与 `RemoteServer.isStarted` 都是普通 `var`，跨线程读写没有可见性保证。极端情况下主线程读到 `null`/`false`，就会跳过对账，把上一轮修掉的问题在罕见时序下重新暴露。

**处置（已修）**：`ControlManager.mServer` 与 `RemoteServer.isStarted` 加 `@Volatile`。语义不变，只补可见性；`serverPort` 未加 —— 它的读发生在 `isStarted == true` 之后，volatile 读已建立 happens-before，无需重复。

**本轮其余检查**（无新发现）：

- `PlayerControlApi` 唯一实现确认；`PlaybackEngine.stopOtherPlayers()` 是空实现，不存在"另一条路径漏掉 `Thunder.stop`"；
- `App.getDashData()` 声明为 `String?`、初值 null，与 `ServerQueryPorts.ports?.dashData()` 的 null 语义一致，`/dash/` 空数据路径行为未变；
- `catvod.Proxy.set` 原本就在 `try` 内、与 `server.start()` 同段，异常语义未变（`IOException` 仍走端口自增重试）；
- `server` 包 import 与行内 FQN 双口径复查：对 `api/base/player/sourcedata/catvod` 仍为零出边；
- `PlaybackPorts` 的 engine 创建点仍只有 `:499`/`:551`，`ports` 未安装时 8 个消费点全部 `?.` 降级。

**收敛判定**：本轮无阻断 / 高 / 中级发现，剩余项均为低（已修）或既有登记项 ⇒ 按 `avbox-code-review-spec.md` 的终止线**可以收尾**。剩余风险全部落在真机走查（§8.4 五项）。





# 10. 附：ui 层穿透治理（同日，包环清零后的下一项）

包环清零后复测发现另一个结构问题：`ui.*` 对 `data`/`api`/`base`/`sourcedata`/`net` 有直接依赖。
按文件类型拆开后，**53 处来自 ViewModel（合理，MVVM 正常方向）**、14 处来自 Activity、**50 处来自 Composable（真违规）**。
口径修正：不能把"包级边"等同于"绕过 ViewModel"。

## 10.1 第 1 步（已落地）

| 改动 | 结果 |
| --- | --- |
| `base/BaseActivity.kt` → `ui/base/` | `base→ui` 归零（原全仓仅此一条反向边，属层次倒置） |
| 4 个 VM 的 `App.getInstance()` → `AppContextHolder.context()` | 消掉 `ui→base` 的最后 4 处；顺带删 `LivePlayActivity` 一条死 import |
| 合计 | `ui → 下层` 同口径 **117 → 102**；`base` 与 `ui` **双向零依赖** |

逐文件重建比对：14 个改动文件中 7 个与"HEAD + 预期变换"逐字符一致，另 7 个的差异全部来自更早批次的既有改动。

## 10.2 第 2 步：闸门（已落地）

`.tools/ui_layer_gate.py` + `.tools/ui_layer_baseline.json`（首冻 **20 文件 / 35 处**）。

- 判定对象 = `ui/**` 内含 `@Composable` 的文件；违规 = import `osc.{api,base,data,sourcedata,net}.*`。
- 白名单：`*ViewModel`（Composable 持有 VM 是常规形态）、`AppBootstrap`（presentation 全局状态，包归属待定）。
- 自测：注入一个 `@Composable` + `import data.AppGraph` 的探针文件 ⇒ FAIL；删除 ⇒ PASS。
- 与 `null_assert_gate` 同族：`--update` 拒绝上调，`--detail` 列全部违规文件。

## 10.3 剩余收敛顺序（未做）

1. **Tmdb 三件套**（DetailEpisodes / DetailTmdbSections / VodPoster / DetailHero / SettingsPage，9 处）——
   唯一"Composable 里发 HTTP"的形态，改成 VM 暴露 `State<TmdbXxx>`。
2. `AppGraph` 直读（CollectPage / FollowingPage / PlayContainerSubtitles，8 处）。
3. 配置单例读（CollectPage / PageActions / ConfigManagePage / PlayerBottomBar / DanmuSheets / RecordsPage，8 处）——
   走一份 `ConfigSnapshot` StateFlow。
4. 进度/统计索引（FollowRow / HistoryRow / HistoryPage / FollowingPage / DetailContent，9 处）。
5. `net` 层（HomePage / SettingsPage / SearchScreen / SearchScreens / SearchSettingsSheet，6 处）。

判据：每次改动后 `ui_layer_gate` 的 `hits` 单调下降且 `--update` 只降不升；`pkg_cycles` 环簇保持 4。

## 10.4 明确不做

- 不为"消边"引入 DI 框架或空 domain 层：`AppGraph` 暴露的已是 Repository 接口，加壳只多一次跳转（分析见同日对话）。
- 不把 `AppBootstrap` 挪包：它自身 import ApiConfig，挪包不减任何边。
- `FollowDays` 不迁 `util`：纯函数但语义属 `VodFollow.updateDays` 的编解码器，塞进已被 29 个包依赖的 `util` 会加重门面。

## 10.5 第一批（Tmdb）进展：地基已落，VM 接管未做

**原估"5 文件 / 9 处"低估了**：`TmdbDetail`/`TmdbEpisode` 等 7 个 DTO 定义在 `object TmdbApi` 内部，
且图片 URL 是 `TmdbPoster` 读配置后拼出来的 —— 只要 Composable 还需要「类型名」或「图片 URL」，
就必须 import `api`，与是否走 VM 无关。所以这批实际是三步，不是一步。

**已做（2026-10-25，纯搬移、零行为变更）**：7 个 DTO + `SOURCE_KEY` 从 `api/TmdbApi` 拆到
`bean/TmdbModels.kt`（顶层类型 + `TMDB_SOURCE_KEY`），`TmdbApi.SOURCE_KEY` 改为引用 bean 常量。
引用面 48 处 / 7 文件全部改写（含 `src/test` 两个测试文件）。
踩坑：正则把 `TmdbApi.TmdbClient`（接口，非 DTO）一起改了，已回改。
结果：`ui_layer_gate` 命中 **35 → 33**（DetailTmdbSections 2→1、VodPoster 2→1），基线已下调并 PASS。
1428 用例 / 0 失败。

**未做（下一步）**：
1. 图片 URL 格式化（6 个纯函数 + 读 `TMDB_IMAGE_BASE`）从 `TmdbPoster` 迁到 `util.TmdbImageUrls`——
   注意 **util 不能反向调 api**（会新增 `util↔api` 环），格式化逻辑必须整体搬而不是委托。
2. `DetailViewModel` 接管 `tmdbDetail` / `episodeMeta` / `logo` 三个 loader（cache + resolve +
   `configEpoch` 失效），三个详情页 Composable 改为 collect（DetailEpisodes / DetailTmdbSections / DetailHero）。
3. VodPoster / SettingsPage 需跨页状态（海报 URL 由调用方 VM 提供），单独一批。

判据：完成后 `ui_layer_gate` 命中应从 33 降到 ~26，且 `pkg_cycles` 不得新增 `util↔api` 环。

### 10.5.1 本步自审（2026-10-25）

| 项 | 结论 |
| --- | --- |
| DTO 字段等价 | 7 个 data class 的字段名 / 类型 / 默认值 / 顺序与迁移前逐项一致（`TmdbSearchHit.originalLanguage: String? = null` 等默认值保留） |
| 序列化 | Gson 按字段名映射，类 FQN 变更不影响已缓存 JSON；缓存键是字符串常量（`cache_tmdb_detail2_` 等），不含类名 |
| 反射 / 混淆 | 无 `TmdbXxx::class` 反射、无 `javaClass.simpleName` 依赖；`proguard-rules.pro` 无 Tmdb 相关规则 |
| 残留引用 | 全库无 `TmdbApi.Tmdb*`；`TmdbClient`（接口）正确地仍留在 `TmdbApi` 内 |
| 未使用 import | 无新增（脚本报的 `getValue`/`setValue` 是 `by` 委托的必需导入，属误报） |
| 依赖方向 | `pkg_cycles` 包 55 / 边 247 / 互导对 4 / 环簇 4 与改动前完全一致，未新增 `util↔api` 或 `bean↔api` 环 |

**发现并修掉一处半迁移**：`TmdbApi.SOURCE_KEY` 在 `VodPoster` 改用 `bean.TMDB_SOURCE_KEY` 后已成
**零使用的别名**（同一语义两个入口）。已删除，只保留 `bean.TMDB_SOURCE_KEY` 单一入口。

**过程问题（非代码遗留）**：① 批量正则误伤 `TmdbClient` 接口；② 收尾误用 `git add -A` 暂存了 168 个
无关文件，已 `git reset` 撤销。两条均写入记忆，作为下次批量改写的禁令。

## 10.6 第一批 2a：URL 与轻状态迁出 `api`（已完成）

新建 `util/TmdbImages`，从 `api` 迁出三类"本来不该在 api 的东西"：

| 迁出内容 | 原位置 | 新位置 |
| --- | --- | --- |
| 6 个图片 URL 拼接（含尺寸常量 `w342/w780/original/w1280/w185/w300`） | `TmdbApi` 静态函数 + `TmdbPoster` 包装 | `TmdbImages`（1 参纯函数 + 读配置的重载） |
| `isActive()` / `configEpoch` / `notifyConfigChanged()` | `TmdbPoster` | `TmdbImages` |

`TmdbPoster.isActive()` 改为**私有委托** `TmdbImages.isActive()`，18 处内部调用点一行未动；
`notifyConfigChanged()` 保留在 api（负责清内存+KV 缓存），末尾调 `TmdbImages.notifyConfigChanged()` 只撞 epoch。
`TmdbApi` 的 `imageBaseUrl` 删除，`testImage` 改调 `TmdbImages.imageBaseUrl(...)`。

**关键验证 —— 没有造出新环**：`pkg_cycles` 显示 `util` 出边仍只有 `util.kv`（未出现 `util→api`），
包 55 / 边 247 / 互导对 4 / 环簇 4 与改动前一致。这正是"必须整体搬迁、不能委托 api"的原因。

`ui_layer_gate` 命中仍 **33**（预期：文件还要 `api.TmdbPoster` 取 cache/resolve，2b 才消数）。
`TmdbApiTest` 的 7 个 URL 用例改指向 `TmdbImages`，1428 用例全绿。

### 10.6.1 第一批 2b（VM 接管）进展与收口（2026-10-25）

**已完成**：`DetailViewModel` 新增三组状态 + `ensure`（`tmdbDetail` / `tmdbLogoPath` / `tmdbEpisodeMeta`，
key 含 `TmdbImages.configEpoch` 以支持配置失效），缓存优先、未命中走 `viewModelScope` 解析；
三个详情页 Composable 改为 `collect` VM state，`EpisodeSheet` 的 seasonHint 改走 `vm.seasonHintFor(...)`。
`DetailContent` 把 `vm` 传给 `DetailHero` / `TmdbCastSection` / `TmdbGenreSection`。

**结果**：`ui_layer_gate` 命中 **33 → 31**，基线同步；1428 用例 / 0 失败；环 4/4 未变。
`DetailEpisodes` 从 3 → 1（只剩 `data.EpisodeMatcher`，属"进度/索引"桶）。

**收口时发现漏估的两条链路（未做，同模式待做）**：
1. `DetailTmdbSections` 的 cast-person：`TmdbPoster.cachedPerson / resolvePerson`（1 处）。
2. `DetailHero` 的海报/背景图源：`cachedImages / cachedBackdrops / resolveImages / resolveBackdrops`，
   由 `heroImageSource(style, wide, active)` 选择（1 处 import）。需要 VM 同时暴露 images 与 backdrops
   两组，Composable 按样式选择。
这两条处理完，这两个文件的 `api.TmdbPoster` import 才能清零（`ui_layer_gate` 31 → 29）。
