package com.dsh.aianswer;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 视觉答题：把截图**直接**交给视觉模型，让它读题并给答案，完全绕开 OCR。
 *
 * <p>为什么要有这条路：OCR 有两类问题补不掉 ——
 * 形近字误认（磨损→唐损、更换→更焕）和数学符号误识（≤ 被认成 K/公/不）。
 * 视觉模型是端到端理解图像，这两类它天然处理得更好；而且用户手动截图时
 * 可以只截题目区域，广告、会员横幅、进度条根本不进画面。
 *
 * <p>默认用智谱 GLM-4V-Flash（免费，和文本模型共用一个 Key）。
 */
public final class VisionSolver {

    private static final String TAG = "AIQ";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 20000;   // 关掉思考后 2~4 秒就该回来，卡住就别干等
    private static final int MAX_SIDE = 1400;      // 够看清符号，又不至于把 token 撑爆
    private static final int JPEG_QUALITY = 78;         // 文字仍然清晰，上传体积少三成

    /**
     * 只读题、不判断答案。
     *
     * <p>为什么要拆开：视觉模型看着图答，比纯文本模型答更容易错 —— 它要同时做
     * 「认字」和「判断」两件事，遇到「报废依据是哪个国标」这种概念题就会凭直觉挑。
     * 让视觉只做它擅长的（把图读成字），判断交给题库和文本模型，两层都更准，
     * 而且**你导入的题库终于能参与**（之前视觉直接给答案，把题库整个绕过了）。
     */
    private static final String PROMPT_READ =
            "这是一道选择题的截图。请只做两件事：\n"
            + "1. 读出题干。忽略广告、会员推广、底部统计、页码进度（如 41/112）、"
            + "「单选/多选」标签和手机状态栏，这些都不是题目内容。\n"
            + "2. 读出每个选项的字母和文字，**原样抄写**，符号不要改（≤ ≥ ± ℃ mm 等）。\n"
            + "不要判断答案。只输出 JSON，不要解释，不要 markdown 代码块：\n"
            + "{\"q\":\"题干\",\"type\":\"单选或多选或判断\","
            + "\"opts\":[{\"l\":\"A\",\"t\":\"选项文字\",\"box\":[x1,y1,x2,y2]}]}\n"
            + "type 按页面上标注的题型填：有「多选」「不定项」字样填 多选；有「判断」填 判断；其余填 单选。\n"
            + "box 是该选项**整行**（字母 + 全部文字）的矩形，用 0 到 1000 的相对坐标，原点左上角；不确定可以省略。";

    private static final String PROMPT =
            "这是一道选择题的截图。请完成三件事：\n"
            + "1. 读出题干。忽略广告、会员推广、底部统计、页码进度（如 41/112）、"
            + "「单选/多选」标签和手机状态栏，这些都不是题目内容。\n"
            + "2. 读出每个选项的字母和文字，**符号原样保留**（≤ ≥ ± ℃ mm 等）。\n"
            + "3. 判断正确答案。\n"
            + "只输出 JSON，不要解释，不要 markdown 代码块：\n"
            + "{\"q\":\"题干\",\"opts\":[{\"l\":\"A\",\"t\":\"选项文字\",\"box\":[x1,y1,x2,y2]}],"
            + "\"ans\":\"B\",\"why\":\"20字内的依据\"}\n"
            + "box 是该选项**整行**（字母 + 全部文字）在图片中的矩形，用 0 到 1000 的相对坐标，原点左上角；不确定可以省略。\n"
            + "如果是多选题，ans 写成字母组合如 \"ABD\"。";

    private VisionSolver() { }

    public static final class Option {
        public String letter = "";
        public String text = "";
        public int[] box;      // 屏幕像素坐标，可能为 null
    }

    public static final class Result {
        public String question = "";
        public String type = "";               // 单选 / 多选 / 判断（模型从页面标签读出来的）
        public final List<Option> options = new ArrayList<>();
        public String answer = "";
        public String why = "";
        public String error = null;
        public String source = "";     // 实际用的哪家厂商
        public boolean cropped = false;// 这次是不是只发了题目区域
        public long ms = 0;
        public String raw = "";
    }

