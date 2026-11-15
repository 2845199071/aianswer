package com.dsh.aianswer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 手动校准「题目区域」：在屏幕上拉两条横线，框住题干 + 选项。
 *
 * <p>为什么不再靠自动推断：视觉模型返回的选项框常常只框住「A」那个字母圈，
 * 按它算宽度会裁成一条竖条，选项文字整条被切掉。屏幕就那么大，题目区在同一个 App 里
 * 位置是固定的 —— 让用户拖一次，比任何推断都准。
 *
 * <p>宽度固定取满屏（选项文字可能很长），只让用户调上下两条边。
 * 结果存进 {@link Prefs#visionCrop}，和自动缓存的格式完全一致，所以两边可以互相覆盖。
 */
public final class CropPicker {

    private static CropPicker sInst;

    private final Context ctx;
    private final WindowManager wm;
    private final int sw, sh;

    private FrameLayout root;
    private WindowManager.LayoutParams lp;
    private View topMask, bottomMask;
    private View lineTop, lineBottom;
    private View selTint, sideL, sideR;
    private View tipBox;                  // 框选区域中央的提示（几秒后淡出）
    private int top, bottom;
    private String pkg = "";
    private int dragging = 0;          // 0 无 / 1 上边界 / 2 下边界
    private float downY;
    private int startTop, startBottom;

    private CropPicker(Context c) {
        ctx = c.getApplicationContext();
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        sw = dm.widthPixels;
        sh = dm.heightPixels;
    }

    /**
     * 打开校准界面。
     *
     * @param pkg  当前答题 App 的包名（保存时按它归档）
     * @param topH 初始上边界（像素），传 0 表示用默认值
     * @param botH 初始下边界（像素），传 0 表示用默认值
     */
    public static void show(Context c, String pkg, int topH, int botH) {
        if (sInst != null) sInst.close();
        CropPicker p = new CropPicker(c);
        p.pkg = pkg == null ? "" : pkg;
        p.open(topH, botH);
        sInst = p;
    }

    private void open(int topH, int botH) {
        // 初值：存过就沿用（同一个包名或通用设置），没存过按屏高 24%~62% 猜一个
        top = (int) (sh * 0.24f);
        bottom = (int) (sh * 0.62f);
        try {
            String saved = Prefs.visionCrop(ctx);
            if (saved != null && !saved.isEmpty()) {
                org.json.JSONObject o = new org.json.JSONObject(saved);
                String sp = o.optString("pkg");
                if (o.optInt("sw") == sw && o.optInt("sh") == sh
                        && (sp.isEmpty() || sp.equals(pkg))) {
                    top = o.optInt("t", top);
                    bottom = o.optInt("b", bottom);
                }
            }
        } catch (Throwable ignored) { }
        if (topH > 0) top = topH;
        if (botH > 0) bottom = botH;
        clamp();

        root = new FrameLayout(ctx);
        root.setBackgroundColor(0x00000000);

        topMask = new View(ctx);
        topMask.setBackgroundColor(0xB3000000);           // 上方压暗
        bottomMask = new View(ctx);
        bottomMask.setBackgroundColor(0xB3000000);

        // 选区淡蓝：让「会被截走的范围」一眼可见，而不是靠两条粗色带猜
        selTint = new View(ctx);
        selTint.setBackgroundColor(0x1A2F6BFF);
        // 左右竖线：截图是全宽，但把四边都画出来，框才像个框
        sideL = new View(ctx);
        sideL.setBackgroundColor(0xFF2F6BFF);
        sideR = new View(ctx);
        sideR.setBackgroundColor(0xFF2F6BFF);

        lineTop = new Edge(ctx, "上边界");
        lineBottom = new Edge(ctx, "下边界");

        // 中央提示：第一次用根本不知道该框什么，只靠底部一行小字容易漏看。
        // 放在选区内、几秒后自己淡出，不长久挡题。
        LinearLayout tip = new LinearLayout(ctx);
        tip.setOrientation(LinearLayout.VERTICAL);
        tip.setGravity(Gravity.CENTER_HORIZONTAL);
        tip.setPadding(dp(18), dp(12), dp(18), dp(13));
        GradientDrawable tipBg = new GradientDrawable();
        tipBg.setColor(0xE61B1F27);
        tipBg.setCornerRadius(dp(14));
        tipBg.setStroke(dp(1), 0x66FFFFFF);
        tip.setBackground(tipBg);

        TextView tipTitle = new TextView(ctx);
        tipTitle.setText("题干和选项都要框进来");
        tipTitle.setTextColor(0xFFFFFFFF);
        tipTitle.setTextSize(15);
        tipTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tipTitle.setGravity(Gravity.CENTER);
        tip.addView(tipTitle);

        TextView tipSub = new TextView(ctx);
        tipSub.setText("框内 = 要发给模型的画面；只框选项会答错");
        tipSub.setTextColor(0xFFC9D6EA);
        tipSub.setTextSize(11);
        tipSub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        tip.addView(tipSub, subLp);
        tipBox = tip;

        root.addView(topMask, new FrameLayout.LayoutParams(sw, top));
        root.addView(selTint, new FrameLayout.LayoutParams(sw, Math.max(1, bottom - top)));
        root.addView(sideL, new FrameLayout.LayoutParams(dp(3), Math.max(1, bottom - top)));
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(3), Math.max(1, bottom - top));
        rlp.gravity = Gravity.TOP | Gravity.END;
        root.addView(sideR, rlp);
        root.addView(lineTop, new FrameLayout.LayoutParams(sw, dp(46)));
        root.addView(lineBottom, new FrameLayout.LayoutParams(sw, dp(46)));
        FrameLayout.LayoutParams tipLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        tipLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        root.addView(tipBox, tipLp);
        root.addView(bottomMask, new FrameLayout.LayoutParams(sw, sh - bottom));

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(10), dp(14), dp(10));

        TextView hint = new TextView(ctx);
        hint.setText("拖动两条线：题干和选项都要框进来");
        hint.setTextSize(12);
        hint.setTextColor(0xFFDCE6F5);
        bar.addView(hint, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView cancel = makeButton("取消", 0xFF3A4150, 0xFFDCE6F5);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { close(); }
        });
        bar.addView(cancel, new LinearLayout.LayoutParams(dp(64), dp(36)));

        TextView ok = makeButton("保存", 0xFF4D7CFE, 0xFFFFFFFF);
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(dp(74), dp(36));
        okLp.setMarginStart(dp(8));
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { save(); }
        });
        bar.addView(ok, okLp);

        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0xF21B1F27);
        barBg.setCornerRadius(dp(14));
        bar.setBackground(barBg);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM;
        barLp.bottomMargin = dp(28);
        barLp.leftMargin = dp(14);
        barLp.rightMargin = dp(14);
        root.addView(bar, barLp);

        root.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        float y = e.getRawY();
                        int dt = Math.abs((int) y - top);
                        int db = Math.abs((int) y - bottom);
                        dragging = dt <= db ? 1 : 2;
                        downY = y;
                        startTop = top;
                        startBottom = bottom;
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        int dy = (int) (e.getRawY() - downY);
                        if (dragging == 1) top = startTop + dy;
                        else if (dragging == 2) bottom = startBottom + dy;
                        clamp();
                        layout();
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        dragging = 0;
                        return true;
                }
                return false;
            }
        });

        lp = new WindowManager.LayoutParams(
                sw, sh,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;

        try {
            wm.addView(root, lp);
        } catch (Throwable t) {
            root = null;
            toast("无法显示校准界面：悬浮窗权限被关了");
            return;
        }
        layout();

        // 2.6 秒后淡出：提示只在「刚进来不知道框什么」时有用，一直挂着就挡题了
        if (tipBox != null) {
            final View t = tipBox;
            t.postDelayed(new Runnable() {
                public void run() {
                    t.animate().alpha(0f).setDuration(420).start();
                }
            }, 2600);
        }
    }

    /**
     * 一条可拖的边界。
     *
     * <p>以前是 34dp 高的整条蓝色实心块：够显眼，但它把下面的题目文字整条盖住，
     * 用户看不出「线切在哪一行」，两条色带也不像一个选框。现在改成
     * <b>白描边 + 细蓝线 + 中间小胶囊</b>：线落在哪一行一目了然，拖动时手也有明确落点。
     */
    private final class Edge extends View {
        private final String label;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        Edge(Context c, String label) {
            super(c);
            this.label = label;
            text.setColor(0xFFFFFFFF);
            text.setTextSize(dpf(11));
            text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth(), cy = getHeight() / 2;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);

            // ① 白色光晕：压在深色题目或浅色背景上都看得见
            stroke.setColor(0xE6FFFFFF);
            stroke.setStrokeWidth(dpf(7));
            cv.drawLine(0, cy, w, cy, stroke);

            // ② 蓝色主线
            stroke.setColor(0xFF2F6BFF);
            stroke.setStrokeWidth(dpf(3.5f));
            cv.drawLine(0, cy, w, cy, stroke);

            // ③ 中间胶囊：抓手 + 标出这是哪一条
            float pw = dpf(86), ph = dpf(28);
            float l = (w - pw) / 2f, t = cy - ph / 2f, r = l + pw, b = t + ph;
            stroke.setStyle(Paint.Style.FILL);
            stroke.setColor(0xFF2F6BFF);
            cv.drawRoundRect(l, t, r, b, ph / 2f, ph / 2f, stroke);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(0xFFFFFFFF);
            stroke.setStrokeWidth(dpf(1.5f));
            cv.drawRoundRect(l, t, r, b, ph / 2f, ph / 2f, stroke);

            Paint.FontMetrics fm = text.getFontMetrics();
            cv.drawText(label, w / 2f, cy - (fm.ascent + fm.descent) / 2f, text);
        }
    }

    private TextView makeButton(String text, int bg, int fg) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(fg);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(18));
        d.setColor(bg);
        tv.setBackground(d);
        tv.setClickable(true);
        return tv;
    }

    private void clamp() {
        int minH = (int) (sh * 0.10f);
        if (top < 0) top = 0;
        if (bottom > sh) bottom = sh;
        if (bottom - top < minH) {
            if (dragging == 1) top = bottom - minH;
            else bottom = top + minH;
        }
        if (top < 0) { top = 0; bottom = minH; }
        if (bottom > sh) { bottom = sh; top = sh - minH; }
    }

    /** 两条线 + 上下遮罩跟着走。 */
    private void layout() {
        if (root == null) return;
        int hh = dp(46);
        positionLine(lineTop, top - hh / 2);
        positionLine(lineBottom, bottom - hh / 2);

        FrameLayout.LayoutParams tp = (FrameLayout.LayoutParams) topMask.getLayoutParams();
        tp.height = Math.max(1, top);
        topMask.setLayoutParams(tp);

        FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) bottomMask.getLayoutParams();
        bp.height = Math.max(1, sh - bottom);
        bp.topMargin = bottom;
        bottomMask.setLayoutParams(bp);

        // 选区淡色 + 左右竖线跟着两条线走
        int selH = Math.max(1, bottom - top);
        if (selTint != null) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) selTint.getLayoutParams();
            p.topMargin = top;
            p.height = selH;
            selTint.setLayoutParams(p);
        }
        if (sideL != null) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) sideL.getLayoutParams();
            p.topMargin = top;
            p.height = selH;
            sideL.setLayoutParams(p);
        }
        if (sideR != null) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) sideR.getLayoutParams();
            p.topMargin = top;
            p.height = selH;
            sideR.setLayoutParams(p);
        }
        if (tipBox != null) {
            // 放在选区正中；测量还没完成时按估算高度居中，下一帧会自己纠正
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) tipBox.getLayoutParams();
            int th = tipBox.getHeight() > 0 ? tipBox.getHeight() : dp(64);
            p.topMargin = Math.max(0, top + Math.max(0, (selH - th) / 2));
            tipBox.setLayoutParams(p);
        }
    }

    private void positionLine(View v, int y) {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
        // 布局还没测量时 v.getHeight() 是 0，用固定高度算，避免线跑到屏幕外
        p.topMargin = Math.max(0, Math.min(sh - dp(46), y));
        v.setLayoutParams(p);
    }

    private void save() {
        try {
            // 校准时焦点在这个窗口上，取到的包名可能是自己 —— 那种情况存成「通用区域」，
            // 对所有 App 生效，否则这条设置会挂在一个永远匹配不上的包名下，白白失效。
            String p = pkg;
            if (p == null || p.isEmpty() || p.equals(ctx.getPackageName())) p = "";
            String json = "{\"pkg\":\"" + p + "\",\"sw\":" + sw + ",\"sh\":" + sh
                    + ",\"l\":0,\"t\":" + top + ",\"r\":" + sw + ",\"b\":" + bottom + "}";
            Prefs.setVisionCrop(ctx, json);
            Prefs.setVisionCropManual(ctx, true);   // 之后自动推断不许覆盖
            android.util.Log.i("AIQ", "校准保存 " + json);
            int pct = Math.round((bottom - top) * 100f / sh);
            toast("已保存：" + top + " ~ " + bottom + "（高 " + pct + "% 屏）· 之后都按这块裁剪");
        } catch (Throwable ignored) { }
        close();
    }

    private void close() {
        if (root != null) {
            try { wm.removeView(root); } catch (Throwable ignored) { }
            root = null;
        }
        if (sInst == this) sInst = null;
        Overlay.get(ctx).setInvisible(false);     // 校准期间藏起来的悬浮窗恢复
    }

    private void toast(String s) {
        try {
            android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) { }
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 取小数 dp（线宽 3.5dp 这种，用 dp(int) 会被截断成 3）。 */
    private float dpf(float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }
}
