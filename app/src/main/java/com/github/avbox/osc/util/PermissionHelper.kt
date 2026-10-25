package com.github.avbox.osc.util

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.SystemClock

import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission

object PermissionHelper {

    @Volatile
    private var notificationAsked: Boolean = false

    private const val HOST_ASK_WINDOW_MS = 5_000L

    @Volatile
    private var hostAskAt = 0L

    @JvmStatic
    fun hostAskedStorageRecently(): Boolean =
        hostAskAt != 0L && SystemClock.elapsedRealtime() - hostAskAt <= HOST_ASK_WINDOW_MS

    @JvmStatic
    fun isStorageGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return XXPermissions.isGrantedPermission(context, PermissionLists.getManageExternalStoragePermission())
        }
        return XXPermissions.isGrantedPermissions(
            context,
            arrayOf<IPermission>(
                PermissionLists.getReadExternalStoragePermission(),
                PermissionLists.getWriteExternalStoragePermission()
            )
        )
    }

    @JvmStatic
    fun requestStorage(activity: Activity, callback: OnPermissionCallback) {
        hostAskAt = SystemClock.elapsedRealtime()
        val permissions: Array<IPermission> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            arrayOf(PermissionLists.getManageExternalStoragePermission())
        else
            arrayOf(
                PermissionLists.getReadExternalStoragePermission(),
                PermissionLists.getWriteExternalStoragePermission()
            )
        XXPermissions.with(activity).permissions(permissions).request(callback)
    }

    @JvmStatic
    fun requestNotificationIfNeeded(activity: Activity?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (activity == null) return
        if (notificationAsked) return
        if (XXPermissions.isGrantedPermission(activity, PermissionLists.getPostNotificationsPermission())) return
        notificationAsked = true
        XXPermissions.with(activity)
            .permission(PermissionLists.getPostNotificationsPermission())
            .request { permissions, allGranted ->
            }
    }

    private const val SDK_ANDROID_17 = 37

    @Volatile
    private var localNetworkAutoAsked: Boolean = false

    @JvmStatic
    fun isLocalNetworkGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < SDK_ANDROID_17) return true
        return XXPermissions.isGrantedPermission(context, PermissionLists.getAccessLocalNetworkPermission())
    }

    @JvmStatic
    fun requestLocalNetwork(activity: Activity?, callback: OnPermissionCallback) {
        if (activity == null) return
        if (isLocalNetworkGranted(activity)) return
        XXPermissions.with(activity)
            .permission(PermissionLists.getAccessLocalNetworkPermission())
            .request(callback)
    }

    @JvmStatic
    fun requestLocalNetworkAuto(activity: Activity?, callback: OnPermissionCallback) {
        if (activity == null) return
        if (isLocalNetworkGranted(activity)) return
        if (localNetworkAutoAsked) return
        localNetworkAutoAsked = true
        requestLocalNetwork(activity, callback)
    }
}
