package com.dsh.aianswer;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 读屏 → 解题 → 可选点击。
 * 事件到达后延迟 400ms 再扫，避开平台切题动画期间的半截 DOM。
 */
public class QService extends AccessibilityService {

    private static final String TAG = "AIQ";
    private static volatile QService sInst;

    /** 自己的包名：设置页里「粘贴题干和选项，例如…」那段示例文字会被版面解析当成题目，必须排除。 */
    private static final String SELF_PKG = "com.dsh.aianswer";
    private static final String[] SKIP_PKGS = {
            SELF_PKG,
            "com.android.systemui",
            "com.android.settings",
            "com.miui.securitycenter",
    };

    public static QService get() { return sInst; }

    private final Handler h = new Handler(Looper.getMainLooper());
    private String lastSig = "";                 // 上一道题的稳定签名（题干+选项，已剥离动态内容）
    private long lastAnswerAt = 0;               // 上一次出答案的时刻，用于冷却
    private volatile String lastLetters = "";

    private final Runnable fire = new Runnable() {
        public void run() { tick(); }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInst = this;
        Log.i(TAG, "无障碍服务已连接");
        Overlay.get(this).show();
        Overlay.get(this).update("待命中", AnswerEngine.get().ready() ? "模型已就绪" : "准备中…");
        ensureModelLoaded();
    }

    /**
     * 进程被系统回收后服务会静默重连（App 界面没打开、模型也就没加载过），
     * 这里补一次自动加载，否则悬浮窗会一直显示「未就绪」。
     */
    private void ensureModelLoaded() {
        if (!Prefs.localOn(this)) return;      // 用户关了本地模型就别偷偷加载
        if (AnswerEngine.get().ready() || AnswerEngine.get().isBusy()) return;
        final ModelManager.Spec s = Prefs.spec(this);
        if (!ModelManager.canLoad(this, s)) return;
        Log.i(TAG, "无障碍侧自动加载模型 " + s.id);
        Overlay.get(this).update("准备中…", "加载本地模型");
        AnswerEngine.get().loadAsync(this, s, new Runnable() {
            public void run() {
                boolean ok = AnswerEngine.get().ready();
                Log.i(TAG, "无障碍侧加载完成 " + ok);
                Overlay.get(QService.this).update(
                        ok ? "待命中" : "加载失败",
                        ok ? "模型已就绪" : "回 App 重试");
            }
        });
    }

    @Override
    public void onDestroy() {
        sInst = null;
        Overlay.get(this).hide();
        notifyDisconnected();
        super.onDestroy();
    }

