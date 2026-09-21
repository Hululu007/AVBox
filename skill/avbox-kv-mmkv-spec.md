# AVBox KV 存储迁移 Spec:Hawk → MMKV

> 项目:AVBox(TVBox OSC fork;仓库根目录 = 本文件所在目录的上一级)
> 配套:先读 `SKILL.md`(通用规范 + 文档地图)与 `avbox-mobile-ui-spec.md`(技术基线,§6.7 是 KV 的现行约束)。
> 状态:**已实施完成(2026-09-13)**。P0 / P2 / P3 落地(**P1 数据迁移按 Q5 取消并删除**),并已完成 gson 2.10.1 → 2.14.0 升级(G3 闭环);编译 / 19 例单测通过;过程记录见 `history/features.md` 同日条目,§7-Q5 与 §8「实施修订记录」列出与本文原方案的偏差。
> 触发背景:2026-09-13 排查"配置管理页源列表添加后重进即消失",根因 = gson 2.13+ 与 Hawk 2.0.1 不兼容(详见 `history/features.md` 同日记录)。

## 0. 摘要

用 MMKV 替换 Hawk,承载全项目键值存储(设置项 / 历史 / 源列表 / 主题等)。
实际执行:①KV 门面(API 对齐 Hawk,业务侧机械替换) ②全量切换(33 文件) ③移除 Hawk 与 Conceal。
**不做数据迁移**(应用未发布、无存量用户,见 Q5),首装即原生 MMKV。
最大的风险落在「集合类型语义对齐」—— 由 `util/kv/KVKeySpec` 显式类型登记表 + 19 例纯 JVM 单测覆盖。

## 1. 现状盘点(2026-09-13 实测)

### 1.1 调用面

| 项 | 数据 |
|---|---|
| Hawk 调用点 | **34 个文件、约 250+ 处**(`Hawk.get/put/contains/delete`) |
| 高密度文件 | `ApiConfig` 48 / `LivePlayActivity` 35 / `SettingsPage` 28 / `HistoryHelper` 14 / `DanmuHelper` 12 |
| 键总量 | `HawkConfig` 定义 78 个 `public static final String`(可用 `Select-String "public static final String"` 复核;另有 `DEFAULT_LOAD_LIVE` 等少量直接字符串键;2026-09-17 新增 `nav_animation_disabled`) |
| 其他存储 | ~~2 处独立 SharedPreferences(`thunder` 雷电标识、`AudioTrackMemory`),与 Hawk 无关,不在本次范围~~ → **2026-09-15 已补迁入 KV**(`thunder_imei`/`thunder_mac`;音轨记忆 `audio_track_<progressKey>_*`),**全仓不再有 SharedPreferences**,见 `history/features.md` 同日"SP 残留清零"条目 |

### 1.2 键类型分布(决定编码规则的关键)

- **基本类型(约 60 个)**:String / int / boolean / long —— KV 直接走 MMKV 原生 API。
- **集合与复杂类型(10 个,迁移重点)**:

| 键 | 类型 | 备注 |
|---|---|---|
| `search_history` / `api_history` / `live_api_history` / `api_line_list` / `live_api_line_list` | `ArrayList<String>` | 历史与线路列表(2026-09-21 新增 `live_api_line_list`:直播侧多仓,同样是 `名字\t链接`,与点播的 `api_line_list` 分开存) |
| `subscribe_list` / `live_subscribe_list` | `ArrayList<String>` | 每项 `名字\t链接`(配置管理页) |
| `local_source_trees` | `ArrayList<String>` | 本地源目录授权(SAF tree uri 字符串),本地服务靠它直读原目录 |
| `live_group_list` | **`JsonArray`(Gson 节点树)** | 直播分组,注意它不是 List |
| `source_card_policy` | `HashMap<String, String>` | 源级卡片点击策略 |
| `sources_for_search` | **`HashMap<String, HashMap<String, String>>`** | 嵌套泛型,读侧需要显式 TypeToken |
| `doh_json` | String(JSON 文本) | 不是集合 |

