package com.github.tvbox.osc.player;

import android.view.ViewGroup;

import androidx.annotation.NonNull;

/**
 * 播放页面的最小契约(引擎挂载入口):渲染容器槽位 + 视图桥 + 服务停止通知。
 * 点播页({@code PlayContainer})与音乐播放页({@code MusicPlayerActivity})各提供一份实现。
 */
public interface PlaybackPage {

    /** 渲染容器槽位(引擎把 {@code VideoView.mPlayerContainer} 搬进来) */
    @NonNull
    ViewGroup renderSlot();

    /** 视图桥(提示/起播/封面/状态读取等) */
    @NonNull
    PlaybackViewBridge viewBridge();

    /** 宿主服务已停止(引擎被释放):页面须放弃对播放器视图的引用 */
    void onServiceStopped();
}
