package com.dsh.aianswer;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GGUF 模型的下载（Range 断点续传）与本地管理。模型不进 APK，首次使用按需拉取。
 *
 * 注意 bytes 必须是**精确字节数**（ModelScope 文件列表里的 Size 字段）。
 * v1.0 曾把十进制的 "491.4 MB" 当成 491 MiB 乘 1024×1024，阈值比真实文件大 5%，
 * 导致下载完成的文件永远被判成"没下完"——别再用 MB 数去乘了。
 */
public final class ModelManager {

    public static final class Spec {
        public final String id;
        public final String name;     // 均衡版
        public final String badge;    // 1.5B（卡片左侧圆标）
        public final String brief;    // 一句话说明，帮用户快速做决定
        public final String url;
        public final long bytes;      // 精确字节数

        Spec(String id, String name, String badge, String brief, String url, long bytes) {
            this.id = id; this.name = name; this.badge = badge;
            this.brief = brief; this.url = url; this.bytes = bytes;
        }
    }

    private static final String BASE = "https://www.modelscope.cn/models/Qwen/";

    public static final Spec SMALL = new Spec(
            "qwen2.5-0.5b-q4km", "轻量版", "0.5B", "最快，实测 80% / 70%，容易答错",
            BASE + "Qwen2.5-0.5B-Instruct-GGUF/resolve/master/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            491400032L);

    public static final Spec LARGE = new Spec(
            "qwen2.5-1.5b-q4km", "均衡版", "1.5B", "实测 100% / 90%，速度与准确率最优",
            BASE + "Qwen2.5-1.5B-Instruct-GGUF/resolve/master/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            1117320736L);

    /**
     * Qwen3-4B-Instruct-2507（unsloth 转的 GGUF）。
     * 选它而不是 Qwen3-4B 原版：2507 是**非思考**版本，不会先吐  thinking 再回答，
     * 与现有「prompt 停在答案：、只比字母 logits」的打分方式完全兼容，模板不用改。
     * 2.33 GB，是这台机器内存条件下还能稳跑的最大档位之一。
     */
    public static final Spec MID = new Spec(
            "qwen3-4b-2507-q4km", "旗舰版", "4B", "通用知识更强，占 2.3 GB，慢约 2 倍",
            "https://www.modelscope.cn/models/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/master/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
            2497281120L);

    /** 顺序 = 列表顺序，也决定「自动挑一个可用的」时的优先级：先小后大更稳。 */
    public static final Spec[] ALL = {SMALL, LARGE, MID};

    /**
     * 由模型市场清单（assets/models.json）临时构造一个 Spec。
     * 市场里的模型不进 ALL，靠 Prefs.spec() 的兜底查找命中。
     */
    public static Spec make(String id, String name, String badge, String brief,
                            String url, long bytes) {
        return new Spec(id, name, badge, brief, url, bytes);
    }

    /** 低于这个体积肯定不是完整模型，用来兜底放行，避免阈值再算错时把用户卡死。 */
    private static final long MIN_PLAUSIBLE = 200L * 1024 * 1024;

    public interface Progress {
        void onProgress(long done, long total);
    }

    private ModelManager() {}

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "models");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File fileOf(Context ctx, Spec s) {
        return new File(dir(ctx), s.id + ".gguf");
    }

    /**
     * 已下载完成：文件存在且体积落在预期区间（±3%）。
     * 真正的完整性由下载时的 Content-Length 校验保证，这里只做二次确认。
     */
    public static boolean isReady(Context ctx, Spec s) {
        File f = fileOf(ctx, s);
        if (!f.exists()) return false;
        long len = f.length();
        return len >= s.bytes * 97 / 100 && len <= s.bytes * 103 / 100;
    }

    /** 体积对不上但文件明显是个完整大模型时，仍放行加载，宁可让 llama.cpp 自己报错。 */
    public static boolean looksUsable(Context ctx, Spec s) {
        File f = fileOf(ctx, s);
        return f.exists() && f.length() > MIN_PLAUSIBLE;
    }

    public static boolean canLoad(Context ctx, Spec s) {
        return isReady(ctx, s) || looksUsable(ctx, s);
    }

    /** 本地已经下好的档位（按 ALL 顺序）。 */
    public static java.util.List<Spec> readySpecs(Context ctx) {
        java.util.List<Spec> list = new java.util.ArrayList<>();
        for (Spec s : ALL) if (isReady(ctx, s)) list.add(s);
        return list;
    }

    /**
     * 挑一个能直接用的档位：优先 keepId（用户当前选的），否则按 ALL 的推荐顺序取第一个。
     */
    public static Spec pickReady(Context ctx, String keepId) {
        if (keepId != null) {
            for (Spec s : ALL) if (s.id.equals(keepId) && isReady(ctx, s)) return s;
        }
        for (Spec s : ALL) if (isReady(ctx, s)) return s;
        return null;
    }

    /** 按手机内存推荐档位：< 7GB 给 0.5B，否则 1.5B。 */
    public static Spec recommend(Context ctx) {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long gb = mi.totalMem / 1073741824L;
            return gb >= 7 ? LARGE : SMALL;
        } catch (Throwable t) {
            return LARGE;
        }
    }

    public static void delete(Context ctx, Spec s) {
        File f = fileOf(ctx, s);
        if (f.exists()) f.delete();
        File p = new File(f.getAbsolutePath() + ".part");
        if (p.exists()) p.delete();
    }

    public static void download(Context ctx, Spec s, Progress p) throws IOException {
        File dst = fileOf(ctx, s);
        File part = new File(dst.getAbsolutePath() + ".part");
        long have = part.exists() ? part.length() : 0;

        HttpURLConnection c = (HttpURLConnection) new URL(s.url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(40000);
        c.setRequestProperty("User-Agent", "AIAnswer/1.1");
        if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");

        int code = c.getResponseCode();
        if (code != 200 && code != 206) {
            throw new IOException("下载失败 HTTP " + code);
        }

        long total;
        if (code == 206) {
            total = parseTotal(c.getHeaderField("Content-Range"), have);
        } else {
            have = 0;                                  // 服务端不支持续传，从头来
            total = c.getContentLength();
        }
        if (total <= 0) total = s.bytes;

        InputStream in = c.getInputStream();
        RandomAccessFile raf = new RandomAccessFile(part, "rw");
        try {
            raf.seek(have);
            byte[] buf = new byte[256 * 1024];
            long done = have;
            long lastTick = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                raf.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastTick > 250) { lastTick = now; p.onProgress(done, total); }
            }
            p.onProgress(done, total);
            if (done < total - 1024) {
                throw new IOException("下载不完整 " + done + "/" + total);
            }
        } finally {
            try { raf.close(); } catch (Exception ignored) {}
            try { in.close(); } catch (Exception ignored) {}
            c.disconnect();
        }

        if (dst.exists()) dst.delete();
        if (!part.renameTo(dst)) throw new IOException("重命名失败");
    }

    private static long parseTotal(String contentRange, long fallback) {
        if (contentRange == null) return fallback;
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) return fallback;
        try { return Long.parseLong(contentRange.substring(slash + 1).trim()); }
        catch (Exception e) { return fallback; }
    }
}
