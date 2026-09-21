package com.github.tvbox.osc.api;

import static com.github.tvbox.osc.util.RegexUtils.getPattern;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.JarLoader;
import com.github.catvod.crawler.JsLoader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.pyLoader;
import com.github.catvod.crawler.python.IPyLoader;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.BootGuard;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.OkGoHelper;
import com.google.gson.JsonObject;

import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 爬虫装载:jar / js / py 三个加载器 + jar 下载与重试链路 + 点播/直播的 spider 获取。
 * 源列表、代理分发时的"当前源"、KV 读写留在 ApiConfig。
 */
final class SpiderLoader {

    private static final int LOAD_JAR_MAX_RETRY = 1;

    private final JarLoader jarLoader = new JarLoader();
    private final JsLoader jsLoader = new JsLoader();
    private final IPyLoader pyLoader = new pyLoader();
    private final ExecutorService jarLoadExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService danmuSearchExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> warmedSearchSpiderKeys = new HashSet<>();
    private final String userAgent = "okhttp/3.15";

    /** 配置级 jar(接口 JSON 顶层 "spider") */
    private String spider = null;
    /** 直播配置级 jar(直播接口 JSON 顶层 "spider") */
    private String liveSpider = "";
    /** 当前生效的直播 spider 地址:py/js 为接口地址,否则为 jar 地址 */
    private String currentLiveSpider;
    private String currentPyKey = "";
    private String currentLivePyKey = "";
    /** 接口 JSON 顶层 "jarCache":允许直接用一周内的 jar 缓存 */
    private String jarCache = "true";

    // ---------- 配置注入 ----------

    void setSpider(String spider) {
        this.spider = spider;
    }

    String getSpider() {
        return spider;
    }

    void setLiveSpider(String liveSpider) {
        this.liveSpider = liveSpider;
    }

    void setJarCache(String jarCache) {
        this.jarCache = jarCache;
    }

    // ---------- jar 下载与装载 ----------

    void loadJar(boolean useCache, String spider, ApiConfig.LoadConfigCallback callback) {
        loadJar(useCache, spider, callback, 0);
    }

    private interface JarLoadCallback {
        void complete(boolean success);
    }

    private interface JarDownloadCallback {
        void complete(File file, String error);
    }

