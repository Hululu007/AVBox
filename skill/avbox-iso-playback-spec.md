# AVBox ISO 播放 Spec:蓝光原盘 ISO / 裸 m2ts 在 media3 上的落地(as-built)

> 项目:AVBox(TVBox OSC fork;仓库根目录 = 本文件所在目录的上一级)。
> 配套:先读 `SKILL.md`(通用规范 + 文档地图)与 `avbox-playback-service-spec.md`(播放器归属/挂摘,本文只讲**取流与抽取链路**)。
> 状态:**as-built 活规范(2026-10-20 重写)**。链路已落地并真机验证:UDF → mpls/m2ts → 192→188 → `TsExtractor` + 自定义 reader;多段盘拼接、RAP 起播、4K 单层/双层盘、ISO9660+UDF 混合盘、裸 192B BDAV 都跑通。**唯一硬边界是 4K Main10 的模拟器解码能力**,不是选片或解析。
> ⚠️ **改动 `engine/iso/**`、`MediaSources` 的 ISO 分支、`PlayerEngine` 的 isoSession 语义、或任何 `TrackOutput`/`ExtractorOutput` 包装层前必读**;`§4` 是不变量(违反即回归),`§5` 是已知坑,`§7` 是走查清单(**编号 1–12 与节号 §7 保持稳定**,外部文档按「§7 清单」引用)。
> 过程与取证**不写在这里**:案例、缺陷解剖、判负实验、性能实测全在 `history/iso-playback-chronicle.md`(下文引用写作「档案 §14.13」)。

## 0. 摘要与范围

**一句话**:不开 native、不 fork media3 —— 在 `DataSource` 层把 ISO 里的 `BDMV/STREAM/*.m2ts` 当作一个可随机读的流(192 字节 ATS 剥成 188),再交给 media3 的 `TsExtractor`,只替换「BD 私有 `stream_type` 的 reader」与「主视频 PID 的选择」两处。

**链路**:

```
MediaSources.createIsoMediaSource(uri, headers, seekMs)
  └── IsoDataSource(HTTP Range + UDF 只读)
        └── M2tsDataSource(逻辑 188 ↔ 物理 192,支持多 run)
              └── ProgressiveMediaSource(IsoExtractorsFactory)
                    └── PrimaryVideoExtractor(主视频 PID 过滤 + 延迟声明门)
                          └── TsExtractor(MODE_MULTI_PMT, extractorFlags=0)
                                └── BdTsPayloadReaderFactory(LPCM / TrueHD core / AC-3 / E-AC3 / DTS-HD / PGS)
                                      └── 自定义 reader:LpcmReader / Ac3CoreReader / PgsReader
```

**不变项(动它们等于动架构)**:

- 只在 **DataSource / Extractor 层**扩展,**不 fork media3、不加 native 解码器、不改内核**;
- ISO 会话由 `PlayerEngine.isoSession` 标记,起播窗口 45s、seek 走**重建 MediaSource**(不是解码器复用);
- 轨道语言来自 **mpls 的 STN 表**,不从 PMT 读;主视频轨来自 STN 的 `videoCount` 区段;
- 选片是**启发式**(去重时长最长),没有 BD-J 菜单解析、没有用户选播放列表;
- 4K Main10 解不动是**设备边界**,按失败如实上报,不做"软解兜底"。

**非目标**:杜比视界完整效果(RPU 不解,按 HDR10 播)、TrueHD 无损(只取 AC-3 core)、BD-J 菜单、虚拟分区/可擦写盘 UDF、外挂字幕。

## 1. 技术基线与边界

| 项 | 值 |
|---|---|
| media3 | **1.11.1**(`TsExtractor` 6 参构造;`DefaultSubtitleParserFactory` 官方字幕转码) |
| App | `com.github.avbox.osc`,versionName 1.2.8,minSdk 24 / targetSdk 37,APK 只带 arm64-v8a |
| 抽取模式 | `TsExtractor.MODE_MULTI_PMT` + `extractorFlags = 0` + `TimestampAdjuster(0)` + `DEFAULT_TIMESTAMP_SEARCH_BYTES × 10` |
| 起播窗口 | ISO 会话 `ISO_SWITCH_LINE_PLAY_TIMEOUT_MS = 45s`(非 ISO 仍 20s) |
| 吞吐守卫 | 门槛 = 平均码率(188 口径)× 115%;`measured <= 0` 一律不判 |
| 网盘 | Quark 直链经本地代理(日志里的 `127.0.0.1:5266`);VIP 实测 7.4–17.9MB/s,盘需求 3.6–11.8MB/s |
| KV 开关 | `player_iso_enabled`(默认 **false**)、`player_iso_lpcm` / `player_iso_truehd_core` / `player_iso_pgs`(默认 true) |

**设备解码边界(必须如实写)**:

- 模拟器只提供 `OMX.google.hevc.decoder`(**ProfileMain / 最大 4096×4096**)⇒ 4K Main10 盘必然 `NO_EXCEEDS_CAPABILITIES`,**这是真报不是误报**;
- 真机硬解(`c2.qti.hevc.decoder` 一类)通常能解 4K Main10,**未验证**;
- 播放配置里的「软解码」(`preferSoft=true`)会强制走软解,4K 同样撞这条线;「软解超能力时自动回退硬解」**未实现、待拍板**。

## 2. 配置与入口判定

| 判定点 | 位置 | 规则 |
|---|---|---|
| 走不走 ISO 通道 | `MediaSources.getMediaSource` | 请求头带 `TVBox-Iso`(`isoHeaders`)或 `contentType == TYPE_ISO` |
| 内容类型推断 | `MediaSources.inferContentType` / `PlayerEngine` | `info.format` 或 URL 后缀 `.iso`;`.m2ts` 走 BDAV 探测 |
| ISO 会话标记 | `PlayerEngine.setDataSource` | ISO 命中后跳过磁盘缓存/预载,打 `echo-iso-play-skip-cache` |
| 裸 192B BDAV | `BdavDataSource` + `retryAsBdav` | 首轮解析失败 → 64KB 结构判据(192 对齐 + PMT/PES)→ 命中即 `IsoSessionSignal.markEscalated()` |
| 音乐页拦截 | `MusicPlayerActivity.isIsoSession/bailIsoSession` | ISO/bdav 内容**禁止**进音乐页(打 `echo-iso-music-bail`,Toast 后回详情页) |
| 45s 窗口续期 | `PlaybackRetryDelegate` | 迟到 ISO 会话(`IsoSessionSignal.escalated()`)首次超时把窗口重算 45s |
| 源能力闸门 | `IsoResolver.readProbe` | `Accept-Ranges: none` ⇒ `markStub` + `range unsupported`,不进入解析(UI 走「该线路返回的资源不完整」);其余只记 `mode=206/200`(§4-C14) |

## 3. 模块地图(as-built)

包 `app/src/main/java/com/github/avbox/osc/player/engine/iso/`:

