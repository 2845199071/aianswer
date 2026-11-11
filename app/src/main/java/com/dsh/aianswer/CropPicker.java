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
    private TextView lineTop, lineBottom;
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

        lineTop = makeHandle("上边界 · 拖动");
        lineBottom = makeHandle("下边界 · 拖动");

        root.addView(topMask, new FrameLayout.LayoutParams(sw, top));
        root.addView(lineTop, new FrameLayout.LayoutParams(sw, dp(34)));
        root.addView(lineBottom, new FrameLayout.LayoutParams(sw, dp(34)));
        root.addView(bottomMask, new FrameLayout.LayoutParams(sw, sh - bottom));

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(10), dp(14), dp(10));

        TextView hint = new TextView(ctx);
        hint.setText("拖动两条线，框住题干和选项");
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
    }

    private TextView makeHandle(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        tv.setPadding(0, 0, dp(14), 0);
        tv.setBackgroundColor(0xFF4D7CFE);
        return tv;
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
        positionLine(lineTop, top - dp(17));
        positionLine(lineBottom, bottom - dp(17));
        FrameLayout.LayoutParams tp = (FrameLayout.LayoutParams) topMask.getLayoutParams();
        tp.height = Math.max(1, top);
        topMask.setLayoutParams(tp);
        FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) bottomMask.getLayoutParams();
        bp.height = Math.max(1, sh - bottom);
        bp.topMargin = bottom;
        bottomMask.setLayoutParams(bp);
    }

    private void positionLine(View v, int y) {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
        // 布局还没测量时 v.getHeight() 是 0，用固定高度算，避免线跑到屏幕外
        p.topMargin = Math.max(0, Math.min(sh - dp(34), y));
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
}
