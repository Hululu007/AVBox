# AVBox 网络调用层迁移 Spec:移除 OkGo → OkHttp + 协程

> 项目:AVBox(TVBox OSC fork;仓库根目录 = 本文件所在目录的上一级)
> 配套:先读 `SKILL.md`(通用规范 + 文档地图);涉及订阅源字段对照 `avbox-mobile-ui-spec.md` §6.12;涉及契约层(`com.github.catvod.**`)对照 `avbox-kotlin-migration-spec.md` §3.2(签名守恒)。
> 状态:**已完成(2026-10-06 起草;同日三次修订 + N0–N5 实施回填,均见 §8)**:N0(await 层 + 协程依赖显式化 + 单测)、N1(`sourcedata` 簇 detail/search 链闭环)、N2(`player` 簇 + `PlayLoader` play/json_jx/m3u8 链闭环)、N3(UI / 杂项簇 suggest 链闭环 + 同步路径 2 处 suspend 化)、N4(`catvod` 契约层内部换血、签名守恒)、N5(`HttpClients` 去 okgo 化 + 删依赖 + §6.2 全量验收)**全部完成并过收尾审查**;剩真机走查(§6.2 第 6 项)。
> 触发背景:2026-10-06 依赖梳理(okhttp / okio / okgo 三件套清点 + 19 文件调用面普查)。结论:OkGo 在本项目只扮演"回调式外观",底层 OkHttpClient 由 `HttpClients` 自建并注入 —— 调用面封闭、可渐进替换。同日决策:借迁移同步完成传输层协程化 + 取消机制全 Job 化(§3)。

## 0. 摘要

OkGo(`com.lzy.net:okgo:3.0.4`,2018 年停更、按 okhttp3 3.x 编译)本身不提供网络能力,只提供调用形状。依赖形态:**项目在 `gradle/libs.versions.toml` 显式声明 okhttp 5.5.0(`app/build.gradle.kts:122`),okgo 传递的 okhttp 3.x 被版本仲裁压制**,okgo 实际运行在 5.5.0 之上:

```kotlin
// app/src/main/java/com/github/avbox/osc/net/HttpClients.kt
val okHttpClient = builder.build()
okHttpClient.dispatcher.maxRequestsPerHost = 10
OkGo.getInstance().setOkHttpClient(okHttpClient)
```

迁移 = **传输层协程化**:19 个调用文件从「okgo 回调」改为「自建 await 层 + suspend」;取消机制从「tag 全局广播」改为「Job 精确取消」(全 Job 化);网络行为(超时 / DNS / 代理 / SSL / UA / 取消 / 重试)逐条保持等价或显式登记差异,语义依据见 §1.3。

做法:新增一个薄 await 层(`suspendCancellableCoroutine` 包装现有 client,约 20 行/文件),按 6 个片渐进替换 19 个文件,最后删除依赖坐标。

⚠️ **"无官方协程 API"的准确表述**:核心 `okhttp` aar 内实测确无 suspend 成员(`Call` 无 suspend 方法、无 `CallsKt` 扩展);但 OkHttp **另有官方伴生模块** `com.squareup.okhttp3:okhttp-coroutines`(5.5.0 已发布,与本项目 okhttp 版本一一对应,提供 `Call.executeAsync()` suspend 扩展 + 取消联动)。它只做"阻塞→挂起"的桥接,不含本 spec 必需的 404/≥500 判定与超时重试策略,所以 await 层仍要自建——但它是"策略层"而非"桥接层"。已拍板**不叠加**该模块、自持桥接(**P11**)。

终态(验收口径):

1. `grep -r "com.lzy" app/src` = 0;
2. `.\gradlew :app:dependencies` 无 `com.lzy.net:okgo`;
3. 全应用自建网络请求 100% 走 okhttp(await 层 / `catvod.net.OkHttp` / 原生 client),传输层 100% suspend 化,取消机制 100% Job 化(`cancelTag` 全库归零)。

边界(明确排除,见 §4.7):cling(DLNA 自有 HTTP 栈)、NanoHTTPD(本地服务端)、librtmp(本地 rtmp AAR)三个第三方栈不在调用面内;JS 契约层(`com.github.catvod.**`)签名不进协程世界(§4.5)。

## 1. 现状盘点(2026-10-06 实测)

### 1.1 调用面(19 文件)

统计口径:`OkGo.get<` **13 处** / `AbsCallback<String>(){` 匿名实例 **18 处** / `SourceHelper.siteGet(` 调用 **10 处** / `cancelTag(` **12 处** / 同步 `execute()` **4 处** / `OkGo.getInstance().setOkHttpClient` **2 处** / okgo 链式 `.tag()` 打点 **14 处**。

| # | 文件 | OkGo 用法 | 片 |
|---|---|---|---|
| 1 | `net/HttpClients.kt` | 初始化:`setOkHttpClient`×2(init/reloadDns)、`HttpHeaders.setUserAgent`×2(init/reloadDns)、`HttpsUtils.UnSafeHostnameVerifier`×1、okgo 版 `HttpLoggingInterceptor`**×4**(:87/:255 挂在 `initExoOkHttpClient()` 的 ExoPlayer client 上、:376/:417 挂在 default/reloadDns 上;均 `Level.NONE`/`ColorLevel.OFF`) | N5 |
| 2 | `sourcedata/SourceHelper.kt` | `siteGet()` 枢纽:`OkGo.get<String>(api)` + 合并站点 header,返回 `GetRequest<String>` | N1 |
| 3 | `sourcedata/ListLoader.kt` | `siteGet`×3 + `AbsCallback`×3;`.tag(api)`×2(无消费者)+ `.tag("detail")`×1;`onError` 读 `code()`/`exception` | N1 |
| 4 | `sourcedata/DetailLoader.kt` | `siteGet`×1 + `AbsCallback`×1;`.tag("detail")`×1 | N1 |
| 5 | `sourcedata/SearchLoader.kt` | `siteGet`×2 + `AbsCallback`×2;`.tag(requestTag)`×2(值硬编码 `"search"`,`SearchLoader.kt:27`) | N1 |
| 6 | `sourcedata/SortLoader.kt` | `siteGet`×2 + `AbsCallback`×2;`.tag(key+"_sort")`×2(无消费者) | N1 |
| 7 | `sourcedata/PushDetailResolver.kt` | `siteGet`×1 + `AbsCallback`×1;`.tag("detail")`×1 | N1 |
| 8 | `sourcedata/SubtitleViewModel.kt` | `OkGo.get<String>`×2 + `AbsCallback`×2 | N1 |
| 9 | `sourcedata/PlayLoader.kt` | `siteGet`×1 + `AbsCallback`×1;`.tag(requestTag)`×1;既有实例级取消 `cancelPlayRequest()`(:234,seq 失效法) | N2 |
| 10 | `player/PlayUrlResolver.kt` | `OkGo.get<String>`×1(`.tag("json_jx")`);`cancelTag`×4(`play`/`json_jx`/`m3u8-1`/`m3u8-2`,集中在 `stopParse()`:196-199) | N2 |
| 11 | `player/usecase/M3u8PurifyUseCase.kt` | `OkGo.get<String>`×2(`.tag("m3u8-1"/"m3u8-2")` + `.headers(HttpHeaders 实例)`)+ `AbsCallback`×2;`cancelTag`×2(新一轮前取消旧轮) | N2 |
| 12 | `ui/SearchViewModel.kt` | `OkGo.get<String>`×2(fetchHotSearch 无 tag / fetchSuggest `.tag("suggest")`)+ `AbsCallback`×2;`cancelTag`×2(onCleared 取消 suggest / 新搜索前取消 search) | N3 |
| 13 | `ui/DetailViewModel.kt` | 仅 `cancelTag`×3(:612 超时 fallback 流程 / :675-676 `destroyEngine()`,值 `detail`×2 + `search`×1) | N1 |
| 14 | `ui/LiveProxyLoader.kt` | `OkGo.get<String>`×1 + `AbsCallback`×1 | N3 |
| 15 | `subtitle/SubtitleLoader.kt` | 同步 `execute()`×1(`.headers(Referer/UA)` → `okhttp3.Response`) | N3 |
| 16 | `ui/music/MusicLrc.kt` | 同步 `execute()`×1(`.headers(UA)` → `okhttp3.Response`;调用方已是 `suspend + withContext(IO)` 形态) | N3 |
| 17 | `catvod/crawler/JsLoader.kt` | 同步 `OkGo.get<File>(jar).execute()` → `okhttp3.Response` | N4 |
| 18 | `catvod/crawler/JarLoader.kt` | 同步 `OkGo.get<File>(url).execute()` → `okhttp3.Response` | N4 |
| 19 | `catvod/crawler/js/Connect.kt` | 仅 `cancelTag`×1(:145,与自实现的 dispatcher 遍历取消 :146 并用;请求本身已是 okhttp 直连 `Request.Builder().tag(...)`) | N4 |

间接受影响面(不 import okgo,但取消接线要跟着改,详见 §4.4):`sourcedata/SourceViewModel.kt`、`ui/page/HomeViewModel.kt`、`ui/page/PartitionListViewModel.kt`、`player/PlaybackFetch.kt`。

### 1.2 OkGo 能力实际使用清单(决定 await 层 API 面)

| 能力 | 使用 | 说明 |
|---|---|---|
| 链式 GET + `.headers()/.params()/.tag()` | 是(全部请求) | **`headers` 为替换语义**(`HttpHeaders.headersMap` 是 `LinkedHashMap<String,String>`,`put` 即覆盖,见 E7);GET 的 `params` 同 key 替换、异 key 累积 |
| `HttpHeaders` 全局 UA | 是 2 处 | `setUserAgent("okhttp/" + OkHttp.VERSION)` = `"okhttp/5.5.0"`(init / reloadDns 各一) |
| `HttpHeaders` 实例构造 | 是 1 处 | `M3u8PurifyUseCase.kt:43` 构造实例传 `.headers(okGoHeaders)`,对应 §4.1 的 `headers(map)` 重载 |
| 异步回调(`Callback`/`AbsCallback`) | 是 18 处 | 全部主线程回调(见 E5) |
| 同步 `execute()` | 是 4 处 | **返回原始 `okhttp3.Response`**,不做转换 |
| `cancelTag` | 是 12 处 | 遍历 client dispatcher 按 `Request.tag()` 匹配 |
| `setOkHttpClient` 注入 | 是 2 处 | 唯一初始化入口 |
| `HttpsUtils.UnSafeHostnameVerifier` | 是 1 处 | 信任所有主机名 |
| okgo 版 `HttpLoggingInterceptor` | 是 **4** 处 | 全部关闭(NONE/OFF),零功能;**其中 2 处挂在 `initExoOkHttpClient()` 的 ExoPlayer client 上**,删依赖前必须一并处置 |
| 缓存(`CacheMode`/`cacheKey`)、Cookie、上传 / 下载 / 进度、自定义 Converter、`onStart`/`onFinish`/`onCacheSuccess`、`isSuccessful`/`isFromCache`/`rawResponse` | **否** | 全部未使用,await 层不需要 |

### 1.3 关键源码取证(`okgo-3.0.4-sources.jar`)

以下 9 条为迁移的等价性依据(来源行号为 okgo 源码 jar 内行号)。**E5/E9 在协程路线下语义变化,属显式登记的行为差异;其余 7 条逐条保持**(E7/E8 的语义已按 3.0.4 源码修正,其中 E8 有一处值编码差异登记):

