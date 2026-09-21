package com.github.tvbox.osc.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.util.kv.KVCodec;
import com.tencent.mmkv.MMKV;

/**
 * 全项目键值存储门面(2026-09-13 起为唯一实现,取代 Hawk,见 skill/avbox-kv-mmkv-spec.md)。
 *
 * <p>MMKV 原生承载:String / 基本类型走 MMKV 原生编码,集合与复杂值由 {@link KVCodec} 编解码,
 * 元素类型在 {@link com.github.tvbox.osc.util.kv.KVKeySpec} 集中登记。
 *
 * <p>与旧 Hawk 实现的关键差别:① 类型推断不再靠匿名 TypeToken 捕获类型变量(该写法在 gson 2.13+ 直接抛异常,
 * 是旧集合键全读不出的根因);② 写入失败返回 false 并打 `echo-kv` 日志,不再静默。
 *
 * <p>实例为单进程模式、不加密(spec §4.4):明文 mmap 落在应用私有目录。
 */
public final class KV {

    private static final String MMAP_ID = "avbox_kv";

    private static MMKV store;

    private KV() {
    }

    /**
     * 初始化存储。必须在任何 get/put 之前调用,见 {@code App.initParams}。
     *
     * <p>无数据迁移:应用未发布、无存量用户,Hawk 与其旧库已一并移除(见 spec §8 R1/R2),
     * 首装即原生 MMKV,旧库数据不再搬运。
     *
     * <p>⚠️ 关于"崩溃路径必须落盘":MMKV 是**异步写**(写进 Scheduler,约 1 秒后落盘),且 2.4.2
     * **没有**同步写 flag(只有 {@code SINGLE_PROCESS_MODE} 等模式位),{@code sync()} 也只是等
     * "当前 pending 批"。实测进程级崩溃处理里写的崩溃时刻 **没落盘**(设备上一直停在几分钟前)
     * ⇒ 需要绝对可靠的崩溃记录不能走 KV,见 {@link BootGuard} 的同步标记文件。
     */
    public static void init(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        MMKV.initialize(appContext == null ? context : appContext);
        store = MMKV.mmkvWithID(MMAP_ID, MMKV.SINGLE_PROCESS_MODE);
    }

    /**
     * 写入键值。
     *
     * <p>失败**不静默**:编码失败或落盘失败都打 `echo-kv` 日志并返回 false,调用方可据此处理
     * (业务侧绝大多数调用点忽略返回值也是安全的 —— 失败已在门面内留痕,这是对 Hawk"put 返 false 且
     * 调用方普遍不检查"的直接修正,spec §4.6)。value 为 null 时语义同 Hawk —— 删除该键。
     */
    public static <T> boolean put(@NonNull String key, @Nullable T value) {
        MMKV instance = requireStore();
        if (value == null) {
            instance.removeValueForKey(key);
            return true;
        }
        String encoded;
        try {
            encoded = KVCodec.encode(value);
        } catch (Throwable e) {
            LOG.e("echo-kv encode-failed key=" + key + " valueType=" + value.getClass().getName() + " " + e.getMessage());
            return false;
        }
        boolean ok = instance.encode(key, encoded);
        if (!ok) LOG.e("echo-kv put-failed key=" + key + " valueType=" + value.getClass().getName());
        return ok;
    }

    /** 读取键值;键不存在或解码失败返回 null(需要"不存在即默认值"语义请用带默认值的重载) */
    @Nullable
    public static <T> T get(@NonNull String key) {
        return getInternal(key, null);
    }

    /**
     * 读取键值;键不存在或解码失败返回 defaultValue(解码失败另打 `echo-kv` 日志,不再静默)。
     * 集合与嵌套泛型的元素类型由 {@link com.github.tvbox.osc.util.kv.KVKeySpec} 的注册表决定,不依赖调用侧泛型。
     *
     * <p>带默认值的重载**恒不为 null**(与 spec §4.2 的契约一致),故不标 {@code @Nullable};
     * 需要"不存在即 null"语义请用 {@link #get(String)}。传 null 默认值等价于 {@code get(key)}。
     */
    public static <T> T get(@NonNull String key, @Nullable T defaultValue) {
        return getInternal(key, defaultValue);
    }

    @Nullable
    private static <T> T getInternal(@NonNull String key, @Nullable T defaultValue) {
        return KVCodec.decode(key, requireStore(), defaultValue);
    }

    public static boolean contains(@NonNull String key) {
        return requireStore().containsKey(key);
    }

    public static void delete(@NonNull String key) {
        requireStore().removeValueForKey(key);
    }

    /** 先于 {@link #init} 调用属编码错误:故意抛异常,避免静默降级成"到处读默认值" */
    @NonNull
    private static MMKV requireStore() {
        MMKV instance = store;
        if (instance == null) throw new IllegalStateException("KV.init(Context) 未调用"); // i18n: keep(异常消息)
        return instance;
    }
}
