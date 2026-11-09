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
        int pad = dp(10);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        bgBox = new GradientDrawable();
        bgBox.setColor(0xF01B1F27);
        bgBox.setCornerRadius(dp(14));
        bgBox.setStroke(dp(1), 0x553A7BFF);
        box.setBackground(bgBox);
        box.setPadding(pad, dp(8), dp(10), dp(9));

        tvAnswer = new TextView(ctx);
        tvAnswer.setTextColor(Color.WHITE);
        tvAnswer.setTextSize(20);
        tvAnswer.setTypeface(null, android.graphics.Typeface.BOLD);
        tvAnswer.setText("待命中");

        // 答案内容：不只给字母，把选项原文列出来，扫一眼就能核对
        tvDetail = new TextView(ctx);
        tvDetail.setTextColor(0xFFE7EEF9);
        tvDetail.setTextSize(13);
        tvDetail.setMaxLines(4);
        tvDetail.setMaxWidth(dp(200));
        tvDetail.setLineSpacing(0f, 1.15f);
        tvDetail.setVisibility(View.GONE);

        tvMeta = new TextView(ctx);
        tvMeta.setTextColor(0xFF8FA3BF);
        tvMeta.setTextSize(11);

        tvQ = new TextView(ctx);
        tvQ.setTextColor(0xFF6F7F96);
        tvQ.setTextSize(10);
        tvQ.setMaxLines(2);
        tvQ.setVisibility(View.GONE);

        // 右侧「识别」按钮：没开自动解题时也能手动触发一次扫描
        btnScan = new TextView(ctx);
        btnScan.setText("识别");
        btnScan.setTextSize(12);
        btnScan.setTextColor(Color.WHITE);
        btnScan.setPadding(dp(12), dp(5), dp(12), dp(5));
        btnScan.setClickable(true);
        btnScan.setFocusable(true);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(dp(13));
        btnBg.setColor(0xFF2B6CF6);
        btnScan.setBackground(btnBg);
        btnScan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                update("识别中…", "手动触发 · 正在读屏");
                QService.forceScan();
            }
        });

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(tvAnswer);
        col.addView(tvDetail);
        col.addView(tvMeta);
        col.addView(tvQ);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.setMarginStart(dp(10));
        row.addView(btnScan, btnLp);

        box.addView(row);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
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
                        dragging = false;
                        ui.removeCallbacks(longPress);
                        ui.postDelayed(longPress, 700);   // 长按 700ms 隐藏
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

    /** 「自动读题」开着时按钮变灰显示「自动」，关掉才恢复成可点的「识别」。 */
    private void refreshScanButton() {
        if (btnScan == null) return;
        boolean auto = Prefs.autoAnswer(ctx);
        btnScan.setText(auto ? "自动" : "识别");
        btnScan.setEnabled(!auto);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(13));
        bg.setColor(auto ? 0xFF3A3F49 : 0xFF2B6CF6);
        btnScan.setBackground(bg);
        btnScan.setTextColor(auto ? 0xFF7E8CA6 : 0xFFFFFFFF);
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

    public void refreshButton() {
        refreshScanButton();
    }

    public void update(String answer, String meta) {
        update(answer, null, meta);
    }

    /** 边框状态：0 正常蓝，1 低置信黄，2 两路答案冲突红。 */
    public void setLevel(int level) {
        if (bgBox == null) return;
        int c = level == 2 ? 0xCCF04438 : level == 1 ? 0xCCF79009 : 0x553A7BFF;
        bgBox.setStroke(dp(level == 0 ? 1 : 2), c);
        if (root != null) root.setBackground(bgBox);
    }

    /** detail 是答案内容（如「A. 导靴干磨」），传 null 表示不显示。 */
    public void update(String answer, String detail, String meta) {
        show();
        refreshScanButton();
        if (attached && root != null) root.setBackground(bgBox);
        if (tvAnswer != null) tvAnswer.setText(answer);
        if (tvMeta != null)   tvMeta.setText(meta);
        if (tvDetail != null) {
            if (detail == null || detail.isEmpty()) {
                tvDetail.setVisibility(View.GONE);
            } else {
                tvDetail.setVisibility(View.VISIBLE);
                tvDetail.setText(detail);
            }
        }
    }

    public void updateQuestion(String q) {
        if (tvQ == null) return;
        if (q == null || q.isEmpty()) { tvQ.setVisibility(View.GONE); return; }
        tvQ.setVisibility(View.VISIBLE);
        tvQ.setText(q.length() > 40 ? q.substring(0, 40) + "…" : q);
    }

    /**
     * 把悬浮窗挪到不遮挡选项的位置。
     * rects 是扁平化的 {left,top,right,bottom} 序列（选项节点的屏幕坐标）。
     * 依次尝试「原位置 → 左侧 → 右侧 → 顶部 → 底部」，挑第一个不重叠的。
     */
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
                    "隐藏悬浮窗",
            };
            android.app.AlertDialog d = new android.app.AlertDialog.Builder(ctx, R.style.WhiteDialog)
                    .setTitle("AI 答题")
                    .setItems(items, new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface dd, int which) {
                            switch (which) {
                                case 0:
                                    Prefs.setAutoAnswer(ctx, !Prefs.autoAnswer(ctx));
                                    refreshScanButton();
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
