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
    private volatile boolean hurryMode = false;   // 屏幕上检测到倒计时：少问几家、快点出
    private volatile long lastNextAt = 0;         // 上次点「下一题」的时刻，防连点
    private volatile String answerPkg = "";       // 刚答题的那个 App：翻页只能在它里面翻

    /**
     * 答完一题后点「下一题」。
     *
     * <p>逻辑照搬刷题助手（固定题库版）的 phase 2，但更保守：只有**真的点过选项**才会走到这里，
     * 而且点之前重新读一次屏——点击会让无障碍节点失效，用旧节点点不到。
     */
    private final Runnable nextRun = new Runnable() {
        public void run() {
            if (!Prefs.autoNext(QService.this) || !Prefs.autoClick(QService.this)) return;
            long now = System.currentTimeMillis();
            if (now - lastNextAt < 1200) return;              // 防连点，两次翻页至少要隔这么久
            ScreenScan.Result cur;
            try {
                cur = ScreenScan.scan(QService.this);
            } catch (Throwable t) {
                return;
            }
            if (cur == null || cur.next == null) {
                Log.i(TAG, "答完了，但没找到「下一题」，不翻页");
                return;
            }
            // 只在这个 App 里翻页。答题途中切到微信、浏览器，它们大概率也有「下一步」，
            // 不拦的话会替用户点掉别人的按钮——这种错一旦发生就没法解释。
            if (answerPkg.isEmpty() || !answerPkg.equals(cur.pkg)) {
                Log.i(TAG, "已离开答题 App（" + cur.pkg + "），不翻页");
                return;
            }
            if (ScreenScan.click(QService.this, cur.next, Prefs.humanClick(QService.this))) {
                lastNextAt = now;
                Log.i(TAG, "点击下一题");
                // 有些页面翻页只改局部内容、不产生无障碍事件，主动叫醒一次识别，
                // 否则会停在上一题的答案上等用户手动操作
                h.removeCallbacks(fire);
                h.postDelayed(fire, 700);
            } else {
                Log.i(TAG, "「下一题」点击失败");
            }
        }
    };

    /** 本题读到的屏幕选项（字母 → 原文，按屏幕顺序）。onAnswer 仲裁记忆库时要用。 */
    private java.util.Map<String, String> lastScreenOpts = new java.util.LinkedHashMap<>();
    /** 手动点「识别」时置 true：题库命中也强制跑一次模型，用来复核题库答案。 */
    private volatile boolean verifyBank = false;

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
                s.verifyBank = true;   // 题库命中也走一遍模型，用来复核
                s.h.removeCallbacks(s.fire);
                s.tick();
            }
        });
    }

    private void tick() {
        // 手动复核标记只对这一次扫描有效，先取走再清零（避免扫描失败时留到下一题）
        final boolean verify = verifyBank;
        verifyBank = false;

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

        // 题库命中：答过的题直接给答案，0ms 且必然和历史一致。
        // 但「有分歧」的题不静默命中——那正是需要重新问一遍、让人裁决的时候。
        final Record.Bank bankEntry = Record.bankGet(this, sig);

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
        // 屏幕选项：字母 → 原文，按屏幕顺序。记忆库靠它把「旧字母」翻译成「这次的字母」。
        final java.util.LinkedHashMap<String, String> screenOpts = new java.util.LinkedHashMap<>();
        final List<String> screenTexts = new ArrayList<>();
        for (ScreenScan.Opt o : r.options) {
            if (o.letter == null || o.text == null) continue;
            screenOpts.put(o.letter, o.text);
            screenTexts.add(o.text);
        }
        lastScreenOpts = screenOpts;
        answerPkg = r.pkg == null ? "" : r.pkg;
        Overlay.get(this).updateQuestion(q);
        // 先避让再显示「思考中…」：以前只在出答案后才挪窗，思考中那几秒照样压着题干。
        // 现在读屏一成功就把窗口摆到不挡题干/选项的位置，答案回来时它已经站好了。
        avoidOptions(r);
        hurryMode = r.hasTimer;

        // ① 自选题库：命中且能在屏幕上定位 → 0 次调用、毫秒级出答案。
        //    命中了但定位不到（选项文字对不上）→ **不占用结果位**，继续降级问 AI，
        //    题库答案只作对照显示。否则「答案落不了地」还挡着能用的结果。
        //    手动「识别」不查题库——那是用户主动要求复核，应该直接问模型。
        final SelfBankMatch.Hit[] selfMiss = new SelfBankMatch.Hit[1];
        if (!verify && Prefs.selfBankFirst(this) && SelfBankStore.hasBank(this)) {
            final List<String> optTexts = new ArrayList<>();
            for (ScreenScan.Opt o : r.options) if (o.text != null) optTexts.add(o.text);
            SelfBankMatch.Hit sh = SelfBank.find(this, SelfBank.stemsOf(q), optTexts, arr);
            if (sh != null && sh.mapped) {
                AnswerEngine.Result hit = new AnswerEngine.Result();
                hit.letters = sh.letters;
                hit.conf = 1f;
                hit.source = sh.source;
                hit.answeredBy = "自选题库 回答";
                hit.ms = 0;
                hit.bankExplain = sh.explain;      // 题库自带解析，别再花钱让模型写一遍

                // 记忆库里也有这道题？两个来源对一下。
                // 题库是用户导入的权威数据，仍然优先；但「以前答的是另一个」这件事必须说出来——
                // 可能是题库导错了，也可能是上次模型错了，两种都该让用户看见。
                if (bankEntry != null && bankEntry.verified && !bankEntry.opts.isEmpty()) {
                    AnsResolver.Out mem = AnsResolver.resolve(bankEntry.opts, bankEntry.a, screenTexts);
                    if (mem.mapped && !AnsResolver.sameSet(mem.letters, sh.letters)) {
                        hit.altLetters = mem.letters;
                        hit.altSource = "记忆库";
                        hit.conf = 0.45f;          // 低于阈值，不自动点
                        hit.warnLevel = 2;         // 红框
                        Log.i(TAG, "题库与记忆库不一致：题库 " + sh.letters + " vs 记忆库 " + mem.letters);
                    }
                }
                onAnswer(r, hit, q, sig, true, null);
                return;
            }
            if (sh != null) selfMiss[0] = sh;
        }

        // 记忆库只有在「审核通过且无分歧」时才允许 0ms 直出。
        // 否则同一道题第二次出现会直接复读上一次的答案——上次要是错的，就错得很稳定。
        //
        // 命中不是照抄字母：记忆里存的是「答案选项的原文」，要先在**当前屏幕**上把它找出来，
        // 找到哪个字母就点哪个。选项顺序被打乱时，这一步就是唯一能防点错的东西。
        final String[] bankMissNote = new String[1];   // 内部类要用，包一层
        if (bankEntry != null && !verify && !bankEntry.hasConflict() && bankEntry.verified) {
            AnsResolver.Out m = AnsResolver.resolve(bankEntry.opts, bankEntry.a, screenTexts);
            // 旧记录（没有选项）不算「对不上」，别记它的账
            if (!m.noOpts) Record.bankNoteResolve(this, bankEntry, m.mapped);
            Log.i(TAG, "记忆库命中 " + bankEntry.a + " → " + m.letters
                    + " mapped=" + m.mapped + " moved=" + m.orderChanged
                    + (m.failReason == null ? "" : " 原因=" + m.failReason));

            // 只有「靠选项内容定位成功」才允许 0ms 直出。
            // 旧记录（没有选项内容）也走 AI：它的答案本身就没被选项校验过，
            // 顺序一变必错；当场复核一次就能把选项补全，之后永久按内容定位。
            if (m.mapped) {
                AnswerEngine.Result hit = new AnswerEngine.Result();
                hit.letters = m.letters;
                hit.conf = m.conf;
                hit.source = "题库";
                hit.answeredBy = m.noOpts ? "记忆库 回答（旧记录，只有字母）"
                        : (m.orderChanged ? "记忆库 回答（选项顺序变了，已按内容定位）"
                                          : "记忆库 回答（已确认）");
                if (m.note != null) hit.voteNote = m.note;
                hit.ms = 0;
                onAnswer(r, hit, q, sig, true);
                return;
            }
            // 有选项内容却对不上：不占用结果位，交给 AI 复核，把记忆里的答案作对照显示
            bankMissNote[0] = "记忆库：" + bankEntry.a
                    + (bankEntry.opts.isEmpty() ? "（旧记录没有选项内容，本次重新核对）"
                                                : "（选项对不上，本次未采用）");
        }

        if (bankEntry != null && bankEntry.hasConflict()) {
            Overlay.get(this).update("复核中…", "记忆库有分歧，重新问一遍模型");
        } else if (bankEntry != null && !bankEntry.verified) {
            Overlay.get(this).update("复核中…", "这道题以前答过，再核一遍才敢用");
        } else if (verify) {
            Overlay.get(this).update("复核中…", "手动识别 · 重新推理");
        } else {
            Overlay.get(this).update("思考中…", multi ? "多选 · 推理中" : "推理中");
        }

        AnswerEngine.get().answerAsync(this, q, lines, arr, multi, false, hurryMode, new AnswerEngine.Cb() {
            public void onResult(AnswerEngine.Result res) {
                if (res.error != null && res.letters.isEmpty() && selfMiss[0] != null) {
                    // AI 也没给出结果：退回题库答案，明确标注未定位（低置信、不自动点）
                    AnswerEngine.Result hit = new AnswerEngine.Result();
                    hit.letters = selfMiss[0].letters;
                    hit.conf = 0.4f;
                    hit.source = selfMiss[0].source;
                    onAnswer(r, hit, q, sig, true, null);
                    return;
                }
                String note = selfMiss[0] != null
                        ? "题库：" + selfMiss[0].bankLetters + "（未定位到屏幕）" : null;
                if (note == null) note = bankMissNote[0];
                else if (bankMissNote[0] != null) note = note + " · " + bankMissNote[0];
                onAnswer(r, res, q, sig, false, note);
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

    /**
     * @param fromBank true = 答案直接来自题库（没有跑模型），不必再写库/记冲突
     */
    private void onAnswer(ScreenScan.Result r, AnswerEngine.Result res, String q, String sig,
                          boolean fromBank) {
        onAnswer(r, res, q, sig, fromBank, (String) null);
    }

    /**
     * @param contrastText 题库命中但没定位到屏幕时的对照说明（可空），如
     *                     「题库：B（未定位到屏幕）」。放在 detail 行里显示，
     *                     不用 updateWhy —— 解析回调会把 why 覆盖掉。
     */
    private void onAnswer(ScreenScan.Result r, AnswerEngine.Result res, String q, String sig,
                          boolean fromBank, String contrastText) {
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
        String letters = res.letters;
        String other = res.altLetters;
        String otherSrc = res.altSource;
        // 两路答案不一致 → 红色警告，且不自动点（点错比不点更亏）
        boolean conflict = res.altLetters != null && !res.altLetters.isEmpty();
        String conflictNote = null;

        // ---- 题库仲裁：模型答案与库里的不一样时，只记录、不改写 ----
        if (!fromBank) {
            Record.Hit arb = Record.bankAuto(this, sig, q, letters, lastScreenOpts, res.source, pct);
            if (arb != null) {
                if (arb.conflict) {
                    letters = arb.letters;            // 主答案仍以题库为准
                    other = arb.other;
                    otherSrc = arb.otherSrc;
                    conflict = true;
                    conflictNote = "这次模型答 " + arb.other + "，和记忆库不一致，没敢用";
                    Record.markConflict();
                } else {
                    letters = arb.letters;
                }
            }
        }
        if (conflict && conflictNote == null) conflictNote = "两路不一致";

        lastLetters = letters;
        boolean lowConf = !conflict && pct > 0 && pct < Prefs.thresholdPct(this);

        // 元信息说人话：第一行「谁回答的 + 多久 + 多确定」，第二行「为什么 / 出什么问题」。
        String who = res.answeredBy != null ? res.answeredBy
                : (res.source == null || res.source.isEmpty() ? "来源未知" : res.source);
        StringBuilder meta = new StringBuilder(who);
        meta.append(" · ").append(fmtMs(res.coreMs > 0 ? res.coreMs : res.ms));
        if (pct > 0 && pct < 100) meta.append(" · 把握 ").append(pct).append("%");
        if (conflict) {
            if (conflictNote != null) meta.append("\n").append(conflictNote);
            else meta.append("\n").append(otherSrc == null ? "两个来源" : otherSrc)
                    .append("给了不同答案，没敢用");
        }
        else if (lowConf) meta.append(" · 没把握，没敢点");
        else if (Prefs.autoClick(this)) meta.append(" · 已自动点");

        StringBuilder why = new StringBuilder();
        if (hurryMode) why.append("抢答模式（检测到计时器）");
        if (res.voteNote != null && !res.voteNote.isEmpty()) {
            if (why.length() > 0) why.append(" · ");
            why.append(res.voteNote);
        }
        if (res.route != null && !res.route.isEmpty()) {
            if (why.length() > 0) why.append(" · ");
            why.append(res.route);
        }
        if (why.length() > 0) meta.append("\n").append(why);

        String answerLine = "答案 " + letters;
        String detail = answerText(r, letters);
        if (contrastText != null && !contrastText.isEmpty()) {
            detail = (detail == null ? "" : detail) + "\n" + contrastText;
        }
        if (conflict && other != null && !other.isEmpty()) {
            // 把另一路的答案和内容一起摆出来，方便人工裁决
            detail = detail + "\n" + (otherSrc == null ? "" : otherSrc + "：") + other
                    + " " + shortText(answerText(r, other));
        }
        // 外框颜色取三者最高：分歧=红，低置信/只剩一家/降级=黄。
        // 「只剩一家」以前只在 meta 小字里，扫一眼看不出来，现在边框就变黄。
        int level = conflict ? 2 : (lowConf ? 1 : 0);
        if (res.warnLevel > level) level = res.warnLevel;
        Overlay.get(this).setLevel(level);
        Overlay.get(this).update(answerLine, detail, meta.toString());

        Record.add(this, sig, q, letters, res.source, pct, res.coreMs > 0 ? res.coreMs : res.ms);
        // 走了模型才叫「未命中」：题库/记忆库命中的题已经在库里了，不用再记一遍
        if (!fromBank) {
            java.util.List<String> optTexts = new ArrayList<>();
            for (ScreenScan.Opt o : r.options) if (o.text != null) optTexts.add(o.letter + ". " + o.text);
            MissLog.add(this, q, optTexts, letters);
        }
        if (lowConf) Record.markLow();
        vibrate();
        avoidOptions(r);

        // 解析：
        // ① 题库自带的 → 直接显示（0 成本，还是用户自己的数据）
        // ② 只有 AI 出的答案才值得再花一次调用去生成
        if (res.bankExplain != null && !res.bankExplain.isEmpty()) {
            Overlay.get(this).updateWhy(res.bankExplain);
        } else if (!fromBank) {
            // 晚一步补上：先把答案给了，别让用户盯着"思考中"等多一次推理
            maybeExplain(r, q, letters, sig);
        }

        if (!Prefs.autoClick(this)) return;
        if (conflict) return;                          // 两路打架时不点
        // 置信低于阈值就不点。自选题库没定位到屏幕时 conf=0.4，会在这里被挡住
        if (pct < Prefs.thresholdPct(this)) return;
        final String toClick = letters;
        // 刚翻页进来的题要等页面稳：上一题的节点在切换瞬间会失效，
        // 立刻点会「点空」——界面上显示已答，实际选项没选上（实测踩过）。
        // 刷题助手靠「每拍重新读屏 + 失败重试」解决，这里用更省事的办法：等久一点。
        boolean justFlipped = System.currentTimeMillis() - lastNextAt < 2500;
        long clickDelay = human() ? 160 + new java.util.Random().nextInt(180) : 120;
        if (justFlipped) clickDelay = 700;
        h.postDelayed(new Runnable() {
            public void run() { clickLetters(r, toClick); }
        }, clickDelay);
    }

    private boolean human() {
        return Prefs.autoClick(this) && Prefs.humanClick(this);
    }

    /** 解析开关打开时异步生成；题目已经切走就丢弃结果，别贴到下一题上。 */
    private void maybeExplain(ScreenScan.Result r, String q, String letters, final String sig) {
        if (!Prefs.explainOn(this) || letters == null || letters.isEmpty()) return;
        if (!Prefs.cloudReady(this, Prefs.provider(this)) && !AnswerEngine.get().ready()) return;
        final List<String> optLines = new ArrayList<>();
        if (r != null && r.options != null) {
            for (ScreenScan.Opt o : r.options) {
                if (o.letter == null || o.text == null) continue;
                optLines.add(o.letter + ". " + o.text);
            }
        }
        AnswerEngine.get().explainAsync(this, q, optLines, letters, new AnswerEngine.ExplainCb() {
            public void onExplain(String text) {
                if (!sig.equals(lastSig)) return;      // 已经换题了，丢弃
                Overlay.get(QService.this).updateWhy(text);
            }
        });
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
            // 题干优先：窗口要连题干一起避，否则会盖住题目第一行
            if (r.questionRect != null && r.questionRect.length == 4) {
                box.add(r.questionRect[0]); box.add(r.questionRect[1]);
                box.add(r.questionRect[2]); box.add(r.questionRect[3]);
            }
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

    /**
     * 点选项。拟人模式下每个点击间隔 120~260ms、按压 90~180ms、落点偏 ±3dp，
     * 多选时逐个排开；普通模式一路直点。
     */
    private void clickLetters(final ScreenScan.Result r, final String letters) {
        final boolean human = Prefs.humanClick(this);
        // 点击期间让悬浮窗透传手势：它压着最上面的选项时，手会点在窗口上而不是选项上
        Overlay.get(this).setPassThrough(true);
        for (int i = 0; i < letters.length(); i++) {
            final String want = String.valueOf(letters.charAt(i));
            long delay = human ? (i * (120L + new java.util.Random().nextInt(140))) : 0;
            Runnable task = new Runnable() {
                public void run() {
                    for (ScreenScan.Opt o : r.options) {
                        if (want.equals(o.letter)) {
                            boolean ok = ScreenScan.click(QService.this, o.node, human);
                            Log.i(TAG, "点击 " + want + " -> " + ok + (human ? "（拟人）" : ""));
                            break;
                        }
                    }
                }
            };
            if (delay > 0) h.postDelayed(task, delay);
            else task.run();
        }
        // 等最后一个字母点完再翻页。多选拟人模式下每个字母间隔最多 260ms，
        // 按最后一个的延迟推算，早了会在选项还没点上时就翻过去。
        long last = human ? Math.max(0, (letters.length() - 1) * 260L) : 0;
        // 点完恢复可触摸：不然用户点不到「识别」按钮
        h.postDelayed(new Runnable() {
            public void run() { Overlay.get(QService.this).setPassThrough(false); }
        }, last + 500);
        if (Prefs.autoNext(this)) h.postDelayed(nextRun, last + 700);
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
