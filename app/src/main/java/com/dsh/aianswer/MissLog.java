package com.dsh.aianswer;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 未命中题记录（v7.5）。
 *
 * <p>自选题库和记忆库都没命中、最后靠模型答的题，记下题干 / 选项 / 答案。
 * 用户可以把它们导出来，补进自选题库——下次遇到就是 0 次调用、0 毫秒。
 *
 * <p>每道题只花一次在线调用，这些题反而是最值得沉淀的：说明题库里确实缺它们。
 * 存 JSON Lines 而不是直接存成题库文本，是为了保留选项（题库解析靠选项定位答案文本，
 * 少了选项就只能按字母猜，屏幕选项一换序就点错）。
 */
public final class MissLog {

    private static final String TAG = "AIQ";
    private static final String FILE = "misslog.jsonl";
    private static final int MAX = 500;          // 只留最近 500 道，够补一轮了
    private static final Object LOCK = new Object();

    public static final class Entry {
        public String q = "";
        public List<String> opts = new ArrayList<>();
        public String a = "";
        public long ts = 0;
    }

    private MissLog() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    /** 记一道「模型才答得出来」的题。同题干只保留最新一条。 */
    public static void add(Context c, String q, List<String> optionLines, String letters) {
        if (c == null || q == null) return;
        String stem = q.trim();
        if (stem.isEmpty() || letters == null || letters.isEmpty()) return;
        // 没选项的题导不出去：导入器要靠选项定位答案文本，缺了只能按字母猜。
        // 与其存一条导不出来的记录，不如不存。
        if (optionLines == null || optionLines.size() < 2) return;
        try {
            synchronized (LOCK) {
                List<Entry> list = read(c);
                String key = key(stem);
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (key(list.get(i).q).equals(key)) list.remove(i);
                }
                Entry e = new Entry();
                e.q = stem;
                if (optionLines != null) e.opts = new ArrayList<>(optionLines);
                e.a = letters;
                e.ts = System.currentTimeMillis();
                list.add(e);
                while (list.size() > MAX) list.remove(0);
                write(c, list);
            }
        } catch (Throwable t) {
            Log.w(TAG, "未命中题记录失败", t);       // 记录失败不能影响答题
        }
    }

    public static List<Entry> read(Context c) {
        List<Entry> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try {
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(buf);
            in.close();
            String all = new String(buf, 0, Math.max(0, n), StandardCharsets.UTF_8);
            for (String line : all.split("\n")) {
                Entry e = fromLine(line);
                if (e != null) out.add(e);
            }
        } catch (Throwable t) {
            Log.w(TAG, "未命中题读取失败", t);
        }
        return out;
    }

    public static int count(Context c) {
        return read(c).size();
    }

    public static void clear(Context c) {
        synchronized (LOCK) {
            file(c).delete();
        }
    }

    /**
     * 导出成可直接再导入的题库文本。
     *
     * <p>格式与 {@link TextParser} 吃的格式一致：`1、题干` / `A. 选项` / `答案：B`。
     * 用户拿去「上传题库文件」就能用，不用手工整理。
     */
    public static String exportText(Context c) {
        return exportText(read(c));
    }

    /** 纯函数版本：不碰文件，便于在 JVM 上验证格式。 */
    public static String exportText(List<Entry> list) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Entry e : list) {
            if (e.opts.size() < 2) continue;      // 兜底：老数据里可能有没选项的，别导出脏条目
            sb.append(++i).append('、').append(e.q.replace("\n", " ").trim()).append('\n');
            for (String line : e.opts) {
                sb.append(line == null ? "" : line.trim()).append('\n');
            }
            sb.append("答案：").append(e.a).append("\n\n");
        }
        return sb.toString();
    }

    /** 一行 JSON。 */
    static String toLine(Entry e) throws Exception {
        JSONObject o = new JSONObject();
        o.put("q", e.q);
        o.put("a", e.a);
        o.put("ts", e.ts);
        JSONArray arr = new JSONArray();
        for (String s : e.opts) arr.put(s);
        o.put("o", arr);
        return o.toString();
    }

    /** 坏行返回 null，不让一行残数据毁掉整个文件。 */
    static Entry fromLine(String line) {
        if (line == null) return null;
        line = line.trim();
        if (line.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(line);
            Entry e = new Entry();
            e.q = o.optString("q", "");
            e.a = o.optString("a", "");
            e.ts = o.optLong("ts", 0);
            JSONArray arr = o.optJSONArray("o");
            if (arr != null) for (int i = 0; i < arr.length(); i++) e.opts.add(arr.optString(i, ""));
            return e.q.isEmpty() ? null : e;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 判重用：同一道题在不同页面可能有标点/空格差异。 */
    static String sameKey(String s) {
        return key(s);
    }

    /** 真正能导出几条（跳过没选项的）。 */
    public static int exportableCount(List<Entry> list) {
        int n = 0;
        for (Entry e : list) if (e.opts.size() >= 2) n++;
        return n;
    }

    private static void write(Context c, List<Entry> list) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) sb.append(toLine(e)).append('\n');
        File tmp = new File(c.getFilesDir(), FILE + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.close();
        File dst = file(c);
        if (dst.exists() && !dst.delete()) Log.w(TAG, "旧未命中文件删不掉");
        if (!tmp.renameTo(dst)) {                 // rename 失败就直接覆盖写，别把数据丢了
            FileOutputStream out2 = new FileOutputStream(dst);
            out2.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out2.close();
            tmp.delete();
        }
    }

    /** 判重用：去掉空白和标点，取前 60 字。 */
    private static String key(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\s\\p{Punct}，。？！、；：“”‘’（）《》【】]", "");
        return t.length() > 60 ? t.substring(0, 60) : t;
    }

    /** 调试用：文件大小（字节）。 */
    public static long size(Context c) {
        File f = file(c);
        return f.exists() ? f.length() : 0;
    }

    @SuppressWarnings("unused")
    private static String readAll(File f) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }
}
