package com.github.tvbox.osc.player.controller;

import android.webkit.WebView;
import android.widget.TextView;

import androidx.media3.ui.SubtitleView;

import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.player.state.PlayerUiState;
import com.github.tvbox.osc.subtitle.widget.SimpleSubtitleView;

import org.json.JSONObject;

import java.util.HashMap;

/**
 * 播放器控制层对外统一契约（Compose 化改造 §5.2）。
 * <p>
 * {@link ComposeVideoController}（新/Compose 实现，阶段 8 后唯一实现）实现本接口；
 * {@code PlayContainer} 与 {@code DanmuLoadController} 只依赖本接口。
 */
public interface PlayerControlApi {

    // ---- PlayContainer 直接操作的视图（§5.4，保留原生 View 引用，不做 Compose 重写） ----

    SimpleSubtitleView getSubtitleView();

    SimpleSubtitleView getLyricView();

    SubtitleView getExoSubtitleView();

    /**
     * 控制层 Compose UI 状态（Step 6 对话框 sheet 化：PlayContainer 经此写入
     * 弹幕/字幕/投屏面板状态与音轨选择弹窗，替代直接 new View 对话框）。
     */
    PlayerUiState getUiState();

    // ---- 对外回调（§5.1，签名不变；阶段 8 起为顶层 VodControlListener 接口） ----

    void setListener(VodControlListener listener);

    // ---- 配置 / 状态 ----

    void setPlayerConfig(JSONObject playerCfg);

    void showParse(boolean userJxList);

    void setPreviewMode(boolean previewMode);

    void setTitle(String playTitleInfo);

    void setUrlTitle(String playTitleInfo);

    void setHasDanmu(boolean hasDanmu);

    // ---- 手势开关（BaseController 语义） ----

    void setCanChangePosition(boolean canChangePosition);

    void setEnableInNormal(boolean enableInNormal);

    void setGestureEnabled(boolean gestureEnabled);

    // ---- 行为 ----

    /** 切换控制栏显隐（详情页预览态点击视频区唤起/收起菜单，宿主经 PlayContainer 调用） */
    void toggleControlBar();

    void hidePauseRoot();

    void setLifecyclePaused(boolean paused);

    void resetSpeed();

    boolean onBackPressed();

    boolean switchPlayer();

    void stopOther();

    // ---- 非 UI 工具（usecase 委托，阶段 0 剥离） ----

    void playM3u8(String url, HashMap<String, String> headers);

    String encodeUrl(String url);

    String firstUrlByArray(String url);

    void evaluateScript(SourceBean sourceBean, String url, WebView view);

    String getWebPlayUrlIfNeeded(String webPlayUrl);
}