| # | 语义 | 取证 | 协程路线处置 |
|---|---|---|---|
| E1 | 同步 `execute()` 返回**原始 `okhttp3.Response`**:不做转换、不做状态码判定、不重试判定之外的加工 | `Request.java:382`、`:42` | 保留阻塞路径 `getSync()`(仅 N4 契约层使用);N3 的 2 处同步调用随调用方 suspend 化 |
| E2 | 异步成功/失败判定:**`code == 404 或 code >= 500` → `onError`**;其余(2xx / 3xx / 其他 4xx)一律走 `convertResponse` → `onSuccess` | `BaseCachePolicy.java:150-171` | await 层抛 `HttpException(code)`,其余返回 body 字符串 |
| E3 | `IOException` 且**非取消** → `onError`;**已取消 → 静默(无任何回调)** | `BaseCachePolicy.java:131-147` | `CancellationException` 天然静默;但需防业务 `catch (Exception)` 吞掉(R12) |
| E4 | `convertResponse` 抛异常 → `onError` | `BaseCachePolicy.java:168-171` | `body.string()` 异常自然传播为普通异常 |
| E5 | okgo 全部回调经**主线程** `Handler(Looper.getMainLooper())` 派发 | `OkGo.java:60,69`、`NoCachePolicy.java:39-58,73-87` | **语义反转**:协程恢复线程跟随 caller(挂 `viewModelScope` 即 Main)。衍生现状:onSuccess 内的 `resultParser.xml/json` 解析**当前跑在主线程**(如 `ListLoader.kt:92-99`),迁移后统一挪 `withContext(Dispatchers.IO)` —— 属净收益的行为变化,消费方主线程假设逐点审查(§4.2) |
| E6 | `SocketTimeoutException` 按 `retryCount` 重试(重新发请求);**全局默认 3**(项目从未调用 `setRetryCount`)⇒ 判定为 `currentRetryCount(自 0 起) < 3`,即 **1 次首发 + 最多 3 次重试 = 最多 4 次尝试** | `OkGo.java:70`、`Request.java:88`、`BaseCachePolicy.java:132-140` | await 层内同判定重试循环:**重试上限 3(总尝试 4)**,口径见 P5 |
| E7 | 请求头 = 全局 UA(`Request` 构造时注入)+ 本次 `.headers(...)`;**`headers(k,v)` 是替换语义** —— `HttpHeaders.headersMap` 为 `LinkedHashMap<String,String>`、`put` 即覆盖,故显式设 UA 的 3 处调用点**只发 1 个 UA 头**(显式值覆盖全局值,不是两个) | `Request.java:82-83,167`、`HttpHeaders.java:83,100-104`、`HttpUtils.appendHeaders`(遍历单值 Map) | await 层须复刻**单值替换**(`Request.Builder.header(...)`,或先清后设),**禁用 `addHeader`** —— 那会造出 okgo 从未有过的双 UA 头;全局 UA 按 **P6** 等价保留 |
| E8 | GET 的 `params` 拼到 **URL query**;`urlParamsMap` 为 `LinkedHashMap<String,List<String>>`、默认 `IS_REPLACE=true`(同 key 覆盖、异 key 累积);值经 `URLEncoder.encode(v,"UTF-8")` 编码 | `NoBodyRequest.java:29`、`HttpParams.java:49,142-154`、`HttpUtils.createUrlFromParams` | `HttpUrl.Builder.addQueryParameter(...)` 对不同 key 等价;⚠️ **值编码有差异**:okgo 空格→`+`、`~`→`%7E`,okhttp 空格→`%20` —— 中文搜索词上多数服务端等价,登记为差异项(或复刻编码) |
| E9 | 请求 `tag` 存在 `okhttp3.Request.tag()`;`cancelTag` 遍历同一 client 的 dispatcher 匹配 | `Request.java:94,292`、`GetRequest.java:47`(`.tag(tag)` 落到 okhttp builder)、`OkGo.java:231`(实例)/`:246`(静态重载) | **全 Job 化**:tag 通道退役,12 处 `cancelTag` 逐处迁移为 Job/结构化取消(§4.4);注意 tag 广播是**全局**取消、Job 是**实例内**取消,存在连带取消消失的语义差异(R14) |
| E10 | 请求头除全局 UA 外还注入 **`Accept-Language`**(`Request` 构造时,早于 UA 注入):值 = `language-COUNTRY,language;q=0.8`(country 为空时仅 `language`),首次计算后静态缓存 | `Request.java:79-80`、`HttpHeaders.java:175-193` | await 层按**同形复刻**(N0 实测新增条目,§5 N0);`HttpHeaders.put` 只跳过 **null** 值(`key != null && value != null`,**空串照发**),`HttpRequest.headers(key: String, value: String)` 非空形参同形(同 E7 的替换语义) |

### 1.4 现状范式普查(协程化的直接动因)

项目已是「协程外壳 + okgo 回调内核」的混合形态,迁移是消除双范式,不是引入新范式:

| 现状 | 取证 | 问题 |
|---|---|---|
| 协程发起、回调收尾,两套生命周期脱钩 | `SearchViewModel.kt:128-138`(`scope.launch(IO)` 内发 okgo 回调请求) | 取消只能靠 tag,scope 取消不掉回调 |
| 手动序号失效法防竞态(协程结构化取消的本职) | `SearchViewModel` suggestSeq(:170-176)、`PlayLoader` playRequestSeq(:234-236)、`DetailViewModel` requestToken | 同一问题三套 homemade 实现 |
| 手动线程跳转 | `ListLoader.kt`(`Looper.myLooper()` 检查 + `PREPARE_POOL.execute`) | `withContext` 一行的事 |
| 已有 suspend 包装 okgo 回调的先例 | `AppBootstrap.kt:131,147`、`HomeViewModel.kt:372`、`PartitionListViewModel.kt:146`(`suspendCancellableCoroutine` 包 Loader 回调) | Loader 直接 suspend 化后这些包装层可简化 |
| `kotlinx.coroutines` 已全面使用但**未显式声明依赖** | 全库 `import kotlinx.coroutines` 实测 **36** 文件(`app/src/main` 34 + `app/src/test` 2),坐标靠 lifecycle/compose 传递带入 | N0 显式声明 `kotlinx-coroutines-android` |

迁移动因汇总:① 依赖形态错配(§0);② 三套请求入口并存(okgo 链式 / `catvod.net.OkHttp` 直连 / `HttpClients` 配置中心);③ 混合范式与三套 homemade 取消实现(本节);④ 主线程解析(§1.3 E5 衍生)。

### 1.5 tag 生产/消费图谱(全 Job 化的迁移底册)

okgo 链式 `.tag()` 打点 14 处(上述 9 文件)/ `cancelTag` 消费 12 处,分布:

| tag 值 | 生产者 | 消费者(cancelTag) | 全 Job 化终态(§4.4) |
|---|---|---|---|
| `detail` | `DetailLoader.kt:111`、`ListLoader.kt:195`、`PushDetailResolver.kt:67` | `DetailViewModel.kt:612,675` | `SourceViewModel` 实例取消入口 |
| `search` | `SearchLoader.kt:70,107`(requestTag 硬编码 `"search"`) | `DetailViewModel.kt:676`、`SearchViewModel.kt:230` | 各自 `searchCaller`(SourceViewModel 实例)取消入口 |
| `suggest` | `SearchViewModel.kt:172` | `SearchViewModel.kt:122`(onCleared) | 挂 `viewModelScope` 结构化取消(自动) |
| `play` | `PlayLoader.kt:197`(requestTag) | `PlayUrlResolver.kt:196`(stopParse) | `PlayLoader` 既有 `cancelPlayRequest()`(:234)从 seq 失效法升级为 Job 取消;stopParse 经宿主链 `fetch.cancelPlayRequest()` 触达 |
| `json_jx` | `PlayUrlResolver.kt:280` | `PlayUrlResolver.kt:197` | 片内 Job 引用 |
| `m3u8-1`/`m3u8-2` | `M3u8PurifyUseCase.kt:50,121` | `M3u8PurifyUseCase.kt:41-42`、`PlayUrlResolver.kt:198-199` | useCase 两跳并一协程;**取消槽为进程级**(companion `activeJob` + `cancelActive()`,同 okgo 全局广播);发回调前 `deliver{}` 把本轮从取消面摘下 |
| `api` 地址(动态) | `ListLoader.kt:75,130` | **无消费者** | 打点直接删除(防御性遗留) |
| `key+"_sort"`(动态) | `SortLoader.kt:189,248` | **无消费者** | 打点直接删除 |
| JS 传入 | `Connect.kt:87-91`(okhttp 直连 builder) | `Connect.kt:145-146`(OkGo.cancelTag + 自实现遍历双通道) | 片内自持 call/Job 引用,`OkGo.cancelTag` 通道删除 |

注:`JsonParallel.kt:70` 的 `"ParseTag"` 打在 okhttp 直连 builder 上,不在 okgo 调用面内,不动。

### 1.6 天然例外(不在调用面内,不迁)

| 例外 | 原因 |
|---|---|
| cling(`org.fourthline.cling`,DLNA / UPnP) | 第三方栈内置自有 HTTP / stream 实现,收敛不了(除非换库),属库内部行为 |
| NanoHTTPD(`RemoteServer`) | 本地 HTTP **服务端**,不是客户端出口 |
| librtmp(本地 rtmp AAR) | 自有协议栈,非 HTTP |

附:Android 系统 `HttpURLConnection` 本身就是 okhttp 实现(4.4+),但本项目代码零处使用(`HttpURLConnection`/`openConnection` 全库 0 命中);media3 播放数据源是自研 `player/core/OkHttpDataSource.kt`(底层 okhttp),Coil 走 `coil-network-okhttp` —— 迁移后"自建请求 100% okhttp"成立。

## 2. 目标与非目标

**目标**

- G1 语义等价:§1.3 E1-E4/E6-E8/E10 八条逐条保持;E5/E9 的行为差异显式登记并逐点审查(§4.2/§4.4)。网络配置(DoH / CustomDns / 代理 / SSL / 超时 / `maxRequestsPerHost`)不变。
- G2 依赖清零:终态三口径(§0)全部满足。
- G3 取消机制全 Job 化:12 处 `cancelTag` 归零,按 §1.5 图谱逐链迁移;三套 homemade 序号失效法(suggestSeq / playRequestSeq / requestToken)收敛为结构化取消(纯结果归属判定的 token 除外,逐处评估)。
- G4 分片可回退:每片独立 commit;分片按取消链闭环重排(§5),片内 tag 生产与消费同片退役,无跨片断链窗口。
- G5 新代码可测:await 层的决策类逻辑(URL 拼接 / 状态码判定 / 重试判定 / 取消静默)配纯 JVM 单测。

**非目标**

- N1 不做业务层范式重构:`LiveData`/`Flow` 形态、`SourceChannel`、`BoundedCall`、`SourceHelper.SPIDER_POOL`/`PREPARE_POOL` 线程池一律不动(仅删除其中通往 okgo 的路径);`suspendCancellableCoroutine` 包装层(`HomeViewModel:372` 等)的简化属可选顺手项,不强制。
- N2 不引入新网络库、不改 `HttpClients` 的 client 配置内容(仅去 okgo 化)。
- N3 不动 §1.6 三个例外;不动 `catvod.net.OkHttp` 的既有公开面。
- N4 **JS 契约层不进协程世界**:`com.github.catvod.**` 全部公开签名保持阻塞式、签名逐成员不变(判据 `javap -p -s`,见 `avbox-kotlin-migration-spec.md` §3.2);协程化只发生在方法体内部(内部换血,§4.5)。理由:`suspend` 编译会追加 `Continuation` 参数,外部 jar 插件(DexClassLoader)与 QuickJS 反射调用按"方法名+参数类型"匹配,签名一变即 `NoSuchMethodError`;外部插件不由本项目控制编译节奏。

## 3. 方案总览

**方案(选定):自建 await 层 + 调用点协程化 + 取消全 Job 化**

新增薄 await 层(§4.1):`suspendCancellableCoroutine` 包装现有 client 的 enqueue,内置 E2/E6 判定;19 个文件的回调体改写为顺序协程代码;12 处 `cancelTag` 按 §1.5 图谱迁移为 Job/结构化取消。

- 选定理由:① 现状已是混合范式(§1.4),回调用 `suspend` 替换是收敛而非扩张;② 核心 `okhttp` aar 实测无 suspend 成员(`Call` 接口无 suspend 方法、无 `CallsKt` 扩展),官方伴生模块 `okhttp-coroutines` 只提供桥接、不含 E2/E6 策略判定 ⇒ **策略层必须自建**;桥接已拍板自持 `suspendCancellableCoroutine`(约 15 行/文件,先例 `AppBootstrap.kt:131,147`,**P11**);③ 取消机制从「tag 全局广播」升维为「结构化并发 + Job 精确取消」,消灭三套 homemade 竞态防护;④ 主线程解析顺带挪 IO(E5)。
- 代价(如实登记):每处调用点 diff 从"换 import"放大到"回调体改顺序代码 + try/catch"(数十行/处);E5 恢复线程、E9 取消精度两处行为变化需逐点审查(§4.2/§4.4);分片须按取消链重排(§5)。

**方案 A(否决):忠实 shim + 分片替换**

复刻 okgo 链式 + 回调形状的过渡层,行为零变化。
- 否决理由:保留回调范式 = 保留双范式与三套竞态防护,与"现代化、可维护性"目标相悖;shim 是长期技术债(原方案自认"若未来协程化再另立 spec",本修订即那个 spec,一步到位)。

**方案 C(否决):双通道过渡(tag 保留 + Job 并行)**

- 否决理由:两套取消机制永久并存,每个新请求都要决策走哪条通道;12 处取消点全部要审两遍。用户已拍板直接全 Job 化(§8)。

**分片表(按取消链闭环重排,详细执行见 §5)**

| 片 | 范围 | 文件数 | 闭环的取消链 |
|---|---|---|---|
| N0 | await 层 + 协程依赖显式化 + 单测 | 新增 1-2 | — |
| N1 | `sourcedata` 簇(除 PlayLoader)+ `SourceViewModel` 接线 + `DetailViewModel` 取消点改造 | 7 + 2 | `detail` / `search` |
| N2 | `player` 簇 + `PlayLoader`(自 N1 挪入) | 3 | `play` / `json_jx` / `m3u8-1/2` |
| N3 | UI / 杂项簇 | 4 | `suggest`;同步路径 2 处 suspend 化 |
| N4 | `catvod` 契约层(内部换血,签名守恒) | 3 | JS tag 取消通道 |
| N5 | 收尾:`HttpClients` + 删依赖 | 1 + 构建脚本 | §6.2 全量验收 |

