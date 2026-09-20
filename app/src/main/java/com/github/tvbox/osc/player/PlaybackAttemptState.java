package com.github.tvbox.osc.player;

import java.util.HashSet;
import java.util.Set;

/** 播放尝试/线路/解码/会话标记(纯字段,无 IO/视图/handler 依赖)。 */
final class PlaybackAttemptState {

    // ==================== 重试/尝试状态 ====================

    boolean allowSwitchPlayer = true;

    boolean hasAutoSwitchedPlayer = false;

    int autoSwitchedPlayerType = -1;

    /** 自动"硬解→软解"是否已用过;兼任"用户显式选过解码 ⇒ 本次不再自动回退"的阻断标记,只有确有自动态可回滚时才清除 */
    boolean hasAutoSwitchedDecode = false;

    /** 自动切软解前的 cfg.ijk(仅回滚/落库剔除用;null = 不在自动软解态) */
    String autoSwitchedDecodeOld = null;

    /** 自动软解改的是哪个解码键("ijk"/"exo"):回滚与落库剔除按它还原 */
    String autoSwitchedDecodeKey = "ijk";

    /** "起播后错误"自动重播是否已用过(每轮一次) */
    boolean hasRetriedAfterStart = false;

    boolean playbackStarted = false;

    long playTimeoutBasePosition = 0;

    final Set<String> triedLineFlags = new HashSet<>();

    int autoRetryCount = 0;

    long lastRetryTime = 0;

    /** 用户手动点选线路:取流失败/超时不自动换线换源,直接报错停留(避免覆盖用户选择) */
    boolean userPickedLine = false;

    boolean allowAutoSwitchLine = true;

    // ==================== 切换意图(点一次生效一次) ====================

    boolean reusePlayerOnSwitch;

    boolean releasePlayerOnSwitch;

    /** 换源点击即停:置位后抑制在途取流结果/超时/嗅探回调把已停的旧源拉起 */
    boolean switchStopPending;

    /** 换源停播时记下的进度(键+毫秒):取流后写进新源的进度键 */
    String pendingInheritKey;

    long pendingInheritProgress;

    // ==================== 会话标记 ====================

    /** 取流/起播期间不更新通知(避免"旧集通知 → 新集"的中间态) */
    boolean switchingPlayback;

    /** 是否维护了媒体会话(有音频轨就维护;见 updateMusicSession) */
    boolean audioPlayback;

    /**
     * 本次会话确认过"纯音频"的粘滞标记:轨道信息读不到时不得让退后台判定翻转成影视;
     * 不得用于封面判定(封面须实时读取,否则影视被压成海报);复位点仅内容边界(见 beginNewPlay/startSession)。
     */
    boolean audioOnlyConfirmed;

    // ==================== 具名转移(每个赋值只在本段出现一次) ====================

    /** 会话边界:清已起播/停播标记与自动软解原值(留着会把上一轮解码方式回填落库;内容边界标记由调用方管) */
    void beginSession() {
        playbackStarted = false;
        switchStopPending = false;
        autoSwitchedDecodeOld = null;
    }

    /** 新一次播放的清场:重试阶梯 + 内核/解码自动态 + 起播标记 */
    void beginNewPlay() {
        playbackStarted = false;
        playTimeoutBasePosition = 0;
        allowSwitchPlayer = true;
        hasAutoSwitchedPlayer = false;
        hasAutoSwitchedDecode = false;
        hasRetriedAfterStart = false;
    }

    /** 用户自救(重播/切解析/切内核/切解码)后:允许再兜一次底 */
    void userSelfRescue() {
        autoRetryCount = 0;
        hasAutoSwitchedPlayer = false;
        hasRetriedAfterStart = false;
    }

    /** 换源点击即停:清起播标记与复用意图,置"在途结果作废" */
    void stoppedForSourceSwitch() {
        playbackStarted = false;
        playTimeoutBasePosition = 0;
        toggleReuseIntent(false, false);
        switchStopPending = true;
    }

    /** 重试阶梯复位(60s 窗口过期 / 关自动换线):计数 + 切内核额度 + 已试线路 */
    void resetAutoRetryLadder() {
        autoRetryCount = 0;
        allowSwitchPlayer = true;
        hasAutoSwitchedPlayer = false;
        clearTriedLines();
    }

    /** 换线成功:阶梯复位 + 置复用意图(不动 release:自动切过内核时须保持) */
    void onLineSwitched() {
        autoRetryCount = 0;
        allowSwitchPlayer = true;
        hasAutoSwitchedPlayer = false;
        reusePlayerOnSwitch = true;
    }

    /** 无路可走(无剧集数据 / 线路耗尽):清计数与已试线路 */
    void linesExhausted() {
        clearTriedLines();
        autoRetryCount = 0;
    }

    /** 清空已尝试线路(切集/换线/关自动换线) */
    void clearTriedLines() {
        triedLineFlags.clear();
    }

    /** 单字段写,另一个字段不动(避免读-改-写冲掉并发改动) */
    void setReuseIntent(boolean reuse) {
        reusePlayerOnSwitch = reuse;
    }

    void setReleaseIntent(boolean release) {
        releasePlayerOnSwitch = release;
    }

    /** 两个意图一起写 */
    void toggleReuseIntent(boolean reuse, boolean release) {
        reusePlayerOnSwitch = reuse;
        releasePlayerOnSwitch = release;
    }

    /** 取出并复位(release 优先:true = 不复用) */
    boolean consumeReuseIntent() {
        boolean reuse = reusePlayerOnSwitch && !releasePlayerOnSwitch;
        toggleReuseIntent(false, false);
        return reuse;
    }

    /** 清会话标记(退出页面/起播失败/页面销毁三处同集) */
    void clearSessionFlags() {
        switchingPlayback = false;
        audioPlayback = false;
    }
}
