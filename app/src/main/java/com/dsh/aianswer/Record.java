package com.dsh.aianswer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 题库 + 本场记录 + 统计（v7.1 结构化版本）。
 *
 * <p>题库结构从「md5(题干) → "B"」升级为 JSON 条目，因为原来的写法有三个硬伤：
 * ① 只会覆盖写，同一题两个不同答案会静默互相冲掉，用户完全看不见；
 * ② 没有入口能人工修正；
 * ③ 无法记录来源/命中次数，统计做不出来。
 *
 * <p>现在每条记录：答案 + 来源 + 置信 + 命中次数 + 是否用户锁定 + 冲突候选 + 错题标记。
 * 旧数据（纯字符串）读到后自动迁移，不清库。
 *
 * <p>冲突策略：用户手改过的条目 locked=true，视为金标准，模型答案永远不覆盖；
 * 其他条目的模型异议只追加到 cf 列表并打「有分歧」角标，绝不自动改写。
 */
public final class Record {

    private static final String TAG = "AIQ";
    private static final String SP = "records";
    private static final String K_SESSION = "session";
    private static final String K_BANK = "bank.";        // + md5(sig)
    private static final int MAX_SESSION = 300;

    public String q;
    public String answer;
    public String source;
    public int conf;
    public long ms;
    public long ts;

    /** 题库条目。 */
    public static final class Bank {
        public String key = "";          // md5(签名)，即 SharedPreferences 后缀
        public String sig = "";          // 完整签名（题干+选项），复核时重新算
        public String q = "";            // 题干，列表里显示
        public String a = "";            // 当前答案，如 "B" / "ABD"
        /**
         * 选项原文：字母 → 文本（v7.6）。
         *
         * <p>光存字母是不够的：同一道题下次出现时选项顺序可能被打乱，
         * 照着旧字母点就点到别的选项上。存下文本才能在屏幕上按内容重新定位。
         * 旧记录这里是空的，命中时按 low-confidence 处理（不自动点）。
         */
        public final java.util.Map<String, String> opts = new java.util.LinkedHashMap<>();
        /** 选项文本定位失败的累计次数；连续两次就把条目打回「待确认」。 */
        public int optStale = 0;
        public String src = "";          // 来源：手动 / 在线-智谱 / 端侧-1.5B
        public int conf = 100;
        public int hits = 1;
        public long ts = 0;
        public boolean locked = false;   // 用户手改过 = 金标准
        public boolean verified = false; // 审核通过：只有它才允许 0ms 直接命中
        public boolean wrong = false;    // 进错题本
        public final List<Cf> cf = new ArrayList<>();

        public boolean hasConflict() { return !cf.isEmpty(); }
    }

    /** 冲突候选：某次模型给出了不同的答案。 */
    public static final class Cf {
        public String a = "";
        public String src = "";
        public long ts = 0;
    }

    /** 冲突仲裁后要显示给用户的东西。 */
    public static final class Hit {
        public String letters = "";   // 主答案
        public boolean fromBank = false;
        public boolean conflict = false;
        public String other = null;   // 另一方的答案
        public String otherSrc = null;
        public boolean locked = false;
        public String note = null;    // 悬浮窗上的一行提示
    }

    /** 本场统计。 */
    public static final class Stats {
        public int total, bank, local, cloud, low, conflict, wrong;
        public long sumMs;

        public long avgMs() { return total == 0 ? 0 : sumMs / total; }
        public void reset() { total = bank = local = cloud = low = conflict = wrong = 0; sumMs = 0; }
    }

    /** 题库汇总，主界面和题库中心都要用。 */
    public static final class BankCounts {
        public int total, wrong, conflict, locked;
    }

    private static final List<Record> session = new ArrayList<>();
    private static final Stats stats = new Stats();
    private static boolean loaded = false;

    private Record() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

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

    // ---------------- 题库：读 ----------------

    /**
     * 取题库条目。旧版本存的是纯字符串（"B"），这里就地迁移成 JSON，
     * 迁移时不改答案，下次写回才落成新结构。
     */
    public static Bank bankGet(Context c, String sig) {
        if (sig == null || sig.isEmpty()) return null;
        String key = md5(sig);
        String raw;
        try {
            raw = sp(c).getString(K_BANK + key, null);
        } catch (ClassCastException e) {
            return null;
        }
        if (raw == null || raw.isEmpty()) return null;
        Bank b = parse(raw);
        if (b == null) {
            b = new Bank();                 // 旧格式：纯答案字符串
            b.a = raw.trim();
            b.src = "旧版本";
            b.hits = 1;
            b.ts = 0;
        }
        b.key = key;
        b.sig = sig;
        return b;
    }