## 4. 详细设计

### 4.1 await 层形态(已定:见 P4)

建议放 `app/src/main/java/com/github/avbox/osc/util/net/`(与既有 `OkProxySelector`/`ProxyAuthenticator` 同包),形态示意:

```kotlin
object Http {
    suspend fun get(url: String, init: HttpRequest.() -> Unit = {}): String
    fun getSync(url: String, init: HttpRequest.() -> Unit = {}): okhttp3.Response  // E1 阻塞路径,仅 N4 使用
}

class HttpRequest internal constructor(private val url: String) {
    fun headers(key: String, value: String)      // E7:单值替换(header,禁用 addHeader)
    fun headers(map: Map<String, String>)
    fun params(key: String, value: String?)      // E8:query 拼接
    fun params(map: Map<String, String>?)
    internal fun build(): okhttp3.Request
}

class HttpException(val code: Int) : Exception("HTTP $code")  // E2:404/≥500
```

调用形态对照(以 `DetailLoader` 为例):

```kotlin
// 迁移前(okgo)
SourceHelper.siteGet(bean).tag("detail").execute(object : AbsCallback<String>() {
    override fun onSuccess(response: Response<String>) { resultParser.json(...) }
    override fun onError(response: Response<String>) { detailResult.postValue(null) }
})

// 迁移后
requestScope.launch {                                   // 归属见 §4.4
    try {
        val body = Http.get(api) { headers(...); params(...) }
        withContext(Dispatchers.IO) { resultParser.json(...) }   // E5:解析挪 IO
    } catch (e: HttpException) { detailResult.postValue(null) }  // E2
    catch (e: CancellationException) { throw e }                 // R12:必须先于 Exception 分支
    catch (e: Exception) { detailResult.postValue(null) }        // E4 等价
}
```

设计约束:

1. 核心实现:`suspendCancellableCoroutine { cont -> client().newCall(req).enqueue(...) }` + `cont.invokeOnCancellation { call.cancel() }`(先例:`AppBootstrap.kt:131,147`)。
2. suspend 出口两条 + 原始响应阻塞路径一条:**String 路径**(`get`)、**原始字节 + 响应头路径**(`getRaw`,`HttpRawResponse(body: ByteArray, headers: Headers)`;N3 增补——`SubtitleLoader` 需原始字节做编码检测 + `content-disposition` 头、`MusicLrc` 需原始字节,而 `get` 的 String 解码在无 charset 声明的 GBK 内容上会乱码);`getSync` 阻塞路径仅 N4 使用。两条 suspend 出口共享同一实现(E2 判定 / E6 重试 / 取消联动 / 读体在 IO)。调用点的 `convertResponse` 有两种写法,统一内建进 String 路径:多数是 `if (body != null) body.string() else throw IllegalStateException(SourceHelper.ERR_NETWORK)`(`M3u8PurifyUseCase.kt:68,130`、`SearchViewModel.kt:150,179` 则是直接 `response.body.string()`)。注:okhttp 5 的 `Response.body` 为非空类型,`body != null` 恒真 ⇒ 该守卫在协程路线下是死分支,await 层不必复刻。
3. 「URL 拼接 / 状态码判定 / 重试判定」抽为可测纯函数(如 `HttpPolicy`),单测覆盖;E6 重试循环在 await 内,仅 `SocketTimeoutException` 触发,**重试上限 3 ⇒ 总尝试最多 4**(P5)。
4. **`getSync` 禁止在任何协程上下文调用**(runBlocking 也不行,R10);它是 N4 契约层阻塞签名的专用出口。**且 `getSync` 不重试、不做 404/≥500 判定** —— 今天 4 处同步调用走的是 `Request.execute()`(`Request.java:382`,直连 okhttp),**完全绕过 cache policy**(E1);E6 的重试循环只属于 `Http.get` 的 suspend 路径。
5. **重试实现细则**(E6 等价):每轮必须**重建 Call**(okgo 每轮 `rawCall = request.getRawCall()`);只认 `java.net.SocketTimeoutException`(okgo 的判定就是 `instanceof SocketTimeoutException`,故 `ConnectException`/`UnknownHostException` **不重试**);重试上限 3、总尝试最多 4。

### 4.2 等价语义映射(§1.3 → 实现)

| 语义 | 实现要点 |
|---|---|
| E1 同步返回原始响应 | `getSync()` 原样返回(仍仅 N4 使用);N3 的 `SubtitleLoader`/`MusicLrc` 调用方改为 suspend,内芯换 `Http.getRaw()`(原始字节 + headers;不可用 `get`,后者会丢编码检测/响应头) |
| E2 404 / ≥500 → 失败 | `HttpException(code)` 抛出,调用点 catch |
| E3 取消静默 | `CancellationException` 语义天然成立;审查所有 `catch (Exception)` 分支先放行 CancellationException(R12) |
| E4 转换异常 → 失败 | `body.string()` 异常自然传播 |
| E5 主线程派发 → 恢复线程跟随 caller | 挂 `viewModelScope`/Main scope 即主线程恢复;解析统一 `withContext(IO)`。**逐点审查清单**:所有 `callback.done` / `HomeRecCallback` / 直接改 UI 状态的回调体,确认无主线程假设(完整清单随 N1-N3 各片执行时登记) |
| E6 超时重试默认 3 | await 层内重试循环,**重试上限 3 / 总尝试最多 4**(P5) |
| E7 头**替换**语义 | `Request.Builder.header(...)`(**禁用 `addHeader`**);全局 UA 按 P6 等价保留 |
| E8 params → query | `HttpUrl.Builder.addQueryParameter(...)`;值编码差异见 §1.3 E8 |
| E9 tag / 取消 | **tag 通道退役**;迁移映射见 §1.5 + §4.4 |
| E10 Accept-Language 默认头 | `HttpRequest.build()` 注入 `HttpPolicy.acceptLanguage()`(替换语义,早于 UA);显式同名头覆盖 |

### 4.3 client 取用(reloadDns 兼容)

- **每请求现取** `HttpClients.getDefaultClient()`(或注入的等价入口),**禁止静态缓存 client 引用** —— `reloadDns()` 会重建 client 并 `setOkHttpClient(new)`;若 await 层缓存旧引用,重载后 DNS/代理不生效。
- 分片期安全性:await 层与未迁移的 okgo 调用使用**同一个 client 实例与 dispatcher**;okgo 侧请求仍走 `Request.tag()`,已迁移侧走 Job —— 两套取消在分片期互不干扰的前提是**同一取消链的生产与消费同片退役**(§5 分片即按此排布)。
- 不静态持有 `noRedirectClient` 等次要形态:await 层无调用点需要它;**N4 的 `Connect.kt:43` 与 `catvod/net/OkHttp.kt:69` 仍在直取 `HttpClients.getNoRedirectClient()`**(不经 await 层),保持现状。

### 4.4 取消架构:全 Job 化(核心设计)

**Scope 归属总则**:

1. UI 侧请求挂宿主 `viewModelScope`(如 `SearchViewModel.fetchSuggest`)—— onCleared 结构化取消,`suggest` 链的 cancelTag 直接删除。
2. `SourceViewModel`(`SourceViewModel.kt:13`,继承 ViewModel)实例由 10 处宿主直接构造(`HomeViewModel`×4、`PartitionListViewModel`×2、`DetailViewModel`×2、`SearchViewModel`×1、`PlaybackFetch`×1),**未注册 ViewModelStore,onCleared 不触发,viewModelScope 永生** —— 不可直接复用。方案(已拍板,见 P9):内建 `requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` + 按链各持一个 Job(detail/search/play),宿主销毁或切换时显式调用。**dispatcher 取 Main.immediate 而非 IO**:N1 保留 `PREPARE_POOL` 跳转,回调体线程与今天(okgo 回调派发到主线程)逐点一致;网络往返本就在 okhttp 自有线程、解析按 P10 统一挪 IO,故性能等价,而选 IO 会失去主线程的天然串行化、让多链真并发去争 `ApiConfig`/`synchronized(fallbackCandidates)`/MMKV 的锁。**`cancelPlayRequest()` 只取消 play 链,不得连带取消 preload 链**(`preloadRequestSeq` 是独立计数器,今天不受它影响)。
3. `GlobalScope` 一律禁用(R11);`runBlocking` 一律禁用(R10,含 N4 —— Connect 用阻塞式 `getSync` 而非 runBlocking 桥接)。

**12 处 `cancelTag` 逐处迁移(承接 §1.5 图谱)**:

| # | 现位置 | 迁移终态 |
|---|---|---|
| 1-2 | `M3u8PurifyUseCase.kt:41-42`(新一轮前取消旧轮) | useCase 两跳链(取 m3u8 → 取 forward URL)并为一协程顺序执行,取消整链同断;入口 `cancelActive()` 后重启,取消槽与净化锁均为 **companion 进程级**(净化子系统本就是进程级:static `M3u8.currentAdCount` + 进程内 `RemoteServer` 内容表),故 handover/headless 期的在飞净化仍可被取消 |
| 3-6 | `PlayUrlResolver.stopParse():196-199`(4 连取消) | `json_jx` 自持 Job;m3u8 经 useCase 暴露的 `cancel()`;`play` 经宿主链 `fetch.cancelPlayRequest()`(PlaybackController:308 已有链路)触达 PlayLoader |
| 7 | `SearchViewModel.kt:122`(onCleared 取消 suggest) | 删除,`viewModelScope` 结构化取消自动覆盖 |
| 8 | `SearchViewModel.kt:230`(新搜索前取消 search) | `searchCaller.cancelSearch()`(SourceViewModel 实例取消入口);`suggestSeq` 计数器同步删除 |
| 9 | `DetailViewModel.kt:612`(超时 fallback 流程取消 detail) | `sourceViewModel.cancelDetail()` |
| 10-11 | `DetailViewModel.kt:675-676`(destroyEngine) | `sourceViewModel.cancelDetail(); searchCaller.cancelSearch()` |
| 12 | `Connect.kt:145`(JS 线程取消) | 删除 `OkGo.getInstance().cancelTag(tag)`,保留并统一为自持 call 引用取消(:135-161 已有自实现遍历,收敛为直接引用) |

**三套 homemade 序号失效法的收敛**(口径必须统一:**Job 管"请求要不要继续跑",序号/token 管"结果属于哪一轮",两者不可互替**):

- `suggestSeq`(`SearchViewModel.kt:52,170-176`)—— 整链在 `viewModelScope` 内、无线程池跳转 ⇒ 结构化取消可完整覆盖,**随 #8 删除**。
- `playRequestSeq`(`PlayLoader.kt:29,234`)—— ⚠️ **不可整体删除**。`playInternal`(:51)在 main 上会先跳 `PREPARE_POOL`,而 `postPlayResult` 的 3 个前置出口(:76 pushFallback / :81 source==null / :92 type 未知)**不经网络、不可取消**;删掉序号门会让过期请求的 null 结果覆盖新一轮的好结果。Job 取消只覆盖"网络阶段",序号门必须保留给"池阶段"。
- `preloadRequestSeq`(`PlayLoader.kt:30`)—— 与 play 链**各自独立**,`cancelPlayRequest()` 今天不影响 preload;Job 化后必须保持这条边界(两链共享单 Job 会连带取消预载)。
- `requestToken`(`DetailViewModel.kt:95`,结果归属判定)保留,不强行 Job 化。

**PlayLoader 既有取消链的推广**:`cancelPlayRequest()`(:234,seq 失效法)→ SourceViewModel:107 → PlaybackFetch:47 → PlaybackRetryDelegate:279,296 / PlaybackController:308,923 已是"实例级取消入口"的现成链路,全 Job 化即把该模式推广到 detail/search 链,接口形态不变、内部实现换 Job。

### 4.5 JS 契约层(N4):壳阻塞、芯换血

- **壳(公开签名)**:`JsLoader`/`JarLoader`/`Connect` 全部公开方法保持阻塞式、签名逐成员不变(`javap -p -s` 判据);`Spider.cancelByTag()` 等被外部 jar 覆写/调用的方法原样保留。
- **芯(内部实现)**:
  - `JsLoader`/`JarLoader` 的同步下载:okgo `execute()` → `Http.getSync()`(阻塞直连,E1);不得为"协程化"引入 runBlocking 桥接(R10)。
  - `Connect.kt` 取消通道:#12;client 取用(`getDefaultClient`/`getNoRedirectClient`)不变。
  - `JsLoader` 自身下载 jar 的调用若在协程上下文(如 AppBootstrap 链),可加 suspend 伴生重载,**公开阻塞方法保留并委托之**。
- 边界翻译是标准姿势(先例:JNI 边界、OkHttp 自身 execute/enqueue 双出口):协程停在契约边界,不是能力不足。

### 4.6 `HttpClients` 收尾项(N5)

