package com.dsh.aianswer;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.util.Log;
import android.view.Display;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地图片识别兜底：无障碍读不到题目时（小程序 Canvas / 自绘页面），截屏在本机认字。
 *
 * <p><b>全程离线</b>：ML Kit 中文文字识别用的是打包进 APK 的模型，不联网、不传截图、不产生调用费用。
 * 代价是安装包大约多 20MB。
 *
 * <p>截屏用 {@link AccessibilityService#takeScreenshot}（Android 11+），
 * 不需要 MediaProjection 授权，也不会弹录屏确认框。
 *
 * <p>只做「把图变成一行行文字 + 坐标」这一件事，认出来之后交给
 * {@link Layout#parse} 走和读屏完全相同的版面解析，所以题库、记忆库、路由、投票全都照常生效。
 */
public final class OcrEngine {

    private static final String TAG = "AIQ";

    /**
     * 超过这个长边才缩小。
     *
     * <p>这里踩过坑：原来写 2000，而手机截图是 1280×2772，会被缩到 72% ——
     * 题目正文本来字号就小，一缩直接糊，选项里的「2.5mm」「≤」这类字符最先认错。
     * 现在放到 4000（等于常见的 1080P / 2K 屏都不缩），只在超长截图上才等比缩小。
     */
    private static final int MAX_SIDE = 4000;
    private static final int TIMEOUT_SEC = 25;

    private static volatile TextRecognizer client;

    private OcrEngine() { }

    /** 截图方案要求 Android 11（API 30）以上。 */
    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 30;
    }

    /** 识别出来的一行文字，坐标已换算回屏幕像素。 */
    public static final class Line {
        public String text = "";
        public int left, top, right, bottom;
    }

    public static final class Hit {
        public final List<Line> lines = new ArrayList<>();
        public String error = null;
        public String raw = "";        // 全文，排查用
        public long ms = 0;
    }

    public interface Callback {
        void onBitmap(Bitmap bmp, String error);
    }

    /** 截当前屏幕。回调在主线程。 */
    public static void captureAsync(final AccessibilityService svc, final Callback cb) {
        capture(svc, cb, 0);
    }

    /**
     * @param attempt 已经重试过几次
     *
     * <p>系统的 takeScreenshot 有频率上限（约 1 秒 1 次），连点「识别」必然撞上。
     * 撞上就等 1.2 秒自动再来一次，别让用户看到「截图失败」。
     */
    private static void capture(final AccessibilityService svc, final Callback cb, final int attempt) {
        if (!supported()) {
            cb.onBitmap(null, "系统版本低于 Android 11，不支持截图识别");
            return;
        }
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        public void onSuccess(AccessibilityService.ScreenshotResult r) {
                            Bitmap out = null;
                            String err = null;
                            HardwareBuffer hb = null;
                            try {
                                hb = r.getHardwareBuffer();
                                Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                                if (hw != null) out = hw.copy(Bitmap.Config.ARGB_8888, false);
                                if (out == null) err = "截图解码失败";
                            } catch (Throwable t) {
                                err = "截图解码异常：" + t.getClass().getSimpleName();
                            } finally {
                                try { if (hb != null) hb.close(); } catch (Throwable ignored) { }
                            }
                            cb.onBitmap(out, err);
                        }

                        public void onFailure(int errorCode) {
                            // 1 = 内部错误；2 = 没有无障碍截图权限；3 = 两次截图间隔太短（限流）；4 = 无效显示
                            Log.w(TAG, "截图失败 码=" + errorCode + " 第 " + (attempt + 1) + " 次");
                            if (attempt < 2 && (errorCode == 1 || errorCode == 3)) {
                                new android.os.Handler(android.os.Looper.getMainLooper())
                                        .postDelayed(new Runnable() {
                                            public void run() { capture(svc, cb, attempt + 1); }
                                        }, 1200);
                                return;
                            }
                            if (errorCode == 3) {
                                cb.onBitmap(null, "截图太频繁被系统限流 · 歇一秒再点");
                            } else if (errorCode == 2) {
                                cb.onBitmap(null, "系统没给截图权限 · 把「AI 答题」无障碍关一次再开");
                            } else {
                                cb.onBitmap(null, "截图失败（错误码 " + errorCode + "）");
                            }
                        }
                    });
        } catch (Throwable t) {
            cb.onBitmap(null, "截图异常：" + t.getClass().getSimpleName());
        }
    }

    /**
     * 识别截图里的文字。同步方法，调用方自己放到工作线程。
     * 返回的每一行都带屏幕坐标，顺序已按视觉从上到下排好。
     */
    /**
     * 识别截图里的文字。
     *
     * <p><b>为什么要把图切成横向条带</b>：ML Kit 内部会把输入缩到 ~1024 长边再送网络。
     * 整屏 1280×2772 一次性送进去，等于把字压到三分之一 —— 实测就是这样把
     * 「扶手带」认成「单造]获」、「润滑」认成「润清」的。
     * 切成 1000px 左右高的条带后，条带几乎不被缩放，字保持原尺寸，笔画不再糊在一起。
     *
     * <p>条带之间留 80px 重叠，避免正好把一行切断；重叠区的重复行在 {@link #dedupe} 里去掉。
     * 坐标会加回条带的纵向偏移，对上层完全透明。
     */
    public static Hit recognize(Context ctx, Bitmap bmp) {
        Hit h = new Hit();
        long t0 = System.currentTimeMillis();
        try {
            List<Line> lines = new ArrayList<>();
            int w = bmp.getWidth();
            int hh = bmp.getHeight();
            // 每条不超过 ML Kit 的输入上限（约 1024），这样任何分辨率下字都不会被额外压缩。
            // 写死「每条 1000px」在 720p 上会切出过窄的条、在 2K 上又不够，所以按比例算。
            int bands = Math.max(1, Math.min(4, (int) Math.ceil(hh / 1024.0)));
            final int overlap = Math.max(40, Math.round(hh * 0.03f));

            for (int i = 0; i < bands; i++) {
                int top = i * hh / bands - (i > 0 ? overlap : 0);
                int bottom = (i + 1) * hh / bands + (i < bands - 1 ? overlap : 0);
                top = Math.max(0, top);
                bottom = Math.min(hh, bottom);
                if (bottom - top < 60) continue;

                Bitmap band = Bitmap.createBitmap(bmp, 0, top, w, bottom - top);
                try {
                    Text text = Tasks.await(client().process(InputImage.fromBitmap(band, 0)),
                            TIMEOUT_SEC, TimeUnit.SECONDS);
                    collect(text, top, lines);
                } finally {
                    try { band.recycle(); } catch (Throwable ignored) { }
                }
            }

            dedupe(lines, w, hh);
            rescueShortLines(bmp, lines);
            h.lines.addAll(lines);
            StringBuilder sb = new StringBuilder();
            for (Line l : h.lines) sb.append(l.text).append('\n');
            h.raw = sb.toString();
            if (h.lines.isEmpty()) h.error = "截图里没识别到文字";
        } catch (Throwable t) {
            h.error = "本地识别失败：" + t.getClass().getSimpleName();
            Log.w(TAG, "本地识别异常", t);
        }
        h.ms = System.currentTimeMillis() - t0;
        return h;
    }

    /**
     * 补漏：**框很宽、却只认出两三个字**的行，几乎一定是漏识别。
     *
     * <p>实测「A 齿顶磨损&lt;2.5mm」整行只认出一个「A t」——框宽近千像素，文字却只有两三个字符，
     * 这种行单独裁出来、放大 4 倍再认一次，通常能把整行补回来。
     *
     * <p>判据用几何而不是内容：只有「框宽 &gt; 250px 且去空白后 ≤ 3 字」才触发，
     * 所以「A 5年」这种本来就很短的选项不会被误伤（它的框只有一百多像素宽）。
     */
    private static void rescueShortLines(Bitmap full, List<Line> lines) {
        for (Line l : lines) {
            if (l.text == null) continue;
            String compact = l.text.replaceAll("\\s", "");
            if (compact.length() > 3) continue;
            // 宽度阈值按屏幕比例，不写死 250（720p 屏的整行可能都不到 250）
            int wideBox = Math.max(100, Math.round(full.getWidth() * 0.19f));
            if (l.right - l.left < wideBox) continue;
            int top = Math.max(0, l.top - 8);
            int bottom = Math.min(full.getHeight(), l.bottom + 8);
            int left = Math.max(0, l.left - 8);
            int right = Math.min(full.getWidth(), l.right + 8);
            if (right - left < 40 || bottom - top < 20) continue;

            Bitmap crop = null, big = null;
            try {
                crop = Bitmap.createBitmap(full, left, top, right - left, bottom - top);
                big = Bitmap.createScaledBitmap(crop, (right - left) * 4, (bottom - top) * 4, true);
                Text t = Tasks.await(client().process(InputImage.fromBitmap(big, 0)),
                        10, TimeUnit.SECONDS);
                String best = "";
                for (Text.TextBlock b : t.getTextBlocks()) {
                    for (Text.Line ln : b.getLines()) {
                        String s = ln.getText();
                        if (s == null) continue;
                        s = s.replace('\n', ' ').trim();
                        if (s.length() > best.length()) best = s;
                    }
                }
                if (best.length() > compact.length()) {
                    Log.i(TAG, "补漏识别：" + compact + " → " + best);
                    l.text = best;
                }
            } catch (Throwable ignored) {
            } finally {
                try { if (big != null && big != crop) big.recycle(); } catch (Throwable ignored) { }
                try { if (crop != null) crop.recycle(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 去掉重叠区里重复的行（位置几乎重合就算同一条）。 */
    private static void dedupe(List<Line> lines, int w, int h) {
        // 容差按屏幕比例：写死 25/60px 在 720p 上会误合并相邻行，在 2K 上又去不掉重复
        final int vTol = Math.max(10, Math.round(h * 0.010f));
        final int hTol = Math.max(20, Math.round(w * 0.050f));
        Collections.sort(lines, new Comparator<Line>() {
            public int compare(Line a, Line b) {
                if (a.top != b.top) return a.top < b.top ? -1 : 1;
                return a.left < b.left ? -1 : (a.left == b.left ? 0 : 1);
            }
        });
        List<Line> out = new ArrayList<>();
        for (Line l : lines) {
            boolean dup = false;
            for (int i = out.size() - 1; i >= 0 && i >= out.size() - 8; i--) {
                Line o = out.get(i);
                if (Math.abs(o.top - l.top) <= vTol && Math.abs(o.left - l.left) <= hTol) {
                    dup = true;
                    break;
                }
            }
            if (!dup) out.add(l);
        }
        lines.clear();
        lines.addAll(out);
    }

    /** 首次识别会把模型加载进内存（约几百毫秒），之后复用同一个实例。 */
    private static TextRecognizer client() {
        TextRecognizer c = client;
        if (c == null) {
            synchronized (OcrEngine.class) {
                c = client;
                if (c == null) {
                    c = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                    client = c;
                }
            }
        }
        return c;
    }

    /** 把一条带里的识别结果收进来，坐标加回条带的纵向偏移。 */
    private static void collect(Text text, int yOffset, List<Line> out) {
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String s = line.getText();
                if (s == null) continue;
                s = s.replace('\n', ' ').trim();
                if (s.isEmpty()) continue;
                Line l = new Line();
                l.text = s;
                Rect r = line.getBoundingBox();
                if (r != null) {
                    l.left   = r.left;
                    l.top    = r.top + yOffset;
                    l.right  = r.right;
                    l.bottom = r.bottom + yOffset;
                }
                out.add(l);
            }
        }
    }
}
