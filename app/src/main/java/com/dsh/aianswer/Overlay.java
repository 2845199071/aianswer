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

    /** 固定尺寸（dp）：内容只在这块地方里变，窗口本身不长不短。
        宽度比原来多 18dp、高 6dp，让「识别/暂停」和「校准」两个按钮都放得下且不挤掉答案。 */
    private static final int W_DP = 268;
    private static final int H_DP = 152;   // 中间内容区吃满剩余高度，3 行 13sp 不再被切
    /** 右上角按钮统一高度（dp）：比原来的 24 大一档，手指更容易点中。 */
    private static final int BTN_DP = 30;

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
    /** detail 区当前完整内容（多选列表 / 多路对照）。 */
    private String detailFull = "";
    /** 解析全文，点 detail 区弹窗看。 */
    private String whyFull = "";
    private TextView btnScan;
    private TextView btnCrop;
    private GradientDrawable bgBox;
    private boolean attached = false;

    /** 长按判定时长：短于「按住不放」的直觉，又不至于按不到。 */
    private static final int LONG_PRESS_MS = 700;

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

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

        TouchCard box = new TouchCard(ctx);
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
        btnScan.setTextSize(12);
        btnScan.setTypeface(null, android.graphics.Typeface.BOLD);
        btnScan.setGravity(Gravity.CENTER);
        btnScan.setPadding(dp(10), 0, dp(10), 0);
        btnScan.setClickable(true);
        btnScan.setFocusable(true);
        btnScan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (QService.get() == null) {
                    update("服务已掉线", "去系统设置里把「AI 答题」无障碍关一次再开");
                    return;
                }
                if (Prefs.autoAnswer(ctx)) {
                    // 自动模式：没按过「开始」→ 这个按钮就是开始；按过之后才是 暂停/继续。
                    if (!QService.isAutoStarted()) {
                        QService.startAuto();
                        refreshScanButton();
                        applyPauseState(false);
                        update("自动读题中…", "正在等下一道题出现在屏幕上");
                        return;
                    }
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

        // 「校准」常驻按钮：截图只截题目+选项这块区域，换 App、换机型、题干变长都要重框，
        // 属于高频动作，埋在长按菜单里太深，直接放到窗上。它是次要操作，所以浅灰底区别于主按钮。
        btnCrop = new TextView(ctx);
        btnCrop.setText("校准");
        btnCrop.setTextSize(12);
        btnCrop.setTypeface(null, android.graphics.Typeface.BOLD);
        btnCrop.setGravity(Gravity.CENTER);
        btnCrop.setPadding(dp(10), 0, dp(10), 0);
        btnCrop.setClickable(true);
        btnCrop.setFocusable(true);
        GradientDrawable cropBg = new GradientDrawable();
        cropBg.setCornerRadius(dp(BTN_DP / 2));
        cropBg.setColor(C_FIELD);
        btnCrop.setBackground(cropBg);
        btnCrop.setTextColor(C_SECOND);
        btnCrop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (QService.get() == null) {
                    update("服务已掉线", "去系统设置里把「AI 答题」无障碍关一次再开");
                    return;
                }
                QService.openCropPicker();
            }
        });

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(dot, dotLp);
        top.addView(tvAnswer, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        top.addView(btnScan, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(BTN_DP)));
        LinearLayout.LayoutParams cropLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(BTN_DP));
        cropLp.setMarginStart(dp(6));
        top.addView(btnCrop, cropLp);

        /* ② 内容 / 解析（40dp = 两行 13sp） */
        tvDetail = new TextView(ctx);
        tvDetail.setTextColor(C_SECOND);
        tvDetail.setTextSize(13);
        tvDetail.setMaxLines(3);
        tvDetail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvDetail.setLineSpacing(0f, 1.08f);   // 3 行要在剩余高度里放得下，行距不能太大
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

        box.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));
        // 高度 = 0 + weight 1：把「窗口总高 - 其他三行」全给内容区。
        // 以前写死 48dp，而 3 行 13sp 带 1.15 行距需要 ~53dp，第三行就被切掉半截。
        box.addView(tvDetail, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
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

        // 拖动 / 长按 / 点击三种手势都在 TouchCard 的拦截层里分流，见该类注释

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
     * 悬浮窗容器：拖动、长按、点击三种手势都在这一层先分流。
     *
     * <p>以前是给根布局挂 OnTouchListener，但「识别」按钮和中间内容区各自带点击监听，
     * 手指落在它们上面时 DOWN 被子 View 吃掉，父布局根本收不到事件——
     * 表现就是「只有空白处能长按、能拖」，用户以为长按坏了。
     *
     * <p>所以改成重写 {@link #dispatchTouchEvent}：它是手势进入窗口的第一站，
     * 无论手指落在按钮上还是空白处都会先经过这里。
     * <ul>
     *   <li>按下：记起点、起 700ms 长按计时，然后<b>原样下发给子 View</b>，点击照旧；</li>
     *   <li>移动超阈值：判定拖动，取消长按，给子 View 补一个 CANCEL 再把窗口搬走；</li>
     *   <li>长按到点：弹菜单并打标记，随后的移动/抬手全吞掉，避免顺手又点了「识别」；</li>
     *   <li>原地抬手：不拦，子 View 正常收 click。</li>
     * </ul>
     *
     * <p>阈值取 {@code ViewConfiguration.getScaledTouchSlop()}，各分辨率/密度手感一致；
     * 移动上限用窗口实际宽高算，换机型也拖不出屏幕。
     */
    private final class TouchCard extends LinearLayout {

        private int downRawX, downRawY, startX, startY;
        private boolean dragging, longFired;
        private final int slop;

        private final Runnable onLongPress = new Runnable() {
            public void run() {
                if (dragging) return;
                longFired = true;
                showMenu();
            }
        };

        TouchCard(Context c) {
            super(c);
            slop = Math.max(dp(6), android.view.ViewConfiguration.get(c).getScaledTouchSlop());
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = (int) e.getRawX();
                    downRawY = (int) e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = false;
                    longFired = false;
                    ui.removeCallbacks(onLongPress);
                    ui.postDelayed(onLongPress, LONG_PRESS_MS);
                    return super.dispatchTouchEvent(e);   // 不拦：子 View 的点击照常

                case MotionEvent.ACTION_MOVE: {
                    if (longFired) return true;           // 菜单已弹出：这一次手势剩下的都别管
                    int dx = (int) e.getRawX() - downRawX;
                    int dy = (int) e.getRawY() - downRawY;
                    if (!dragging) {
                        // 小抖动不算拖动，继续交给子 View（也避免误伤点击）
                        if (Math.abs(dx) < slop && Math.abs(dy) < slop) {
                            return super.dispatchTouchEvent(e);
                        }
                        dragging = true;
                        ui.removeCallbacks(onLongPress);  // 一旦拖动，长按作废
                        cancelChildren(e);                // 子 View 清掉按下态，不会再触发点击
                    }
                    moveTo(startX + dx, startY + dy);
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    ui.removeCallbacks(onLongPress);
                    boolean swallow = dragging || longFired;
                    dragging = false;
                    if (swallow) {
                        cancelChildren(e);
                        return true;
                    }
                    return super.dispatchTouchEvent(e);   // 普通点击：照常交给子 View
                }

                default:
                    if (longFired || dragging) return true;
                    return super.dispatchTouchEvent(e);
            }
        }

        /** 把窗口搬到 (x, y)，坐标夹在屏幕内。 */
        private void moveTo(int x, int y) {
            int sw = ctx.getResources().getDisplayMetrics().widthPixels;
            int sh = ctx.getResources().getDisplayMetrics().heightPixels;
            lp.x = Math.max(0, Math.min(x, Math.max(0, sw - getWidth())));
            lp.y = Math.max(0, Math.min(y, Math.max(0, sh - getHeight())));
            try { wm.updateViewLayout(this, lp); } catch (Throwable ignored) {}
        }

        /** 给子 View 补一个 CANCEL，取消它已按下的状态（否则按钮会一直显示按下）。 */
        private void cancelChildren(MotionEvent src) {
            try {
                MotionEvent c = MotionEvent.obtain(src);
                c.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(c);
                c.recycle();
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 右上角按钮的四种形态：
     * 自动解题开 + 未按开始 →「开始」（实心蓝，按下才进入自动读题；开关打开不等于开始）
     * 自动解题开 + 运行中   →「暂停」（点了临时停下，不动开关）
     * 自动解题开 + 已暂停   →「继续」（点了接着跑）
     * 自动解题关            →「识别」（手动答一次）
     */
    public void refreshScanButton() {
        if (btnScan == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(BTN_DP / 2));
        if (Prefs.autoAnswer(ctx)) {
            btnScan.setEnabled(true);
            if (!QService.isAutoStarted()) {
                btnScan.setText("开始");
                bg.setColor(0xFF2F6BFF);                        // 实心蓝：当前最该按的按钮
                btnScan.setBackground(bg);
                btnScan.setTextColor(0xFFFFFFFF);
                return;
            }
            boolean paused = QService.isAutoPaused();
            btnScan.setText(paused ? "继续" : "暂停");
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
        ui.removeCallbacksAndMessages(null);
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

        String head = answer == null ? "" : answer;
        String rest = detail == null ? "" : detail;

        // 单选时 QService 会把选项原文并进答案行（「答案 A  链条磨损加速」），省一行高度。
        // 但窗口只有两个按钮那么宽，长选项会被省略号截断 —— 这里先按真实宽度量：
        // 19sp 放不下就一级级缩到 12sp；连 12sp 都放不下，就把选项原文退回下面一行，
        // 大字只留「答案 A」。用户看到的是完整内容，而不是「答案 A 链条…」。
        if (rest.isEmpty()) {
            int sep = head.indexOf("  ");
            // 15sp 是可读下限：再小就得眯眼。放不下就拆行，让选项原文去下面占那几行空白。
            if (sep > 0 && textWidth(head, 15) > answerAvail()) {
                rest = head.substring(sep + 2).trim();
                head = head.substring(0, sep);
            }
        }
        if (tvAnswer != null) {
            int sp = pickSp(head);
            tvAnswer.setTextSize(sp > 0 ? sp : 12);   // 只剩一两个字的极端情况也不会爆版
            tvAnswer.setText(head);
        }

        if (tvMeta != null) {
            String m = meta == null ? "" : meta;
            // 暂停中的提示别被新答案冲掉：一直挂在元信息最前面
            if (QService.isAutoPaused()) m = "⏸ 已暂停" + (m.isEmpty() ? "" : " · " + m);
            tvMeta.setText(m);
        }
        detailFull = rest;
        whyFull = "";            // 换内容就把上一条解析收起来，等新的算完再显示
        renderDetail();
    }

    /** 量文字宽度用的画笔（不参与布局，只做估算）。 */
    private final android.text.TextPaint measurePaint = new android.text.TextPaint();

    private float textWidth(String s, int sp) {
        measurePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        measurePaint.setTextSize(sp * ctx.getResources().getDisplayMetrics().scaledDensity);
        return measurePaint.measureText(s == null ? "" : s);
    }

    /**
     * 答案行的可用宽度（px）。
     *
     * <p>窗口宽度是固定的，但按钮文字会随状态变（「识别/暂停/继续」），所以优先取
     * TextView 量出来的真实宽度；还没布局完时按设计值估算。
     */
    private int answerAvail() {
        int avail;
        if (tvAnswer != null && tvAnswer.getWidth() > 0) {
            avail = tvAnswer.getWidth();
        } else {
            int win = lp != null && lp.width > 0 ? lp.width : dp(W_DP);
            int w1 = btnScan != null && btnScan.getWidth() > 0 ? btnScan.getWidth() : dp(50);
            int w2 = btnCrop != null && btnCrop.getWidth() > 0 ? btnCrop.getWidth() : dp(50);
            avail = win - dp(26) - dp(14) - w1 - w2 - dp(6);
        }
        return Math.max(dp(48), avail - dp(2));   // 留 2dp 余量，别让省略号卡在最后一个字上
    }

    /** 放得下的最大字号（19sp 起逐级降到 12sp）；都放不下返回 -1。 */
    private int pickSp(String text) {
        int avail = answerAvail();
        for (int sp = 19; sp >= 12; sp--) {
            if (textWidth(text, sp) <= avail) return sp;
        }
        return -1;
    }

    /**
     * 内容区渲染：选项原文在上，解析在下，两行封顶；都没有才显示占位提示。
     *
     * <p>以前是「有内容就不显示解析」——单选把选项并进答案行之后内容为空，
     * 解析又只在内容为空时才顶上来，于是中间空出一大块白，用户以为答案丢了。
     */
    private void renderDetail() {
        if (tvDetail == null) return;
        StringBuilder sb = new StringBuilder();
        if (!detailFull.isEmpty()) sb.append(detailFull);
        if (!whyFull.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("解析 ").append(whyFull);
        }
        if (sb.length() == 0) {
            tvDetail.setTextColor(C_LIGHT);
            tvDetail.setText(placeholder());
            return;
        }
        tvDetail.setTextColor(detailFull.isEmpty() ? C_HINT : C_SECOND);
        tvDetail.setText(sb.toString());
    }

    /** 内容区空着时显示的状态提示（纯占位，不承载信息）。 */
    private String placeholder() {
        String a = tvAnswer == null || tvAnswer.getText() == null ? "" : tvAnswer.getText().toString();
        if (a.startsWith("待命")) return "拖动可移动 · 长按任意位置开设置";
        if (a.contains("识别") || a.contains("看图") || a.contains("思考")
                || a.contains("复核") || a.contains("校对")) return "正在识别，稍等";
        return "";
    }

    /** 解析文本：空串表示这条没有解析。有选项原文时排在它下面一行。 */
    public void updateWhy(String text) {
        if (tvDetail == null) return;
        whyFull = text == null ? "" : text.trim();
        renderDetail();
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

    /**
     * 长按菜单：不用回 App 就能切模式、复制答案、清空记录、校准区域、导出诊断、隐藏悬浮窗。
     *
     * <p>排版和 App 设置页一致（dialog_overlay_menu.xml）：白底圆角弹窗 + 分段小标题 +
     * 每项一个浅灰圆角块 + 底部主按钮，不再用系统 setItems 的紧贴列表。
     */
    private void showMenu() {
        ui.removeCallbacksAndMessages(null);
        try {
            final View v = android.view.LayoutInflater.from(ctx)
                    .inflate(R.layout.dialog_overlay_menu, null);

            final boolean auto = Prefs.autoAnswer(ctx);
            final boolean paused = QService.isAutoPaused();
            final int recorded = Record.sessionSnapshot().size();

            setText(v, R.id.tvOvStatus, auto
                    ? (paused ? "自动解题 · 已暂停 · 点右上角「继续」恢复" : "自动解题 · 运行中")
                    : "手动识别 · 点右上角「识别」才读一次题");
            setText(v, R.id.tvOvModeTitle, auto ? "切换为手动识别" : "切换为自动解题");
            setText(v, R.id.tvOvModeHint, auto
                    ? "手动模式：只在你点「识别」时读一次题，不自动点选项、不自动翻页"
                    : "自动模式：连着读题答题；右上角按钮会变成「暂停 / 继续」，随时停一下");
            setText(v, R.id.tvOvCopyHint, recorded > 0
                    ? "当前 " + recorded + " 题 · 复制成文本，方便粘到别处"
                    : "本场还没有记录，先答一题再来");

            final android.app.AlertDialog d =
                    new android.app.AlertDialog.Builder(ctx, R.style.WhiteDialog)
                            .setView(v)
                            .create();
            if (d.getWindow() != null && Build.VERSION.SDK_INT >= 26) {
                d.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            }

            v.findViewById(R.id.rowOvMode).setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    Prefs.setAutoAnswer(ctx, !Prefs.autoAnswer(ctx));
                    // 暂停概念只属于自动模式：切走时清掉暂停标志和「⏸ 已暂停」的显示，
                    // 否则手动模式下点「识别」答题后，点击选项会被残留的暂停拦住
                    QService.resetAutoPause();
                    refreshScanButton();
                    applyPauseState(false);
                    QService.refreshOverlay();
                    MainActivity.refreshIfForeground();   // 主界面开着时同步那个开关
                    d.dismiss();
                }
            });

            v.findViewById(R.id.rowOvCopy).setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
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
                    d.dismiss();
                }
            });

            v.findViewById(R.id.rowOvClear).setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    Record.clearSession(ctx);
                    toast("本场记录已清空");
                    MainActivity.refreshIfForeground();
                    d.dismiss();
                }
            });

            v.findViewById(R.id.rowOvDump).setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    d.dismiss();
                    // 同理：导出的节点树里别把这份菜单也带进去
                    ui.postDelayed(new Runnable() {
                        public void run() { QService.dumpNow(); }
                    }, 240);
                }
            });

            v.findViewById(R.id.rowOvHide).setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    d.dismiss();
                    ui.postDelayed(new Runnable() {
                        public void run() { hideByUser(); }
                    }, 200);
                }
            });

            v.findViewById(R.id.btnOvDone).setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) { d.dismiss(); }
            });

            d.show();
            // 宽度与 App 里的设置弹窗同比例（0.86 屏宽），高度由内容决定
            if (d.getWindow() != null) {
                int w = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.86f);
                d.getWindow().setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable t) {
            toast("菜单打开失败，请再长按一次");
        }
    }

    /** 菜单里的文字统一从这里写，空 id 也不会炸。 */
    private void setText(View root, int id, String s) {
        TextView tv = (TextView) root.findViewById(id);
        if (tv != null) tv.setText(s == null ? "" : s);
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