| 项 | 处置 |
|---|---|
| `OkGo.getInstance().setOkHttpClient(...)` ×2 | 删除(okgo 消失后无意义;await 层直取 `getDefaultClient()`) |
| `HttpHeaders.setUserAgent("okhttp/" + OkHttp.VERSION)` ×2 | 按 **P6** 等价保留:await 层以**替换**语义注入全局 UA |
| `HttpsUtils.UnSafeHostnameVerifier` | 替换为 `builder.hostnameVerifier { _, _ -> true }`(先例:`catvod/net/OkHttp.kt:214`) |
| okgo 版 `HttpLoggingInterceptor` ×4 | 按 **P7** 全部删除,不引入官方 `logging-interceptor`。**注意 :87/:255 两处挂在 `initExoOkHttpClient()` 上**(ExoPlayer client),同属 okgo 依赖,须一并处置 |
| `reloadDns()` 的联动(Parser/`OkHttp.resetClient()`) | 原样保留 |

### 4.7 不迁清单(终态也不动)

- §1.6 三个例外;
- `catvod.net.OkHttp` 既有实现(不改其面);
- 业务线程池与调度结构(`SourceHelper.SPIDER_POOL`/`PREPARE_POOL`、`BoundedCall`)—— 仅删除其中通往 okgo 的路径;
- `JsonParallel.kt` 的 okhttp 直连 tag("ParseTag" 不在 okgo 调用面);
- 另注:`sardine-android`(WebDAV)在 `gradle/libs.versions.toml` 与 `proguard-rules.pro` 有记录但**代码零引用**,属独立清理项,不在本 spec 范围。

## 5. 分片执行计划与实测登记

每片统一四步:① 替换调用面 → ② 片内 `com.lzy` grep 归零 + `cancelTag` 归零 + `git diff` 检查无夹带 → ③ `.\gradlew :app:assembleDebug` 与 `.\gradlew :app:testDebugUnitTest` → ④ 判读(`BUILD SUCCESSFUL` + 用例计数)后提交。提交信息英文小写、简短、带 scope(如 `net: add coroutine http layer`、`net: migrate sourcedata to coroutines`)。

分片按**取消链闭环**排布:同一 tag 的生产者与消费者必须同片退役,避免"生产者已 Job 化、消费者还在 cancelTag 打空气"的断链窗口。

### N0 await 层 + 单测

- 范围:新增 await 层(§4.1,位置与命名见 P4;重试口径见 P5;桥接自持见 P11)+ 纯函数决策层 + 单测;`libs.versions.toml` 显式声明 `kotlinx-coroutines-android`(§1.4);不改任何调用点。
- 卡口:构建绿 + await 层单测绿(基线:现有 536+ 用例不回退)。
- 实测登记:_已回填(2026-10-06)_:新增 `app/src/main/java/com/github/avbox/osc/util/net/Http.kt`(`Http`/`HttpRequest`/`HttpException`)与 `HttpPolicy.kt`(URL 拼接 / E2 判定 / E6 判定 / E10 取值);重试·状态码·取消静默经 `internal Http.executeWithRetry(request, client)` 注入 fake client 覆盖;单测新增 3 类 22 例(`HttpPolicyTest` 7 / `HttpRequestTest` 9 / `HttpTest` 6);`libs.versions.toml` 显式声明 `kotlinx-coroutines-android` = **1.10.2**(与既有解析版本一致,依赖图零变化);`.\gradlew :app:assembleDebug` + `.\gradlew :app:testDebugUnitTest` 绿,总计 **677 用例 0 失败**(含新增 22 例)。**N1 收尾审查修复(`9e66e73`)**:响应体读取从挂起点恢复线程(Main)移入 `executeWithRetry` 内层 `withContext(Dispatchers.IO)`,`HttpTest` +1 例锁定(总 678)。

### N1 `sourcedata` 簇(7 文件)+ 接线 2 文件 —— detail/search 链闭环

- 范围:`SourceHelper.siteGet` 返回 `GetRequest<String>` → **suspend 函数**(枢纽,先行,7 个下游文件同片消化);ListLoader/DetailLoader/SearchLoader/SortLoader/PushDetailResolver/SubtitleViewModel 回调体改顺序协程;`SourceViewModel` 加 `requestScope` + `cancelDetail()`/`cancelSearch()`(P9);`DetailViewModel` 3 处 cancelTag 改实例取消入口。
- 留意:`siteGet` 变 suspend 是 breaking change,片内 10 处调用全部改;`onError` 里读 `code()`/`exception` 的位置(ListLoader/SortLoader)改 catch 形参;`api`/`key+"_sort"` 动态 tag 打点直接删除;resultParser 解析挪 IO 后逐点确认 `SourceChannel`/LiveData 仍用 `postValue`。
- 实测登记:_已回填(2026-10-06)_:① `SourceHelper.siteGet` 转 suspend —— `suspend fun siteGet(sourceBean, init: HttpRequest.() -> Unit = {}): String`;PlayLoader 未迁的 1 处调用改用临时桥 `siteGetRequest()`(okgo 版原样保留,**N2 删除**);② 7 文件回调体全部改顺序协程:ListLoader/DetailLoader/SearchLoader/SortLoader/PushDetailResolver(blocking 段 `withContext(Dispatchers.IO)`,取代原 Main→PREPARE_POOL 递归跳转;解析按 P10 同置 IO;OkGo 的 `cancelTag` 打点与无消费者 tag(`api`/`key_sort`)齐删);SubtitleViewModel 两处 `OkGo.get` 改 `viewModelScope.launch + Http.get`;③ `SourceResultParser.xml/json` 级联转 suspend(`PushDetailResolver.checkPush` → suspend,`Http.get` + `withTimeoutOrNull(15s)` 取代自有单线程 + CountDownLatch,超时改为真正取消请求),两处宽 `catch (Exception)` 补 `CancellationException` 放行(R12);④ `SourceViewModel` 加 `requestScope`(`SupervisorJob + Dispatchers.Main.immediate`,**lazy 构造**:JVM 单测直接 `SourceViewModel()`,不能触碰 `Dispatchers.Main`)+ detail/search 两条链「各一个 `SupervisorJob` 作父、cancel 后重建」的取消入口 `cancelDetail()`/`cancelSearch()`(cancel-all 该链全部在飞请求,不连带其它链);⑤ 接线:`DetailViewModel` 3 处 `cancelTag` 改实例取消入口、`SearchViewModel:230` 的 search 取消**提前到本片**收口为 `searchCaller.cancelSearch()`(生产者已随本片退役,不留 N1→N3 断链窗口);⑥ 片内 `com.lzy` 归零(仅剩 `SourceHelper.siteGetRequest` 桥与 N2 的 PlayLoader),`cancelTag` 全库余 8 处(PlayUrlResolver 4 + M3u8PurifyUseCase 2 → N2,SearchViewModel 1 `suggest` → N3,Connect 1 → N4);⑦ `SourceResultParserRoutingTest` 3 例调用点包 `runBlocking`(签名转 suspend 的连带);`.\gradlew :app:assembleDebug` + `:app:testDebugUnitTest` 绿,总计 **677 用例 0 失败**。
- 片内偏差登记:blocking 段用 `Dispatchers.IO` 而非 `PREPARE_POOL.asCoroutineDispatcher()`(避免 3 线程池与 PlayLoader 共用时的排队/饥饿面);`PREPARE_POOL`/`SPIDER_POOL` 本体与其它使用点(`SourceViewModel.action`、`getFixUrl` 内部、PlayLoader)全部保留。
- **收尾审查轮(2026-10-06,独立只读复核 + 作者复核)**—— 发现 4 条,3 条当场修复,1 条留 N3:
  - 中(已修,`9e66e73`):`Http.executeWithRetry` 挂起点恢复在 caller 线程(链 dispatcher 为 `Main.immediate`),`response.body.string()` 整包读取(gzip 解压在内)会落在**主线程** —— okgo 时代 convert 在 okhttp 线程、只有 `onSuccess` 回 Main,属本次引入的回退。修复:重试循环内层 `withContext(Dispatchers.IO)`(每轮重建 Call 与 4 次尝试口径不变),单测 +1 例 `get_readsResponseBodyOffCallerThread` 锁定。
  - 中(已修,`e652d70`):`PushDetailResolver.fetchPushDetail` 的 `type != 4`(csp)分支只剩 `withContext(IO)` 无超时 —— `withTimeout` 无法打断阻塞调用,等于丢掉原 `CountDownLatch.await(15s)` 的硬上限。修复:该分支改 `BoundedCall.call(..., PUSH_DETAIL_TIMEOUT_MS, ...)`(与其它 loader 的 spider 调用同形),type-4 分支保留 `withTimeoutOrNull(15s)`。
  - 低(已修,`e652d70`):`SortLoader` 的 `RemoteTVBox.post` 桥里 `it.body.string()` 抛 `IOException` 时原本会**永不 resume**(协程永挂)。修复:`catch` 内 `cont.resumeWithException(e)`,由既有 `catch (Exception) → postSortFailure` 接手。
  - 低(既有,留 N3):`SearchViewModel.onCleared()` 未接 `searchCaller.cancelSearch()`(页面销毁后旧搜索仍跑完;结果只落无人订阅的通道,无功能后果)—— 与旧行为一致、非回归,N3 处理 SearchViewModel 时一并收口。
  - 修复后复跑 `:app:assembleDebug` + `:app:testDebugUnitTest` 绿,**678 用例 0 失败**(-0)。审查通过项(逐条核过、非问题):分支语义/E2·E4 判定、R12 放行全在、解析与 spider 调用全在 IO、取消链闭环(余 8 处 cancelTag 全属 N2/N3/N4)、`checkPush` 元素增删与失败标记语义守恒、`Main.immediate` 不阻塞(除已修项)、签名与调用点无漏改;顺带复核了 `DetailLoader` 失败分支沿用 `resultParser.json(detailResult, "", ...)`(与迁移前逐字一致,非本次改动)。
- **第二轮审查(2026-10-06,换角度独立复核)**—— 结论:**未发现阻断/高/中**,前轮两处修复经逐条验证成立且完备(重试计数=总 4 次尝试 / 仅 `SocketTimeoutException` / 取消静默 / `getSync` 不受影响;BoundedCall 的 15s 硬上限与「解析失败标记」路径与迁移前一致)。新发现 3 条低危、全部当场修复:
  - 低(已修,`81999ce`):重试范围宽于 okgo —— `body.string()` 原在重试 try 内,读体超时会重发整包(okgo 的读体失败属 E4 转换失败、不重试)。修复:重试只围绕 `execute`(请求阶段),读体在重试之外;单测 +1 例 `get_doesNotRetryBodyReadTimeout`。
  - 低(已修,`b675a09`):`SortLoader` 的 `RemoteTVBox` 桥 `cont.resume` 写在 `response.use` 内,`close()` 抛 `IOException` 时二次 resume(okhttp 线程 `IllegalStateException`);改为先读出 body 再 resume。
  - 低(已修,`b675a09`):`SubtitleViewModel.pagesTotal` 在 IO 块内写、Main 读(非 volatile);改为解析出的页数带回 caller(Main)再赋值。
  - 既有低危登记(非本次引入、不修):`RemoteTVBox.post` 固定 1s 超时(与站点级 15s 口径不同,迁移前即如此);`SubtitleViewModel.subtitleUrlFromAssrt` 的裸 okhttp 请求无取消挂钩(VM 清除后仍回调)。
  - 复跑绿,**679 用例 0 失败**。**两轮审查收敛:最新一轮无阻断/高/中、剩余全为既有低危 ⇒ 本片可收尾**。

### N2 `player` 簇 + `PlayLoader` —— play/json_jx/m3u8 链闭环

