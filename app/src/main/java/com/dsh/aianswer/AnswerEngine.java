package com.dsh.aianswer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 模型生命周期 + 单线程推理队列。
 *
 * <p>调度顺序（v7.2）：
 * <ol>
 *   <li><b>投票</b>：开了多模型投票且在线厂商 ≥2 → 多路问同一题，取多数；</li>
 *   <li><b>路由</b>：开了自动路由 → 按题型决定先问在线还是先问本地；</li>
 *   <li><b>兜底</b>：沿用原来的「在线优先/本地优先」+ 失败自动换路。</li>
 * </ol>
 *
 * <p>投票和复核互斥：两者都是「对拍」，叠加会让一次答题跑到 3~4 次调用，
 * 免费额度撑不住。开了投票就直接跳过复核分支。
 *
 * <p>全走同一线程：既避免并发抢 KV cache，也保证切题时答案不会乱序返回。
 */
public final class AnswerEngine {

    private static final String TAG = "AIQ";
    private static AnswerEngine sInst;

    public static synchronized AnswerEngine get() {
        if (sInst == null) sInst = new AnswerEngine();
        return sInst;
    }

    public static final class Result {
        public String letters = "";   // "A" 或 "ABD"
        public float  conf    = 0f;   // 0~1
        public long   ms      = 0;    // 总耗时（含在线尝试）
        public long   cloudMs = 0;    // 其中在线花掉的
        public long   coreMs  = 0;    // 真正出答案那一段
        public String error   = null;
        public String source  = "";   // 旧字段，内部用
        /** 人话版来源，直接显示：「DeepSeek 回答」「投票 3 家全一致」「本地 1.5B 回答」。 */
        public String answeredBy = null;
        public String route   = null; // 路由说明，如「路由：数学计算 → 在线」
        public String voteNote= null; // 投票结果，如「3/3 一致」
        /** 自选题库自带解析：有就直接显示，不再调模型生成。 */
        public String bankExplain = null;
        /**
         * 告警级别：0 正常 / 1 该留意（仅供参考、降级了、只剩一家）/ 2 不可信（分歧）。
         * 悬浮窗按它给外框上色，让「这答案不靠谱」一眼能看出来，不用读小字。
         */
        public int warnLevel = 0;
        public String altLetters = null;  // 另一路给出的不同答案（null 表示没有/一致）
        public String altSource  = null;
        /**
         * 真分歧（平票 / 各家各不相同）：此时没有「答案」可给，
         * 界面应显示「有分歧」并把每一家的答案都列出来，让用户自己判断，绝不自动点。
         */
        public boolean disputed = false;
        /** disputed 时有效：答案字母 → 投它的厂商名，按投票顺序。 */
        public java.util.Map<String, String> voteMap = null;
    }

    public interface Cb { void onResult(Result r); }
    public interface ExplainCb { void onExplain(String text); }

    private final Llama llama = new Llama();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;
    private volatile boolean loading = false;
    private volatile Runnable pendingDone = null;

    private AnswerEngine() {}

    public boolean ready()   { return llama.isReady(); }

    /**
     * 用本地模型生成一段文本（目前给错别字校对用）。
     * 阻塞调用，调用方必须在工作线程。
     */
    public String genLocal(String prompt, int maxTok) {
        if (!llama.isReady()) return null;
        return llama.gen(prompt, maxTok);
    }
    public boolean isBusy()  { return busy; }
    public String  soError() { return Llama.loadError(); }

    public void loadAsync(final Context ctx, final ModelManager.Spec spec, final Runnable done) {
        synchronized (this) {
            if (loading) { pendingDone = done; return; }
            loading = true;
        }
        worker.execute(new Runnable() {
            public void run() {
                File f = ModelManager.fileOf(ctx, spec);
                // 手机 SoC 是大小核混合（如 1+3+4）。线程数超过大核数时，快核会被慢核拖住同步，
                // 实测 4 线程比 8 线程快 20~40%。这里按「大核一般 3~4 个」取上限 4。
                int cores = Runtime.getRuntime().availableProcessors();
                int threads = Math.max(2, Math.min(4, cores));
                // 上下文按最长题目留足：题干 + 4 个长选项 ≈ 600 token
                boolean ok = llama.open(f.getAbsolutePath(), 1024, threads);
                Log.i(TAG, "加载 " + spec.id + " -> " + ok);
                loading = false;
                final Runnable a = done;
                final Runnable b = pendingDone;
                pendingDone = null;
                main.post(new Runnable() {
                    public void run() {
                        if (a != null) a.run();
                        if (b != null) b.run();
                    }
                });
            }
        });
    }