    /**
     * 服务被系统杀掉或被关掉时提醒一声。
     * 不然用户切到答题页发现悬浮窗没了，只会以为「App 坏了」。
     */
    private void notifyDisconnected() {
        try {
            if (!Prefs.autoAnswer(this)) return;      // 本来就没在用，不用打扰
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            String ch = "aiq";
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        ch, "服务状态", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, ch)
                    : new Notification.Builder(this);
            b.setContentTitle("AI 答题已停止读屏")
                    .setContentText("无障碍服务被关闭或被系统回收，点此重新开启")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(1001, b.build());
        } catch (Throwable ignored) {}
    }

    @Override
    public void onInterrupt() {
        sInst = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        int t = e.getEventType();
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        if (!Prefs.autoAnswer(this)) return;
        // 自己产生的窗口事件（悬浮窗刷新）直接忽略，别自己唤醒自己
        CharSequence pk = e.getPackageName();
        if (pk != null && SELF_PKG.contentEquals(pk)) return;
        if (AnswerEngine.get().isBusy()) return;
        h.removeCallbacks(fire);
        h.postDelayed(fire, 400);
    }

    /** App 里点「悬浮窗：显示中」→ 收起。 */
    public static void hideOverlay() {
        QService s = sInst;
        if (s == null) return;
        Overlay.get(s).hideByUser();
    }

    /** App 里点「悬浮窗：已隐藏」→ 重新显示。 */
    public static void showOverlay() {
        QService s = sInst;
        if (s == null) return;
        Overlay.get(s).showByUser();
    }

    public static boolean serviceAlive() { return sInst != null; }

    /** 打开配置弹窗时先把悬浮窗收走，别压着弹窗内容（截图里它就盖住过右上角）。 */
    public static void pauseOverlayForDialog() {
        QService s = sInst;
        if (s != null) Overlay.get(s).hide();
    }

    /** 关闭弹窗后放回来（用户主动隐藏过的不动）。 */
    public static void resumeOverlayAfterDialog() {
        QService s = sInst;
        if (s != null && !Prefs.overlayHidden(s)) Overlay.get(s).show();
    }

    /** 自动开关变化时刷新悬浮窗按钮状态。 */
    public static void refreshOverlay() {
        QService s = sInst;
        if (s != null) Overlay.get(s).refreshButton();
    }

    /** 悬浮窗「识别」按钮：手动触发一次扫描，不受「自动解题」开关限制。 */
    public static void forceScan() {
        final QService s = sInst;
        if (s == null) return;
        s.h.post(new Runnable() {
            public void run() {
                s.lastSig = "";        // 清掉判重，同一题也能重算
                s.lastAnswerAt = 0;    // 跳过冷却
                s.h.removeCallbacks(s.fire);
                s.tick();
            }
        });
    }

    private void tick() {
        boolean cloudOk = Prefs.cloudOn(this) && Prefs.cloudReady(this, Prefs.provider(this));
        boolean localOk = Prefs.localOn(this) && AnswerEngine.get().ready();
        if (!cloudOk && !localOk) {
            if (!"__not_ready__".equals(lastSig)) {
                lastSig = "__not_ready__";
                Overlay.get(this).update("未就绪", "回 App 配 Key 或载入本地模型");
            }
            return;
        }

        ScreenScan.Result r;
        try {
            r = ScreenScan.scan(this);
        } catch (Throwable t) {
            Log.e(TAG, "扫描失败", t);
            return;
        }
        if (r == null || r.options.size() < 2) return;
        if (skipPkg(r.pkg)) return;

        final String q = Layout.stripDynamic(r.question == null ? "" : r.question);
        if (q.length() < 4) return;

        // 同题判重：题干和选项都没变，说明还是这道题，答案已经在屏幕上，绝不重算
        final String sig = signature(r, q);
        if (sig.equals(lastSig)) return;

        // 答案刚出来时给页面一点静默期。倒计时、进度条这类每秒变化的元素会不断产生
        // CONTENT_CHANGED 事件，不拦一下就会出现"刚显示答案立刻又开始思考"
        if (System.currentTimeMillis() - lastAnswerAt < 1200) {
            h.removeCallbacks(fire);
            h.postDelayed(fire, 600);
            return;
        }
        lastSig = sig;

        // 题库命中：答过的题直接给答案，0ms 且必然和历史一致
        final String bank = Record.bankAnswer(this, sig);

        final boolean multi = isMulti(r.texts);
        final List<String> letters = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        for (ScreenScan.Opt o : r.options) {
            if (o.letter == null || o.text == null) continue;
            letters.add(o.letter);
            lines.add(o.letter + ". " + o.text);
        }
        if (letters.size() < 2) return;

        final String[] arr = letters.toArray(new String[0]);
        Overlay.get(this).updateQuestion(q);

        if (bank != null) {
            AnswerEngine.Result hit = new AnswerEngine.Result();
            hit.letters = bank;
            hit.conf = 1f;
            hit.source = "题库";
            hit.ms = 0;
            onAnswer(r, hit, q, sig);
            return;
        }

        Overlay.get(this).update("思考中…", multi ? "多选 · 推理中" : "推理中");

        AnswerEngine.get().answerAsync(this, q, lines, arr, multi, new AnswerEngine.Cb() {
            public void onResult(AnswerEngine.Result res) {
                onAnswer(r, res, q, sig);
            }
        });
    }

    /** 把答案字母翻译回选项原文，悬浮窗直接显示「A. 导靴干磨」，不用再回去比对。 */
    private static String answerText(ScreenScan.Result r, String letters) {
        if (r == null || r.options == null || letters == null || letters.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < letters.length(); i++) {
            char c = letters.charAt(i);
            for (ScreenScan.Opt o : r.options) {
                if (o.letter == null || o.letter.isEmpty()) continue;
                if (Character.toUpperCase(o.letter.charAt(0)) != c) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(o.letter).append(". ").append(o.text == null ? "" : o.text.trim());
                break;
            }
        }
        return sb.toString();
    }

    private static String fmtMs(long ms) {
        return ms < 1000 ? ms + "ms" : String.format(java.util.Locale.US, "%.1fs", ms / 1000.0);
    }

    private void onAnswer(ScreenScan.Result r, AnswerEngine.Result res, String q, String sig) {
        lastAnswerAt = System.currentTimeMillis();
        if (res.error != null) {
            lastSig = "";                    // 失败：放开判重，冷却过后允许重试
            Overlay.get(this).update("出错", res.error);
            return;
        }
        if (res.letters == null || res.letters.isEmpty()) {
            lastSig = "";
            Overlay.get(this).update("拿不准", "未给出有效选项 · " + fmtMs(res.ms));
            return;
        }

        int pct = Math.round(res.conf * 100);
        lastLetters = res.letters;

        // 两路答案不一致 → 红色警告，且不自动点（点错比不点更亏）
        final boolean conflict = res.altLetters != null && !res.altLetters.isEmpty();
        final boolean lowConf  = !conflict && pct > 0 && pct < Prefs.thresholdPct(this);

        StringBuilder meta = new StringBuilder();
        if (res.source != null && !res.source.isEmpty()) meta.append(res.source).append(" · ");
        meta.append(fmtMs(res.coreMs > 0 ? res.coreMs : res.ms));
        if (res.cloudMs > 0) meta.append("（在线 ").append(fmtMs(res.cloudMs)).append(" 未成）");
        if (res.conf > 0f) meta.append(" · 置信 ").append(pct).append("%");
        if (conflict) meta.append(" · 两路不一致");
        else if (lowConf) meta.append(" · 建议自查");
        else if (Prefs.autoClick(this)) meta.append(" · 自动点击");

        String answerLine = "答案 " + res.letters;
        String detail = answerText(r, res.letters);
        if (conflict) {
            // 把另一路的答案和内容一起摆出来，方便人工裁决
            detail = detail + "\n" + (res.altSource == null ? "" : res.altSource + "：") + res.altLetters
                    + " " + shortText(answerText(r, res.altLetters));
        }
        Overlay.get(this).setLevel(conflict ? 2 : (lowConf ? 1 : 0));
        Overlay.get(this).update(answerLine, detail, meta.toString());

        Record.add(this, sig, q, res.letters, res.source, pct, res.coreMs > 0 ? res.coreMs : res.ms);
        if (lowConf) Record.markLow();
        vibrate();
        avoidOptions(r);

        if (!Prefs.autoClick(this)) return;
        if (conflict) return;                          // 两路打架时不点
        if (pct < Prefs.thresholdPct(this)) return;    // 低于阈值宁可不点

        h.postDelayed(new Runnable() {
            public void run() { clickLetters(r, res.letters); }
        }, 120);
    }

    /** 答案出来后轻振一下，不用一直盯着悬浮窗。 */
    private void vibrate() {
        if (!Prefs.vibrate(this)) return;
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createOneShot(40,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable ignored) {}
    }

    /** 把选项的屏幕坐标交给悬浮窗，让它自己挪到不挡选项的地方。 */
    private void avoidOptions(ScreenScan.Result r) {
        try {
            if (r == null || r.options == null || r.options.isEmpty()) return;
            java.util.List<Integer> box = new ArrayList<>();
            for (ScreenScan.Opt o : r.options) {
                if (o.node == null) continue;
                android.graphics.Rect b = new android.graphics.Rect();
                o.node.getBoundsInScreen(b);
                if (b.width() <= 0 || b.height() <= 0) continue;
                box.add(b.left); box.add(b.top); box.add(b.right); box.add(b.bottom);
            }
            if (box.size() < 4) return;
            int[] rects = new int[box.size()];
            for (int i = 0; i < rects.length; i++) rects[i] = box.get(i);
            Overlay.get(this).avoid(rects);
        } catch (Throwable ignored) {}
    }

    private static String shortText(String s) {
        if (s == null) return "";
        String t = s.replace("\n", " ").trim();
        return t.length() > 18 ? t.substring(0, 18) + "…" : t;
    }

    private void clickLetters(ScreenScan.Result r, String letters) {
        for (int i = 0; i < letters.length(); i++) {
            String want = String.valueOf(letters.charAt(i));
            for (ScreenScan.Opt o : r.options) {
                if (want.equals(o.letter)) {
                    boolean ok = ScreenScan.click(this, o.node);
                    Log.i(TAG, "点击 " + want + " -> " + ok);
                    break;
                }
            }
        }
    }

    /** 题目签名：题干 + 全部选项文字。任一项变了才算换题。 */
    private String signature(ScreenScan.Result r, String q) {
        StringBuilder sb = new StringBuilder(q);
        for (ScreenScan.Opt o : r.options) {
            sb.append('|').append(o.letter).append(':').append(o.text);
        }
        return sb.toString();
    }

    static boolean skipPkg(String pkg) {        if (pkg == null || pkg.isEmpty()) return false;
        for (String p : SKIP_PKGS) if (p.equals(pkg)) return true;
        return false;
    }


    // ---------------- 读屏诊断 ----------------

    /** 读屏自检结果，用来定位"读不到题"卡在哪一步。 */
    public static final class Diag {
        public String pkg = "";
        public java.util.List<String> texts = new java.util.ArrayList<>();
        public String question = "";
        public int options = 0;
        public java.util.List<String> optionLines = new java.util.ArrayList<>();
        public String reason = "";
    }

    /** 抓当前窗口的原始文本与解析结果，主界面点「读屏诊断」时调用。 */
    public static Diag diagnose() {
        Diag d = new Diag();
        QService svc = sInst;
        if (svc == null) { d.reason = "无障碍服务没开（先去系统设置里打开「AI 答题」）"; return d; }
        try {
            ScreenScan.Capture cap = ScreenScan.capture(svc);
            if (!cap.hasRoot) { d.reason = "拿不到窗口节点（部分 App 禁止读屏）"; return d; }
            d.pkg = cap.pkg == null ? "" : cap.pkg;
            if (cap.texts != null) d.texts.addAll(cap.texts);
            if (cap.parsed != null) {
                d.question = cap.parsed.question == null ? "" : cap.parsed.question;
                d.options = cap.parsed.options == null ? 0 : cap.parsed.options.size();
                if (cap.parsed.options != null) {
                    for (Layout.Opt o : cap.parsed.options) {
                        d.optionLines.add(o.letter + ". " + o.text);
                    }
                }
            }
            if (skipPkg(d.pkg)) {
                d.reason = "当前是排除列表里的界面（本 App / 系统界面），切到答题页再诊断";
            } else if (d.texts.isEmpty()) {
                d.reason = "窗口在，但一个文字节点都读不到（题目可能是图片或自绘 Canvas）";
            } else if (d.options < 2) {
                d.reason = "读到 " + d.texts.size() + " 段文字，但没认出选项（选项不是「A. 内容」这种格式）";
            } else if (d.question.trim().length() < 4) {
                d.reason = "选项认出来了，但题干拼不出来";
            } else {
                d.reason = "识别正常";
            }
        } catch (Throwable t) {
            d.reason = "扫描异常：" + t;
        }
        return d;
    }

    private boolean isMulti(List<String> texts) {        for (String t : texts) {
            if (t == null) continue;
            if (t.contains("多选") || t.contains("不定项") || t.contains("至少")) return true;
        }
        return false;
    }
}
