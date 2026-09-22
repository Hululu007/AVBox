package com.github.tvbox.osc.bean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

public class SourceBean {
    private String key;
    private String name;
    private String api;
    private int type;   // 0 xml 1 json 3 Spider
    private int searchable; // 是否可搜索
    private int quickSearch; // 是否可以快速搜索
    private int changeable = 1;
    private int filterable; // 是否可以站点选择
    private String playerUrl; // 站点解析Url
    private String ext; // 扩展数据
    private String jar; // 自定义jar
    private ArrayList<String> categories = null; // 分类&排序
    private int playerType; // 1 ijk 2 exo 10 mxplayer -1 以参数设置页面的为准
    private int timeout; // 站点播放信息获取超时，单位秒
    private String clickSelector; // 需要点击播放的嗅探站点selector   ddrk.me;#id
    private String style; // 展示风格
    private String icon; // 站点头像/logo
    private int hide; // 1=从站点切换列表隐藏
    private int indexs; // 1=索引型源:卡片只是关键词入口,只走搜索不进详情
    private int danmaku = 1; // 0=本站不通过全局弹幕 API 自动搜弹幕
    private Map<String, String> header; // 站点级请求头(type 0/1/4 的接口请求会带,并作为播放请求头的兜底)

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    public String getKey() {
        return safeString(key);
    }

    public void setKey(String key) {
        this.key = safeString(key);
    }

    public String getName() {
        return safeString(name);
    }

    public void setName(String name) {
        this.name = safeString(name);
    }

    public String getApi() {
        return safeString(api);
    }

    public void setApi(String api) {
        this.api = safeString(api);
    }

    public void setPlayerUrl(String playerUrl) {
        this.playerUrl = safeString(playerUrl);
    }

    public String getPlayerUrl() {
        return safeString(playerUrl);
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public boolean isSearchable() {
        return searchable != 0;
    }

    public void setSearchable(int searchable) {
        this.searchable = searchable;
    }

    public boolean isQuickSearch() {
        return quickSearch != 0;
    }

    public void setQuickSearch(int quickSearch) {
        this.quickSearch = quickSearch;
    }

    public boolean isChangeable() {
        return changeable != 0;
    }

    public void setChangeable(int changeable) {
        this.changeable = changeable;
    }

    public int getFilterable() {
        return filterable;
    }

    public void setFilterable(int filterable) {
        this.filterable = filterable;
    }

    public String getExt() {
        return safeString(ext);
    }

    public void setExt(String ext) {
        this.ext = safeString(ext);
    }

    public ArrayList<String> getCategories() {
        return categories;
    }

    public void setCategories(ArrayList<String> categories) {
        this.categories = categories;
    }

    public String getJar() {
        return safeString(jar);
    }

    public void setJar(String jar) {
        this.jar = safeString(jar);
    }

    public int getPlayerType() { return playerType; }

    public void setPlayerType(int playerType) { this.playerType = playerType; }

    public int getTimeout() { return timeout; }

    public void setTimeout(int timeout) { this.timeout = timeout; }

    public int getPlayTimeoutSeconds() {
        return timeout > 0 ? Math.max(5, Math.min(60, timeout)) : 15;
    }

    public String getClickSelector() { return safeString(clickSelector); }

    public void setClickSelector(String clickSelector) { this.clickSelector = safeString(clickSelector); }


    public String getStyle() { return safeString(style); }

    public void setStyle(String style) { this.style = safeString(style); }

    public String getIcon() { return safeString(icon); }

    public void setIcon(String icon) { this.icon = safeString(icon); }

    /** 未配置时返回只读空表:调用方不必判空,但不要修改返回值 */
    public Map<String, String> getHeader() {
        return header == null ? Collections.emptyMap() : header;
    }

    public void setHeader(Map<String, String> header) { this.header = header; }

    public boolean isHidden() { return hide == 1; }

    public void setHide(int hide) { this.hide = hide; }

    public boolean isIndexSource() { return indexs == 1; }

    public void setIndexs(int indexs) { this.indexs = indexs; }

    public boolean isDanmakuEnabled() { return danmaku != 0; }

    public void setDanmaku(int danmaku) { this.danmaku = danmaku; }
}