- 范围:`PlayUrlResolver`(`json_jx` Job 化 + `stopParse()` 4 连取消改显式取消)、`M3u8PurifyUseCase`(两跳并一协程 + 进程级取消槽)、`PlayLoader`(回调体改协程,`cancelPlayRequest()` seq 失效法升级 Job)。
- 留意:`PlayLoader` 从 sourcedata 挪入本片(与 stopParse 的 `play` 消费同片闭环);PlaybackFetch/PlaybackRetryDelegate/PlaybackController 的 `cancelPlayRequest()` 调用链接口不变,仅内部实现变化。
- 实测登记:_已回填(2026-10-06)_:① `M3u8PurifyUseCase`:取 m3u8 与取 forward URL 两跳并为**单协程**(`requestScope` = `SupervisorJob + Dispatchers.Main.immediate` 且 **lazy 构造**;取消槽 = companion `activeJob`,入口 `cancelActive()` 后重启,`url.contains("url=")` 早退路径保持同步且**不置空取消槽**);两跳共用私有 `suspend fun request(url, headers)`(`Http.get` + 逐头透传);`M3u8.purify` 按 P10 挪 `withContext(Dispatchers.IO)`(其余 `RemoteServer.putM3u8Content` / 回调 / Toast 仍在 Main,与原 okgo 主线程回调逐点一致);`purify + 读 currentAdCount` 同处 companion 级 `Mutex` 临界区,读数随 `deliver` 分支带出(不在 Main 读静态)。② `PlayUrlResolver`:`json_jx` 自持 `jsonJob`(lazy `requestScope`,Main.immediate),`stopParse()` 的 4 连 `cancelTag` → `host.cancelPlayRequest()`(play)+ `jsonJob?.cancel()`(json_jx)+ `host.cancelM3u8Purify()`(m3u8-1/2);`jsonParse` 按 P10 挪 IO,`host.*` 交互仍在 Main(E5 恢复线程 = 原主线程回调);网络/解析两段 catch 各自放行 `CancellationException`(R12)。③ **m3u8 取消接线**(未迁文件面):`PlayUrlResolver.Host` 新增 `cancelPlayRequest()`/`cancelM3u8Purify()` → PlaybackController 侧实现为 `fetch.cancelPlayRequest()` 与 `M3u8PurifyUseCase.cancelActive()`(进程级;收尾审查轮由"经 view bridge"改此,见下「收尾审查轮」第 3 条,两个接口成员及其实现已回退,接口面回到原状)。④ `PlayLoader`:`playFromExtendedApi` 回调体改 `requestScope.launch(chain)` + `SourceHelper.siteGet`(`params("play"/"flag"/"extend")`;okgo `HttpParams.put` 源码级核对=null 值跳过、空串保留,`HttpRequest.params(key, String?)` 同形);归一化/合并/主线程投递语义不变,解析段按 P10 挪 IO;`cancelPlayRequest()` = **seq 自增(池阶段门保留)+ play 链 `SupervisorJob` cancel 后重建**(cancel-all 对齐原 tag 广播),`preloadChain` 独立且无取消入口(迁移前 `playPreload` tag 本就无消费者);`rootJob` + lazy `requestScope`(JVM 单测直接 `SourceViewModel()` 构造 → 不触碰 `Dispatchers.Main`,同 N1 口径)。⑤ 桥删除:`SourceHelper.siteGetRequest` 删除,连带删掉唯一消费者已消失的 `SourceHelper.ERR_NETWORK` 常量。⑥ 卡口:片内 `com.lzy` 归零(余 8 文件全属 N3/N4/N5);`cancelTag` 全库余 **2**(SearchViewModel `suggest` → N3、Connect → N4);`runBlocking`/`GlobalScope` 全库 0;改动 10 文件行尾全 LF;`.\gradlew :app:assembleDebug` + `:app:testDebugUnitTest` 绿,**679 用例 0 失败**(-0,`PlayLoaderSeqTest` 3 例未动仍绿)。
- **行为差异登记(E9/R14 的 N2 面貌)**:`stopParse()` 的 play 取消现在走 `PlayLoader.cancelPlayRequest()`(seq 自增 + Job 取消),比 okgo 的 `cancelTag("play")` 多两处作用面 —— ① `PREPARE_POOL` 派发后、请求入队前(含 `getFixUrl` 阻塞段,最长可达源级 `playTimeoutSeconds`)的请求不再继续(chain 已取消 ⇒ `launch(chain)` 直接取消);② 已 `mainHandler.post` 但尚未执行的 play 结果会被序号门作废。方向与既有 `cancelInFlight()`/两条 resolve 恢复路径一致(它们本就在 stopParse 前调 `cancelPlayRequest()`),最坏后果 = 该轮解析被放弃、由既有 resolve 超时链路换线/重试,列入真机走查(起播/换源/弱网)。**未动**:`playRequestSeq`/`preloadRequestSeq` 两门、`getFixUrl` 的 `SPIDER_POOL` 阻塞段、`PlaybackFetch`/`PlaybackRetryDelegate`/`PlaybackController.cancelInFlight` 调用链。
- **收尾审查轮(2026-10-06,两轮独立只读复核 + 作者复核)** —— 第一轮 4 条(2 中 2 低)、第二轮换角度 2 中(同一根因)+ 3 低,全部当场处置:
  - 中(已修):**m3u8 净化协程自己取消自己** —— 回调链 `processM3u8Content → callback.startPlayUrl` → `PlayContainerControlListener:126 goPlayUrl` → `PlaybackController:788 stopParse()` → `cancelM3u8Purify`,而 `PlayContainerViewBridge.runOnUi` 在主线程同步内联 ⇒ 取消对象 = 正在执行该语句的协程(当时恰好无功能后果,但此后任何新增挂起点都会被静默掐断)。修复:`deliver { }` 在**发回调前**把本轮从取消面摘下(`activeJob === coroutineContext[Job]` 身份判等),即「取消只作用于网络段,不作用于已产出结果的轮次」,与 okgo `cancelTag` 只取消网络 call 同形。
  - 中(已修,与上同一根因):`M3u8.currentAdCount` 是**进程级静态**,`M3u8.purify` 挪 IO 后从"主线程天然串行"变"跨轮并发写单槽"(可致"净化静默失效");且锁若留在实例上,两个页面实例并发时锁不生效。修复:锁与取消槽**上收为 use case 的 companion(进程级)**,`purify + 读数`同临界区并把读数带出。
  - 中(已修,第二轮):**取消通道原先绑"当前 view bridge"** —— `PlaybackEngine.detach(page, keepPlayback=true)`(handover)不走 `stopPlaybackForPageExit` 却立刻 `setViewBridge(headlessView)`,旧页在飞净化成"孤儿"、永不可被后续 stopParse 取消(okgo 的 `cancelTag` 是全局广播,不受 bridge 影响)。修复:取消语义改**进程级** —— `PlayUrlResolver.Host.cancelM3u8Purify()` → `PlaybackController` → `M3u8PurifyUseCase.cancelActive()`;连带**回退** `PlaybackViewBridge`/`PlayerControlApi` 的 `cancelM3u8Purify()` 两个接口成员及其 3 处实现(无线程/bridge 绑定面)。
  - 低(已修):`PlayLoader.cancelPlayRequest()` 无回归保护 —— 新增 `PlayLoaderSeqTest.cancelPlayRequestInvalidatesOnlyPlayChain`(play 序号自增 / preload 序号不动 / 旧链 `isCancelled` / 链换新实例 / preload 链仍 active / 取消前序号变陈旧);三种变异(连带 cancel preload、删 seq 自增、不重建链)均必然失败。
  - 低(判定**不成立**,不改码):"`HttpRequest.headers` 未复刻 okgo 空值过滤" —— `HttpHeaders.put` 源码 = `if (key != null && value != null)`,**只跳 null、空串照发**;`HttpRequest.headers(key: String, value: String)` 非空形参同形(§1.3 E10 措辞已同步修正)。
  - 低(既有/设计,登记不改):`RemoteServer.putM3u8Content` 在投递前写入 ⇒ "内容已入服务端但客户端被取消"会留痕,靠既有 4 槽 LRU 淘汰兜底;本轮语义变化(投递段不可取消、净化段取消=丢弃结果)连同"取消面变窄"一并登记。
  - **覆盖缺口(已登记)**:`deliver`/`purifyLock` 无法纯 JVM 用例锁定(use case 构造需要 `Context`),留真机走查(连续切集观察 `echo-fixAdM3u8` / `echo-m3u8` 日志)。
  - 复核通过项(逐条核过、非问题):被迁三文件 okgo 残留 0 且被删 API(含 `app/src/test`)零引用;6 条协程出口全部走 `deliver` 无漏口;`deliver` 之后无挂起点/网络(不会"取消后仍跑完并回调");第二跳网络段在释放前仍可被真取消(`invokeOnCancellation { call.cancel() }`);早退路径不置空取消槽(保旧轮可被 startPlayUrl 链路取消);`withLock` 挂起不阻塞、取消即解锁、单锁无死锁;`SupervisorJob(rootJob)` 父子不误杀;"轮次换新"`assertNotSame`、池阶段序号门与 preload 隔离齐备;`PlaybackController.play` 先 arm 超时(`PlaybackTimeouts` 15s 起)后发请求 ⇒ 不存在"新请求被旧 cancel 误杀后静默消失"。
  - 修复后复跑 `:app:assembleDebug` + `:app:testDebugUnitTest` 绿,**680 用例 0 失败**(+1)。**两轮复核后收敛:最新一轮无阻断/高,中危全部当场修复 ⇒ 本片可收尾**。

### N3 UI / 杂项簇(4 文件)—— suggest 链闭环

- 范围:`SearchViewModel`(fetchSuggest 挂 viewModelScope + 删 suggestSeq;fetchSearch 的 `cancelTag("search")` 改 `searchCaller.cancelSearch()`;fetchHotSearch 协程直发)、`LiveProxyLoader`、`SubtitleLoader`(同步 → suspend)、`MusicLrc`(调用方已 suspend,内芯换 `Http.get()`)。
- 留意:依赖 N1 已提供 `SourceViewModel.cancelSearch()`;`HomeViewModel:372`/`PartitionListViewModel:146` 的 `suspendCancellableCoroutine` 包装层此时可顺手简化为直接 suspend 调用(可选,不强制)。
- 实测登记:_已回填(2026-10-06)_:① 范围实为 4+1 文件:`MusicPlayerActivity.syncLyric` 的 `runCatching { MusicLrc.load }` 随 MusicLrc 取消面变化一并改 try/catch(CancellationException 放行,R12;否则取消被吞且打"parse failed"误导日志);② `SearchViewModel`:`fetchSuggest` 挂 `viewModelScope`(Main.immediate),`suggestSeq` 删除 → `suggestJob`(新输入 cancel 旧请求,取消精度升级;`clearSuggest` 同改);`fetchHotSearch` 保留 IO 形态(缓存 KV 读写 + 解析均在 IO,与迁移前同),`Http.get` + `headers("User-Agent", UA.random())`,catch 顺序 CancellationException → Exception(原 onError 兜底);`onCleared` 删 `cancelTag("suggest")`,补 `searchCaller.cancelSearch()`(收 N1 遗留);`search` 的取消 N1 已提前收口,本片无改动;③ `LiveProxyLoader`:实例级 `loadScope`(`SupervisorJob + Dispatchers.Main.immediate`,**lazy** 构造),else 分支 `Http.get` + `TxtSubscribe.parseToJsonArray` 挪 IO;`loadLives` / `channelGroupList` / `ArrayList` 拷回保持 Main(R3 关注点,与迁移前逐点一致);`cancelAll()` 在清 handler 之外补 `cancelChildren()`(首次获得真正取消面,见行为差异);`.py/.js` 分支(BoundedCall + 单线程 executor)未动;④ `SubtitleLoader`:`loadFromRemote` 转 suspend、内芯换 `Http.getRaw`(原始字节与 `content-disposition` 头两处用途都保留);`loadFromRemoteAsync` 改 object 级 lazy `ioScope`(`SupervisorJob + Dispatchers.IO`)协程,回调投递仍走 `AppTaskExecutor.mainThread()`;`loadFromDataAsync`/`loadFromLocalAsync`(纯本地,无 okgo)未动;**删除无调用者的同步重载 `loadSubtitle(path): SubtitleLoadSuccessResult?`**(app/src 全量 grep 0 引用,含 test);⑤ `MusicLrc`:`read`/`fetch` 转 suspend、`runCatching` 改 try/catch(CancellationException 放行)、`fetch` 换 `Http.getRaw`(保留原始字节路径 ⇒ `UniversalDetector` 编码检测不变);⑥ 卡口:片内 `com.lzy` 归零(全库余 4 文件全属 N4/N5),`cancelTag` 全库余 **1**(Connect → N4),`runBlocking`/`GlobalScope` 0,`suggestSeq` 全库 0,改动 7 文件行尾全 LF;`.\gradlew :app:assembleDebug` + `:app:testDebugUnitTest` 绿,**683 用例 0 失败**(+3,`HttpTest` 新增 `getRaw` 3 例)。
- **await 层扩展(片内偏差登记)**:新增 `Http.getRaw(url, init): HttpRawResponse`(`body: ByteArray` + `headers: Headers`;E2/E6/取消/读体 IO 与 `get` 同策略,内部 `executeWithRetry`/`executeRawWithRetry` 共享 `executeChecked` 泛型实现)。原因:`SubtitleLoader` 需原始字节(编码检测)+ `content-disposition` 头、`MusicLrc` 需原始字节,`get` 的 String 路径在无 charset 声明的 GBK 内容上会乱码 ⇒ §4.1 原口径"仅 String suspend 路径"不足,补第二 suspend 出口;§4.1 约束 2 与 §4.2 E1 行已同步改写。
- **行为差异登记(E9/R14 的 N3 面貌)**:① `SubtitleLoader`/`MusicLrc` 由"同步 execute(不判定 / 不重试 / 不可取消)"变为"suspend 路径(E2 404·≥500 判定 + E6 超时重试)";**取消联动只对 `MusicLrc` 成立**(`MusicPlayerActivity.lyricJob` 可取消),`SubtitleLoader` 无取消入口(全库唯一入口是回调版 `loadSubtitle`;与迁移前"不可取消、跑完由 `DefaultSubtitleEngine.loadGeneration` 丢弃"同形,但重试面使被放弃的下载最坏跑满 4 次尝试 ≈40s 才丢);② `LiveProxyLoader.cancelAll()` 首次真正取消在飞请求(原仅清 handler 消息;修复方向:Activity destroy 后不再有 `loadLives` 全局写入 / `onGroupsLoaded` 回调);③ `fetchSuggest` 取消由"结果 seq 丢弃"升级为"请求级取消",`clearSuggest` 同步取消在飞请求;④ 线程面:SubtitleLoader 解析/回调链仍为 IO → Main,`LiveProxyLoader.loadLives` 保持 Main,`fetchHotSearch` 回调体由 Main 变 IO(写入对象为 `MutableStateFlow`/MMKV,线程安全);⑤ 两条同步路径改 suspend 后由"阻塞 `execute` 直发"变"`enqueue` 进 dispatcher 队列",新增 per-host 限额(`maxRequestsPerHost=10`)与排队面(反向收益:不再占用等待线程);⑥ N0 桥接既有细节本片首次常态化:"已 `onResponse`、任务未执行"窗口内取消 ⇒ 结果被替换为 `CancellationException`,`Response` 不被 `use{}` close(连接被 `call.cancel()` 强制打断,官方 `okhttp-coroutines` 同形;不改码)。
- **可选简化未做(登记)**:`HomeViewModel:372`/`PartitionListViewModel:146` 的 `suspendCancellableCoroutine` 包装的是 `PartitionLoader.request`(fire-and-forget + `SourceChannel.flow.collect` 收结果 + pending 取消语义),非一行可换,留待后续;`DetailViewModel` 的 `requestToken` 保留不强行 Job 化。
- **收尾审查轮(2026-10-06,独立只读复核 + 作者复核)** —— 独立复核结论:**未发现阻断/高/中**;5 条低危(2 条登记口径/补登记、1 条判定不成立、1 条既有边界登记、1 条测试缺口),处置如下:
  - 登记口径更正(N3-①):"`SubtitleLoader` 取消联动"不成立(该条已改写)——object 级 `ioScope` 无取消入口(全库唯一入口是回调版 `loadSubtitle`),被放弃的字幕下载跑满至多 4 次尝试(约 40s)后由 `DefaultSubtitleEngine.loadGeneration` 丢弃;与迁移前"不可取消、跑完丢弃"同形,仅耗时上限由 1×10s 变 4 次尝试。
  - 补登记(N3-⑤):两条同步路径改 suspend 后由"阻塞 `execute` 直发"变"`enqueue` 进 dispatcher 队列",新增 per-host 限额(`maxRequestsPerHost=10`)与排队面。
  - 补登记(N3-⑥):"已 `onResponse`、任务未执行"窗口内取消 ⇒ 结果被替换为 `CancellationException`,`Response` 不被 `use{}` close;属 N0 桥接既有细节(官方 `okhttp-coroutines` 同形),本片首次把取消面接上这两处,登记不改码。
  - 判定不成立(不改码):"`LiveProxyLoader` UI 回调由内联变 `handler.post`,与 `loadLives` 不再同帧" —— 经 `git show 56be128^` 核证,迁移前 `onSuccess`/`onError` 本就 `handler.post`,`loadLives` 与 `host.on*` 的投递边界逐字未变。
  - 测试补齐:`HttpTest` 新增 `getRaw_throwsHttpExceptionForServerError`(≥500)/`getRaw_retriesSocketTimeoutUpToFourAttempts`(总 4 次)/`getRaw_returnsEmptyBody`(空 body 直达 `UniversalDetector` 的真实分支)3 例。
  - 复核通过项(逐条核过、非问题):6 处新/改 catch 的 `CancellationException` 放行全在(R12);`cancelAll()` 只 `cancelChildren` 不打死亡 scope(取消后仍可再 load),`.py/.js` 分支可见产出仍受 `handler` 清空覆盖(与迁移前同);`loadLives`/`channelGroupList`/`ArrayList` 快照全在 Main(`Main.immediate` 保证,`load()` 从非主线程调用亦成立);`fetchSuggest` 无旧值覆盖新值(cancel 后在飞恢复被替换为取消异常,赋值语句到不了);`SubtitleLoader` 并发无共享可变状态;lazy scope 到真正 load 才触碰 Main(JVM 单测不受影响);`Http` 重构后 N1 的两条修复(读体离主线程/重试不收窄到读体)对 `getRaw` 同构成立;响应头/fragment/编码/返回形态与迁移前一致,`HttpException` 安全落既有 catch(`DefaultSubtitleEngine` 的 `exception!!` 不会 NPE)。
  - 复跑 `:app:assembleDebug` + `:app:testDebugUnitTest` 绿,**686 用例 0 失败**(+3)。**独立复核 + 作者复核后收敛:无阻断/高/中 ⇒ 本片可收尾**。

