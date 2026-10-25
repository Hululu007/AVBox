package com.github.avbox.osc.player.core

class TrackInfo {
    val audio: MutableList<TrackInfoBean> = ArrayList()
    val video: MutableList<TrackInfoBean> = ArrayList()
    val subtitle: MutableList<TrackInfoBean> = ArrayList()


    fun audioSelected(track: Boolean): Int = getSelected(audio, track)

    fun subtitleSelected(track: Boolean): Int = getSelected(subtitle, track)

    fun getSelected(list: List<TrackInfoBean>, track: Boolean): Int {
        var i = 0
        for (trackInfoBean in list) {
            if (trackInfoBean.selected) return if (track) trackInfoBean.trackId else i
            i++
        }
        return 99999
    }

    fun addAudio(audio: TrackInfoBean) {
        this.audio.add(audio)
    }


    fun videoSelected(track: Boolean): Int = getSelected(video, track)

    fun addVideo(video: TrackInfoBean) {
        this.video.add(video)
    }


    fun addSubtitle(subtitle: TrackInfoBean) {
        this.subtitle.add(subtitle)
    }
}
