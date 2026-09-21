package com.github.tvbox.osc.player.usecase;

import android.content.Context;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.server.RemoteServer;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.LanguageManager;
import com.github.tvbox.osc.util.M3u8;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.HttpHeaders;
import com.lzy.okgo.model.Response;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * m3u8 去广告用例（从 VodController:1667-1834 剥离，Compose 化改造 阶段 0）。
 * <p>
 * 职责：拉取 m3u8 → 识别重定向（#EXT-X-STREAM-INF）→ M3u8.purify 去广告 →
 * 命中广告时走本地代理播放，否则回退直链。纯网络/解析逻辑，与 UI 无关。
 */
public final class M3u8PurifyUseCase {

    /** 资源文案:Application 的 base 只在进程启动时挂一次,切语言后直接用 app.getString 会停在旧语言 */
    private static String str(int resId, Object... args) {
        App app = App.getInstance();
        return app == null ? "" : LanguageManager.INSTANCE.localized(app).getString(resId, args);
    }

    public interface Callback {
        void startPlayUrl(String url, HashMap<String, String> headers);

        void onM3u8ProxyUrl(String proxyUrl, String sourceUrl);
    }

    private final Context context;
    private final Callback callback;

    public M3u8PurifyUseCase(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public void playM3u8(final String url, final HashMap<String, String> headers) {
        if (url.contains("url=")) {
            callback.startPlayUrl(url, headers);
            return;
        }
        OkGo.getInstance().cancelTag("m3u8-1");
        OkGo.getInstance().cancelTag("m3u8-2");
        final HttpHeaders okGoHeaders = new HttpHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                okGoHeaders.put(entry.getKey(), entry.getValue());
            }
        }
        OkGo.<String>get(url)
                .tag("m3u8-1")
                .headers(okGoHeaders)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        String content = response.body();
                        if (!content.startsWith("#EXTM3U")) {
                            callback.startPlayUrl(url, headers);
                            return;
                        }
                        String forwardUrl = extractForwardUrl(url, content);
                        if (forwardUrl.isEmpty()) {
                            LOG.i("echo-m3u81-to-play");
                            processM3u8Content(url, content, headers);
                        } else {
                            fetchAndProcessForwardUrl(forwardUrl, headers, okGoHeaders, url);
                        }
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        LOG.e("echo-m3u8请求错误1: " + response.getException());
                        callback.startPlayUrl(url, headers);
                    }
                });
    }

    private String extractForwardUrl(String baseUrl, String content) {
        // 不要给 limit(2026-09-12 修复 Bug):原为 split(..., 50),limit>0 时最后一个元素是"第 50 行到文末"的整块,
        // 于是第一个 #EXT-X-STREAM-INF 出现在第 50 行之后会被漏识别(master 被当媒体列表),第 49 行则会拼出跨行畸形 URL
        String[] lines = content.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                // 只需要找接下来的几行
                for (int j = i + 1; j < lines.length; j++) {
                    String targetLine = lines[j].trim();
                    if (targetLine.isEmpty()) continue;
                    if (isValidM3u8Line(targetLine)) {
                        return resolveForwardUrl(baseUrl, targetLine);
                    }
                }
            }
        }
        return "";
    }

    private boolean isValidM3u8Line(String line) {
        return !line.startsWith("#") && (line.endsWith(".m3u8") || line.contains(".m3u8?"));
    }

    private void processM3u8Content(String url, String content, HashMap<String, String> headers) {
        String basePath = getBasePath(url);
        String purified = M3u8.purify(basePath, content);
        // 2026-09-13:只在真正走代理时才写入内容槽 —— 无广告(走直链)的集不写,
        // 避免把在播集的槽位冲掉;proxyUrl 带本次生成的键(?k=),服务端按键取内容
        if (purified == null || M3u8.currentAdCount == 0) {
            LOG.i("echo-m3u8内容解析：未检测到广告");
            callback.startPlayUrl(url, headers);
        } else {
            String key = RemoteServer.putM3u8Content(purified);
            String proxyUrl = ControlManager.get().getAddress(true) + "proxyM3u8?k=" + key;
            callback.onM3u8ProxyUrl(proxyUrl, url);
            callback.startPlayUrl(proxyUrl, headers);
            Toast.makeText(context, str(R.string.toast_ads_removed, M3u8.currentAdCount), Toast.LENGTH_SHORT).show();
        }
    }

    private void fetchAndProcessForwardUrl(final String forwardUrl, final HashMap<String, String> headers,
                                           HttpHeaders okGoHeaders, final String fallbackUrl) {
        OkGo.<String>get(forwardUrl)
                .tag("m3u8-2")
                .headers(okGoHeaders)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        String content = response.body();
                        LOG.i("echo-m3u82-to-play");
                        processM3u8Content(forwardUrl, content, headers);
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        LOG.e("echo-重定向 m3u8 请求错误: " + response.getException());
                        callback.startPlayUrl(fallbackUrl, headers);
                    }
                });
    }

    private String getBasePath(String url) {
        int ilast = url.lastIndexOf('/');
        return url.substring(0, ilast + 1);
    }

    private String resolveForwardUrl(String baseUrl, String line) {
        try {
            // 使用 URL 构造器自动解析相对路径
            URL base = new URL(baseUrl);
            URL resolved = new URL(base, line);
            return resolved.toString();
        } catch (MalformedURLException e) {
            // 出现异常时可以记录日志，并返回原始 line
            LOG.e("echo-resolveForwardUrl异常: " + e.getMessage());
            return line;
        }
    }
}