**启动看门狗键(2026-09-21 新增,均登记在 `KVKeySpec`)**:`boot_loading_jar`(String,当前正在装载的
jar 地址)、`boot_loading_count`(Long,同源累计装载次数)、`boot_last_attempt_at`(Long,上次装载时刻,
用于"距上次太久就重新计数")、`boot_load_start_elapsed`(Long,本次进程开始装载 jar 的开机计时,
与崩溃标记同源比较)、`boot_vod_source` / `boot_live_source`(String,崩溃时正在使用的启动源)、
`boot_safe_disabled`(String,被自动停用的源地址,UI 读后即清)。⚠️ 崩溃时刻**不在 KV 里** ——
它必须同步落盘,走 `files/boot_crash.marker`(原因见 §4.1 的异步写说明)。

### 1.3 为什么迁(Hawk 2.0.1 的硬伤)

1. **上游停更**(2018 年后无版本)且与 gson 2.13+ 不兼容:其 `HawkConverter` 用匿名 `TypeToken` 捕获类型变量,Gson 2.13 起直接抛 `IllegalArgumentException` → **所有集合键读取静默失败**(2026-09-13 实测;当前靠锁定 gson 2.10.1 规避)。
2. **静默失败**:`put` 失败仅返回 false(调用方普遍不检查)、`get` 失败返回默认值 —— "读-改-写"场景会静默丢数据。
3. 依赖 Conceal(native `.so`)做加密,链路重,同样停更。

## 2. 目标与非目标

**目标**

- G1 行为等价:Hawk 能存/读的所有类型,KV 一致支持(含集合与 JsonArray)。
- G2 数据零丢失:一次性迁移全部既有键;迁移幂等、可重试。
- G3 解除 gson 版本枷锁(迁移完成后可自由升级 gson)。
- G4 不再静默失败:写入失败可感知(返回 boolean + 错误日志)。

**非目标**

- N1 不改业务调用语义(不借机重构业务逻辑)。
- N2 不引入多进程/多实例等新能力(保持单进程单实例)。
- N3 不动 Room 与文件缓存等其他存储。

## 3. 方案总览(分阶段)

| 阶段 | 内容 | 出口条件 | 实际结果 |
|---|---|---|---|
| P0 门面 | 新增 `util/KV`(内部 MMKV + Gson 编码)+ 依赖;`Hawk` 原样保留 | 编译通过;KV 各类型读写自测通过 | ✅ 含纯 JVM 编解码核心 + 19 例单测 |
| ~~P1 迁移~~ | ~~`KV.init` 内一次性搬 Hawk → MMKV~~ | —— | ❌ **未采用**:应用未发布、无存量用户,P1/P2 期间曾实现并真机跑通,随后**连同 P3 一起移除** |
| P2 切换 | 33 文件 `Hawk.` → `KV.` 批量替换 | 全量回归 | ✅ 业务侧 `Hawk.` 残留 0 处 |
| P3 清理 | 删除 Hawk/Conceal 依赖、迁移代码与旧数据 | 立即执行(用户决策,不设观察期) | ✅ 依赖树已无 hawk/conceal |

> **P1 为何取消**:迁移代码的唯一价值是"让存量用户不丢数据"。用户 2026-09-13 明确"应用尚未发布、没有存量用户",
> 于是"Hawk → MMKV 数据搬运 + 纠偏 + 完成标记"整套失去存在理由,直接删除比长期维护它更正确 ——
> 首装即原生 MMKV,不存在"旧库"这个前提。

## 4. 详细设计

### 4.1 依赖与初始化

- `gradle/libs.versions.toml` 新增 `mmkv = "2.4.2"`。
- `App.onCreate` → `initParams()` 中 `KV.init(this)`(必须在任何 KV 读写之前)。
- MMKV 实例:单实例 `MMKV.mmkvWithID("avbox_kv", MMKV.SINGLE_PROCESS_MODE)`(**不加密**,见 §4.4)。
- ⚠️ **写入是异步的,别拿它做崩溃/断电级持久化(2026-09-21 实测教训)**:MMKV 把 `encode` 排进
  Scheduler、约 1 秒后落盘,而 **2.4.2 没有同步写 flag**(模式位只有 `SINGLE_PROCESS_MODE` /
  `MULTI_PROCESS_MODE` / `READ_ONLY_MODE` 等),`sync()` 也只是等"当前 pending 批"。
  实测进程级 `UncaughtExceptionHandler` 里写的崩溃时刻**根本没落盘**(设备上一直停在几分钟前),
  导致启动看门狗的判据从未成立。**结论:写完进程就可能死的场景必须用同步文件 IO**
  (先例 = `BootGuard` 的 `files/boot_crash.marker`),不要指望 KV。
