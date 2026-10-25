# AGENTS.md

本文件是**架构硬约束速查 + 交付前检查清单**，给所有在本仓库工作的 agent 与新人。
完整规范、决策理由与历史在 `skill/SKILL.md` 与 `skill/avbox-*-spec.md`；**冲突时以 `skill/` 为准**，
本文件只负责两件事：哪些做法会破坏结构、改完怎么验。

## 架构不变量（不可协商）

本仓库**没有单一线性层序** —— `api`、`player`、`sourcedata` 之间存在多向的合法依赖
（`api → player.usecase`、`api → server`、`player → sourcedata`、`sourcedata → api`）。
所以约束只能写成下面的**禁止清单**，别试图画一条"层线"去套。

1. **`base` 只放 `App`（Application 生命周期），不得依赖 `ui`**。
2. **`player.core` 是内核底座**：不得依赖 `player` 的任何兄弟包，也不得依赖 `api` / `server` /
   `ui` / `data`；确需共用能力时只允许更底层的 `util` / `bean`。
3. **`server` 只做协议与端口**：不得 import `api` / `base` / `player` / `sourcedata` / `catvod`；
   需要宿主能力一律走端口（`ServerQueryPorts` / `PlaybackPorts` / `ControlManager.onProxyPortReady`）。
4. **`data` 在依赖图底部**：只依赖 `bean` / `io` / `util` / `event`，不得依赖
   `api` / `base` / `server` / `player` / `sourcedata`。
5. **Composable 只渲染**：不得 import `api` / `base` / `data` / `sourcedata` / `net`
   （`*ViewModel` 与 `AppBootstrap` 除外）—— 数据由 ViewModel 暴露为 state。
   门禁只判定 `@Composable` 文件；Activity 与 ViewModel 不在判定域（VM → Repository / ApiConfig
   是 MVVM 的正常方向），但「不在判定域」不等于「可以随便绕」——新增的 VM 直连网络封装属审查项。
6. **不得新增包级循环**：现有环只允许缩小，不允许扩大或新增。复核工具与口径见
   `skill/review/refactor-plan-20261025.md` §0（`pkg_cycles` 只看 import 行；含 FQN 全限定调用的环
   只能看 `scc_full`，两者结论不一致时以后者为准）。

## 单向数据流（UDF）

状态只在 ViewModel / 状态机里产生，UI 只读：

- **`MutableStateFlow` / `mutableStateOf` / `MutableSharedFlow` 必须 `private`**，对外只暴露
  `StateFlow` / `asSharedFlow()` 只读投影（`private set` 亦可）。`PlayerUiState` / `MusicPlayerState`
  这类状态容器是项目认可的形态，不受此限。
- **`ui/` 不得直写别人的状态**：`vm.editTarget.value = ...` 这类跨对象写值禁止；写本文件自有状态不算违规。
- **`ui/` 不得直接调度**：`scheduler.set*` / `mark*` 一律经 ViewModel 或控制器。
- **禁用 EventBus**（`EventBus.getDefault().post` / `@Subscribe`）。
- **禁用全局单槽**：`App.setVodInfo` / `App.getVodInfo` 这种全局可变量不得新增。
- 不加 `@Suppress("UNUSED_EXPRESSION")` 这类 revision hack；不用 `override var` 表达可变接口属性。

验法：`python .tools/udf_gate.py`（8 条规则，存量已全部归零，新增即 FAIL）。

## 交付前必跑

门禁脚本在本地 `.tools/` 目录（已 gitignore，不入库）：

```bash
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest   # 判读 BUILD SUCCESSFUL + 用例 0 失败
python .tools/ui_layer_gate.py      # Composable 穿透；基线只减不增（数字见 .tools/ui_layer_baseline.json）
python .tools/null_assert_gate.py   # 裸 !!；判定域已归零，新增即 FAIL
python .tools/udf_gate.py           # 单向数据流 8 条规则
python .tools/playstate_gate.py     # PlayState 单一真源
python .tools/i18n_gate.py          # ui 层硬闸门须 0 处
```

## 红线

- **小修改禁止跑测试**
- **禁止未经允许提交**（本地 commit 也不行，先问）
- **禁止未经允许操控用户的设备**
- **禁止未经允许跑 release 构建**
- **禁止硬编码本机绝对路径**（盘符、用户主目录、SDK 路径）—— 文档、代码、脚本一律用相对仓库根或环境变量。
- **改 `com.github.catvod.**` 前必须过 `javap -p -s` 符号守恒**：该包被动态 jar 按字节码访问，
  改签名会表现为“启动正常、点开某个源才崩”。细则见 `skill/avbox-kotlin-migration-spec.md` §3.2。
- **裁剪依赖前先读 `skill/avbox-mobile-ui-spec.md` §6.3**：动态加载的爬虫 jar 需要宿主提供。
  gson / okhttp / zxing，静态引用为 0 也不代表能删。