    private static Bank parse(String raw) {
        if (raw == null || !raw.startsWith("{")) return null;
        try {
            JSONObject o = new JSONObject(raw);
            Bank b = new Bank();
            b.a      = o.optString("a", "");
            b.q      = o.optString("q", "");
            b.src    = o.optString("s", "");
            b.conf   = o.optInt("c", 100);
            b.hits   = o.optInt("h", 1);
            b.ts     = o.optLong("t", 0);
            b.locked = o.optBoolean("l", false);
            b.verified = o.optBoolean("v", false);
            b.wrong  = o.optBoolean("w", false);
            b.optStale = o.optInt("os", 0);
            JSONObject op = o.optJSONObject("op");
            if (op != null) {
                java.util.Iterator<String> it = op.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String v = op.optString(k, "");
                    if (k != null && !k.isEmpty() && v != null && !v.isEmpty()) b.opts.put(k, v);
                }
            }
            JSONArray cf = o.optJSONArray("cf");
            if (cf != null) {
                for (int i = 0; i < cf.length() && i < 8; i++) {
                    JSONObject x = cf.getJSONObject(i);
                    Cf f = new Cf();
                    f.a   = x.optString("a", "");
                    f.src = x.optString("s", "");
                    f.ts  = x.optLong("t", 0);
                    if (!f.a.isEmpty()) b.cf.add(f);
                }
            }
            return b.a.isEmpty() ? null : b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String toJson(Bank b) {
        try {
            JSONObject o = new JSONObject();
            o.put("a", b.a == null ? "" : b.a);
            o.put("q", b.q == null ? "" : b.q);
            o.put("s", b.src == null ? "" : b.src);
            o.put("c", b.conf);
            o.put("h", b.hits);
            o.put("t", b.ts);
            o.put("l", b.locked);
            o.put("v", b.verified);
            o.put("w", b.wrong);
            o.put("os", b.optStale);
            if (!b.opts.isEmpty()) {
                JSONObject op = new JSONObject();
                for (java.util.Map.Entry<String, String> e : b.opts.entrySet()) {
                    op.put(e.getKey(), e.getValue());
                }
                o.put("op", op);
            }
            JSONArray arr = new JSONArray();
            for (Cf f : b.cf) {
                arr.put(new JSONObject().put("a", f.a).put("s", f.src).put("t", f.ts));
            }
            o.put("cf", arr);
            return o.toString();
        } catch (Throwable t) {
            return "{\"a\":\"" + (b.a == null ? "" : b.a) + "\"}";
        }
    }

    public static void bankPut(Context c, Bank b) {
        if (b == null || b.key == null || b.key.isEmpty()) return;
        sp(c).edit().putString(K_BANK + b.key, toJson(b)).apply();
    }

    /** 兼容老调用：只要答案字母。有分歧的题不静默命中，交给模型+人来判。 */
    public static String bankAnswer(Context c, String sig) {
        Bank b = bankGet(c, sig);
        if (b == null || b.hasConflict()) return null;
        return b.a;
    }

    // ---------------- 题库：写 ----------------

    /**
     * 自动写入模型答案。
     * 题库没有 → 新建（hits=1）；答案一致 → hits+1；
     * 答案不同 → <b>不覆盖</b>，追加冲突候选。
     */
    public static Hit bankAuto(Context c, String sig, String q, String letters,
                               java.util.Map<String, String> opts, String source, int conf) {
        Hit hit = new Hit();
        hit.letters = letters == null ? "" : letters;
        if (sig == null || sig.isEmpty() || hit.letters.isEmpty()) return hit;

        Bank b = bankGet(c, sig);
        if (b == null) {
            b = new Bank();
            b.key = md5(sig);
            b.sig = sig;
            b.q = q == null ? "" : q;
            b.a = hit.letters;
            b.src = source == null ? "" : source;
            b.conf = conf;
            b.hits = 1;
            b.ts = System.currentTimeMillis();
            b.verified = false;      // 第一次答完只是「候选」，要再遇到一次一致才算数
            if (opts != null) b.opts.putAll(opts);
            bankPut(c, b);
            return hit;
        }

        b.sig = sig;
        if (q != null && !q.isEmpty() && (b.q == null || b.q.isEmpty())) b.q = q;
        // 选项内容以最新一次屏幕为准：题干和答案都一致时，刷新选项只会让下次定位更准。
        // 旧记录（没有选项）也会在这里被补齐。
        if (opts != null && opts.size() >= 2) {
            b.opts.clear();
            b.opts.putAll(opts);
            b.optStale = 0;
        }

        if (b.a.equals(hit.letters)) {
            b.hits++;
            b.ts = System.currentTimeMillis();
            if (source != null && (b.src == null || b.src.isEmpty())) b.src = source;
            // 模型这次和题库一致：旧分歧已经没有意义，清掉，否则这道题会永远标着「分歧」
            // 每次都强制跑模型复核，白白消耗在线额度
            if (!b.cf.isEmpty()) b.cf.clear();
            b.verified = true;       // 两次独立作答一致 = 自动审核通过
            bankPut(c, b);
            return hit;
        }

        // 答案不同：只记冲突，不改库
        addConflict(b, hit.letters, source);
        b.verified = false;          // 出现分歧 → 立刻打回，等人工裁决
        bankPut(c, b);
        hit.conflict = true;
        hit.other = hit.letters;
        hit.otherSrc = source;
        // 主答案仍以题库为准，但要翻译成**当前屏幕**的字母：
        // 题库里记的 B 是录制那次的第 2 个选项，这次顺序可能变了，
        // 直接把 B 塞回去就会指着另一个选项说「题库是这个」。
        java.util.List<String> screenTexts = new java.util.ArrayList<>(opts == null
                ? new java.util.ArrayList<String>() : opts.values());
        AnsResolver.Out mv = AnsResolver.resolve(b.opts, b.a, screenTexts);
        hit.letters = mv.mapped ? mv.letters : b.a;
        hit.fromBank = true;
        hit.locked = b.locked;
        return hit;
    }

    private static void addConflict(Bank b, String letters, String source) {
        for (Cf f : b.cf) if (f.a.equals(letters)) return;      // 同一答案不重复记
        Cf f = new Cf();
        f.a = letters;
        f.src = source == null ? "" : source;
        f.ts = System.currentTimeMillis();
        b.cf.add(f);
        if (b.cf.size() > 5) b.cf.remove(0);                    // 只留最近 5 条
        b.ts = System.currentTimeMillis();
    }

    /**
     * 命中后回写「选项能否对上」的结果（v7.6 有效性校验）。
     *
     * <p>定位成功 → 清零：说明存下来的选项内容还能用。
     * 定位失败 → 累计，连续两次就打回「待确认」，让下次重新问一次模型刷新选项。
     * 单次失败不惩罚：屏幕偶尔只渲染出一部分选项，一次对不上不代表记忆坏了。
     */
    public static void bankNoteResolve(Context c, Bank b, boolean mapped) {
        if (b == null) return;
        if (mapped) {
            if (b.optStale != 0) {
                b.optStale = 0;
                bankPut(c, b);
            }
            return;
        }
        b.optStale++;
        if (b.optStale >= 2 && b.verified) {
            b.verified = false;
            Log.i(TAG, "选项连续对不上，打回待确认：" + b.q);
        }
        bankPut(c, b);
    }

    /** 用户手改答案：锁定为金标准，原答案进错题本。 */
    public static void bankManual(Context c, String sig, String q, String letters) {
        if (sig == null || sig.isEmpty() || letters == null || letters.isEmpty()) return;
        Bank b = bankGet(c, sig);
        if (b == null) {
            b = new Bank();
            b.key = md5(sig);
            b.sig = sig;
            b.q = q == null ? "" : q;
            b.a = letters;
            b.src = "手动";
            b.conf = 100;
            b.hits = 1;
            b.ts = System.currentTimeMillis();
            b.locked = true;
            bankPut(c, b);
            return;
        }
        if (!letters.equals(b.a)) {
            b.wrong = true;                       // 原来那个答案是错的
            b.cf.clear();                         // 人工裁决过，清掉历史分歧
        }
        b.a = letters;
        b.src = "手动";
        b.conf = 100;
        b.locked = true;
        b.verified = true;
        b.ts = System.currentTimeMillis();
        bankPut(c, b);
    }

    /** 采纳某个冲突候选（题库详情里点「采纳」）。 */
    public static void bankAdopt(Context c, String sig, String letters, String src) {
        if (sig == null || sig.isEmpty() || letters == null || letters.isEmpty()) return;
        Bank b = bankGet(c, sig);
        if (b == null) return;
        if (!letters.equals(b.a)) b.wrong = true;
        b.a = letters;
        b.src = (src == null || src.isEmpty()) ? "手动采纳" : ("采纳 " + src);
        b.conf = 100;
        b.locked = true;
        b.verified = true;
        b.cf.clear();
        b.ts = System.currentTimeMillis();
        bankPut(c, b);
    }

    /** 人工确认这条记忆库答案可用（题库详情里的「确认可用」）。 */
    public static void bankConfirm(Context c, String sig) {
        Bank b = bankGet(c, sig);
        if (b == null) return;
        b.verified = true;
        b.cf.clear();
        bankPut(c, b);
    }

    public static void bankConfirm(Context c, Bank b) {
        if (b == null) return;
        b.verified = true;
        b.cf.clear();
        bankPut(c, b);
    }

    /** 标记/取消错题。 */
    public static void bankSetWrong(Context c, String sig, boolean wrong) {
        Bank b = bankGet(c, sig);
        if (b == null) return;
        b.wrong = wrong;
        bankPut(c, b);
    }

    public static void bankDelete(Context c, String sig) {
        if (sig == null || sig.isEmpty()) return;
        sp(c).edit().remove(K_BANK + md5(sig)).apply();
    }

    // --- 按条目操作（题库中心用：列表里只有 md5 后缀，拼不出完整签名）---

    public static void bankSaveManual(Context c, Bank b, String letters) {
        if (b == null || letters == null || letters.isEmpty()) return;
        if (!letters.equals(b.a)) {
            b.wrong = true;              // 原来那个答案是错的
            b.cf.clear();                // 人工裁决过，清掉历史分歧
        }
        b.a = letters;
        b.src = "手动";
        b.conf = 100;
        b.locked = true;
        b.verified = true;
        b.ts = System.currentTimeMillis();
        bankPut(c, b);
    }

    public static void bankSaveAdopt(Context c, Bank b, String letters, String src) {
        if (b == null || letters == null || letters.isEmpty()) return;
        if (!letters.equals(b.a)) b.wrong = true;
        b.a = letters;
        b.src = (src == null || src.isEmpty()) ? "手动采纳" : ("采纳 " + src);
        b.conf = 100;
        b.locked = true;
        b.verified = true;
        b.cf.clear();
        b.ts = System.currentTimeMillis();
        bankPut(c, b);
    }

    public static void bankSetWrong(Context c, Bank b, boolean wrong) {
        if (b == null) return;
        b.wrong = wrong;
        bankPut(c, b);
    }

    public static void bankDelete(Context c, Bank b) {
        if (b == null || b.key == null || b.key.isEmpty()) return;
        sp(c).edit().remove(K_BANK + b.key).apply();
    }

    public static void clearBank(Context c) {
        SharedPreferences p = sp(c);
        SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet()) {
            if (k.startsWith(K_BANK)) e.remove(k);
        }
        e.apply();
    }