- **无 Hawk、无迁移代码**:`Hawk.init/put/get` 与 `com.orhanobut:hawk`(及传递依赖 conceal)已全部移除,`proguard` 的 hawk keep 规则同步删除。

### 4.2 KV 门面 API

```java
public final class KV {
    public static void init(Context context);
    public static <T> boolean put(String key, T value);      // 失败返回 false 并 LOG.e
    public static <T> T get(String key);                     // 不存在/失败 → null
    public static <T> T get(String key, T defaultValue);     // 不存在/失败 → defaultValue
    public static boolean contains(String key);
    public static void delete(String key);
}
```

- 调用点替换规则:`Hawk.get(key, def)` → `KV.get(key, def)`;`put` / `contains` / `delete` 同理。
- ⚠️ **`get(key, def)` ≠ 判存在性**:它分不清"键不存在"与"存的就是这个值"(旧 Hawk 同款语义)。要判存在性用 `contains(key)` —— 这条差异曾在迁移代码里造成过一次必崩(见 §9)。

### 4.3 类型编码规则

| 值类型 | 编码方式 |
|---|---|
| String | MMKV 原生 `encode(String)` |
| int / long / boolean / float / double / byte[] | 走 String 通道(经 `KVCodec`),或 MMKV 原生 `encode` / `decodeXxx` |
| `List<T>` / `Set<T>` / `Map<K,V>` / `JsonArray` / 任意对象 | Gson 序列化为文本 + `\u0001json:` 前缀落盘 |

- 读取用**显式 Type**恢复元素类型 —— **这是对 Hawk 缺陷的直接修正:严禁用匿名 `TypeToken<T>` 捕获类型变量**。
- 集合与嵌套泛型(如 `checked_sources_for_search`)由 **`util/kv/KVKeySpec` 的"键 → Type"登记表**统一处理
  (用户决策 §7-Q3):复杂键集中登记显式 Type,调用点保持 `KV.get(key, def)` 形态、可机械替换;
  **该表是长期设施不是迁移脚手架** —— 泛型擦除后 `new ArrayList()` / `new HashMap<>()` / `null` 默认值
  都带不来元素类型,不登记就会解出元素为 `LinkedTreeMap` 的集合。
- **JsonArray 必须单独支持**(`live_group_list` 存的就是 Gson 节点树,不能按 List 处理)。
- 数值收敛:JSON 数字被 Gson 解析为 `Double`,按 `int`/`long` 读取时由 `KVDecoder.coerceNumber()` 显式收敛,
  越界值回落调用方默认值(不静默截断)。

### 4.4 加密

- **不加密(用户决策,2026-09-13)**:采用 MMKV 默认行为,明文 mmap 存于应用私有目录。相对 Hawk/Conceal 属安全级别下调(root / adb 备份场景可读明文),用户已确认接受。
- 回补路径:MMKV 支持 `reKey(newKey)` 原地加密,未来若需加密可在任一发版补上,不丢数据。

### 4.5 数据迁移

**不存在数据迁移(用户决策,2026-09-13:应用未发布、无存量用户)。**

首装即原生 MMKV。曾按原方案实现过"Hawk → MMKV 一次性搬运 + 完成标记 + 纠偏"(真机跑通),随后整体删除:
它唯一的价值是保护存量用户数据,而存量用户不存在;留着只会让 Hawk/Conceal 继续留在依赖树里。

### 4.6 错误处理与可观测性(吸取 Hawk 教训)

- `put` 返回 boolean ⇒ 由门面自身打 `echo-kv` 日志,失败不再静默(G4);需要补偿动作的调用方(极少数)自行检查返回值。
- 集合读取:区分「键不存在」(返回默认值,正常)与「解不出类型」(返回默认值 + `LOG.e` + 键名)。
- 关键事件打点前缀 `echo-kv*`,经 `util/LOG` 落盘(`FILE_LOG_PREFIXES` 已含 `echo-kv`),真机取日志:
  `adb shell run-as <applicationId> cat files/preload_debug.log`(该 ROM 吞 logcat,此为既有约定)。