    public void unloadAsync(final Runnable done) {
        worker.execute(new Runnable() {
            public void run() {
                llama.close();
                main.post(done);
            }
        });
    }

    public void answerAsync(final Context ctx, final String question, final List<String> optLines,
                            final String[] letters, final boolean multi, final Cb cb) {
        answerAsync(ctx, question, optLines, letters, multi, false, cb);
    }

    /**
     * @param localOnly true 时强制只走端侧，绝不请求云端。
     *                  「性能自测」「手动试一道」用它：这些场景用户只是想量本地速度，
     *                  却在开在线时偷偷烧掉 5 次额度——这个坑必须堵上。
     */
    public void answerAsync(final Context ctx, final String question, final List<String> optLines,
                            final String[] letters, final boolean multi, final boolean localOnly,
                            final Cb cb) {
        answerAsync(ctx, question, optLines, letters, multi, localOnly, false, cb);
    }

    /**
     * @param hurry 屏幕上检测到倒计时。串行问 3 家最坏 13 秒，倒计时里等不起：
     *              投票缩到 2 家、总预算砍到 4.5 秒，在线也只试一家就回退本地。
     */
    public void answerAsync(final Context ctx, final String question, final List<String> optLines,
                            final String[] letters, final boolean multi, final boolean localOnly,
                            final boolean hurry, final Cb cb) {
        final CloudSolver.Provider p = Prefs.provider(ctx);
        final boolean cloudUsable = !localOnly && Prefs.cloudOn(ctx) && Prefs.cloudReady(ctx, p);
        final boolean localUsable = Prefs.localOn(ctx) && llama.isReady();
        // 投票需要两个以上「已配置」的在线厂商；只配一个时开关本身也会被 UI 置灰
        final boolean voteOn = !localOnly && Prefs.cloudOn(ctx) && Prefs.vote(ctx)
                && Prefs.cloudReadyCount(ctx) >= 2;

        if (!cloudUsable && !localUsable && !voteOn) {
            Result r = new Result();
            r.error = "在线和本地都没启用（去设置里打开一个）";
            post(cb, r);
            return;
        }

        busy = true;
        worker.execute(new Runnable() {
            public void run() {
                long t0 = System.currentTimeMillis();
                Result r = null;
                long tCloud = 0;

                // 路由先决定「这题该走哪条线」，投票只作用于在线那条线。
                // 这样两个能力可以同时开：短常识题照样本地秒答，不白烧投票额度。
                boolean bothRoutes = !localOnly && Prefs.autoRoute(ctx) && cloudUsable && localUsable;
                Router.Plan plan = bothRoutes ? Router.decide(question, optLines, multi) : null;
                String routeText = plan == null ? null
                        : plan.reason + " → " + (plan.cloud ? "在线" : "本地");

                if (voteOn && (plan == null || plan.cloud)) {
                    // ---- 1) 多模型投票（只在在线线路上）----
                    long c0 = System.currentTimeMillis();
                    r = askVote(ctx, question, optLines, multi, hurry);
                    tCloud = System.currentTimeMillis() - c0;
                    if (r.letters.isEmpty() && localUsable) {
                        Log.i(TAG, "投票无结果（" + r.error + "），回退端侧");
                        Result local = askLocal(ctx, question, optLines, letters, multi);
                        local.source = local.source + " · 投票未成";
                        r = local;
                    }
                    if (r != null) r.route = routeText;
                } else if (plan != null) {
                    // ---- 2) 按题型路由 ----
                    boolean tryCloud = plan.cloud;
                    if (tryCloud && CloudSolver.cooling(p.id)) {
                        Log.i(TAG, "路由到在线但熔断中，改走本地");
                        tryCloud = false;
                        routeText = routeText + "（在线暂停）";
                    }
                    if (tryCloud) {
                        long c0 = System.currentTimeMillis();
                        Result cr = askCloudAny(ctx, question, optLines, multi, hurry);
                        tCloud = System.currentTimeMillis() - c0;
                        if (cr.letters.isEmpty()) {
                            Log.i(TAG, "在线未给出答案（" + cr.error + "），回退端侧");
                            Result local = askLocal(ctx, question, optLines, letters, multi);
                            local.source = local.source + " · 在线未成";
                            local.warnLevel = 1;
                            cr = local;
                        }
                        cr.route = routeText;
                        r = cr;
                    } else {
                        Result lr = askLocal(ctx, question, optLines, letters, multi);
                        if (lr.letters.isEmpty() && cloudUsable && !CloudSolver.cooling(p.id)) {
                            long c0 = System.currentTimeMillis();
                            Log.i(TAG, "本地未给出答案，回退在线");
                            Result cr = askCloudAny(ctx, question, optLines, multi, hurry);
                            tCloud = System.currentTimeMillis() - c0;
                            cr.route = routeText + "（本地未成）";
                            r = cr;
                        } else {
                            lr.route = routeText;
                            r = lr;
                        }
                    }
                } else {
                    // ---- 3) 原来的「在线优先/本地优先」 ----
                    boolean tryCloud = cloudUsable && (Prefs.preferCloud(ctx) || !localUsable);
                    if (tryCloud && localUsable && CloudSolver.cooling(p.id)) {
                        Log.i(TAG, "在线熔断中（" + CloudSolver.cooldownLeftSec() + "s），直接用端侧");
                        tryCloud = false;
                    }
                    if (tryCloud) {
                        long c0 = System.currentTimeMillis();
                        r = askCloudAny(ctx, question, optLines, multi, hurry);
                        tCloud = System.currentTimeMillis() - c0;
                        if (r.letters.isEmpty() && localUsable) {
                            Log.i(TAG, "云端未给出答案（" + r.error + "），回退端侧");
                            Result local = askLocal(ctx, question, optLines, letters, multi);
                            local.source = local.source + " · 在线未成";
                            local.warnLevel = 1;
                            r = local;
                        }
                    } else {
                        r = askLocal(ctx, question, optLines, letters, multi);
                        if (r.letters.isEmpty() && cloudUsable && !CloudSolver.cooling(p.id)) {
                            long c0 = System.currentTimeMillis();
                            r = askCloud(ctx, p, question, optLines, multi);
                            tCloud = System.currentTimeMillis() - c0;
                        }
                    }
                }
                if (r == null) { r = new Result(); r.error = "没有可用通道"; }

                // ---- 4) 拿不准时另一条路复核（投票开启时跳过，避免 3~4 次调用）----
                int pct = Math.round(r.conf * 100);
                boolean unsure = !r.letters.isEmpty() && pct < Prefs.thresholdPct(ctx);
                boolean canDual = !voteOn && Prefs.dualCheck(ctx) && unsure
                        && localUsable && cloudUsable;
                if (canDual) {
                    String primary = r.letters;
                    Result second;
                    boolean primaryCloud = r.source != null && r.source.contains("在线");
                    if (primaryCloud) {
                        second = askLocal(ctx, question, optLines, letters, multi);   // 主路在线 → 端侧复核（免费）
                    } else {
                        long c0 = System.currentTimeMillis();
                        second = askCloud(ctx, p, question, optLines, multi);         // 主路端侧 → 在线复核（花钱）
                        tCloud += System.currentTimeMillis() - c0;
                    }
                    if (!second.letters.isEmpty()) {
                        if (second.letters.equals(primary)) {
                            Log.i(TAG, "两路一致：" + primary);
                            r.conf = Math.max(r.conf, 0.9f);
                        } else {
                            Log.i(TAG, "两路不一致：" + primary + " vs " + second.letters);
                            r.altLetters = second.letters;
                            r.altSource  = second.source;
                        }
                    }
                }

                r.ms = System.currentTimeMillis() - t0;
                r.cloudMs = tCloud;
                // coreMs 是「真正出答案那一段」的耗时：
                // 以前一律算 ms - tCloud，投票时 tCloud 就是全部耗时 → 显示 1ms，
                // 用户会以为答案是从哪缓存里蹦出来的。答案出自在线就报在线那段。
                boolean answerFromLocal = r.source != null
                        && (r.source.startsWith("端侧") || r.source.startsWith("本地"));
                r.coreMs = answerFromLocal
                        ? Math.max(0, r.ms - tCloud)
                        : (tCloud > 0 ? tCloud : r.ms);
                busy = false;
                post(cb, r);
            }
        });
    }