| 文件 | 职责 | 维护要点 |
|---|---|---|
| `UdfVolume.kt` | 卷识别 / 分区映射 / 目录遍历 / 读文件 | 卷识别按 ECMA-167 扫**描述符序列**;`physical`/`metadata` 两种映射;目录项 FE **必须串行读**(并发实测判负,§13.13) |
| `UdfParser.kt` / `UdfFormat.kt` | File Entry、`short_ad`/`long_ad`、`decodeDString` | 派生缓存带**解析器版本** |
| `IsoLocator.kt` | 定位 `BDMV/STREAM`、选 m2ts、合并相邻 run、批量读+解析 mpls | 碎片文件多 run;mpls 走 `IsoMplsFetch`(并发 4 + 12s 整体超时,**读与解析在同一个任务里**,失败退化为串行重试一次);内联条目与单条 mpls 仍走调用线程;BDMV 允许套**一层**包装目录(`bdmvEntry` 懒定位,`locateSelection` 与 `loadClipIndex` 共用) |
| `MplPlaylist.kt` | mpls `STN_table` + play item 解析、选片、流去重 | 选片按**去重时长**;语言在 STN |
| `IsoDataSource.kt` | ISO 层 DataSource(HTTP Range 之上)+ 探测与解析编排 | 会话开始时 `IsoThroughput.beginSession()` |
| `M2tsDataSource.kt` | 192→188 剥 ATS、逻辑↔物理映射、多 run | 属性测试覆盖(`M2tsDataSourceTest`) |
| `IsoExtractorsFactory.kt` | 组装 `TsExtractor` + reader 工厂 + `PrimaryVideoExtractor` | 6 参构造;`extractorFlags=0` |
| `BdTsPayloadReaderFactory.kt` | BD 私有 `stream_type` → reader 映射 + **临时 Format 注入** | 见 §4-C4/C5、§5 的 #1–#3 与 #5 |
| `ProvisionalFormatReader.kt` | 在 `createTracks` 时为音频轨先发一条猜测 Format | 真实 Format 到达自动覆盖 |
| `PrimaryVideoExtractor.kt` | 主视频 PID 过滤 + **延迟声明门** | 见 §4-C1..C3 |
| `LpcmReader.kt` | LPCM 4B 头解析、声道重排、24→16bit | 样本必带 `BUFFER_FLAG_KEY_FRAME` |
| `Ac3CoreReader.kt` | `0x83` TrueHD 只取 PES 起始的 AC-3 core | 不能直连 `Ac3Reader`(假同步会崩) |
| `DtsCoreReader.kt` | `0x85`/`0x86` 只取 DTS core 帧,丢掉 EXTSS | mime 永远是 `audio/vnd.dts`;见 §9-11 |
| `PgsReader.kt` | PGS 按 END 拼完整 display set 再产 sample | `createTracks` 立刻发 Format |
| `LanguageOverrideReader.kt` | 按 PID 覆盖语言 | 语言来源是 STN |
| `IsoPlaylistState.kt` | 跨阶段共享:mpls 选中结果 / 片段表 / 主视频 PID / 语言 | `reset()` 必须覆盖全部字段;**语言表由 `IsoResolver.resolve()` 每次重发**,别只在 locate 里发(§8-D11) |
| `IsoSeekRebuild.kt` | seek 落点重建 MediaSource / RAP 起播判定 | 纯判定 `IsoSeekAction`,带单测(`IsoSeekRapTest`/`IsoSeekRebuildTest`) |
| `IsoThroughput.kt` / `IsoBandwidthGuard.kt` | 4s 窗口吞吐 / 带宽判定 | 见 §1 |
| `ClpiIndex.kt` | `*.clpi` RAP 索引 | seek/RAP 起播用 |
| `BdavDataSource.kt` | 裸 192B BDAV 探测 + 复用 `M2tsStream` | stub <1MB 快速失败 |
| `IsoSessionSignal.kt` | 引擎 → 控制器的迟到会话信号 | 一次性 |
| `IsoFileSource.kt` / `IsoResolver.kt` / `IsoSettings.kt` / `RangeBlockReader.kt` | 源解析 / 开关 / 测试接缝 | `RangeBlockReader` 是主要测试替身;probe 的镜像总长只认 `Content-Range`(C14) |

接线点(ISO 相关):

- `player/engine/MediaSources.kt`:`createIsoMediaSource`(多段时 `ConcatenatingMediaSource` 追加)、`bdavMediaSource`、`isIsoUri`/`isoHeaders`/`inferContentType`;
- `player/engine/PlayerEngine.kt`:`isoSession` 标记、`prepare()` 里的 RAP 起播、`seekTo` 三态(`ALLOW`/`BLOCK`/`REBUILD`)、`retryAsBdav`;
- `player/PlaybackRetryDelegate.kt`:带宽三处入口(中段/耗尽/超时探测)+ 45s 窗口续期;
- `ui/DetailActivity.kt` / `MusicPlayerActivity.kt`:ISO 内容禁进音乐页(判定必须限定"引擎装的就是本页内容")。

## 4. 不变量(违反即回归)

> 这一节是本文最需要维护的部分。每条都对应一次真实故障,证据在档案里。

**C1 — 声明过的轨必须拿到 Format,否则 `prepare` 永远不完成。**
media3 `ProgressiveMediaPeriod.maybeFinishPrepare()` 要求**每条已声明轨**的 `SampleQueue.getUpstreamFormat() != null`;少一条就 return,播放停在 `BUFFERING`,最后被 `ExoPlayerImplInternal.PLAYBACK_STUCK_AFTER_MS = 4000`(**硬编码,无 API**)掐死。因此:**不得声明一条可能永远没有 Format 的轨**。手段只有两个 —— 临时 Format(`ProvisionalFormatReader`)或延迟声明门(`PrimaryVideoExtractor`)。

**C2 — 包装 `TrackOutput` 时,数据一个字节都不能扣。**
`H264Reader.consume()` 在发 Format **之前**就 `output.sampleData(data, data.bytesLeft())`,并用自己累计的 `totalBytesWritten` 计算后续 `sampleMetadata` 的绝对位置(`absoluteOffset = totalBytesWritten - size - offset`)。扣掉任何一段字节都会让位置记账错位,表现为 `IndexOutOfBoundsException: off=... out of bounds`。可以延迟**声明**,不能扣**数据**。

**C3 — `endTracks()` = 声明窗口关闭,想延迟声明就必须扣住它。**
`TsExtractor` 在 PMT 解析完就调 `output.endTracks()`;之后 `ProgressiveMediaPeriod.prepareTrackOutput()` 对新 TrackId 直接返回 `DiscardingTrackOutput`(只打一条 `Extractor added new track ... after finishing tracks.`)。所以"字面懒声明"= 全轨丢弃。

**C4 — BD 的 `stream_type` 与 MPEG-TS 撞号,必须自己映射。**

| `stream_type` | 真身 | media3 默认行为 |
|---|---|---|
| `0x80` | LPCM | 当 DC2/H.262 |
| `0x82` | DTS | 需 `FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS` |
| `0x83` | TrueHD | **不认**(无 reader) |
| `0x84` | E-AC-3 | 与 DTS-HD 冲突 |
| `0x85` | DTS-HD HRA | 丢轨 |
| `0x86` | DTS-HD MA | 当 SCTE-35 ⇒ **崩抽取器** |
| `0x90` | PGS | 未注册 |
| `0x24`/`0x1b`/`0x02` | HEVC / AVC / MPEG-2 | 走默认 |

**C5 — 语言只在 mpls 的 `STN_table`。**
音频语言在 `attrStart+2`、PG 在 `attrStart+1`;PMT 里没有。读错会得到 `0jp` 这种垃圾值。`IsoPlaylistState.publishStreams` 是唯一来源,`LanguageOverrideReader` 按 PID 覆盖。

**C6 — 只保留 STN 指定的主视频轨。**
DV 双层盘的 PMT 同时有主层(3840×2160)和增强层(1920×1088),`PrimaryVideoExtractor` 按 `STN_table` 的 `videoCount` 区段丢后者。安全闸:**主 PID 已声明后**才丢;拿不到主 PID 时全留(绝不出现"零视频轨")。

**C7 — 选片按「去重时长」,时间轴用「声明时长」。**
同一 `(clip, in, out)` 只计一次 ⇒ `uniqueDurationMs`;`pickLongestPlaylist` 用它排序,`IsoPlaylist.durationMs` 保持原值进时间轴。两个数不等就是重复段诱饵。

**C8 — 轨道表按 `(pid, language)` 去重。**
重复段会把同一条音轨加 N 次,脏表会污染语言标注与音轨菜单。


**C10 — UDF 卷识别是「扇区序列」,不是 sector 16 的 magic。**
`CD001`(ISO9660 主/补充/结束符)是合法成员 → 跳过;`BEA01` 空操作;`NSR02`/`NSR03` 记版本;`TEA01` 或全零扇区结束;扫满 16 个扇区仍无版本才报错。

**C11 — 一个 m2ts 可以有多个物理 run。**
`long_ad`/`short_ad` 拼出的 extent 首尾相接要合并成一个 run;跨 run 读取不能假设物理连续。

**C12 — 新增日志前缀必须进 `AppLog.FILE_LOG_PREFIXES`。**
现有 `echo-iso` 前缀已覆盖全部 ISO 日志(`AppLog.kt:20`)。代码**零注释**;说明写进本文或档案。

