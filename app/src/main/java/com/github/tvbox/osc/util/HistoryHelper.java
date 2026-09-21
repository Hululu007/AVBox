package com.github.tvbox.osc.util;

import com.github.tvbox.osc.util.KV;
import java.util.ArrayList;

public class HistoryHelper {
    private static final Integer[] hisNumArray = {30,50,100};
    private static final String API_LINE_SPLIT = "\t";

    public static String getHistoryNumName(int index){
        Integer value = getHisNum(index);
        return value + "条";
    }

    public static int getHisNum(int index){
        Integer value = null;
        if(index>=0 && index < hisNumArray.length){
            value = hisNumArray[index];
        }else{
            value = hisNumArray[0];
        }
        return value;
    }

    /**
     * 无痕模式(2026-09-12):开启后不写入搜索历史与观看历史(含播放进度),收藏不受影响。
     * 判定收敛在这里,所有历史写入侧统一调用 —— 目前是 [setSearchHistory] 与
     * `RoomDataManger.insertVodRecord`(观看历史 + 进度的唯一落库点)。
     */
    public static boolean isIncognito(){
        return KV.get(HawkConfig.INCOGNITO, false);
    }

    public static void setSearchHistory(String title){
        // 无痕模式:不记录新的搜索历史(已有历史照常展示,清空/删除仍可用)
        if (isIncognito()) return;
        // 读取历史记录
        ArrayList<String> history = KV.get(HawkConfig.SEARCH_HISTORY, new ArrayList<String>());
        history.remove(title);
        history.add(0, title);
        // 保证最多只保留 20 条，超过的就删除最后一条
        if (history.size() > 20) {
            history.remove(history.size() - 1);
        }
        KV.put(HawkConfig.SEARCH_HISTORY, history);
    }

    public static void clearSearchHistory(){
        KV.put(HawkConfig.SEARCH_HISTORY, new ArrayList<String>());
    }

    /** 删除单条搜索历史(2026-09-11:搜索页长按历史 chip) */
    public static void removeSearchHistory(String title){
        ArrayList<String> history = KV.get(HawkConfig.SEARCH_HISTORY, new ArrayList<String>());
        history.remove(title);
        KV.put(HawkConfig.SEARCH_HISTORY, history);
    }

    public static void setLiveApiHistory(String value){
        ArrayList<String> history = KV.get(HawkConfig.LIVE_API_HISTORY, new ArrayList<String>());
        if (!history.contains(value)) {
            history.add(0, value);
        }
        if (history.size() > 30) {
            history.remove(30);
        }
        KV.put(HawkConfig.LIVE_API_HISTORY, history);
    }

    public static void setApiHistory(String value){
        ArrayList<String> history = KV.get(HawkConfig.API_HISTORY, new ArrayList<String>());
        if (!history.contains(value)) {
            history.add(0, value);
        }
        if (history.size() > 30) {
            history.remove(30);
        }
        KV.put(HawkConfig.API_HISTORY, history);
    }

    public static String buildApiLine(String name, String url) {
        String lineName = name == null ? "" : name.trim();
        String lineUrl = url == null ? "" : url.trim();
        if (lineName.isEmpty()) {
            lineName = lineUrl;
        }
        return lineName + API_LINE_SPLIT + lineUrl;
    }

    public static String getApiLineName(String value) {
        if (value == null) return "";
        int splitIndex = value.indexOf(API_LINE_SPLIT);
        String name = splitIndex >= 0 ? value.substring(0, splitIndex) : value;
        return name.trim();
    }

    public static String getApiLineUrl(String value) {
        if (value == null) return "";
        int splitIndex = value.indexOf(API_LINE_SPLIT);
        String url = splitIndex >= 0 ? value.substring(splitIndex + API_LINE_SPLIT.length()) : value;
        return url.trim();
    }

    public static boolean isApiLineUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String trimUrl = url.trim();
        ArrayList<String> apiLines = KV.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
        for (String apiLine : apiLines) {
            if (trimUrl.equals(getApiLineUrl(apiLine))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isApiLineSource(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String source = KV.get(HawkConfig.API_LINE_SOURCE, "");
        return url.trim().equals(source);
    }

    public static boolean isApiLineHistory(String url) {
        return isApiLineSource(url) || isApiLineUrl(url);
    }

    public static void clearApiLineList() {
        KV.put(HawkConfig.API_LINE_LIST, new ArrayList<String>());
        KV.put(HawkConfig.API_LINE_SOURCE, "");
        ApiLineSignal.INSTANCE.notifyChanged();
    }

    /** 点播仓列表(每项 "名字\t链接"),空列表表示当前不是多仓点播源 */
    public static ArrayList<String> getApiLines() {
        return KV.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
    }

    // ---- 直播侧多仓(2026-09-21):与点播四个判定一一对应,只是换 LIVE_API_LINE_LIST 这一对键 ----

    /** 这个地址是不是某个仓里的子源(决定直播「配置切换」列仓列表还是配置历史) */
    public static boolean isLiveApiLineUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String trimUrl = url.trim();
        ArrayList<String> apiLines = KV.get(HawkConfig.LIVE_API_LINE_LIST, new ArrayList<String>());
        for (String apiLine : apiLines) {
            if (trimUrl.equals(getApiLineUrl(apiLine))) {
                return true;
            }
        }
        return false;
    }

    /** 这个地址本身是不是"仓地址"(用户当初填的那个仓库链接,而不是仓里的子源) */
    public static boolean isLiveApiLineSource(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        return url.trim().equals(KV.get(HawkConfig.LIVE_API_LINE_SOURCE, ""));
    }

    /** 直播源来自仓(仓地址本身或仓里某条子源)—— 换源时据此决定要不要清空仓列表 */
    public static boolean isLiveApiLineHistory(String url) {
        return isLiveApiLineSource(url) || isLiveApiLineUrl(url);
    }

    /** 直播仓列表(每项 "名字\t链接"),空列表表示当前不是多仓直播源 */
    public static ArrayList<String> getLiveApiLines() {
        return KV.get(HawkConfig.LIVE_API_LINE_LIST, new ArrayList<String>());
    }

    public static void clearLiveApiLineList() {
        KV.put(HawkConfig.LIVE_API_LINE_LIST, new ArrayList<String>());
        KV.put(HawkConfig.LIVE_API_LINE_SOURCE, "");
        ApiLineSignal.INSTANCE.notifyChanged();
    }

    /**
     * 这个地址是不是"当前点播仓的来源地址"。
     *
     * <p>多仓加载会把 {@code API_URL} 改写成仓里第一条子源的地址,于是订阅列表里那条仓地址与
     * 当前地址不再相等 —— 凡拿订阅地址跟当前地址比对的 UI(配置管理页"使用中")都要一并认这种关系,
     * 否则切到仓之后订阅卡全部显示未使用。要求仓处于生效态,避免清场后残留误判。
     */
    public static boolean isApiLineSourceOf(String url, String activeUrl) {
        if (url == null || url.trim().isEmpty()) return false;
        if (!isApiLineUrl(activeUrl)) return false;
        return url.trim().equals(KV.get(HawkConfig.API_LINE_SOURCE, ""));
    }

    /** 同 {@link #isApiLineSourceOf},直播侧 */
    public static boolean isLiveApiLineSourceOf(String url, String activeUrl) {
        if (url == null || url.trim().isEmpty()) return false;
        if (!isLiveApiLineUrl(activeUrl)) return false;
        return url.trim().equals(KV.get(HawkConfig.LIVE_API_LINE_SOURCE, ""));
    }
}