- ⚠️ **单测里不能用 `android.text.TextUtils.isEmpty` 判空(2026-09-21,第三次踩)**:
  工程开了 `testOptions.unitTests.returnDefaultValues = true`,Android 桩方法**静默返回默认值** ——
  `TextUtils.isEmpty("")` 返 `false`、`TextUtils.isEmpty(null)` 也返 `false`。于是同一处判空
  "真机生效、单测失效",而**单测正是用来钉这类边界的**。踩过三次的落点:`ConfigParser`(最初)、
  `bean/Depot`(空地址过滤在单测里不生效)、`util/BootGuard`(`shouldDisable` 的"空 jar 不停用"守卫
  被新写的单测当场抓到)。**约定:纯逻辑类里判空一律写本地 `text == null || text.length() == 0`
  的小工具方法**,并在注释里指明原因(三处现有实现互相引用,便于后来者一次看懂)。

## 5. 实施步骤(实际执行)

1. P0:依赖 + `KV` 门面(纯 JVM 编解码核心 `util/kvcodec/KVDecoder` + `util/kv/KVKeySpec` 登记表)+ 集合与嵌套泛型单测(19 例)。
2. P2:全局替换 33 文件 `Hawk.` → `KV.` + import 替换 → 编译纠错 → 构建验证。
3. P3:删除 Hawk/Conceal 依赖、`KVMigrate` 与旧库,解除 gson 版本锁注释,proguard keep 规则同步清理。
4. 装机验证:冷启动 + 搜索页(原崩溃点)+ 迁移/解码日志核对。

## 6. 风险、回滚与验收

### 6.1 风险

| 风险 | 等级 | 缓解 |
|---|---|---|
| 集合语义对齐出错(JsonArray / 嵌套 Map) | 高 | 编码规则单测(19 例,含 JsonArray 与嵌套 Map 往返);`KVKeySpec` 集中登记元素类型 |
| MMKV native `.so` 兼容(本机为 16KB 页设备) | 中 | 装机即验证初始化与读写(已通过) |
| 回归面大(33 文件 250+ 处) | 中 | 批量替换 + 编译器兜底 + §6.2 清单逐项过 |
| 无数据迁移 ⇒ 已装旧版(含 Hawk 中间版)的机器配置不保留 | 低 | 应用未发布、无存量用户(用户决策);开发者本机如需干净状态,卸载重装/清除应用数据即可 |

### 6.2 验收清单(逐项真机确认)

- 设置页全部选项(播放/偏好/预载/主题)改动后重启保持。
- 配置管理页:添加/编辑/删除源、切换源、重启保持。
- 搜索历史:新增/删除/清空、重启保持。
- 线路历史与自动换线、直播分组与直播源切换。
- 播放位置续播、弹幕配置、DoH 配置、无痕模式。
- 卸载重装(空数据)走默认值路径正常。

### 6.3 回滚

- **无回滚退路**(用户决策):Hawk 与其旧库已从代码与依赖中彻底移除,MMKV 侧数据出问题只能清库重建(设置页重置 / 清除应用数据)。
- 因此 §6.2 全量回归必须在发版前完成。

## 7. 决策记录(2026-09-13 用户确认,取代原"未决问题")

| # | 问题 | 决策 | 对方案的影响 |
|---|---|---|---|
| Q1 | 是否启用 MMKV 加密(cryptKey)? | **不加密** | §4.1 初始化不带 cryptKey;§4.4 采用默认明文(接受相对 Hawk 的安全级别下调);未来可用 `reKey` 回补 |
| Q2 | 旧 Hawk 依赖保留几个版本周期? | **不保留** | §3 阶段表 P3 = 立即移除;§6.3 起无回滚退路 |
| Q3 | 嵌套泛型处理机制? | **KV 内建"键 → Type"登记表** | §4.3 按登记表实现(复杂键集中登记);全部调用点保持 `KV.get(key, def)` 可机械替换 |
| Q4 | 是否借迁移统一键命名 / 清理废弃键? | **不做** | 保持最小变更,不做键名重构与废弃键清理 |
| Q5(追加) | 应用是否已发布 / 有无存量用户? | **未发布,无存量用户**(2026-09-13 用户确认) | **取消数据迁移**(§4.5):P1 整套(Hawk 搬运 + 纠偏 + 完成标记)删除,首装即原生 MMKV;Hawk/Conceal 依赖与 `proguard` keep 规则同步移除 |

