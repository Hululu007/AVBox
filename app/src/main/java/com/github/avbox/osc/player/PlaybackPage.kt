package com.github.avbox.osc.player

import android.view.ViewGroup

interface PlaybackPage {

    fun renderSlot(): ViewGroup

    fun viewBridge(): PlaybackViewBridge

    fun onServiceStopped()

    /**
     * 只播声音、不需要画面的页面（音乐页）。这类页面的渲染视图由
     * [MusicSessionDelegate.ensureAudioOnlyRender] 统一切成 TextureView，
     * 因此接管播放器时不必先按配置重建渲染视图。
     */
    fun isAudioOnlyPage(): Boolean = false
}