    /**
     * 挑一家用来做视觉答题：**优先用户在设置里选中的那家**；
     * 它没有视觉能力（比如 DeepSeek）时，才顺延到其他已配置且支持视觉的厂商。
     */
    private static CloudSolver.Provider pick(Context ctx) {
        CloudSolver.Provider cur = Prefs.provider(ctx);
        if (cur != null && cur.hasVision() && !Prefs.key(ctx, cur).isEmpty()) return cur;
        for (CloudSolver.Provider p : Prefs.cloudReadyList(ctx)) {
            if (p.hasVision()) return p;
        }
        return null;
    }

    public static boolean available(Context ctx) { return pick(ctx) != null; }

    /** 同步调用，调用方必须放在工作线程。 */
    public static Result ask(Context ctx, Bitmap bmp) { return ask(ctx, bmp, ""); }

    /** 只读题、不判断答案：题干 + 选项（+坐标），交给后面的题库/文本模型。 */
    public static Result read(Context ctx, Bitmap bmp, String pkg) {
        return run(ctx, bmp, pkg, true);
    }

    /**
     * @param pkg 当前页面包名，用来缓存「题目区域」；传空则一律整屏
     */
    public static Result ask(Context ctx, Bitmap bmp, String pkg) {
        return run(ctx, bmp, pkg, false);
    }