## 8. 实施修订记录(2026-09-13 实施时追加)

| # | 原方案 | 实际落地 | 原因 |
|---|---|---|---|
| R1 | §3 P1:一次性搬 Hawk → MMKV(幂等,带完成标记) | **实现后整体删除**(含 `KVMigrate`、完成标记、纠偏逻辑、hawk 依赖) | Q5:应用未发布、无存量用户 ⇒ 迁移唯一的价值(保护存量用户数据)不存在。留着只会让 Hawk/Conceal 永久留在依赖树里,并持续产生维护与验证成本 |
| R2 | §3 P3:删除 Hawk/Conceal 依赖、迁移代码与旧数据 | **全部删除**:依赖树已无 hawk/conceal,`proguard-rules.pro` 的 `-keep class com.orhanobut.hawk.**` 一并移除 | 同上;且无迁移后 `KVMigrate` 是唯一引用方,删之即彻底解耦 |
| R3 | §4.2 门面 API `get(key)` 返回 null | 增加 `@Nullable`/`@NonNull` 语义区分:`get(key)` 标 `@Nullable`,`get(key, def)` 不标(契约恒不为 null) | 带默认值的重载若标 `@Nullable`,Kotlin 侧 33 个文件的调用全部报"可空类型不可当非空用",违背"业务侧近乎零改动" |
| R4 | §4.3 "键 → Type 注册表"(一次性) | **转为长期设施**:`KVKeySpec` 是实现 `KVDecoder.TypeRegistry` 的常驻登记表,**不随迁移删除** | 泛型擦除下 `new ArrayList()` / `new HashMap<>()` / `null` 默认值都带不来元素类型;没有登记表,`subscribe_list` 等键会解出 `LinkedTreeMap` 元素 → 取值 ClassCastException。它是 KV 正常运行的必需件,与 Hawk 无关 |
| R5 | §4.6 "批量替换时同步补 `put` 返回值检查" | **未逐点补**:失败留痕收敛到门面内(`KV.put` 自行打 `echo-kv` + 返回 false) | 250+ 处调用点逐个加 `if (!KV.put(...))` 会把"存储替换"变成"业务改写"(违反 N1),且绝大多数调用点也没有可执行的补偿动作。G4"写入失败可感知"由门面日志 + 布尔返回共同满足 |
| R6 | ——(原方案未提及) | 新增 `util/kvcodec` 纯 JVM 包(`KVDecoder`/`KVLog`)与 `app/src/test` 单测 19 例 | §5-P0 要求"集合与嵌套泛型单测"。把编解码核心与 Android(MMKV/LOG)解耦后,单测可纯 JVM 跑,不需要 Robolectric |
| R7 | §4.6 `echo-kv*` 打点 | 仅保留**事件级**日志(类型登记表装载/类型解不出/解码失败);正常读写不打点 | 热路径(如播放器每帧读设置)打点会淹没有效信息;日志前缀已加入 `util/LOG.FILE_LOG_PREFIXES` |
| R8 | ——(原方案未提及) | 迁移(已删除)期间暴露的两个真实缺陷被固化修复:`KVMigrate` 必须先判键存在性;`KVCodec` 用 `containsKey` 而非 `getValueSize` 判存在性 | 见 §9。前者随迁移一起删除,后者是**现行代码**的修复,必须保留 |
| R9 | §7-Q2 隐含"gson 保持 ≤2.12 直到迁移完成" | **gson 2.10.1 → 2.14.0**(用户要求,2026-09-13) | 原约束只服务于 Hawk 的集合读取;Hawk 已彻底移除,约束消失。KV 侧只用稳定 API(`TypeToken.get` / 显式 Type),不碰 gson 内部实现 ⇒ 升级零改动,编译 + 19 例单测通过。**这也是本次迁移的验收点之一(G3 解除 gson 版本枷锁)** |
| R10 | ——(迁移完成后审查发现) | **修复登记类型与写入类型不一致**:`LIVE_WEB_HEADER` 由 `register(key, "")`(String)改为 `new TypeToken<HashMap<String,String>>(){}`;并新增单测 `KVKeySpecTest.liveWebHeader_roundTripDecodesAsStringMap` 锁死"写入类型 == 登记类型" | `ApiConfig.loadLives` 写入的是 `HashMap<String,String>`(header/ua),登记成 String 会让读取侧 Gson 用 String 解析对象原文抛错,又被 `KV.get(key)`(quiet 副本)静默吞成 null ⇒ **直播源配置的 UA/Referer/header 全部失效**(2026-09-13 全量缺陷审查发现)。教训:新增/修改复杂键必须按"写入值的实际类型"登记 |

