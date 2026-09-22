package com.github.tvbox.osc.util.parser;
import android.util.Base64;
import com.github.catvod.crawler.SpiderDebug;
import com.github.tvbox.osc.util.HeaderGuard;
import com.github.tvbox.osc.util.LOG;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 并发解析，直到获得第一个结果
 */
public class JsonParallel {

    /**
     * 当前在途解析任务；client/executor/futures 全部任务局部，并发调用互不覆盖。
     * 新一轮 parse 启动时自动取消上一个在途任务。
     */
    private static volatile Task currentTask;

    private static final class Task {
        final OkHttpClient client = new OkHttpClient();
        final ExecutorService executorService = Executors.newFixedThreadPool(5);
        final List<Future<JSONObject>> futures = new ArrayList<>();

        void cancel() {
            try {
                client.dispatcher().cancelAll();
            } catch (Throwable ignored) {
                LOG.d("JsonParallel", "cancel dispatcher calls failed");
            }
            for (Future<JSONObject> future : futures) {
                try {
                    future.cancel(true);
                } catch (Throwable ignored) {
                    LOG.d("JsonParallel", "cancel in-flight future failed");
                }
            }
            futures.clear();
            executorService.shutdownNow();
        }
    }

    public static JSONObject parse(LinkedHashMap<String, String> jx, String url) {
        Task task = new Task();
        cancelTasks(); // 取消上一个在途任务，避免多轮解析并存
        currentTask = task;
        try {
            if (jx != null && jx.size() > 0) {
                // 使用线程池并发处理任务
                CompletionService<JSONObject> completionService = new ExecutorCompletionService<>(task.executorService);

                // 遍历所有的解析配置
                for (final String jxName : jx.keySet()) {
                    final String parseUrl = jx.get(jxName);
                    task.futures.add(completionService.submit(new Callable<JSONObject>() {
                        @Override
                        public JSONObject call() {
                            try {
                                // 获取请求头，并从中取出实际url
                                HashMap<String, String> reqHeaders = JsonParallel.getReqHeader(parseUrl);
                                String realUrl = reqHeaders.get("url");
                                reqHeaders.remove("url");
                                Headers headers = Headers.of(reqHeaders);
                                Request request = new Request.Builder()
                                        .url(realUrl + url)
                                        .headers(headers)
                                        .tag("ParseTag")
                                        .build();

                                Call call = task.client.newCall(request);
                                Response response = call.execute();
                                String json = response.body().string();

                                JSONObject taskResult = Utils.jsonParse(url, json);
                                taskResult.put("jxFrom", jxName);
                                return taskResult;
                            } catch (Throwable th) {
                                // 输出日志
                                return null;
                            }
                        }
                    }));
                }

                JSONObject pTaskResult = null;
                for (int i = 0; i < task.futures.size(); ++i) {
                    Future<JSONObject> completed = completionService.take();
                    try {
                        pTaskResult = completed.get();
                        if (pTaskResult != null) {
                            for (Future<JSONObject> future : task.futures) {
                                try {
                                    future.cancel(true);
                                } catch (Throwable t) {
                                    SpiderDebug.log(t);
                                }
                            }
                            task.futures.clear();
                            break;
                        }
                    } catch (Throwable th) {
                        SpiderDebug.log(th);
                    }
                }
                if (pTaskResult != null)
                    return pTaskResult;
            }
        } catch (Throwable th) {
            SpiderDebug.log(th);
        } finally {
            task.cancel();
            if (currentTask == task) currentTask = null;
        }
        return new JSONObject();
    }

    public static void cancelTasks() {
        Task task = currentTask;
        if (task != null) {
            task.cancel();
        }
    }
    public static HashMap<String, String> getReqHeader(String url) {
        HashMap<String, String> reqHeaders = new HashMap<>();
        reqHeaders.put("url", url);
        if (url.contains("cat_ext")) {
            try {
                int start = url.indexOf("cat_ext=");
                // cat_ext 可能是末参数，此时 indexOf("&") 返回 -1，取串尾兜底
                int end = url.indexOf("&", start);
                if (end == -1) end = url.length();
                String ext = url.substring(start + 8, end);
                ext = new String(Base64.decode(ext, Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP));
                String newUrl = url.substring(0, start);
                if (end < url.length()) newUrl += url.substring(end + 1); // 跳过后续参数前的分隔符
                if (newUrl.endsWith("&") || newUrl.endsWith("?")) {
                    newUrl = newUrl.substring(0, newUrl.length() - 1); // cat_ext 是唯一 query 参数时去掉残留分隔符
                }
                JSONObject jsonObject = new JSONObject(ext);
                if (jsonObject.has("header")) {
                    JSONObject headerJson = jsonObject.optJSONObject("header");
                    if (headerJson != null) {
                        Iterator<String> keys = headerJson.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            String value = headerJson.optString(key, "");
                            // 聚合解析器的 ext 头来自配置:非法字符会让 Headers.of 抛 IAE,该解析器静默失效
                            if (!HeaderGuard.isSendable(key, value)) {
                                LOG.d("JsonParallel", "drop illegal header: " + key);
                                continue;
                            }
                            reqHeaders.put(key, value);
                        }
                    }
                }
                reqHeaders.put("url", newUrl);
            } catch (Throwable th) {
                LOG.d("JsonParallel", "cat_ext param decode failed, ignore extended headers");
            }
        }
        return reqHeaders;
    }
}
