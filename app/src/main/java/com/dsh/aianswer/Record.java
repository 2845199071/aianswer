package com.dsh.aianswer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 题库记忆 + 本场记录 + 统计。
 *
 * 题库（bank）：题干签名 → 答案字母。答过的题下次再出现直接命中，0ms、100% 准。
 *   刷的是固定题库，同一道题会反复出现，这条比换任何模型都有效。
 * 本场（session）：按出现顺序记录，用于「复制答案」核对和统计。
 *
 * 都存 SharedPreferences。题库随使用变大，但几千条也就几百 KB，够用。
 */
public final class Record {

    private static final String TAG = "AIQ";
    private static final String SP = "records";
    private static final String K_SESSION = "session";
    private static final String K_BANK = "bank.";        // + md5(sig)
    private static final int MAX_SESSION = 300;          // 本场最多留 300 条，够两三场

    public String q;          // 题干（导出/核对用）
    public String answer;     // "B" 或 "ABD"
    public String source;     // 端侧模型 / 在线 / 题库
    public int conf;          // 0~100
    public long ms;
    public long ts;

    /** 本场统计。 */
    public static final class Stats {
        public int total, bank, local, cloud, low;
        public long sumMs;

        public long avgMs() { return total == 0 ? 0 : sumMs / total; }
        public void reset() { total = bank = local = cloud = low = 0; sumMs = 0; }
    }

    private static final List<Record> session = new ArrayList<>();
    private static final Stats stats = new Stats();
    private static boolean loaded = false;

    private Record() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 首次访问时把上次的session读回内存，让「复制答案」跨进程重启也能用。 */
    private static synchronized void ensureLoaded(Context c) {
        if (loaded) return;
        loaded = true;
        try {
            String raw = sp(c).getString(K_SESSION, "");
            if (raw == null || raw.isEmpty()) return;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Record r = new Record();
                r.q      = o.optString("q", "");
                r.answer = o.optString("a", "");
                r.source = o.optString("s", "");
                r.conf   = o.optInt("c", 0);
                r.ms     = o.optLong("m", 0);
                r.ts     = o.optLong("t", 0);
                session.add(r);
                accumulate(r);
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取本场记录失败", t);
        }
    }

    /** 签名 → 短哈希，避免用整段题干当 key。 */
    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] b = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }

    // ---------------- 题库 ----------------

    /** 题库里有没有这道题；有就返回答案字母。 */
    public static String bankAnswer(Context c, String sig) {
        if (sig == null || sig.isEmpty()) return null;
        String v = sp(c).getString(K_BANK + md5(sig), null);
        return (v == null || v.isEmpty()) ? null : v;
    }

    public static void clearBank(Context c) {
        SharedPreferences p = sp(c);
        SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet()) {
            if (k.startsWith(K_BANK)) e.remove(k);
        }
        e.apply();
    }

    public static int bankSize(Context c) {
        int n = 0;
        for (String k : sp(c).getAll().keySet()) if (k.startsWith(K_BANK)) n++;
        return n;
    }

    // ---------------- 本场记录 ----------------

    public static synchronized void add(Context c, String sig, String question,
                                        String answer, String source, int conf, long ms) {
        if (answer == null || answer.isEmpty()) return;
        ensureLoaded(c);
        Record r = new Record();
        r.q = question == null ? "" : question;
        r.answer = answer;
        r.source = source == null ? "" : source;
        r.conf = conf;
        r.ms = ms;
        r.ts = System.currentTimeMillis();
        session.add(r);
        if (session.size() > MAX_SESSION) session.remove(0);
        accumulate(r);

        SharedPreferences.Editor e = sp(c).edit();
        // 写题库：同一签名只覆盖答案，防止旧答案残留
        if (sig != null && !sig.isEmpty()) e.putString(K_BANK + md5(sig), answer);
        e.putString(K_SESSION, toJson(session));
        e.apply();
    }

    private static void accumulate(Record r) {
        stats.total++;
        stats.sumMs += Math.max(0, r.ms);
        if (r.conf > 0 && r.conf < 100) { /* 计数在 QService 按阈值判定 */ }
        if (r.source != null) {
            if (r.source.contains("题库")) stats.bank++;
            else if (r.source.contains("在线")) stats.cloud++;
            else stats.local++;
        }
        if (r.conf > 0 && r.conf < 60) stats.low++;
    }

    /** 低置信计数由 QService 用真实阈值累加，这里单独 +1。 */
    public static synchronized void markLow() { stats.low++; }

    public static synchronized List<Record> sessionSnapshot() {
        return new ArrayList<>(session);
    }

    public static synchronized Stats statsSnapshot() {
        Stats s = new Stats();
        s.total = stats.total; s.bank = stats.bank; s.local = stats.local;
        s.cloud = stats.cloud; s.low = stats.low; s.sumMs = stats.sumMs;
        return s;
    }

    /** 导出文本：「1.B  2.A  3.D …」，每 10 题换行，方便肉眼核对。 */
    public static synchronized String exportText() {
        if (session.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < session.size(); i++) {
            if (i > 0 && i % 10 == 0) sb.append('\n');
            else if (i > 0) sb.append("  ");
            sb.append(i + 1).append('.').append(session.get(i).answer);
        }
        return sb.toString();
    }

    public static synchronized void clearSession(Context c) {
        session.clear();
        stats.reset();
        sp(c).edit().remove(K_SESSION).apply();
    }

    private static String toJson(List<Record> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Record r : list) {
                JSONObject o = new JSONObject();
                o.put("q", r.q);
                o.put("a", r.answer);
                o.put("s", r.source);
                o.put("c", r.conf);
                o.put("m", r.ms);
                o.put("t", r.ts);
                arr.put(o);
            }
        } catch (Throwable ignored) {}
        return arr.toString();
    }
}
