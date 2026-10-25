package com.github.avbox.osc.dlna

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper

import com.google.gson.Gson
import com.github.avbox.osc.R
import com.github.avbox.osc.base.App
import com.github.avbox.osc.bean.CastVideo
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.LanguageManager

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import org.fourthline.cling.android.AndroidUpnpService
import org.fourthline.cling.controlpoint.ControlPoint
import org.fourthline.cling.model.action.ActionInvocation
import org.fourthline.cling.model.message.UpnpResponse
import org.fourthline.cling.model.message.header.STAllHeader
import org.fourthline.cling.model.meta.RemoteDevice
import org.fourthline.cling.model.meta.RemoteService
import org.fourthline.cling.model.types.UDADeviceType
import org.fourthline.cling.model.types.UDAServiceType
import org.fourthline.cling.registry.DefaultRegistryListener
import org.fourthline.cling.registry.Registry
import org.fourthline.cling.support.avtransport.callback.Play
import org.fourthline.cling.support.avtransport.callback.Seek
import org.fourthline.cling.support.avtransport.callback.SetAVTransportURI
import org.fourthline.cling.support.model.SeekMode

import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class DLNACastManager : DefaultRegistryListener(), ServiceConnection {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val deviceMap = ConcurrentHashMap<String, CastDevice>()

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())

    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private var upnpService: AndroidUpnpService? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var binding = false

    fun init(context: Context) {
        if (upnpService != null || binding) {
            search()
            return
        }
        val appContext = context.applicationContext
        acquireMulticastLock(appContext)
        binding = appContext.bindService(Intent(context, DLNACastService::class.java), this, Context.BIND_AUTO_CREATE)
        if (!binding) releaseMulticastLock()
    }

    fun release(context: Context) {
        try {
            upnpService?.registry?.removeListener(this)
            context.applicationContext.unbindService(this)
        } catch (ignored: Exception) {
            AppLog.d("DLNACastManager", "unbind service failed")
        }
        upnpService = null
        binding = false
        deviceMap.clear()
        publishDevices()
        releaseMulticastLock()
    }

    private fun acquireMulticastLock(context: Context) {
        try {
            if (multicastLock?.isHeld == true) return
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager?
            if (wifiManager == null) return
            val lock = wifiManager.createMulticastLock("tvbox_dlna_cast")
            multicastLock = lock
            lock.setReferenceCounted(false)
            lock.acquire()
        } catch (ignored: Exception) {
            AppLog.d("DLNACastManager", "acquire multicast lock failed")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (ignored: Exception) {
            AppLog.d("DLNACastManager", "release multicast lock failed")
        }
        multicastLock = null
    }

    fun search() {
        val service = upnpService ?: return
        service.controlPoint.search(STAllHeader())
        loadRegisteredDevices()
    }

    private fun getDevices(): List<CastDevice> {
        val list = ArrayList(deviceMap.values)
        list.sortWith(compareBy(nullsLast<String>()) { it.name })
        return list
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        binding = false
        val upnp = service as AndroidUpnpService
        upnpService = upnp
        upnp.registry.addListener(this)
        search()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        upnpService = null
        binding = false
    }

    override fun remoteDeviceAdded(registry: Registry?, device: RemoteDevice?) {
        val d = device ?: return
        if (d.type.implementsVersion(RENDERER_TYPE)) addDevice(CastDevice.dlna(d))
    }

    override fun remoteDeviceRemoved(registry: Registry?, device: RemoteDevice?) {
        val d = device ?: return
        if (d.type.implementsVersion(RENDERER_TYPE)) removeDevice(CastDevice.dlna(d))
    }

    private fun loadRegisteredDevices() {
        val service = upnpService ?: return
        for (device in service.registry.getDevices(RENDERER_TYPE)) {
            if (device is RemoteDevice) addDevice(CastDevice.dlna(device))
        }
    }

    private fun addDevice(device: CastDevice) {
        val id = device.id ?: return
        deviceMap[id] = device
        publishDevices()
    }

    private fun removeDevice(device: CastDevice) {
        val id = device.id ?: return
        deviceMap.remove(id)
        publishDevices()
    }

    private fun publishDevices() {
        _devices.value = getDevices()
    }

    fun cast(device: CastDevice, video: CastVideo, callback: CastCallback?) {
        val control = upnpService?.controlPoint
        val service = findAVTransport(device)
        if (control == null || service == null) {
            postFail(callback, str(R.string.cast_device_offline))
            return
        }
        AppLog.i("dlna-cast start device=" + device.name + ", id=" + device.id + ", url=" + video.url)
        control.execute(uriAction(control, service, video, callback))
    }

    private fun findAVTransport(device: CastDevice?): RemoteService? {
        val service = upnpService ?: return null
        if (device == null) return null
        for (item in service.registry.getDevices(RENDERER_TYPE)) {
            if (item !is RemoteDevice) continue
            if (item.identity.udn.identifierString == device.id) {
                return item.findService(AVT_TYPE)
            }
        }
        return null
    }

    private fun uriAction(control: ControlPoint, service: RemoteService, video: CastVideo, callback: CastCallback?): SetAVTransportURI {
        val metaData = buildMetaData(video)
        return object : SetAVTransportURI(service, video.url, metaData) {
            override fun success(invocation: ActionInvocation<*>?) {
                control.execute(playAction(control, service, video, callback))
            }

            override fun failure(invocation: ActionInvocation<*>?, operation: UpnpResponse?, defaultMsg: String?) {
                AppLog.e("dlna-cast SetAVTransportURI failure: " + formatResponse(operation, defaultMsg))
                postFail(callback, defaultMsg)
            }
        }
    }

    private fun playAction(control: ControlPoint, service: RemoteService, video: CastVideo, callback: CastCallback?): Play {
        return object : Play(service) {
            override fun success(invocation: ActionInvocation<*>?) {
                if (video.position > 0) control.execute(seekAction(service, video.position))
                postSuccess(callback)
            }

            override fun failure(invocation: ActionInvocation<*>?, operation: UpnpResponse?, defaultMsg: String?) {
                AppLog.e("dlna-cast Play failure: " + formatResponse(operation, defaultMsg))
                postFail(callback, defaultMsg)
            }
        }
    }

    private fun seekAction(service: RemoteService, position: Long): Seek {
        return object : Seek(service, SeekMode.REL_TIME, formatMs(position)) {
            override fun success(invocation: ActionInvocation<*>?) {
            }

            override fun failure(invocation: ActionInvocation<*>?, operation: UpnpResponse?, defaultMsg: String?) {
                AppLog.i("dlna-cast Seek ignored: " + formatResponse(operation, defaultMsg))
            }
        }
    }

    private fun buildMetaData(video: CastVideo): String {
        try {
            val sb = StringBuilder()
            sb.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            sb.append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            sb.append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
            sb.append("<item id=\"0\" parentID=\"-1\" restricted=\"0\">")
            sb.append("<dc:title>").append(escapeXml(video.name)).append("</dc:title>")
            sb.append("<dc:creator></dc:creator>")
            sb.append("<upnp:class>object.item.videoItem</upnp:class>")
            val headers = video.headers
            if (headers.isNotEmpty()) {
                sb.append("<dc:description>").append(escapeXml(Gson().toJson(headers))).append("</dc:description>")
            }
            sb.append("<res protocolInfo=\"http-get:*:video/*:*\">").append(escapeXml(video.url)).append("</res>")
            sb.append("</item>")
            sb.append("</DIDL-Lite>")
            return sb.toString()
        } catch (e: Exception) {
            AppLog.e("dlna-cast metadata failure: " + e.message)
            return ""
        }
    }

    private fun escapeXml(value: String?): String {
        if (value == null) return ""
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "&apos;")
            .replace("\"", "&quot;")
    }

    private fun formatMs(ms: Long): String {
        if (ms <= 0) return "00:00:00"
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun formatResponse(operation: UpnpResponse?, defaultMsg: String?): String {
        if (operation == null) return defaultMsg ?: ""
        return operation.statusCode.toString() + " " + operation.statusMessage + " " + (defaultMsg ?: "")
    }

    private fun postSuccess(callback: CastCallback?) {
        mainHandler.post {
            callback?.onResult(true, "")
        }
    }

    private fun postFail(callback: CastCallback?, msg: String?) {
        mainHandler.post {
            callback?.onResult(false, msg ?: str(R.string.toast_cast_failed))
        }
    }

    interface CastCallback {
        fun onResult(success: Boolean, msg: String?)
    }

    companion object {
        private fun str(resId: Int, vararg args: Any?): String {
            val app = App.getInstance()
            return if (app == null) "" else LanguageManager.localized(app).getString(resId, *args)
        }

        private val RENDERER_TYPE = UDADeviceType("MediaRenderer", 1)
        private val AVT_TYPE = UDAServiceType("AVTransport", 1)
        private val INSTANCE = DLNACastManager()

        @JvmStatic
        fun get(): DLNACastManager {
            return INSTANCE
        }
    }
}
