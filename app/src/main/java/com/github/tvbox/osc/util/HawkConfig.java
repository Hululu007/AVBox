package com.github.tvbox.osc.util;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class HawkConfig {
    public static final String API_URL = "api_url";
    public static final String EPG_URL = "epg_url";
    public static final String API_HISTORY = "api_history";
    public static final String API_LINE_LIST = "api_line_list";
    public static final String API_LINE_SOURCE = "api_line_source";
    /**
     * 直播侧的多仓(仓库)列表与其仓地址(2026-09-21,对齐 FongMi 的 {@code LiveConfig.parseDepot})。
     * 与点播的 API_LINE_LIST 分开存 —— 同一条链路的直播/点播是两个独立配置,仓列表不能互相覆盖。
     */
    public static final String LIVE_API_LINE_LIST = "live_api_line_list";
    public static final String LIVE_API_LINE_SOURCE = "live_api_line_source";
    /**
     * 启动看门狗标记(见 {@code util/BootGuard}):正在装载的 jar / 累计次数 / 装载起点 / 崩溃时的启动源 /
     * 被停用的源。用途 = 第三方爬虫把 CDN 报错当 .so 加载导致"冷启动必崩"时,下次启动自动停用那个源。
     * ⚠️ 崩溃时刻**不在这里** —— 它必须同步落盘,走 files/boot_crash.marker。
     */
    public static final String BOOT_LOADING_JAR = "boot_loading_jar";
    public static final String BOOT_LOADING_COUNT = "boot_loading_count";
    /** 最近一次"开始加载 jar"的开机计时(每次装载都覆盖):与崩溃标记同源,用于判"崩溃是否发生在装载阶段" */
    public static final String BOOT_LOAD_START_ELAPSED = "boot_load_start_elapsed";
    /** 上次"开始加载 jar"的时刻:用于"距上次太久就重新计数"的判定 */
    public static final String BOOT_LAST_ATTEMPT_AT = "boot_last_attempt_at";
    /** 崩溃发生时正在使用的启动源(点播/直播分开记,见 BootGuard.recordCurrentSource) */
    public static final String BOOT_VOD_SOURCE = "boot_vod_source";
    public static final String BOOT_LIVE_SOURCE = "boot_live_source";
    /** 上一次因连续崩溃被自动停用的源地址(UI 读它做提示) */
    public static final String BOOT_SAFE_DISABLED = "boot_safe_disabled";
    /**
     * 风险源黑名单(源地址列表)。与 {@link #BOOT_SAFE_DISABLED} 的区别:那个是一次性提示(读后即清),
     * 这份是持久名单 —— 配置管理页据此给源打"已禁用"标记并拦一次,仓改写据此跳过坏子源。
     * 用户二次确认后可移除(见 {@code BootGuard.enableSource})。
     */
    public static final String BOOT_DISABLED_SOURCES = "boot_disabled_sources";
    public static final String LIVE_API_HISTORY = "live_api_history";
    public static final String HOME_API = "home_api";
    public static final String DEFAULT_PARSE = "parse_default";
    public static final String IJK_CODEC = "ijk_codec";
    // EXO 解码方式(2026-09-17):与 IJK 的 IJK_CODEC 独立开键 —— IJK 走内核自带 options(mediacodec=0/1),
    // EXO 走 media3 的 MediaCodecSelector(软解 = 系统软件解码器 c2.android.* 优先,仅视频渲染器)
    public static final String EXO_DECODE = "exo_decode";
    public static final String SUBTITLE_TEXT_STYLE = "subtitle_text_style";//外挂字幕文字样式 0 白 1 粉(#FFB6C1)
    public static final String PLAY_TYPE = "play_type";//1 ijk 2 exo 10 MXPlayer
    public static final String LIVE_PLAY_TYPE = "live_play_type";//1 ijk 2 exo 10 MXPlayer
    public static final String PLAY_RENDER = "play_render"; //0 texture 2
    public static final String PLAY_SCALE = "play_scale"; //0 texture 2
    // EXO 音频隧道(audio offload,2026-09-11):压缩音频码流直通 DSP 解码;设备/格式不支持时自动回退普通播放
    public static final String PLAY_TUNNEL = "play_tunnel";
    // 音轨优先 AAC(2026-09-11,独立开关):选轨偏好 AAC,提高隧道命中率/规避个别机型 offload 异常
    public static final String PLAY_PREFER_AAC = "play_prefer_aac";
    public static final String LIVE_PLAY_SCALE = "live_play_scale";
    public static final String DOH_URL = "doh_url";
    public static final String HISTORY_NUM = "history_num";
    public static final String LIVE_CHANNEL = "last_live_channel_name";
    public static final String LIVE_CHANNEL_REVERSE = "live_channel_reverse";
    public static final String LIVE_CROSS_GROUP = "live_cross_group";
    public static final String LIVE_CONNECT_TIMEOUT = "live_connect_timeout";
    public static final String LIVE_SHOW_NET_SPEED = "live_show_net_speed";
    public static final String LIVE_SHOW_TIME = "live_show_time";
    public static final String SUBTITLE_TEXT_SIZE = "subtitle_text_size";
    public static final String SUBTITLE_TIME_DELAY = "subtitle_time_delay";
    public static final String SUBTITLE_EXO_SCALE = "subtitle_exo_scale";
    public static final String SUBTITLE_EXO_POSITION = "subtitle_exo_position";
    public static final String SOURCES_FOR_SEARCH = "checked_sources_for_search";
    public static final String REMOTE_TVBOX = "remote_tvbox_host";
    public static final String IJK_CACHE_PLAY = "ijk_cache_play";
    public static final String PLAYER_IS_LIVE = "player_is_live";
    public static final String DOH_JSON = "doh_json";
    public static final String LIVE_GROUP_INDEX = "live_group_index";
    public static final String LIVE_GROUP_LIST = "live_group_list";
    public static final String LIVE_API_URL = "live_api_url";
    public static final String M3U8_PURIFY = "m3u8_purify";
    public static final String AUTO_SWITCH_LINE = "auto_switch_line";
    public static final String SCREEN_DISPLAY = "screen_display";
    public static final String LIVE_WEB_HEADER = "live_web_header";
    public static final String DEFAULT_LOAD_LIVE = "DEFAULT_LOAD_LIVE";
    public static final String SEARCH_HISTORY = "search_history";
    // 搜索页热门榜缓存(原为 SearchActivity 内的字面量键,2026-09-13 KV 迁移时集中登记以便类型注册)
    public static final String HOME_HOT = "home_hot";
    public static final String HOME_HOT_DAY = "home_hot_day";
    /**
     * 无痕模式(2026-09-12):开启后**不写入**搜索历史与观看历史(含播放进度);
     * 手动收藏、以及删除/清空历史等用户主动操作不受影响。判定统一走 [HistoryHelper.isIncognito]
     */
    public static final String INCOGNITO = "incognito";
    /**
     * 禁用手势控制(2026-09-13):开启后播放器页面不再响应上下滑调节亮度/音量。
     * 判定统一走 [com.github.tvbox.osc.util.GestureHelper.isControlDisabled],点播与直播两侧共用
     */
    public static final String GESTURE_CONTROL_DISABLED = "gesture_control_disabled";
    /**
     * 禁用导航动画(2026-09-17):开启后底部导航(HorizontalPager)不响应左右滑动手势,点底栏仍可切换
     */
    public static final String NAV_ANIMATION_DISABLED = "nav_animation_disabled";
    // 搜索线程数(2026-09-12,设置页滑块 16/32/48/64 四档):全站搜索源并发信号量许可数
    public static final String SEARCH_THREADS = "search_threads";
    public static final int SEARCH_THREADS_DEFAULT = 32;
    // 长按倍速(2026-09-12,设置页滑块 2x~10x 步长 1):长按画面临时提速的倍率
    public static final String LONG_PRESS_SPEED = "long_press_speed";
    public static final int LONG_PRESS_SPEED_DEFAULT = 3;
    // 缓冲倍数(2026-09-12,设置页滑块 1x~10x 步长 1,默认 3x,照搬 fongmi 方案):
    // Exo 蓄水目标 = 官方默认 50s × N;起播/再缓冲阈值不乘,保证秒开
    public static final String BUFFER_TIMES = "buffer_times";
    public static final int BUFFER_TIMES_DEFAULT = 3;
    public static final String PRELOAD_NEXT_EPISODE = "preload_next_episode";
    /** 下一集预载时长(秒,20~120 步长 10,第二期参数化):控制预载数据范围(内存缓冲 + 磁盘写盘) */
    public static final String PRELOAD_DURATION = "preload_duration";
    public static final int PRELOAD_DURATION_DEFAULT = 60;
    /**
     * 边播边缓存(第二期扩展,**默认关**,2026-09-13 由默认开改关):点播全程走磁盘缓存数据源
     * (直播页不启用,见 MyVideoView 点播标记)。改关原因:CacheDataSource 与 App 内本地代理
     * (spider 自建/网盘)的区间读取语义不兼容,实测导致 EXO 起播失败(设置页已加提示副标题)。
     */
    public static final String PLAY_CACHE = "play_cache";
    /**
     * Exo 共享缓存容量 MB(128~4096 步长 128,默认 512):
     * 预载写盘与边播边缓存共用同一 SimpleCache(LRU);容量在缓存创建时固定,改动需重启 App 生效
     */
    public static final String EXO_CACHE_SIZE_MB = "exo_cache_size_mb";
    public static final int EXO_CACHE_SIZE_MB_DEFAULT = 512;
    public static final String DANMU_OPEN = "danmu_open";
    public static final String DANMU_MAX_LINE = "danmu_max_line";
    public static final String DANMU_SPEED = "danmu_speed";
    public static final String DANMU_ALPHA = "danmu_alpha";
    public static final String DANMU_SIZE_SCALE = "danmu_size_scale";
    public static final String DANMU_RANDOM_COLOR = "danmu_random_color";
    public static final String DANMU_API = "danmu_api";
    /** 弹幕接口是否用内置默认(原为 DanmakuApi 内的字面量键,2026-09-13 KV 迁移时集中登记) */
    public static final String DANMU_API_USE_DEFAULT = "danmu_api_use_default";
    // 源名快照(2026-09-14):HashMap<sourceKey, 源显示名>。历史记录只存 sourceKey 不存源名,
    // 换源/冷启动后源不在当前配置里时,历史卡片靠这份快照兜底显示记录时的完整源名(含 emoji)
    public static final String SOURCE_NAME_CACHE = "source_name_cache";
    // 配置管理订阅源(2026-09-11):ArrayList<String>,每项 "名字\t链接"
    public static final String SUBSCRIBE_LIST = "subscribe_list";
    // 配置管理独立直播源(2026-09-12 点播/直播拆分):格式同 SUBSCRIBE_LIST。
    // 与点播源分开存储 —— 同一链接若同时用作点播与直播,应让直播保持"跟随"(LIVE_API_URL 空),不必重复录入
    public static final String LIVE_SUBSCRIBE_LIST = "live_subscribe_list";
    // 主题设置(2026-09-11,照搬 示例文件/android 主题设置页)
    public static final String THEME_SOURCE = "theme_source"; //0 跟随系统取色 1 自定义种子色
    public static final String THEME_MODE = "theme_mode"; //0 跟随系统 1 浅色 2 深色
    public static final String THEME_SEED = "theme_seed"; //自定义种子色 ARGB
    public static final String THEME_PALETTE_STYLE = "theme_palette_style"; //PaletteStyle 枚举名
    // 液态玻璃(2026-09-13,照搬 示例文件/android):blur 需 API 31+,lens 需 API 33+,低版本回退 M3 栏。
    // 2026-09-16 用户定稿:无总开关,两个作用域开关各自控制(默认都开);blur/distortion 两档参数共用。
    // 原总开关键 `liquid_glass_enabled` 已删除(存量值不再读取,无迁移)
    public static final String LIQUID_GLASS_NAVBAR = "liquid_glass_navbar"; //底部导航栏是否玻璃
    public static final String LIQUID_GLASS_CONTROLS = "liquid_glass_controls"; //应用控件(顶栏等)是否玻璃
    public static final String LIQUID_GLASS_BLUR = "liquid_glass_blur"; //模糊强度 dp(0~40,默认 20)
    public static final String LIQUID_GLASS_DISTORTION = "liquid_glass_distortion"; //折射强度 dp(0~30,默认 30)
    // 迅雷下载库的伪造设备标识(2026-09-15 由独立 SharedPreferences `rand_thunder_id` 迁入 KV,该 SP 与其 xml 已废弃)
    public static final String THUNDER_IMEI = "thunder_imei";
    public static final String THUNDER_MAC = "thunder_mac";
    // 本地源目录授权(SAF OpenDocumentTree,持久授权):ArrayList<String>,每项为目录 tree uri 字符串。
    // 应用读不到源目录时(无「所有文件访问」)靠它让本地服务直接读原目录,源地址得以指向原目录而不复制
    public static final String LOCAL_SOURCE_TREES = "local_source_trees";
}
