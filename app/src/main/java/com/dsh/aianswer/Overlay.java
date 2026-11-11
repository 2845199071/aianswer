package com.dsh.aianswer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 答案悬浮窗。可拖动，位置在本次服务生命周期内保留；不抢焦点、默认不吃点击。 */
public final class Overlay {

    /* ---------------- 设计 token：与 res/values/colors.xml 完全一致 ----------------
       悬浮窗是独立 Window，拿不到 Dialog 主题，所以这里直接写同值常量。
       换肤时改 colors.xml 的同时改这几行即可。 */
    private static final int C_CARD     = 0xFFFFFFFF;   // bg_card
    private static final int C_STROKE   = 0xFFDFE5EF;   // stroke（略深一点，白卡在白底上才看得出边界）
    private static final int C_T1       = 0xFF1C2333;   // text_primary
    private static final int C_T2       = 0xFF2A3446;   // text_body
    private static final int C_SECOND   = 0xFF4A5568;   // text_secondary
    private static final int C_HINT     = 0xFF6E7A90;   // text_hint
    private static final int C_LIGHT    = 0xFFA8B0BF;   // text_light
    private static final int C_PRIMARY  = 0xFF4D7CFE;   // primary
    private static final int C_SOFT     = 0xFFEAF1FF;   // primary_soft
    private static final int C_FIELD    = 0xFFF2F4F9;   // bg_field
    private static final int C_WARN     = 0xFFF0A22E;
    private static final int C_RED      = 0xFFE5484D;

    /** 固定尺寸（dp）：内容只在这块地方里变，窗口本身不长不短。 */
    private static final int W_DP = 250;
    private static final int H_DP = 128;

    private static Overlay sInst;

    public static synchronized Overlay get(Context c) {
        if (sInst == null) sInst = new Overlay(c.getApplicationContext());
        return sInst;
    }

    private final Context ctx;
    private final WindowManager wm;
    private View root;
    private WindowManager.LayoutParams lp;
    private TextView tvAnswer;
    private TextView tvDetail;
    private TextView tvMeta;
    private TextView tvQ;
    private View dot;
    private int lastLevel = 0;         // 暂停前的告警级别，恢复时还原状态点颜色
    private String savedMeta = "";     // 暂停前的元信息，恢复时还原
    private long downAt = 0;           // 区分「点一下」和「长按/拖动」
    /** detail 区当前完整内容（多选列表 / 多路对照）。 */
    private String detailFull = "";
    /** 解析全文，点 detail 区弹窗看。 */
    private String whyFull = "";
    private TextView btnScan;
    private GradientDrawable bgBox;
    private boolean attached = false;

    // 拖动状态
    private int downRawX, downRawY, startX, startY;
    private boolean dragging = false;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable longPress = new Runnable() {
        public void run() { if (!dragging) showMenu(); }
    };

