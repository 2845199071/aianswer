package com.dsh.aianswer;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
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
    private long lastFailAt = 0;                   // 失败提示/快照的节流
    private String lastFailKey = "";
    private volatile boolean ocrBusy = false;      // 图片识别进行中，防止叠加重入
    private long lastOcrAt = 0;                    // 两次图片识别的最小间隔
    private volatile String lastOcrOpts = "";      // 上一轮图片识别处理过的选项（归一化），用于同题判重
    private volatile String lastOcrQ = "";         // 上一轮的题干（归一化）
    private String ocrPkg = "";                    // 截图时的页面包名
    private String ocrHintedPkg = "";              // 已经提示过「自动切图片识别」的包名
    private boolean ocrVerify = false;
    private volatile boolean hurryMode = false;   // 屏幕上检测到倒计时：少问几家、快点出
    /** 悬浮窗点一下 = 临时暂停/恢复自动模式。只拦「自动动作」，开关本身不变，手动点「识别」仍然可用。 */
    private static volatile boolean autoPaused = false;
    private volatile boolean visionBusy = false;  // 视觉（截图直发模型）进行中
    private long lastVisionAt = 0;                // 两次视觉调用的最小间隔
    private volatile String lastVisionQ = "";     // 上一轮视觉答过的题（归一化），用于同题判重
    private String visionPkg = "";                // 视觉截图时的页面包名
    private String visionHintedPkg = "";          // 已经提示过「改成看图答题」的包名
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
            // 自动翻页是「自动解题」的配套动作：暂停中、或手动模式下都不翻。
            // 手动模式点一次「识别」= 只答眼前这一题，翻页留给用户自己。
            if (autoPaused || !Prefs.autoAnswer(QService.this)) return;
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
                // 有些页面翻页只改局部内容、不产生无障碍事件，主动叫醒一次识别。
                // 但「叫醒后解下一题」属于自动解题 —— 手动模式下翻页只是把页面翻过去，
                // 下一题留给用户自己决定。之前这里没检查开关，手动点一次就一路自动做下去。
                h.removeCallbacks(fire);
                if (Prefs.autoAnswer(QService.this)) {
                    h.postDelayed(fire, 700);
                }
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
                s.lastOcrAt = 0;       // 手动识别不受图片识别的降频限制
                s.lastOcrOpts = "";   // 同一题也允许重新识别（复核）
                s.lastOcrQ = "";
                s.lastVisionQ = "";   // 视觉路线同样
                s.lastVisionAt = 0;
                s.verifyBank = true;   // 题库命中也走一遍模型，用来复核
                s.h.removeCallbacks(s.fire);
                s.tick();
            }
        });
    }

    /**
     * 读屏没过时把原因写在悬浮窗上，并导出一份原始节点快照。
     * 以前这些分支都是静默 return，界面永远停在「识别中…」，看起来像卡死。
     * 快照写入有 2.5 秒节流，避免倒计时页面每拍都写文件。
     */
    private void fail(String title, String detail) {
        Overlay.get(this).update(title, "读屏未通过 · " + detail);
        long now = System.currentTimeMillis();
        String key = title + "/" + detail;
        if (now - lastFailAt < 2500 && key.equals(lastFailKey)) return;
        lastFailAt = now;
        lastFailKey = key;
        try {
            DiagDump.write(this, ScreenScan.capture(this), key);
        } catch (Throwable ignored) { }
    }

    /**
     * 页面像不像答题页。
     *
     * <p>「读屏认不出选项」不等于「这是答题页」——微信聊天、桌面、设置页同样认不出。
     * 不先判一下的话，用户随便刷别的页面也会被截图发到云端，既费额度又泄露隐私。
     * 特征词命中任一，或者页面上有 3 个以上独立字母节点，才认为是题目页。
     */
    private static boolean looksLikeQuiz(ScreenScan.Result r) {
        if (r == null || r.texts == null || r.texts.size() < 3) return false;
        int loneLetters = 0;
        for (String t : r.texts) {
            if (t == null) continue;
            String s = t.trim();
            if (s.isEmpty()) continue;
            if (s.length() == 1 && "ABCDEF".indexOf(Character.toUpperCase(s.charAt(0))) >= 0) {
                loneLetters++;
                continue;
            }
            if (s.contains("答题") || s.contains("背题") || s.contains("提交答案")
                    || s.contains("下一题") || s.contains("单选") || s.contains("多选")
                    || s.contains("判断") || s.contains("选择题") || s.contains("交卷")
                    || s.contains("正确") || s.contains("错误")) {
                return true;
            }
        }
        return loneLetters >= 3;
    }

    /**
     * 读屏一个选项都认不出来时的图片识别兜底（自动生效，不需要开关）。
     *
     * @return true = 已经接手（异步进行中），调用方不要再去报「没认出选项」
     */
    /**
     * 读屏读不到文字时的**默认**兜底：截图直接交给视觉模型读题答题（不走 OCR）。
     *
     * <p>小程序 / Canvas 自绘页面的无障碍树里根本没有题目文字，OCR 再怎么调也只能
     * 把图片认成字、再猜错几个；视觉模型是端到端理解图像，题干、选项、答案一次给全，
     * 而且不用先转成文字再让文本模型猜一遍，链路上少一次损耗。
     *
     * @return true = 已经接手（异步进行中），调用方不要再去报「没认出选项」
     */
    private boolean tryVision(boolean verify, ScreenScan.Result r) {
        if (!Prefs.cloudOn(this)) return false;              // 在线关着，不偷偷调云端
        if (!VisionSolver.available(this)) return false;     // 没配视觉模型 → 交给 OCR 兜底
        // 不像答题页就不截（防止在聊天页/桌面乱上传）；但用户手动点「识别」时例外 ——
        // 读屏完全读不到内容时，用户点这一下就是明确要求「看图答题」。
        if (!looksLikeQuiz(r) && !verify) return false;

        long now = System.currentTimeMillis();
        if (visionBusy) return true;
        // 刚在这一页答完题：画面多半已经变成结果页，别为同一道题再花一次调用
        if (r != null && answerPkg.equals(r.pkg) && now - lastAnswerAt < 12000) return true;
        if (now - lastVisionAt < 6000) return true;
        lastVisionAt = now;
        visionBusy = true;
        final String pkg = r == null || r.pkg == null ? "" : r.pkg;
        visionPkg = pkg;

        // 每个 App 只解释一次「为什么突然开始看图」，之后安静工作
        if (!pkg.equals(visionHintedPkg)) {
            visionHintedPkg = pkg;
            Overlay.get(this).update("看图答题中…", "读屏读不到题目 · 改成截图交给模型");
        } else {
            Overlay.get(this).update("看图答题中…", "");
        }

        // 截图前把自己藏起来：悬浮窗会被一起拍进画面，模型会把「识别中…」当成题目内容
        Overlay.get(this).setInvisible(true);
        h.postDelayed(() -> captureForVision(pkg, verify), 80);
        return true;
    }

    /** 悬浮窗隐藏后稍等一帧再截，避免拍到还没重绘的残影。 */
    private void captureForVision(final String pkg, final boolean verify) {
        OcrEngine.captureAsync(this, new OcrEngine.Callback() {
            public void onBitmap(Bitmap bmp, String err) {
                Overlay.get(QService.this).setInvisible(false);
                if (bmp == null) {
                    visionBusy = false;
                    fail("截图失败", err == null ? "未知原因" : err);
                    return;
                }
                dumpScreen(bmp);          // 调试用：原屏留一份，好和裁剪后的图对比
                final Bitmap b = bmp;
                new Thread(new Runnable() {
                    public void run() {
                        // 只读题、不判断答案：答案交给题库和文本模型（视觉看着图答更容易错）
                        final VisionSolver.Result vr = VisionSolver.read(QService.this, b, pkg);
                        try { b.recycle(); } catch (Throwable ignored) { }   // 这一帧不落盘，用完即弃
                        h.post(new Runnable() {
                            public void run() { onVisionAuto(vr, pkg, verify); }
                        });
                    }
                }, "vision").start();
            }
        });
    }

    /** 调试用：把这一帧原屏存一份（覆盖写、长边压到 1000px），用来核对裁剪区域对不对。 */
    private void dumpScreen(Bitmap bmp) {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) return;
            int w = bmp.getWidth(), h = bmp.getHeight();
            float sc = Math.min(1f, 1000f / Math.max(w, h));
            Bitmap s = sc < 1f
                    ? Bitmap.createScaledBitmap(bmp, Math.round(w * sc), Math.round(h * sc), true)
                    : bmp;
            java.io.FileOutputStream fo =
                    new java.io.FileOutputStream(new java.io.File(dir, "screen_last.jpg"));
            s.compress(Bitmap.CompressFormat.JPEG, 60, fo);
            fo.close();
            if (s != bmp) s.recycle();
        } catch (Throwable ignored) { }
    }

    /**
     * 自动路径的视觉结果落地：转成和读屏完全一样的两份结构，交给 {@link #onAnswer}，
     * 于是题库写回、自动点击、翻页、悬浮窗显示全部复用同一套逻辑。
     */
    private void onVisionAuto(VisionSolver.Result vr, String pkg, boolean verify) {
        visionBusy = false;
        if (vr == null) { fail("看图读题失败", "没有返回内容"); return; }
        if (vr.error != null) { fail("看图读题失败", vr.error); return; }

        final String q = Layout.stripDynamic(vr.question == null ? "" : vr.question);
        if (q.length() < 4) {
            fail("看图没读出题干", "模型返回内容里没有题目");
            return;
        }

        // 同题判重：结果页里题目还在，不拦一下会每几秒重读一遍
        String vq = Matcher.norm(q);
        if (vq.length() >= 4 && OcrText.similar(vq, lastVisionQ) >= 0.90f) {
            Log.i(TAG, "视觉：同一题已经读过，跳过本轮");
            lastVisionAt = System.currentTimeMillis() + 15000;
            return;
        }
        lastVisionQ = vq;

        ScreenScan.Result r = new ScreenScan.Result();
        r.pkg = pkg;
        r.question = q;
        for (VisionSolver.Option o : vr.options) {
            ScreenScan.Opt so = new ScreenScan.Opt();
            so.letter = o.letter;
            so.text = o.text;
            so.box = o.box;              // 有坐标就能按坐标点，没有就只显示答案
            r.options.add(so);
            r.texts.add(o.letter + ". " + o.text);
        }
        if (r.options.size() < 2) {
            fail("看图没认出选项", "只认出 " + r.options.size() + " 个，至少需要 2 个");
            return;
        }
        // 题型标记：isMulti() 靠 texts 里的「多选/不定项/至少」判断，缺了它多选题会被当单选，
        // 文本模型就只给一个字母（实测多选题全中招）。
        String tp = vr.type == null ? "" : vr.type;
        if (tp.contains("多选") || tp.contains("不定项") || tp.contains("至少")) {
            r.texts.add("多选");
        }
        Log.i(TAG, "视觉读题 " + r.options.size() + " 个选项 / 题型 " + (tp.isEmpty() ? "未知" : tp)
                + " / " + vr.ms + "ms → 交给题库和模型判定");
        // 走和读屏完全一样的判定链：自选题库 → 记忆库 → 文本模型
        handle(r, verify);
    }

    private boolean tryOcr(boolean verify, ScreenScan.Result r) {
        if (!OcrEngine.supported()) return false;
        if (!looksLikeQuiz(r)) return false;                  // 不像答题页，绝不截图
        long now = System.currentTimeMillis();
        if (ocrBusy) return true;
        // 刚在同一个页面答完题，接下来几秒页面通常只是变成「结果页」——
        // 看着像同一道题、内容又已经变了，最容易被当成新题重复答题、重复点击。
        if (r != null && answerPkg.equals(r.pkg) && now - lastAnswerAt < 10000) return true;
        // 同一个页面会持续产生 CONTENT_CHANGED 事件，不拦一下会每来一次就截一次图
        if (now - lastOcrAt < 5000) return true;
        lastOcrAt = now;
        ocrBusy = true;
        ocrPkg = r.pkg == null ? "" : r.pkg;
        ocrVerify = verify;

        // 每个 App 只解释一次「为什么突然在截图」，之后安静工作，不再打扰
        if (!ocrPkg.equals(ocrHintedPkg)) {
            ocrHintedPkg = ocrPkg;
            Overlay.get(this).update("已切图片识别",
                    "读屏读不到题目 · 改成截图认字，稍等");
        } else {
            Overlay.get(this).update("图片识别中…", "正在截屏认字");
        }
        OcrEngine.captureAsync(this, new OcrEngine.Callback() {
            public void onBitmap(Bitmap bmp, String err) {
                if (bmp == null) {
                    ocrBusy = false;
                    fail("截图失败", err == null ? "未知原因" : err);
                    return;
                }
                final Bitmap b = bmp;
                new Thread(new Runnable() {
                    public void run() {
                        OcrEngine.Hit r;
                        try {
                            r = OcrEngine.recognize(QService.this, b);
                        } finally {
                            // 识别完立刻释放这一帧（整屏 ARGB_8888 约 14MB），不进堆、不落盘
                            try { b.recycle(); } catch (Throwable ignored) { }
                        }
                        final OcrEngine.Hit hit = r;
                        h.post(new Runnable() {
                            public void run() {
                                ocrBusy = false;
                                applyOcr(hit);
                            }
                        });
                    }
                }, "ocr").start();
            }
        });
        return true;
    }

    /**
     * 把本地识别出来的文字交给**与读屏完全相同**的版面解析，再走同一条解题链。
     * 这样图片识别不是另起一套，题库、记忆库、题型路由、投票、自动点击全都自动生效。
     */
    private void applyOcr(OcrEngine.Hit hit) {
        if (hit == null) {
            DiagDump.writeOcr(this, null, null, "没有返回内容");
            fail("图片识别失败", "没有返回内容");
            return;
        }
        if (hit.lines.size() < 2) {
            DiagDump.writeOcr(this, hit, null, "识别行数为 " + hit.lines.size());
            fail("图片识别失败", hit.error == null ? "截图里没识别到文字" : hit.error);
            return;
        }

        // 先过一遍 OCR 专用的行整理：去悬浮窗/状态栏/题型标签、把拆开的字母和文字合起来
        java.util.List<OcrEngine.Line> lines = OcrText.normalize(hit.lines);
        if (lines.size() < 2) {
            DiagDump.writeOcr(this, hit, null, "清洗后只剩 " + lines.size() + " 行");
            fail("图片识别失败", "截图里可用文字太少");
            return;
        }
        java.util.List<String> texts = new ArrayList<>();
        java.util.List<int[]> pos = new ArrayList<>();
        for (OcrEngine.Line l : lines) {
            texts.add(l.text);
            pos.add(new int[]{l.top, l.left});
        }
        Layout.Parsed p = Layout.parse(texts, pos);

        // 几何阈值全部按屏幕比例算，换设备（720p / 2K / 平板）不会失准
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        OcrText.Metrics metrics = new OcrText.Metrics(dm.widthPixels, dm.heightPixels);

        // 几何校验：按「字母递增 + 纵向等距 + 左边界对齐」找真正的选项列。
        // 广告文案天天变，但它改不了「跟选项不在同一列」这件事。
        OcrText.Block blk = OcrText.findOptionBlock(lines, metrics);
        boolean useGeo = blk != null && blk.idx.size() >= 2;
        int optCount = useGeo ? blk.idx.size() : (p.options == null ? 0 : p.options.size());
        int firstOpt = useGeo ? blk.idx.get(0)
                : (p.options == null || p.options.isEmpty() ? -1 : p.options.get(0).index);

        if (optCount < 2) {
            DiagDump.writeOcr(this, hit, p, "没凑出选项（清洗后 " + lines.size()
                    + " 行 · 几何 " + (blk == null ? 0 : blk.idx.size())
                    + " · 解析 " + (p.options == null ? 0 : p.options.size()) + "）");
            fail("图片识别无选项", "认到 " + lines.size() + " 行文字，但没凑出 A/B/C 选项");
            return;
        }

        // 题干取「选项列正上方」那些行；几何定位比纯解析可靠，太短再用解析结果兜底。
        String q = OcrText.extractQuestion(lines, firstOpt, metrics);
        if (q.length() < 4) q = p.question == null ? "" : p.question.trim();
        p.question = q;
        DiagDump.writeOcr(this, hit, p, "题干长度 " + q.length()
                + " · 选项来源 " + (useGeo ? "几何" : "解析") + " · " + optCount + " 个");

        ScreenScan.Result r = new ScreenScan.Result();
        r.pkg = ocrPkg;
        r.question = p.question;
        // 题型标签（`/多选`）在清洗时被剔除了，从原始行里补一个标记，
        // 否则多选题会被当成单选题，只给一个字母。
        for (OcrEngine.Line raw : hit.lines) {
            if (raw.text == null) continue;
            if (raw.text.contains("多选") || raw.text.contains("不定项")) {
                r.texts.add("多选");
                break;
            }
        }
        if (useGeo) {
            for (int k = 0; k < blk.idx.size(); k++) {
                OcrEngine.Line l = lines.get(blk.idx.get(k));
                ScreenScan.Opt o = new ScreenScan.Opt();
                o.letter = String.valueOf(blk.letters.charAt(k));
                // 符号归一：`≤` 在 ML Kit 里会被认成 K / 公 / 不 / A K 等多种样子
                o.text = OcrText.fixOptionBody(l.text);
                if (l.right > l.left && l.bottom > l.top) {
                    o.box = new int[]{l.left, l.top, l.right, l.bottom};
                }
                r.options.add(o);
                r.texts.add(o.text);
            }
        } else {
            for (Layout.Opt lo : p.options) {
                ScreenScan.Opt o = new ScreenScan.Opt();
                o.letter = lo.letter;
                o.text = OcrText.fixOptionBody(lo.text);
                if (lo.index >= 0 && lo.index < lines.size()) {
                    OcrEngine.Line l = lines.get(lo.index);
                    if (l.right > l.left && l.bottom > l.top) {
                        o.box = new int[]{l.left, l.top, l.right, l.bottom};
                    }
                }
                r.options.add(o);
                r.texts.add(lo.text == null ? "" : lo.text);
            }
        }
        // 同题判重（图片识别版）。
        //
        // 读屏一直失败时，这套 OCR 会每隔几秒重跑一次；同一道题答完后页面变成「结果页」，
        // 内容看着像、其实已经变了，会被当成新题反复答题、反复点击。
        // 两道闸：① 结果页特征（「正确答案 B」「您选择 A」）——最可靠；
        //          ② 题干 + 选项归一化后的相似度 —— 兜住结果页文字还没渲染出来的情况。
        // 手动点「识别」（ocrVerify）不判重：用户就是要重新看一遍。
        // 选项文字是这一屏最稳的东西：结果页给选项加了「✓/✗」和颜色，但文字本身不变。
        // 题干反而会被结果页的解析文字、广告挤得面目全非（实测同一题两次能差到 0.14），
        // 所以同题判定以选项为主，题干只在「两个选项」这种判断题里才要求也像。
        StringBuilder ob = new StringBuilder();
        for (ScreenScan.Opt o : r.options) ob.append('|').append(o.text == null ? "" : o.text);
        String optKey = Matcher.norm(ob.toString());
        String qKey = Matcher.norm(Layout.stripDynamic(r.question == null ? "" : r.question));
        if (!ocrVerify) {
            if (OcrText.looksAnswered(hit.lines)) {
                Log.i(TAG, "图片识别：这页已经是答案结果页，跳过");
                lastOcrAt = System.currentTimeMillis() + 15000;
                return;
            }
            float simOpt = OcrText.similar(optKey, lastOcrOpts);
            float simQ = OcrText.similar(qKey, lastOcrQ);
            boolean tiny = r.options.size() <= 2;     // 是/否、对/错：光看选项分不出来
            Log.i(TAG, "图片识别判重 选项 " + String.format(java.util.Locale.US, "%.2f", simOpt)
                    + " · 题干 " + String.format(java.util.Locale.US, "%.2f", simQ)
                    + " · " + r.options.size() + " 个选项");
            if (optKey.length() >= 2 && simOpt >= 0.85f && (!tiny || simQ >= 0.5f)) {
                Log.i(TAG, "图片识别：同一题已经处理过，跳过本轮");
                // 这道题的答案已经在屏幕上了，没必要每 5 秒再截一次图
                lastOcrAt = System.currentTimeMillis() + 15000;
                return;
            }
        }
        lastOcrOpts = optKey;
        lastOcrQ = qKey;

        Log.i(TAG, "图片识别到 " + r.options.size() + " 个选项 / " + hit.ms + "ms"
                + (useGeo ? "（几何）" : "（解析）"));

        /*
         * 本地模型可用时，先做一遍错别字校对再解题。
         *
         * OCR 的形近字误认（磨损→唐损、更换→更焕、围裙板→国裙板）几何校验和分辨率都救不了，
         * 因为它们不是漏字或错位，而是把字认成了另一个字；语言模型靠上下文能判出来。
         * 放工作线程做，避免 1~2 秒的生成卡住界面；校对不可用时原样往下走。
         */
        if (AnswerEngine.get().ready()) {
            final ScreenScan.Result rr = r;
            final java.util.List<String> optLines = new ArrayList<>();
            for (ScreenScan.Opt o : rr.options) optLines.add(o.letter + ". " + o.text);
            Overlay.get(this).update("校对中…", "本地模型修正识别错别字");
            new Thread(new Runnable() {
                public void run() {
                    final TextFix.Result fx = TextFix.fix(rr.question, optLines);
                    h.post(new Runnable() {
                        public void run() {
                            if (fx != null) {
                                rr.question = fx.question;
                                for (int i = 0; i < rr.options.size() && i < fx.options.size(); i++) {
                                    String s = fx.options.get(i);
                                    int dot = s.indexOf('.');
                                    if (dot > 0 && dot + 1 < s.length()) {
                                        rr.options.get(i).text = s.substring(dot + 1).trim();
                                    }
                                }
                            }
                            handle(rr, ocrVerify);
                        }
                    });
                }
            }, "textfix").start();
            return;
        }
        handle(r, ocrVerify);
    }

    /**
     * 悬浮窗「截屏」按钮：把当前画面交给视觉模型读题答题。
     *
     * <p>它解决 OCR 补不掉的两类错误：形近字误认（磨损→唐损）和符号误识（≤ 认成 K/公/不）。
     * 视觉模型端到端理解图像，而且用户此刻停在题目上，画面里本来就没有广告干扰。
     */
    public static void visionScan() {
        final QService s = sInst;
        if (s == null) return;
        if (!VisionSolver.available(s)) {
            Overlay.get(s).update("没配视觉 Key",
                    "设置 › 在线模型 › 给要用的那家填 Key");
            return;
        }
        Overlay.get(s).setInvisible(true);
        s.h.postDelayed(() -> OcrEngine.captureAsync(s, new OcrEngine.Callback() {
            public void onBitmap(Bitmap bmp, String err) {
                Overlay.get(s).setInvisible(false);
                if (bmp == null) {
                    Overlay.get(s).update("截图失败", err == null ? "未知原因" : err);
                    return;
                }
                final Bitmap b = bmp;
                Overlay.get(s).update("视觉读题中…", "");
                new Thread(new Runnable() {
                    public void run() {
                        final VisionSolver.Result vr = VisionSolver.ask(s, b, "");
                        try { b.recycle(); } catch (Throwable ignored) { }
                        s.h.post(new Runnable() {
                            public void run() { s.showVision(vr); }
                        });
                    }
                }, "vision").start();
            }
        }), 80);
    }

    /**
     * 打开「校准题目区域」：用户拖两条横线框住题干 + 选项，之后同一个 App 一直按这块裁剪。
     * 比自动推断可靠 —— 屏幕就那么大，题目区位置是固定的，让人拖一次最准。
     */
    /** 悬浮窗点一下：暂停/恢复自动模式。返回 true = 现在是暂停状态。 */
    public static boolean toggleAutoPause() {
        autoPaused = !autoPaused;
        Log.i(TAG, "自动模式" + (autoPaused ? "已暂停（开关不变，点「继续」恢复）" : "已恢复"));
        // 暂停时把已经在倒计时的自动动作一并取消：答案出来后排队中的「点下一题」、叫醒识别
        final QService s = sInst;
        if (s != null && autoPaused) {
            s.h.removeCallbacks(s.nextRun);
            s.h.removeCallbacks(s.fire);
        }
        return autoPaused;
    }

    public static boolean isAutoPaused() { return autoPaused; }

    /** 切换自动/手动模式时调用：暂停概念只属于自动模式，切走就清掉，免得状态残留。 */
    public static void resetAutoPause() {
        autoPaused = false;
        if (sInst != null) {
            Overlay.get(sInst).applyPauseState(false);   // 把「⏸ 已暂停」的显示也一并清掉
        }
    }

    public static void openCropPicker() {
        final QService s = sInst;
        if (s == null) return;
        String pkg = "";
        try {
            android.view.accessibility.AccessibilityNodeInfo n = s.getRootInActiveWindow();
            if (n != null && n.getPackageName() != null) {
                String p = n.getPackageName().toString();
                // 菜单刚关、校准界面还没出来，焦点可能是自己的对话框，不能把它当成答题 App
                if (!p.equals(s.getPackageName())) pkg = p;
            }
        } catch (Throwable ignored) { }
        if (pkg.isEmpty()) pkg = s.answerPkg;
        if (pkg.isEmpty()) pkg = s.visionPkg;
        Overlay.get(s).setInvisible(true);
        CropPicker.show(s, pkg, 0, 0);
    }

    private static String fmtMs2(long ms) {
        return ms < 1000 ? ms + "ms" : String.format(java.util.Locale.US, "%.1fs", ms / 1000.0);
    }

    /** 视觉模型的结果落地：答案行 + 题目 + 可选自动点击。 */
    private void showVision(VisionSolver.Result vr) {
        if (vr == null) { Overlay.get(this).update("视觉答题失败", "没有返回"); return; }
        if (vr.error != null) { Overlay.get(this).update("视觉答题失败", vr.error); return; }
        if (vr.answer == null || vr.answer.isEmpty()) {
            Overlay.get(this).update("拿不准", "模型没给出答案");
            return;
        }

        String optText = "";
        for (VisionSolver.Option o : vr.options) {
            if (o.letter != null && o.letter.equals(vr.answer)) { optText = o.text == null ? "" : o.text; break; }
        }
        String line = optText.isEmpty()
                ? ("答案 " + vr.answer)
                : ("答案 " + vr.answer + "  " + optText);

        StringBuilder meta = new StringBuilder(
                vr.source == null || vr.source.isEmpty() ? "视觉模型" : vr.source);
        meta.append(" · 视觉 · ").append(fmtMs2(vr.ms));
        if (vr.why != null && !vr.why.isEmpty()) meta.append(" · ").append(vr.why);

        Overlay.get(this).setLevel(0);
        Overlay.get(this).update(line, null, meta.toString());
        Overlay.get(this).updateQuestion(vr.question);
        lastLetters = vr.answer;
        lastAnswerAt = System.currentTimeMillis();
        Record.add(this, "vision:" + vr.question, vr.question, vr.answer, "视觉模型",
                Math.round(95 * 1f), vr.ms);
        vibrate();
    }

    /** 长按菜单「读屏诊断」：原地抓一次，显示结论并导出节点供 adb pull。 */
    public static void dumpNow() {
        final QService s = sInst;
        if (s == null) return;
        s.h.post(new Runnable() {
            public void run() {
                try {
                    ScreenScan.Capture cap = ScreenScan.capture(s);
                    Diag d = diagnose();
                    DiagDump.write(s, cap, d.reason);
                    Overlay.get(s).update(d.reason,
                            "读到 " + d.texts.size() + " 段文字 · 认出 " + d.options + " 个选项",
                            "节点快照 nodesnap.txt 已更新");
                } catch (Throwable t) {
                    Overlay.get(s).update("诊断失败", String.valueOf(t));
                }
            }
        });
    }

    private void tick() {
        // 手动复核标记只对这一次扫描有效，先取走再清零（避免扫描失败时留到下一题）
        final boolean verify = verifyBank;
        verifyBank = false;
        // 悬浮窗点一下暂停的自动模式：自动题不跑；手动点「识别」（verify）仍然作答
        if (autoPaused && !verify) return;

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
            fail("扫描异常", String.valueOf(t));
            return;
        }
        Log.i(TAG, "读屏 pkg=" + (r == null ? "null" : r.pkg)
                + " 文字=" + (r == null ? 0 : r.texts.size())
                + " 选项=" + (r == null ? 0 : r.options.size()));
        if (r == null || r.options.size() < 2) {
            int n = r == null ? 0 : r.texts.size();
            // 默认路线：截图直接交给视觉模型读题答题。
            // OCR 退成兜底 —— 只在没配视觉模型时才用。
            if (tryVision(verify, r)) return;
            if (tryOcr(verify, r)) return;
            if (n == 0) {
                fail("读屏没拿到内容", "点「识别」用看图答题");
            } else {
                fail("没认出选项", "读到 " + n + " 段文字，至少需要 2 个 A/B/C 选项");
            }
            return;
        }
        handle(r, verify);
    }

    /**
     * 真正的解题流程：手里已经有题干和选项。
     * 读屏拿到的和图片识别拿到的都从这里往下走，所以题库、记忆库、路由、投票全都照常生效。
     */
    private void handle(ScreenScan.Result r, boolean verify) {
        if (skipPkg(r.pkg)) return;   // 切到系统界面或本 App：正常场景，不打扰

        final String q = Layout.stripDynamic(r.question == null ? "" : r.question);
        if (q.length() < 4) {
            fail("题干拼不出", "选项已认出，但题干为空或不足 4 字");
            return;
        }

        // 同题判重：题干和选项都没变，说明还是这道题，答案已经在屏幕上，绝不重算
        final String sig = signature(r, q);
        if (sig.equals(lastSig)) return;

        // 答案刚出来时给页面一点静默期。倒计时、进度条这类每秒变化的元素会不断产生
        // CONTENT_CHANGED 事件，不拦一下就会出现"刚显示答案立刻又开始思考"
        if (System.currentTimeMillis() - lastAnswerAt < 1200) {
            h.removeCallbacks(fire);
            if (Prefs.autoAnswer(this)) {
                h.postDelayed(fire, 600);
            }
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
        if (letters.size() < 2) {
            fail("选项不可用", "解析出的选项字母少于 2 个");
            return;
        }

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
            Overlay.get(this).update("复核中…", "这道题还没确认过，本次仍问模型");
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
        boolean hasAlt = res.altLetters != null && !res.altLetters.isEmpty();
        // 只有分歧严重到「平票 / 三方各说一套」（warnLevel=2）才算真冲突：红框 + 不自动点。
        // 2:1 多数时少数派的答案照样显示出来，但多数票照常点 ——
        // 否则「有共识」也会被卡住不点，等于白投。
        boolean conflict = hasAlt && res.warnLevel >= 2;
        String conflictNote = null;

        // ---- 题库仲裁：模型答案与库里的不一样时 ----
        // 只有**用户确认过**的条目才可以反过来改主答案；
        // 待确认的候选（自己答过但没人审核）永远不能顶掉本次模型的答案，
        // 否则等于绕过审核直接引用，跟用户定下的规则冲突。
        boolean bankTrusted;
        Record.Bank curBank = Record.bankGet(this, sig);
        bankTrusted = curBank != null && curBank.verified && !curBank.hasConflict();
        if (!fromBank) {
            Record.Hit arb = Record.bankAuto(this, sig, q, letters, lastScreenOpts, res.source, pct);
            if (arb != null) {
                if (arb.conflict) {
                    if (bankTrusted) {
                        letters = arb.letters;            // 已确认：主答案以记忆库为准
                        other = arb.other;
                        otherSrc = arb.otherSrc;
                        conflict = true;
                        conflictNote = "这次模型答 " + arb.other + "，和记忆库不一致，没敢用";
                    } else {
                        // 未确认的候选：以本次模型答案为准，库里那条只摆出来对照
                        other = arb.letters;
                        otherSrc = "记忆库（待确认）";
                        hasAlt = true;                  // 让它跟着显示在下面
                        conflictNote = "记忆库候选 " + arb.letters + " 还没确认过，以本次答案为准";
                    }
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
            // answeredBy 已经是「投票分歧（N 家各不相同）」，再补一句「两路不一致」是重复。
            // 只有题库仲裁那种具体情况（conflictNote 有内容）才追加。
            if (conflictNote != null) meta.append("\n").append(conflictNote);
        } else {
            // 未确认的记忆库候选也走这条：先说明它没被采用，再报「点不点」
            if (conflictNote != null) meta.append("\n").append(conflictNote);
            if (lowConf) meta.append(" · 没把握，没敢点");
            else if (autoPaused) meta.append(" · 已暂停，没替你点");
            else if (Prefs.autoClick(this)) meta.append(" · 已自动点");
        }

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

        // 单选：把选项内容并进答案那一行（「答案 B  6个月」）。
        // 以前是「答案 B」下面再单独一行「B. 6个月」，字母重复，白占一行高度。
        String optText = answerText(r, letters);
        String answerLine;
        String detail = null;
        StringBuilder det = new StringBuilder();

        if (res.disputed && res.voteMap != null && !res.voteMap.isEmpty()) {
            // 真分歧：没有「答案」可给。大字直接写「有分歧」，下面把每一家的名字和
            // 它选的选项都摆出来，让用户自己判断 —— 不替用户挑一个。
            answerLine = "有分歧";
            for (java.util.Map.Entry<String, String> e : res.voteMap.entrySet()) {
                String L = e.getKey();
                String t = answerText(r, L);
                if (t != null) {
                    t = t.replace('\n', ' ')
                         .replaceFirst("^[A-Ea-e][.、．:：]\\s*", "").trim();
                }
                if (det.length() > 0) det.append('\n');
                det.append(e.getValue()).append('：').append(L);
                if (t != null && !t.isEmpty()) det.append(' ').append(shortText(t));
            }
        } else if (letters.length() == 1 && optText != null && !optText.isEmpty()) {
            String t = optText.replaceFirst("^[A-Ea-e][.、．:：]\\s*", "").trim();
            answerLine = t.isEmpty() ? ("答案 " + letters) : ("答案 " + letters + "  " + t);
        } else {
            answerLine = "答案 " + letters;
            detail = optText;
        }

        if (detail != null && !detail.isEmpty()) det.append(detail);
        if (contrastText != null && !contrastText.isEmpty()) {
            if (det.length() > 0) det.append('\n');
            det.append(contrastText);
        }
        if (!res.disputed && hasAlt && other != null && !other.isEmpty()) {
            // 另一路的答案连同内容一起摆出来，方便人工裁决（2:1 多数时也显示）。
            // 真分歧时上面已经把每一家都列了，这里不能再追加一遍。
            if (det.length() > 0) det.append('\n');
            // 「投票 · DeepSeek V4.1-Flash」太长，去掉前缀只留模型名
            String src = otherSrc == null ? "另一路" : otherSrc.replaceFirst("^投票 · ", "");
            det.append(src).append('：').append(other);
            // answerText 自带字母（「B. 4mm」），不去掉会显示成「B B. 4mm」
            String ot = answerText(r, other);
            if (ot != null) ot = ot.replaceFirst("^[A-Ea-e][.、．:：]\\s*", "").trim();
            if (ot != null && !ot.isEmpty()) det.append(' ').append(shortText(ot));
        }
        detail = det.length() == 0 ? null : det.toString();
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
        if (autoPaused) {                              // 暂停中：答案只显示，不替用户点
            Log.i(TAG, "自动模式已暂停，答案只显示不点击");
            return;
        }
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
                            // 图片识别没有节点，只能按识别出来的矩形中心发手势
                            boolean ok = o.node != null
                                    ? ScreenScan.click(QService.this, o.node, human)
                                    : ScreenScan.tapAt(QService.this, o.box, human);
                            Log.i(TAG, "点击 " + want + " -> " + ok + (human ? "（拟人）" : "")
                                    + (o.node == null ? "（图片识别·坐标）" : ""));
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
