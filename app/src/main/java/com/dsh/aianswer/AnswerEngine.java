package com.dsh.aianswer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 模型生命周期 + 单线程推理队列。
 * 分流策略：云端可用且优先 → 云端；云端失败（Key 失效/限流/断网）→ 自动回退端侧。
 * 全走同一线程：既避免并发抢 KV cache，也保证切题时答案不会乱序返回。
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
        public String source  = "";   // 走了哪条路，显示在悬浮窗
        public String altLetters = null;  // 复核通道给出的不同答案（null 表示没有/一致）
        public String altSource  = null;
    }

    public interface Cb { void onResult(Result r); }

    private final Llama llama = new Llama();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;
    private volatile boolean loading = false;
    private volatile Runnable pendingDone = null;

    private AnswerEngine() {}

    public boolean ready()   { return llama.isReady(); }
    public boolean isBusy()  { return busy; }
    public String  soError() { return Llama.loadError(); }

    public void loadAsync(final Context ctx, final ModelManager.Spec spec, final Runnable done) {
        // 界面和无障碍服务可能同时要求加载：后来者挂起，等真正加载完一起回调，
        // 绝不能立刻回调（那会让调用方以为已经加载失败）
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

    /**
     * @param letters  屏幕上实际存在的选项字母，如 {"A","B","C","D"}
     * @param optLines 与 letters 对应的「A. 内容」文本行
     * @param multi    多选
     */
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
        final CloudSolver.Provider p = Prefs.provider(ctx);
        final boolean cloudUsable = !localOnly && Prefs.cloudOn(ctx) && Prefs.cloudReady(ctx, p);
        final boolean localUsable = Prefs.localOn(ctx) && llama.isReady();

        if (!cloudUsable && !localUsable) {
            Result r = new Result();
            r.error = "在线和本地都没启用（去设置里打开一个）";
            post(cb, r);
            return;
        }

        busy = true;
        worker.execute(new Runnable() {
            public void run() {
                long t0 = System.currentTimeMillis();
                Result r;
                boolean tryCloud = cloudUsable && (Prefs.preferCloud(ctx) || !localUsable);
                // 熔断中且本地可用：别再去碰云端，否则每题白等一轮重试
                if (tryCloud && localUsable && CloudSolver.cooling()) {
                    Log.i(TAG, "在线熔断中（" + CloudSolver.cooldownLeftSec() + "s），直接用端侧");
                    tryCloud = false;
                }

                long tCloud = 0;
                if (tryCloud) {
                    long c0 = System.currentTimeMillis();
                    r = askCloud(ctx, p, question, optLines, multi);
                    tCloud = System.currentTimeMillis() - c0;
                    if (r.letters.isEmpty() && localUsable) {
                        Log.i(TAG, "云端未给出答案（" + r.error + "），回退端侧");
                        Result local = askLocal(question, optLines, letters, multi);
                        local.source = local.source + " · 在线未成";
                        r = local;
                    }
                } else {
                    r = askLocal(question, optLines, letters, multi);
                }
                // ---- 拿不准时用另一条路复核 ----
                // 只在「主路置信度低于阈值」时触发：多数题不受影响，
                // 只有真正没把握的题才多花一次调用（也才不会把免费额度烧穿）。
                int pct = Math.round(r.conf * 100);
                boolean unsure = !r.letters.isEmpty() && pct < Prefs.thresholdPct(ctx);
                // 复核用哪条路：
                //   主路是端侧 → 只能用在线复核，而在线是花钱的，必须用户显式授权；
                //   主路是在线 → 用端侧复核，免费，随便跑。
                boolean canDual = Prefs.dualCheck(ctx) && unsure
                        && (tryCloud ? localUsable : (cloudUsable && Prefs.dualCloudAllowed(ctx)));
                if (canDual) {
                    String primary = r.letters;
                    Result second;
                    if (tryCloud) {
                        second = askLocal(question, optLines, letters, multi);   // 主路在线 → 复核用端侧（免费）
                    } else {
                        long c0 = System.currentTimeMillis();
                        second = askCloud(ctx, p, question, optLines, multi);     // 主路端侧 → 复核用在线（花钱）
                        tCloud += System.currentTimeMillis() - c0;
                    }
                    if (!second.letters.isEmpty()) {
                        if (second.letters.equals(primary)) {
                            Log.i(TAG, "两路一致：" + primary);
                            r.conf = Math.max(r.conf, 0.9f);   // 互相印证，提高置信
                        } else {
                            Log.i(TAG, "两路不一致：" + primary + " vs " + second.letters);
                            r.altLetters = second.letters;
                            r.altSource  = second.source;
                        }
                    }
                }
                r.ms = System.currentTimeMillis() - t0;
                r.cloudMs = tCloud;
                r.coreMs = r.ms - tCloud;
                busy = false;
                post(cb, r);
            }
        });
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
        return r;
    }

    private Result askLocal(String q, List<String> optLines, String[] letters, boolean multi) {
        Result r = new Result();
        r.source = "端侧模型";
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

    private void post(final Cb cb, final Result r) {
        main.post(new Runnable() {
            public void run() { cb.onResult(r); }
        });
    }
}
