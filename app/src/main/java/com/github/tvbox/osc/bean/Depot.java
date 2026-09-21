package com.github.tvbox.osc.bean;

import com.github.tvbox.osc.util.LOG;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 多仓(仓库)配置里 {@code urls} 数组的一项 —— 一条子源。
 *
 * <p>对齐 FongMi/TV 的 {@code com.fongmi.android.tv.bean.Depot}:那边是 {@code {"url":…,"name":…}}
 * 经 Gson 直接反序列化,本类保持同样的字段语义,免得两边对"多仓"的理解分叉。
 *
 * <p>比 FongMi 多一层容错 —— 本项目的配置生态里 {@code urls} 有三种写法(见
 * {@link #arrayFrom(JsonArray)}),Gson 直接映射会把字符串写法整条丢掉,所以这里手写遍历。
 */
public class Depot {

    private String url;
    private String name;

    /**
     * 与 TextUtils.isEmpty 等价。
     *
     * <p>⚠️ 刻意不用 {@code android.text.TextUtils}:单测开了
     * {@code testOptions.unitTests.returnDefaultValues = true},那些 Android 桩方法会**静默返回 false**,
     * 于是"空地址过滤"在单测里完全失效、真机上却生效 —— 同一处逻辑两种行为。
     * 同一坑在 {@code ConfigParser} 里已经踩过一次(见那里的同名注释)。
     */
    private static boolean isEmpty(String text) {
        return text == null || text.length() == 0;
    }

    /**
     * 解析 {@code urls} 数组。同时兼容三种写法(老写法必须保留,否则存量仓配置会失效):
     * <ul>
     *   <li>{@code {"url":"…","name":"…"}} —— TVBox 标准多仓格式</li>
     *   <li>{@code {"api":"…","name":"…"}} —— 个别仓用 api 当地址字段名</li>
     *   <li>{@code "http://…"} —— 数组里直接写字符串的简写</li>
     * </ul>
     * 任何异常都按"不是多仓"处理(保留已解析到的条目),不能让仓解析把正常配置加载带崩。
     */
    public static List<Depot> arrayFrom(JsonArray urls) {
        List<Depot> items = new ArrayList<>();
        if (urls == null) return items;
        try {
            for (JsonElement element : urls) {
                if (element == null || element.isJsonNull()) continue;
                Depot depot = new Depot();
                if (element.isJsonObject()) {
                    JsonObject item = element.getAsJsonObject();
                    depot.url = string(item, "url");
                    if (isEmpty(depot.url)) depot.url = string(item, "api");
                    depot.name = string(item, "name");
                } else if (element.isJsonPrimitive()) {
                    // 只收字符串:JsonPrimitive.getAsString() 会把数字 123 也变成 "123",
                    // 当地址收下来会在切换界面留一条永远拉不动的线路
                    JsonElement primitive = element;
                    if (primitive.getAsJsonPrimitive().isString()) depot.url = primitive.getAsString();
                }
                // 空地址进列表只会在切换界面变成一条点不开的线路
                if (!isEmpty(depot.getUrl())) items.add(depot);
            }
        } catch (Throwable th) {
            LOG.d("Depot", "depot urls parse failed, keep items so far");
        }
        return items;
    }

    /**
     * 容错取字符串字段:只认真正的 JSON 字符串。
     * 数字/布尔(如 {@code "name":123})按"没写"处理并回落 url —— 与 {@code DefaultConfig.safeJsonString}
     * 的宽松度保持一致,免得同一份仓配置在两处解出不同的名字。
     */
    static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) return "";
        try {
            return element.getAsJsonPrimitive().isString() ? element.getAsString() : "";
        } catch (Throwable th) {
            return "";
        }
    }

    public String getUrl() {
        return url == null ? "" : url.trim();
    }

    public String getName() {
        String value = name == null ? "" : name.trim();
        return value.isEmpty() ? getUrl() : value;
    }
}