    /**
     * 多模型投票：最多 3 路串行（llama 单实例、免费档基本限 1 并发，串行最稳）。
     * 单路失败不计票；全失败才报错。3/3 全票 → 绿；2:1 → 黄；三方各异 → 红且不自动点。
     */
    private Result askVote(Context ctx, String q, List<String> optLines, boolean multi, boolean hurry) {
        Result r = new Result();
        r.source = "投票";
        // 先按健康度排序再截断：谁最近老过载，谁就排在后面，
        // 不然名额被它占着，投出来的「多数」永远是同一家说了算。
        // 只问用户在设置里勾选的那些厂商（没勾就退化为全部已配置）
        java.util.Set<String> picked = Prefs.voteProviders(ctx);
        java.util.List<CloudSolver.Provider> only = new ArrayList<>();
        for (CloudSolver.Provider cp : Prefs.cloudReadyList(ctx)) {
            if (picked.isEmpty() || picked.contains(cp.id)) only.add(cp);
        }
        List<CloudSolver.Provider> ps = CloudSolver.orderByHealth(only);
        int maxProv = hurry ? 2 : 3;
        if (ps.size() > maxProv) ps = new ArrayList<>(ps.subList(0, maxProv));

        Map<String, Integer> tally = new LinkedHashMap<>();
        Map<String, String> who = new LinkedHashMap<>();
        // 每个答案字母是哪家投的：平票时要靠它选可信度更高的那一路，而不是取先到的
        Map<String, CloudSolver.Provider> prov = new LinkedHashMap<>();
        List<String> fails = new ArrayList<>();
        int ok = 0;
        // 总预算：串行 3 家最坏 13 秒，考试倒计时等不起。超过预算就不再问剩下的，
        // 用手上已有的票给结果（宁可少一票，也不要卡住不动）。
        final long deadline = System.currentTimeMillis() + (hurry ? VOTE_BUDGET_HURRY_MS : VOTE_BUDGET_MS);

        for (CloudSolver.Provider p : ps) {
            if (!tally.isEmpty() && System.currentTimeMillis() > deadline) {
                fails.add(shortName(p) + " 跳过(超时预算)");
                Log.i(TAG, "投票预算用完，跳过 " + p.id);
                break;
            }
            Prefs.addCloudCall(ctx);
            CloudSolver.Out o = CloudSolver.ask(p, Prefs.key(ctx, p),
                    Prefs.baseCustom(ctx), Prefs.modelCustom(ctx), q, optLines, multi);
            if (o.letters.isEmpty()) {
                Log.i(TAG, "投票 " + p.id + " 未给答案：" + o.error);
                fails.add(shortName(p) + " " + shortErr(o.error));
                continue;
            }
            ok++;
            Integer c = tally.get(o.letters);
            tally.put(o.letters, c == null ? 1 : c + 1);
            String label = p.label.replaceAll("（.*$", "");
            String prev = who.get(o.letters);
            who.put(o.letters, prev == null ? label : prev + "·" + label);
            if (!prov.containsKey(o.letters)) prov.put(o.letters, p);
        }

        if (tally.isEmpty()) {
            r.error = "投票全部失败（检查 Key 或稍后再试）";
            return r;
        }

        String best = null, second = null;
        int bestN = 0, secondN = 0;
        for (Map.Entry<String, Integer> e : tally.entrySet()) {
            if (e.getValue() > bestN) {
                second = best; secondN = bestN;
                best = e.getKey(); bestN = e.getValue();
            } else if (e.getValue() > secondN) {
                second = e.getKey(); secondN = e.getValue();
            }
        }

        String failNote = fails.isEmpty() ? "" : "（" + joinFails(fails) + "）";
        r.letters = best;
        if (ok == 1) {
            // 只有一家答上来：这不是"一致"，是"其他人全挂了"，必须点名说清
            r.conf = 0.85f;
            r.answeredBy = who.get(best) + " 回答";
            r.voteNote = "其余厂商没答上来" + failNote;
            r.source = "单模型 · " + who.get(best);
            r.warnLevel = 1;      // 只剩一家：答案可用，但没经过交叉验证
        } else if (bestN == ok && ok > 1) {
            r.conf = 0.95f;
            r.answeredBy = "投票 " + ok + " 家全一致";   // 一致时不点名：写谁没意义
            if (!failNote.isEmpty()) r.voteNote = "另有厂商未答" + failNote;
            r.source = "投票 " + ok + "/" + ok;
        } else if (bestN * 2 > ok) {
            r.conf = 0.8f;
            r.answeredBy = "投票 " + bestN + "/" + ok + " 多数";
            r.voteNote = failNote;
            r.source = "投票 " + bestN + "/" + ok;
        } else {
            /*
             * 平票 / 三方各说一套：标红，QService 不会自动点击。
             *
             * 平票时**绝不能取先到的那一家**。实测两家各投一票：
             * 先到的给了 A（错），后到的 DeepSeek 给了 B（对），
             * 结果悬浮窗上写「答案 A」——用户看到的就是「为什么答案会叫选 A」。
             * 这里改成按模型可信度挑主答案：付费 > 免费，同等再比成功率。
             */
            String pick = best;
            if (best != null && second != null) {
                CloudSolver.Provider pb = prov.get(best), p2 = prov.get(second);
                if (pb != null && p2 != null) {
                    boolean secondBetter = (!p2.free && pb.free)
                            || (p2.free == pb.free
                                && CloudSolver.successRate(p2.id) > CloudSolver.successRate(pb.id));
                    if (secondBetter) pick = second;
                }
            }
            r.letters = pick;
            r.conf = 0.45f;
            r.warnLevel = 2;
            r.disputed = true;          // 界面显示「有分歧」，不再假装给一个答案
            r.voteMap = who;            // 每家给了什么，全部摆出来
            r.answeredBy = "投票分歧（" + ok + " 家各不相同）";
            r.voteNote = failNote;
            r.source = "投票 分歧";
            String otherSide = pick.equals(best) ? second : best;
            if (otherSide != null) {
                r.altLetters = otherSide;
                r.altSource = "投票 · " + who.get(otherSide);
            }
            return r;
        }
        if (second != null && secondN > 0) {
            // 少数派的答案也要摆出来：用户有权知道另一家给的是什么。
            // 注意 warnLevel 保持 0 —— 2:1 多数仍然算「有共识」，照常自动点；
            // 只有平票 / 三方各说一套才会走上面的 warnLevel=2（红框且不点）。
            r.altLetters = second;
            r.altSource = "投票 · " + who.get(second);
        }
        return r;
    }

