package com.github.tvbox.osc.bean;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.KV;

import org.json.JSONException;
import org.json.JSONObject;

import xyz.doikki.videoplayer.player.VideoView;

public class LivePlayerManager {
    JSONObject defaultPlayerConfig = new JSONObject();
    JSONObject currentPlayerConfig;

    public void init(VideoView videoView) {
        try {
            defaultPlayerConfig.put("pl", KV.get(HawkConfig.LIVE_PLAY_TYPE, KV.get(HawkConfig.PLAY_TYPE, 2)));
            if (defaultPlayerConfig.optInt("pl", 2) == 0) {
                defaultPlayerConfig.put("pl", 2);
            }
            defaultPlayerConfig.put("ijk", KV.get(HawkConfig.IJK_CODEC, "硬解码")); // i18n: keep(R1:ijk_codec/exo_decode KV 值与比较键)
            // EXO 解码方式(2026-09-17):与 IJK 的 ijk 键独立,取全局设置(IJK/EXO 各记一份,见 PlaySettingsPage)
            defaultPlayerConfig.put("exo", KV.get(HawkConfig.EXO_DECODE, "硬解码")); // i18n: keep(R1:ijk_codec/exo_decode KV 值与比较键)
            defaultPlayerConfig.put("pr", KV.get(HawkConfig.PLAY_RENDER, 1));
            defaultPlayerConfig.put("sc", KV.get(HawkConfig.LIVE_PLAY_SCALE, 0));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        getDefaultLiveChannelPlayer(videoView);
    }

    public void getDefaultLiveChannelPlayer(VideoView videoView) {
        PlayerHelper.updateCfg(videoView, defaultPlayerConfig);
        try {
            currentPlayerConfig = new JSONObject(defaultPlayerConfig.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * BugReview:异步回调(如代理配置加载)可能在 mVideoView 已释放(init 未执行)时触达播放链路,
     * currentPlayerConfig 此时为 null;统一回落到 defaultPlayerConfig,杜绝 NPE(2026-09-10 22:34 崩溃)
     */
    private JSONObject currentOrDefaultConfig() {
        return currentPlayerConfig != null ? currentPlayerConfig : defaultPlayerConfig;
    }

    public int getLivePlayerType() {
        JSONObject config = currentOrDefaultConfig();
        int playerTypeIndex = 2;
        int playerType = config.optInt("pl", 2);
        String ijkCodec = config.optString("ijk", "硬解码"); // i18n: keep(R1:ijk_codec/exo_decode KV 值与比较键)
        switch (playerType) {
            case 1:
                if (ijkCodec.equals("硬解码")) // i18n: keep(R1:ijk_codec/exo_decode KV 值与比较键)
                    playerTypeIndex = 0;
                else
                    playerTypeIndex = 1;
                break;
            case 2:
                playerTypeIndex = 2;
                break;
        }
        return playerTypeIndex;
    }

    public int getLivePlayerScale() {
        return currentOrDefaultConfig().optInt("sc", 0);
    }

    /**
     * 本次直播播放的**有效 IJK 解码名**("直播配置 → 缺省全局",2026-09-17)。
     *
     * <p>用途:直播切台不像点播那样每次起播都走 {@code PlayerHelper.updateCfg},而 rtmp 频道会强制 IJK、
     * 需要这个值(见 {@code PlayerHelper.applyRtmpSchemeOverride})。配置为空(点播→直播接管路径尚未 init)
     * 时回落全局设置 —— 与改造前的取值完全一致。
     */
    public String effectiveIjkCodecName() {
        return currentOrDefaultConfig().optString("ijk", KV.get(HawkConfig.IJK_CODEC, "硬解码")); // i18n: keep(R1:ijk_codec/exo_decode KV 值与比较键)
    }

    public void changeLivePlayerType(VideoView videoView, int playerType) {
        JSONObject playerConfig;
        try {
            playerConfig = new JSONObject(currentOrDefaultConfig().toString());
        } catch (JSONException e) {
            playerConfig = new JSONObject();
        }
        try {
            switch (playerType) {
                case 0:
                    playerConfig.put("pl", 1);
                    playerConfig.put("ijk", "硬解码"); // i18n: keep(R1:ijk_codec/exo_decode KV 值与比较键)
                    break;
                case 1:
                    playerConfig.put("pl", 1);
                    playerConfig.put("ijk", "软解码"); // i18n: keep(R1:ijk_codec/exo_decode KV 值与比较键)
                    break;
                case 2:
                    playerConfig.put("pl", 2);
                    // EXO(2026-09-17):解码方式按**全局 EXO 设置**走 —— 旧实现是"切到 EXO 就把 ijk 改成软解码",
                    // 那个值随后还会被写进全局 IJK 设置,把用户在 IJK 下的选择一并带偏(同处修掉)
                    playerConfig.put("exo", KV.get(HawkConfig.EXO_DECODE, "硬解码")); // i18n: keep(R1:ijk_codec/exo_decode KV 值与比较键)
                    break;
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        PlayerHelper.updateCfg(videoView, playerConfig);

        try {
            defaultPlayerConfig.put("pl", playerConfig.getInt("pl"));
            defaultPlayerConfig.put("ijk", playerConfig.getString("ijk"));
            defaultPlayerConfig.put("exo", playerConfig.optString("exo", KV.get(HawkConfig.EXO_DECODE, "硬解码"))); // i18n: keep(R1:ijk_codec/exo_decode KV 值与比较键)
            KV.put(HawkConfig.LIVE_PLAY_TYPE, playerConfig.getInt("pl"));
            // 只有 IJK 内核才同步全局 IJK 解码键(2026-09-17):选 EXO 时它的解码值属于 exo 键,不该覆盖 IJK 设置
            if (playerConfig.getInt("pl") == 1) {
                KV.put(HawkConfig.IJK_CODEC, playerConfig.getString("ijk"));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        currentPlayerConfig = playerConfig;
    }

    public boolean switchLivePlayer(VideoView videoView) {
        JSONObject playerConfig = currentPlayerConfig;
        if (playerConfig == null) {
            LOG.i("echo-liveSwitchPlayer: skip empty player config");
            return false;
        }
        try {
            int playerType = playerConfig.getInt("pl");
            int switchPlayerType = (playerType == 1) ? 2 : (playerType == 2) ? 1 : playerType;
            if (switchPlayerType == playerType) {
                LOG.i("echo-liveSwitchPlayer: skip unsupported playerType=" + playerType);
                return false;
            }
            LOG.i("echo-liveSwitchPlayer: " + playerType + " -> " + switchPlayerType);
            playerConfig.put("pl", switchPlayerType);
        } catch (JSONException e) {
            LOG.i("echo-liveSwitchPlayer error: " + e.getMessage());
            return false;
        }
        PlayerHelper.updateCfg(videoView, playerConfig);

        currentPlayerConfig = playerConfig;
        return true;
    }

    public void changeLivePlayerScale(@NonNull VideoView videoView, int playerScale){
        videoView.setScreenScaleType(playerScale);
        KV.put(HawkConfig.LIVE_PLAY_SCALE, playerScale);

        JSONObject playerConfig;
        try {
            playerConfig = new JSONObject(currentOrDefaultConfig().toString());
        } catch (JSONException e) {
            playerConfig = new JSONObject();
        }
        try {
            playerConfig.put("sc", playerScale);
            defaultPlayerConfig.put("sc", playerScale);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        currentPlayerConfig = playerConfig;
    }
}
