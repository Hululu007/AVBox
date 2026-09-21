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
 * <p>字段语义对齐 FongMi/TV 的 {@code com.fongmi.android.tv.bean.Depot};但解析手写遍历,
 * 因为本项目的 {@code urls} 有三种写法(Gson 直接映射会把裸字符串那条整条丢掉)。
 */
public class Depot {

    private String url;
    private String name;

    /**
     * 与 TextUtils.isEmpty 等价 —— 不用 android.text.TextUtils:单测开了 returnDefaultValues,
     * 它会静默返回 false,判空在单测里失效(本仓已踩三次)。
     */
    private static boolean isEmpty(String text) {
        return text == null || text.length() == 0;
    }

    /**
     * 解析 {@code urls} 数组,兼容 {@code {"url":…}}、{@code {"api":…}} 与裸字符串三种写法;
     * 空地址条目丢弃,任何异常按"不是多仓"处理(保留已解析到的条目)。
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
                } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    // 只收字符串:getAsString() 会把数字 123 也变成 "123"
                    depot.url = element.getAsString();
                }
                if (!isEmpty(depot.getUrl())) items.add(depot);
            }
        } catch (Throwable th) {
            LOG.d("Depot", "depot urls parse failed, keep items so far");
        }
        return items;
    }

    /** 取字符串字段:非字符串(数字等)按"没写"处理并回落 url,与 DefaultConfig.safeJsonString 同宽松度 */
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