**C13 — 解码失败必须分清是音频轨还是视频轨。**
`ERROR_CODE_DECOD…` 只说明"某个渲染器解码失败",不含轨类型。把它一律当"视频硬解失败"会:①弹错误的提示(`player_decode_fallback_tip`);②把**视频**切成软解(4K 反而更糟);③掩盖真正问题(2026-10-20 的实例是**音频** `FfmpegAudioRenderer` 解不了 `audio/vnd.dts.hd`)。判据:失败音频轨→只换音轨(`echo-autoRetry audio track fallback:<pid>` + `echo-iso-audio-skip:<pid>`),视频解码设置一个字节都不动。

**C14 — `DataSource.open()` 返回的是「本次可读字节数」,不是镜像总长。**
镜像总长只能从 `Content-Range: bytes a-b/total` 的 `total` 取;只有 `200 + Content-Length = 完整长度` 时,`open()` 的返回值才等于总长。分块代理(实测某网盘代理对**不带 Range** 的请求也回 `206`,body 只给 1024 字节)会让这个值缩小三个数量级,直接撞 `UdfVolume` 的 64KB 闸门,表现为 `echo-iso-error:image too small`。判据:`echo-iso-probe` 里 `open != total`。见档案 §14.14。

## 5. 已知坑与判据

> 每条:现象 → 根因 → 判据。完整解剖见档案对应节。

| # | 现象 | 根因(一句话) | 判据 / 日志 | 档案 |
|---|---|---|---|---|
| 1 | 4 秒 `StuckPlayerException` | 声明了永远没有 Format 的音轨(C1) | `echo-iso-track-defer` 后出现 `track-drop`;正常盘不该有 | §14.13 |
| 2 | `IndexOutOfBoundsException: off=-165...` | 包装层扣了 Format 之前的数据(C2) | 任何 `off=` 负值 | §14.13 |
| 3 | 全片无声、音轨菜单只剩一条 | 用时间窗丢轨,误伤"晚到但真实"的主音轨 | `track-drop:<pid>` 紧跟 `track-late:<pid>` | §14.13 |
| 4 | 起不来,`physical discontinuity` | 碎片 m2ts 多 run 未合并(C11) | `echo-iso-target-runs` 里 `n>1` | §14.1 |
| 5 | 静默丢轨 / 抽取器崩 | `stream_type` 撞号(C4) | `echo-iso-dts-hd`,或零音轨 | §14.2 |
| 6 | 音量/语言标错、字幕语言怪 | 语言读 PMT 而非 STN(C5) | `echo-iso-streams` 出现 `0jp` 类 | §14.3 |
| 7 | 字幕轨名显示 `APPLICATION/PGS` | 名字取错了字段 | `echo-setTrack ... codecs=` 才是源格式 | §14.4 |
| 8 | PGS 横向拉伸 1.25× | 字幕层没贴视频矩形 | `echo-render-rect` | §14.5 |
| 9 | 302 段正片被判 `segment-missing` | UDF 目录 FID 跨逻辑块,目录被截断 | `echo-iso-m2ts:count` 与 `echo-iso-mpls:count` 同时偏小 | §14.6 |
| 11 | 播到增强层(1080p 残差)、灰块 | DV EL 被当视频轨(C6) | `echo-iso-video-skip`;解码 Format 的尺寸 | §14.10 |
| 12 | 时长 3:51、每 55 秒跳开头、无字幕无 TrueHD | 重复段诱饵骗过 `maxBy(duration)`(C7) | `echo-iso-mpls` 里 `duration != unique` | §14.11 |
| 25 | 有声音、进度条正常、**画面全黑**,`mime=video/mpeg2 count=0` | 选中**诱饵 mpls**(`items` 很多、`duration` 远大于 `unique`,循环重复),指向 MPEG-2 菜单素材,设备无该解码器 | `echo-iso-mpls` 的 `chosen` 是 `duration` 远大于 `unique` 的那个;**修复后 `chosen` 应为 `duration == unique` 的 mpls** | §14.16 |
| 26 | mpls 数 ≥200 的盘,按文件大小取 top N 会**漏掉正片** | **单段长片的系统性盲区**:正片 `items=1`、文件极小,按大小排序必然排在最后;而诱饵 `items` 多、文件大,稳居第一。`pickLongestPlaylist` 虽按 `uniqueDurationMs` 选,但候选集里没有正片就无从选起 | `echo-iso-mpls:count=264:scanned=8` 且 `chosen` 的 `items` 很大、`unique` 很小;**修复后应见 `scanned=10` 且 `chosen=00800/00801.mpls`** | §14.16 |
| 13 | 打开就报 `volume recognition missing` | 混合盘 VRS 不在 sector 16(C10) | 无 `echo-iso-udf-vrs` | §14.12 |
| 14 | 黑屏无声、进度条正常 | 诱饵指向 MPEG-2 菜单素材,设备无 MPEG-2 解码器,media3 不报错 | `echo-exo-selector: mime=video/mpeg2 count=0` | 档案 §4.6 |
| 15 | 4K 盘 `NO_EXCEEDS_CAPABILITIES` | 模拟器只到 ProfileMain,不是误报 | `echo-exo-codec-init: kind=SOFTWARE` + Format 尺寸 | §14.8 |
| 16 | 弹「硬解失败,已切换软解」但视频明明是硬解 | 音频轨解码失败被当成视频硬解失败(C13) | `echo-exo-player-error` 的 `msg=FfmpegAudioRenderer error` + `format=…audio/vnd.dts.hd` | §13.7 |
| 17 | 42GB 盘 0.7s 就报「视频播放出错」,日志只有 `image too small` | probe 把 `open()` 返回的 1024 当成镜像总长(C14) | `echo-iso-probe:open=1024:total=45098467328`;正常盘 `open == total` | §14.14 |
| 18 | 一直缓冲(约 20s)后报「视频播放出错」,日志只有 `BDMV directory missing` | 压片组把 BDMV 连同海报塞进片名文件夹再打包,BDMV 不在 ISO 根下 | `echo-iso-root:count=…` 里第一项是个目录、没有 `BDMV`;修复后出现 `echo-iso-bdmv:nested=<dir>` | §14.15 |
| 19 | 起播后几十秒内弹「网盘限速,带宽不足」,`unexpected end of stream`,卡在某个固定 position 反复 `recover` | **连接不复用**:`IsoMplsFetch` 4 线程并发抓 202 个 mpls,每请求一短连接、OkHttp 池里无空闲可复用(实测 **1.34 请求/连接**),两秒建 402 个连接,被上游判定异常流量限速到 1MB/s。**注意开放结尾 `bytes=X-` 会反过来破坏复用**(读到 window 末尾就 close,响应体没读完 ⇒ 连接被丢弃),必须用固定长度 | 抓包统计 `dst port 5266` 的 **SYN 数 ÷ Range 数**,比值≈1 即不复用;按秒看 SYN:正常 1~2/秒、起播瞬间数百/秒;`echo-iso-bps` 从 5MB/s 衰减到 1.0~1.4MB/s;`bandwidth-mid` 实测≈1048576;`echo-iso-clpi-prefetch:n/m` 里 `m<n`;**修复后应出现 `echo-iso-eof:<idx>:<windowRemaining>:<bytesRemaining>`(截断被吞)且 `echo-exo-player-error` 消失** | §14.16 |
| 24 | 多段盘播到某段末尾时连续 `echo-iso-truncated`,换连接重试全败,最终 `Source error` | **请求范围正好停在 segment 末尾**:`m2tsReadLength` 返回段内剩余,`span` 被截成零头(如 152704),`isoReadWindows` 又把 `available` 限制在段内 ⇒ 请求永远"正好到段尾",上游对该形态返回短响应。fongmi 用固定 4MB 块**永远不停在段尾**,所以不受影响 | `echo-iso-span` 的 `spanLen` 等于段内零头而非 `M2TS_MAX_READ`;**修复后 `spanLen` 应为 4283544(4MB)、`availableLength` 为整条 playlist 总长** | §14.16 |
| 20 | 画面冻结在固定 position、进度条继续走,`open` 的 position 每次只前进固定差值 | **`DataSource.open()` 的返回值是「本数据源有多长」,不是「本次请求多少」**:把 `open` 返回值截断成窗口大小后,media3 认为整段只有那么大,读满即 `END_OF_INPUT` 并判定该段播完 | `echo-iso-open` 的 position 恒为 0 或每次只前进 `窗口 − 常量`;`rawPosition` 停住不动,最终 `StuckPlayerException: no progress` | §14.16 |
| 21 | 给流式读取加了容错但完全不生效(日志里容错分支零命中) | **「返回 -1」与「抛 IOException」是两条路径**:`unexpected end of stream` 是 `ProtocolException` 从 `read()` 抛出,不会返回 -1;只挂在 `read <= 0` 上的重连逻辑等于没接 | 加了 `echo-iso-recover` 却 0 命中,同时 `echo-exo-player-error` 照旧出现 | §14.16 |
| 22 | 以为「按 HDMV 导航只读 1 个 mpls」能解决连接风暴,实测对该盘无效 | **该盘是 BD-J 盘**:`index.bdmv` 里 first_play/top_menu 的 `object_type=2`(BD-J),主片选择在 Java 菜单里,静态解析拿不到 ⇒ 任何 HDMV 快路径都会回落全量扫描 | 读 `index.bdmv`:偏移 8 = IndexesStartAddress、偏移 40 = AppInfo length(应为 34)、偏移 78 = index_len,其后的 `object_type` 2 bit 为 2 即 BD-J;样本见 `源码/bdmv/` | §14.16 |
| 23 | 起播后固定时刻卡顿(13s / 35s / 20s)、进度滚回 0,`echo-iso-eof` 的 `windowRemaining` 与 `bytesRemaining` 相等 | **后台任务抢带宽**:快照构建读 2.1MB metadata + clpi 预取 81 个文件,与播放抢连接。**带宽余量为负时,任何后台 IO 都会拖死播放;调执行时机只是把卡顿往后推**。带宽门控也防不住 —— 它判**平均**带宽,而快照是**瞬时抢占** | `echo-iso-eof:0:<N>:<N>` 两个值相同 ⇒ 新连接刚建好就 EOF、一字节未读;卡顿时刻与当时的 `IsoPrepare.DELAY_MS` 吻合。**2026-10-23 已彻底移除该后台任务**(`IsoDiskCache`/`IsoPrepare` 连同快照代码全删,clpi 改为 `accessPoints` 按需读),故 `echo-iso-cache-*`/`echo-iso-clpi-prefetch`/`echo-iso-prepare-*` 均已不存在 | §14.16 |

