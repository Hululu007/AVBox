package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;
import android.os.Build;

import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.XXPermissions;
import com.hjq.permissions.permission.PermissionLists;
import com.hjq.permissions.permission.base.IPermission;

/**
 * 权限统一入口(XXPermissions 28.x 对象模型 API)。
 *
 * <p>存储权限:本应用需要浏览/读写 /sdcard 任意路径(本地 jar、备份、文件浏览器、局域网共享):
 * Android 11+ 该能力依赖 MANAGE_EXTERNAL_STORAGE(系统"所有文件访问"设置页,无弹窗);
 * Android 10- 走 READ/WRITE 弹窗。Android 13+ 的旧存储权限会被系统静默拒绝,不可再依赖。
 *
 * <p>通知权限(2026-09-13 补):`POST_NOTIFICATIONS` 自 Android 13(API 33)起是**运行时权限**,
 * 清单里声明了也必须显式申请,否则音乐后台播放的**前台服务通知不显示**(服务本身能起,
 * 但用户看不到播放控制,且部分 ROM 会限制无可见通知的前台服务)。
 */
public final class PermissionHelper {

    /**
     * 本进程是否已经为通知权限弹过窗。**一次性闸门**,见 {@link #requestNotificationIfNeeded}。
     */
    private static volatile boolean notificationAsked;

    private PermissionHelper() {
    }

    public static boolean isStorageGranted(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return XXPermissions.isGrantedPermission(context, PermissionLists.getManageExternalStoragePermission());
        }
        return XXPermissions.isGrantedPermissions(context, new IPermission[]{
                PermissionLists.getReadExternalStoragePermission(),
                PermissionLists.getWriteExternalStoragePermission()});
    }

    public static void requestStorage(Activity activity, OnPermissionCallback callback) {
        IPermission[] permissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? new IPermission[]{PermissionLists.getManageExternalStoragePermission()}
                : new IPermission[]{
                        PermissionLists.getReadExternalStoragePermission(),
                        PermissionLists.getWriteExternalStoragePermission()};
        XXPermissions.with(activity).permissions(permissions).request(callback);
    }

    /**
     * 申请通知权限(仅 Android 13+ 需要;低版本该权限由系统默认授予,申请也无意义)。
     *
     * <p>**不阻断主流程**:拒绝授权只是没有通知,音乐照常播放,调用方无需处理结果。
     *
     * <p>⚠️ **必须在未授权时提前返回**(2026-09-19 真机修复):本方法由
     * {@code PlaybackController.updateMusicSession()} 在**每次播放状态回调**里调用。
     * 状态回调的密度经真机实测(app 落盘的 files/preload_debug.log)在起播后约 6 秒内为
     * **8~9 次/秒** —— 该文件显示 state=3(PLAYING) 与 state=4(PAUSED) 在 45~50ms 间隔上交替
     * (HLS 起播期 IJK 在缓冲中 isPlaying() 报 false 所致,位置仍在推进)。
     * 若授权失败后仍继续下发申请,每次都会拉起一个 {@code GrantPermissionsActivity}
     * (已固定拒绝时它"创建→立刻 finish"、约 150ms 一轮),系统窗口反复抢焦点会 pause/resume 本页,
     * 渲染 Surface 随之被反复打断 —— 真机(vivo V2425A / Android 16,POST_NOTIFICATIONS 固定拒绝)
     * 3.2 秒内实测拉起 **22 个**权限页,画面与声音即表现为"抽搐式"卡顿;
     * 一旦授权成功,本方法即提前返回,卡顿消失。
     */
    public static void requestNotificationIfNeeded(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (activity == null) return;
        // 闸门**先于**授权查询(2026-09-19):本方法在热路径上每秒被调多次,而
        // isGrantedPermission 是一次 binder checkSelfPermission。已经问过之后答案不可能再变,
        // 没必要每次都付这次 IPC。放前面与放后面行为完全等价(未授权是唯一的分支条件)。
        if (notificationAsked) return;
        if (XXPermissions.isGrantedPermission(activity, PermissionLists.getPostNotificationsPermission())) return;
        // 一次性闸门:拒绝过就永不再弹 —— 申请失败(尤其 USER_FIXED / "不再询问")后
        // 再申请只会变成上面那种"权限页风暴",而且被拒绝的权限再申请系统也不会给弹窗
        notificationAsked = true;
        XXPermissions.with(activity)
                .permission(PermissionLists.getPostNotificationsPermission())
                .request((permissions, allGranted) -> {
                    // 拒绝不影响任何功能:只是前台服务通知不展示,音乐照常播放
                });
    }
}
