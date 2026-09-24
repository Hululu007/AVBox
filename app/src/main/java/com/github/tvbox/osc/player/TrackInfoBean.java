package com.github.tvbox.osc.player;

public class TrackInfoBean {
    public int trackId;
    public int renderId;
    public int trackGroupId;
    public String name;
    public String language;
    public int groupIndex;
    public int index;
    public boolean selected;
    public boolean bitmapSubtitle;
    /** 轨道类型(media3 C.TRACK_TYPE_*) */
    public int type;
    /** 轨道指纹(见 TrackMemory):跨集定位只认它 */
    public String formatKey;
}