## 6. 运维:拉日志 / 复现

```bash
export MSYS_NO_PATHCONV=1
ADB="/c/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe"

"$ADB" devices
"$ADB" -s 127.0.0.1:16384 exec-out run-as com.github.avbox.osc cat files/preload_debug.log > cur.log
"$ADB" -s 127.0.0.1:16384 logcat -d -v time > logcat.txt
"$ADB" -s 127.0.0.1:16384 install -r app/build/outputs/apk/debug/AVBox_debug.apk
"$ADB" -s 127.0.0.1:16384 forward tcp:15266 tcp:5266
```

- 应用日志是**前缀白名单**(`AppLog.FILE_LOG_PREFIXES`),不在此列的一律丢弃;长 URL 用 `awk 'length($0)>200'` 截断再看。
- 日志文件名 `files/preload_debug.log`,单线程追加,不丢行;**8MB 轮转**:超过就改名成 `preload_debug.log.1`(旧的 `.1` 覆盖),要完整历史两个文件都得拉。
- 落盘只在 debug(`AppLog.FILE_LOG = BuildConfig.DEBUG`);release 只走 `Log.i`,不碰磁盘。
- 频次基线(实测):整份日志平均 **4.9 行/秒**;`echo-progress-tick` / `echo-bar-draw` 各 **1/s**(后者仅在进度变化时打);ISO 相关最密的是 `echo-iso-seek`(每次 DataSource 重开一条)与每 10s 一条的 `echo-iso-bps`。**没有 per-sample / per-frame 日志**,不要往热路径加。
- 离线复现:把真实盘切片交给 `IsoSliceHarnessTest`(环境变量 `ISO_SLICE=<192B 切片文件>`),它只做诊断、不断言;`PgsParseProbeTest` 用 `PGS_SETS`。
- 取证顺序:**先 `Range` 直读盘头**(sector 16 起的卷识别序列 / PMT / STN),再与应用日志对照。应用日志单独看会把"我们的解析 bug"误判成"盘有问题"(档案 §14.6/§14.7)。

**日志速查(ISO 相关,按出现顺序)**

| 标记 | 含义 |
|---|---|
| `echo-iso-play: format=...` / `echo-iso-play-skip-cache` | 命中 ISO 分流 / 跳过磁盘缓存 |
| `echo-iso-udf-vrs:NSR02\|NSR03` → `echo-iso-udf-avdp/lvd/fsd` → `echo-iso-udf-ok` | UDF 各阶段;`NSR02` = UDF 1.02(混合盘常态,不是异常) |
| `echo-iso-audio-format:<id>:<mime>:ch=` | 实际选中并送解码的音轨格式(每条变更一次),音频类问题先看它 |
| `echo-iso-audio-skip:<pid>` | 该音轨被拉黑后不再声明(`IsoAudioFallback`,音频解码失败后自动切换的落地) |
| `echo-iso-m2ts:count/…:chosen` | 盘内 m2ts 与选中项(多段盘判据) |
| `echo-iso-mpls:…:chosen=…:duration=…:unique=…:top=…` | 播放列表盘点;`duration != unique` = 重复段;末尾 `:fetch=<ms>` 是这批 mpls 的读+解析耗时(串行,1 连接) |
| `echo-iso-mpls-prefetch:<windows>:<bytes>:<payload>:<ok>:<reason>` | mpls 聚合预取:把待读 mpls 的 extent 合并成 1~4 个窗口一次读完(`D12`)。`windows>0` 时 `bytes` 是窗口总长;`windows=0` 时 `bytes` 是**所有 extent 的 union span**(判「差多少」用),此时退回逐文件。`reason` = `ok`/`empty`/`span`(单窗超 4MB)/`sparse`(密度 <50%)/`many`(窗口数 >4);`prefetch-fail:<offset>:<msg>` = 该窗口抛错;`-timeout:<n>` = 超出 3s 预算的窗口数(超时后不再试后续窗口) |
| `echo-iso-playlist:…:segments/unique/total` / `-append` | 选片结果与跨段追加 |
| `echo-iso-streams:<pid>:<lang>,…` | STN 读到的语言表 |
| `echo-iso-stream-lang:<n>` | 本次会话发布的 `pid→语言` 条数。`IsoResolver.resolve()` 每次都会打(含命中 `IsoVolumeCache`);**坏会话里它缺失** = 语言表没重建 |
| `echo-setTrack subtitle-list: count/selected/names` | 内置字幕列表的最终名字(语言类问题先看它,与音轨的 `echo-setTrack list` 对齐) |
| `echo-iso-pgs-format:pid=…:lang=…` / `echo-iso-pgs:first=` | PGS 轨注册(必须)/ 首条 display set;`lang=-` = PMT 没带语言描述符(BD 常态,靠 STN 覆盖) |
| `echo-iso-truehd-core` / `echo-iso-lpcm-format` / `echo-iso-dts-hd` | 三条自定义音频链路就绪 |
| `echo-iso-track-defer:declared=N` | 扣住 `endTracks()`,N 条轨等 Format(正常盘随后自动放行) |
| `echo-iso-track-drop:<pid>:<type>` / `-late` / `-end:` | 1.5s 仍无格式无数据被丢 / 丢弃后才来格式 / 汇总。**`drop` 表示该盘有"不说话"的轨** |
| `echo-iso-video-skip:<pid>:primary=` | 非主视频轨被丢弃(DV EL / 次视频) |
| `echo-iso-seek:<pos>:<len>` | ISO 内随机读。打开后紧跟的 `长度 − 1,128,000` 是 media3 尾读,不是我们的 seek |
| `echo-iso-seek-rap:<ms>:<…>` / `echo-iso-seek-inperiod` / `-blocked` | RAP 起播 / 段内 seek / 被闸门挡 |
| `echo-iso-bps:<window>:<avg>:<required>` | 吞吐 vs 需求(比例 <3x 才值得怀疑带宽) |
| `echo-iso-bandwidth*` / `echo-iso-bdav-*` | 带宽判定 / BDAV 探测与升级 |
| `echo-exo-selector` / `echo-exo-codec-init` | 解码器候选与实选;`kind=SOFTWARE` 播 4K Main10 必失败 |
| `echo-iso-error:<msg>` | 解析/定位失败;`volume recognition missing` ≠ 文件不是 ISO |
| `echo-iso-probe:open=…:total=…:mode=…:Content-Type=…` | 起播探测:本次可读 vs 真实总长。探测固定 `Range: bytes=0-4095`(4KB),所以 `open=4096` 是常态;**总长只认 `Content-Range` 的 total**(`mode=200` 时才退化到 `Content-Length`);`mode=206/200/range-none` 是源能力,`range-none` 会直接失败并给「该线路返回的资源不完整」 |
| `echo-iso-probe-head:<16 字节 hex>` | 探测到的首 16 字节;BD 盘前 4KB 常为全零,不代表盘坏 |
| `echo-iso-truncated:<pos>:<remaining>` | **上游提前 EOF**(不是本次读长度用尽):表现为「播到一半停住/直接结束」,出现即源侧截断,不是容器问题 |
| `echo-iso-stream-unmapped:<stream_type>:<lang>` | PMT 声明了但我们和 media3 都没有 reader 的流(如 `0xA1` 从属音频、`0xEA` VC-1);该轨被静默丢弃,菜单里不会出现 |
| `echo-iso-root:count=…:名字:长度:是否目录,…` | BDMV 找不到时打印根目录前 12 项;一眼分辨「盘里真没有」与「套了一层文件夹」 |
| `echo-iso-bdmv:nested=<dir>` | 命中的 BDMV 在一个包装目录里(不在根);解析已支持这种形态(§13.13) |
| `echo-iso-volume-key:<key 后 12 位>:<firstBytes 长度>` | 每次会话算出的**内存缓存**指纹(`IsoVolumeCache`);`firstBytes` 长度必须是 4096 |致快照永远不命中 |

