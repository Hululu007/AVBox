package com.github.catvod.crawler;

import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.server.RemoteServer;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.lzy.okgo.OkGo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import okhttp3.Response;

public class JarLoader {

    private static final String TAG = "JarLoader";
    private static final String MAIN_KEY = "main";

    private final ConcurrentHashMap<String, DexClassLoader> loaders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Method> proxyMethods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Method> danmuClickMethods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Method> danmuLongClickMethods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> siteJarKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> aliases = new ConcurrentHashMap<>();
    private final ProtectedInitJar protectedInitJar = new ProtectedInitJar();
    private volatile String recent = MAIN_KEY;

    public boolean load(String cache) {
        boolean success = load(MAIN_KEY, new File(cache));
        if (success) recent = MAIN_KEY;
        return success;
    }

    public void setRecentJarKey(String key) {
        if (TextUtils.isEmpty(key)) return;
        recent = realKey(key);
        injectProxyPort(loaders.get(recent));
    }

    public void loadLiveJar(String jar) {
        String key = jarKey(jar);
        parseJar(key, jar);
        setRecentJarKey(key);
    }

    public void clear() {
        for (Spider spider : spiders.values()) {
            try {
                spider.destroy();
            } catch (Throwable ignored) {
                LOG.d("JarLoader", "destroy spider failed");
            }
        }
        loaders.clear();
        proxyMethods.clear();
        danmuClickMethods.clear();
        danmuLongClickMethods.clear();
        spiders.clear();
        locks.clear();
        siteJarKeys.clear();
        aliases.clear();
        recent = MAIN_KEY;
    }