### N4 `catvod` 契约层(3 文件)—— 内部换血,签名守恒

- 范围:`JsLoader`、`JarLoader`(同步下载 → `getSync()` 直连)、`Connect.kt`(取消通道收敛为自持引用)。
- 留意:**只改方法内部实现、签名逐成员不变**(判据 `javap -p -s`,见 `avbox-kotlin-migration-spec.md` §3.2);`Connect.kt` 的 client 取用(`getDefaultClient`/`getNoRedirectClient`)保持不变;禁 runBlocking(R10)。
- 实测登记:_已回填(2026-10-06)_:① `JsLoader.loadJarInternal`:`OkGo.get<File>(jar).execute()` → `Http.getSync(jar)`(E1 阻塞直连;`response.body.byteStream()` + 手动 close 的流语义逐字保留,不判定/不重试/不取消);② `JarLoader.download`:`OkGo.get<File>(url).execute()` → `Http.getSync(url)`(同上;下载循环内的 `Thread.interrupted()` 中断检查保留);③ `Connect.cancelByTag`:删 `OkGo.getInstance().cancelTag(tag)`,`client` 遍历与 `cancelDefaultClient(tag)` 保留 —— 经 okgo 3.0.4 源码核证,`OkGo.cancelTag` 的实现 = 遍历 `getOkHttpClient().dispatcher()` 按 `tag.equals(call.request().tag())` 取消,而 `HttpClients` 的 `setOkHttpClient(defaultClient)` 所设 client 与 `getDefaultClient()` 为同一实例 ⇒ 该通道与既有 `cancelDefaultClient(tag)` 覆盖**完全同面**,删除是纯去重(覆盖面不变);`to()` 的 client 取用(`getDefaultClient`/`getNoRedirectClient`)与 `withTimeout` 均未动;④ 签名守恒(P8):`javap -p -s` 前后对比 `JsLoader`/`JarLoader`/`Connect`(+三 Companion)共 6 个类,**219 行输出逐行一致**;⑤ `Http.getSync` 补 internal 注入重载 `getSync(request, client)`(与 `executeWithRetry(request, client)` 同模式,public 形态行为不变)+ `HttpTest` 新增 2 例锁定 E1 语义(`getSync_returnsRawResponseForFailCodes`:404 原样返回、不抛 `HttpException`;`getSync_doesNotRetryOnTimeout`:超时仅 1 次尝试);⑥ 卡口:`com.lzy` 全库余 **1** 文件(`HttpClients` → N5)、`cancelTag` 全库 **0**、`runBlocking`/`GlobalScope`(main)0、改动 5 文件行尾全 LF;`.\gradlew :app:assembleDebug` + `:app:testDebugUnitTest` 绿,**688 用例 0 失败**(+2)。
- **行为差异登记(E9/R14 的 N4 面貌)**:本片**零业务行为差异** —— 两处同步下载为 E1 等价替换(同为"直连 okhttp、绕过 cache policy、不判定/不重试",请求头 UA/Accept-Language 由 `HttpRequest.build()` 同形注入,§1.3 E7/E10),`getSync` 的消费形态与 okgo `execute()` 的"调用方自管流关闭"逐点一致;`Connect.cancelByTag` 的 okgo 通道删除系纯去重(覆盖面见上③)。既有边界(未动、非本次引入):`cancelByTag` 在 `client != null && tag == null` 时 `tag!!` 抛 NPE 被整体 catch 吞掉(迁移前同样到不了 okgo 通道,行为一致);`Http.getSync` 在 client 未初始化时抛 `IllegalStateException`(okgo `getOkHttpClient()` 同抛),两处调用点均由既有 `catch (e: Throwable)` 覆盖;R10 复核:两处 `getSync` 调用点均为契约层阻塞签名内部,**未引入 runBlocking**。
- **收尾审查轮(2026-10-06,独立只读复核 + 作者复核)** —— 独立复核结论:**未发现阻断/高/中**;6 条低危(1 覆盖缺口 + 5 既有/口味),全部登记不改(无需本片修复):
  - 覆盖缺口(登记):`Http.getSync(url)` public 形态"取 `HttpClients.getDefaultClient()` = okgo 同 client"这条等价前提无 JVM 单测锁定 —— `defaultClient` 为 private 无注入面(`HttpClients.kt:351,357`),补测须为测试改产品码;等价性由 `HttpClients.kt:398-402`(`setOkHttpClient` 与 `defaultClient` 赋值相邻、同实例)与既有 `ConnectTimeoutTest`(`derivedClientKeepsSharedDispatcherSoTagCancelStillWorks`/`twoBuildsFromOneBuilderShareDispatcher`)间接支撑;新增 2 例锁的是 internal 注入重载(与 `executeWithRetry(request, client)` 同模式的既有先例)。
  - 既有边界(登记不改):`Connect.cancelByTag` 第一段遍历要求 `tag != null`(`tag!!`),null 时 NPE 被整体 catch 吞掉、其后遍历不可达 —— 迁移前 okgo 通道同样在 `tag!!` 之后,行为一致;生产 tag 恒非空(`Global.kt:42` 的 `js_okhttp_tag_<key>`、`JsSpider.kt:76`)。
  - 既有边界(登记不改):`Connect.client` 为 companion 静态、last-writer-wins,`reloadDns()` 重建 client 后该静态可能指向旧实例 —— 取消覆盖面 = {上次 `to()` 的 client} ∪ {当前 default}(`cancelDefaultClient`) 两个 dispatcher 遍历且 in-flight call 必在其发起时所用 client 的 dispatcher 上,集合与原三通道(okgo 通道与 `cancelDefaultClient` 同实例)**一致**;`withTimeout` 派生 client 共享 dispatcher(既有 `ConnectTimeoutTest` 锁定)。
  - 既有休眠路径(登记不改):2 元 `to(url, req)` 默认 tag `"js_okhttp_tag"` 与实际 tag `"js_okhttp_tag_<key>"` 不匹配 ⇒ 该重载发起的请求不可被按 tag 取消;全库无调用者(生产走三参 `Global.kt:269/282`),当前休眠。
  - 既有语义(登记不改):不判定状态码 ⇒ 404/5xx 内容会被写进 `.jar`(失败兜底靠 md5 校验 / 一周新鲜度)、失败留部分写文件、只关 body 流不关 response —— 与迁移前同面(E1 等价)。
  - 口味差异(登记不改):`Accept-Language` okgo 侧首次计算后静态缓存、await 层每请求现算(值同形,`HttpPolicy.acceptLanguage`;缺省 UA 兜底同串) —— 属 N0 await 层口径,线上不可观测。
  - 作者侧补齐独立复核的验证边界(其只读工具无法解 okgo zip / 执行 javap):okgo 3.0.4 源码 `Request.getRawCall()`(:340-352)= `generateRequestBody()`(GET 为 null)→ `generateRequest(null)` → `client == null` 时取 `OkGo.getInstance().getOkHttpClient()`(= `defaultClient` 同实例)→ `newCall`;`execute()`(:382-383)= `getRawCall().execute()` ⇒ 与 `Http.getSync` 逐点同形(无 cache policy、无判定、无重试);`NoBodyRequest.generateRequestBuilder` 仅 URL 拼接 + `appendHeaders`(空 headers 不动 builder);全库 grep `addCommonHeaders`/`commonParams`/`setBaseUrl`/`setRetryCount`/`setCacheMode` = 0(okgo 无额外全局加工);`javap -p -s` 219 行前后逐行一致为本实施轮实测,未纳入独立复核工具链,由作者侧签署。
  - 复核通过项(逐条核过、非问题):三文件 diff 仅 import/调用行替换(无成员面改动)、`JsLoader`/`JarLoader` 的 catch/finally/流关闭结构逐字保留、`JarLoader.download` 的 `Thread.interrupted()` 保留、Js tag 取消链(生产 `Connect.getRequest` → 消费 `JsSpider.cancelByTag` → `Connect.cancelByTag`)闭环、`com.lzy` 全库余 1 文件(HttpClients,属 N5)、`.execute()` 全库其余调用点均为 okhttp 直连、`Http.kt` 新增 internal 重载不扩外部可见面(`internal` mangling,非契约层)。
  - **收敛:首轮即无阻断/高/中,剩余全为既有/口味差异/覆盖缺口 ⇒ 本片可收尾。**

### N5 收尾

