package com.github.tvbox.osc.api;

import static com.github.tvbox.osc.util.RegexUtils.getPattern;

import android.app.Activity;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;

import androidx.media3.common.util.UriUtil;
import com.github.catvod.crawler.JarLoader;
import com.github.catvod.crawler.JsLoader;
import com.github.catvod.crawler.pyLoader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.python.IPyLoader;
import com.github.tvbox.osc.base.App;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    private String spider = null;
    private String currentPyKey = "";
    private String currentLivePyKey = "";
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

    private final JarLoader jarLoader = new JarLoader();
    private final JsLoader jsLoader = new JsLoader();
    private final IPyLoader pyLoader =  new pyLoader();
    private final Gson gson;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService configLoadExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService jarLoadExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService danmuSearchExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> warmedSearchSpiderKeys = new HashSet<>();

    private final String userAgent = "okhttp/3.15";

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

    private static byte[] getImgJar(String body){
        Pattern pattern = getPattern("[A-Za-z0-9]{8}\\*\\*");
        Matcher matcher = pattern.matcher(body);
        if(matcher.find()){
            body = body.substring(body.indexOf(matcher.group()) + 10);
            return Base64.decode(body, Base64.DEFAULT);
        }
        return "".getBytes();
    }

    private String TempKey = null;
    private String configUrl(String apiUrl){
        TempKey = null;
        String configUrl = "", pk = ";pk;";
        apiUrl=apiUrl.replace("file://", "clan://localhost/");
        if (apiUrl.contains(pk)) {
            String[] a = apiUrl.split(pk);
            TempKey = a[1];
            if (apiUrl.startsWith("clan")){
                configUrl = clanToAddress(a[0]);
            }else if (apiUrl.startsWith("http")){
                configUrl = a[0];
            }else {
                configUrl = "http://" + a[0];
            }
        } else if (apiUrl.startsWith("clan")) {
            configUrl = clanToAddress(apiUrl);
        } else if (!apiUrl.startsWith("http")) {
            configUrl = "http://" + apiUrl;
        } else {
            configUrl = apiUrl;
        }
        return configUrl;
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
        String configUrl=configUrl(apiUrl);

        final String configKey = TempKey;

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
        String liveApiConfigUrl = configUrl(liveApiUrl);
        final String liveConfigKey = TempKey;
        File live_cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/" + MD5.encode(liveApiUrl));
        LOG.i("echo-load live config "+liveApiUrl);
        if (useCache && live_cache.exists()) {
            try {
                parseLiveConfigContent(liveApiUrl, live_cache);
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
                        parseLiveConfigContent(liveApiUrl, live_cache);
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

    private static final int LOAD_JAR_MAX_RETRY = 1;

    public void loadJar(boolean useCache, String spider, LoadConfigCallback callback) {
        loadJar(useCache, spider, callback, 0);
    }

    private interface JarLoadCallback {
        void complete(boolean success);
    }

    private interface JarDownloadCallback {
        void complete(File file, String error);
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
                            result = clanContentFix(clanToAddress(apiUrl), result);
                        }
                        result = fixContentPath(apiUrl, result);
                    }
                } catch (Throwable th) {
                    error = th.getMessage();
                    if (TextUtils.isEmpty(error)) error = th.toString();
                } finally {
                    if (response != null) closeQuietly(response.body());
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

    private void loadJarAsync(File file, JarLoadCallback callback) {
        jarLoadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                try {
                    success = file != null && file.exists() && jarLoader.load(file.getAbsolutePath());
                } catch (Throwable th) {
                    LOG.e("echo---jar Loader threw exception: " + th.getMessage());
                }
                final boolean result = success;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.complete(result);
                    }
                });
            }
        });
    }

    private void downloadJarAsync(String url, boolean isJarInImg, File cache, JarDownloadCallback callback) {
        jarLoadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                File result = null;
                String error = "";
                okhttp3.Response response = null;
                InputStream inputStream = null;
                FileOutputStream outputStream = null;
                File temp = new File(cache.getAbsolutePath() + ".tmp");
                try {
                    File cacheDir = cache.getParentFile();
                    if (cacheDir != null && !cacheDir.exists()) cacheDir.mkdirs();
                    if (temp.exists()) temp.delete();
                    okhttp3.Request request = new okhttp3.Request.Builder()
                            .url(url)
                            .header("User-Agent", userAgent)
                            .build();
                    okhttp3.OkHttpClient client = OkGoHelper.getDefaultClient();
                    if (client == null) client = com.github.catvod.net.OkHttp.client();
                    response = client.newCall(request).execute();
                    if (!response.isSuccessful()) {
                        error = "HTTP " + response.code();
                    } else if (response.body() == null) {
                        error = "empty body";
                    } else if (isJarInImg) {
                        String respData = response.body().string();
                        LOG.i("echo---jar Response: " + respData);
                        byte[] imgJar = getImgJar(respData);
                        if (imgJar == null || imgJar.length == 0) {
                            error = "empty img jar";
                        } else {
                            outputStream = new FileOutputStream(temp);
                            outputStream.write(imgJar);
                            outputStream.flush();
                            closeQuietly(outputStream);
                            outputStream = null;
                            result = replaceCache(temp, cache);
                        }
                    } else {
                        inputStream = response.body().byteStream();
                        outputStream = new FileOutputStream(temp);
                        byte[] buffer = new byte[16384];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                        }
                        outputStream.flush();
                        closeQuietly(outputStream);
                        outputStream = null;
                        result = replaceCache(temp, cache);
                    }
                } catch (Throwable th) {
                    error = th.getMessage();
                } finally {
                    closeQuietly(inputStream);
                    closeQuietly(outputStream);
                    if (response != null) closeQuietly(response.body());
                    if (result == null && temp.exists()) temp.delete();
                }
                final File finalResult = result;
                final String finalError = error;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.complete(finalResult, finalError);
                    }
                });
            }
        });
    }

    private File replaceCache(File temp, File cache) throws IOException {
        if (cache.exists() && !cache.delete()) {
            LOG.i("echo---delete old jar cache failed:" + cache.getAbsolutePath());
        }
        if (!temp.renameTo(cache)) {
            FileUtils.copyFile(temp, cache);
            temp.delete();
        }
        return cache;
    }

    private void closeQuietly(java.io.Closeable closeable) {
        try {
            if (closeable != null) closeable.close();
        } catch (Throwable ignored) {
            LOG.d("ApiConfig", "close failed");
        }
    }

    private void loadJar(boolean useCache, String spider, LoadConfigCallback callback, int retryCount) {
        String[] urls = spider.split(";md5;");
        String jarUrl = urls[0];
        String md5 = urls.length > 1 ? urls[1].trim() : "";
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/csp/"+MD5.string2MD5(jarUrl)+".jar");

        if (!md5.isEmpty() || useCache) {
            if (cache.exists() && (useCache || MD5.getFileMd5(cache).equalsIgnoreCase(md5))) {
                if (cache.exists()) {
                    loadJarAsync(cache, new JarLoadCallback() {
                        @Override
                        public void complete(boolean success) {
                            if (success) {
                                callback.success();
                            } else {
                                callback.error("JAR加载失败");
                            }
                        }
                    });
                    return;
                }
                if (jarLoader.load(cache.getAbsolutePath())) {
                    callback.success();
                } else {
                    callback.error("JAR加载失败");
                }
                return;
            }
        }else {
            if (Boolean.parseBoolean(jarCache) && cache.exists() && !FileUtils.isWeekAgo(cache)) {
                LOG.i("echo-load jar jarCache:"+jarUrl);
                if (cache.exists()) {
                    loadJarAsync(cache, new JarLoadCallback() {
                        @Override
                        public void complete(boolean success) {
                            if (success) {
                                callback.success();
                            } else {
                                loadJar(false, spider, callback, retryCount);
                            }
                        }
                    });
                    return;
                }
                if (jarLoader.load(cache.getAbsolutePath())) {
                    callback.success();
                    return;
                }
            }
        }

        boolean isJarInImg = jarUrl.startsWith("img+");
        jarUrl = jarUrl.replace("img+", "");
        LOG.i("echo-load jar start:"+jarUrl);
        final String requestUrl = jarUrl;
        downloadJarAsync(requestUrl, isJarInImg, cache, new JarDownloadCallback() {
            private boolean retryLoad(String reason) {
                if (retryCount >= LOAD_JAR_MAX_RETRY) return false;
                if (cache.exists() && !cache.delete()) {
                    LOG.i("echo---delete bad jar cache failed:" + cache.getAbsolutePath());
                }
                LOG.i("echo---retry load jar reason:" + reason + " url:" + requestUrl + " retry:" + (retryCount + 1));
                loadJar(false, spider, callback, retryCount+1);
                return true;
            }

            @Override
            public void complete(File file, String error) {
                if (file != null && file.exists()) {
                    loadJarAsync(file, new JarLoadCallback() {
                        @Override
                        public void complete(boolean success) {
                            if (success) {
                                LOG.i("echo---load-jar-success");
                                callback.success();
                            } else {
                                LOG.e("echo---jar Loader returned false");
                                if (retryLoad("loader_false")) return;
                                callback.error("JAR加载失败");
                            }
                        }
                    });
                    return;
                }
                if (!TextUtils.isEmpty(error)) {
                    LOG.i("echo---jar Request failed: " + error);
                }
                if (cache.exists()) {
                    loadJarAsync(cache, new JarLoadCallback() {
                        @Override
                        public void complete(boolean success) {
                            if (success) {
                                callback.success();
                            } else {
                                if (retryLoad("request_error")) return;
                                callback.error("网络错误");
                            }
                        }
                    });
                    return;
                }
                if (retryLoad("request_error")) return;
                callback.error("网络错误");
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
        ArrayList<String> apiLines = parseApiCollection(jsonStr);
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
        }
        return true;
    }

    private ArrayList<String> parseApiCollection(String jsonStr) {
        ArrayList<String> apiLines = new ArrayList<>();
        try {
            String json = trimJsonObject(jsonStr);
            if (TextUtils.isEmpty(json)) {
                return apiLines;
            }
            JsonObject infoJson = gson.fromJson(json, JsonObject.class);
            if (infoJson == null || infoJson.has("sites") || !infoJson.has("urls") || !infoJson.get("urls").isJsonArray()) {
                return apiLines;
            }
            JsonArray urls = infoJson.get("urls").getAsJsonArray();
            for (JsonElement element : urls) {
                String name = "";
                String url = "";
                if (element.isJsonObject()) {
                    JsonObject item = element.getAsJsonObject();
                    name = DefaultConfig.safeJsonString(item, "name", "");
                    url = DefaultConfig.safeJsonString(item, "url", "");
                    if (TextUtils.isEmpty(url)) {
                        url = DefaultConfig.safeJsonString(item, "api", "");
                    }
                } else if (element.isJsonPrimitive()) {
                    url = element.getAsString();
                }
                if (!TextUtils.isEmpty(url)) {
                    apiLines.add(HistoryHelper.buildApiLine(name, url));
                }
            }
        } catch (Throwable ignored) {
            LOG.d("ApiConfig", "api lines parse failed, keep lines so far");
        }
        return apiLines;
    }

    private String trimJsonObject(String content) {
        if (content == null) {
            return "";
        }
        String trimContent = content.trim();
        int start = trimContent.indexOf("{");
        int end = trimContent.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return trimContent.substring(start, end + 1);
        }
        return trimContent;
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
        }
        invalidateLiveConfig();
    }

    /** 清空独立直播源并回到「跟随点播源」(2026-09-12):点播配置完全不受影响 */
    public void clearLiveConfig() {
        KV.put(HawkConfig.LIVE_API_URL, "");
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

    private static  String jarCache ="true";
    private void parseJson(String apiUrl, String jsonStr) {
        resetConfigData();
        LOG.i("echo-apiurl:" + apiUrl);
        JsonObject infoJson = gson.fromJson(jsonStr, JsonObject.class);
        // 配置级头像(2026-09-10):接口 JSON 顶层 "logo",胶囊头像的兜底来源(站点级 icon 优先)
        configLogo = DefaultConfig.safeJsonString(infoJson, "logo", "");
        // spider
        spider = DefaultConfig.safeJsonString(infoJson, "spider", "");
        jarCache = DefaultConfig.safeJsonString(infoJson, "jarCache", "true");
        danmaku = DefaultConfig.safeJsonString(infoJson, "danmaku", "");
        // 远端站点源
        SourceBean firstSite = null;
        for (JsonElement opt : infoJson.get("sites").getAsJsonArray()) {
            JsonObject obj = (JsonObject) opt;
            if (!obj.has("key") || !obj.has("type") || !obj.has("api")) {
                LOG.i("echo-skip incomplete site config: " + obj);
                continue;
            }
            SourceBean sb = new SourceBean();
            String siteKey = obj.get("key").getAsString().trim();
            sb.setKey(siteKey);
            sb.setName(obj.has("name")?obj.get("name").getAsString().trim():siteKey);
            sb.setType(obj.get("type").getAsInt());
            sb.setApi(obj.get("api").getAsString().trim());
            sb.setSearchable(DefaultConfig.safeJsonInt(obj, "searchable", 1));
            sb.setQuickSearch(DefaultConfig.safeJsonInt(obj, "quickSearch", 1));
            sb.setChangeable(DefaultConfig.safeJsonInt(obj, "changeable", 1));
            if(siteKey.startsWith("py_")){
                sb.setFilterable(1);
            }else {
                sb.setFilterable(DefaultConfig.safeJsonInt(obj, "filterable", 1));
            }
            sb.setPlayerUrl(DefaultConfig.safeJsonString(obj, "playUrl", ""));
            sb.setExt(DefaultConfig.safeJsonString(obj, "ext", ""));
            sb.setJar(DefaultConfig.safeJsonString(obj, "jar", ""));
            sb.setPlayerType(DefaultConfig.safeJsonInt(obj, "playerType", -1));
            sb.setCategories(DefaultConfig.safeJsonStringList(obj, "categories"));
            sb.setTimeout(DefaultConfig.safeJsonInt(obj, "timeout", 0));
            sb.setClickSelector(DefaultConfig.safeJsonString(obj, "click", ""));
            sb.setStyle(DefaultConfig.safeJsonString(obj, "style", ""));
            sb.setIcon(DefaultConfig.safeJsonString(obj, "icon", ""));
            String extPreview = sb.getExt();
            LOG.i("echo-site:" + sb.getName() + " icon:" + sb.getIcon()
                    + " ext:" + (extPreview.length() > 160 ? extPreview.substring(0, 160) : extPreview));
            if (firstSite == null) firstSite = sb;
            sourceBeanList.put(siteKey, sb);
        }
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
                    ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
                    for (int i=0; i< lives_groups.size();i++) {
                        JsonObject jsonObject = lives_groups.get(i).getAsJsonObject();
                        String name = jsonObject.has("name")?jsonObject.get("name").getAsString():"线路"+(i+1);
                        LiveSettingItem liveSettingItem = new LiveSettingItem();
                        liveSettingItem.setItemIndex(i);
                        liveSettingItem.setItemName(name);
                        liveSettingItemList.add(liveSettingItem);
                    }
                    liveSettingGroupList.get(5).setLiveSettingItems(liveSettingItemList);
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
            JsonArray hostsArray = infoJson.getAsJsonArray("hosts");
            for (int i = 0; i < hostsArray.size(); i++) {
                String entry = hostsArray.get(i).getAsString();
                String[] parts = entry.split("=", 2); // 只分割一次，防止 value 里有 =
                if (parts.length == 2) {
                    myHosts.put(parts[0], parts[1]);
                }
            }
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
        String jsonContent = trimJsonObject(content);
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
        if (isLiveJsonContent(content)) {
            parseLiveJson(apiUrl, jsonContent);
        } else {
            parseLiveText(apiUrl, content);
        }
    }

    private boolean isLiveJsonContent(String content) {
        if (content == null) return false;
        String text = content.trim();
        if (text.startsWith("\ufeff")) text = text.substring(1).trim();
        return text.startsWith("{");
    }

    private void parseLiveText(String apiUrl, String content) {
        liveChannelGroupList.clear();
        liveSpider = "";
        currentLiveSpider = "";
        currentLivePyKey = "";
        initLiveSettings();
        KV.put(HawkConfig.LIVE_GROUP_LIST, new JsonArray());
        KV.put(HawkConfig.EPG_URL, extractLiveTextEpg(content));
        KV.put(HawkConfig.LIVE_PLAY_TYPE, KV.get(HawkConfig.PLAY_TYPE, 2));
        KV.put(HawkConfig.LIVE_WEB_HEADER, null);
        JsonArray livesArray = TxtSubscribe.parseToJsonArray(content);
        loadLives(livesArray);
        LOG.i("echo-live-text-config-----------load:" + apiUrl);
    }

    private String extractLiveTextEpg(String content) {
        if (content == null) return "";
        String text = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = text.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("\ufeff")) line = line.substring(1).trim();
            if (!line.startsWith("#EXTM3U")) continue;
            String epg = extractQuotedAttr(line, "x-tvg-url");
            if (epg.isEmpty()) epg = extractQuotedAttr(line, "tvg-url");
            if (epg.isEmpty()) epg = extractQuotedAttr(line, "url-tvg");
            return epg;
        }
        return "";
    }

    private String extractQuotedAttr(String line, String key) {
        String token = key + "=\"";
        int start = line.indexOf(token);
        if (start < 0) return "";
        start += token.length();
        int end = line.indexOf("\"", start);
        if (end < 0) return "";
        return line.substring(start, end).trim();
    }

    private String liveSpider="";
    private void parseLiveJson(String apiUrl, String jsonStr) {
        liveChannelGroupList.clear();
        JsonObject infoJson = gson.fromJson(jsonStr, JsonObject.class);
        // spider
        liveSpider = DefaultConfig.safeJsonString(infoJson, "spider", "");
        // 直播源
        initLiveSettings();
        if(infoJson.has("lives")){
            JsonArray lives_groups=infoJson.get("lives").getAsJsonArray();

            int live_group_index=getLiveGroupIndex();
            if(live_group_index>lives_groups.size()-1)live_group_index=0;
            KV.put(HawkConfig.LIVE_GROUP_LIST,lives_groups);
            //加载多源配置
            try {
                ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
                for (int i=0; i< lives_groups.size();i++) {
                    JsonObject jsonObject = lives_groups.get(i).getAsJsonObject();
                    String name = jsonObject.has("name")?jsonObject.get("name").getAsString():"线路"+(i+1);
                    LiveSettingItem liveSettingItem = new LiveSettingItem();
                    liveSettingItem.setItemIndex(i);
                    liveSettingItem.setItemName(name);
                    liveSettingItemList.add(liveSettingItem);
                }
                liveSettingGroupList.get(5).setLiveSettingItems(liveSettingItemList);
            } catch (Exception e) {
                // 捕获任何可能发生的异常
                e.printStackTrace();
            }

            JsonObject livesOBJ = lives_groups.get(live_group_index).getAsJsonObject();
            loadLiveApi(livesOBJ);
        }

        myHosts = new HashMap<>();
        if (infoJson.has("hosts")) {
            JsonArray hostsArray = infoJson.getAsJsonArray("hosts");
            for (int i = 0; i < hostsArray.size(); i++) {
                String entry = hostsArray.get(i).getAsString();
                String[] parts = entry.split("=", 2); // 只分割一次，防止 value 里有 =
                if (parts.length == 2) {
                    myHosts.put(parts[0], parts[1]);
                }
            }
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
     * 刷新直播设置「配置切换」组(第 6 组)的候选项(2026-09-12 点播/直播拆分):
     * 第 0 项固定为合成的「跟随点播源」(即未单独配置直播源的默认态),
     * 其后依次为直播配置历史 —— 因此历史第 i 项在该组里的 itemIndex = i + 1。
     * 跟随项无条件占位(即使当前未配置点播源),避免"是否显示"导致的下标漂移。
     */
    public void refreshLiveApiHistoryItems() {
        if (liveSettingGroupList.size() < 7) return;
        ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
        LiveSettingItem followItem = new LiveSettingItem();
        followItem.setItemIndex(0);
        followItem.setItemName(LIVE_FOLLOW_ITEM_NAME);
        liveSettingItemList.add(followItem);
        ArrayList<String> history = KV.get(HawkConfig.LIVE_API_HISTORY, new ArrayList<String>());
        for (int i = 0; i < history.size(); i++) {
            LiveSettingItem liveSettingItem = new LiveSettingItem();
            liveSettingItem.setItemIndex(i + 1);
            liveSettingItem.setItemName(history.get(i));
            liveSettingItemList.add(liveSettingItem);
        }
        liveSettingGroupList.get(6).setLiveSettingItems(liveSettingItemList);
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
            currentLiveSpider = "";
            currentLivePyKey = "";
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
                String type = livesOBJ.has("type") ? livesOBJ.get("type").getAsString() : (isLiveSpiderApi(api) ? "3" : "0");
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
                        LOG.i("echo-liveApi1"+api);
                        if(api.contains(".py")){
                            LOG.i("echo-pyLoader.getSpider");
                            String ext="";
                            if(livesOBJ.has("ext") && (livesOBJ.get("ext").isJsonObject() || livesOBJ.get("ext").isJsonArray())){
                                ext=livesOBJ.get("ext").toString();
                            }else {
                                ext=DefaultConfig.safeJsonString(livesOBJ, "ext", "");
                            }

                            currentLivePyKey = MD5.string2MD5(api);
                            currentLiveSpider = api;
                            pyLoader.getSpider(currentLivePyKey,api,ext);
                        } else if (api.contains(".js")) {
                            LOG.i("echo-jsLoader.getSpider");
                            String ext="";
                            if(livesOBJ.has("ext") && (livesOBJ.get("ext").isJsonObject() || livesOBJ.get("ext").isJsonArray())){
                                ext=livesOBJ.get("ext").toString();
                            }else {
                                ext=DefaultConfig.safeJsonString(livesOBJ, "ext", "");
                            }
                            currentLiveSpider = api;
                            jsLoader.getSpider(MD5.string2MD5(api), api, ext, jarUrl);
                        }
                        if(!jarUrl.isEmpty() && !isLiveSpiderApi(api)){
                            jarLoader.loadLiveJar(jarUrl);
                            if (TextUtils.isEmpty(currentLiveSpider)) {
                                currentLiveSpider = jarUrl;
                            }
                        }else if(!liveSpider.isEmpty() && !isLiveSpiderApi(api)){
                            jarLoader.loadLiveJar(liveSpider);
                            if (TextUtils.isEmpty(currentLiveSpider)) {
                                currentLiveSpider = liveSpider;
                            }
                        }
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

    private String currentLiveSpider;
    public void setLiveJar(String liveJar)
    {
        if(liveJar.contains(".py")){
            currentLivePyKey = MD5.string2MD5(liveJar);
            pyLoader.getSpider(currentLivePyKey, liveJar, "");
            pyLoader.setRecentPyKey(currentLivePyKey);
        }else if(liveJar.contains(".js")){
            jsLoader.getSpider(MD5.string2MD5(liveJar), liveJar, "", "");
        }else {
            String jarUrl=!liveJar.isEmpty()?liveJar:liveSpider;
            jarLoader.setRecentJarKey(MD5.string2MD5(jarUrl));
        }
        currentLiveSpider=liveJar;
    }

    public String getSpider() {
        return spider;
    }

    public String getDanmaku() {
        return danmaku == null ? "" : danmaku;
    }

    public Spider getCSP(SourceBean sourceBean) {
        if (sourceBean.getApi().endsWith(".js") || sourceBean.getApi().contains(".js?")){
            currentPyKey = "";
            return jsLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
        }
        else if (sourceBean.getApi().contains(".py")) {
            currentPyKey = sourceBean.getKey();
            pyLoader.setRecentPyKey(currentPyKey);
            return pyLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt());
        }
        else {
            currentPyKey = "";
            return jarLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
        }
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
                    synchronized (warmedSearchSpiderKeys) {
                        if (warmedSearchSpiderKeys.contains(warmKey)) continue;
                        warmedSearchSpiderKeys.add(warmKey);
                    }
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
        currentLivePyKey = MD5.string2MD5(url);
        currentLiveSpider = url;
        return pyLoader.getSpider(currentLivePyKey, url, "");
    }

    public Spider getJsCSP(String url) {
        currentLiveSpider = url;
        return jsLoader.getSpider(MD5.string2MD5(url), url, "", "");
    }

    public Spider getLiveCSP(String url) {
        return url.contains(".js") ? getJsCSP(url) : getPyCSP(url);
    }

    public void searchDanmuUi(String name, String episode, boolean longClick) {
        danmuSearchExecutor.execute(() -> {
            try {
                jarLoader.searchDanmuUi(name, episode, longClick);
            } catch (Throwable th) {
                LOG.e("ApiConfig searchDanmuUi error: " + th.getMessage());
                th.printStackTrace();
            }
        });
    }

    public boolean hasDanmuSearchUi() {
        return jarLoader.hasDanmuSearchUi();
    }

    public int getLiveConnectTimeoutSeconds() {
        return (KV.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1) + 1) * 5;
    }

    private boolean isLiveSpiderApi(String api) {
        return api.contains(".py") || api.contains(".js");
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

                result = jarLoader.proxyInvoke(param);
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
            return jsLoader.proxyInvoke(param);
        }

        if (isLive) {
            String liveApi = currentLiveSpider != null ? currentLiveSpider : "";

            if (liveApi.contains(".py")) {
                return pyLoader.proxyInvoke(param, currentLivePyKey);
            }
            if (liveApi.contains(".js")) {
                return jsLoader.proxyInvoke(param);
            }
            return jarLoader.proxyInvoke(param);
        }

        if (isPy) {
            return pyLoader.proxyInvoke(param, getCurrentPyKey());
        }

        if (isApiPy) {
            return pyLoader.proxyInvoke(param, getCurrentPyKey());
        }

        return jarLoader.proxyInvoke(param);
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
            if (!sourceBean.getKey().equals(currentPyKey)) {
                currentPyKey = sourceBean.getKey();
                pyLoader.getSpider(currentPyKey, sourceBean.getApi(), sourceBean.getExt());
                pyLoader.setRecentPyKey(currentPyKey);
            }
            return currentPyKey;
        }
        return currentPyKey;
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        return jarLoader.jsonExt(key, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        return jarLoader.jsonExtMix(flag, key, name, jxs, url);
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

    String clanToAddress(String lanLink) {
        if (lanLink.startsWith("clan://localhost/")) {
            return lanLink.replace("clan://localhost/", ControlManager.get().getAddress(true) + "file/");
        } else {
            String link = lanLink.substring(7);
            int end = link.indexOf('/');
            return "http://" + link.substring(0, end) + "/file/" + link.substring(end + 1);
        }
    }

    String clanContentFix(String lanLink, String content) {
        String fix = lanLink.substring(0, lanLink.indexOf("/file/") + 6);
        return content.replace("clan://localhost/", fix).replace("file://", fix);
    }

    String fixContentPath(String url, String content) {
        if (content.contains("\"./") || content.contains("\"../")) {
            url=url.replace("file://","clan://localhost/");
            if(!url.startsWith("http") && !url.startsWith("clan://")){
                url = "http://" + url;
            }
            if(url.startsWith("clan://"))url=clanToAddress(url);
            content = content.replace("../", UriUtil.resolve(url, "../"));
            content = content.replace("./", UriUtil.resolve(url, "./"));
        }
        return content;
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

    public void clearJarLoader()
    {
        jarLoader.clear();
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

    public void clearLoader(){
        jarLoader.clear();
        pyLoader.clear();
        jsLoader.clear();
        synchronized (warmedSearchSpiderKeys) {
            warmedSearchSpiderKeys.clear();
        }
    }

    public void clearSpiderCache() {
        currentPyKey = "";
        currentLivePyKey = "";
        currentLiveSpider = "";
        clearLoader();
    }
}
