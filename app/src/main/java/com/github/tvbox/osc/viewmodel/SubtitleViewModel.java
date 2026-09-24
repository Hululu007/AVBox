package com.github.tvbox.osc.viewmodel;

import android.text.TextUtils;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.github.tvbox.osc.bean.Subtitle;
import com.github.tvbox.osc.bean.SubtitleData;
import com.github.tvbox.osc.player.SubtitleFilePicker;
import com.github.tvbox.osc.util.OkGoHelper;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SubtitleViewModel extends ViewModel {

    /** 字幕直链回调（Step 6：从 SearchSubtitleDialog 迁出，供 Compose 版 SubtitleSearchSheet 调用） */
    public interface SubtitleLoader {
        void loadSubtitle(Subtitle subtitle);
    }

    /** 发布页文件列表回调（记忆还原路径用；error = 网络/解析失败） */
    private interface FilesCallback {
        void onFiles(List<Subtitle> files, boolean error);
    }

    public MutableLiveData<SubtitleData> searchResult;

    public SubtitleViewModel() {
        searchResult = new MutableLiveData<>();
    }

    public void searchResult(String title, int page) {
        searchResultFromAssrt(title, page);
    }

    public void getSearchResultSubtitleUrls(Subtitle subtitle) {
        getSearchResultSubtitleUrlsFromAssrt(subtitle);
    }

    public void getSubtitleUrl(Subtitle subtitle, SubtitleLoader subtitleLoader) {
        getSubtitleUrlFromAssrt(subtitle, subtitleLoader, null);
    }

    /**
     * 记忆还原路径:在指定发布页里挑出"本集"的文件并解析出直链。
     *
     * <p>不走 {@link #searchResult}:那是面板的列表数据,播放层写进去会与用户正在浏览的面板互相覆盖。
     * 挑文件规则见 {@link com.github.tvbox.osc.player.SubtitleFilePicker};挑不出(不确定是本集)回调 {@code onFailed}。
     */
    public void pickEpisodeSubtitle(String releaseUrl, String episodeName, String fileNameHint,
                                    SubtitleLoader onPicked, Runnable onFailed) {
        if (TextUtils.isEmpty(releaseUrl) || onPicked == null) {
            if (onFailed != null) onFailed.run();
            return;
        }
        Subtitle release = new Subtitle();
        release.setUrl(releaseUrl);
        getSearchResultSubtitleUrlsFromAssrt(release, new FilesCallback() {
            @Override
            public void onFiles(List<Subtitle> files, boolean error) {
                if (error || files == null || files.isEmpty()) {
                    if (onFailed != null) onFailed.run();
                    return;
                }
                List<String> names = new ArrayList<>();
                for (Subtitle item : files) names.add(item.getName());
                int index = SubtitleFilePicker.pick(names, episodeName, fileNameHint);
                if (index < 0) {
                    if (onFailed != null) onFailed.run();
                    return;
                }
                getSubtitleUrlFromAssrt(files.get(index), onPicked, onFailed);
            }
        });
    }

    private void setSearchListData(List<Subtitle> data, boolean isNew, boolean isZip) {
        try {
            SubtitleData subtitleData = new SubtitleData();
            subtitleData.setSubtitleList(data);
            subtitleData.setIsNew(isNew);
            subtitleData.setIsZip(isZip);
            searchResult.postValue(subtitleData);
        } catch (Throwable e) {
            e.printStackTrace();
            searchResult.postValue(null);
        }
    }

    private int pagesTotal = -1;

    private void searchResultFromAssrt(String title, int page) {
        try {
            if (pagesTotal > 0 && page > pagesTotal) {
                setSearchListData(new ArrayList<>(), page <= 1, true);
                return;
            }
            if (page == 1) pagesTotal = -1;//第一页时 重置页大小
            String searchApiUrl = "https://secure.assrt.net/sub/";
            OkGo.<String>get(searchApiUrl)
                    .params("searchword", title)
                    .params("sort", "rank")
                    .params("page", page)
                    .params("no_redir", "1")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public void onSuccess(com.lzy.okgo.model.Response<String> response) {
                            try {
                                String content = response.body();
                                Document doc = Jsoup.parse(content);
                                Elements items = doc.select(".resultcard .sublist_box_title a.introtitle");
                                List<Subtitle> data = new ArrayList<>();
                                for (Element item : items) {
                                    String subtitleTitle = item.attr("title");
                                    String href = item.attr("href");
                                    if (TextUtils.isEmpty(href) || !containsSearchWord(subtitleTitle, title)) continue;
                                    Subtitle one = new Subtitle();
                                    one.setName(subtitleTitle);
                                    one.setUrl("https://assrt.net" + href);
                                    one.setIsZip(true);
                                    data.add(one);
                                }
                                setSearchListData(data, page <= 1, true);
                                Elements pages = doc.select(".pagelinkcard a");
                                if (pages.size() > 0) {
                                    String[] ps = pages.last().text().split("/", 2);
                                    if (ps.length == 2 && !TextUtils.isEmpty(ps[1])) {
                                        pagesTotal = Integer.valueOf(ps[1].trim());
                                    }
                                }
                            } catch (Throwable th) {
                                th.printStackTrace();
                            }
                        }

                        @Override
                        public String convertResponse(Response response) throws Throwable {
                            return response.body().string();
                        }

                        @Override
                        public void onError(com.lzy.okgo.model.Response<String> response) {
                            super.onError(response);
                            setSearchListData(null, page <= 1, true);
                        }
                    });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    Pattern regexShooterFileOnclick = Pattern.compile("onthefly\\(\"(\\d+)\",\"(\\d+)\",\"([\\s\\S]*)\"\\)");

    private void getSearchResultSubtitleUrlsFromAssrt(Subtitle subtitle) {
        getSearchResultSubtitleUrlsFromAssrt(subtitle, new FilesCallback() {
            @Override
            public void onFiles(List<Subtitle> files, boolean error) {
                setSearchListData(files, true, error);
            }
        });
    }

    private void getSearchResultSubtitleUrlsFromAssrt(Subtitle subtitle, FilesCallback callback) {
        try {
            String url = subtitle.getUrl();
            OkGo.<String>get(url).execute(new AbsCallback<String>() {
                @Override
                public void onSuccess(com.lzy.okgo.model.Response<String> response) {
                    try {
                        String content = response.body();
                        List<Subtitle> data = new ArrayList<>();
                        Document doc = Jsoup.parse(content);
                        Elements items = doc.select("#detail-filelist .waves-effect");
                        if (items.size() > 0) {//压缩包里面的字幕
                            for (Element item : items) {
                                String onclick = item.attr("onclick");
                                if (TextUtils.isEmpty(onclick)) continue;
                                Matcher matcher = regexShooterFileOnclick.matcher(onclick);
                                if (matcher.find()) {
                                    String fileName = matcher.group(3);
                                    if (!isSupportedSubtitleFile(fileName)) continue;
                                    String url = String.format("https://secure.assrt.net/download/%s/-/%s/%s", matcher.group(1), matcher.group(2), matcher.group(3));
                                    Subtitle one = new Subtitle();
                                    Element name = item.selectFirst("#filelist-name");
                                    one.setName(name == null ? fileName : name.text());
                                    one.setUrl(url);
                                    one.setIsZip(false);
                                    data.add(one);
                                }
                            }
                            callback.onFiles(data, false);
                        } else {//有的字幕 不一定是压缩包
                            Element item = doc.selectFirst(".download a#btn_download");
                            if (item == null) {
                                callback.onFiles(null, false);
                                return;
                            }
                            String href = item.attr("href");
                            if (TextUtils.isEmpty(href)) {
                                callback.onFiles(null, false);
                                return;
                            }
                            if (isSupportedSubtitleFile(href)) {
                                String url = "https://assrt.net" + href;
                                Subtitle one = new Subtitle();
                                String title = href.substring(href.lastIndexOf("/") + 1);
                                one.setName(URLDecoder.decode(title));
                                one.setUrl(url);
                                one.setIsZip(false);
                                data.add(one);
                                callback.onFiles(data, false);
                            } else {
                                callback.onFiles(null, false);
                            }
                        }
                    } catch (Throwable th) {
                        th.printStackTrace();
                        callback.onFiles(null, true);
                    }
                }

                @Override
                public String convertResponse(Response response) throws Throwable {
                    return response.body().string();
                }

                @Override
                public void onError(com.lzy.okgo.model.Response<String> response) {
                    super.onError(response);
                    callback.onFiles(null, true);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            callback.onFiles(null, true);
        }
    }

    private boolean containsSearchWord(String subtitleTitle, String searchWord) {
        if (TextUtils.isEmpty(subtitleTitle) || TextUtils.isEmpty(searchWord)) return false;
        return subtitleTitle.toLowerCase(Locale.ROOT).contains(searchWord.toLowerCase(Locale.ROOT));
    }

    private boolean isSupportedSubtitleFile(String fileName) {
        if (TextUtils.isEmpty(fileName)) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".srt")
                || lower.endsWith(".ass")
                || lower.endsWith(".stl")
                || lower.endsWith(".ttml");
    }

    /**
     * 解析字幕直链(assrt 下载链是 302,直链在 Location 头里)。
     *
     * <p>{@code onFailed} 只有记忆还原路径传:拿不到直链必须能回落默认字幕链,否则该片会一直没字幕
     * (面板路径不传 —— 选不到字幕由用户自己重选,不需要回落)。
     */
    private void getSubtitleUrlFromAssrt(Subtitle subtitle, SubtitleLoader subtitleLoader, Runnable onFailed) {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.54 Safari/537.36";
        Request request = new Request.Builder()
                .url(subtitle.getUrl())
                .get()
                .addHeader("Referer", "https://secure.assrt.net")
                .addHeader("User-Agent", ua)
                .build();
        OkHttpClient base = OkGoHelper.getDefaultClient();
        OkHttpClient.Builder builder = base != null ? base.newBuilder() : new OkHttpClient.Builder().proxySelector(OkGoHelper.proxySelector()).proxyAuthenticator(OkGoHelper.proxyAuthenticator());
        builder.readTimeout(15, TimeUnit.SECONDS);
        builder.writeTimeout(15, TimeUnit.SECONDS);
        builder.connectTimeout(15, TimeUnit.SECONDS);
        builder.followRedirects(false);
        builder.followSslRedirects(false);
        builder.retryOnConnectionFailure(true);
        OkHttpClient client = builder.build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                if (onFailed != null) onFailed.run();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String location = response.header("location");
                if (TextUtils.isEmpty(location)) {
                    if (onFailed != null) onFailed.run();
                    return;
                }
                subtitle.setUrl(location);
                subtitleLoader.loadSubtitle(subtitle);
            }
        });
    }

}