    private void loadJarAsync(File file, JarLoadCallback callback) {
        jarLoadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                // 启动看门狗(2026-09-21):这里是所有 jar 装载的唯一收口(缓存命中与下载成功都走它),
                // 所以只需要在这里记一次"正在加载谁"—— 爬虫在自己的线程上闪退时,我们靠这个标记
                // 知道是哪个源把应用崩掉的(详见 BootGuard 注释里的自锁场景)。
                if (file != null) BootGuard.onJarLoadStart(file.getAbsolutePath());
                boolean success = false;
                try {
                    success = file != null && file.exists() && jarLoader.load(file.getAbsolutePath());
                } catch (Throwable th) {
                    LOG.e("echo---jar Loader threw exception: " + th.getMessage());
                }
                // 装载成功**不**清计数(2026-09-21):爬虫的 <clinit> 跑在自己的线程上,
                // 这里报成功之后 28ms 它才崩 —— 早清等于擦掉唯一证据。改由 BootGuard
                // 在"连续存活满 STABLE_RUN_MS(10 分钟)"后清(那时才真的算稳定源)。
                if (success) BootGuard.scheduleStableRunReset();
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

    /** 关闭失败即忽略;ApiConfig 拉配置时复用同一份实现 */
    static void closeQuietly(Closeable closeable) {
        try {
            if (closeable != null) closeable.close();
        } catch (Throwable ignored) {
            LOG.d("ApiConfig", "close failed");
        }
    }

    private static byte[] getImgJar(String body) {
        Pattern pattern = getPattern("[A-Za-z0-9]{8}\\*\\*");
        Matcher matcher = pattern.matcher(body);
        if (matcher.find()) {
            body = body.substring(body.indexOf(matcher.group()) + 10);
            return Base64.decode(body, Base64.DEFAULT);
        }
        return "".getBytes();
    }

    private void loadJar(boolean useCache, String spider, ApiConfig.LoadConfigCallback callback, int retryCount) {
        String[] urls = spider.split(";md5;");
        String jarUrl = urls[0];
        String md5 = urls.length > 1 ? urls[1].trim() : "";
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/csp/" + MD5.string2MD5(jarUrl) + ".jar");

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
        } else {
            if (Boolean.parseBoolean(jarCache) && cache.exists() && !FileUtils.isWeekAgo(cache)) {
                LOG.i("echo-load jar jarCache:" + jarUrl);
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
        LOG.i("echo-load jar start:" + jarUrl);
        final String requestUrl = jarUrl;
        downloadJarAsync(requestUrl, isJarInImg, cache, new JarDownloadCallback() {
            private boolean retryLoad(String reason) {
                if (retryCount >= LOAD_JAR_MAX_RETRY) return false;
                if (cache.exists() && !cache.delete()) {
                    LOG.i("echo---delete bad jar cache failed:" + cache.getAbsolutePath());
                }
                LOG.i("echo---retry load jar reason:" + reason + " url:" + requestUrl + " retry:" + (retryCount + 1));
                loadJar(false, spider, callback, retryCount + 1);
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

    // ---------- spider 获取(点播) ----------

    Spider getCSP(SourceBean sourceBean) {
        if (sourceBean.getApi().endsWith(".js") || sourceBean.getApi().contains(".js?")) {
            currentPyKey = "";
            return jsLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
        } else if (sourceBean.getApi().contains(".py")) {
            currentPyKey = sourceBean.getKey();
            pyLoader.setRecentPyKey(currentPyKey);
            return pyLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt());
        } else {
            currentPyKey = "";
            return jarLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
        }
    }

    /** 按 key 装载 py spider(代理分发时按"当前源"重新装载) */
    Spider pySpider(String key, String api, String ext) {
        Spider result = pyLoader.getSpider(key, api, ext);
        pyLoader.setRecentPyKey(key);
        return result;
    }

    String getCurrentPyKey() {
        return currentPyKey;
    }

    void setCurrentPyKey(String key) {
        currentPyKey = key;
    }

    // ---------- spider 获取(直播) ----------

    void setLiveJar(String liveJar) {
        if (liveJar.contains(".py")) {
            currentLivePyKey = MD5.string2MD5(liveJar);
            pyLoader.getSpider(currentLivePyKey, liveJar, "");
            pyLoader.setRecentPyKey(currentLivePyKey);
        } else if (liveJar.contains(".js")) {
            jsLoader.getSpider(MD5.string2MD5(liveJar), liveJar, "", "");
        } else {
            String jarUrl = !liveJar.isEmpty() ? liveJar : liveSpider;
            jarLoader.setRecentJarKey(MD5.string2MD5(jarUrl));
        }
        currentLiveSpider = liveJar;
    }

    Spider getPyCSP(String url) {
        currentLivePyKey = MD5.string2MD5(url);
        currentLiveSpider = url;
        return pyLoader.getSpider(currentLivePyKey, url, "");
    }

    Spider getJsCSP(String url) {
        currentLiveSpider = url;
        return jsLoader.getSpider(MD5.string2MD5(url), url, "", "");
    }

    Spider getLiveCSP(String url) {
        return url.contains(".js") ? getJsCSP(url) : getPyCSP(url);
    }

    /** 直播接口(type=3)的 spider 装载:py/js 走接口地址,否则按 jar 地址装载 */
    void loadLiveSpider(String api, String jarUrl, JsonObject livesOBJ) {
        LOG.i("echo-liveApi1" + api);
        if (api.contains(".py")) {
            LOG.i("echo-pyLoader.getSpider");
            String ext = liveExt(livesOBJ);
            currentLivePyKey = MD5.string2MD5(api);
            currentLiveSpider = api;
            pyLoader.getSpider(currentLivePyKey, api, ext);
        } else if (api.contains(".js")) {
            LOG.i("echo-jsLoader.getSpider");
            String ext = liveExt(livesOBJ);
            currentLiveSpider = api;
            jsLoader.getSpider(MD5.string2MD5(api), api, ext, jarUrl);
        }
        if (!jarUrl.isEmpty() && !isLiveSpiderApi(api)) {
            jarLoader.loadLiveJar(jarUrl);
            if (TextUtils.isEmpty(currentLiveSpider)) {
                currentLiveSpider = jarUrl;
            }
        } else if (!liveSpider.isEmpty() && !isLiveSpiderApi(api)) {
            jarLoader.loadLiveJar(liveSpider);
            if (TextUtils.isEmpty(currentLiveSpider)) {
                currentLiveSpider = liveSpider;
            }
        }
    }

    private static String liveExt(JsonObject livesOBJ) {
        if (livesOBJ.has("ext") && (livesOBJ.get("ext").isJsonObject() || livesOBJ.get("ext").isJsonArray())) {
            return livesOBJ.get("ext").toString();
        }
        return DefaultConfig.safeJsonString(livesOBJ, "ext", "");
    }

    static boolean isLiveSpiderApi(String api) {
        return api.contains(".py") || api.contains(".js");
    }

    /** 进入新一轮直播配置解析时清空"当前生效"标记 */
    void resetCurrentLiveSpider() {
        currentLiveSpider = "";
        currentLivePyKey = "";
    }

    String getCurrentLiveSpider() {
        return currentLiveSpider;
    }

    String getCurrentLivePyKey() {
        return currentLivePyKey;
    }

    // ---------- 预热 ----------

    /** 登记"已预热";返回 false 表示这次之前已经登记过(调用方跳过) */
    boolean markWarmed(String warmKey) {
        synchronized (warmedSearchSpiderKeys) {
            return warmedSearchSpiderKeys.add(warmKey);
        }
    }

    // ---------- 代理与扩展 ----------

    Object[] proxyInvokeJar(Map<String, String> param) {
        return jarLoader.proxyInvoke(param);
    }

    Object[] proxyInvokeJs(Map<String, String> param) {
        return jsLoader.proxyInvoke(param);
    }

    Object[] proxyInvokePy(Map<String, String> param, String pyKey) {
        return pyLoader.proxyInvoke(param, pyKey);
    }

    JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        return jarLoader.jsonExt(key, jxs, url);
    }

    JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        return jarLoader.jsonExtMix(flag, key, name, jxs, url);
    }

    void searchDanmuUi(String name, String episode, boolean longClick) {
        danmuSearchExecutor.execute(() -> {
            try {
                jarLoader.searchDanmuUi(name, episode, longClick);
            } catch (Throwable th) {
                LOG.e("ApiConfig searchDanmuUi error: " + th.getMessage());
                th.printStackTrace();
            }
        });
    }

    boolean hasDanmuSearchUi() {
        return jarLoader.hasDanmuSearchUi();
    }

    // ---------- 清理 ----------

    void clearJarLoader() {
        jarLoader.clear();
    }

    void clearLoader() {
        jarLoader.clear();
        pyLoader.clear();
        jsLoader.clear();
        synchronized (warmedSearchSpiderKeys) {
            warmedSearchSpiderKeys.clear();
        }
    }

    void clearSpiderCache() {
        currentPyKey = "";
        currentLivePyKey = "";
        currentLiveSpider = "";
        clearLoader();
    }
}