    /**
     * 在线通道：按健康度依次问，最多两家。
     *
     * <p>以前只问 {@code Prefs.provider()} 这一家，它一过载就直接掉本地。
     * 免费档动不动的 1305 等于把答案押在一家身上；这里失败就换下一家健康的，
     * 换过就在来源里注明，用户知道这次是备用模型答的。
     */
    private Result askCloudAny(Context ctx, String q, List<String> optLines, boolean multi, boolean hurry) {
        List<CloudSolver.Provider> ps = CloudSolver.orderByHealth(Prefs.cloudReadyList(ctx));
        int max = hurry ? 1 : 2;
        Result last = null;
        int tried = 0;
        for (CloudSolver.Provider cp : ps) {
            if (tried >= max) break;
            if (!Prefs.cloudReady(ctx, cp)) continue;
            tried++;
            Result r = askCloud(ctx, cp, q, optLines, multi);
            if (!r.letters.isEmpty()) {
                if (tried > 1) r.answeredBy = r.answeredBy + "（第 " + tried + " 家才成）";
                return r;
            }
            last = r;
        }
        if (last == null) {
            return askCloud(ctx, Prefs.provider(ctx), q, optLines, multi);
        }
        return last;
    }

    private Result askCloud(Context ctx, CloudSolver.Provider p,
                            String q, List<String> optLines, boolean multi) {
        Result r = new Result();
        Prefs.addCloudCall(ctx);          // 只计数，用来让用户看到「一共用了几次在线」
        CloudSolver.Out o = CloudSolver.ask(p,
                Prefs.key(ctx, p), Prefs.baseCustom(ctx), Prefs.modelCustom(ctx),
                q, optLines, multi);
        r.letters = o.letters;
        r.error   = o.error;
        r.conf    = o.letters.isEmpty() ? 0f : 0.95f;   // 云端不给概率，按高置信处理
        r.source  = "在线 · " + p.label.replaceAll("（.*$", "");
        r.answeredBy = p.label.replaceAll("（.*$", "") + " 回答";
        return r;
    }