## 7. 走查清单(编号稳定)

> 每次动 `engine/iso/**` 或 `PrimaryVideoExtractor`/`BdTsPayloadReaderFactory` 后按此表跑一遍。设备操作(点播放)必须由用户执行。

1. **单段 ISO**:`udf-vrs` → `m2ts` → `mpls`(`duration == unique`)→ `playlist` → `pgs-format` → `track-defer` → `echo-exo-selector` → `codec-init` → `bps` → 进度 1s/s 推进,无 `echo-iso-error`。
2. **多段 ISO**:`playlist:segments=N` 且 `playlist-append` 出现;跨段无黑屏、无 `codec-init` 重建(档案 §12.9)。
3. **音轨**:菜单条数 = STN 音轨数;主音轨出声(`AT::add`,48kHz/5.1 之类);无 `track-drop` + `track-late` 组合。
4. **字幕**:三条 PGS 都能切、轨名带语言(`英语`/`国语`/…)且显示 `PGS`(不是 `APPLICATION/PGS`)、位图不被拉伸;再**退出播放页、10 分钟内重新进同一集**(命中 `IsoVolumeCache` 与磁盘快照两种路径各一次),轨名语言必须仍在 —— 见 §8-D11。**这条比"能不能播"更容易回归**。
5. **4K 单层**:送解码的 Format 是 3840×2160;失败必须是 `NO_EXCEEDS_CAPABILITIES`(设备边界),不能是"零视频轨"。
6. **4K 双层 DV**:`video-skip` 出现且只出现一次,解码对象是主层。
7. **诱饵盘**:`chosen` 是正片,`duration == unique`;诱饵仍在 `top=` 里但排后面。
8. **混合盘(ISO9660+UDF 1.02)**:`udf-vrs:NSR02` 后全链路通。
9. **碎片盘**:`target-runs` 多 run 也能播。
10. **裸 192B m2ts**:`bdav-probe:192` → 起播;空壳源 13s 级快速失败并提示"资源不完整"。
11. **音乐页**:ISO 内容被拦(`echo-iso-music-bail`),不进音乐页、不静音播放。
12. **seek / 续播**:ISO 内拖动或 RAP 起播后出现**新的** `codec-init` 且画面正确;连拖三次成立。
13. **多段大盘 + 网盘源(连接风暴回归)**:mpls 数 ≥100 的盘在夸克这类源上起播,连续播 2 分钟不得出现 `echo-exo-player-error`;`echo-iso-bps` 不得持续低于需求的 50%;抓包算 `dst port 5266` 的 **SYN ÷ Range**,比值应明显 >1(说明连接在复用),且起播窗口 SYN 不得出现数百/秒尖峰(见 §5-19)。**这条是"能解析但播不动"的唯一判据**。

## 8. 决策记录

| # | 决策 | 理由 / 代价 |
|---|---|---|
| D1 | 只用 media3 扩展点(`DataSource` / `TsPayloadReader.Factory` / `ExtractorOutput`),不 fork | 跟随上游;代价是个别行为要在包装层绕(见 C1–C3) |
| D2 | 不加 native,TrueHD 只取 AC-3 core | 640kbps 有损;纯 TrueHD 无 core 的盘不产音轨(不会"有轨无声") |
| D3 | 选片用去重时长的启发式,不做 BD-J 解析 | 挡得住重复段诱饵,挡不住"每段微调 in/out";用户选播放列表**拍板暂不做** |
| D4 | 4K Main10 解不动如实上报,不做软解兜底 | 回退硬解的开关**待拍板**;模拟器上无论如何解不动 |
| D5 | seek 走"重建 MediaSource + RAP 起播",不做解码器复用 | B2c/B2a 两轮实测判负(档案 §11.8);RAP 起播真机可用(档案 §11.10) |
| D6 | 音轨"晚到"用**临时 Format**,不用时间窗丢轨 | 时间窗会误伤真实主音轨(档案 §14.13 的 bug B) |
| D7 | 延迟声明门保留,但只作保险 | 有临时 Format 后它基本不参与;留着兜住"既无格式又无数据且未覆盖的类型" |
| D8 | 多段盘用 `ConcatenatingMediaSource` 逐段追加 | 段首必带参数集,跨段解码器复用;代价是 seek 更容易触发重建 |
| D10 | 音频解码失败只换音轨,不碰解码方式 | 见 C13。落地:`IsoAudioFallback` 按内容(VOD URL)记一条被拉黑的 PID,重建 MediaSource 时 `PrimaryVideoExtractor` 不再声明它(`echo-iso-audio-skip`),selector 自然落到下一条;提示用 `player_audio_track_fallback_tip`「音轨无法解码,自动切换」。**不做**自动切软解(4K 上只会更糟),也不改 `exo` 解码设置;音频分支在 `autoRetry` 里直接 return,不再往"切内核/换线路"继续走。同 URL 再次播放时拉黑仍然生效 —— 这是有意的:解码器的能力不会因为重播而改变 |

| D11 | `pid→语言`表随 `IsoVolumeInfo` 走,`IsoResolver.resolve()` **每次**都重新发布(含命中原生卷缓存) | 语言表是会话态(`MediaSources.createIsoMediaSource` 每次 `IsoPlaylistState.reset()`),而原先只在 `IsoLocator.locateSelection()` 里 `publishStreams()`;`IsoVolumeCache`(进程内 LRU 4 条 / TTL 10 分钟)命中时**根本不跑 locate** ⇒ 表被清空却没重建,所有轨(音+字)失去语言,字幕菜单退化成"字幕1/2/3…"。代价:`IsoVolumeInfo` 多两个字段 + 每次 resolve 一次 O(n≤30) 映射重建 |
| D12 | 起播解析**只减请求条数、不加并发**:mpls 读前按 extent 合并成 1~4 个窗口一次预取(`mplPrefetchPlan`,上限 4MB / 间隙 256KB / 密度 50%),失败回落逐文件;每个窗口跑在 `IsoMplsFetch` 的独立预取线程上,`Future.get(3s)` 超时即放弃(后台跑完仍会填 cache),超时后不再试后续窗口 | 解析段耗时是**请求条数 × 冷首字节**(实测 11 条约 4.1s,370ms/条),放大单条块长无收益;`IsoMplsFetch` 的 `THREADS=1` 是 §5-19 连接风暴换来的,不能开回并发。窗口守卫不满足时直接返回空即退回原路径(实测 45.2GB 盘 5 个 618B mpls 分散在 5 处 → `windows=0`,零副作用)。**同批的「probe 一次读 0..sector16+512KB 省掉 VRS 请求」已判负并回退**:省一次往返但 probe 自身变长(1.39s→1.46s),净收益 0。配套约束:`UdfParser` 的 span 缓存加总字节上限 8MB(防 4 卷 × 2 窗口 × 4MB 常驻);fast 路径预取成功才把条目计入 `covered`,未覆盖的条目仍会二次预取(`mplPrefetchCandidates`) |

