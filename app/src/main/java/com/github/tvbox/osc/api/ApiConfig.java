package com.github.tvbox.osc.api;

import static com.github.tvbox.osc.util.RegexUtils.getPattern;

import android.app.Activity;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.Depot;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.bean.LiveSettingGroup;
import com.github.tvbox.osc.bean.LiveSettingItem;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.ProxyRule;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AES;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.M3u8;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PermissionHelper;
import com.github.tvbox.osc.util.Proxy;
import com.github.tvbox.osc.util.VideoParseRuler;
import com.github.tvbox.osc.util.live.TxtSubscribe;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.github.tvbox.osc.util.KV;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class ApiConfig {
    // volatile:DCL 单例必须(2026-09-12 修复 Bug)。首次构造可能发生在 AppBootstrap 的 IO 协程上,
    // 而主线程 Compose 同时也在调 get() —— 构造函数很重(clearLoader + loadDefaultConfig 解析大 JSON),
    // 无 volatile 时其他线程可能读到未完全初始化的实例
    private static volatile ApiConfig instance;
    private final LinkedHashMap<String, SourceBean> sourceBeanList;
    private SourceBean mHomeSource;
    private ParseBean mDefaultParse;
    private final List<LiveChannelGroup> liveChannelGroupList;
    private final List<ParseBean> parseBeanList;
    private List<String> vipParseFlags;
    private Map<String,String> myHosts;
    private List<IJKCode> ijkCodes;
    private String currentPlaySourceKey = "";
    private String loadedLiveConfigUrl = "";
    /** 直播设置「配置切换」组第 0 项的合成名称:代表"未单独配置直播源、跟随点播源" */
    public static final String LIVE_FOLLOW_ITEM_NAME = "跟随点播源";
    private String danmaku = "";
    private volatile String configLogo = ""; // 配置级头像(接口 JSON 顶层 "logo")

    public String getConfigLogo() {
        return configLogo == null ? "" : configLogo;
    }

    private final SourceBean emptyHome = new SourceBean();

    /** 爬虫装载:jar/js/py 加载器与 jar 下载链路 */
    private final SpiderLoader spiderLoader = new SpiderLoader();
    private final Gson gson;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService configLoadExecutor = Executors.newSingleThreadExecutor();

    private ApiConfig() {
        clearLoader();
        sourceBeanList = new LinkedHashMap<>();
        liveChannelGroupList = new ArrayList<>();
        parseBeanList = new ArrayList<>();
        searchSourceBeanList = new ArrayList<>();
        gson = new Gson();
        KV.put(HawkConfig.LIVE_GROUP_LIST,new JsonArray());
        loadDefaultConfig();
    }

    public static ApiConfig get() {
        if (instance == null) {
            synchronized (ApiConfig.class) {
                if (instance == null) {
                    instance = new ApiConfig();
                }
            }
        }
        return instance;
    }

    public static String FindResult(String json, String configKey) {
        String content = json;
        try {
            if (AES.isJson(content)) return content;
            Pattern pattern = getPattern("[A-Za-z0-9]{8}\\*\\*");
            Matcher matcher = pattern.matcher(content);
            if(matcher.find()){
                content=content.substring(content.indexOf(matcher.group()) + 10);
                content = new String(Base64.decode(content, Base64.DEFAULT));
            }
            content = content.trim();
            if (content.startsWith("2423")) {
                content = content.replaceAll("\\s+", "");
                String data = content.substring(content.indexOf("2324") + 4, content.length() - 26);
                content = new String(AES.toBytes(content)).toLowerCase();
                String key = AES.rightPadding(content.substring(content.indexOf("$#") + 2, content.indexOf("#$")), "0", 16);
                String iv = AES.rightPadding(content.substring(content.length() - 13), "0", 16);
                json = AES.CBC(data, key, iv);
            }else if (configKey !=null && !AES.isJson(content)) {
                json = AES.ECB(content, configKey);
            }
            else{
                json = content;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return json;
    }

    /** 本机服务基址:用 Supplier 传给解析器,保证只在 clan://localhost/ 地址上才求值 */
    private static String localFileBase() {
        return ControlManager.get().getAddress(true);
    }

    public void loadConfig(boolean useCache, LoadConfigCallback callback, Activity activity) {
        String apiUrl = KV.get(HawkConfig.API_URL, "");
        if (apiUrl.isEmpty()) {
            callback.error("-1");
            return;
        }
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/" + MD5.encode(apiUrl));
        if (useCache && cache.exists()) {
            try {
                String json = readConfigFile(cache);
                if (switchApiCollectionIfNeeded(apiUrl, json)) {
                    loadConfig(false, callback, activity);
                    return;
                }
                clearApiLinesIfUnmatched(apiUrl);
                parseJson(apiUrl, json);
                callback.success();
                return;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        ConfigParser.ConfigUrl resolved = ConfigParser.configUrl(apiUrl, ApiConfig::localFileBase);
        final String configUrl = resolved.url;
        final String configKey = resolved.key;

        fetchConfigAsync(apiUrl, configUrl, configKey, new ConfigFetchCallback() {
            @Override
            public void success(String json) {
                try {
//                            LOG.longI("echo-ConfigJson", json);
                    if (switchApiCollectionIfNeeded(apiUrl, json)) {
                        FileUtils.saveCache(cache,json);
                        loadConfig(false, callback, activity);
                        return;
                    }
                    clearApiLinesIfUnmatched(apiUrl);
                    parseJson(apiUrl, json);
                    FileUtils.saveCache(cache,json);
                    callback.success();
                } catch (Throwable th) {
                    th.printStackTrace();
                    callback.error("配置解析失败");
                }
            }

            @Override
            public void error(String error) {
                // 本地源无权限读不到文件时**不回落旧快照**:回落会让用户以为源正常、实则内容永不更新
                if (isLocalSourceUnreadable(apiUrl)) {
                    callback.error(LOCAL_SOURCE_UNREADABLE_MSG);
                    return;
                }
                // 文件已被删除/改名(2026-09-17)同理:回落快照只会显示删除前的旧内容,且快照重启/清缓存都不掉
                if (isLocalSourceMissing(apiUrl)) {
                    callback.error(LOCAL_SOURCE_MISSING_MSG);
                    return;
                }
                if (cache.exists()) {
                    try {
                        String json = readConfigFile(cache);
                        if (switchApiCollectionIfNeeded(apiUrl, json)) {
                            loadConfig(false, callback, activity);
                            return;
                        }
                        clearApiLinesIfUnmatched(apiUrl);
                        parseJson(apiUrl, json);
                        callback.success();
                        return;
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
                callback.error("拉取配置失败\n" + error);
            }
        });
    }

    /**
     * 实际生效的直播配置地址(2026-09-12 点播/直播拆分):
     * 独立直播源(LIVE_API_URL)优先;未单独配置时回落到当前点播源(API_URL)。
     * 直播侧全部走这个方法取地址,避免各处重复写"空则回落"的判断。
     */
    public static String getEffectiveLiveUrl() {
        String liveApiUrl = KV.get(HawkConfig.LIVE_API_URL, "");
        return TextUtils.isEmpty(liveApiUrl) ? KV.get(HawkConfig.API_URL, "") : liveApiUrl;
    }

    /**
     * 直播是否跟随点播源(2026-09-12 点播/直播拆分):
     * LIVE_API_URL 为空,或与 API_URL 相同 —— 后者是旧版"切源双写"留下的存量状态,语义与"跟随"等价,
     * 因此无需数据迁移:老用户升级后行为与升级前完全一致,且点播换源时直播会继续跟随。
     */
    public static boolean isLiveFollowVod() {
        String liveApiUrl = KV.get(HawkConfig.LIVE_API_URL, "");
        if (TextUtils.isEmpty(liveApiUrl)) {
            return true;
        }
        // 两者都非空才比较;API_URL 为空(未配置点播)时直播源独立存在,不算跟随
        return liveApiUrl.equals(KV.get(HawkConfig.API_URL, ""));
    }

    public void loadLiveConfig(boolean useCache, LoadConfigCallback callback) {
        String apiUrl = getEffectiveLiveUrl();
        if (apiUrl.isEmpty()) {
            callback.error("-1");
            return;
        }
        final String liveApiUrl = apiUrl;
        ConfigParser.ConfigUrl resolvedLive = ConfigParser.configUrl(liveApiUrl, ApiConfig::localFileBase);
        String liveApiConfigUrl = resolvedLive.url;
        final String liveConfigKey = resolvedLive.key;
        File live_cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/" + MD5.encode(liveApiUrl));
        LOG.i("echo-load live config "+liveApiUrl);
        if (useCache && live_cache.exists()) {
            try {
                String json = readConfigFile(live_cache);
                if (switchLiveApiCollectionIfNeeded(liveApiUrl, json)) {
                    loadLiveConfig(false, callback);
                    return;
                }
                clearLiveApiLinesIfUnmatched(liveApiUrl);
                parseLiveConfigContent(liveApiUrl, json);
                if (hasLiveConfigResult()) {
                    loadedLiveConfigUrl = liveApiUrl;
                    callback.success();
                    return;
                }
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        fetchConfigAsync(liveApiUrl, liveApiConfigUrl, liveConfigKey, new ConfigFetchCallback() {
            @Override
            public void success(String json) {
                try {
                    if (switchLiveApiCollectionIfNeeded(liveApiUrl, json)) {
                        FileUtils.saveCache(live_cache, json);
                        loadLiveConfig(false, callback);
                        return;
                    }
                    clearLiveApiLinesIfUnmatched(liveApiUrl);
                    parseLiveConfigContent(liveApiUrl, json);
                    if (!hasLiveConfigResult()) {
                        callback.error("直播配置解析失败");
                        return;
                    }
                    loadedLiveConfigUrl = liveApiUrl;
                    FileUtils.saveCache(live_cache, json);
                    callback.success();
                } catch (Throwable th) {
                    th.printStackTrace();
                    callback.error("直播配置解析失败");
                }
            }

            @Override
            public void error(String error) {
                if (isLocalSourceUnreadable(liveApiUrl)) {
                    callback.error(LOCAL_SOURCE_UNREADABLE_MSG);
                    return;
                }
                // 与点播同款(2026-09-17):本地直播源文件被删后不再静默回落旧快照
                if (isLocalSourceMissing(liveApiUrl)) {
                    callback.error(LOCAL_SOURCE_MISSING_MSG);
                    return;
                }
                if (live_cache.exists()) {
                    try {
                        String json = readConfigFile(live_cache);
                        if (switchLiveApiCollectionIfNeeded(liveApiUrl, json)) {
                            loadLiveConfig(false, callback);
                            return;
                        }
                        clearLiveApiLinesIfUnmatched(liveApiUrl);
                        parseLiveConfigContent(liveApiUrl, json);
                        if (hasLiveConfigResult()) {
                            loadedLiveConfigUrl = liveApiUrl;
                            callback.success();
                            return;
                        }
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
                callback.error("直播配置拉取失败");
            }
        });
    }

    /** 本地源文件不可读的提示(UI 直接展示) */
    private static final String LOCAL_SOURCE_UNREADABLE_MSG = "本地源文件读不到\n请开启「所有文件访问」后重试(或重新导入本地源)";

    /** 本地源文件已不存在的提示(UI 直接展示) */
    private static final String LOCAL_SOURCE_MISSING_MSG = "本地源文件已不存在\n可能已在文件管理器里被删除或改名,请重新导入本地源";

    /**
     * 本机文件源(`clan://localhost/` / `file://`)且当前无存储权限 ⇒ 本地服务按原始路径读必然 EACCES。
     * 把"静默回落 filesDir 旧快照"改成明确报错,否则用户改了本地 json 不生效且毫无提示(2026-09-16)。
     * ⚠️ 只判这两种"本机文件"形态:`clan://<ip>/…` 是局域网 TVBox 服务地址,与本地存储权限无关。
     */
    private static boolean isLocalSourceUnreadable(String apiUrl) {
        if (apiUrl == null) return false;
        if (!apiUrl.startsWith("clan://localhost/") && !apiUrl.startsWith("file://")) return false;
        return !PermissionHelper.isStorageGranted(App.getInstance());
    }

    /**
     * 本机文件源的**目标文件已不存在**(2026-09-17)。
     *
     * <p>为什么单独判:把本地 json 删掉后本地服务返回 "File ... not found",拉取失败会静默回落
     * filesDir 里的旧快照并报 success —— 与 {@link #isLocalSourceUnreadable} 要避免的情况完全一致
     * (用户以为源正常、实则内容永不更新),而快照在 getFilesDir 下,重启/清缓存都不会掉。
     *
     * <p>⚠️ 调用方**必须**先判 {@link #isLocalSourceUnreadable}:无存储权限时 File.exists 的结论不可信
     * (可能把"读不到"误报成"不存在")。只判解析得出真实路径的两种形态,`clan://<ip>/…` 无此概念。
     */
    private static boolean isLocalSourceMissing(String apiUrl) {
        String path = localSourcePath(apiUrl);
        return path != null && !new File(path).exists();
    }

    /** 本机文件源地址 → 真实路径(与 {@code RemoteServer} 的 `/file/` 同一映射);非本机形态或解析不出返回 null */
    private static String localSourcePath(String apiUrl) {
        if (apiUrl == null) return null;
        String url = apiUrl;
        int pk = url.indexOf(";pk;");
        if (pk >= 0) url = url.substring(0, pk);
        int query = url.indexOf('?');
        if (query >= 0) url = url.substring(0, query);
        if (url.startsWith("clan://localhost/")) {
            return Environment.getExternalStorageDirectory().getAbsolutePath()
                    + "/" + Uri.decode(url.substring("clan://localhost/".length()));
        }
        if (url.startsWith("file://")) {
            // 手写的地址可能带百分号编码(中文目录),解码后再判存在,避免把"存在"误报成"已删除"
            return Uri.decode(url.substring("file://".length()));
        }
        return null;
    }

    private boolean hasLiveConfigResult() {
        return liveChannelGroupList != null && !liveChannelGroupList.isEmpty();
    }

    public boolean shouldReloadLiveConfig() {
        String apiUrl = getEffectiveLiveUrl();
        return liveChannelGroupList == null || liveChannelGroupList.isEmpty() || !apiUrl.equals(loadedLiveConfigUrl);
    }

    /**
     * 作废已加载的直播内存态(2026-09-12 点播/直播拆分):点播源或直播源变更后调用,
     * 让直播页下次进入必然重载。只清内存与"已加载来源"标记,不动 KV 与磁盘缓存(离线仍可用缓存兜底)。
     */
    public void invalidateLiveConfig() {
        liveChannelGroupList.clear();
        loadedLiveConfigUrl = "";
    }

    public static String getLiveGroupIndexKey() {
        String liveApiUrl = KV.get(HawkConfig.LIVE_API_URL, "");
        if (liveApiUrl == null || liveApiUrl.length() == 0) {
            return HawkConfig.LIVE_GROUP_INDEX;
        }
        return HawkConfig.LIVE_GROUP_INDEX + "_" + liveApiUrl;
    }

    public static int getLiveGroupIndex() {
        return KV.get(getLiveGroupIndexKey(), 0);
    }

    public static void setLiveGroupIndex(int index) {
        KV.put(getLiveGroupIndexKey(), index);
    }

    public void loadJar(boolean useCache, String spider, LoadConfigCallback callback) {
        spiderLoader.loadJar(useCache, spider, callback);
    }

    private interface ConfigFetchCallback {
        void success(String body);

        void error(String error);
    }

    private void fetchConfigAsync(final String apiUrl, final String requestUrl, final String configKey, final ConfigFetchCallback callback) {
        configLoadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                String result = "";
                String error = "";
                okhttp3.Response response = null;
                try {
                    okhttp3.Request request = new okhttp3.Request.Builder()
                            .url(requestUrl)
                            .build();
                    okhttp3.OkHttpClient client = OkGoHelper.getDefaultClient();
                    if (client == null) client = com.github.catvod.net.OkHttp.client();
                    response = client.newCall(request).execute();
                    if (!response.isSuccessful()) {
                        error = "HTTP " + response.code();
                    } else if (response.body() == null) {
                        error = "empty body";
                    } else {
                        result = FindResult(response.body().string(), configKey);
                        if (apiUrl.startsWith("clan")) {
                            result = ConfigParser.clanContentFix(ConfigParser.clanToAddress(apiUrl, ApiConfig::localFileBase), result);
                        }
                        result = ConfigParser.fixContentPath(apiUrl, result, ApiConfig::localFileBase);
                    }
                } catch (Throwable th) {
                    error = th.getMessage();
                    if (TextUtils.isEmpty(error)) error = th.toString();
                } finally {
                    if (response != null) SpiderLoader.closeQuietly(response.body());
                }
                final String finalResult = result;
                final String finalError = error;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (TextUtils.isEmpty(finalError)) {
                            callback.success(finalResult);
                        } else {
                            callback.error(finalError);
                        }
                    }
                });
            }
        });
    }

    private void parseJson(String apiUrl, File f) throws Throwable {
        parseJson(apiUrl, readConfigFile(f));
    }

    private String readConfigFile(File f) throws Throwable {
        // BugReview #27:close 放 finally/try-with-resources,读失败时防 FD 泄漏
        try (BufferedReader bReader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String s = "";
            while ((s = bReader.readLine()) != null) {
                sb.append(s + "\n");
            }
            return sb.toString();
        }
    }

    private boolean switchApiCollectionIfNeeded(String apiUrl, String jsonStr) {
        ArrayList<String> apiLines = ConfigParser.parseApiCollection(jsonStr);
        if (apiLines.isEmpty()) {
            return false;
        }
        String firstApi = HistoryHelper.getApiLineUrl(apiLines.get(0));
        if (TextUtils.isEmpty(firstApi) || firstApi.equals(apiUrl)) {
            return false;
        }
        KV.put(HawkConfig.API_LINE_LIST, apiLines);
        KV.put(HawkConfig.API_LINE_SOURCE, apiUrl);
        KV.put(HawkConfig.API_URL, firstApi);
        HistoryHelper.setApiHistory(apiUrl);
        // 作废内存旧配置(2026-09-13):本方法把 API_URL 换成了合集里的首条线路,
        // 若该线路随后拉取失败,不先作废就会残留合集旧数据、首页继续显示旧内容
        invalidateVodConfig();
        String liveApiUrl = KV.get(HawkConfig.LIVE_API_URL, "");
        if (TextUtils.isEmpty(liveApiUrl) || liveApiUrl.equals(apiUrl)) {
            KV.put(HawkConfig.LIVE_API_URL, firstApi);
            HistoryHelper.setLiveApiHistory(firstApi);
            // 直播此时跟随点播(2026-09-21):点播换仓后直播源也被改写,
            // 旧的直播仓列表已不对应当前直播源,必须一起作废,否则「配置切换」会列出上一仓的子源
            HistoryHelper.clearLiveApiLineList();
        }
        return true;
    }

    /**
     * 直播源的"多仓"(仓库)分流(2026-09-21,对齐 FongMi 的 {@code LiveConfig.parseDepot})。
     *
     * <p>改前直播侧只认 {@code lives},仓地址(顶层只有 {@code urls})解析出空列表 ⇒ 报"直播配置解析失败"。
     *
     * <p>与点播 {@link #switchApiCollectionIfNeeded} 的两点差异:改的是 {@code LIVE_API_URL};
     * 跟随点播时把 {@code API_URL} 一起指向首仓(两地址不一致会被 {@link #isLiveFollowVod()} 判成已脱离跟随)。
     */
    private boolean switchLiveApiCollectionIfNeeded(String apiUrl, String jsonStr) {
        ArrayList<String> apiLines = ConfigParser.parseApiCollection(jsonStr);
        if (apiLines.isEmpty()) {
            return false;
        }
        String firstApi = HistoryHelper.getApiLineUrl(apiLines.get(0));
        if (TextUtils.isEmpty(firstApi) || firstApi.equals(apiUrl)) {
            return false;
        }
        KV.put(HawkConfig.LIVE_API_LINE_LIST, apiLines);
        KV.put(HawkConfig.LIVE_API_LINE_SOURCE, apiUrl);
        // 跟随态必须在改写 LIVE_API_URL 之前判定:isLiveFollowVod 靠"LIVE_API_URL 是否等于 API_URL"成立
        boolean followLive = isLiveFollowVod();
        KV.put(HawkConfig.LIVE_API_URL, firstApi);
        if (followLive) {
            KV.put(HawkConfig.API_URL, firstApi);
            HistoryHelper.setApiHistory(firstApi);
        }
        HistoryHelper.setLiveApiHistory(apiUrl);
        loadedLiveConfigUrl = "";
        clearLiveConfigResult();
        return true;
    }

    /** 与直播仓列表对不上号就清掉,免得「配置切换」继续列上一仓的子源;空地址(跟随态)不清 */
    private void clearLiveApiLinesIfUnmatched(String apiUrl) {
        if (TextUtils.isEmpty(apiUrl)) return;
        if (!HistoryHelper.isLiveApiLineUrl(apiUrl) && !HistoryHelper.isLiveApiLineSource(apiUrl)) {
            HistoryHelper.clearLiveApiLineList();
        }
    }

    /** 直播配置数据清场,不动 KV 与仓列表 —— 供"换仓后重新拉取"先丢弃旧结果用 */
    private void clearLiveConfigResult() {
        liveChannelGroupList.clear();
        spiderLoader.setLiveSpider("");
        spiderLoader.resetCurrentLiveSpider();
        initLiveSettings();
        KV.put(HawkConfig.LIVE_GROUP_LIST, new JsonArray());
    }

    private void resetConfigData() {
        clearSpiderCache();
        currentPlaySourceKey = "";
        configLogo = "";
        sourceBeanList.clear();
        liveChannelGroupList.clear();
        parseBeanList.clear();
        searchSourceBeanList = new ArrayList<>();
        KV.put(HawkConfig.LIVE_GROUP_LIST,new JsonArray());
    }

    /**
     * 清空全部配置(点播 + 直播)。
     * 2026-09-12 点播/直播拆分后,业务侧通常应改用 {@link #clearVodConfig()} / {@link #clearLiveConfig()} ——
     * 删空点播源不应连坐清掉用户单独配置的直播源。
     */
    public void clearConfig() {
        clearVodConfig();
        clearLiveConfig();
    }

    /**
     * 清空点播配置(2026-09-12,配置管理页删光点播源时调用):
     * 内存源数据与 KV 点播地址一并清空,回到「尚未配置订阅接口」的初始状态;
     * 调用方随后执行 AppBootstrap.retry() 即可让各页按未配置刷新(getHomeSourceBean 有 emptyHome 兜底)。
     * **独立直播源不受影响**;直播若处于跟随态则 LIVE_API_URL 一并置空(否则会变成指向旧点播源的陈旧快照)。
     */
    public void clearVodConfig() {
        boolean followLive = isLiveFollowVod(); // 必须在清空 API_URL 之前判定
        resetConfigData();
        mHomeSource = null;
        KV.put(HawkConfig.API_URL, "");
        KV.put(HawkConfig.HOME_API, "");
        HistoryHelper.clearApiLineList();
        if (followLive) {
            KV.put(HawkConfig.LIVE_API_URL, "");
            // 跟随态下直播源就是点播源(2026-09-21):点播仓列表已清,直播仓列表同理作废
            HistoryHelper.clearLiveApiLineList();
        }
        invalidateLiveConfig();
    }

    /** 清空独立直播源并回到「跟随点播源」(2026-09-12):点播配置完全不受影响 */
    public void clearLiveConfig() {
        KV.put(HawkConfig.LIVE_API_URL, "");
        // 仓列表跟着被清掉的直播源一起作废(2026-09-21):留着会在「配置切换」里列出已失效的子源
        HistoryHelper.clearLiveApiLineList();
        invalidateLiveConfig();
    }

    /**
     * 作废内存里的点播配置(**不**动 KV 地址,2026-09-13)。
     *
     * 存在的理由:[loadConfig] 失败时走的是 `callback.error(...)`,**根本不会调用 [parseJson]**,
     * 而清场动作 `resetConfigData()` 只在 parseJson 开头执行 —— 于是单例里的
     * `sourceBeanList` / `mHomeSource` / `parseBeanList` 全部保留着**上一个源**的数据,
     * 首页套用旧源继续正常显示与播放,可 KV 里的 `API_URL` 已经指向新源:
     * 表现就是"运行中用旧源、重启后才发现新源不可用"的状态不一致。
     *
     * 因此在**切换点播源之前**调用本方法:新源拉取成功会由 parseJson 重新填充;
     * 拉取失败时首页自然落到空态/未配置引导态,与「配置加载失败」弹窗一致,
     * 不会再拿旧源冒充新源(同类修复先例:2026-09-11「删空订阅列表仍用着被删的源」)。
     */
    public void invalidateVodConfig() {
        resetConfigData();
        mHomeSource = null;
        invalidateLiveConfig();
    }

    private void clearApiLinesIfUnmatched(String apiUrl) {
        ArrayList<String> apiLines = KV.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
        if (apiLines.isEmpty()) {
            return;
        }
        for (String apiLine : apiLines) {
            if (apiUrl.equals(HistoryHelper.getApiLineUrl(apiLine))) {
                return;
            }
        }
        HistoryHelper.clearApiLineList();
    }

    private void parseJson(String apiUrl, String jsonStr) {
        resetConfigData();
        LOG.i("echo-apiurl:" + apiUrl);
        JsonObject infoJson = gson.fromJson(jsonStr, JsonObject.class);
        // 配置级头像(2026-09-10):接口 JSON 顶层 "logo",胶囊头像的兜底来源(站点级 icon 优先)
        configLogo = DefaultConfig.safeJsonString(infoJson, "logo", "");
        // spider
        spiderLoader.setSpider(DefaultConfig.safeJsonString(infoJson, "spider", ""));
        spiderLoader.setJarCache(DefaultConfig.safeJsonString(infoJson, "jarCache", "true"));
        danmaku = DefaultConfig.safeJsonString(infoJson, "danmaku", "");
        // 远端站点源
        List<SourceBean> sites = ConfigParser.parseSites(infoJson);
        for (SourceBean sb : sites) {
            sourceBeanList.put(sb.getKey(), sb);
        }
        SourceBean firstSite = sites.isEmpty() ? null : sites.get(0);
        if (sourceBeanList != null && sourceBeanList.size() > 0) {
            String home = KV.get(HawkConfig.HOME_API, "");
            SourceBean sh = getSource(home);
            if (sh == null) {
                assert firstSite != null;
                setSourceBean(firstSite);
            }
            else
                setSourceBean(sh);
        }
        // 需要使用vip解析的flag
        vipParseFlags = DefaultConfig.safeJsonStringList(infoJson, "flags");
        // 解析地址
        parseBeanList.clear();
        if(infoJson.has("parses")){
            JsonArray parses = infoJson.get("parses").getAsJsonArray();
            for (JsonElement opt : parses) {
                JsonObject obj = (JsonObject) opt;
                ParseBean pb = new ParseBean();
                pb.setName(obj.get("name").getAsString().trim());
                pb.setUrl(obj.get("url").getAsString().trim());
                String ext = obj.has("ext") ? obj.get("ext").getAsJsonObject().toString() : "";
                pb.setExt(ext);
                pb.setType(DefaultConfig.safeJsonInt(obj, "type", 0));
                parseBeanList.add(pb);
            }
            if(!parseBeanList.isEmpty())addSuperParse();
        }
        // 获取默认解析
        if (parseBeanList != null && parseBeanList.size() > 0) {
            String defaultParse = KV.get(HawkConfig.DEFAULT_PARSE, "");
            if (!TextUtils.isEmpty(defaultParse))
                for (ParseBean pb : parseBeanList) {
                    if (pb.getName().equals(defaultParse))
                        setDefaultParse(pb);
                }
            if (mDefaultParse == null)
                setDefaultParse(parseBeanList.get(0));
        }

        // 直播源
        String live_api_url=KV.get(HawkConfig.LIVE_API_URL,"");
        if(live_api_url.isEmpty() || apiUrl.equals(live_api_url)){
            LOG.i("echo-load-config_live");
            initLiveSettings();
            if(infoJson.has("lives")){
                JsonArray lives_groups=infoJson.get("lives").getAsJsonArray();
                int live_group_index=getLiveGroupIndex();
                if(live_group_index>lives_groups.size()-1)live_group_index=0;
                KV.put(HawkConfig.LIVE_GROUP_LIST,lives_groups);
                //加载多源配置
                try {
                    liveSettingGroupList.get(5).setLiveSettingItems(ConfigParser.parseLiveSettingItems(lives_groups));
                } catch (Exception e) {
                    // 捕获任何可能发生的异常
                    e.printStackTrace();
                }

                JsonObject livesOBJ = lives_groups.get(live_group_index).getAsJsonObject();
                loadLiveApi(livesOBJ);
            }
        }

        myHosts = new HashMap<>();
        if (infoJson.has("hosts")) {
            myHosts = ConfigParser.parseHosts(infoJson.getAsJsonArray("hosts"));
        }

        loadProxyRules(infoJson);

        //video parse rule for host
        if (infoJson.has("rules")) {
            VideoParseRuler.clearRule();
            for(JsonElement oneHostRule : infoJson.getAsJsonArray("rules")) {
                JsonObject obj = (JsonObject) oneHostRule;
                //嗅探过滤规则
                if (obj.has("host")) {
                    String host = obj.get("host").getAsString();
                    if (obj.has("rule")) {
                        JsonArray ruleJsonArr = obj.getAsJsonArray("rule");
                        ArrayList<String> rule = new ArrayList<>();
                        for (JsonElement one : ruleJsonArr) {
                            String oneRule = one.getAsString();
                            rule.add(oneRule);
                        }
                        if (rule.size() > 0) {
                            VideoParseRuler.addHostRule(host, rule);
                        }
                    }
                    if (obj.has("filter")) {
                        JsonArray filterJsonArr = obj.getAsJsonArray("filter");
                        ArrayList<String> filter = new ArrayList<>();
                        for (JsonElement one : filterJsonArr) {
                            String oneFilter = one.getAsString();
                            filter.add(oneFilter);
                        }
                        if (filter.size() > 0) {
                            VideoParseRuler.addHostFilter(host, filter);
                        }
                    }
                }
                //广告过滤规则
                if (obj.has("hosts") && obj.has("regex")) {
                    ArrayList<String> rule = new ArrayList<>();
                    ArrayList<String> ads = new ArrayList<>();
                    JsonArray regexArray = obj.getAsJsonArray("regex");
                    for (JsonElement one : regexArray) {
                        String regex = one.getAsString();
                        if (M3u8.isAd(regex)) ads.add(regex);
                        else rule.add(regex);
                    }
                    JsonArray array = obj.getAsJsonArray("hosts");
                    for (JsonElement one : array) {
                        String host = one.getAsString();
                        VideoParseRuler.addHostRule(host, rule);
                        VideoParseRuler.addHostRegex(host, ads);
                    }
                }
                //嗅探脚本规则 如 click
                if (obj.has("hosts") && obj.has("script")) {
                    ArrayList<String> scripts = new ArrayList<>();
                    JsonArray scriptArray = obj.getAsJsonArray("script");
                    for (JsonElement one : scriptArray) {
                        String script = one.getAsString();
                        scripts.add(script);
                    }
                    JsonArray array = obj.getAsJsonArray("hosts");
                    for (JsonElement one : array) {
                        String host = one.getAsString();
                        VideoParseRuler.addHostScript(host, scripts);
                    }
                }
            }
        }

        if (infoJson.has("doh")) {
            // 接口可能把 doh 写成非数组(或格式异常):此时视为未提供,退回内置列表,不让整个配置加载挂掉
            String doh_json = "";
            try {
                doh_json = infoJson.getAsJsonArray("doh").toString();
            } catch (Exception e) {
                e.printStackTrace();
            }
            if(!KV.get(HawkConfig.DOH_JSON, "").equals(doh_json)){
                KV.put(HawkConfig.DOH_URL, 0);
                KV.put(HawkConfig.DOH_JSON,doh_json);
            }
        }else {
            KV.put(HawkConfig.DOH_JSON,"");
        }
        OkGoHelper.setDnsList();
        LOG.i("echo-api-config-----------load");
        //追加的广告拦截
        if(infoJson.has("ads")){
            for (JsonElement host : infoJson.getAsJsonArray("ads")) {
                if(!AdBlocker.hasHost(host.getAsString())){
                    AdBlocker.addAdHost(host.getAsString());
                }
            }
        }
    }

    private void loadDefaultConfig() {
        String defaultIJKADS="{\"ijk\":[{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"0\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"软解码\"},{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"1\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"硬解码\"}],\"ads\":[\"mimg.0c1q0l.cn\",\"www.googletagmanager.com\",\"www.google-analytics.com\",\"mc.usihnbcq.cn\",\"mg.g1mm3d.cn\",\"mscs.svaeuzh.cn\",\"cnzz.hhttm.top\",\"tp.vinuxhome.com\",\"cnzz.mmstat.com\",\"www.baihuillq.com\",\"s23.cnzz.com\",\"z3.cnzz.com\",\"c.cnzz.com\",\"stj.v1vo.top\",\"z12.cnzz.com\",\"img.mosflower.cn\",\"tips.gamevvip.com\",\"ehwe.yhdtns.com\",\"xdn.cqqc3.com\",\"www.jixunkyy.cn\",\"sp.chemacid.cn\",\"hm.baidu.com\",\"s9.cnzz.com\",\"z6.cnzz.com\",\"um.cavuc.com\",\"mav.mavuz.com\",\"wofwk.aoidf3.com\",\"z5.cnzz.com\",\"xc.hubeijieshikj.cn\",\"tj.tianwenhu.com\",\"xg.gars57.cn\",\"k.jinxiuzhilv.com\",\"cdn.bootcss.com\",\"ppl.xunzhuo123.com\",\"xomk.jiangjunmh.top\",\"img.xunzhuo123.com\",\"z1.cnzz.com\",\"s13.cnzz.com\",\"xg.huataisangao.cn\",\"z7.cnzz.com\",\"xg.huataisangao.cn\",\"z2.cnzz.com\",\"s96.cnzz.com\",\"q11.cnzz.com\",\"thy.dacedsfa.cn\",\"xg.whsbpw.cn\",\"s19.cnzz.com\",\"z8.cnzz.com\",\"s4.cnzz.com\",\"f5w.as12df.top\",\"ae01.alicdn.com\",\"www.92424.cn\",\"k.wudejia.com\",\"vivovip.mmszxc.top\",\"qiu.xixiqiu.com\",\"cdnjs.hnfenxun.com\",\"cms.qdwght.com\"]}";
        JsonObject defaultJson=gson.fromJson(defaultIJKADS, JsonObject.class);
        // 广告地址
        if(AdBlocker.isEmpty()){
            //默认广告拦截
            for (JsonElement host : defaultJson.getAsJsonArray("ads")) {
                AdBlocker.addAdHost(host.getAsString());
            }
        }
        // IJK解码配置
        if(ijkCodes==null){
            ijkCodes = new ArrayList<>();
            boolean foundOldSelect = false;
            String ijkCodec = KV.get(HawkConfig.IJK_CODEC, "硬解码");
            JsonArray ijkJsonArray = defaultJson.get("ijk").getAsJsonArray();
            for (JsonElement opt : ijkJsonArray) {
                JsonObject obj = (JsonObject) opt;
                String name = obj.get("group").getAsString();
                LinkedHashMap<String, String> baseOpt = new LinkedHashMap<>();
                for (JsonElement cfg : obj.get("options").getAsJsonArray()) {
                    JsonObject cObj = (JsonObject) cfg;
                    String key = cObj.get("category").getAsString() + "|" + cObj.get("name").getAsString();
                    String val = cObj.get("value").getAsString();
                    baseOpt.put(key, val);
                }
                IJKCode codec = new IJKCode();
                codec.setName(name);
                codec.setOption(baseOpt);
                if (name.equals(ijkCodec) || TextUtils.isEmpty(ijkCodec)) {
                    codec.selected(true);
                    ijkCodec = name;
                    foundOldSelect = true;
                } else {
                    codec.selected(false);
                }
                ijkCodes.add(codec);
            }
            if (!foundOldSelect && ijkCodes.size() > 0) {
                ijkCodes.get(0).selected(true);
            }
        }
        LOG.i("echo-default-config-----------load");
    }
    private void parseLiveConfigContent(String apiUrl, File f) throws Throwable {
        // BugReview #27:close 放 finally/try-with-resources,读失败时防 FD 泄漏
        String content;
        try (BufferedReader bReader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String s = "";
            while ((s = bReader.readLine()) != null) {
                sb.append(s + "\n");
            }
            content = sb.toString();
        }
        parseLiveConfigContent(apiUrl, content);
    }

    private void parseLiveConfigContent(String apiUrl, String content) {
        String jsonContent = ConfigParser.trimJsonObject(content);
        if (!TextUtils.isEmpty(jsonContent)) {
            try {
                JsonObject infoJson = gson.fromJson(jsonContent, JsonObject.class);
                if (infoJson != null && infoJson.has("lives")) {
                    parseLiveJson(apiUrl, jsonContent);
                    return;
                }
            } catch (Throwable ignored) {
                LOG.d("ApiConfig", "live config json parse failed, fallback to text");
            }
        }
        if (ConfigParser.isLiveJsonContent(content)) {
            parseLiveJson(apiUrl, jsonContent);
        } else {
            parseLiveText(apiUrl, content);
        }
    }

    private void parseLiveText(String apiUrl, String content) {
        liveChannelGroupList.clear();
        spiderLoader.setLiveSpider("");
        spiderLoader.resetCurrentLiveSpider();
        initLiveSettings();
        KV.put(HawkConfig.LIVE_GROUP_LIST, new JsonArray());
        KV.put(HawkConfig.EPG_URL, ConfigParser.extractLiveTextEpg(content));
        KV.put(HawkConfig.LIVE_PLAY_TYPE, KV.get(HawkConfig.PLAY_TYPE, 2));
        KV.put(HawkConfig.LIVE_WEB_HEADER, null);
        JsonArray livesArray = TxtSubscribe.parseToJsonArray(content);
        loadLives(livesArray);
        LOG.i("echo-live-text-config-----------load:" + apiUrl);
    }

    private void parseLiveJson(String apiUrl, String jsonStr) {
        liveChannelGroupList.clear();
        JsonObject infoJson = gson.fromJson(jsonStr, JsonObject.class);
        // spider
        spiderLoader.setLiveSpider(DefaultConfig.safeJsonString(infoJson, "spider", ""));
        // 直播源
        initLiveSettings();
        if(infoJson.has("lives")){
            JsonArray lives_groups=infoJson.get("lives").getAsJsonArray();

            int live_group_index=getLiveGroupIndex();
            if(live_group_index>lives_groups.size()-1)live_group_index=0;
            KV.put(HawkConfig.LIVE_GROUP_LIST,lives_groups);
            //加载多源配置
            try {
                liveSettingGroupList.get(5).setLiveSettingItems(ConfigParser.parseLiveSettingItems(lives_groups));
            } catch (Exception e) {
                // 捕获任何可能发生的异常
                e.printStackTrace();
            }

            JsonObject livesOBJ = lives_groups.get(live_group_index).getAsJsonObject();
            loadLiveApi(livesOBJ);
        }

        myHosts = new HashMap<>();
        if (infoJson.has("hosts")) {
            myHosts = ConfigParser.parseHosts(infoJson.getAsJsonArray("hosts"));
        }
        LOG.i("echo-api-live-config-----------load");
    }

    private final List<LiveSettingGroup> liveSettingGroupList = new ArrayList<>();
    private void initLiveSettings() {
        ArrayList<String> groupNames = new ArrayList<>(Arrays.asList("线路选择", "画面比例", "播放解码", "超时换源", "偏好设置", "多源切换", "配置切换"));
        ArrayList<ArrayList<String>> itemsArrayList = new ArrayList<>();
        ArrayList<String> sourceItems = new ArrayList<>();
        ArrayList<String> scaleItems = new ArrayList<>(Arrays.asList("默认", "16:9", "4:3", "填充", "原始", "裁剪"));
        ArrayList<String> playerDecoderItems = new ArrayList<>(Arrays.asList("ijk硬解", "ijk软解", "exo"));
        ArrayList<String> timeoutItems = new ArrayList<>(Arrays.asList("5s", "10s", "15s", "20s", "25s", "30s"));
        ArrayList<String> personalSettingItems = new ArrayList<>(Arrays.asList("显示时间", "显示网速", "换台反转", "跨选分类"));
        ArrayList<String> yumItems = new ArrayList<>();
        ArrayList<String> liveApiHistoryItems = new ArrayList<>();

        itemsArrayList.add(sourceItems);
        itemsArrayList.add(scaleItems);
        itemsArrayList.add(playerDecoderItems);
        itemsArrayList.add(timeoutItems);
        itemsArrayList.add(personalSettingItems);
        itemsArrayList.add(yumItems);
        itemsArrayList.add(liveApiHistoryItems);

        liveSettingGroupList.clear();
        for (int i = 0; i < groupNames.size(); i++) {
            LiveSettingGroup liveSettingGroup = new LiveSettingGroup();
            ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
            liveSettingGroup.setGroupIndex(i);
            liveSettingGroup.setGroupName(groupNames.get(i));
            for (int j = 0; j < itemsArrayList.get(i).size(); j++) {
                LiveSettingItem liveSettingItem = new LiveSettingItem();
                liveSettingItem.setItemIndex(j);
                liveSettingItem.setItemName(itemsArrayList.get(i).get(j));
                liveSettingItemList.add(liveSettingItem);
            }
            liveSettingGroup.setLiveSettingItems(liveSettingItemList);
            liveSettingGroupList.add(liveSettingGroup);
        }
        refreshLiveApiHistoryItems();
    }

    public List<LiveSettingGroup> getLiveSettingGroupList() {
        return liveSettingGroupList;
    }

    /**
     * 刷新直播设置「配置切换」组(第 6 组):第 0 项固定为合成的「跟随点播源」(无条件占位,避免下标漂移),
     * 其后为候选项 —— 第 i 项的 itemIndex = i + 1。
     *
     * <p>2026-09-21 多仓:当前直播源来自仓列表时,第 1 项起改列**仓里的子源**而不是配置历史。
     */
    public void refreshLiveApiHistoryItems() {
        if (liveSettingGroupList.size() < 7) return;
        ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
        LiveSettingItem followItem = new LiveSettingItem();
        followItem.setItemIndex(0);
        followItem.setItemName(LIVE_FOLLOW_ITEM_NAME);
        liveSettingItemList.add(followItem);
        ArrayList<String> entries = getLiveConfigEntries();
        for (int i = 0; i < entries.size(); i++) {
            LiveSettingItem liveSettingItem = new LiveSettingItem();
            liveSettingItem.setItemIndex(i + 1);
            liveSettingItem.setItemName(HistoryHelper.getApiLineName(entries.get(i)));
            liveSettingItemList.add(liveSettingItem);
        }
        liveSettingGroupList.get(6).setLiveSettingItems(liveSettingItemList);
    }

    /** 「配置切换」当前列的是仓列表还是配置历史 —— UI 点击/删除时据此取值 */
    public boolean isLiveApiLineMode() {
        return HistoryHelper.isLiveApiLineUrl(KV.get(HawkConfig.LIVE_API_URL, ""));
    }

    /** 「配置切换」第 1 项起的条目:仓模式给仓列表,否则给配置历史(与上面刷新用的是同一份) */
    public ArrayList<String> getLiveConfigEntries() {
        return HistoryHelper.isLiveApiLineUrl(KV.get(HawkConfig.LIVE_API_URL, ""))
                ? HistoryHelper.getLiveApiLines()
                : KV.get(HawkConfig.LIVE_API_HISTORY, new ArrayList<String>());
    }

    /**
     * 同 {@link #getLiveConfigEntries()},但剥成纯地址列表。
     *
     * <p>条目是 {@code "名字\t链接"} 的行,而选中判定要比对地址 —— 直接拿整行去 indexOf 永远匹配不上
     * (表现为「配置切换」当前项不高亮)。
     */
    public ArrayList<String> getLiveConfigUrls() {
        ArrayList<String> urls = new ArrayList<>();
        for (String entry : getLiveConfigEntries()) {
            String url = HistoryHelper.getApiLineUrl(entry);
            if (!TextUtils.isEmpty(url)) urls.add(url);
        }
        return urls;
    }

    /** 「配置切换」组第 {@code position} 项对应的直播源地址(第 0 项是「跟随点播源」,返回空串) */
    public String getLiveApiHistoryUrl(int position) {
        ArrayList<String> urls = getLiveConfigUrls();
        int index = position - 1;
        if (index < 0 || index >= urls.size()) return "";
        return urls.get(index);
    }

    public void loadLives(JsonArray livesArray) {
        liveChannelGroupList.clear();
        int groupIndex = 0;
        int channelIndex = 0;
        int channelNum = 0;
        for (JsonElement groupElement : livesArray) {
            LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
            liveChannelGroup.setLiveChannels(new ArrayList<LiveChannelItem>());
            liveChannelGroup.setGroupIndex(groupIndex++);
            String groupName = ((JsonObject) groupElement).get("group").getAsString().trim();
            String[] splitGroupName = groupName.split("_", 2);
            liveChannelGroup.setGroupName(splitGroupName[0]);
            if (splitGroupName.length > 1)
                liveChannelGroup.setGroupPassword(splitGroupName[1]);
            else
                liveChannelGroup.setGroupPassword("");
            channelIndex = 0;
            for (JsonElement channelElement : ((JsonObject) groupElement).get("channels").getAsJsonArray()) {
                JsonObject obj = (JsonObject) channelElement;
                LiveChannelItem liveChannelItem = new LiveChannelItem();
                liveChannelItem.setChannelName(obj.get("name").getAsString().trim());
                liveChannelItem.setChannelLogo(DefaultConfig.safeJsonString(obj, "logo", ""));
                liveChannelItem.setChannelEpg(DefaultConfig.safeJsonString(obj, "epg", ""));
                liveChannelItem.setChannelUa(DefaultConfig.safeJsonString(obj, "ua", ""));
                liveChannelItem.setChannelClick(DefaultConfig.safeJsonString(obj, "click", ""));
                liveChannelItem.setChannelFormat(DefaultConfig.safeJsonString(obj, "format", ""));
                liveChannelItem.setChannelOrigin(DefaultConfig.safeJsonString(obj, "origin", ""));
                liveChannelItem.setChannelReferer(DefaultConfig.safeJsonString(obj, "referer", ""));
                liveChannelItem.setChannelTvgId(DefaultConfig.safeJsonString(obj, "tvg-id", ""));
                liveChannelItem.setChannelTvgName(DefaultConfig.safeJsonString(obj, "tvg-name", ""));
                if (obj.has("parse")) {
                    try {
                        liveChannelItem.setChannelParse(obj.get("parse").getAsInt());
                    } catch (Throwable ignored) {
                        LOG.d("ApiConfig", "channel parse flag not an int, use default");
                    }
                }
                if (obj.has("catchup")) {
                    JsonObject catchupObj = new JsonObject();
                    if (obj.get("catchup").isJsonObject()) {
                        catchupObj = obj.getAsJsonObject("catchup");
                    } else {
                        catchupObj.addProperty("type", obj.get("catchup").getAsString());
                        if (obj.has("catchup-source")) catchupObj.addProperty("source", obj.get("catchup-source").getAsString());
                        if (obj.has("catchup-replace")) catchupObj.addProperty("replace", obj.get("catchup-replace").getAsString());
                    }
                    liveChannelItem.setChannelCatchup(catchupObj);
                }
                if (obj.has("header") && obj.get("header").isJsonObject()) {
                    JsonObject headerObj = obj.getAsJsonObject("header");
                    HashMap<String, String> channelHeader = new HashMap<>();
                    for (Map.Entry<String, JsonElement> entry : headerObj.entrySet()) {
                        channelHeader.put(entry.getKey(), entry.getValue().getAsString());
                    }
                    liveChannelItem.setChannelHeader(channelHeader);
                }
                ArrayList<String> urls = DefaultConfig.safeJsonStringList(obj, "urls");
                ArrayList<String> sourceNames = new ArrayList<>();
                ArrayList<String> sourceUrls = new ArrayList<>();
                int sourceIndex = 1;
                for (String url : urls) {
                    String[] splitText = url.split("\\$", 2);
                    sourceUrls.add(splitText[0]);
                    if (splitText.length > 1)
                        sourceNames.add(splitText[1]);
                    else
                        sourceNames.add("源" + Integer.toString(sourceIndex));
                    sourceIndex++;
                }
                liveChannelItem.setChannelSourceNames(sourceNames);
                liveChannelItem.setChannelUrls(sourceUrls);
                if (mergeLiveChannel(liveChannelGroup.getLiveChannels(), liveChannelItem)) {
                    liveChannelItem.setChannelIndex(channelIndex++);
                    liveChannelItem.setChannelNum(++channelNum);
                }
            }
            liveChannelGroupList.add(liveChannelGroup);
        }
    }

    private boolean mergeLiveChannel(ArrayList<LiveChannelItem> channelItems, LiveChannelItem newItem) {
        LiveChannelItem oldItem = findLiveChannel(channelItems, newItem.getChannelName());
        if (oldItem == null) {
            channelItems.add(newItem);
            return true;
        }
        mergeLiveChannelUrls(oldItem, newItem);
        return false;
    }

    private LiveChannelItem findLiveChannel(ArrayList<LiveChannelItem> channelItems, String channelName) {
        for (LiveChannelItem item : channelItems) {
            if (channelName != null && channelName.equals(item.getChannelName())) return item;
        }
        return null;
    }

    private void mergeLiveChannelUrls(LiveChannelItem oldItem, LiveChannelItem newItem) {
        ArrayList<String> oldUrls = oldItem.getChannelUrls();
        ArrayList<String> oldSourceNames = oldItem.getChannelSourceNames();
        if (oldUrls == null) {
            oldUrls = new ArrayList<>();
            oldItem.setChannelUrls(oldUrls);
        }
        if (oldSourceNames == null) {
            oldSourceNames = new ArrayList<>();
            oldItem.setChannelSourceNames(oldSourceNames);
        }
        while (oldSourceNames.size() < oldUrls.size()) {
            oldSourceNames.add("源" + Integer.toString(oldSourceNames.size() + 1));
        }
        ArrayList<String> newUrls = newItem.getChannelUrls();
        ArrayList<String> newSourceNames = newItem.getChannelSourceNames();
        if (newUrls == null) return;
        for (int i = 0; i < newUrls.size(); i++) {
            String url = newUrls.get(i);
            if (oldUrls.contains(url)) continue;
            oldUrls.add(url);
            if (newSourceNames != null && i < newSourceNames.size()) {
                oldSourceNames.add(newSourceNames.get(i));
            } else {
                oldSourceNames.add("源" + Integer.toString(oldSourceNames.size() + 1));
            }
        }
        oldItem.setChannelUrls(oldUrls);
        oldItem.setChannelSourceNames(oldSourceNames);
    }

    public void loadLiveApi(JsonObject livesOBJ) {
        try {
            LOG.i("echo-loadLiveApi");
            liveChannelGroupList.clear();
            spiderLoader.resetCurrentLiveSpider();
            String lives = livesOBJ.toString();
            int index = lives.indexOf("proxy://");
            String url;
            if (index != -1) {
                int endIndex = lives.lastIndexOf("\"");
                url = lives.substring(index, endIndex);
                url = DefaultConfig.checkReplaceProxy(url);
                String extUrl = Uri.parse(url).getQueryParameter("ext");
                if (extUrl != null && !extUrl.isEmpty()) {
                    String extUrlFix;
                    if(extUrl.startsWith("http") || extUrl.startsWith("clan://")){
                        extUrlFix = extUrl;
                    }else {
                        extUrlFix = new String(Base64.decode(extUrl, Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                    }
                    extUrlFix = Base64.encodeToString(extUrlFix.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
                    url = url.replace(extUrl, extUrlFix);
                }
            } else {
                String api = livesOBJ.has("api") ? livesOBJ.get("api").getAsString().trim() : "";
                String type = livesOBJ.has("type") ? livesOBJ.get("type").getAsString() : (SpiderLoader.isLiveSpiderApi(api) ? "3" : "0");
                if(type.equals("0") || type.equals("3")){
                    url = livesOBJ.has("url")?livesOBJ.get("url").getAsString():"";
                    if(url.isEmpty())url=api;
                    LOG.i("echo-liveurl"+url);
                    if(!url.startsWith("http://127.0.0.1")){
                        if(url.startsWith("http")){
                            url = Base64.encodeToString(url.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
                        }
                        url ="http://127.0.0.1:9978/proxy?do=live&type=txt&ext="+url;
                    }
                    if(type.equals("3")){
                        String jarUrl = livesOBJ.has("jar")?livesOBJ.get("jar").getAsString().trim():"";
                        spiderLoader.loadLiveSpider(api, jarUrl, livesOBJ);
                    }
                }else {
                    liveChannelGroupList.clear();
                    return;
                }
            }
            //设置epg
            if(livesOBJ.has("epg")){
                String epg =livesOBJ.get("epg").getAsString();
                KV.put(HawkConfig.EPG_URL,epg);
            }else {
                KV.put(HawkConfig.EPG_URL,"");
            }
            //直播播放器类型
            if(livesOBJ.has("playerType")){
                String livePlayType =livesOBJ.get("playerType").getAsString();
                KV.put(HawkConfig.LIVE_PLAY_TYPE,livePlayType);
            }else {
                KV.put(HawkConfig.LIVE_PLAY_TYPE,KV.get(HawkConfig.PLAY_TYPE, 2));
            }
            //设置UA
            if(livesOBJ.has("timeout")){
                int timeout = Math.max(5, Math.min(30, livesOBJ.get("timeout").getAsInt()));
                KV.put(HawkConfig.LIVE_CONNECT_TIMEOUT, (timeout + 4) / 5 - 1);
            }
            if(livesOBJ.has("header")) {
                JsonObject headerObj = livesOBJ.getAsJsonObject("header");
                HashMap<String, String> liveHeader = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry : headerObj.entrySet()) {
                    liveHeader.put(entry.getKey(), entry.getValue().getAsString());
                }
                KV.put(HawkConfig.LIVE_WEB_HEADER, liveHeader);
            } else if(livesOBJ.has("ua")) {
                String ua = livesOBJ.get("ua").getAsString();
                HashMap<String,String> liveHeader = new HashMap<>();
                liveHeader.put("User-Agent", ua);
                KV.put(HawkConfig.LIVE_WEB_HEADER, liveHeader);
            }else {
                KV.put(HawkConfig.LIVE_WEB_HEADER,null);
            }
            LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
            liveChannelGroup.setGroupName(url);
            liveChannelGroupList.clear();
            liveChannelGroupList.add(liveChannelGroup);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    public void setLiveJar(String liveJar) {
        spiderLoader.setLiveJar(liveJar);
    }

    public String getSpider() {
        return spiderLoader.getSpider();
    }

    public String getDanmaku() {
        return danmaku == null ? "" : danmaku;
    }

    public Spider getCSP(SourceBean sourceBean) {
        return spiderLoader.getCSP(sourceBean);
    }

    public void warmSearchSpiders() {
        final ArrayList<SourceBean> sources = new ArrayList<>(sourceBeanList.values());
        final SourceBean home = getHomeSourceBean();
        final Set<String> sharedSpiderApis = new HashSet<>();
        Set<String> spiderApis = new HashSet<>();
        for (SourceBean source : sources) {
            if (source == null || source.getType() != 3) continue;
            String spiderApiKey = source.getJar() + "|" + source.getApi();
            if (!spiderApis.add(spiderApiKey)) sharedSpiderApis.add(spiderApiKey);
        }
        configLoadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                LOG.i("echo-warm-spider start");
                int eligibleCount = 0;
                for (SourceBean source : sources) {
                    if (source == null || source.getType() != 3 || !source.isSearchable()) continue;
                    if (home != null && TextUtils.equals(home.getKey(), source.getKey())) continue;
                    // 同类 Spider 可能通过静态状态保存 ext，不能在后台预热时交替初始化。
                    if (sharedSpiderApis.contains(source.getJar() + "|" + source.getApi())) continue;
                    if (eligibleCount >= 10) break;
                    eligibleCount++;
                    String warmKey = source.getKey() + "|" + source.getApi() + "|" + source.getJar() + "|" + source.getExt();
                    if (!spiderLoader.markWarmed(warmKey)) continue;
                    try {
                        LOG.i("echo-warm-spider load:" + warmKey);
                        getCSP(source);
                    } catch (Throwable th) {
                        LOG.e("echo-warm-search-spider-error " + source.getKey() + ":" + th.getMessage());
                    }
                }
            }
        });
    }

    public Spider getPyCSP(String url) {
        return spiderLoader.getPyCSP(url);
    }

    public Spider getJsCSP(String url) {
        return spiderLoader.getJsCSP(url);
    }

    public Spider getLiveCSP(String url) {
        return spiderLoader.getLiveCSP(url);
    }

    public void searchDanmuUi(String name, String episode, boolean longClick) {
        spiderLoader.searchDanmuUi(name, episode, longClick);
    }

    public boolean hasDanmuSearchUi() {
        return spiderLoader.hasDanmuSearchUi();
    }

    public int getLiveConnectTimeoutSeconds() {
        return (KV.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1) + 1) * 5;
    }

    public Object[] proxyLocal(Map<String, String> param) {
        SourceBean source = getCurrentProxySource(param);
        String api = source.getApi();

        String siteKey = param.get("siteKey");
        String action = param.get("do");

        boolean isJs = "js".equals(action);
        boolean isPy = "py".equals(action);
        boolean isLive = KV.get(HawkConfig.PLAYER_IS_LIVE, false);
        boolean isApiJs = api.contains(".js");
        boolean isApiPy = api.contains(".py");

        boolean canUseType3 = !TextUtils.isEmpty(siteKey)
                && source.getType() == 3
                && !isJs
                && !isPy
                && !isLive
                && !isApiJs
                && !isApiPy;

        if (canUseType3) {
            try {
                Spider spider = getCSP(source);

                Object[] result = spider.proxy(param);
                if (result != null) return result;

                result = spiderLoader.proxyInvokeJar(param);
                if (result != null) return result;

                result = proxyDirect(param);
                if (result != null) return result;

                return null;
            } catch (Throwable th) {
                LOG.e("echo-proxy siteKey error: " + th.getMessage());
                return null;
            }
        }

        if (isJs) {
            return spiderLoader.proxyInvokeJs(param);
        }

        if (isLive) {
            String liveApi = spiderLoader.getCurrentLiveSpider() != null ? spiderLoader.getCurrentLiveSpider() : "";

            if (liveApi.contains(".py")) {
                return spiderLoader.proxyInvokePy(param, spiderLoader.getCurrentLivePyKey());
            }
            if (liveApi.contains(".js")) {
                return spiderLoader.proxyInvokeJs(param);
            }
            return spiderLoader.proxyInvokeJar(param);
        }

        if (isPy) {
            return spiderLoader.proxyInvokePy(param, getCurrentPyKey());
        }

        if (isApiPy) {
            return spiderLoader.proxyInvokePy(param, getCurrentPyKey());
        }

        return spiderLoader.proxyInvokeJar(param);
    }

    private Object[] proxyDirect(Map<String, String> param) {
        try {
            String url = param.get("url");
            if (TextUtils.isEmpty(url)) return null;
            url = URLDecoder.decode(url, "UTF-8");
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null;
            if (!DefaultConfig.isVideoFormat(url)) return null;
            if (url.contains(".m3u8")) {
                param.put("url", url);
                param.put("go", "live");
                param.put("type", "m3u8");
                return Proxy.itv(param);
            }
            return null;
        } catch (Throwable th) {
            LOG.e("echo-proxy direct fallback error: " + th.getMessage());
            return null;
        }
    }

    private SourceBean getCurrentProxySource(Map<String, String> param) {
        String siteKey = param.get("siteKey");
        if (TextUtils.isEmpty(siteKey)) {
            siteKey = currentPlaySourceKey;
            if (!TextUtils.isEmpty(siteKey)) param.put("siteKey", siteKey);
        }
        SourceBean sourceBean = TextUtils.isEmpty(siteKey) ? null : getSource(siteKey);
        return sourceBean == null ? ApiConfig.get().getHomeSourceBean() : sourceBean;
    }

    public void setCurrentPlaySourceKey(String sourceKey) {
        currentPlaySourceKey = sourceKey == null ? "" : sourceKey;
    }

    private String getCurrentPyKey() {
        SourceBean sourceBean = getCurrentProxySource(new HashMap<String, String>());
        if (sourceBean.getApi().contains(".py")) {
            if (!sourceBean.getKey().equals(spiderLoader.getCurrentPyKey())) {
                spiderLoader.setCurrentPyKey(sourceBean.getKey());
                spiderLoader.pySpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt());
            }
            return spiderLoader.getCurrentPyKey();
        }
        return spiderLoader.getCurrentPyKey();
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        return spiderLoader.jsonExt(key, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        return spiderLoader.jsonExtMix(flag, key, name, jxs, url);
    }

    public interface LoadConfigCallback {
        void success();

        void error(String msg);
        void notice(String msg);
    }

    public interface FastParseCallback {
        void success(boolean parse, String url, Map<String, String> header);

        void fail(int code, String msg);
    }

    public SourceBean getSource(String key) {
        if (!sourceBeanList.containsKey(key)) {
            if ("push_agent".equals(key)) {
                SourceBean sourceBean = new SourceBean();
                sourceBean.setKey("push_agent");
                sourceBean.setName("推送");
                sourceBean.setType(-1);
                return sourceBean;
            }
            return null;
        }
        return sourceBeanList.get(key);
    }

    public void setSourceBean(SourceBean sourceBean) {
        this.mHomeSource = sourceBean;
        KV.put(HawkConfig.HOME_API, sourceBean.getKey());
    }

    public void setDefaultParse(ParseBean parseBean) {
        if (this.mDefaultParse != null)
            this.mDefaultParse.setDefault(false);
        this.mDefaultParse = parseBean;
        KV.put(HawkConfig.DEFAULT_PARSE, parseBean.getName());
        parseBean.setDefault(true);
    }

    public ParseBean getDefaultParse() {
        return mDefaultParse;
    }

    public List<SourceBean> getSourceBeanList() {
        return new ArrayList<>(sourceBeanList.values());
    }
    public List<SourceBean> getSwitchSourceBeanList() {
        List<SourceBean> filteredList = new ArrayList<>();
        for (SourceBean bean : sourceBeanList.values()) {
            filteredList.add(bean);
        }
        return filteredList;
    }

    private List<SourceBean> searchSourceBeanList;
    public List<SourceBean> getSearchSourceBeanList() {
        if(searchSourceBeanList.isEmpty()){
            LOG.i("echo-第一次getSearchSourceBeanList");
            searchSourceBeanList = new ArrayList<>();
            for (SourceBean bean : sourceBeanList.values()) {
                if (bean.isSearchable()) {
                    searchSourceBeanList.add(bean);
                }
            }
        }
        return searchSourceBeanList;
    }

    public List<ParseBean> getParseBeanList() {
        return parseBeanList;
    }

    public List<String> getVipParseFlags() {
        return vipParseFlags;
    }

    public SourceBean getHomeSourceBean() {
        return mHomeSource == null ? emptyHome : mHomeSource;
    }

    public List<LiveChannelGroup> getChannelGroupList() {
        return liveChannelGroupList;
    }

    public List<IJKCode> getIjkCodes() {
        return ijkCodes;
    }

    public IJKCode getCurrentIJKCode() {
        String codeName = KV.get(HawkConfig.IJK_CODEC, "硬解码");
        return getIJKCodec(codeName);
    }

    public IJKCode getIJKCodec(String name) {
        for (IJKCode code : ijkCodes) {
            if (code.getName().equals(name))
                return code;
        }
        return ijkCodes.get(0);
    }

    public Map<String,String> getMyHost() {
        return myHosts;
    }

    private void loadProxyRules(JsonObject infoJson) {
        if (!infoJson.has("proxy")) {
            OkGoHelper.setProxyList(null);
            return;
        }
        try {
            OkGoHelper.setProxyList(ProxyRule.arrayFrom(infoJson.get("proxy")));
        } catch (Throwable th) {
            th.printStackTrace();
            OkGoHelper.setProxyList(null);
        }
    }

    public void clearJarLoader() {
        spiderLoader.clearJarLoader();
    }

    private void addSuperParse()
    {
        ParseBean superPb = new ParseBean();
        superPb.setName("超级解析");
        superPb.setUrl("SuperParse");
        superPb.setExt("");
        superPb.setType(4);
        parseBeanList.add(0, superPb);
    }

    public void clearLoader() {
        spiderLoader.clearLoader();
    }

    public void clearSpiderCache() {
        spiderLoader.clearSpiderCache();
    }
}
