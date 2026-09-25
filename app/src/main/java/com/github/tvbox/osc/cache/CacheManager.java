package com.github.tvbox.osc.cache;

import com.github.tvbox.osc.data.AppDataManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 类描述:
 *
 * @author pj567
 * @since 2020/5/15
 */
public class CacheManager {
    //反序列,把二进制数据转换成java object对象
    private static Object toObject(byte[] data) {
        ByteArrayInputStream bais = null;
        ObjectInputStream ois = null;
        try {
            bais = new ByteArrayInputStream(data);
            ois = new ObjectInputStream(bais);
            return ois.readObject();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (bais != null) {
                    bais.close();
                }
                if (ois != null) {
                    ois.close();
                }
            } catch (Exception ignore) {
                ignore.printStackTrace();
            }
        }
        return null;
    }

    //序列化存储数据需要转换成二进制
    private static <T> byte[] toByteArray(T body) {
        ByteArrayOutputStream baos = null;
        ObjectOutputStream oos = null;
        try {
            baos = new ByteArrayOutputStream();
            oos = new ObjectOutputStream(baos);
            oos.writeObject(body);
            oos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (baos != null) {
                    baos.close();
                }
                if (oos != null) {
                    oos.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return new byte[0];
    }

    public static <T> void delete(String key, T body) {
        Cache cache = new Cache();
        cache.key = key;
        cache.data = toByteArray(body);
        AppDataManager.get().getCacheDao().delete(cache);
    }

    public static <T> void save(String key, T body) {
        Cache cache = new Cache();
        cache.key = key;
        cache.data = toByteArray(body);
        AppDataManager.get().getCacheDao().save(cache);
    }

    public static Object getCache(String key) {
        Cache cache = AppDataManager.get().getCacheDao().getCache(key);
        if (cache != null && cache.data != null) {
            return toObject(cache.data);
        }
        return null;
    }

    /**
     * 删除全部进度行(反序列化是 Long 的行;字幕/歌词缓存是 String,不受影响),返回被删缓存键供调用方作废后续回写。
     * 供"清空历史"兜底:没有索引条目的存量进度键是 MD5,反推不出归属,只能这样清。
     */
    public static List<String> clearAllProgress() {
        CacheDao dao = AppDataManager.get().getCacheDao();
        List<Cache> rows = dao.getAll();
        List<String> removed = new ArrayList<>();
        for (Cache row : rows) {
            if (row == null || row.data == null) continue;
            if (toObject(row.data) instanceof Long) {
                dao.delete(row);
                removed.add(row.key);
            }
        }
        return removed;
    }
}