## 9. 未决清单

| # | 事项 | 状态 |
|---|---|---|
| 1 | 真机 4K Main10 硬解验证 | **未验证**(模拟器只到 ProfileMain) |
| 2 | 「软解超 `NO_EXCEEDS_CAPABILITIES` 时是否自动回退硬解」 | **待拍板** |
| 3 | 盘 B(`00020.mpls`,302 段)的诱饵审计:`duration` 与 `unique` 是否相等 | **未核对** |
| 4 | 用户选播放列表 + 按盘记选择 | 已评估,**用户拍板暂不做** |
| 5 | 音轨"按 PID 载荷判活"的更精确丢弃规则 | 可选,**未开工**(现状见下,别读成空白)。**已有的一半**:`DeferredTrackOutput` 在 **Format 或首个 sample 任一到达**时就 `open()`(「有数据即落地」),所以"永远不说话的轨"仍会等到 `DEFER_HOLD_MS = 1500ms` 被 `track-drop`。**缺的一半**:判死依据是**时长**而非**该 PID 的载荷**(`PrimaryVideoExtractor.kt` 的 `dropAllowed()` 只看"主视频/音频是否已落地");要做可挂在 `LanguageOverrideReader`(它已是带 pid 的包装层,`EsInfo` 不带 pid 的障碍已绕过) |
| 6 | 临时 Format 的准值:从 STN 的 `audio_format`/声道数读,而非按类型猜 | 可选,**未开工**(现状见下)。`BdTsPayloadReaderFactory.PROVISIONAL_*` 仍是硬编码 `48kHz` + `6ch`(AC-3/E-AC-3/DTS) / `2ch`(LPCM);`MplPlaylist.readStnStreams` **只读 `pid + language`**,`audio_format`/声道数字段根本没解。代价仅限"真实 Format 到达前音轨菜单显示猜值",不影响出声 |
| 7 | 4K 多段盘、PGS 跨段延续、分钟级跨段 | **未覆盖** |
| 8 | 稳态吞吐长跑(2–3 分钟连续) | **未测**;现有 5.9–11.8MB/s 都是折算值 |
| 9 | ~~seek 的两个疑点:`TsBinarySearchSeeker` 为何一次二分都没跑;落点开始读时视频轨为何拿不到 Format~~ | **已结(2026-10-20 真机复验,档案 §11.10)**。①"二分没跑"不是缺陷:重建路径按 CLPI 访问点算出**精确字节偏移**、直接开新 period,**本来就不该跑二分**;段内 seek 才是 media3 容器 seek(真机 `echo-iso-seek:807646097:4467110511`,一次跳 807MB,无二次 `codec-init`)。②"落点拿不到 Format"的前提不成立:访问点**自带参数集**(档案 §11.9.2 逐字节解开 `AUD/SPS/PPS/SEI`,每个访问点重发),真机 RAP 起播 `codec-init: name=c2.qti.avc.decoder kind=HARDWARE` 出画有声有字。真正留下的是教训:模拟器假解码器给的 4 次判负全部作废(档案 §11.10) |
| 11 | `audio/vnd.dts.hd` 送 ffmpeg 时解码失败(DTS-HD MA 的扩展子流) | **已结(2026-10-20 23:22 真机验证)**。机制已锁定(2026-10-20 23:11):同一条轨**起读时是 `audio/vnd.dts`(core),几十毫秒后 `DtsReader` 遇到 EXTSS 帧重新发一条 `audio/vnd.dts.hd`**,ffmpeg 当场 `FfmpegDecoderException`;所以复现与否取决于"何时遇到扩展子流"。修法:`0x85/0x86` 不再用 media3 的 `DtsReader`,改用 `DtsCoreReader` —— 只认 core 帧、按 `DtsUtil.getDtsFrameSize` 截断、跳过 EXTSS,Format 恒为 `audio/vnd.dts`(`echo-iso-dts-hd:…:core=1` + `echo-iso-dts-core:ch=…:rate=…`)。C13/D10 的换音轨回退保留作兜底。真机复验:同一续播点 684,637ms 重放,`echo-iso-dts-core:ch=6:rate=48000` → `echo-iso-audio-format:…audio/vnd.dts` → `PLAYING hasAudio=true`,全程无 `player-error` / 无换轨 |

| 12 | 字幕菜单全是"字幕1/2/3…"、没有中英文;音频那边也丢语言 | **已结(2026-10-20 23:37 真机复验)**。根因(2026-10-20 真机日志锁定):`IsoVolumeCache` 命中时跳过 `locateSelection()`,于是 `publishStreams()` 不执行,而 `reset()` 已经把表清空。所以是**首次冷进正常、退出重进 / 10 分钟内重播必坏**,不是概率问题。判据:坏会话**没有** `echo-iso-stream-lang`,且 `subtitle-list` 的名字退回 `字幕N PGS`(临时加的 per-track `echo-iso-lang` 已于同轮移除,见 §11 末行)。修法见 `§8-D11`;回归用例 `IsoResolverTest.resolve_republishesStreamLanguagesOnCacheHit`。**复验**:同一张盘第二次 prepare(23:37:29.391,`IsoVolumeCache` 命中,580ms 内出表)→ `echo-iso-stream-lang:count=25` → 25 条轨全部 `lang=eng/deu/spa/fra/zho/nor/por/fin/swe/jpn`,`lang=-` **0 条**;字幕列表恢复 `字幕1 - 英语 PGS … 字幕15 - 日语 PGS`。对照修复前同一路径(23:30:39)25 条轨全是 `lang=-` |

| 13 | ISO 起播剩余耗时:~~probe 补读 0.49s~~(**2026-10-23 已做**:probe 首请求带 `Range: bytes=0-4095`,单次往返)、clpi 索引 0.64s(尝试与轨道声明并行) | probe 已结;clpi 并行**未开工**。基础时间线见档案 §13.9 |
| 14 | **连接风暴**:`IsoMplsFetch` 4 线程并发抓 202 个 mpls、每请求一短连接 ⇒ 起播两秒 402 个连接,被上游限速到 1MB/s,表现为「能解析但播不动」 | **根因锁定为「连接不复用」**(2026-10-23,档案 §14.16 / `§5-19`):实测 1.34 请求/连接。**已落地并实测**:①`IsoMplsFetch.THREADS` 4→1 串行(`SYN 503→50`、复用率 `1.34→3.24`);②**扫描量按文件大小截断到 top 16**(`MAX_PLAYLISTS`,日志加 `scanned=`)—— mpls 请求 **202→16**、`fetch` **6138ms→2158ms(−65%)**、`chosen` 与全扫一致、报错 0(方案稿 §10.7)。**P0 对该盘无效** —— 该盘是 BD-J(`§5-22`),HDMV 快路径必然回落全量扫描;top-N 策略不依赖 HDMV,是当前正解 |

## 10. 档案索引

| 想知道什么 | 去哪 |
|---|---|
| 某个现象当初怎么定位的、盘的事实、日志原文 | `history/iso-playback-chronicle.md` 甲(案例 A–H)/ 乙(缺陷 §14.x) |
| seek / 倍速为什么这么定、哪些路被证伪 | 档案 丙(`§11.5–§11.10`) |
| 多段盘与性能的实测数字 | 档案 丁(`§12.9`、`§13.x`) |
| 播放器归属 / 挂摘 / 后台与通知 | `avbox-playback-service-spec.md` |
| 通用规范、零注释、门禁、审查收敛 | `SKILL.md` |

## 11. 修订记录