    private boolean load(String key, File file) {
        if (Thread.interrupted()) return false;
        if (!exists(file)) return false;
        if (loaders.containsKey(key)) return true;
        try {
            file.setReadOnly();
            String cachePath = jarDir().getAbsolutePath();
            DexClassLoader loader = new DexClassLoader(file.getAbsolutePath(), cachePath, cachePath, App.getInstance().getClassLoader());
            if (!invokeInit(loader, file.getAbsolutePath())) {
                LOG.i("echo--jar-load error key=" + key + ", init returned false");
                return false;
            }
            invokeProxy(key, loader);
            invokeDanmaku(key, loader);
            injectProxyPort(loader);
            loaders.put(key, loader);
            LOG.i("echo--jar-load success key=" + key + ", file=" + file.getAbsolutePath());
            return true;
        } catch (Throwable e) {
            LOG.i("echo--jar-load error key=" + key + ", msg=" + e.getClass().getSimpleName() + ":" + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean invokeInit(DexClassLoader loader, String jar) {
        boolean riskyJar = false;
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Init");
            riskyJar = protectedInitJar.check(jar);
            if (riskyJar) {
                LOG.i("echo--jar-initProtectedJar file=" + jar);
                if (protectedInitJar.hasDexNative(jar) && protectedInitJar.init(clz)) {
                    return true;
                }

                // 2026-09-14 二轮(反汇编 + 离线解密实证):该 jar 的 Init.init 开头就是包名白名单
                // 闸门 —— getApplicationInfo(getPackageName()) -> getApplicationLabel,再用内置
                // AES 密文("XMjUpOPJ...",key/iv 由 Init.short[1664] 运行时解出)解出逗号分隔的包名
                // 表(共 50 个:com.fongmi.android.tv、com.github.tvbox.osc(MBox)、com.hisense... 等,
                // **不含本包名 com.github.avbox.osc**);未命中即提示"包名不匹配,当前包名: xxx"
                // 并在 5 秒后 Process.killProcess。所以本应用无法通过闸门,伪装包名也只会撞上
                // Android 11+ 包可见性(NameNotFoundException) —— 伪装这条路已废弃。
                //
                // 闸门之后 init 真正做、且其它站点依赖的副作用只有 saveConfig():
                // 往 filesDir/Pizazz/config.json 写入内置默认配置(437 字节 JSON,19 个键:
                // quarkQuality/quarkThread/proxyMode/pansouUrl/panView/aliThread/xunleiThread...)。
                // 配置中心(csp_Config)每个分类都要读这些键:
                //   new JsonParser().parse(读config.json).getAsJsonObject().get("quarkQuality").getAsString()
                // —— 键缺失 = NPE -> catch(Exception) -> return "" -> 分类空白"暂无内容";
                // 只有"光鸭"分类不读 config.json,所以它是唯一出卡片的分类(用户截图实证)。
                // 结论:绕过闸门,只补做 saveConfig,永不进入 killProcess 分支。
                boolean bound = bindInitContext(clz, App.getInstance());
                boolean saved = invokeSaveConfig(clz);
                ensureInitConfig();
                LOG.i("echo--jar-skip Init.init(whitelist-gated) contextBound=" + bound
                        + ", saveConfig=" + saved + ", file=" + jar);
                return true;
            }
            Method method = clz.getMethod("init", Context.class);
            method.invoke(null, App.getInstance());
            return true;
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return !riskyJar;
    }

    private boolean bindInitContext(Class<?> clz, Context hostContext) {
        boolean bound = false;
        try {
            Object instance = null;
            try {
                instance = clz.getMethod("get").invoke(null);
            } catch (Throwable ignored) {
                LOG.d("JarLoader", "init get() invoke failed");
            }
            Context app = hostContext;
            for (java.lang.reflect.Field field : clz.getDeclaredFields()) {
                if (!Context.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    if (field.get(null) != null) continue;
                    field.set(null, app);
                    bound = true;
                } else if (instance != null) {
                    if (field.get(instance) != null) continue;
                    field.set(instance, app);
                    bound = true;
                }
            }
        } catch (Throwable e) {
            LOG.i("echo--jar-bindInitContext error " + e.getClass().getSimpleName() + ":" + e.getMessage());
        }
        return bound;
    }

    /**
     * 2026-09-14 二轮(实证):jar 的 Init.saveConfig() 位于包名闸门之后,但本身与闸门无关,
     * 可反射单独调用。它把内置默认配置合并进 filesDir/Pizazz/config.json(已存在的键保留,
     * 缺失的键补齐,并强制刷新 version)。网盘"配置·中心"各分类(读 quarkQuality /
     * quarkThread / proxyMode / pansouUrl / panView 等)完全依赖这份默认值,否则
     * JsonObject.get(key) 返回 null,getAsString() 抛 NPE,分类内容被 catch 成空字符串。
     */
    private boolean invokeSaveConfig(Class<?> clz) {
        try {
            Method method = clz.getDeclaredMethod("saveConfig");
            method.setAccessible(true);
            method.invoke(null);
            return true;
        } catch (Throwable e) {
            LOG.i("echo--jar-saveConfig error " + e.getClass().getSimpleName() + ":" + e.getMessage());
            return false;
        }
    }

    /**
     * 2026-09-14:Init.saveConfig 本该生成的 filesDir/config.json 缺失时,依赖它的站点
     * (豆瓣)homeContent 开头 new JSONObject(读文件) 直接 syntaxError(真机堆栈实证)。
     * 该 JSON 的键均经 optString 带默认值(homePage 等),写 "{}" 即可通过;文件已存在则
     * 不动(保留后续 init/saveConfig 或用户数据的真实内容)。
     */
    private void ensureInitConfig() {
        try {
            // jar 的 merge.m.k.d(name) 实际路径 = filesDir/Pizazz/<name>(字节码反汇编实证),
            // 豆瓣 homeContent 读的就是 filesDir/Pizazz/config.json。
            java.io.File dir = new java.io.File(App.getInstance().getFilesDir(), "Pizazz");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "config.json");
            if (!f.exists()) {
                java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                out.write(new byte[]{'{', '}'});
                out.close();
                LOG.i("echo--jar-init config.json created");
            }
        } catch (Throwable e) {
            LOG.i("echo--jar-init config.json error " + e.getClass().getSimpleName() + ":" + e.getMessage());
        }
    }

    private void invokeProxy(String key, DexClassLoader loader) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Proxy");
            Method method = clz.getMethod("proxy", Map.class);
            proxyMethods.put(key, method);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void invokeDanmaku(String key, DexClassLoader loader) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Danmaku");
            try {
                danmuClickMethods.put(key, clz.getMethod("onClick", String.class, String.class));
            } catch (Throwable ignored) {
                LOG.d("JarLoader", "danmaku onClick method not found");
            }
            try {
                danmuLongClickMethods.put(key, clz.getMethod("onLongClick", String.class, String.class));
            } catch (Throwable ignored) {
                LOG.d("JarLoader", "danmaku onLongClick method not found");
            }
        } catch (Throwable ignored) {
            LOG.d("JarLoader", "danmaku class not found in jar");
        }
    }

