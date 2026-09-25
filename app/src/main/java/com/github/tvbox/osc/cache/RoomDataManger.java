package com.github.tvbox.osc.cache;

import android.text.TextUtils;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.data.AppDataManager;
import com.google.gson.ExclusionStrategy;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import com.github.tvbox.osc.util.KV;
import java.util.ArrayList;
import java.util.List;

/**
 * @author pj567
 * @date :2021/1/7
 * @description:
 */
public class RoomDataManger {
    static ExclusionStrategy vodInfoStrategy = new ExclusionStrategy() {
        @Override
        public boolean shouldSkipField(FieldAttributes field) {
            if (field.getDeclaringClass() == VodInfo.class && field.getName().equals("seriesFlags")) {
                return true;
            }
            if (field.getDeclaringClass() == VodInfo.class && field.getName().equals("seriesMap")) {
                return true;
            }
            return false;
        }

        @Override
        public boolean shouldSkipClass(Class<?> clazz) {
            return false;
        }
    };

    private static Gson getVodInfoGson() {
        return new GsonBuilder().addSerializationExclusionStrategy(vodInfoStrategy).create();
    }

    public static void insertVodRecord(String sourceKey, VodInfo vodInfo) {
        // 无痕模式(2026-09-12):不写入观看历史(含播放进度)——
        // 本方法是观看历史的唯一落库点(片头/切集/进度同步都汇聚到这里),在此拦截即可全覆盖;
        // 收藏走 insertVodCollect,不受无痕模式影响
        if (HistoryHelper.isIncognito()) return;
        VodRecordDao dao = AppDataManager.get().getVodRecordDao();
        VodRecord record = dao.getVodRecord(sourceKey, vodInfo.id);
        if (record == null) {
            record = new VodRecord();
        }
        record.sourceKey = sourceKey;
        record.vodId = vodInfo.id;
        record.updateTime = System.currentTimeMillis();
        record.dataJson = getVodInfoGson().toJson(vodInfo);
        dao.insert(record);
    }

    public static VodInfo getVodInfo(String sourceKey, String vodId) {
        VodRecord record = AppDataManager.get().getVodRecordDao().getVodRecord(sourceKey, vodId);
        try {
            if (record != null && record.dataJson != null && !TextUtils.isEmpty(record.dataJson)) {
                VodInfo vodInfo = getVodInfoGson().fromJson(record.dataJson, new TypeToken<VodInfo>() {
                }.getType());
                if (vodInfo.name == null)
                    return null;
                return vodInfo;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void deleteVodRecord(String sourceKey, VodInfo vodInfo) {
        VodRecord record = AppDataManager.get().getVodRecordDao().getVodRecord(sourceKey, vodInfo.id);
        if (record != null) {
            AppDataManager.get().getVodRecordDao().delete(record);
        }
    }

    public static List<VodInfo> getAllVodRecord(int limit) {
        VodRecordDao dao = AppDataManager.get().getVodRecordDao();
        Integer index = KV.get(HawkConfig.HISTORY_NUM, 0);
        Integer hisNum = HistoryHelper.getHisNum(index);
        int size = Math.min(limit, hisNum);
        // 条数下推 SQL:历史条目再多也只读所需条数(全表读 + 逐条反序列化会随条目数恶化)。
        // 代价:dataJson 读不出的行会占掉一个名额(仍会被下面的 reserver 裁掉)
        List<VodRecord> recordList = dao.getAll(size);
        List<VodInfo> vodInfoList = new ArrayList<>();
        if (recordList != null) {
            for (VodRecord record : recordList) {
                VodInfo info = null;
                try {
                    if (record.dataJson != null && !TextUtils.isEmpty(record.dataJson)) {
                        info = getVodInfoGson().fromJson(record.dataJson, new TypeToken<VodInfo>() {
                        }.getType());
                        info.sourceKey = record.sourceKey;
//                        SourceBean sourceBean = ApiConfig.get().getSource(info.sourceKey);
                        if (info.name == null)
                            info = null;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (info != null) {
                    vodInfoList.add(info);
                }
            }
        }
        if (dao.getCount() > hisNum) {
            dao.reserver(hisNum);
        }
        return vodInfoList;
    }

    public static void insertVodCollect(String sourceKey, VodInfo vodInfo) {
        VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodInfo.id);
        if (record != null) {
            return;
        }
        record = new VodCollect();
        record.sourceKey = sourceKey;
        record.vodId = vodInfo.id;
        record.updateTime = System.currentTimeMillis();
        record.name = vodInfo.name;
        record.pic = vodInfo.pic;
        AppDataManager.get().getVodCollectDao().insert(record);
    }

    public static void deleteVodCollect(int id) {
        AppDataManager.get().getVodCollectDao().delete(id);
    }

    public static void deleteVodCollect(String sourceKey, VodInfo vodInfo) {
        VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodInfo.id);
        if (record != null) {
            AppDataManager.get().getVodCollectDao().delete(record);
        }
    }
    
    public static void deleteVodCollectAll() {
        AppDataManager.get().getVodCollectDao().deleteAll();
    }

    public static void deleteVodRecordAll() {
        AppDataManager.get().getVodRecordDao().deleteAll();
    }

    public static boolean isVodCollect(String sourceKey, String vodId) {
        VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodId);
        return record != null;
    }

    public static List<VodCollect> getAllVodCollect() {
        return AppDataManager.get().getVodCollectDao().getAll();
    }
}