| 日期 | 变更 |
|---|---|
| 2026-10-19 | ISO 播放立项:UDF 只读解析 + m2ts 选片 + 192→188 剥头 + `TsExtractor` 接入;A0 spike 与 A1 分片落地(细节见档案与旧文档存档) |
| 2026-10-19 | LPCM 致命 bug 修复:样本未带 `BUFFER_FLAG_KEY_FRAME` ⇒ 音频渲染器永不 ready,表现为"卡住 + 带宽误报" |
| 2026-10-19 | TrueHD(`0x83`)取 AC-3 core(`Ac3CoreReader`);裸 192B BDAV 支持(`BdavDataSource` + `IsoSessionSignal`) |
| 2026-10-19 | PGS 走 media3 官方字幕转码(`PgsReader` 按 END 拼接 + 6 参 `TsExtractor`);自研 PGS 解码器删除 |
| 2026-10-20 | 多段盘拼接(`ConcatenatingMediaSource` + mpls 解析);RAP 起播经真机复验可用 |
| 2026-10-20 | 五台超大盘 + 三台 4K 暴露出 `§5` 的 #4–#13:碎片 run、`stream_type` 撞号、STN 语言、字幕命名与矩形、FID 跨块、快照版本、DV EL、诱饵播放列表、混合盘 VRS |
| 2026-10-20 | **本文重写为 as-built 活规范**,并从 `文档/` 迁入 `skill/`:结论/约束/判据留本文,案例与取证迁 `history/iso-playback-chronicle.md`;文档地图登记 |
| 2026-10-20 | 盘 J 的"4 秒卡死"修复:`PrimaryVideoExtractor` 延迟声明门 + 有数据即落地 + 按 stream type 的临时 Format(`ProvisionalFormatReader`);新增 `§4-C1..C3` 与 `§5` 的 #1–#3 |
| 2026-10-20 | 诊断日志复核:确认无 per-sample 日志、落盘仅 debug;顺带修掉 `echo-progress-tick` 暴露的**进度回调自旋**(纯判定 `ProgressTickDelay`,下限 200ms)与 debug 日志**8MB 轮转**(`AppLog.shouldRotateLog`);全量 **1287 通过 / 0 失败 / 3 跳过** |
| 2026-10-20 | §9 对账(只改文档,不动代码):seek 两个疑点按真机复验(档案 §11.10)结案并删除;`#5`/`#6` 补 as-built 现状 —— 「有数据即落地」已覆盖大部分丢轨场景(缺的只是按载荷而非时长判死)、临时 Format 仍硬编码 `48k/6ch/2ch` 且 STN 只解 `pid+language`,避免被读成"完全空白" |
| 2026-10-20 | **修复"字幕菜单只有 字幕1/2/3、没有语言"**:`pid→语言`表原先只在 `IsoLocator.locateSelection()` 发布,而 `IsoVolumeCache`(进程内 LRU,TTL 10 分钟)命中时跳过 locate ⇒ `reset()` 清空后不重建,音/字轨全部失去语言。改为把语言表挂进 `IsoVolumeInfo`,在 `IsoResolver.resolve()` 里每次(含缓存命中)重新发布(`§8-D11`、`§9-12`);新增 `echo-iso-stream-lang` / `echo-setTrack subtitle-list` 两条判据日志(`echo-iso-pgs-format` 原有行补 `pid/lang`)与 `IsoResolverTest` 回归用例;全量 **1318 通过 / 0 失败 / 3 跳过**。**真机复验(23:37)**:同一张盘 10 分钟内第二次 prepare 命中 `IsoVolumeCache` 后 `echo-iso-stream-lang:count=25` 照常发布,25 条轨语言齐全,字幕列表 `字幕1 - 英语 … 字幕15 - 日语` |
| 2026-10-20 | **收掉临时诊断日志**:诊断期加的 per-track `echo-iso-lang:<pid>:<type>:<lang>`(每次重建 25 行)按用户决定删除 —— 判据由 `echo-iso-stream-lang`(有没有发布)+ `echo-setTrack subtitle-list`(最终名字)覆盖;`AppLog.i` 的写盘本就在单线程 executor 上异步、且 release 不落盘,但这行是全轮唯一随重建次数增长的日志,删掉后常驻零新增噪声 |
| 2026-10-20 | **目录快照迁到 `filesDir/iso-volume`**(原 `cacheDir` 会被系统低存储回收、系统设置/应用信息里的"清除缓存"清掉 = 白丢 3.09s 起播收益)。策略见 `§8-D9`:**应用内「清除缓存」改为显式清它**(`SettingsViewModel.clearCache()` → `IsoDiskCache.clear()`)并把 ≤8MB 计入显示的大小(`bytesOnDisk()`);旧位置首次访问整目录删除(`echo-iso-cache-legacy-drop`)。顺带修正 §3 对该文件的错误描述(它不碰 clpi),并把"前 4KB 实测全零 ⇒ 键只剩总长"记入 `§9-10`;全量 **1302 通过 / 0 失败 / 3 跳过** |
| 2026-10-20 | **音频解码失败不再冒充"硬解失败"**(`§4-C13`、`§8-D10`、`§5` #16):`PlayerEngine` 记录失败轨类型(`classifyTrackKind`),音频失败只换音轨 —— 新增 `IsoAudioFallback`(按内容拉黑 PID)+ `PrimaryVideoExtractor` 的 `suppressedAudio` 出口 + `echo-iso-audio-format` / `echo-iso-audio-skip` 两条日志 + 文案 `player_audio_track_fallback_tip`;`audio/vnd.dts.hd` 本身仍解不了,记入 `§9-11`;全量 **1313 通过 / 0 失败 / 3 跳过** |
| 2026-10-23 | **「多段大盘在网盘源上能解析但播不动」根因锁定为连接风暴**(档案 §14.16、`§5` #19–#21、`§7` #13、`§9-14`):`probePlaylists()` 读 202 个 mpls、4 线程并发、每请求一短连接 ⇒ 起播两秒 402 个连接,被上游限速到 1MB/s(`bandwidth-mid` 实测≈1048576)。同日落地并保留:probe 单次往返(`Range: bytes=0-4095`,总长走 `Content-Range → Content-Length → 回落探测`)、metadata 预取上限 1MB→64KB、`M2tsStream` 跨窗口续读 + `read()` 抛 IOException 时 `recover`(最多 4 次)、`IsoDataSource.openWindow` 改开放结尾 `bytes=X-`、窗口 4MB。**推翻并记录两条弯路**:cap `open()` 返回值会让 media3 认为整段只有那么大(`§5-20`);`unexpected end of stream` 是抛 IOException 而非返回 -1,只挂 `read <= 0` 的容错等于没接(`§5-21`)。全量 **1391 通过 / 0 失败 / 3 跳过**;根本解 P0(202 → 1 个 mpls)未开工 |
| 2026-10-23 | **同日两处修正**(档案 §14.16 补记):①机制从「请求数多」修正为**「连接不复用」** —— 抓包全窗口 **SYN 503 / Range 672 = 1.34 请求每连接**,fongmi 全程 5 个连接;`IsoMplsFetch.THREADS` 4→1 串行让连接归还池复用,单测契约同步(`load_keepsNetworkedEntriesOnSingleThread`)。②**P0 前提证伪**:取到该盘 `index.bdmv` 按 libbluray `index_parse.c` 核对,`object_type=2` ⇒ **BD-J 盘**,HDMV 快路径必然回落全量扫描,P0 解决不了这条(新增 `§5-22`)。样本留档 `源码/bdmv/` |
| 2026-10-23 | **修复收口:固定长度 + EOF 容忍**(方案稿 §10.5、`§5-19`):串行后实测 `SYN 503→50`、复用率 `1.34→3.24`,但**开放结尾 `bytes=X-` 会破坏复用**(读到 window 末尾就 close,响应体没读完 ⇒ 连接被丢弃);改回固定长度 `window.length`,并在 `IsoDataSource.read` 用 `isUpstreamTruncation()` 把 `ProtocolException: unexpected end of stream` 转成 `END_OF_INPUT`,交给 `M2tsStream.recover()` 换连接续读补齐(新增 `echo-iso-eof`)。实测 05:42 起播:**`echo-exo-player-error` 消失**,`eof` 连续 11 次全部吞掉,位置推进到 25.8 秒。遗留:59 秒只播 25.8 秒,带宽 3.3MB/s 仍低于需求 4.42MB/s,属源侧限速的剩余影响 |
| 2026-10-23 | **后台任务改带宽门控**(方案稿 §10.6、`§5-23`):截断修复后卡顿换形态 —— 起播 13s(快照构建)/35s(延迟 20s + 扫描 13s)各卡一次,`echo-iso-eof:0:4283544:4283544` 两值相等即「新连接刚建好就 EOF、一字节未读」。两次误判(先怪 clpi 预取、再怪执行时机)**都只是把卡顿往后推**;真因是带宽余量为负时任何后台 IO 都会拖死播放。改 `IsoPrepare` 延迟后先判余量,`bps < required × 1.5` 整段跳过(`echo-iso-prepare-skip`)。取舍:这类盘快照永不建成、每次冷启动全扫 202 个 mpls,**播放不卡 > 起播快**。实测 05:50:55 起播 75 秒无报错(`eof` 31 次全吞),但门控 0 次触发 —— 当轮带宽 4~10MB/s 属环境变好,**门控那半边仍未实测** |
| 2026-10-23 | **扫描量优化:按文件大小取 top 16**(方案稿 §10.7):查证 fongmi **有**缓存(`IsoCache`/`IsoDiskCache`),它快是因为扫描量小(抓包仅 5 个连接);而快照收益实测 −3.09s、clpi 预取 0.5~0.6s,**不该移除该减量**。`probePlaylists` 的 `entries` 本就按 `it.length` 降序,现截断到 `MAX_PLAYLISTS=16`,日志加 `scanned=`。实测 **mpls 请求 202→16、`fetch` 6138ms→2158ms(−65%)、`chosen` 与全扫完全一致(00801.mpls/81 段)**、报错 0。对 BD-J 盘同样有效(不依赖 HDMV 导航)。风险:诱饵盘;若某盘 `chosen` 与全扫不一致需调大 N |
| 2026-10-23 | **彻底删除目录快照与 clpi 预取**(方案稿 §10.8 之后):`IsoDiskCache.kt`/`IsoPrepare.kt` 连同 `IsoVolumeSnapshot`/`CachedVolume`/`UdfVolume.snapshot()` 全删,`SettingsPage` 的"清除缓存"不再含 ISO 部分;clpi 改回 `accessPoints` 按需读。理由:top-N 短路后快照收益从 6s 缩到 2.3s,而构建抢带宽会导致播放卡死;且加载路径会绕过新逻辑。**同步删除** `§3` 模块地图两行、不变量 **C9**、`§5` #10、`§6` 的 4 条 cache 日志、`§8-D9`、`§9-10`。单测 **1391 → 1374**(删掉的 17 个全是快照用例) |
| 2026-10-20 | 真机复现确认为**轨中途升级**成 `dts.hd`(不是续播落点);按 A 方案落地 `DtsCoreReader`:`0x85/0x86` 只送 DTS core、跳过 EXTSS,mime 恒 `audio/vnd.dts`(`§9-11` 转"待真机验证");新增 `DtsCoreReaderTest` 4 例;全量 **1317 通过 / 0 失败 / 3 跳过**
| 2026-10-20 | `DtsCoreReader` **真机复验通过**(23:22,同一续播点):`dts-hd:…:core=1` → `dts-core:ch=6:rate=48000` → `audio/vnd.dts` 全程稳定,`PLAYING hasAudio=true`,无 `player-error` / 无换轨;`§9-11` 结案 |
| 2026-10-22 | **修复分块代理下起播必失败**(`§4-C14`、`§5` #17、档案 §14.14):`readProbe` 原先把 `DataSource.open()` 返回值当镜像总长,而该源对**无 Range** 请求也回 `206 + Content-Range: bytes 0-1023/45098467328`(body 仅 1024 字节)⇒ 1024 撞 UDF 64KB 闸门报 `image too small`。总长改从 `Content-Range` 的 `total` 取、回落 `open()`;首 4KB 不足时用一次带 Range 的 `DataSourceRangeReader` 补齐(指纹口径仍 4KB);probe 阶段的 `IsoFormatException` 也落 `echo-iso-error`;新增 `echo-iso-probe` / `echo-iso-probe-head` 两条常驻判据日志与 `IsoProbeLengthTest`(7 例)。全量 **1333 通过 / 0 失败 / 3 跳过** |
| 2026-10-22 | **目录快照构建移出起播路径**(884ms):`IsoPrepare.schedule` 的第 4 参由 `ByteArray?` 改为 `(() -> ByteArray?)?`,`buildSnapshot` 与落盘都在 `iso-prepare` 线程执行(排在 clpi 预取之前,不受 6s 延迟约束);`IsoResolverTest` 的读计数排除 `iso-prepare` 线程以保住 single-flight 断言。起播拆解:首次 **12.08s**(mpls 34 个串行 3.42s + UDF 2.66s + 快照 0.88s + 软解首帧 2.90s)→ 快照命中 **4.74s**;`§9` 补第 13 项剩余优化,数据见档案 §13.9 |
| 2026-10-22 | **兼容性判据补强**(只加日志 + 一处前置失败,不动解析逻辑;档案 §13.10):①`M2tsStream` 区分「本次读长度用尽」与「上游提前 EOF」,后者打 `echo-iso-truncated`——此前截断被当正常结尾,现象是「播一半停住」且日志无错;②`BdTsPayloadReaderFactory` 对 media3 也不认识的 `stream_type` 打 `echo-iso-stream-unmapped`——此前静默丢轨,菜单少一条轨没有任何痕迹;③probe 行补 `mode=206/200/range-none`,并在 `Accept-Ranges: none` 时 `markStub` + 抛 `range unsupported`,让「源不支持随机读」落到「该线路返回的资源不完整」文案,而不是撞 UDF 的 `image too small`;`IsoProbeLengthTest` 7 → 9 例,全量 **1335 通过 / 0 失败 / 3 跳过** |
| 2026-10-22 | **支持 BDMV 套一层包装目录**(`§5` #18、档案 §14.15):压片组常把 `BDMV` 连同海报/简介塞进片名文件夹再打成 ISO,根下直接 `find(BDMV)` 会报 `BDMV directory missing`,表现是「缓冲约 20s 后视频播放出错」。`IsoLocator` 增加 `bdmvEntry`(lazy):根下没有就遍历第一层子目录找,命中打 `echo-iso-bdmv:nested=<dir>`;`loadClipIndex` 同时改为共用该入口(否则 CLPI/seek 在这类盘上失效)。新增 `echo-iso-root` 根目录探针与 `UdfImageBuilder(nestedBdmv=true)` fixture + 2 条用例,全量 **1348 通过 / 0 失败 / 3 跳过** |
| 2026-10-22 | **巨型盘起播优化**(档案 §13.13):一张 264 mpls / 123 m2ts 的嵌套盘起播 34.7s,其中 mpls 段 22.4s(fetch 13.8s + 解析 8.6s)。①`UdfVolume.snapshot` 支持嵌套 BDMV(快照里用 `BDMV` 别名指向真实目录,格式不变),这类盘从"每次全量"变成"第二次命中快照";②`IsoMplsFetch` 的并发 2 → 4,且把 `parseMplPlaylist` 一起放进任务(纯内存,返回 `MplFetchResult`),失败/取消退化为串行重试一次。新增快照往返用例,全量 **1349 通过 / 0 失败 / 3 跳过** |
| 2026-10-22 | **目录项 FE 并发读:实测判负,代码已回退**(档案 §13.13):巨型盘的 `dir=11596`(264 个 PLAYLIST 目录项)+ STREAM 123 项 ≈ 17.7s 全串行,试过 `UdfVolume.list` 对 >24 项目录用 4 线程并发读 `readChild`。实测同盘 `dir` 11.59s → 11.39s、STREAM 段 6.14s → 6.21s,**都在误差内**;单请求耗时从 43ms 涨到 173ms ⇒ 上游按并发数限速、总吞吐固定,并发只是把排队时间换了个形状。代码与 4 条测试已删除,`UdfVolume.list` 恢复原实现 |
| 2026-10-22 | **mpls 读取并行化**(`IsoMplsFetch`,档案 §13.11):`IsoLocator.probePlaylists` 原来逐个串行读,实测 ≈0.1s/个(10 个 1.33s、34 个 3.42s、90 个 **9.25s**,30 张盘里最慢的一张起播 20.3s)。改为并发 2 + 8s 整体超时,取消/异常的条目退回串行补齐,选片结果不变;内联条目(快照命中)与单条 mpls 仍走调用线程。`echo-iso-mpls` 末尾新增 `:fetch=<ms>`;新增 `IsoMplsFetchTest` 3 例,全量 **1338 通过 / 0 失败 / 3 跳过** |