    private Overlay(Context c) {
        ctx = c;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean canShow() {
        if (Build.VERSION.SDK_INT >= 23) return android.provider.Settings.canDrawOverlays(ctx);
        return true;
    }

    public void show() {
        if (attached || !canShow()) return;
        // 用户主动隐藏过：服务重连、系统重启都不再自己冒出来
        if (Prefs.overlayHidden(ctx)) return;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        bgBox = new GradientDrawable();
        bgBox.setColor(C_CARD);
        bgBox.setCornerRadius(dp(16));
        bgBox.setStroke(dp(1), C_STROKE);
        box.setBackground(bgBox);
        box.setPadding(dp(13), dp(10), dp(13), dp(10));
        if (Build.VERSION.SDK_INT >= 21) box.setElevation(dp(8));
        box.setClipToOutline(true);          // 内容不越出圆角

        /* ① 一行：状态点 + 答案 + 识别按钮（34dp） */
        dot = new View(ctx);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(C_PRIMARY);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp =
                new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.setMarginEnd(dp(7));
        dotLp.gravity = Gravity.CENTER_VERTICAL;

        tvAnswer = new TextView(ctx);
        tvAnswer.setTextColor(C_T1);
        tvAnswer.setTextSize(19);
        tvAnswer.setTypeface(null, android.graphics.Typeface.BOLD);
        tvAnswer.setSingleLine(true);
        tvAnswer.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvAnswer.setGravity(Gravity.CENTER_VERTICAL);
        tvAnswer.setText("待命中");

        // 右侧「识别」按钮：没开自动解题时也能手动触发一次（截图路线现在默认自动走，不需要额外按钮）
        btnScan = new TextView(ctx);
        btnScan.setText("识别");
        btnScan.setTextSize(11);
        btnScan.setTypeface(null, android.graphics.Typeface.BOLD);
        btnScan.setGravity(Gravity.CENTER);
        btnScan.setPadding(dp(11), 0, dp(11), 0);
        btnScan.setClickable(true);
        btnScan.setFocusable(true);
        btnScan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (QService.get() == null) {
                    update("服务已掉线", "去系统设置里把「AI 答题」无障碍关一次再开");
                    return;
                }
                if (Prefs.autoAnswer(ctx)) {
                    // 自动模式：这个按钮 = 暂停/继续。开关一个都不动，只是临时停一停。
                    boolean paused = QService.toggleAutoPause();
                    refreshScanButton();
                    applyPauseState(paused);
                    return;
                }
                update("识别中…", "手动触发 · 正在读屏");
                QService.forceScan();
            }
        });
        refreshScanButton();

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(dot, dotLp);
        top.addView(tvAnswer, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        top.addView(btnScan, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(24)));

        /* ② 内容 / 解析（40dp = 两行 13sp） */
        tvDetail = new TextView(ctx);
        tvDetail.setTextColor(C_SECOND);
        tvDetail.setTextSize(13);
        tvDetail.setMaxLines(2);
        tvDetail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvDetail.setLineSpacing(0f, 1.15f);
        tvDetail.setGravity(Gravity.TOP);
        tvDetail.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showWhyDialog(); }
        });

        /* ③ 来源/耗时/把握（30dp = 两行 11sp） */
        tvMeta = new TextView(ctx);
        tvMeta.setTextColor(C_HINT);
        tvMeta.setTextSize(11);
        tvMeta.setMaxLines(2);
        tvMeta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvMeta.setGravity(Gravity.TOP);

        /* ④ 题干（14dp = 一行 10sp） */
        tvQ = new TextView(ctx);
        tvQ.setTextColor(C_LIGHT);
        tvQ.setTextSize(10);
        tvQ.setSingleLine(true);
        tvQ.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvQ.setGravity(Gravity.TOP);

        box.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));
        box.addView(tvDetail, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));
        box.addView(tvMeta, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));
        box.addView(tvQ, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12)));

        // 固定宽高：内容怎么变，窗口都不变长变短，也就不会跳位置、不用反复避让
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int wDp = W_DP;
        int screenDp = Math.round(dm.widthPixels / Math.max(1f, dm.density));
        if (screenDp > 0 && wDp > screenDp * 0.72f) wDp = Math.round(screenDp * 0.72f);

        lp = new WindowManager.LayoutParams(
                dp(wDp),
                dp(H_DP),
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = dp(110);

        box.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = (int) e.getRawX();
                        downRawY = (int) e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        downAt = android.os.SystemClock.uptimeMillis();
                        dragging = false;
                        ui.removeCallbacks(longPress);
                        ui.postDelayed(longPress, 700);   // 长按 700ms 弹菜单
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) e.getRawX() - downRawX;
                        int dy = (int) e.getRawY() - downRawY;
                        if (!dragging && Math.abs(dx) + Math.abs(dy) < dp(6)) return true;  // 防误触
                        dragging = true;
                        ui.removeCallbacks(longPress);    // 一旦拖动就取消长按
                        int sw = ctx.getResources().getDisplayMetrics().widthPixels;
                        int sh = ctx.getResources().getDisplayMetrics().heightPixels;
                        int nx = Math.max(0, Math.min(startX + dx, Math.max(0, sw - v.getWidth())));
                        int ny = Math.max(0, Math.min(startY + dy, Math.max(0, sh - v.getHeight())));
                        lp.x = nx;
                        lp.y = ny;
                        try { wm.updateViewLayout(v, lp); } catch (Throwable ignored) {}
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        ui.removeCallbacks(longPress);
                        return dragging;
                }
                return false;
            }
        });

        try {
            wm.addView(box, lp);
            root = box;
            attached = true;
            // 布局完成后贴到右上角（宽度未知，只能等量完再摆）
            box.post(new Runnable() {
                public void run() {
                    if (root == null) return;
                    int sw = ctx.getResources().getDisplayMetrics().widthPixels;
                    lp.x = Math.max(0, sw - root.getWidth() - dp(8));
                    try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 右上角按钮的三种形态：
     * 自动解题开 + 运行中 →「暂停」（点了临时停下，不动开关）
     * 自动解题开 + 已暂停 →「继续」（点了接着跑）
     * 自动解题关          →「识别」（手动答一次）
     */
    private void refreshScanButton() {
        if (btnScan == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(13));
        if (Prefs.autoAnswer(ctx)) {
            boolean paused = QService.isAutoPaused();
            btnScan.setText(paused ? "继续" : "暂停");
            btnScan.setEnabled(true);
            bg.setColor(paused ? 0xFFFDF4E7 : C_SOFT);          // 暂停中橙底，一眼看出停了
            btnScan.setBackground(bg);
            btnScan.setTextColor(paused ? 0xFF8A4B0F : C_PRIMARY);
        } else {
            btnScan.setText("识别");
            btnScan.setEnabled(true);
            bg.setColor(C_SOFT);
            btnScan.setBackground(bg);
            btnScan.setTextColor(C_PRIMARY);
        }
    }

    /** 用户主动隐藏：记到配置里，服务重连也不会恢复。 */
    public void hideByUser() {
        ui.removeCallbacks(longPress);
        Prefs.setOverlayHidden(ctx, true);
        hide();
        try {
            android.widget.Toast.makeText(ctx,
                    "悬浮窗已隐藏 · 回 App 点「悬浮窗」可恢复", android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }

    /** 用户主动显示。 */
    public void showByUser() {
        Prefs.setOverlayHidden(ctx, false);
        show();
    }

    /**
     * 截图前把自己藏起来。
     *
     * <p>悬浮窗是独立 Window，系统截图会连它一起拍进去；实测模型会把「识别中…」
     * 「手动触发 · 正在读屏」当成题目内容读，干扰判断。截图前后各调一次。
     */
    public void setInvisible(boolean invisible) {
        if (root == null) return;
        try {
            root.setVisibility(invisible ? View.INVISIBLE : View.VISIBLE);
        } catch (Throwable ignored) { }
    }

    public void refreshButton() {
        refreshScanButton();
    }

    public void update(String answer, String meta) {
        update(answer, null, meta);
    }

    /**
     * 暂停/恢复的视觉反馈：暂停时状态点变灰、元信息换成「已暂停」，恢复时都还原。
     * 开关本身（自动解题/点击/翻页）一个都不动。
     */
    public void applyPauseState(boolean paused) {
        if (paused) {
            if (tvMeta != null) {
                savedMeta = tvMeta.getText().toString();
                tvMeta.setText("⏸ 已暂停 · 点「继续」恢复");
            }
            paintDot(0xFF9AA4B5);
        } else {
            if (tvMeta != null) tvMeta.setText(savedMeta);
            paintDot(lastLevel == 2 ? C_RED : lastLevel == 1 ? C_WARN : C_PRIMARY);
        }
    }

    private void paintDot(int color) {
        if (dot == null) return;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        dot.setBackground(d);
    }

    /** 边框与状态点：0 正常（主色），1 低置信（橙），2 两路答案冲突（红）。 */
    public void setLevel(int level) {
        if (bgBox == null) return;
        int c = level == 2 ? C_RED : level == 1 ? C_WARN : C_STROKE;
        lastLevel = level;
        bgBox.setStroke(dp(level == 0 ? 1 : 2), c);
        if (root != null) root.setBackground(bgBox);
        paintDot(level == 2 ? C_RED : level == 1 ? C_WARN : C_PRIMARY);
    }

    /** detail 是答案内容（如「A. 导靴干磨」、多选列表或多路对照），传 null 表示这行留空。 */
    public void update(String answer, String detail, String meta) {
        show();
        refreshScanButton();
        if (attached && root != null) root.setBackground(bgBox);
        if (tvAnswer != null) {
            tvAnswer.setText(answer);
            // 长答案自动缩字号：高度是固定的，缩字号不会改变窗口尺寸，只是不截断
            int n = answer == null ? 0 : answer.length();
            tvAnswer.setTextSize(n > 18 ? 15 : n > 13 ? 17 : 19);
        }
        if (tvMeta != null) {
            String m = meta == null ? "" : meta;
            // 暂停中的提示别被新答案冲掉：一直挂在元信息最前面
            if (QService.isAutoPaused()) m = "⏸ 已暂停" + (m.isEmpty() ? "" : " · " + m);
            tvMeta.setText(m);
        }
        detailFull = detail == null ? "" : detail;
        whyFull = "";            // 换内容就把上一条解析收起来，等新的算完再显示
        renderDetail();
    }

    /** 内容区渲染：有答案内容优先显示内容，没有才显示解析。两者都能点开看全文。 */
    private void renderDetail() {
        if (tvDetail == null) return;
        if (!detailFull.isEmpty()) {
            tvDetail.setTextColor(C_SECOND);
            tvDetail.setText(detailFull);
        } else if (!whyFull.isEmpty()) {
            tvDetail.setTextColor(C_HINT);
            tvDetail.setText("解析 " + whyFull);
        } else {
            // 没内容时给一句淡淡的说明，别让中间空出一大块白
            tvDetail.setTextColor(C_LIGHT);
            tvDetail.setText(placeholder());
        }
    }

    /** 内容区空着时显示的状态提示（纯占位，不承载信息）。 */
    private String placeholder() {
        String a = tvAnswer == null || tvAnswer.getText() == null ? "" : tvAnswer.getText().toString();
        if (a.startsWith("待命")) return "拖动可移动 · 长按可开关菜单";
        if (a.contains("识别") || a.contains("看图") || a.contains("思考")
                || a.contains("复核") || a.contains("校对")) return "正在识别，稍等";
        return "";
    }

    /** 解析文本：空串表示这条没有解析。内容区空着时它会顶上，否则点内容区可看全文。 */
    public void updateWhy(String text) {
        if (tvDetail == null) return;
        String t = text == null ? "" : text.trim();
        whyFull = t;
        if (!t.isEmpty() && detailFull.isEmpty()) renderDetail();
    }

    /** 点内容区 → 小窗看全文（内容 + 解析，最多 6 行可滚动）。 */
    private void showWhyDialog() {
        StringBuilder sb = new StringBuilder();
        if (!detailFull.isEmpty()) sb.append(detailFull);
        if (!whyFull.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n解析：");
            else sb.append("解析：");
            sb.append(whyFull);
        }
        if (sb.length() == 0) return;
        try {
            TextView body = new TextView(ctx);
            body.setText(sb.toString());
            body.setTextSize(14);
            body.setTextColor(0xFF1C2333);
            body.setLineSpacing(0f, 1.35f);
            body.setPadding(dp(20), dp(16), dp(20), dp(6));
            body.setMaxLines(6);
            body.setMovementMethod(new android.text.method.ScrollingMovementMethod());

            android.app.AlertDialog d = new android.app.AlertDialog.Builder(ctx, R.style.WhiteDialog)
                    .setTitle("详细内容")
                    .setView(body)
                    .setPositiveButton("知道了", null)
                    .create();
            if (d.getWindow() != null && Build.VERSION.SDK_INT >= 26) {
                d.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            }
            d.show();
        } catch (Throwable ignored) {}
    }

    public void updateQuestion(String q) {
        if (tvQ == null) return;
        tvQ.setText(q == null ? "" : q);
    }

    /**
     * 把悬浮窗挪到不遮挡选项的位置。
     * rects 是扁平化的 {left,top,right,bottom} 序列（选项节点的屏幕坐标）。
     * 依次尝试「原位置 → 左侧 → 右侧 → 顶部 → 底部」，挑第一个不重叠的。
     */
    /**
     * 点击期间让悬浮窗「透传」触摸。
     *
     * <p>窗口默认是可触摸的，落在它上面的手势会被它吃掉——表现为「点了选项但没选上」，
     * 而且 {@code ScreenScan.click} 还会返回 true（ACTION_CLICK 兜底走的是 dispatchGesture，
     * 手势落在窗口上也算投递成功）。实测最靠上的 A 选项最容易中招，因为悬浮窗底部正好压着它。
     *
     * <p>点击前后各调一次，中间几百毫秒里用户也不会去点悬浮窗。
     */
    public void setPassThrough(boolean pass) {
        if (lp == null) return;
        final int flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        boolean cur = (lp.flags & flag) != 0;
        if (cur == pass) return;
        if (pass) lp.flags |= flag;
        else lp.flags &= ~flag;
        try {
            if (attached && root != null) wm.updateViewLayout(root, lp);
        } catch (Throwable ignored) {}
    }

    public void avoid(int[] rects) {
        if (!attached || root == null || rects == null || rects.length < 4) return;
        if (!Prefs.avoidOverlay(ctx)) return;
        root.post(new Runnable() {
            public void run() { doAvoid(rects); }
        });
    }

    private void doAvoid(int[] rects) {
        if (root == null) return;
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;

        int sw = ctx.getResources().getDisplayMetrics().widthPixels;
        int sh = ctx.getResources().getDisplayMetrics().heightPixels;

        int[][] cand = {
                {lp.x, lp.y},
                {dp(4), lp.y},                                   // 左侧
                {sw - w - dp(4), lp.y},                          // 右侧
                {lp.x, dp(60)},                                  // 顶部
                {dp(4), sh - h - dp(120)},                       // 左下
                {sw - w - dp(4), sh - h - dp(120)},               // 右下
        };
        for (int[] c : cand) {
            int x = Math.max(0, Math.min(c[0], Math.max(0, sw - w)));
            int y = Math.max(0, Math.min(c[1], Math.max(0, sh - h)));
            if (!hitsAny(x, y, w, h, rects)) {
                if (x == lp.x && y == lp.y) return;          // 原位就不挡
                lp.x = x; lp.y = y;
                try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) {}
                return;
            }
        }
    }

    private boolean hitsAny(int x, int y, int w, int h, int[] rects) {
        for (int i = 0; i + 3 < rects.length; i += 4) {
            int l = rects[i], t = rects[i + 1], r = rects[i + 2], b = rects[i + 3];
            if (x < r && x + w > l && y < b && y + h > t) return true;
        }
        return false;
    }

    /** 长按菜单：不用回 App 就能切模式、复制答案、清空记录。 */
    private void showMenu() {
        ui.removeCallbacks(longPress);
        try {
            final String[] items = {
                    Prefs.autoAnswer(ctx) ? "切换为：手动识别" : "切换为：自动解题",
                    "复制本场答案",
                    "清空本场记录",
                    "校准题目区域（只截题目和选项）",
                    "读屏诊断 · 导出节点",
                    "隐藏悬浮窗",
            };
            android.app.AlertDialog d = new android.app.AlertDialog.Builder(ctx, R.style.WhiteDialog)
                    .setTitle("AI 答题")
                    .setItems(items, new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface dd, int which) {
                            switch (which) {
                                case 0:
                                    Prefs.setAutoAnswer(ctx, !Prefs.autoAnswer(ctx));
                                    // 暂停概念只属于自动模式：切走时清掉暂停标志和「⏸ 已暂停」的显示，
                                    // 否则手动模式下点「识别」答题后，点击选项会被残留的暂停拦住
                                    QService.resetAutoPause();
                                    refreshScanButton();
                                    applyPauseState(false);
                                    QService.refreshOverlay();
                                    break;
                                case 1:
                                    String txt = Record.exportText();
                                    if (txt.isEmpty()) {
                                        toast("本场还没有记录");
                                    } else {
                                        android.content.ClipboardManager cm =
                                                (android.content.ClipboardManager)
                                                        ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                                        if (cm != null) cm.setPrimaryClip(
                                                android.content.ClipData.newPlainText("answers", txt));
                                        toast("已复制 " + Record.sessionSnapshot().size() + " 题答案");
                                    }
                                    break;
                                case 2:
                                    Record.clearSession(ctx);
                                    toast("本场记录已清空");
                                    break;
                                case 3:
                                    // 等菜单收起再画校准界面，否则会叠在一起
                                    ui.postDelayed(new Runnable() {
                                        public void run() { QService.openCropPicker(); }
                                    }, 220);
                                    break;
                                case 4:
                                    QService.dumpNow();
                                    break;
                                case 5:
                                default:
                                    hideByUser();
                                    break;
                            }
                        }
                    })
                    .create();
            if (d.getWindow() != null && Build.VERSION.SDK_INT >= 26) {
                d.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            }
            d.show();
        } catch (Throwable t) {
            hideByUser();     // 极端情况下退回原来的"长按即隐藏"
        }
    }

    private void toast(String s) {
        try {
            android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    public void hide() {
        if (!attached || root == null) return;
        try { wm.removeView(root); } catch (Throwable ignored) {}
        root = null; attached = false;
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