## 9. 2026-09-13 崩溃复盘(教训保留,相关代码已删除)

> 迁移代码(`KVMigrate`)已按 Q5 删除,本节保留是因为其中两条教训对**现行 KV 代码**仍然生效。

**现象**:装机后一进搜索页必崩 —— `java.lang.IllegalArgumentException: Semaphore should have at least 1 permit, but had 0`
(`SearchViewModel.<init>` → `Semaphore(semaphorePermits)`)。

**根因(两层)**:

1. **主因 —— 迁移把"旧库里根本没有的键"按代表值写进了 KV**。`readHawk(key, type)` 用
   `Hawk.get(key, 代表值)` 读取,而 **`Hawk.get(key, defaultValue)` 在键不存在时返回的就是这个默认值**(不是 null);
   代表值只该用来告诉 Hawk "元素是什么类型"(int→0、boolean→false、集合→空集合)。
   于是登记表里有、旧库却没有的键全被写进去且写错值:实测旧库 26 键、KV 变成 76 键。
   `search_threads` 业务默认是 **32**(16/32/48/64 四档),被写成 **0** → `Semaphore(0)` 崩。
   **辨识特征:搬运日志的"完成键数"远大于"旧库键数"** —— 这个数字对比本该在首次装机就被发现。
2. **次因 —— 完成标记不版本化**:修复后老安装带旧标记会直接短路,纠偏永不执行。**任何"一次性/迁移"逻辑的标记都必须可升版本。**

**第三个 bug(与崩溃无关,但是现行代码的真实缺陷,已修且必须保留)**:`KVCodec.decode` 原本用
`getValueSize(key) < 0` 判"键不存在",但 **MMKV 的 `getValueSize` 返回 `size_t`,不存在的键返回 0 而非 -1**
(见 `Core/MMKV.cpp`),该判断恒为 false ⇒ 每个不存在的键都带 `raw=null` 进解码器刷一条 `type-mismatch` 错误,
一轮启动 2.9 万行。**改用 `containsKey` + `raw == null` 兜底**。

**同批加固(现行代码)**:`KVDecoder.coerceNumber()` —— Gson 解析裸数字 token 一律给 `Double`,
`int` 读取会因 `int.class.isInstance(Double)` 为 false 而静默回落默认值(值看着对、类型被换掉);
现按目标数值类型收敛,越界值降级为回落默认值(不静默截断)。

**回归护栏**:单测 19 例,含 `jsonNumberParsedAsDouble_isCoercedToIntTarget`、
`intTarget_outOfRangeFallsBackToDefaultInsteadOfTruncating`、`searchThreadsCrashRegression_zeroUnwrapsToIntNotDefault`。

**可复用教训**:
① **"取默认值"与"判断存在性"是两件事** —— 任何 KV/配置读取层都要把二者分开暴露(`KV.contains` vs `KV.get`),用一个带默认值的 getter 去判断存在性,一定出错;
② 一次性逻辑的完成标记必须可升版本,否则修复无法到达已执行过的机器;
③ **批量搬运/写入后核对数量守恒**(旧库键数 ≈ 新库键数),数量对不上就是数据写坏的第一个信号;
④ 不要用 size 类 API 的返回值猜存在性(注意 `size_t` 的"0 表示不存在"语义);
⑤ **没发布的代码不要背迁移包袱** —— 本次多写了一个完整的迁移 + 纠偏 + 标记体系,最后因"无存量用户"全部删除;先确认"有没有存量数据"再决定要不要迁移,能省掉整条链路。