    /** 投票总预算：超过就用手上的票出结果，不再等剩下的厂商。 */
    private static final long VOTE_BUDGET_MS = 9000;
    /** 倒计时场景的预算：两题之间只剩十几秒时，9 秒等于交白卷。 */
    private static final long VOTE_BUDGET_HURRY_MS = 4500;

    private static String shortName(CloudSolver.Provider p) {
        String s = p.label.replaceAll("（.*$", "");
        return s.length() > 6 ? s.substring(0, 6) : s;
    }

    /** 失败原因压到很短，最多列出两条（悬浮窗只有一行 meta）。 */
    private static String joinFails(List<String> fails) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fails.size() && i < 2; i++) {
            if (sb.length() > 0) sb.append("、");
            sb.append(fails.get(i));
        }
        if (fails.size() > 2) sb.append("等").append(fails.size()).append("家");
        return sb.toString();
    }

    private static String shortErr(String e) {
        if (e == null) return "未答";
        if (e.contains("1305") || e.contains("过载")) return "过载";
        if (e.contains("1302") || e.contains("429") || e.contains("频繁")) return "限流";
        if (e.contains("暂停中")) return "冷却";
        if (e.contains("超时") || e.contains("timeout")) return "超时";
        if (e.contains("Key") || e.contains("401")) return "Key失效";
        if (e.contains("余额") || e.contains("1113") || e.contains("402")) return "欠费";
        return e.length() > 8 ? e.substring(0, 8) : e;
    }

    private Result askLocal(Context ctx, String q, List<String> optLines, String[] letters, boolean multi) {
        Result r = new Result();
        r.source = "端侧 " + Prefs.spec(ctx).badge;
        r.answeredBy = "本地 " + Prefs.spec(ctx).badge + " 回答";
        if (!llama.isReady()) { r.error = "端侧模型未加载"; return r; }
        try {
            String prompt = PromptBuilder.build(q, optLines, multi);
            if (multi) {
                r.letters = pickMulti(prompt, r);
            } else {
                r.letters = pickSingle(prompt, letters, r);
            }
        } catch (Throwable t) {
            r.error = t.toString();
            Log.e(TAG, "推理异常", t);
        }
        return r;
    }

    /** 单选：一次 prefill，直接比字母 logits，不做采样。 */
    private String pickSingle(String prompt, String[] letters, Result r) {
        float[] p = llama.score(prompt, letters);
        if (p == null || p.length == 0) { r.error = "推理失败"; return ""; }
        int best = 0;
        for (int i = 1; i < p.length; i++) if (p[i] > p[best]) best = i;
        r.conf = p[best];
        return letters[best];
    }

    /** 多选：logits 只表达一个位置，只能走短生成，最多吐 6 个 token。 */
    private String pickMulti(String prompt, Result r) {
        String out = llama.gen(prompt, 6);
        if (out == null) { r.error = "推理失败"; return ""; }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.length() && sb.length() < 6; i++) {
            char c = Character.toUpperCase(out.charAt(i));
            if (c >= 'A' && c <= 'F' && sb.indexOf(String.valueOf(c)) < 0) sb.append(c);
        }
        r.conf = sb.length() > 0 ? 0.7f : 0f;
        return sb.toString();
    }

    /**
     * 生成一句解析。答案已经显示出去了才调用，慢一点没关系，所以异步补。
     * 在线可用时优先在线（解析质量明显更好）；否则只允许 1.5B 以上档位本地生成。
     */
    public void explainAsync(final Context ctx, final String question, final List<String> optLines,
                             final String letters, final ExplainCb cb) {
        worker.execute(new Runnable() {
            public void run() {
                String text = "";
                CloudSolver.Provider p = Prefs.provider(ctx);
                try {
                    if (Prefs.cloudOn(ctx) && Prefs.cloudReady(ctx, p) && !CloudSolver.cooling(p.id)) {
                        text = CloudSolver.explain(p, Prefs.key(ctx, p),
                                Prefs.baseCustom(ctx), Prefs.modelCustom(ctx),
                                question, optLines, letters);
                    }
                } catch (Throwable ignored) {}
                if (text.isEmpty() && llama.isReady()
                        && Prefs.spec(ctx).bytes >= ModelManager.LARGE.bytes) {
                    try {
                        String prompt = PromptBuilder.buildExplain(question, optLines, letters);
                        text = CloudSolver.tidyExplain(llama.gen(prompt, 28));
                    } catch (Throwable t) {
                        Log.w(TAG, "本地解析失败", t);
                    }
                }
                final String fin = text == null ? "" : text;
                main.post(new Runnable() {
                    public void run() { cb.onExplain(fin); }
                });
            }
        });
    }

    private void post(final Cb cb, final Result r) {
        main.post(new Runnable() {
            public void run() { cb.onResult(r); }
        });
    }
}
