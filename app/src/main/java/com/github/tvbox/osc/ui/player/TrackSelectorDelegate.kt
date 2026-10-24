package com.github.tvbox.osc.ui.player

import android.content.Context
import android.widget.Toast
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.TrackInfoBean
import com.github.tvbox.osc.player.state.PlayerUiState
import com.github.tvbox.osc.player.state.SelectDialogState
import com.github.tvbox.osc.util.LOG

class TrackSelectorDelegate(private val host: Host) {

    interface Host {
        fun player(): MyVideoView?

        fun context(): Context

        fun uiState(): PlayerUiState
    }

    fun selectAudioTrack() {
        val view = host.player() ?: return
        val context = host.context()
        val trackInfo = view.mediaPlayer?.getTrackInfo()
        if (trackInfo == null) {
            Toast.makeText(context, context.getString(R.string.player_no_audio_track), Toast.LENGTH_SHORT).show()
            return
        }
        val bean = trackInfo.getAudio()
        if (bean.size < 1) return
        val names = ArrayList<String>()
        for (item in bean) names.add(item.name ?: "")
        val selected = trackInfo.getAudioSelected(false)
        LOG.i(
            "echo-setTrack list: count=" + bean.size +
                " selected=" + selected + " names=" + names,
        )
        host.uiState().openSelectDialog(SelectDialogState(
            context.getString(R.string.player_switch_audio_track),
            names,
            selected,
        ) { pos ->
            if (pos >= 0 && pos < bean.size) {
                val value = bean[pos]
                for (audio in bean) {
                    audio.selected = isSameTrack(audio, value)
                }
                switchTrack(value, false)
            }
        })
    }

    fun selectVideoTrack() {
        val view = host.player() ?: return
        val context = host.context()
        val trackInfo = view.mediaPlayer?.getTrackInfo()
        if (trackInfo == null || trackInfo.getVideo().isEmpty()) {
            Toast.makeText(context, context.getString(R.string.player_no_video_track), Toast.LENGTH_SHORT).show()
            return
        }
        val tracks = trackInfo.getVideo()
        val names = ArrayList<String>()
        for (item in tracks) names.add(item.name ?: "")
        host.uiState().openSelectDialog(SelectDialogState(
            context.getString(R.string.player_switch_video_track),
            names,
            trackInfo.getVideoSelected(false),
        ) { pos ->
            if (pos >= 0 && pos < tracks.size) {
                val value = tracks[pos]
                for (track in tracks) {
                    track.selected = isSameTrack(track, value)
                }
                switchTrack(value, true)
            }
        })
    }

    private fun switchTrack(track: TrackInfoBean, seekBack: Boolean) {
        val view = host.player() ?: return
        val progress = view.currentPosition
        LOG.i(
            "echo-setTrack request: name=" + track.name + " render=" + track.renderId +
                " group=" + track.trackGroupId + " track=" + track.trackId +
                " pos=" + progress + " state=" + view.playState,
        )
        try {
            view.selectTrack(track)
            if (seekBack) view.seekTo(progress)
            LOG.i("echo-setTrack done: state=" + view.playState)
        } catch (e: Exception) {
            LOG.e("echo-setTrack error:" + e.message)
        }
    }

    companion object {

        @JvmStatic
        fun isSameTrack(left: TrackInfoBean, right: TrackInfoBean): Boolean =
            left.renderId == right.renderId &&
                left.trackGroupId == right.trackGroupId &&
                left.trackId == right.trackId
    }
}
