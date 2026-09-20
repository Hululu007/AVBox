package com.github.tvbox.osc.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.widget.ImageView;

import coil3.SingletonImageLoader;
import coil3.request.Disposable;
import coil3.request.ImageRequest;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Random;

import android.util.LruCache;
import me.jessyan.autosize.utils.AutoSizeUtils;

public class ImgUtil {
    // BugReview #25:无界静态缓存,每张占位图约 170KB(180x240 ARGB_8888),长列表浏览累积数百 MB;
    // 改 LruCache 限流,仅手动"清理缓存"才释放的问题同步消除
    private static final int DRAWABLE_CACHE_MAX = 64;
    private static final LruCache<String, Drawable> drawableCache = new LruCache<>(DRAWABLE_CACHE_MAX);
    public static int defaultWidth = 244;
    public static int defaultHeight = 320;

    public static class Style {
        public float ratio;
        public String type;

        public Style(float ratio, String type) {
            this.ratio = ratio;
            this.type = type;
        }
    }

    public static boolean isBase64Image(String picUrl) {
        return picUrl != null && picUrl.startsWith("data:image");
    }

    public static Style initStyle() {
        String bStyle = ApiConfig.get().getHomeSourceBean().getStyle();
        if (!bStyle.isEmpty()) {
            try {
                JSONObject jsonObject = new JSONObject(bStyle);
                return new Style((float) jsonObject.getDouble("ratio"), jsonObject.getString("type"));
            } catch (JSONException ignored) {
                LOG.d("ImgUtil", "home style json invalid, use default grid");
            }
        }
        return null;
    }

    public static int spanCountByStyle(Style style, int defaultCount) {
        int spanCount = defaultCount;
        if ("rect".equals(style.type)) {
            if (style.ratio >= 1.7) {
                spanCount = 3;
            } else if (style.ratio >= 1.3) {
                spanCount = 4;
            }
        } else if ("list".equals(style.type)) {
            spanCount = 1;
        }
        return spanCount;
    }

    public static int getStyleDefaultWidth(Style style) {
        int styleDefaultWidth = 280;
        if (style.ratio < 1) styleDefaultWidth = 214;
        if (style.ratio > 1.7) styleDefaultWidth = 380;
        return styleDefaultWidth;
    }

    public static Bitmap decodeBase64ToBitmap(String base64Str) {
        String base64Data = base64Str.substring(base64Str.indexOf(",") + 1);
        byte[] decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
        return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
    }

    /**
     * 播放器封面专用加载入口:与 {@link #load} 的区别是 **①不画占位图/错误图、②把请求句柄交回调用方**。
     *
     * <p>① 为什么不画占位/错误图:那些 drawable 是给**卡片**(180×240)设计的,而播放器封面是
     * MATCH_PARENT 的整块画面区 —— 一旦被 `FIT_CENTER` 放大铺到全屏,就会呈现为一块与视频无关的
     * 浅色/白色矩形(真机实测:纯音频退到多任务时画面区变白、回前台又闪成透明)。
     * 播放器区只认「真正加载成功的图」,加载期间保持黑底(与视频未起播时一致)。
     *
     * <p>② 为什么要句柄:Coil 3 的 {@code ImageViewTarget} 走内部 {@code ViewTargetRequestManager},
     * 该管理器只认识自己注册过的请求、不暴露取消入口;而播放器封面每换集/换线都要换图,
     * 旧请求的迟到回调会把过期海报盖到新画面上。返回 {@link Disposable} 由
     * {@code MyVideoView.clearArtwork()} 负责 {@code dispose()}(Coil 的 dispose 会同步把
     * {@code isDisposed} 置真并取消 job,晚到的 onSuccess 不再落地)。
     *
     * @return 请求句柄;地址无效时返回 null(此时已直接设兜底图,无需取消)
     */
    public static Disposable loadPlayerArtwork(String url, ImageView view) {
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (isInvalidImageUrl(url)) {
            view.setImageDrawable(createTextDrawable("TVBox", 0, 0, 1));
            return null;
        }
        ImageRequest request = new ImageRequest.Builder(App.getInstance())
                .data(url)
                .target(new ArtworkTarget(view))
                .build();
        return SingletonImageLoader.get(App.getInstance()).enqueue(request);
    }

    /**
     * 播放器封面的 Target:**只有 onSuccess 才落地**,onStart(占位)/onError 一律不改视图。
     * 视图保持自身黑底 → 永远不会出现"加载中/加载失败的浅色矩形盖住视频"。
     * 失败也不落错误图:播放器区的正确兜底是黑底,而不是一张与内容无关的图。
     * (与 {@code PlaybackService.updateArtwork} 里取通知封面用的是同一种写法)
     */
    private static final class ArtworkTarget implements coil3.target.Target {
        private final ImageView view;

        ArtworkTarget(ImageView view) {
            this.view = view;
        }

        @Override
        public void onSuccess(coil3.Image image) {
            view.setImageDrawable(coil3.Image_androidKt.asDrawable(image, view.getResources()));
        }
    }

    public static int getRandomColor() {
        Random random = new Random();
        return Color.argb(255, random.nextInt(256), random.nextInt(256), random.nextInt(256));
    }

    public static Drawable createTextDrawable(String text) {
        return createTextDrawable(text, 0, 0, AutoSizeUtils.mm2px(App.getInstance(), 10));
    }

    private static Drawable createTextDrawable(String text, int width, int height, float cornerRadius) {
        if (TextUtils.isEmpty(text)) text = "TVBox";
        if (width <= 0) width = 180;
        if (height <= 0) height = 240;
        if (cornerRadius <= 0) cornerRadius = 1;
        String key = text + "_" + width + "x" + height + "_" + (int) cornerRadius;
        text = text.substring(0, 1);
        Drawable cached = drawableCache.get(key);
        if (cached != null) return cached;
        int randomColor = getRandomColor();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(randomColor);
        paint.setStyle(Paint.Style.FILL);
        RectF rectF = new RectF(0, 0, width, height);
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(60);
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fontMetrics = paint.getFontMetrics();
        float x = width / 2f;
        float y = (height - fontMetrics.bottom - fontMetrics.top) / 2f;
        canvas.drawText(text, x, y, paint);
        Drawable drawable = new BitmapDrawable(App.getInstance().getResources(), bitmap);
        drawableCache.put(key, drawable);
        return drawable;
    }

    public static void clearCache() {
        drawableCache.evictAll();
    }

    public static void clearMemoryCache() {
        clearCache();
        try {
            SingletonImageLoader.get(App.getInstance()).getMemoryCache().clear();
            LOG.i("echo-img-clear-memory-cache");
        } catch (Throwable th) {
            LOG.i("echo-img-clear-memory-cache-error:" + th.getMessage());
        }
    }

    private static boolean isInvalidImageUrl(String url) {
        if (TextUtils.isEmpty(url)) return true;
        url = url.trim();
        if (TextUtils.isEmpty(url)) return true;
        return hasEmptyProxyParam(url, "img");
    }

    private static boolean hasEmptyProxyParam(String url, String key) {
        if (!url.startsWith("proxy://") && !url.contains("/proxy?")) return false;
        int queryIndex = url.indexOf('?');
        String query = queryIndex >= 0 ? url.substring(queryIndex + 1) : url.substring("proxy://".length());
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eqIndex = pair.indexOf('=');
            if (eqIndex < 0) continue;
            if (key.equals(pair.substring(0, eqIndex)) && TextUtils.isEmpty(pair.substring(eqIndex + 1))) {
                return true;
            }
        }
        return false;
    }
}
