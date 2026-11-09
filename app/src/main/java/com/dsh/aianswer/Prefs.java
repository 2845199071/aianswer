package com.dsh.aianswer;

import android.content.Context;
import android.content.SharedPreferences;

/** 设置项集中一处，避免 QService 和 MainActivity 各写一套 key。 */
public final class Prefs {

    private static final String F = "aiq";
    private static final String K_SPEC     = "spec_id";
    private static final String K_AUTO     = "auto_answer";
    private static final String K_CLICK    = "auto_click";
    private static final String K_THRES    = "threshold";

    private static final String K_CLOUD_ON = "cloud_on";
    private static final String K_PROVIDER = "provider";
    private static final String K_KEY_     = "key_";
    private static final String K_BASE_CUS = "base_custom";
    private static final String K_MODEL_CUS = "model_custom";
    private static final String K_PREFER_CLOUD = "prefer_cloud";
    private static final String K_LOCAL_ON = "local_on";
    private static final String K_OVERLAY_HIDDEN = "overlay_hidden";
    private static final String K_DUAL    = "dual_check";     // 拿不准时另一条路复核
    private static final String K_VIBRATE = "vibrate";
    private static final String K_AVOID   = "avoid_overlay";  // 悬浮窗自动避开选项
    private static final String K_DUAL_CLOUD = "dual_cloud";  // 允许复核时消耗在线额度
    private static final String K_CLOUD_CALLS = "cloud_calls"; // 累计在线调用次数（仅计数，不涉Key）

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(F, Context.MODE_PRIVATE);
    }

    // ---------- 端侧模型 ----------

    public static String specId(Context c) {
        return sp(c).getString(K_SPEC, ModelManager.LARGE.id);
    }
    public static void setSpecId(Context c, String v) {
        sp(c).edit().putString(K_SPEC, v).apply();
    }
    /** 用户是否已经明确选过档位（没选过才按内存自动推荐）。 */
    public static boolean hasSpecChosen(Context c) {
        return sp(c).contains(K_SPEC);
    }

    public static ModelManager.Spec spec(Context c) {
        String id = specId(c);
        for (ModelManager.Spec s : ModelManager.ALL) if (s.id.equals(id)) return s;
        return ModelManager.LARGE;
    }

    // ---------- 行为 ----------

    public static boolean autoAnswer(Context c) { return sp(c).getBoolean(K_AUTO, false); }
    public static void setAutoAnswer(Context c, boolean v) { sp(c).edit().putBoolean(K_AUTO, v).apply(); }

    public static boolean autoClick(Context c) { return sp(c).getBoolean(K_CLICK, false); }
    public static void setAutoClick(Context c, boolean v) { sp(c).edit().putBoolean(K_CLICK, v).apply(); }

    public static int thresholdPct(Context c) { return sp(c).getInt(K_THRES, 55); }
    public static void setThresholdPct(Context c, int v) { sp(c).edit().putInt(K_THRES, v).apply(); }

    // ---------- 云端 ----------

    /** 本地模型总开关：关掉后即使模型已加载也不参与答题。 */
    public static boolean localOn(Context c) { return sp(c).getBoolean(K_LOCAL_ON, true); }
    public static void setLocalOn(Context c, boolean v) { sp(c).edit().putBoolean(K_LOCAL_ON, v).apply(); }

    /** 悬浮窗是否被用户主动隐藏。隐藏后服务重连也不会自己冒出来。 */
    /** 主路置信度低于阈值时，用另一条路再算一遍对拍。 */
    public static boolean dualCheck(Context c) { return sp(c).getBoolean(K_DUAL, false); }
    public static void setDualCheck(Context c, boolean v) { sp(c).edit().putBoolean(K_DUAL, v).apply(); }

    /**
     * 复核时是否允许调用**在线**（会花钱）。
     * 默认 false：主路是本地时不主动花钱复核；主路是在线时用本地复核（免费）。
     * 只有用户明确打开，才会为了复核去花一次在线额度。
     */
    public static boolean dualCloudAllowed(Context c) { return sp(c).getBoolean(K_DUAL_CLOUD, false); }
    public static void setDualCloudAllowed(Context c, boolean v) {
        sp(c).edit().putBoolean(K_DUAL_CLOUD, v).apply();
    }

    /** 累计在线调用次数，让用户对「花了多少」心里有数。 */
    public static int cloudCalls(Context c) { return sp(c).getInt(K_CLOUD_CALLS, 0); }
    public static void addCloudCall(Context c) {
        sp(c).edit().putInt(K_CLOUD_CALLS, cloudCalls(c) + 1).apply();
    }
    public static void resetCloudCalls(Context c) { sp(c).edit().remove(K_CLOUD_CALLS).apply(); }

    /** 出答案时轻振一下，省得一直盯着悬浮窗。 */
    public static boolean vibrate(Context c) { return sp(c).getBoolean(K_VIBRATE, true); }
    public static void setVibrate(Context c, boolean v) { sp(c).edit().putBoolean(K_VIBRATE, v).apply(); }

    /** 悬浮窗自动避开选项区域。 */
    public static boolean avoidOverlay(Context c) { return sp(c).getBoolean(K_AVOID, true); }
    public static void setAvoidOverlay(Context c, boolean v) { sp(c).edit().putBoolean(K_AVOID, v).apply(); }

    public static boolean overlayHidden(Context c) { return sp(c).getBoolean(K_OVERLAY_HIDDEN, false); }
    public static void setOverlayHidden(Context c, boolean v) {
        sp(c).edit().putBoolean(K_OVERLAY_HIDDEN, v).apply();
    }

    public static boolean cloudOn(Context c) { return sp(c).getBoolean(K_CLOUD_ON, false); }
    public static void setCloudOn(Context c, boolean v) { sp(c).edit().putBoolean(K_CLOUD_ON, v).apply(); }

    /** true = 先问云端，失败了再退端侧；false = 端侧优先。 */
    public static boolean preferCloud(Context c) { return sp(c).getBoolean(K_PREFER_CLOUD, true); }
    public static void setPreferCloud(Context c, boolean v) { sp(c).edit().putBoolean(K_PREFER_CLOUD, v).apply(); }

    public static String providerId(Context c) {
        return sp(c).getString(K_PROVIDER, CloudSolver.ZHIPU.id);
    }
    public static void setProviderId(Context c, String v) {
        sp(c).edit().putString(K_PROVIDER, v).apply();
    }
    public static CloudSolver.Provider provider(Context c) {
        return CloudSolver.byId(providerId(c));
    }

    public static String key(Context c, CloudSolver.Provider p) {
        return sp(c).getString(K_KEY_ + p.id, "");
    }
    public static void setKey(Context c, CloudSolver.Provider p, String v) {
        sp(c).edit().putString(K_KEY_ + p.id, v == null ? "" : v.trim()).apply();
    }

    public static String baseCustom(Context c) { return sp(c).getString(K_BASE_CUS, ""); }
    public static void setBaseCustom(Context c, String v) { sp(c).edit().putString(K_BASE_CUS, v == null ? "" : v.trim()).apply(); }

    public static String modelCustom(Context c) { return sp(c).getString(K_MODEL_CUS, ""); }
    public static void setModelCustom(Context c, String v) { sp(c).edit().putString(K_MODEL_CUS, v == null ? "" : v.trim()).apply(); }

    /** 当前厂商是否已具备调用条件（Key 填了，自定义还要 Base + 模型名）。 */
    public static boolean cloudReady(Context c, CloudSolver.Provider p) {
        if (key(c, p).isEmpty()) return false;
        if (p.id.equals(CloudSolver.CUSTOM.id)) {
            return !baseCustom(c).isEmpty() && !modelCustom(c).isEmpty();
        }
        return true;
    }
}
