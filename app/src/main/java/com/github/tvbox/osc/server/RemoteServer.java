package com.github.tvbox.osc.server;

import static com.github.tvbox.osc.util.RegexUtils.getPattern;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Base64;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.event.ServerEvent;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.LocalSourceTree;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.Proxy;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.greenrobot.eventbus.EventBus;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;
import okio.Buffer;

/**
 * @author pj567
 * @date :2021/1/5
 * @description:
 */
public class RemoteServer extends NanoHTTPD {
    private Context mContext;
    public static int serverPort = 9978;
    private boolean isStarted = false;

    /**
     * 去广告 m3u8 内容槽位(key → 内容;2026-09-13 起带键,取代此前的无参单槽)。
     * 背景:proxyUrl 原先不带任何身份参数、服务端直接吐"最后一次净化"的内容 ⇒ 切集后旧播放器的
     * 重试/重连请求会拿到新一集的列表。现按请求 {@code ?k=} 取:键不匹配/缺失返 404,旧播放器走
     * 失败链路而不是串集。保留最近 [M3U8_SLOT_LIMIT] 条(访问序 LRU)——在播集反复重拉不会被冲掉。
     * 内容是小文本(几 KB),纯内存、无持久化;进程重启后重新净化即产生新键。
     */
    private static final int M3U8_SLOT_LIMIT = 4;
    private static final AtomicLong m3u8Seq = new AtomicLong(0);
    private static final Map<String, String> m3u8Slots = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > M3U8_SLOT_LIMIT;
                }
            });
    private ArrayList<RequestProcess> getRequestList = new ArrayList<>();
    private ArrayList<RequestProcess> postRequestList = new ArrayList<>();

    public RemoteServer(int port, Context context) {
        super(port);
        mContext = context;
        addGetRequestProcess();
        addPostRequestProcess();
    }

    /** 写入净化结果并返回本次的键(proxyUrl 用 {@code ?k=<key>} 取);仅在真正走代理播放时调用 */
    public static String putM3u8Content(String content) {
        String key = System.currentTimeMillis() + "-" + m3u8Seq.incrementAndGet();
        m3u8Slots.put(key, content);
        return key;
    }

    /** 按键取净化结果;键缺失或已被 LRU 淘汰返回 null(调用方应答 404,不返回错误进度) */
    public static String getM3u8Content(String key) {
        return key == null ? null : m3u8Slots.get(key);
    }

    private void addGetRequestProcess() {
        getRequestList.add(new CacheRequestProcess());
    }

    private void addPostRequestProcess() {
        postRequestList.add(new CacheRequestProcess());
    }

    @Override
    public void start(int timeout, boolean daemon) throws IOException {
        isStarted = true;
        super.start(timeout, daemon);
        EventBus.getDefault().post(new ServerEvent(ServerEvent.SERVER_SUCCESS));
    }

    @Override
    public void stop() {
        super.stop();
        isStarted = false;
    }

    private Response getProxy(Object[] rs){
        try {
            if (rs == null || rs.length < 3) {
                LOG.e("echo-proxy error: empty proxy result");
                return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "500");
            }
            if (rs[0] instanceof NanoHTTPD.Response) return (NanoHTTPD.Response) rs[0];
            int code = (int) rs[0];
            String mime = (String) rs[1];
            InputStream stream = rs[2] != null ? (InputStream) rs[2] : null;
            Response response = NanoHTTPD.newChunkedResponse(
                    Response.Status.lookup(code),
                    mime,
                    stream
            );
            // 添加头部信息
            if (rs.length >= 4 && rs[3] instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> mapHeader = (Map<String, String>) rs[3];
                if(!mapHeader.isEmpty()){
                    for (String key : mapHeader.keySet()) {
                        response.addHeader(key, mapHeader.get(key));
                    }
                }
            }
            return response;
        } catch (Throwable th) {
            LOG.e("echo-proxy error: " + th.getMessage());
            return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "500");
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        EventBus.getDefault().post(new ServerEvent(ServerEvent.SERVER_CONNECTION));
        if (!session.getUri().isEmpty()) {
            String fileName = session.getUri().trim();
            if (fileName.indexOf('?') >= 0) {
                fileName = fileName.substring(0, fileName.indexOf('?'));
            }
            if (session.getMethod() == Method.GET) {
                if (isProxyRequest(fileName, session.getParms())) {
                    return handleProxy(session);
                }
                for (RequestProcess process : getRequestList) {
                    if (process.isRequest(session, fileName)) {
                        return process.doResponse(session, fileName, session.getParms(), null);
                    }
                }
                if (fileName.startsWith("/file/")) {
                    try {
                        String f = fileName.substring(6);
                        // BugReview P3:路径遍历防护——拒绝含 ".." 的路径,防上溯读取 app 私有目录
                        if (f.contains("..")) {
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "Forbidden");
                        }
                        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
                        String file = root + "/" + f;
                        File localFile = new File(file);
                        if (localFile.isDirectory()) {
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, fileList(root, f));
                        }
                        if (localFile.isFile()) {
                            try {
                                return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", new FileInputStream(localFile));
                            } catch (Throwable ignored) {
                                // 文件在但读不到(没开「所有文件访问」等)⇒ 交给下面的目录授权兜底
                                LOG.d("RemoteServer", "file open failed, fallback to tree grant");
                            }
                        }
                        // 本地源目录授权(SAF):应用自己读不到原目录时靠它直引原目录,副本不必搬。
                        // 只服务回环请求 —— 应用读原目录走的就是 127.0.0.1,没必要把"应用都读不到的目录"再开给局域网客户端
                        InputStream granted = isLocalRequest(session) ? LocalSourceTree.INSTANCE.open(App.getInstance(), f) : null;
                        if (granted != null) {
                            return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", granted);
                        }
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "File " + file + " not found!");
                    } catch (Throwable th) {
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, th.getMessage());
                    }
                } else if (fileName.equals("/dns-query")) {
                    String name = session.getParms().get("name");
                    byte[] rs = new byte[0];
                    try {
                        if (OkGoHelper.dnsOverHttps != null && !TextUtils.isEmpty(name)) {
                            rs = buildDnsResponse(name, OkGoHelper.dnsOverHttps.lookup(name));
                        }
                    } catch (Throwable th) {
                        rs = new byte[0];
                    }
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/dns-message", new ByteArrayInputStream(rs), rs.length);
                } else if (fileName.startsWith("/proxyM3u8")) {
                    // 2026-09-13:按请求携带的键取内容;键缺失/不匹配(旧播放器的重试/重连)=404,
                    // 让它走失败链路,而不是串到"最后一次净化"的新一集列表
                    String content = getM3u8Content(session.getParms().get("k"));
                    if (content == null) {
                        return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "m3u8 slot not found");
                    }
                    return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", content);
                }
                else if (fileName.startsWith("/dash/")) {
                    String dashData = App.getInstance().getDashData();
                    try {
                        String data = new String(Base64.decode(dashData, Base64.DEFAULT | Base64.NO_WRAP), "UTF-8");
                        return NanoHTTPD.newFixedLengthResponse(
                                Response.Status.OK,
                                "application/dash+xml",
                                data
                        );
                    } catch (Throwable th) {
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, dashData);
                    }
                }
            } else if (session.getMethod() == Method.POST) {
                Map<String, String> files = new HashMap<String, String>();
                try {
                    if (session.getHeaders().containsKey("content-type")) {
                        String hd = session.getHeaders().get("content-type");
                        if (hd != null) {
                            // cuke: 修正中文乱码问题
                            if (hd.toLowerCase().contains("multipart/form-data") && !hd.toLowerCase().contains("charset=")) {
                                Matcher matcher = getPattern("[ |\t]*(boundary[ |\t]*=[ |\t]*['|\"]?[^\"^'^;^,]*['|\"]?)", Pattern.CASE_INSENSITIVE).matcher(hd);
                                String boundary = matcher.find() ? matcher.group(1) : null;
                                if (boundary != null) {
                                    session.getHeaders().put("content-type", "multipart/form-data; charset=utf-8; " + boundary);
                                }
                            }
                        }
                    }
                    session.parseBody(files);
                } catch (IOException IOExc) {
                    return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: IOException: " + IOExc.getMessage());
                } catch (NanoHTTPD.ResponseException rex) {
                    return createPlainTextResponse(rex.getStatus(), rex.getMessage());
                }
                for (RequestProcess process : postRequestList) {
                    if (process.isRequest(session, fileName)) {
                        return process.doResponse(session, fileName, session.getParms(), files);
                    }
                }
            }
        }
        return createPlainTextResponse(Response.Status.NOT_FOUND, "Not Found");
    }

    private boolean isProxyRequest(String fileName, Map<String, String> params) {
        if (params == null) return false;
        if (!params.containsKey("do") && !params.containsKey("go")) return false;
        return fileName.equals("/proxy") || fileName.equals("/");
    }

    private Response handleProxy(IHTTPSession session) {
        // BugReview P3:请求头先并入、URL 参数后并入(URL 参数优先),防名为 do/go/url/header/siteKey
        // 的请求头覆盖同名 query 参数改写代理路由
        Map<String, String> params = new HashMap<>(session.getHeaders());
        params.putAll(session.getParms());
        if (params.containsKey("do")) {
            boolean isDanmuProxy = "danmu".equals(params.get("do"));
            if (isDanmuProxy) normalizeDanmuParams(params);
            if (isDanmuProxy) LOG.i("echo-proxy-danmu params: " + params.toString());
            Object[] rs = ApiConfig.get().proxyLocal(params);
            return getProxy(rs);
        }
        if (params.containsKey("go")) {
            Object[] rs = Proxy.proxy(params);
            return getProxy(rs);
        }
        return getProxy(null);
    }

    private void normalizeDanmuParams(Map<String, String> params) {
        try {
            VodInfo vodInfo = App.getInstance().getVodInfo();
            if (vodInfo == null) return;
            if (!TextUtils.isEmpty(vodInfo.name)) params.put("vodName", vodInfo.name);
            if (!isNumeric(params.get("vodIndex"))) {
                String episode = getCurrentEpisodeIndex(vodInfo);
                if (!TextUtils.isEmpty(episode)) params.put("vodIndex", episode);
            }
        } catch (Throwable th) {
            LOG.e("echo-proxy-danmu normalize error: " + th.getMessage());
        }
    }

    private String getCurrentEpisodeIndex(VodInfo vodInfo) {
        if (vodInfo.seriesMap != null && !TextUtils.isEmpty(vodInfo.playFlag)) {
            java.util.List<VodInfo.VodSeries> series = vodInfo.seriesMap.get(vodInfo.playFlag);
            if (series != null && vodInfo.playIndex >= 0 && vodInfo.playIndex < series.size()) {
                VodInfo.VodSeries current = series.get(vodInfo.playIndex);
                if (current != null && !TextUtils.isEmpty(current.name)) {
                    String number = extractNumber(current.name);
                    return TextUtils.isEmpty(number) ? current.name : number;
                }
            }
        }
        return String.valueOf(Math.max(0, vodInfo.playIndex) + 1);
    }

    private boolean isNumeric(String text) {
        return !TextUtils.isEmpty(text) && text.matches("\\d+");
    }

    private String extractNumber(String text) {
        if (TextUtils.isEmpty(text)) return "";
        Matcher matcher = getPattern("\\d+").matcher(text);
        return matcher.find() ? matcher.group() : "";
    }

    public boolean isStarting() {
        return isStarted;
    }

    public String getServerAddress() {
        String ipAddress = getLocalIPAddress(mContext);
        return "http://" + ipAddress + ":" + RemoteServer.serverPort + "/";
    }

    public String getLoadAddress() {
        return "http://127.0.0.1:" + RemoteServer.serverPort + "/";
    }

    /** 请求是否来自应用本机(回环);局域网客户端不算 */
    private static boolean isLocalRequest(IHTTPSession session) {
        String address = session.getRemoteIpAddress();
        return address != null && (address.startsWith("127.") || address.equals("::1"));
    }

    public static Response createPlainTextResponse(Response.IStatus status, String text) {
        return newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, text);
    }

    @SuppressLint("DefaultLocale")
    public static String getLocalIPAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
        if (ipAddress == 0) {
            try {
                Enumeration<NetworkInterface> enumerationNi = NetworkInterface.getNetworkInterfaces();
                while (enumerationNi.hasMoreElements()) {
                    NetworkInterface networkInterface = enumerationNi.nextElement();
                    String interfaceName = networkInterface.getDisplayName();
                    if (interfaceName.equals("eth0") || interfaceName.equals("wlan0")) {
                        Enumeration<InetAddress> enumIpAddr = networkInterface.getInetAddresses();
                        while (enumIpAddr.hasMoreElements()) {
                            InetAddress inetAddress = enumIpAddr.nextElement();
                            if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                                return inetAddress.getHostAddress();
                            }
                        }
                    }
                }
            } catch (SocketException e) {
                e.printStackTrace();
            }
        } else {
            return String.format("%d.%d.%d.%d", (ipAddress & 0xff), (ipAddress >> 8 & 0xff), (ipAddress >> 16 & 0xff), (ipAddress >> 24 & 0xff));
        }
        return "0.0.0.0";
    }

    String fileTime(long time, String fmt) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time);
        Date date = calendar.getTime();
        SimpleDateFormat sdf = new SimpleDateFormat(fmt);
        return sdf.format(date);
    }

    String fileList(String root, String path) {
        File file = new File(root + "/" + path);
        File[] list = file.listFiles();
        JsonObject info = new JsonObject();
        info.addProperty("remote", getServerAddress().replace("http://", "clan://"));
        info.addProperty("del", 0);
        if (path.isEmpty()) {
            info.addProperty("parent", ".");
        } else {
            info.addProperty("parent", file.getParentFile().getAbsolutePath().replace(root + "/", "").replace(root, ""));
        }
        if (list == null || list.length == 0) {
            info.add("files", new JsonArray());
            return info.toString();
        }
        Arrays.sort(list, new Comparator<File>() {
            @Override
            public int compare(File o1, File o2) {
                if (o1.isDirectory() && o2.isFile()) return -1;
                return o1.isFile() && o2.isDirectory() ? 1 : o1.getName().compareTo(o2.getName());
            }
        });
        JsonArray result = new JsonArray();
        for (File f : list) {
            if (f.getName().startsWith(".")) {
                if (f.getName().equals(".tvbox_folder")) {
                    info.addProperty("del", 1);
                }
                continue;
            }
            JsonObject fileObj = new JsonObject();
            fileObj.addProperty("name", f.getName());
            fileObj.addProperty("path", f.getAbsolutePath().replace(root + "/", ""));
            fileObj.addProperty("time", fileTime(f.lastModified(), "yyyy/MM/dd aHH:mm:ss"));
            fileObj.addProperty("dir", f.isDirectory() ? 1 : 0);
            result.add(fileObj);
        }
        info.add("files", result);
        return info.toString();
    }

    /** 把 DoH 解析结果编码为合法的 DNS 应答报文(单条 question + 每条地址按自身地址族写 TYPE/RDLENGTH) */
    static byte[] buildDnsResponse(String hostname, java.util.List<InetAddress> addresses) {
        // 客户端只给 name、拿不到它请求的 QTYPE:非纯 IPv6(含无地址的 SERVFAIL)一律按 A 标
        boolean ipv6Only = !addresses.isEmpty();
        for (InetAddress address : addresses) {
            if (!(address instanceof Inet6Address)) {
                ipv6Only = false;
                break;
            }
        }
        // 无地址时回 SERVFAIL(rCode=2),避免返回空报文
        int rCode = addresses.isEmpty() ? 2 : 0;
        Buffer buffer = new Buffer();
        buffer.writeShort(0); // ID
        buffer.writeShort(0x8180 | rCode); // 标准响应 + 递归可用
        buffer.writeShort(1); // QDCOUNT
        buffer.writeShort(addresses.size()); // ANCOUNT
        buffer.writeShort(0); // NSCOUNT
        buffer.writeShort(0); // ARCOUNT
        for (String label : hostname.split("\\.")) {
            byte[] raw = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buffer.writeByte(raw.length);
            buffer.write(raw);
        }
        buffer.writeByte(0); // 名字结束
        buffer.writeShort(ipv6Only ? 0x001c : 0x0001); // QTYPE: A / AAAA
        buffer.writeShort(1); // CLASS_IN
        for (InetAddress address : addresses) {
            boolean ipv6 = address instanceof Inet6Address;
            byte[] raw = address.getAddress();
            buffer.writeByte(0xc0);
            buffer.writeByte(0x0c); // 名字指针 → 指向 question 中的名字
            buffer.writeShort(ipv6 ? 0x001c : 0x0001); // TYPE: A / AAAA
            buffer.writeShort(1); // CLASS_IN
            buffer.writeInt(60); // TTL 60s
            buffer.writeShort(raw.length); // RDLENGTH 必须等于实际写入的地址字节数
            buffer.write(raw);
        }
        return buffer.readByteString().toByteArray();
    }
}
