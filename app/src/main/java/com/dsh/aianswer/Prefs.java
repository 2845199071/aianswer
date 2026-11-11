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

    // ---- v7.1 / v7.2 新增 ----
    private static final String K_EXPLAIN   = "explain";       // 出答案附一句解析
    private static final String K_HUMAN     = "human_click";   // 拟人点击（默认关）
    private static final String K_AUTO_NEXT = "auto_next";     // 答完自动点下一题（默认关）
    private static final String K_ROUTE     = "auto_route";    // 按题型自动选模型
    private static final String K_VOTE      = "vote";          // 多模型投票（旧：只有开关）
    private static final String K_VOTE_IDS  = "vote_ids";      // 新：参与投票的厂商 id，逗号分隔
    private static final String K_SELF_FIRST= "self_bank_first"; // 先查自选题库，未命中再问 AI
    private static final String K_ACC_GUIDED = "acc_guided";    // 首次读屏引导是否已弹过

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
        // 不在内置三档里：可能是模型市场下载的新档位
        ModelMarket.Item it = ModelMarket.byId(c, id);
        if (it != null) return ModelMarket.specOf(it);
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

    /** 已配置好的在线厂商数量：投票制要 ≥2 才能开。 */
    public static int cloudReadyCount(Context c) {
        int n = 0;
        for (CloudSolver.Provider p : CloudSolver.ALL) {
            if (cloudReady(c, p)) n++;
        }
        return n;
    }

    /** 已配置好的在线厂商，按「免费优先 + 预设顺序」排列，投票时最多取前 3 个。 */
    public static java.util.List<CloudSolver.Provider> cloudReadyList(Context c) {
        java.util.List<CloudSolver.Provider> out = new java.util.ArrayList<>();
        for (CloudSolver.Provider p : CloudSolver.ALL) {
            if (cloudReady(c, p) && p.free) out.add(p);
        }
        for (CloudSolver.Provider p : CloudSolver.ALL) {
            if (cloudReady(c, p) && !p.free) out.add(p);
        }
        return out;
    }

    // ---------- v7.1 新增 ----------

    /** 出答案后附一句解析。默认关：解析是第二次推理，会多花 0.5~2 秒。 */
    public static boolean explainOn(Context c) { return sp(c).getBoolean(K_EXPLAIN, false); }
    public static void setExplainOn(Context c, boolean v) { sp(c).edit().putBoolean(K_EXPLAIN, v).apply(); }

    /** 拟人点击：给点击加随机延迟与轻微偏移。默认关，必须用户显式打开。 */
    /** 答完自动点「下一题」。默认关：连答是批量刷题才需要的行为，不该默认替用户做。 */
    public static boolean autoNext(Context c) { return sp(c).getBoolean(K_AUTO_NEXT, false); }
    public static void setAutoNext(Context c, boolean v) { sp(c).edit().putBoolean(K_AUTO_NEXT, v).apply(); }

    public static boolean humanClick(Context c) { return sp(c).getBoolean(K_HUMAN, false); }
    public static void setHumanClick(Context c, boolean v) { sp(c).edit().putBoolean(K_HUMAN, v).apply(); }

    /**
     * 按题型自动选模型（短题走本地、数学/文言/长题走在线）。
     * **默认关闭**：路由会让「这题到底走的哪条通道」变得不直观，用户排查答案来源时容易困惑；
     * 需要省额度时再手动打开。
     */
    public static boolean autoRoute(Context c) { return sp(c).getBoolean(K_ROUTE, false); }
    public static void setAutoRoute(Context c, boolean v) { sp(c).edit().putBoolean(K_ROUTE, v).apply(); }

    /**
     * 参与投票的厂商 id 列表；空集合 = 关闭投票。
     * 用户可以在设置里只挑几家参与，不必把填过 Key 的全部拉进来。
     */
    public static java.util.Set<String> voteProviders(Context c) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        String s = sp(c).getString(K_VOTE_IDS, null);
        if (s == null) {
            // 老版本只有总开关：开着就按「全部已配置」迁移一次，行为保持一致
            if (sp(c).getBoolean(K_VOTE, false)) {
                for (CloudSolver.Provider p : cloudReadyList(c)) out.add(p.id);
            }
            return out;
        }
        for (String x : s.split(",")) {
            String t = x.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public static void setVoteProviders(Context c, java.util.Set<String> ids) {
        StringBuilder sb = new StringBuilder();
        if (ids != null) {
            for (String x : ids) {
                if (x == null || x.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(x.trim());
            }
        }
        sp(c).edit().putString(K_VOTE_IDS, sb.toString()).apply();
    }

    /** 投票是否开启：选了一家就算开（全不选即关闭）。 */
    public static boolean vote(Context c) { return !voteProviders(c).isEmpty(); }

    /** 兼容旧调用：true = 选中全部已配置，false = 清空（关闭投票）。 */
    public static void setVote(Context c, boolean v) {
        if (!v) {
            setVoteProviders(c, new java.util.LinkedHashSet<String>());
            return;
        }
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        for (CloudSolver.Provider p : cloudReadyList(c)) all.add(p.id);
        setVoteProviders(c, all);
    }

    /**
     * 先查自选题库、没命中再问 AI。默认开。
     * 题库命中是 0 次在线调用、毫秒级，所以「有题库就用」是更合理的默认。
     */
    public static boolean accGuided(Context c) { return sp(c).getBoolean(K_ACC_GUIDED, false); }
    public static void setAccGuided(Context c, boolean v) { sp(c).edit().putBoolean(K_ACC_GUIDED, v).apply(); }

    public static boolean selfBankFirst(Context c) { return sp(c).getBoolean(K_SELF_FIRST, true); }
    public static void setSelfBankFirst(Context c, boolean v) {
        sp(c).edit().putBoolean(K_SELF_FIRST, v).apply();
    }

    /**
     * 视觉识别的「题目区域」缓存（按包名 + 屏幕尺寸）。
     *
     * <p>整屏 1280×2772 里有大半个屏幕是广告、会员条、底部导航，全发给模型既慢又贵。
     * 第一次整屏识别时，模型会返回每个选项的坐标，据此反推出题干 + 选项所在的矩形，
     * 之后同一页面只发这一块。布局变了会让结果变差，那时代码会自动清掉缓存重来。
     */
    private static final String K_VISION_CROP = "vision_crop";

    public static String visionCrop(Context c) { return sp(c).getString(K_VISION_CROP, ""); }
    public static void setVisionCrop(Context c, String v) {
        sp(c).edit().putString(K_VISION_CROP, v == null ? "" : v).apply();
    }

    /** 用户亲手校准过：自动推断就不许再覆盖它。 */
    private static final String K_VISION_MANUAL = "vision_crop_manual";
    public static boolean visionCropManual(Context c) { return sp(c).getBoolean(K_VISION_MANUAL, false); }
    public static void setVisionCropManual(Context c, boolean v) {
        sp(c).edit().putBoolean(K_VISION_MANUAL, v).apply();
    }
}