    private static Result run(Context ctx, Bitmap bmp, String pkg, boolean readOnly) {
        Result r = new Result();
        long t0 = System.currentTimeMillis();
        CloudSolver.Provider p = pick(ctx);
        if (p == null) {
            r.error = "当前没有支持视觉的在线模型"
                    + "：去设置 › 在线模型里填「DeepSeek / 智谱 / 千帆 / 硅基流动」的 Key";
            return r;
        }
        String key = Prefs.key(ctx, p);
        if (key == null || key.trim().isEmpty()) {
            r.error = p.label + " 没填 Key";
            return r;
        }
        String base = CloudSolver.CUSTOM.id.equals(p.id) ? Prefs.baseCustom(ctx) : p.base;
        if (base == null || base.trim().isEmpty()) {
            r.error = p.label + " 没填 Base URL";
            return r;
        }
        r.source = p.label.replaceAll("（.*$", "");

        // 有缓存就只发「题干 + 选项」那一块：整屏里大半个屏幕是广告和会员条，白花钱还拖时间
        Crop crop = loadCrop(ctx, pkg, bmp.getWidth(), bmp.getHeight());
        Bitmap send = bmp;
        int ox = 0, oy = 0;
        if (crop != null) {
            Log.i(TAG, "题目区域命中 " + crop.l + "," + crop.t + " → "
                    + (crop.l + crop.w) + "," + (crop.t + crop.h));
            try {
                send = Bitmap.createBitmap(bmp, crop.l, crop.t, crop.w, crop.h);
                ox = crop.l;
                oy = crop.t;
                r.cropped = true;
            } catch (Throwable t) {
                send = bmp;
                crop = null;
                ox = oy = 0;
            }
        }

        try {
            r = request(ctx, p, base, key, send, ox, oy, r, readOnly);

            // 这道题可能比校准区域更高（选项折行、题干很长）：内容贴边就说明被切了，
            // 立刻用「上下各放宽一截」的区域重发一次。只在贴边时才多花这一次调用。
            if (crop != null && r.error == null && hitEdge(crop, r)) {
                Log.i(TAG, "内容贴住裁剪边，疑似被切，扩展区域重发一次");
                Crop big = expand(crop, bmp.getHeight());
                if (big != null) {
                    Bitmap cut2 = null;
                    try {
                        cut2 = Bitmap.createBitmap(bmp, big.l, big.t, big.w, big.h);
                        Result r2 = new Result();
                        r2.source = r.source;
                        r2 = request(ctx, p, base, key, cut2, big.l, big.t, r2, readOnly);
                        if (r2.error == null) {
                            r2.cropped = true;
                            r = r2;
                            Log.i(TAG, "扩展后识别成功：" + r2.options.size() + " 个选项");
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        try { if (cut2 != null && cut2 != bmp) cut2.recycle(); } catch (Throwable ignored) { }
                    }
                }
            }

            if (r.error != null && crop != null) {
                // 裁剪失败先不清缓存：可能只是网络抖动或模型抽风，一次失败就把用户的
                // 校准冲掉太伤（实测就是这么丢的）。连续两次失败才认定区域真的不行。
                Log.i(TAG, "裁剪区识别失败（第 " + (cropFailStreak + 1) + " 次）：" + r.error);
                cropFailStreak++;
                if (cropFailStreak >= 2) {
                    Log.i(TAG, "连续 " + cropFailStreak + " 次裁剪失败，区域可能真的不对，清掉等重新校准");
                    Prefs.setVisionCrop(ctx, "");
                    Prefs.setVisionCropManual(ctx, false);
                    cropFailStreak = 0;
                }
                Result r2 = new Result();
                r2.source = r.source;
                r2 = request(ctx, p, base, key, bmp, 0, 0, r2, readOnly);
                r2.cropped = false;
                r = r2;
            } else if (crop != null && r.error == null) {
                cropFailStreak = 0;      // 这次裁剪是好的
            } else if (crop == null && r.error == null && pkg != null && !pkg.isEmpty()) {
                // 只有自动路径才更新区域；手动「识别」是整屏核对，不能把校准值覆盖掉
                saveCrop(ctx, pkg, bmp.getWidth(), bmp.getHeight(), r);
            }
        } finally {
            if (send != bmp) {
                try { send.recycle(); } catch (Throwable ignored) { }
            }
        }
        r.ms = System.currentTimeMillis() - t0;
        return r;
    }

    /** 真正发请求 + 解析。ox/oy 是这张图相对整屏的偏移，用来把选项坐标还原成屏幕坐标。 */
    private static Result request(Context ctx, CloudSolver.Provider p, String base, String key,
                                  Bitmap src, int ox, int oy, Result r, boolean readOnly) {
        try {
            byte[] jpg = toJpeg(src);
            String b64 = android.util.Base64.encodeToString(jpg, android.util.Base64.NO_WRAP);
            // 调试：把「实际发给模型的那张图」留一份，方便核对裁剪到哪了（覆盖写，只留一张）
            saveLast(ctx, jpg, (ox > 0 || oy > 0) ? "auto" : "manual",
                    src.getWidth(), src.getHeight(), ox, oy);
            JSONObject body = new JSONObject();
            body.put("model", p.visionModel);
            body.put("temperature", 0.01);

            /*
             * 思考模式：**开，但只给 low**。
             *
             * 关掉思考时单题 1.3 秒，但遇到「主驱动链报废依据是哪个国标」这种概念辨析题会选错 ——
             * 实测它挑了 GB16899（扶梯安全规范，更知名），而正确答案是 GB/T 31821（报废技术条件）。
             * 这类题不需要长篇推理，只需要「想清楚问的是什么」，low 就够，代价约 1 秒。
             *
             * 注意：思考 token 计入 max_tokens，所以开着思考时必须把额度提上去，
             * 否则额度被思考吃光、正文为空（仍然计费）。
             */
            String pid = p.id == null ? "" : p.id.toLowerCase();
            boolean think = pid.contains("deepseek");
            if (readOnly) {
                // 只是抄字，不需要思考；额度给够题干 + 四个选项 + 坐标即可
                body.put("max_tokens", 700);
                if (think) {
                    body.put("thinking", new JSONObject().put("type", "disabled"));
                    body.put("reasoning_effort", "none");
                }
            } else if (think) {
                body.put("thinking", new JSONObject().put("type", "enabled"));
                body.put("reasoning_effort", "low");
                body.put("max_tokens", 1400);
            } else {
                body.put("max_tokens", 400);
            }

            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text", readOnly ? PROMPT_READ : PROMPT));
            content.put(new JSONObject().put("type", "image_url")
                    .put("image_url", new JSONObject().put("url", "data:image/jpeg;base64," + b64)));
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "user").put("content", content));
            body.put("messages", msgs);

            long tNet = System.currentTimeMillis();
            String resp = post(base, key, body.toString());
            Log.i(TAG, "视觉请求 " + (System.currentTimeMillis() - tNet) + "ms · 图 "
                    + (b64.length() / 1024) + "KB · " + src.getWidth() + "x" + src.getHeight()
                    + ((ox > 0 || oy > 0) ? "（裁剪）" : "（整屏）")
                    + " · 思考=" + (pid.contains("deepseek") ? "low" : "无此参数"));
            JSONObject msg = new JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message");
            String text = msg.optString("content", "");
            if (text == null || text.trim().isEmpty()) text = msg.optString("reasoning_content", "");
            r.raw = text;
            saveRaw(ctx, text, ox > 0 || oy > 0);
            parse(text, src.getWidth(), src.getHeight(), ox, oy, r);
            if (!readOnly && r.answer.isEmpty() && r.error == null) r.error = "模型没给出答案";
            if (readOnly && r.question.isEmpty() && r.error == null) r.error = "模型没读出题干";
        } catch (HttpError he) {
            r.error = he.getMessage();
            Log.w(TAG, "视觉答题失败 " + he.code + " " + he.getMessage());
        } catch (Throwable t) {
            r.error = "视觉答题异常：" + t.getClass().getSimpleName();
            Log.w(TAG, "视觉答题异常", t);
        }
        return r;
    }

    /** 内容有没有贴住裁剪框的边缘：贴边 = 下面/上面还有东西被切掉了。 */
    private static boolean hitEdge(Crop c, Result r) {
        if (r.options.isEmpty()) return true;
        int top = Integer.MAX_VALUE, bottom = -1;
        for (Option o : r.options) {
            if (o.box == null || o.box.length != 4) continue;
            top = Math.min(top, o.box[1]);
            bottom = Math.max(bottom, o.box[3]);
        }
        if (bottom < 0) return false;
        int cBottom = c.t + c.h;
        if (bottom >= cBottom - c.h * 0.06f) return true;      // 最后一个选项贴底
        if (top <= c.t + c.h * 0.04f) return true;             // 第一个选项贴顶（题干可能被切）
        return false;
    }

    /** 上下各放一截，用来兜住「比校准区域更高的题」。 */
    private static Crop expand(Crop c, int sh) {
        int nt = Math.max(0, c.t - (int) (c.h * 0.15f));
        int nb = Math.min(sh, c.t + c.h + (int) (c.h * 0.55f));
        if (nb - nt <= c.h) return null;
        return new Crop(c.l, nt, c.l + c.w, nb);
    }

    // ---------------- 题目区域缓存 ----------------

    /** 裁剪连续失败的次数：单次失败不清缓存（可能是抖动），连续两次才清。 */
    private static int cropFailStreak = 0;

    private static final class Crop {
        final int l, t, w, h;
        Crop(int l, int t, int r, int b) {
            this.l = l; this.t = t;
            this.w = Math.max(1, r - l);
            this.h = Math.max(1, b - t);
        }
    }

    /** 取缓存：包名或屏幕尺寸对不上就不用（换 App、旋转、改分辨率都会失效）。 */
    private static Crop loadCrop(Context ctx, String pkg, int sw, int sh) {
        if (pkg == null || pkg.isEmpty() || sw <= 0 || sh <= 0) return null;
        String s = Prefs.visionCrop(ctx);
        if (s == null || s.isEmpty()) {
            Log.i(TAG, "题目区域：没存过");
            return null;
        }
        try {
            JSONObject o = new JSONObject(s);
            String cp = o.optString("pkg");
            // 空包名 = 用户手动校准的「通用区域」，对所有 App 生效
            if (cp != null && !cp.isEmpty() && !cp.equals(pkg)) {
                Log.i(TAG, "题目区域：包名不匹配（存的是 " + cp + "，当前 " + pkg + "）");
                return null;
            }
            int ssw = o.optInt("sw"), ssh = o.optInt("sh");
            int l = o.optInt("l"), t = o.optInt("t"), r = o.optInt("r"), b = o.optInt("b");
            // 宽度必须对得上
            if (ssw <= 0 || Math.abs(ssw - sw) > sw * 0.03f) {
                Log.i(TAG, "题目区域：屏宽不匹配（存 " + ssw + "，当前 " + sw + "）");
                return null;
            }
            // 高度允许 6% 误差并按比例换算：显示尺寸和截图尺寸常常差一个导航栏的高度
            if (ssh > 0 && Math.abs(ssh - sh) > sh * 0.06f) {
                Log.i(TAG, "题目区域：屏高差太多（存 " + ssh + "，当前 " + sh + "）");
                return null;
            }
            if (ssh > 0 && ssh != sh) {
                float k = (float) sh / ssh;
                t = Math.round(t * k);
                b = Math.round(b * k);
            }
            if (r - l < sw * 0.95f) {
                Log.i(TAG, "题目区域：宽度不够（" + (r - l) + " / " + sw + "）");
                return null;
            }
            if (b - t < sh * 0.15f) {
                Log.i(TAG, "题目区域：高度不够（" + (b - t) + " / " + sh + "）");
                return null;
            }
            return new Crop(Math.max(0, l), Math.max(0, t),
                    Math.min(sw, r), Math.min(sh, b));
        } catch (Throwable t) {
            Log.i(TAG, "题目区域：解析失败 " + t.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 用模型自己返回的选项坐标反推「题干 + 选项」所在矩形。
     *
     * <p>为什么不用 OCR 定位：OCR 恰恰在这种自绘页面上读不到字，才轮到视觉模型出场；
     * 而视觉模型本来就要把每个选项的框给回来（用于点击），顺手就能算出题目在哪。
     */
    private static void saveCrop(Context ctx, String pkg, int sw, int sh, Result r) {
        if (pkg == null || pkg.isEmpty()) return;
        if (Prefs.visionCropManual(ctx)) return;   // 用户校准过的区域，自动推断不许覆盖
        int l = Integer.MAX_VALUE, t = Integer.MAX_VALUE, rr = -1, b = -1;
        for (Option o : r.options) {
            if (o.box == null || o.box.length != 4) continue;
            l = Math.min(l, o.box[0]);
            t = Math.min(t, o.box[1]);
            rr = Math.max(rr, o.box[2]);
            b = Math.max(b, o.box[3]);
        }
        if (rr <= l || b <= t) return;                        // 模型没给坐标，没法裁

        int optH = b - t;
        int up   = Math.max((int) (sh * 0.20f), (int) (optH * 1.15f));  // 题干在选项上方
        int down = Math.max((int) (sh * 0.06f), (int) (optH * 0.30f));  // 多选/提交按钮留一点

        // 宽度一律取满屏：模型返回的横向框常常只框住「A」那个字母圈，
        // 实测按它算宽度会裁成 371px 的竖条，选项文字整条被切掉。
        // 只有纵向（顶部广告、底部会员条）是真正可以砍掉的部分。
        int nl = 0, nr = sw;
        int nt = Math.max(0, t - up), nb = Math.min(sh, b + down);
        if (nb - nt < sh * 0.15f) return;
        // 跟整屏差不多大就不裁了，省不下多少还多一次 Bitmap 拷贝
        if ((long) (nr - nl) * (nb - nt) > (long) sw * sh * 92 / 100) return;

        try {
            JSONObject o = new JSONObject();
            o.put("pkg", pkg);
            o.put("sw", sw);
            o.put("sh", sh);
            o.put("l", nl); o.put("t", nt); o.put("r", nr); o.put("b", nb);
            Prefs.setVisionCrop(ctx, o.toString());
            Log.i(TAG, "记住题目区域 " + (nr - nl) + "x" + (nb - nt)
                    + "（整屏 " + sw + "x" + sh + "）");
        } catch (Throwable ignored) { }
    }

    private static void parse(String text, int w, int h, int ox, int oy, Result r) {
        if (text == null) {
            r.error = "空返回";
            return;
        }
        int a = text.indexOf('{');
        int b = text.lastIndexOf('}');
        if (a < 0 || b <= a) {
            r.error = "模型没按格式返回";
            return;
        }
        try {
            JSONObject o = new JSONObject(text.substring(a, b + 1));
            r.question = o.optString("q", "").trim();
            r.type = o.optString("type", "").trim();
            r.answer = o.optString("ans", "").toUpperCase().replaceAll("[^A-E]", "");
            r.why = o.optString("why", "").trim();
            JSONArray arr = o.optJSONArray("opts");
            if (arr == null) arr = o.optJSONArray("options");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.optJSONObject(i);
                    if (e == null) continue;
                    Option op = new Option();
                    op.letter = e.optString("l", e.optString("letter", "")).trim().toUpperCase();
                    op.text = e.optString("t", e.optString("text", "")).trim();
                    if (op.letter.isEmpty() || op.text.isEmpty()) continue;
                    JSONArray bx = e.optJSONArray("box");
                    if (bx != null && bx.length() == 4) {
                        int l = (int) (bx.optDouble(0) / 1000.0 * w);
                        int t = (int) (bx.optDouble(1) / 1000.0 * h);
                        int rr = (int) (bx.optDouble(2) / 1000.0 * w);
                        int bb = (int) (bx.optDouble(3) / 1000.0 * h);
                        // 加上裁剪偏移，还原成整屏坐标（点击要用屏幕坐标）
                        if (rr > l && bb > t) op.box = new int[]{l + ox, t + oy, rr + ox, bb + oy};
                    }
                    r.options.add(op);
                }
            }
        } catch (Throwable t) {
            r.error = "返回内容不是合法 JSON";
        }
    }

    /** 调试用：模型原始返回（含思考内容），出错时能看清它把哪一步想歪了。 */
    private static void saveRaw(Context ctx, String text, boolean cropped) {
        try {
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            java.io.FileOutputStream fo =
                    new java.io.FileOutputStream(new java.io.File(dir, "vision_raw.txt"));
            fo.write(("cropped=" + cropped + "\n" + (text == null ? "" : text)).getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignored) { }
    }

    /** 调试用：把最近一次真正发出去的图存到 /sdcard/Android/data/<pkg>/files/（覆盖写，只留一张）。 */
    private static void saveLast(Context ctx, byte[] jpg, String tag, int w, int h, int ox, int oy) {
        try {
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            java.io.FileOutputStream fo =
                    new java.io.FileOutputStream(new java.io.File(dir, "vision_" + tag + ".jpg"));
            fo.write(jpg);
            fo.close();
            StringBuilder sb = new StringBuilder();
            sb.append("tag=").append(tag)
              .append(" size=").append(w).append('x').append(h)
              .append(" offset=").append(ox).append(',').append(oy)
              .append(" bytes=").append(jpg.length)
              .append(" time=").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                      java.util.Locale.US).format(new java.util.Date()));
            java.io.FileOutputStream mo =
                    new java.io.FileOutputStream(new java.io.File(dir, "vision_" + tag + ".txt"));
            mo.write(sb.toString().getBytes("UTF-8"));
            mo.close();
        } catch (Throwable ignored) { }
    }

    private static byte[] toJpeg(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(w, h));
        Bitmap s = src;
        if (scale < 1f) s = Bitmap.createScaledBitmap(src, Math.round(w * scale), Math.round(h * scale), true);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        s.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bo);
        if (s != src) s.recycle();
        return bo.toByteArray();
    }

    private static String post(String base, String key, String json) throws Exception {
        String url = base.endsWith("/") ? base + "chat/completions" : base + "/chat/completions";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "AIAnswer/3.0 (Android)");

        OutputStream os = c.getOutputStream();
        os.write(json.getBytes(StandardCharsets.UTF_8));
        os.close();

        int code = c.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        String text = readAll(is);
        c.disconnect();
        if (code < 200 || code >= 300) {
            Log.w(TAG, "视觉 HTTP " + code + " body=" + shorten(text));
            throw new HttpError(code, explain(code, text));
        }
        return text;
    }

    private static String explain(int code, String body) {
        if (body != null && body.contains("1113")) return "智谱余额不足（1113）";
        switch (code) {
            case 401: return "智谱 Key 无效（401）";
            case 404: return "视觉模型不存在（404）";
            case 429: return "智谱限流或过载（429）· 稍等再点";
            case 400: return "请求被拒（400）：" + shorten(body);
            default:  return "HTTP " + code + "：" + shorten(body);
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String shorten(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    static final class HttpError extends Exception {
        final int code;
        HttpError(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }
}