- 范围:`HttpClients` 去 okgo 化(§4.6);删 `gradle/libs.versions.toml` 的 `okgo` 条目与 `app/build.gradle.kts:151` 的依赖;全量验收(§6.2)。
- 实测登记:_已回填(2026-10-06)_:① `HttpClients` 去 okgo 化、§4.6 五项逐条落地:删 `OkGo.getInstance().setOkHttpClient(...)` ×2(init/reloadDns)、删 `HttpHeaders.setUserAgent(...)` ×2(全局 UA 的等价实现 N0 已落于 `HttpRequest.build()` 的**替换语义**注入,值同串 `"okhttp/" + OkHttp.VERSION`)、`HttpsUtils.UnSafeHostnameVerifier` → `builder.hostnameVerifier { _, _ -> true }`(经 okgo 3.0.4 源码核证 `verify(hostname, session) { return true; }` 完全等价;先例 `catvod/net/OkHttp.kt:214`)、删 okgo 版 `HttpLoggingInterceptor` **×4**(`initExoOkHttpClient`/`initDnsOverHttps`/`init`/`reloadDns` 各一,全部 `Level.NONE` + `ColorLevel.OFF` 零功能,按 P7 不引入官方 logging-interceptor);`reloadDns()` 联动(`Parser.resetHttpClient()` / `com.github.catvod.net.OkHttp.resetClient()`)原样保留;连带清 6 条失效 import(`com.lzy.*` ×4、`okhttp3.OkHttp`、`java.util.logging.Level`);② 依赖坐标:`gradle/libs.versions.toml` 删 `okgo = "3.0.4"` 与 `okgo = { group = "com.lzy.net", … }` 两条 + `app/build.gradle.kts` 删 `implementation(libs.okgo)`(实测位置 :152,范围行原记 :151 系行号偏差,以实测为准);③ §6.2 全量验收:1) `app/src` 内 `com.lzy`/`cancelTag`/`runBlocking`/`GlobalScope` **全 0**(全库唯一残留为 `.codebuddy/tools/_*_baseline_*.java` 历史快照,非构建面);2) `:app:assembleDebug` + `:app:testDebugUnitTest` 绿,**688 用例 0 失败**(与基线持平);3) `:app:dependencies --configuration debugRuntimeClasspath` 无 `com.lzy.net`(okhttp 5.5.0 仍在);4) `AVBox_debug.apk` 内 **29 个 dex 全量字节扫描 `com/lzy`/`com.lzy` 0 命中**;5) main 无 `runBlocking`/`GlobalScope`;6) 真机走查待用户执行(§6.2 第 6 项清单);改动 3 文件行尾全 LF。
- **依赖裁剪风险评估(新增登记)**:动态 jar 的可见依赖面核查:① `app/libs/thunder.jar`(迅雷下载库,宿主 `PlaybackController` 编译期引用)字节扫描 `com/lzy` **0 命中**;② 生态证据:fongmi(`示例文件/TV-fongmi`)全仓 **0 处** okgo/com.lzy,jar 爬虫依赖的是 `com.github.catvod.net.OkHttp` 门面(双生态同名同包,jar 普遍要求 TVBox ∪ fongmi 双端可用)⇒ 生态 jar 引用 okgo 的动机与兼容前提均不存在;③ 上游 TVBox(`示例文件/上游项目`)50 处 okgo 命中均为**宿主代码**(与本次迁移面同形)。残余风险 = 仅面向 TVBox 的野路子 jar 理论上可直接 `import com.lzy.*`(宿主恰好提供),无法穷举;缓解:真机走查覆盖 csp/clan 类 jar 源的加载与请求,回滚按 §6.3(N5 逆序 revert 即恢复依赖)。
- **终态口径核验(§0)**:① `grep -r "com\.lzy" app/src` = 0;② `:app:dependencies` 无 `com.lzy.net:okgo`;③ 自建网络请求 100% okhttp(await 层 / `catvod.net.OkHttp` / 原生 client)、`cancelTag` 全库 0(12 处 tag 取消全 Job/实例级化)—— **N0–N5 六片闭环,迁移完成(待真机走查)**。

## 6. 风险、回滚与验收

### 6.1 风险

| # | 风险 | 级别 | 缓解 |
|---|---|---|---|
| R1 | 漏实现 404/≥500 → 失败(E2):网络错误进成功路径,下游解析报错 | 高 | await 层判定 + 单测锁定 |
| R2 | GET params 拼错位置(E8) | 高 | 单测锁定 URL 形态 |
| R3 | E5 语义反转:恢复线程跟随 caller,带主线程假设的回调体(直接改 UI / 非线程安全 sink)在新线程跑崩或竞态 | 中(已下调) | §4.2 逐点审查清单。**实测缓解**:本项目的 sink 是线程安全的 —— `SearchViewModel.hotSearch`/`suggest` 是 `MutableStateFlow`;`SourceChannel` 是 `MutableSharedFlow` 包装且 `postValue`/`setValue` 实现相同(都是 `tryEmit`)、无 main 断言;`PlayLoader.postPlayResult` 已包 `mainHandler.post`;`LiveProxyLoader` 已用 `handler.post` 回 UI。**仍需单独审的点**:`LiveProxyLoader.onSuccess` 的 `ApiConfig.get().loadLives(livesArray)` 是**全局状态写入** —— N3 已核定:协程恢复到 Main 再执行,与迁移前一致(§5 N3);`SortLoader` 的 3 处 `HomeRecCallback.done` 消费方 |
| R4 | 取消后行为漂移(E3):协程取消静默,但 okgo 的"静默"与 `CancellationException` 传播路径不同,业务 catch 若吞掉会卡 UI | 中 | R12 同防;真机走查快速切页/连点 |
| R5 | 超时重试丢失或次数错(E6):弱网成功率下降、超时耗时变化;写成"总 3 次"会比现状少一次尝试 | 中 | await 层复刻 **3 次重试 / 4 次尝试**(P5) |
| R6 | 头语义实现成 add(E7):用 `addHeader` 会把「显式 UA 覆盖全局 UA」变成「两个 UA 头」,凭空引入 okgo 从未有过的行为 | 中 | `header()`(替换)+ 单测锁定单头 |
| R7 | await 层静态缓存 client:reloadDns 后 DNS/代理不生效 | 中 | §4.3 约束 + 审查点 |
| R8 | 契约层误动(N4) | 中 | 只改内部实现 + `javap` 复核 |
| R9 | 全局 UA 与显式 UA 的覆盖关系被改错(E7):现状是**显式覆盖全局、只发 1 个 UA**,若实现成双头则站点风控行为不可观测 | 低 | 按 P6 + 修正后的 E7 实现,列入真机走查 |
| R10 | `runBlocking` 被引入冷启动路径(init / reloadDns / N4 桥接):主线程卡死或 ANR | 高 | 全库禁 runBlocking,grep 卡口进各片验收;N4 用阻塞式 `getSync` 而非桥接 |
| R11 | scope 归属错误:GlobalScope 泄漏 / SourceViewModel 永生 scope 未被宿主取消 → 请求打到已销毁页面 | 高 | §4.4 归属总则;片内审查点:每个 launch 的 scope 归属显式登记 |
| R12 | `catch (Exception)` 吞 `CancellationException`:协程取消失效(静默吞掉后继续跑)或状态错乱 | 高 | 调用点模板先 catch 再 rethrow(§4.1 示例);片内 grep `catch` 审查 |
| R13 | 超时层次破坏:SearchViewModel 现状 = 外层 30s 总超时 + okgo 内层 10s×3 重试,协程化后层次要保持(外层 withTimeoutOrNull 包住内层重试,不可互换) | 中 | 迁移时逐调用点登记超时层次;真机弱网走查 |
| R14 | E9 取消精度变化:tag 广播是全局取消(跨 SourceViewModel 实例连带),Job 是实例内精确 —— 连带取消消失后,原先被"误伤"取消的并发请求现在会跑完 | 低 | 方向更精确、属修复;真机验证切页/切源竞态(详情 fallback 流程 :608-614 重点) |

### 6.2 验收清单(N5 执行)

1. `grep -r "com\.lzy" app/src` = 0;`grep -r "cancelTag" app/src/main` = 0;
2. `.\gradlew :app:assembleDebug` + `.\gradlew :app:testDebugUnitTest` 全绿(用例数不低于基线);
3. `.\gradlew :app:dependencies` 无 `com.lzy.net`;
4. `:app:assembleDebug` 产物 dex 中无 `com/lzy/net`(debug 未混淆,可直接查;注:若日后 debug 开启 minify,此口径失效,需改用 mapping 反查);
5. `grep -r "runBlocking" app/src/main` = 0;`grep -r "GlobalScope" app/src/main` = 0;
6. 真机走查(用户执行):起播/换源、搜索建议(连续输入竞态)、详情加载与超时 fallback 切源、字幕加载、直播代理、m3u8 净化、弹幕、弱网超时场景(观察重试耗时与外层 30s 层次)、快速切页(取消链 R4/R11/R14)。

### 6.3 回滚

每片一个独立 commit(revert 即回);分片期 okgo 与 await 层并存,取消链按片闭环(§5),片间无断链窗口。N5 删依赖后如需回滚,按 N0-N4 的逆序 revert。

## 7. 决策记录

**已拍板(2026-10-06)**

| # | 决策 | 结论 |
|---|---|---|
| P1 | 路线 | 方案"await 层 + 调用点协程化"(原方案 B 解禁);原方案 A(忠实 shim)否决 |
| P2 | 取消机制 | **全 Job 化**,tag 通道一次性退役(§1.5/§4.4);不采用双通道过渡 |
| P3 | JS 契约层 | **不进协程世界**:公开签名保持阻塞式 + 签名守恒;协程化只发生在方法体内部(N4) |
| P4 | await 层命名与位置(原 D1) | `app/src/main/java/com/github/avbox/osc/util/net/Http.kt`,含 `Http`/`HttpRequest`/`HttpException`,`HttpPolicy`(纯策略)同包。理由:与 `catvod.net.OkHttp` / `okhttp3.OkHttp` / `HttpClients`(只管 client 配置)三者职责分离;`OkProxySelector`/`ProxyAuthenticator` 已在该包;同包便于 G5 单测 |
| P5 | 超时重试语义(原 D2) | 保留 okgo 现状并显式化:**重试上限 3 次 ⇒ 总尝试最多 4 次**;每轮重建 Call;仅 `java.net.SocketTimeoutException` 触发(`ConnectException`/`UnknownHostException` 不重试);`getSync` 不重试(§4.1 约束 4/5) |
| P6 | 全局 UA(原 D3) | 等价保留:await 层以**替换**语义注入全局 UA(值仍取 `"okhttp/" + OkHttp.VERSION`),显式 UA 覆盖它 —— 3 处站点请求维持**单** UA 头(§4.6) |
| P7 | okgo 版 `HttpLoggingInterceptor`(原 D4) | 4 处全删(:87/:255/:376/:417),不引入官方 `logging-interceptor`(§4.6) |
| P8 | N4 审查深度(原 D5) | 逐文件 `javap -p -s` **前后对比**,判据见 `avbox-kotlin-migration-spec.md` §3.2 |
| P9 | `SourceViewModel` scope(原 D7) | 内建 `requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`,按链各持 Job(detail/search/play),`cancelPlayRequest()` 不连带 preload,`playRequestSeq` 保留(§4.4) |
| P10 | 解析挪 IO(原 D8) | 全部 onSuccess 内解析统一 `withContext(Dispatchers.IO)`;`LiveProxyLoader` 的 `ApiConfig.loadLives` 全局写入单独处理 |
| P11 | 桥接方式(原 D9) | **不叠加**官方 `okhttp-coroutines`,自持 `suspendCancellableCoroutine` 桥接(约 15 行,先例 `AppBootstrap.kt:131,147`) |

**待拍板:无。** 原 D1-D5 / D7-D9 已于 2026-10-06 全部拍板(P4-P11);原 D6(分片顺序)已随 P2 落定,按取消链闭环重排(见 §5)。

**执行顺序**:N0 的三个阻塞项(D9/D1/D2 ⇒ **P11/P4/P5**)已全部解除,**N0 可立即开工**;P6/P7/P8 随 N5 前落实,P9/P10 随 N1 前落实。

## 8. 修订记录

