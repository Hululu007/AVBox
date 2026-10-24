package com.github.tvbox.osc.player.core

class TrackInfo {
    private val audio: MutableList<TrackInfoBean> = ArrayList()
    private val video: MutableList<TrackInfoBean> = ArrayList()
    private val subtitle: MutableList<TrackInfoBean> = ArrayList()

    fun getAudio(): MutableList<TrackInfoBean> = audio

    fun getAudioSelected(track: Boolean): Int = getSelected(audio, track)

    fun getSubtitleSelected(track: Boolean): Int = getSelected(subtitle, track)

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

    fun getVideo(): MutableList<TrackInfoBean> = video

    fun getVideoSelected(track: Boolean): Int = getSelected(video, track)

    fun addVideo(video: TrackInfoBean) {
        this.video.add(video)
    }

    fun getSubtitle(): MutableList<TrackInfoBean> = subtitle

    fun addSubtitle(subtitle: TrackInfoBean) {
        this.subtitle.add(subtitle)
    }
}
