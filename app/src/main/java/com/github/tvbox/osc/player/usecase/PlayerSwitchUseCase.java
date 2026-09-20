package com.github.tvbox.osc.player.usecase;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.thunder.Jianpian;
import com.github.tvbox.osc.util.thunder.Thunder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 播放器切换 / URL 工具 / 旁路资源停止 用例
 * （从 VodController:1681-1908 剥离，Compose 化改造 阶段 0）。
 */
public final class PlayerSwitchUseCase {

    /** 播放器类型变化后由控制层刷新 UI 配置并通知宿主 */
    public interface SwitchCallback {
        void onPlayerConfigChanged();
    }

    private PlayerSwitchUseCase() {
    }

    /**
     * EXO(1)/IJK(2) 互切：返回 true 表示跳过（当前类型不支持或切换失败）。
     */
    public static boolean switchPlayer(JSONObject playerConfig, SwitchCallback callback) {
        try {
            int playerType = playerConfig.getInt("pl");
            int p_type = (playerType == 1) ? playerType + 1 : (playerType == 2) ? playerType - 1 : playerType;
            if (p_type != playerType) {
                LOG.i("echo-switchPlayer: " + playerType + " -> " + p_type);
                playerConfig.put("pl", p_type);
                callback.onPlayerConfigChanged();
            } else {
                LOG.i("echo-switchPlayer: skip unsupported playerType=" + playerType);
                return true;
            }
        } catch (Exception e) {
            LOG.i("echo-switchPlayer error: " + e.getMessage());
            return true;
        }
        return false;
    }

    public static String encodeUrl(String url) {
        try {
            return java.net.URLEncoder.encode(url, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }

    public static String firstUrlByArray(String url) {
        try {
            JSONArray urlArray = new JSONArray(url);
            for (int i = 0; i < urlArray.length(); i++) {
                String item = urlArray.getString(i);
                if (item.contains("http")) {
                    url = item;
                    break; // 找到第一个立即终止循环
                }
            }
        } catch (JSONException e) {
            LOG.d("PlayerSwitchUseCase", "url is not a json array, keep raw");
        }
        return url;
    }

    public static void stopOther() {
        Thunder.stop(false);//停止磁力下载
        Jianpian.finish();//停止p2p下载
        App.getInstance().setDashData(null);
    }
}