| 日期 | 变更 |
|---|---|
| 2026-10-06 | 起草:现状盘点(19 文件)、okgo 源码取证 9 条(E1-E9)、方案与分片、决策点 D1-D6 |
| 2026-10-06 | 修订:① 路线切换为协程化(原方案 B 解禁,方案 A/C 否决,P1);② 取消机制全 Job 化(P2),新增 §1.4 范式普查、§1.5 tag 图谱、§4.4 取消架构,分片按取消链闭环重排;③ JS 契约层豁免口径细化:壳阻塞/芯换血(P3,§4.5);④ 修正 §0 依赖形态措辞(项目显式声明 okhttp 5.5.0,非"仲裁强拉")及代码块标注;⑤ E5/E9 语义变化显式登记,R10-R14 新增,验收口径补 cancelTag/runBlocking/GlobalScope 归零;⑥ N0 增补 `kotlinx-coroutines-android` 显式声明;⑦ 决策点重排:P1-P3 已决,D7/D8 新增,D6 并入 P2 |
| 2026-10-06 | 复核修订(逐条对照 okgo 3.0.4 sources jar + 全库实测计数):① **E7 纠正** —— `headers(k,v)` 是**替换**语义(`HttpHeaders.headersMap` 为 `LinkedHashMap<String,String>`,`put` 即覆盖),现状只发 **1 个** UA 头,await 层**禁用 `addHeader`**;连带修 §1.2 表、§4.1 代码注释、§4.2 行、R6/R9、D3、§4.6;② §0/§3/§4.1 纠正「无官方协程 API」—— 官方 `okhttp-coroutines`(5.5.0 已发布)存在,只做桥接、策略层仍需自建,新增 **D9**;③ okgo 版 `HttpLoggingInterceptor` ×3 → **×4**(:87/:255 挂在 `initExoOkHttpClient()`,删依赖前须一并处置);④ 计数校正:`HttpHeaders.setUserAgent` ×2、`import kotlinx.coroutines` 36 文件;⑤ E6 口径写明「重试 3 次 / 总尝试最多 4」;⑥ E8 登记 `URLEncoder` 值编码差异(空格 `+` vs `%20`);⑦ §4.1 澄清 `convertResponse` 两种写法与 okhttp5 `Response.body` 非空(守卫为死分支);⑧ §4.3 澄清 `getNoRedirectClient()` 在 N4 仍有调用点 |
| 2026-10-06 | 决策推荐 + 三处新增实证:① §4.4 **订正**:`playRequestSeq` **不可随 Job 化整体删除**(`PREPARE_POOL` 阶段的 `postPlayResult` :76/:81/:92 不经网络、不可取消,序号门必须保留给池阶段),并写明 `preloadRequestSeq` 与 play 链各自独立、`cancelPlayRequest()` 不得连带取消预载;序号/token 与 Job 的分工口径统一为「Job 管要不要继续跑,序号管结果属于哪一轮」;② §4.1 新增约束 5:`getSync` **不重试、不做 404/≥500 判定**(今天 4 处同步 `execute()` 走 `Request.java:382` 直连 okhttp、完全绕过 cache policy),重试细则(每轮重建 Call / 只认 `SocketTimeoutException`);③ §6.1 **R3 严重度下调**为「中」并给出实测缓解(sink 为 `MutableStateFlow` 与 `SourceChannel`(`MutableSharedFlow`, `postValue`/`setValue` 同为 `tryEmit`、无 main 断言)、`postPlayResult`/`LiveProxyLoader` 已 `mainHandler.post`),仍需单独审 `LiveProxyLoader` 的 `ApiConfig.loadLives` 全局写入;④ §7 填全 D1-D5/D7-D9 的推荐并给出执行顺序(N0 只被 D9/D1/D2 阻塞) |
| 2026-10-06 | **决策拍板**:原 D1-D5/D7-D9 全部拍板并转写为 **P4-P11**(§7 已拍板表),待拍板项清零。① **P4** await 层 = `util/net/Http.kt`;② **P5** 显式常量、重试 3 次 / 总尝试 4;③ **P6** 等价保留全局 UA(**替换**语义);④ **P7** 删 4 处 `HttpLoggingInterceptor`;⑤ **P8** N4 逐文件 `javap -p -s` 前后对比;⑥ **P9** `requestScope` 取 `Dispatchers.Main.immediate` + 按链 Job(不连带 preload、`playRequestSeq` 保留);⑦ **P10** 解析统一 `withContext(Dispatchers.IO)`;⑧ **P11** 不叠加 `okhttp-coroutines`、自持桥接。同步改动:§0/§3/§4.1/§4.2/§4.4/§4.6/§5/§6.1 内全部 `D*` 引用改写为 `P*`;§4.4 的 `requestScope` 示例由 `Dispatchers.IO` 改为 `Main.immediate` 并附理由 |
| 2026-10-06 | **N0 实施回填**:await 层 + 纯策略层落地(见 §5 N0 实测登记);① 实施发现 okgo 默认头除 UA 外还有 `Accept-Language`,新增 **E10** 并同形复刻(§1.3/§4.2;G1 的保持条目由七条改八条);② `kotlinx-coroutines-android` 显式声明 1.10.2,依赖图零变化;③ 单测新增 3 类 22 例(fake client 注入点为 `internal Http.executeWithRetry(request, client)`),总量 677 用例 0 失败 |
| 2026-10-06 | **N1 实施回填**:detail/search 链闭环落地(见 §5 N1 实测登记);① `siteGet` 转 suspend,PlayLoader 未迁调用点暂用 `siteGetRequest`(okgo 桥,N2 删除);② `SourceResultParser.xml/json` 随 `checkPush` 级联转 suspend,`xml/json` 的宽 catch 补 CancellationException 放行;③ `SourceViewModel.requestScope` 取 **lazy** 构造(JVM 单测直接 new,不能触碰 `Dispatchers.Main`);④ detail/search 取消入口 = 每链一个 `SupervisorJob` 作父 + cancel 后重建(cancel-all 且不连带其它链),暂不做「新请求取消旧请求」;⑤ `SearchViewModel:230` 的 search 取消随生产者提前收口(原排 N3);⑥ `SourceResultParserRoutingTest` 3 例包 `runBlocking`;总量 677 用例 0 失败 |
| 2026-10-06 | **N1 收尾审查修复**:独立只读复核 + 作者复核发现 4 条(3 修 1 留),见 §5 N1「收尾审查轮」;① `9e66e73` 响应体读取移出 Main(引 1 条锁定单测);② `e652d70` 恢复 push 详情 spider 分支 15s 硬上限(`withTimeout` 打断不了阻塞调用,改 `BoundedCall`)、修 `RemoteTVBox` 桥的永不 resume;③ 复跑 678 用例 0 失败;**结论:可以收尾**(最新一轮无阻断/高/中) |
| 2026-10-06 | **N1 第二轮审查修复**:换角度复核仅 3 条低危(全为本次引入、全当场修):① `81999ce` 重试范围收窄到请求阶段(读体超时不重试,回归 E4/E6;+1 单测);② `b675a09` 修 `RemoteTVBox` 桥 resume-inside-use 的二次 resume、`SubtitleViewModel.pagesTotal` 跨线程写的可见性;③ 另有 2 条既有低危登记不修;679 用例 0 失败。**两轮后收敛:本片可收尾** |
| 2026-10-06 | **N2 实施回填**:`player` 簇 + `PlayLoader` 三链闭环(见 §5 N2 实测登记);① `M3u8PurifyUseCase` 两跳并一协程 + 单 Job `cancel()`;② `PlayUrlResolver` `json_jx` 自持 Job、`stopParse()` 4 连取消改显式取消(需 `Host` 新增 `cancelPlayRequest()`/`cancelM3u8Purify()`,`PlaybackViewBridge`/`PlayerControlApi` 各加 `cancelM3u8Purify()` 接线到 useCase);③ `PlayLoader` 回调体改协程、`cancelPlayRequest()` = seq 自增 + play 链 cancel/重建(preload 链独立不连带);④ 删 `SourceHelper.siteGetRequest` 桥与随之失效的 `ERR_NETWORK`;⑤ 片内 `com.lzy` 归零、`cancelTag` 余 2(全属 N3/N4);679 用例 0 失败;⑥ 新增行为差异登记一条(stopParse 的 play 取消比 `cancelTag` 多"池阶段/已投递结果"两处作用面) |
| 2026-10-06 | **N2 收尾审查修复**(两轮独立只读复核 + 作者复核,见 §5 N2「收尾审查轮」):① 中,m3u8 净化协程**自取消**(回调链经 `goPlayUrl → stopParse`)→ `deliver{}` 发回调前摘除本轮取消面;② 中,`M3u8.currentAdCount` 跨轮竞态 + 实例锁不覆盖双页实例 → 净化锁与取消槽上收为 **companion 进程级**,`purify + 读数`同临界区;③ 中,取消通道绑 view bridge 致 handover 孤儿页不可取消 → 取消改**进程级** `M3u8PurifyUseCase.cancelActive()`,`PlaybackViewBridge`/`PlayerControlApi` 的 `cancelM3u8Purify` 及实现**回退**(接口面回原状);④ 低,补 `PlayLoader.cancelPlayRequest` 回归用例(3 种变异必失败);⑤ 低判定不成立:okgo `HttpHeaders.put` 实测只跳 null(§1.3 E10 措辞修正);⑥ 680 用例 0 失败 |
| 2026-10-06 | **N3 实施回填**:UI / 杂项簇落地(见 §5 N3 实测登记);① `SearchViewModel` suggest 链挂 `viewModelScope`(删 `suggestSeq` → `suggestJob`,新输入取消旧请求),`fetchHotSearch` 协程直发,`onCleared` 补 `searchCaller.cancelSearch()`(收 N1 遗留)、删 `cancelTag("suggest")`;② `LiveProxyLoader` 换协程(实例级 `loadScope` lazy),`cancelAll()` 补 `cancelChildren()`(首次真正取消在飞请求),`loadLives` 保持 Main;③ `SubtitleLoader` `loadFromRemote` 转 suspend + `Http.getRaw`,`loadFromRemoteAsync` 协程化,删无调用者同步重载;④ `MusicLrc` `read`/`fetch` 转 suspend + `Http.getRaw`(保留 `UniversalDetector` 字节检测);⑤ **await 层补 `getRaw`**(原始字节 + headers,§4.1 约束 2 / §4.2 E1 已同步改写);⑥ `MusicPlayerActivity.syncLyric` 的 `runCatching` 改 try/catch 放行取消(R12,范围 4+1 文件);⑦ 卡口:片内 `com.lzy` 归零、全库 `cancelTag` 余 1(N4)、`suggestSeq` 0、行尾全 LF,683 用例 0 失败 |
| 2026-10-06 | **N3 收尾审查轮**(独立只读复核 + 作者复核,见 §5 N3「收尾审查轮」):无阻断/高/中;① 登记口径更正——`SubtitleLoader` 无取消入口(取消联动仅 `MusicLrc`),重试面使其最坏 4 次尝试后才被丢弃;② 补登记"enqueue 排队面"与"取消后已 deliver 的 `Response` 不 close"(N0 桥接既有细节,官方桥接同形);③ 判定不成立一条(`LiveProxyLoader` 投递边界经 `56be128^` 核证逐字未变);④ `HttpTest` +3 例(≥500 / 重试总 4 次 / 空 body);⑤ 686 用例 0 失败,**本片可收尾** |
| 2026-10-06 | **N4 实施回填**:`catvod` 契约层三文件内部换血、签名守恒(见 §5 N4 实测登记);① `JsLoader`/`JarLoader` 同步下载 `OkGo.execute()` → `Http.getSync`(E1 直连,流语义/中断检查逐字保留);② `Connect.cancelByTag` 删 `OkGo` 通道(经 3.0.4 源码核证与 `cancelDefaultClient` 完全同面、纯去重),自实现遍历与 client 取用不变;③ P8 落地:`javap -p -s` 6 类 219 行前后逐行一致;④ `Http.getSync` 补 internal 注入重载 + `HttpTest` 2 例锁 E1(404 不判定 / 不重试);⑤ 卡口:`com.lzy` 余 1(HttpClients → N5)、`cancelTag` 全库 0;688 用例 0 失败 |
| 2026-10-06 | **N4 收尾审查轮**(独立只读复核 + 作者复核,见 §5 N4「收尾审查轮」):无阻断/高/中;6 条低危(1 覆盖缺口 + 5 既有/口味差异)全部登记不改;作者侧补齐独立复核的验证边界(okgo `Request.getRawCall/execute` 源码逐点同形核证、`NoBodyRequest`/`HttpUtils.appendHeaders` 抽验、全库无 okgo 额外全局加工、javap 219 行实测签署);**首轮即收敛,本片可收尾** |
| 2026-10-06 | **N5 收尾回填**:OkGo 彻底移除(见 §5 N5 实测登记);① `HttpClients` 去 okgo 化五项落地(`setOkHttpClient`×2 / `setUserAgent`×2 删除、`UnSafeHostnameVerifier` → `hostnameVerifier { _, _ -> true }`、`HttpLoggingInterceptor`×4 删除、`reloadDns()` 联动保留),连带清 6 条失效 import;② 删 `libs.versions.toml` 两条目 + `app/build.gradle.kts` 依赖;③ §6.2 验收全过:`app/src` 卡口全 0、依赖图无 `com.lzy.net`、APK 29 dex 0 命中、688 用例 0 失败;④ 新增依赖裁剪风险登记(thunder.jar 扫描 0 命中 / fongmi 生态 0 okgo / 上游 50 处均宿主代码;残余=野路子 jar 直接 import,真机走查兜底);⑤ 终态口径核验通过(§0 三条全满足),**N0–N5 六片全部完成(待真机走查)** |