    /** 题库全量，按最近更新倒序。几千条也在几十毫秒内。 */
    public static List<Bank> bankList(Context c) {
        List<Bank> out = new ArrayList<>();
        SharedPreferences p = sp(c);
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(K_BANK)) continue;
            Object v = e.getValue();
            if (!(v instanceof String)) continue;
            Bank b = parse((String) v);
            if (b == null) {
                b = new Bank();
                b.a = ((String) v).trim();
                b.src = "旧版本";
                b.hits = 1;
            }
            b.key = k.substring(K_BANK.length());
            if (b.a.isEmpty()) continue;
            out.add(b);
        }
        Collections.sort(out, new Comparator<Bank>() {
            public int compare(Bank a, Bank b) { return Long.compare(b.ts, a.ts); }
        });
        return out;
    }

    public static BankCounts bankCounts(Context c) {
        BankCounts n = new BankCounts();
        for (Bank b : bankList(c)) {
            n.total++;
            if (b.wrong) n.wrong++;
            if (b.hasConflict()) n.conflict++;
            if (b.locked) n.locked++;
        }
        return n;
    }

    public static int bankSize(Context c) {
        int n = 0;
        for (String k : sp(c).getAll().keySet()) if (k.startsWith(K_BANK)) n++;
        return n;
    }

    // ---------------- 本场记录 ----------------

    /**
     * 记一条本场记录。题库写入统一走 {@link #bankAuto}，这里不再重复处理，
     * 避免同一次答题把命中次数加两遍。
     */
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
        sp(c).edit().putString(K_SESSION, toJson(session)).apply();
    }

    private static void accumulate(Record r) {
        stats.total++;
        stats.sumMs += Math.max(0, r.ms);
        if (r.source != null) {
            if (r.source.contains("题库")) stats.bank++;
            else if (r.source.contains("在线") || r.source.contains("投票")) stats.cloud++;
            else stats.local++;
        }
        if (r.conf > 0 && r.conf < 60) stats.low++;
    }

    public static synchronized void markLow() { stats.low++; }
    public static synchronized void markConflict() { stats.conflict++; }
    public static synchronized void markWrong() { stats.wrong++; }

    public static synchronized List<Record> sessionSnapshot() {
        return new ArrayList<>(session);
    }

    public static synchronized Stats statsSnapshot() {
        Stats s = new Stats();
        s.total = stats.total; s.bank = stats.bank; s.local = stats.local;
        s.cloud = stats.cloud; s.low = stats.low; s.conflict = stats.conflict;
        s.wrong = stats.wrong; s.sumMs = stats.sumMs;
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