    public void parseJar(String key, String jar) {
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(jar)) return;
        if (loaders.containsKey(key)) return;
        Object lock = lock(key);
        synchronized (lock) {
            if (loaders.containsKey(key)) return;
            String source = jar;
            String md5 = "";
            String[] texts = jar.split(";md5;");
            if (texts.length > 1) {
                source = texts[0];
                md5 = texts[1].trim();
            }
            aliases.put(jarKey(source), key);
            if (md5.startsWith("http")) {
                String value = OkHttp.string(md5, null);
                md5 = value == null ? "" : value.trim();
            }
            File file = fileForJar(source);
            if (!TextUtils.isEmpty(md5) && exists(file) && MD5.getFileMd5(file).equalsIgnoreCase(md5)) {
                load(key, file);
            } else if (TextUtils.isEmpty(md5) && exists(file) && !FileUtils.isWeekAgo(file)) {
                load(key, file);
            } else if (source.startsWith("http")) {
                load(key, download(source, file));
            } else if (source.startsWith("assets")) {
                load(key, copyAsset(source, file));
            } else if (source.startsWith("file")) {
                load(key, local(source));
            } else if (source.startsWith("clan://")) {
                load(key, download(clanToAddress(source), file));
            }
        }
    }

    public DexClassLoader dex(String jar) {
        try {
            String key = jarKey(jar);
            parseJar(key, jar);
            return loaders.get(key);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        key = key == null ? "" : key;
        api = api == null ? "" : api;
        ext = ext == null ? "" : ext;
        jar = jar == null ? "" : jar;
        if (TextUtils.isEmpty(api)) return new SpiderNull();

        String jaKey = TextUtils.isEmpty(jar) ? MAIN_KEY : jarKey(jar);
        String spKey = jaKey + key;
        recent = jaKey;
        siteJarKeys.put(key, jaKey);
        injectProxyPort(loaders.get(jaKey));

        Spider cached = spiders.get(spKey);
        if (cached != null) {
            Log.i(TAG, "getSpider cached key=" + spKey);
            return cached;
        }

        // BugReview #18:per-key 锁消除 check-then-act 竞态(主线程/搜索线程池/代理线程
        // 并发时重复初始化并互相覆盖,被覆盖的实例无人 destroy)。双检 + 锁内重检。
        Object lock = locks.computeIfAbsent(spKey, k -> new Object());
        synchronized (lock) {
            cached = spiders.get(spKey);
            if (cached != null) return cached;
            try {
                if (!MAIN_KEY.equals(jaKey)) parseJar(jaKey, jar);
                DexClassLoader loader = loaders.get(jaKey);
                if (loader == null) return new SpiderNull();
                Spider spider = (Spider) loader.loadClass("com.github.catvod.spider." + className(api)).newInstance();
                spider.siteKey = key;
                spider.initApi(new SpiderApi());
                spider.init(App.getInstance(), ext);
                spiders.put(spKey, spider);
                Log.i(TAG, "getSpider success key=" + spKey);
                return spider;
            } catch (Throwable e) {
                Log.i(TAG, "getSpider error key=" + spKey + ", msg=" + e.getMessage());
                e.printStackTrace();
                return new SpiderNull();
            }
        }
    }

    public void searchDanmuUi(String name, String episode, boolean longClick) {
        try {
            ConcurrentHashMap<String, Method> methods = longClick ? danmuLongClickMethods : danmuClickMethods;
            Method method = methods.get(recent);
            if (method == null) method = methods.get("main");
            if (method == null) return;
            method.invoke(null, name, episode);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public boolean hasDanmuSearchUi() {
        return danmuClickMethods.containsKey(recent) || danmuLongClickMethods.containsKey(recent);
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        try {
            Class<?> clz = loadParserClass("com.github.catvod.parser.Json" + key);
            Method method = clz.getMethod("parse", LinkedHashMap.class, String.class);
            return (JSONObject) method.invoke(null, jxs, url);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        try {
            Class<?> clz = loadParserClass("com.github.catvod.parser.Mix" + key);
            Method method = clz.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
            return (JSONObject) method.invoke(null, jxs, name, flag, url);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    public Object[] proxyInvoke(Map<String, String> params) {
        String siteKey = params == null ? null : params.get("siteKey");
        if (!TextUtils.isEmpty(siteKey)) {
            Object[] result = proxyInvoke(proxyMethods.get(siteJarKeys.get(siteKey)), params);
            if (result != null) return result;
        }
        Object[] result = proxyInvoke(proxyMethods.get(recent), params);
        if (result != null) return result;
        for (Map.Entry<String, Method> entry : proxyMethods.entrySet()) {
            if (entry.getKey().equals(recent)) continue;
            result = proxyInvoke(entry.getValue(), params);
            if (result != null) return result;
        }
        return null;
    }

    private Object[] proxyInvoke(Method method, Map<String, String> params) {
        try {
            return method == null ? null : (Object[]) method.invoke(null, params);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    private DexClassLoader requireRecentLoader() {
        DexClassLoader loader = loaders.get(recent);
        if (loader == null) loader = loaders.get(MAIN_KEY);
        if (loader == null) throw new IllegalStateException("No jar loaded for recent key: " + recent);
        return loader;
    }

    private Class<?> loadParserClass(String name) throws ClassNotFoundException {
        DexClassLoader loader = loaders.get(recent);
        if (loader != null) {
            try {
                return loader.loadClass(name);
            } catch (ClassNotFoundException ignored) {
                LOG.d("JarLoader", "class not in cached loader: " + name);
            }
        }
        loader = loaders.get(MAIN_KEY);
        if (loader != null) return loader.loadClass(name);
        throw new ClassNotFoundException(name);
    }

    private File download(String url, File file) {
        InputStream is = null;
        FileOutputStream os = null;
        try {
            Response response = OkGo.<File>get(url).execute();
            if (response.body() == null) return file;
            is = response.body().byteStream();
            os = new FileOutputStream(create(file));
            byte[] buffer = new byte[16384];
            int length;
            while ((length = is.read(buffer)) != -1) {
                if (Thread.interrupted()) return file;
                os.write(buffer, 0, length);
            }
            os.flush();
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            close(is);
            close(os);
        }
        return file;
    }

    private File copyAsset(String url, File file) {
        InputStream is = null;
        FileOutputStream os = null;
        try {
            String path = url.replace("assets://", "").replace("assets/", "");
            is = App.getInstance().getAssets().open(path);
            os = new FileOutputStream(create(file));
            byte[] buffer = new byte[16384];
            int length;
            while ((length = is.read(buffer)) != -1) {
                os.write(buffer, 0, length);
            }
            os.flush();
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            close(is);
            close(os);
        }
        return file;
    }

    private File local(String path) {
        path = path.replace("file:/", "");
        File file = new File(Environment.getExternalStorageDirectory(), path);
        return file.exists() ? file : new File(path);
    }

    private File fileForJar(String jar) {
        return new File(jarDir(), jarKey(jar) + ".jar");
    }

    private File jarDir() {
        File dir = new File(App.getInstance().getCacheDir(), "jar");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File create(File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (file.exists()) file.delete();
        file.createNewFile();
        file.setReadable(true);
        file.setWritable(true);
        file.setExecutable(true);
        return file;
    }

    private boolean exists(File file) {
        return file != null && file.exists() && file.length() > 0;
    }

    private Object lock(String key) {
        Object lock = locks.get(key);
        if (lock != null) return lock;
        Object created = new Object();
        Object old = locks.putIfAbsent(key, created);
        return old == null ? created : old;
    }

    private String jarKey(String jar) {
        String key = MD5.string2MD5(jar == null ? "" : jar);
        return TextUtils.isEmpty(key) ? MAIN_KEY : key;
    }

    private String realKey(String key) {
        String alias = aliases.get(key);
        return TextUtils.isEmpty(alias) ? key : alias;
    }

    private String className(String api) {
        return api.contains("csp_") ? api.split("csp_")[1] : api;
    }

    private String clanToAddress(String url) {
        if (url.startsWith("clan://localhost/")) {
            return url.replace("clan://localhost/", ControlManager.get().getAddress(true) + "file/");
        }
        if (url.startsWith("clan://")) {
            String text = url.substring(7);
            int index = text.indexOf('/');
            if (index > 0) return "http://" + text.substring(0, index) + "/file/" + text.substring(index + 1);
        }
        return url;
    }

    private void injectProxyPort(DexClassLoader loader) {
        com.github.catvod.Proxy.set(getServerPort());
        if (loader == null) return;
        try {
            Class<?> proxy = loader.loadClass("com.github.catvod.Proxy");
            Method set = proxy.getMethod("set", int.class);
            set.invoke(null, getServerPort());
        } catch (Throwable ignored) {
            LOG.d("JarLoader", "inject proxy port into jar failed");
        }
    }

    private int getServerPort() {
        try {
            String address = ControlManager.get().getAddress(true);
            if (address != null && address.startsWith("http://127.0.0.1:")) {
                String baseUrl = address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
                return Integer.parseInt(baseUrl.substring(baseUrl.lastIndexOf(":") + 1));
            }
        } catch (Throwable ignored) {
            LOG.d("JarLoader", "parse server port failed, use RemoteServer.serverPort");
        }
        return RemoteServer.serverPort;
    }

    private void close(java.io.Closeable closeable) {
        try {
            if (closeable != null) closeable.close();
        } catch (Throwable ignored) {
            LOG.d("JarLoader", "close failed");
        }
    }
}
