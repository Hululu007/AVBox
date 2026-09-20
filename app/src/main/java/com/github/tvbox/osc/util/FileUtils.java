package com.github.tvbox.osc.util;

import android.os.Environment;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.server.ControlManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileUtils {

    public static boolean writeSimple(byte[] data, File dst) {
        try {
            if (dst.exists())
                dst.delete();
            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dst));
            bos.write(data);
            bos.close();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static byte[] readSimple(File src) {
        try {
            BufferedInputStream bis = new BufferedInputStream(new FileInputStream(src));
            int len = bis.available();
            byte[] data = new byte[len];
            bis.read(data);
            bis.close();
            return data;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void copyFile(File source, File dest) throws IOException {
        InputStream is = null;
        OutputStream os = null;
        try {
            is = new FileInputStream(source);
            os = new FileOutputStream(dest);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        } finally {
            is.close();
            os.close();
        }
    }

    public static void recursiveDelete(File file) {
        if (!file.exists())
            return;
        if (file.isDirectory()) {
            for (File f : file.listFiles()) {
                recursiveDelete(f);
            }
        }
        file.delete();
    }

    public static String readFileToString(String path, String charsetName) {
        // 定义返回结果
        String jsonString = "";

        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(new File(path)), charsetName));// 读取文件
            String thisLine = null;
            while ((thisLine = in.readLine()) != null) {
                jsonString += thisLine;
            }
            in.close();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException el) {
                    LOG.d("FileUtils", "close reader failed");
                }
            }
        }
        // 返回拼接好的JSON String
        return jsonString;
    }

    public static String getRootPath() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    public static File getLocal(String path) {
        return new File(path.replace("file:/", getRootPath()));
    }

    public static File getCacheDir() {
        return App.getInstance().getCacheDir();
    }

    public static String getCachePath() {
        return getCacheDir().getAbsolutePath();
    }
    public static String getFilePath() {
        return App.getInstance().getFilesDir().getAbsolutePath();
    }

    public static void cleanDirectory(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;
        for(File one : files) {
            try {
                deleteFile(one);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static boolean isWeekAgo(File file)
    {
        long oneWeekMillis = 3L * 24 * 60 * 60 * 1000;
        long timeDiff = System.currentTimeMillis() - file.lastModified();
        return timeDiff > oneWeekMillis;
    }

    /**
     * 递归删除:文件直接删,目录先删空其内容。
     * ⚠️ 不能把 canWrite() 当删除前提(2026-09-12 修复 Bug):爬虫 jar 释放的加固库是 400 只读
     * (实测 cache/danMugo_v8.so 13,441,840B + cache/.ftyfnw3nr9fd9ykv,两者合计正好是设置页显示的 12.9MB),
     * canWrite() 为 false 时会被直接跳过,于是「清除缓存」怎么点都清不掉、大小恒定不变。
     * 删除只要求**父目录**可写,与文件自身只读无关,故这里先尝试解除只读再删。
     */
    public static void deleteFile(File file) {
        if (!file.exists()) return;
        if (file.isFile()) {
            deleteSingle(file);
            return;
        }
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files == null || files.length == 0) {
                deleteSingle(file);
                return;
            }
            for(File one : files) {
                deleteFile(one);
            }
        }
        return;
    }

    /**
     * 递归强删整个目录树:先删子项(文件/子目录),最后删目录本身。
     * 与 {@link #deleteFile} 的"只清内容、保留各级目录"语义不同,本方法连各级子目录一并删除,
     * 供 {@link #purgeExoCacheIfPending} 彻底删除 exo-video-cache 目录树——
     * 否则多级子目录需多次启动才能逐层清空(表现为 purge-incomplete 逐次递减 10→7→3→done)。
     */
    private static void deleteFileTree(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File one : files) {
                    deleteFileTree(one);
                }
            }
        }
        // 此处 file 已是文件或已清空的空目录:解除只读再删
        deleteSingle(file);
    }

    /** 删除单个文件/空目录;只读项先解除只读再删,仍失败则留日志便于排查 */
    private static void deleteSingle(File file) {
        if (!file.canWrite()) file.setWritable(true);
        if (!file.delete()) {
            LOG.i("clearCache: cannot delete " + file.getAbsolutePath());
        }
    }

    public static void cleanPlayerCache() {
        String ijkCachePath = getCachePath() + "/ijkcaches/";
        String thunderCachePath = getCachePath() + "/thunder/";
        File ijkCacheDir = new File(ijkCachePath);
        File thunderCacheDir = new File(thunderCachePath);
        try {
            if (ijkCacheDir.exists()) cleanDirectory(ijkCacheDir);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            if (thunderCacheDir.exists()) cleanDirectory(thunderCacheDir);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 外置缓存里属于**用户数据**、清理时必须保留的一级目录:config/ = 老的本地源配置副本
     * (2026-09-16 起新副本改放外置 files,见 {@link #getExternalFilesPath()};clan:// 地址直接指向该文件,
     * 删掉等于把订阅源弄丢)。⚠️ 系统「清除缓存」仍会删它(历史遗留:用户数据本不该放在 cacheDir)。
     */
    private static final String EXTERNAL_CACHE_KEEP_DIR = "config";

    /**
     * 应用缓存占用(内部缓存 + 外部缓存,不含 {@link #EXTERNAL_CACHE_KEEP_DIR} 用户数据)。
     * 口径说明:Exo 视频缓存目录 exo-video-cache 位于外部缓存,只统计内部缓存会漏掉绝大部分占用;
     * 外部缓存不可用时 getExternalCacheDir() 返回 null,此时不叠加,避免与内部缓存重复计数。
     * 目录递归统计是耗时 IO,须在后台线程调用。
     */
    public static long getCacheSize() {
        long size = directorySize(getCacheDir(), null);
        File externalCacheDir = App.getInstance().getExternalCacheDir();
        if (externalCacheDir != null && !externalCacheDir.getAbsolutePath().equals(getCachePath())) {
            size += directorySize(externalCacheDir, EXTERNAL_CACHE_KEEP_DIR);
        }
        return size;
    }

    /** [skipChildName] 只在顶层生效:跳过该名字的一级子项(用于排除外置缓存里的用户数据) */
    private static long directorySize(File dir, String skipChildName) {
        if (dir == null || !dir.exists()) return 0;
        if (dir.isFile()) return dir.length();
        File[] files = dir.listFiles();
        if (files == null) return 0;
        long size = 0;
        for (File one : files) {
            if (skipChildName != null && skipChildName.equals(one.getName())) continue;
            size += one.isDirectory() ? directorySize(one, null) : one.length();
        }
        return size;
    }

    /** 字节数 → 展示文本(空缓存显示 0KB);与统计一致,须在后台线程调用 */
    public static String formatCacheSize(long bytes) {
        if (bytes <= 0) return "0KB";
        if (bytes < 1024L * 1024L) return Math.max(1, bytes / 1024) + "KB";
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024);
        return String.format(Locale.US, "%.2fGB", bytes / 1024.0 / 1024 / 1024);
    }

    /** Exo 边播缓存目录名:与 ExoMediaSourceHelper 的共享 SimpleCache 目录保持一致 */
    private static final String EXO_CACHE_DIR_NAME = "exo-video-cache";
    /** 「清除缓存」写下的待清理标记:由 {@link #purgeExoCacheIfPending()} 在下次启动早期执行 */
    private static final String PENDING_EXO_CLEAR_FLAG = "clear_exo_cache.pending";

    /**
     * 清理应用缓存(内部 + 外部,含 exo-video-cache)。2026-09-14 调整:**改为直接删除 exo-video-cache**
     * —— 对齐 fongmi(FileUtil.clearCache → Path.clear(Path.cache) 递归强删整个 cacheDir 含 exo 子目录)。
     * 进程级共享 SimpleCache 常驻,目录被删会"内存索引/磁盘失配",但 CacheDataSource 已设
     * FLAG_IGNORE_CACHE_ON_ERROR 兜底(不崩溃),下次播放缓存 miss 回源,SimpleCache 自愈(移除不存在的 span)。
     * 故点「清除缓存」后数字立即归零,不再延迟到下次启动(此前 2026-09-13 的"写待清理标记 + 下次启动 purge"
     * 方案被本次替换;{@link #purgeExoCacheIfPending()} 保留以清理历史遗留标记)。
     * 用 {@link #deleteFileTree} 递归强删整个目录树(含各级子目录),避免 deleteFile "只清内容保留目录"留下空子目录。
     * 与系统设置里的「清除缓存」同语义,因此也会清掉已下载的爬虫 jar 缓存(cache/jar/),
     * 下次按需重新下载;{@link #EXTERNAL_CACHE_KEEP_DIR} 用户数据(config 订阅源)保留不动。
     * 耗时 IO,须在后台线程调用。
     */
    public static void clearCache() {
        // ① 内部缓存:逐项强删(含回落到内部的 exo-video-cache,不再跳过)
        File cacheDir = getCacheDir();
        File[] innerFiles = cacheDir.listFiles();
        if (innerFiles != null) {
            for (File one : innerFiles) {
                try {
                    deleteFileTree(one);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        // ② 外部缓存:逐项强删,跳过 config(用户订阅源数据)
        File externalCacheDir = App.getInstance().getExternalCacheDir();
        if (externalCacheDir == null) return;
        File[] files = externalCacheDir.listFiles();
        if (files == null) return;
        for (File one : files) {
            if (EXTERNAL_CACHE_KEEP_DIR.equals(one.getName())) continue;
            try {
                deleteFileTree(one);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 「清除缓存」遗留的 Exo 视频缓存清理(2026-09-13):在 App 启动早期调用,
     * **必须早于 ExoMediaSourceHelper.getSharedCache 的首次创建**(SimpleCache 尚未持有目录/索引时删除才安全)。
     * 无待清理标记时仅一次文件存在性检查,零开销;有标记时删除 exo-video-cache 目录(含索引),
     * 内容清空才清除标记,否则保留标记待下次启动重试(删除中途进程被杀也保留标记,下次继续)。
     * 目录删除为耗时 IO,须在后台线程调用。
     */
    public static void purgeExoCacheIfPending() {
        File flag = pendingExoClearFlag();
        if (!flag.exists()) return;
        File exoDir = new File(getExternalCachePath(), EXO_CACHE_DIR_NAME);
        LOG.i("echo-exo-cache-purge-start: " + exoDir.getAbsolutePath());
        try {
            // 强删整个目录树(含各级子目录):deleteFile 只清内容、保留各级目录,会留空子目录导致
            // purge-incomplete 逐次递减(10→7→3→done),需多次启动才清干净
            deleteFileTree(exoDir);
        } catch (Exception e) {
            e.printStackTrace();
        }
        File[] remaining = exoDir.exists() ? exoDir.listFiles() : null;
        if (remaining == null || remaining.length == 0) {
            if (exoDir.exists()) exoDir.delete(); // 兜底:理论上 deleteFileTree 已删,防御性再删一次
            flag.delete(); // 确认清理完成后才清标记:删除失败/进程中途被杀都保留标记,下次启动继续
            LOG.i("echo-exo-cache-purge-done");
        } else {
            // 详情取证:逐项打剩余项的类型/可写/大小/路径,定位强删后仍删不掉的根因(疑似只读/被占用)
            for (File r : remaining) {
                LOG.i("echo-exo-cache-remain: type=" + (r.isDirectory() ? "DIR" : "FILE")
                        + " writable=" + r.canWrite()
                        + " size=" + r.length()
                        + " path=" + r.getAbsolutePath());
            }
            // 少量文件删除失败:保留标记,下次启动重试
            LOG.i("echo-exo-cache-purge-incomplete: " + remaining.length);
        }
    }

    /** 「清除缓存」遗留的待清理标记文件(见 {@link #clearCache()} 的说明) */
    private static File pendingExoClearFlag() {
        return new File(getFilePath(), PENDING_EXO_CLEAR_FLAG);
    }

    public static void clearSpiderCacheFiles() {
        cleanDirectory(new File(getFilePath() + "/csp/"));
        cleanDirectory(new File(getCachePath() + "/jar/"));
        cleanDirectory(new File(getCachePath() + "/py/"));
        cleanDirectory(new File(getCachePath() + "/catvod_jsapi/"));
        clearJsModuleCache();
    }

    private static void clearJsModuleCache() {
        File externalCacheDir = new File(getExternalCachePath());
        File[] files = externalCacheDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file != null && file.getName().startsWith("qjscache_")) {
                deleteFile(file);
            }
        }
    }

    public static String read(String path) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(getLocal(path))));
            StringBuilder sb = new StringBuilder();
            String text;
            while ((text = br.readLine()) != null) sb.append(text).append("\n");
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String getFileName(String filePath){
        if(TextUtils.isEmpty(filePath)) return "";
        String fileName = filePath;
        int p = fileName.lastIndexOf(File.separatorChar);
        if(p != -1){
            fileName = fileName.substring(p + 1);
        }
        return fileName;
    }

    public static String getFileNameWithoutExt(String filePath){
        if(TextUtils.isEmpty(filePath)) return "";
        String fileName = filePath;
        int p = fileName.lastIndexOf(File.separatorChar);
        if(p != -1){
            fileName = fileName.substring(p + 1);
        }
        p = fileName.indexOf('.');
        if(p != -1){
            fileName = fileName.substring(0, p);
        }
        return fileName;
    }


    public static boolean hasExtension(String path) {
        int lastDotIndex = path.lastIndexOf(".");
        int lastSlashIndex = Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"));
        // 如果路径中有点号，并且点号在最后一个斜杠之后，认为有后缀
        return lastDotIndex > lastSlashIndex && lastDotIndex < path.length() - 1;
    }

    public static void saveCache(File cache,String json){
        try {
            File cacheDir = cache.getParentFile();
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            if (cache.exists())
                cache.delete();
            FileOutputStream fos = new FileOutputStream(cache);
            fos.write(json.getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }





    //JS  工具方法
    private static final Pattern URL_JOIN = Pattern.compile("^http.*\\.(js|txt|json)", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    public static String loadModule(String name) {
        String rel = null;
        try {
            if (name.contains("gbk.js")) {
                name = "gbk.js";
            } else if (name.contains("模板.js")) {
                name = "模板.js";
            } else if (name.contains("cat.js")) {
                name = "cat.js";
            }
            LOG.i("echo-loadModule "+name);
            Matcher m = URL_JOIN.matcher(name);
            if (m.find()) {
                String cache = getCache(MD5.encode(name));
                rel= cache;
                if (StringUtils.isEmpty(cache)) {
                    String netStr = get(name);
                    if (!TextUtils.isEmpty(netStr)) {
                        setCache(604800, MD5.encode(name), netStr);
                    }
                    rel= netStr;
                }
            } else if (name.startsWith("assets://")) {
                rel= getAsOpen(name.substring(9));
            } else if (isAsFile(name, "js/lib")) {
                rel=getAsOpen("js/lib/" + name);
            } else if (name.startsWith("file://")) {
                rel=get(ControlManager.get()
                        .getAddress(true) + "file/" + name.replace("file:///", "")
                        .replace("file://", ""));
            } else if (name.startsWith("clan://localhost/")) {
                rel=get(ControlManager.get()
                        .getAddress(true) + "file/" + name.replace("clan://localhost/", ""));
            } else if (name.startsWith("clan://")) {
                String substring = name.substring(7);
                int indexOf = substring.indexOf(47);
                rel=get("http://" + substring.substring(0, indexOf) + "/file/" + substring.substring(indexOf + 1));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return rel;
    }


    // BugReview #26:被 NanoHTTPD 线程、主线程、QuickJS 线程并发读写,改并发容器
    private static final Map<String, Set<String>> cachedDirFiles = new ConcurrentHashMap<>();
    public static boolean isAsFile(String name,String dir) {
        // 1. 先从缓存里取目录列表
        Set<String> files = cachedDirFiles.get(dir);
        if (files == null) {
            LOG.i("echo-读取AssetsList");
            try {
                String[] list = App.getInstance().getAssets().list(dir);
                files = new HashSet<>(Arrays.asList(list));
            } catch (IOException e) {
                files = Collections.emptySet();
            }
            cachedDirFiles.put(dir, files);
        }
        // 2. 内存查找
        return files.contains(name.trim());
    }

    public static String getAsOpen(String name) {
        try {
            InputStream is = App.getInstance().getAssets().open(name);
            byte[] data = new byte[is.available()];
            is.read(data);
            return new String(data, "UTF-8");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    public static String getCache(String name) {
        try {
            String code = "";
            File file = open(name);
            if (file.exists()) {
                code = new String(readSimple(file));
            }
            if (TextUtils.isEmpty(code)) {
                return "";
            }
            JsonObject asJsonObject = (new Gson().fromJson(code, JsonObject.class)).getAsJsonObject();
            if (((long) asJsonObject.get("expires").getAsInt()) <= System.currentTimeMillis() / 1000) {
                recursiveDelete(open(name));
            }
            return asJsonObject.get("data").getAsString();
        } catch (Exception e4) {
            return "";
        }
    }

    public static void setCache(int time, String name, String data) {
        try {
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("expires", (int) (time + (System.currentTimeMillis() / 1000)));
            jSONObject.put("data", data);
            writeSimple(jSONObject.toString().getBytes(), open(name));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void setCacheByte(String name, byte[] data) {
        try {
            writeSimple(byteMerger("//DRPY".getBytes(), Base64.encode(data, Base64.URL_SAFE)), open("B_" + name));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static byte[] byteMerger(byte[] bt1, byte[] bt2){
        byte[] bt3 = new byte[bt1.length+bt2.length];
        System.arraycopy(bt1, 0, bt3, 0, bt1.length);
        System.arraycopy(bt2, 0, bt3, bt1.length, bt2.length);
        return bt3;
    }

    public static String get(String str) {
        return get(str, null);
    }

    public static String get(String str, Map<String, String> headerMap) {
        if (headerMap == null) {
            headerMap=new HashMap<>();
            headerMap.put("User-Agent",str.startsWith("https://gitcode.net/") ? UA.random() : "okhttp/3.15");
        }
        return OkHttp.string(str, headerMap);
    }

    public static File open(String str) {
        return new File(getExternalCachePath() + "/qjscache_" + str + ".js");
    }
    public static String getExternalCachePath() {
        File externalCacheDir = App.getInstance().getExternalCacheDir();
        if (externalCacheDir == null){
            return getCachePath();
        }
        return externalCacheDir.getAbsolutePath();
    }

    /**
     * 外置私有 files 目录:本地源配置副本等用户数据的落点(2026-09-16 起由外置 cache 迁来,避免被
     * 系统「清除缓存」删掉后只能静默回落到 filesDir 旧快照);外置不可用时回落内部 files 目录。
     */
    public static String getExternalFilesPath() {
        File externalFilesDir = App.getInstance().getExternalFilesDir(null);
        if (externalFilesDir == null) {
            return getFilePath();
        }
        return externalFilesDir.getAbsolutePath();
    }
}
