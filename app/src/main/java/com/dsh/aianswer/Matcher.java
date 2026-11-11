package com.dsh.aianswer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 题库：加载 assets/questions.json，规范化后做模糊匹配。 */
/** 题库匹配核心：纯 Java，无 Android 依赖，便于在 JVM 上直接测。 */
public class Matcher {

    public static class Item {
        public String type;                       // 单选/多选/判断
        public String q;                          // 题干原文
        public String nq;                         // 规范化题干
        public List<String> ans = new ArrayList<>();      // 答案字母
        public LinkedHashMap<String, String> opts = new LinkedHashMap<>(); // 选项
        public String explain = "";
        /**
         * v1.4：本题「可接受的答案文本」。
         *
         * <p>普通题就是答案字母对应的选项原文；同题干存在多个版本时，是各版本答案
         * 文本的并集（见 buildVariants）。刷题时不去比对字母，而是看屏幕上实际
         * 出现的是哪一个 —— 这就是「冲突题合并」的落点。
         */
        public List<String> okTexts = new ArrayList<>();
        /** 同题干的版本数；>1 且 okTexts 比本题答案多，说明答案来自合并。 */
        public int variants = 1;
        // 性能：选项归一化结果与「是否判断题」在加载时算一次，
        // 否则每次匹配都要对 1201 条 × 4 个选项重新 norm 一遍（v1.8 提速）
        public List<String> nopts = new ArrayList<>();
        public boolean judge;
    }

    public static class Hit {
        public Item item;
        public double score;
        public double stem;
        public double opt;
        public boolean ambiguous;
        /** 这次命中的「屏幕题干原文」。用候选投票时，它就是被选中的那个切分。 */
        public String screen = "";
        public Hit(Item i, double s) { item = i; score = s; }
    }

    private final List<Item> items = new ArrayList<>();
    private final Map<String, List<Integer>> index = new HashMap<>();
    private boolean loaded;

    public int size() { return items.size(); }
    public List<Item> all() { return items; }

    /** 从 JSON 文本加载题库，返回是否成功。 */
    public boolean loadJson(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.type = o.optString("t", "");
                it.q = o.optString("q", "");
                if (it.q.isEmpty()) continue;
                it.nq = norm(it.q);
                JSONArray a = o.optJSONArray("a");
                if (a != null) for (int k = 0; k < a.length(); k++) it.ans.add(a.optString(k, "").toUpperCase());
                JSONObject op = o.optJSONObject("o");
                if (op != null) {
                    LinkedHashMap<String, String> raw = new LinkedHashMap<String, String>();
                    Iterator<String> keys = op.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        raw.put(key, op.optString(key, ""));
                    }
                    // 历史 JSON 用 F..J 存 5 个选项（老表格布局）。只有整组没有 A..E 时才左移，
                    // 否则 8 选项题（A..H）里的 F/G/H 会被错当成 A/B/C。
                    boolean hasLow = false;
                    for (String k : raw.keySet()) {
                        char c = k.isEmpty() ? ' ' : Character.toUpperCase(k.charAt(0));
                        if (c >= 'A' && c <= 'E') { hasLow = true; break; }
                    }
                    for (Map.Entry<String, String> e : raw.entrySet()) {
                        String key = e.getKey();
                        char c = key.isEmpty() ? ' ' : Character.toUpperCase(key.charAt(0));
                        if (!hasLow && c >= 'F' && c <= 'J') key = String.valueOf((char) ('A' + (c - 'F')));
                        it.opts.put(key.toUpperCase(), e.getValue());
                    }
                }
                it.explain = o.optString("e", "");
                int idx = items.size();
                // 预计算：选项归一化 + 是否判断题（匹配时不再逐条重算）
                for (String v : it.opts.values()) {
                    String n = norm(v);
                    if (!n.isEmpty()) it.nopts.add(n);
                }
                it.judge = isJudge(it);
                it.okTexts = textsOf(it);     // 默认就是本题答案文本，多版本时由 buildVariants 合并
                items.add(it);
                String key = it.nq.length() >= 6 ? it.nq.substring(0, 6) : it.nq;
                List<Integer> bucket = index.get(key);
                if (bucket == null) { bucket = new ArrayList<>(); index.put(key, bucket); }
                bucket.add(idx);
            }
            buildVariants();
            loaded = true;
        } catch (Exception e) {
            loaded = false;
        }
        return loaded;
    }

    /** 答案字母 → 选项原文（空选项跳过）。 */
    static List<String> textsOf(Item it) {
        List<String> out = new ArrayList<>();
        for (String letter : it.ans) {
            String raw = it.opts.get(letter);
            if (raw != null && !raw.trim().isEmpty()) out.add(raw);
        }
        return out;
    }

    /**
     * v1.4：同题干的多个版本，答案合并。
     *
     * <p>导入的题库里常出现「题干完全相同、但收录了不同版本」的题，选项和答案都可能
     * 不同（例：「自动扶梯和自动人行道的停止开关应为（）」，一条答「红色的」、
     * 一条答「具有清晰的、永久性的转换位置标记」——两个说法都对，只是出题角度不同）。
     *
     * <p>过去只能靠匹配度在几条里挑，挑错了给出的答案就不在本题的选项里，等于没法用。
     * 现在把同题干各版本的答案文本取并集：刷题时看屏幕上实际出现哪一个 ——
     * 单选题只取一个（一道题只能选一个），多选题有几个取几个。
     *
     * <p>两条保护：
     * ① 只有「选项集合有交集」才合并 —— 选项完全不重合说明是两道不同的题，
     *    题干相同纯属巧合，合并反而会给出不属于本题的答案；
     * ② 各版本答案文本本来就一样时不合并（并集只有 1 个），行为与旧版一致。
     */
    private void buildVariants() {
        Map<String, List<Item>> groups = new LinkedHashMap<>();
        for (Item it : items) {
            List<Item> g = groups.get(it.nq);
            if (g == null) { g = new ArrayList<>(); groups.put(it.nq, g); }
            g.add(it);
        }
        for (List<Item> g : groups.values()) {
            if (g.size() < 2) continue;
            if (!shareOption(g)) continue;
            LinkedHashMap<String, String> uni = new LinkedHashMap<>();
            for (Item it : g) {
                for (String t : textsOf(it)) {
                    String n = norm(t);
                    if (!n.isEmpty() && !uni.containsKey(n)) uni.put(n, t);
                }
            }
            if (uni.size() < 2) continue;      // 各版本答案其实一致，不必合并
            List<String> all = new ArrayList<>(uni.values());
            for (Item it : g) {
                it.okTexts = all;
                it.variants = g.size();
            }
        }
    }

    /** 组内任意两条题是否有选项文本重合（重合才认为是同一道题的不同版本）。 */
    private static boolean shareOption(List<Item> g) {
        for (int a = 0; a < g.size(); a++) {
            for (int b = a + 1; b < g.size(); b++) {
                for (String x : g.get(a).nopts) {
                    for (String y : g.get(b).nopts) {
                        if (x.equals(y) || x.contains(y) || y.contains(x)) return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean isLoaded() { return loaded; }

    /** 表头列字母（F..J）归一到选项字母（A..E）。仅供老题库兼容，见 loadJson。 */
    static String mapOptKey(String key) {
        if (key == null || key.isEmpty()) return key;
        String k = key.toUpperCase();
        char c = k.charAt(0);
        if (c >= 'F' && c <= 'J') return String.valueOf((char) ('A' + (c - 'F')));
        return k;
    }

    /**
     * 在屏幕选项里定位「答案文本」对应的那个，返回下标；定位不到返回 -1。
     * 选项可能被打乱顺序，所以只按文本，不按字母。
     */
    public static int findOptionIndex(Item item, String letter, java.util.List<String> screenTexts) {
        return findOptionIndex(item, letter, screenTexts, 0.60, 0);
    }

    /**
     * 可调门槛版。显示屏幕字母映射时用严格档（minSim=0.75）：相似度不够宁可返回 -1，
     * 让界面回退到题库字母，也不要给出一个似是而非的错误字母。
     * minContain：contains 兜底要求较短一方至少这么长（归一化后），
     * 防止把别处一小段同文片段当成整个选项。
     */
    public static int findOptionIndex(Item item, String letter, java.util.List<String> screenTexts,
                                      double minSim, int minContain) {
        if (item == null) return -1;
        return findTextIndex(item.opts.get(letter), screenTexts, minSim, minContain);
    }

    /**
     * v1.4：按「答案文本」在屏幕选项里定位下标（选项顺序可能被打乱），找不到返回 -1。
     *
     * <p>合并了多版本的题没有单一的答案字母可用（并集里的文本来自不同版本，
     * 字母对不上），所以统一走文本定位。门槛同 findOptionIndex：
     * 显示屏幕字母用严格档 0.75，宁可返回 -1 也不要给个似是而非的位置。
     */
    public static int findTextIndex(String wantText, java.util.List<String> screenTexts,
                                    double minSim, int minContain) {
        if (wantText == null || screenTexts == null || screenTexts.isEmpty()) return -1;
        String want = norm(wantText);
        // 纯符号选项（∞、√、×、①、±…）：norm 会把它们整段剥空，只能按原文精确比。
        // 不处理的话「∞」这类选项永远定位不到，答案会少一个（兆欧表那题踩过）。
        if (want.isEmpty()) {
            String w = wantText.trim();
            for (int i = 0; i < screenTexts.size(); i++) {
                String s = screenTexts.get(i);
                if (s != null && s.trim().equals(w)) return i;
            }
            return -1;
        }
        String wantSig = symSig(wantText);
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < screenTexts.size(); i++) {
            String raw = screenTexts.get(i);
            // v1.4：正负号 / 比较符方向必须一致。
            // 「+15%」与「-15%」归一后都是「15%」，不校验的话会点错选项。
            if (!wantSig.equals(symSig(raw))) continue;
            String have = norm(raw);
            double sc;
            if (have.equals(want)) sc = 1.0;
            else if ((have.contains(want) || want.contains(have))
                    && Math.min(have.length(), want.length()) >= minContain) sc = 0.85;
            else {
                int l = lcs(have, want);
                sc = have.isEmpty() || want.isEmpty() ? 0 : 2.0 * l / (have.length() + want.length());
            }
            if (sc > bestScore) { bestScore = sc; best = i; }
        }
        return bestScore >= minSim ? best : -1;
    }

    /**
     * 提取「正负号 + 比较符」签名，用于判断两个选项是不是方向相反。
     *
     * <p>norm() 只保留 > &lt; ≤ ≥，正负号会被当标点剥掉，于是
     * 「+15%；15S」和「-15%;15S」、「+7%」和「-7%」归一后完全相同 ——
     * 只按文本相似度定位就会点到相反的那个选项。
     */
    static String symSig(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '＋') c = '+';
            else if (c == '－' || c == '−' || c == '—') c = '-';
            else if (c == '<' || c == '＜') c = '<';
            else if (c == '>' || c == '＞') c = '>';
            else if (c == '≤' || c == '≦') c = '≤';
            else if (c == '≥' || c == '≧') c = '≥';
            else if (c != '+' && c != '-' && c != '±') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    /** 全角转半角、去空白与标点、去题号前缀、小写。 */
    public static String norm(String s) {
        if (s == null) return "";
        s = Layout.stripScore(s);   // 先剥掉「（1分）」这类分值标记，避免「1分」进题干
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0xFF01 && ch <= 0xFF5E) ch = (char) (ch - 0xFEE0);
            else if (ch == 0x3000) ch = ' ';
            // v1.3 修：比较符号必须保留方向。原先一律剥掉，「直流电流>1000mA」和
            // 「直流电流≤1000mA」归一成同一串 —— 那是两道不同的题（答案分别是 A孔 / mA孔），
            // 归一撞车后会互相抢分并给出错答案。全角的 ＞＜ 上面已转成 ASCII，这里补 ≥≦。
            else if (ch == 0x2265 || ch == 0x2267) ch = '}';   // ≥ ≧
            else if (ch == 0x2264 || ch == 0x2266) ch = '{';   // ≤ ≦
            if (ch >= 'A' && ch <= 'Z') ch = (char) (ch + 32);
            boolean keep = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || (ch >= 0x4E00 && ch <= 0x9FFF)
                    || ch == '>' || ch == '<' || ch == '{' || ch == '}';
            if (keep) sb.append(ch);
        }
        String out = sb.toString();
        // 去掉题号前缀（如 12、/ 12./ 12)/ 第12题）。
        // v1.7.3 修：只能剥「数字后跟分隔符」或「剩余正文足够长」的——
        // 短选项开头的数字是内容不是题号（如「3年」「20万次」），一律剥掉会把
        // A=3年/B=5年 全归一成「年」，findOptionIndex 永远命中第一个，屏幕字母全错。
        int i = 0;
        while (i < out.length() && Character.isDigit(out.charAt(i))) i++;
        if (i > 0 && i < out.length() && i <= 3) {
            char next = out.charAt(i);
            boolean sep = "、．.,，)）:：/;；".indexOf(next) >= 0;
            if (sep || out.length() - i >= 6) out = out.substring(i);
        }
        return out;
    }

    public Hit match(String screenText, java.util.List<String> screenOptions) {
        return match(screenText, screenOptions, true);
    }

    /** 不做阈值过滤，返回最接近的一条（用于「题库里没有」时展示近似候选 / 记录日志）。 */
    public Hit bestEffort(String screenText, java.util.List<String> screenOptions) {
        return match(screenText, screenOptions, false);
    }

    /**
     * 在多个候选题干里挑最像的一个。
     *
     * <p>屏幕上题目上方常有说明文字，按版面切出来的题干未必是干净的那一段；
     * 这里把「最近 1 段 / 2 段 / …」几种切分全部拿去和题库比，得分最高的那个才是真题干。
     * 夹了多余文字时相似度会掉，所以这一步能把切分错误纠正回来。
     */
    public Hit matchBest(java.util.List<String> stems, java.util.List<String> screenOptions) {
        return pickBest(stems, screenOptions, true);
    }

    /** 同上，但不过阈值（用于展示最接近的候选）。 */
    public Hit bestEffort(java.util.List<String> stems, java.util.List<String> screenOptions) {
        return pickBest(stems, screenOptions, false);
    }

    private Hit pickBest(java.util.List<String> stems, java.util.List<String> screenOptions, boolean enforce) {
        if (!loaded || stems == null || stems.isEmpty()) return null;
        Hit best = null;
        for (String s : stems) {
            if (s == null || s.trim().isEmpty()) continue;
            Hit h = match(s, screenOptions, false);
            if (h == null) continue;
            h.screen = s;
            if (best == null) { best = h; continue; }
            double d = h.score - best.score;
            // 同分取更短的：多余的那几段是噪声，去掉更可信
            if (d > 1e-6 || (d >= -1e-6 && s.length() < best.screen.length())) best = h;
        }
        if (best == null) return null;
        if (!enforce) return best;
        double need = isJudge(best.item) ? 0.55 : 0.60;
        // 题干几乎一模一样时，不再要求选项分：题库里写的「12V」和屏幕上的「十二伏特」
        // 内容一致但文本不同，选项分为 0 会把本来正确的题判成没命中
        boolean strongStem = best.stem >= 0.95;
        if (best.score < need && !strongStem) return null;
        // AI答题 场景加固（v7.2）：刷题助手面对的是固定卷子，屏幕选项和题库一一对应，
        // 选项分天然可信；而 AI答题 面对任意页面，会出现「选项恰好一样、题干完全不同」
        // （同一份卷子的相邻题、同一批选项的系列题），选项满分会把总分抬过阈值造成误命中。
        // 这里加一道题干下限：题干不像就不算命中，选项再像也不认。
        if (best.stem < MIN_STEM) return null;
        String q = norm(best.screen);
        // 题干过短时只能靠选项确认，否则容易误配
        if (q.length() < 6 && screenOptions != null && !screenOptions.isEmpty() && best.opt < 0.80) return null;
        return best;
    }

    /** 题干相似度下限。低于它直接判「题库里没有」，防止选项分把误配抬过线。 */
    private static final double MIN_STEM = 0.45;

    private Hit match(String screenText, java.util.List<String> screenOptions, boolean enforce) {
        if (!loaded || screenText == null) return null;
        String q = norm(screenText);
        if (q.length() < 2) return null;
        List<Integer> cand = new ArrayList<>();
        for (int len = 6; len >= 2; len--) {
            if (q.length() < len) continue;
            List<Integer> b = index.get(q.substring(0, len));
            if (b != null) for (Integer i : b) if (!cand.contains(i)) cand.add(i);
            if (!cand.isEmpty()) break;
        }
        if (cand.isEmpty()) {
            for (int i = 0; i < items.size(); i++) cand.add(i);
        }
        Hit best = null;
        Hit second = null;
        // v1.8 提速：屏幕选项归一化 + 题干二元组集合各算一次，候选循环里复用
        java.util.List<String> so = new ArrayList<>();
        if (screenOptions != null) {
            for (String v : screenOptions) {
                String n = norm(v);
                if (!n.isEmpty()) so.add(n);
            }
        }
        java.util.Set<String> qBg = bigrams(q);
        for (int idx : cand) {
            Item it = items.get(idx);
            double stem = score(q, it.nq, qBg);
            // 上界剪枝：opt 最大 1.0，若上界都进不了前二就不必再算 optScore（结果不变）
            if (best != null) {
                double ub = it.judge ? stem : 0.55 * stem + 0.45;
                if (ub <= best.score && (second == null || ub <= second.score)) continue;
            }
            double opt = optScoreFast(it, so);
            // 判断题的选项永远是「正确/错误」，对区分题目没有信息量，
            // 混进来只会把题干相似度摊薄（满分题干也只显示 69%），所以只按题干算。
            double sc = it.judge ? stem : 0.55 * stem + 0.45 * opt;
            Hit candHit = new Hit(it, sc);
            candHit.stem = stem;
            candHit.opt = opt;
            if (best == null || sc > best.score) { second = best; best = candHit; }
            else if (second == null || sc > second.score) second = candHit;
        }
        // 前缀索引没命中（题干开头有杂字/被截断）时，全量再扫一遍
        if (best == null || best.score < 0.45) {
            for (int i = 0; i < items.size(); i++) {
                if (cand.contains(i)) continue;
                Item it = items.get(i);
                double stem = score(q, it.nq, qBg);
                if (stem < 0.45) continue;
                if (best != null) {
                    double ub = it.judge ? stem : 0.55 * stem + 0.45;
                    if (ub <= best.score && (second == null || ub <= second.score)) continue;
                }
                double opt = optScoreFast(it, so);
                double sc = it.judge ? stem : 0.55 * stem + 0.45 * opt;
                Hit candHit = new Hit(it, sc);
                candHit.stem = stem;
                candHit.opt = opt;
                if (best == null || sc > best.score) { second = best; best = candHit; }
                else if (second == null || sc > second.score) second = candHit;
            }
        }
        // 合并了多版本的题，两条候选的 okTexts 是同一份并集（同一 List 实例），
        // 不算「答案有歧义」——反正屏幕上出现哪个就认哪个。
        if (best != null) best.ambiguous = second != null && best.score - second.score < 0.03
                && !second.item.okTexts.equals(best.item.okTexts);
        if (best == null) return null;
        best.screen = screenText;
        if (!enforce) return best;
        double need = isJudge(best.item) ? 0.55 : 0.60;
        boolean strongStem = best.stem >= 0.95;
        if (best.score < need && !strongStem) return null;
        if (best.stem < MIN_STEM) return null;
        // 题干过短时只能靠选项确认，否则容易误配
        if (q.length() < 6 && screenOptions != null && !screenOptions.isEmpty() && best.opt < 0.80) return null;
        return best;
    }

    static boolean isJudge(Item it) {
        if (it == null) return false;
        if (it.type != null && it.type.contains("判断")) return true;
        // 判断题的选项就两个：正确/错误（或对/错）
        if (it.opts.size() == 2) {
            StringBuilder sb = new StringBuilder();
            for (String v : it.opts.values()) sb.append(norm(v));
            String t = sb.toString();
            return t.contains("正确") && t.contains("错误");
        }
        return false;
    }

    /** 选项集合相似度：题库选项有多大比例能在屏幕上找到对应项。 */
    private static double optScore(Item it, java.util.List<String> screenOptions) {
        java.util.List<String> so = new ArrayList<>();
        if (screenOptions != null) {
            for (String v : screenOptions) {
                String n = norm(v);
                if (!n.isEmpty()) so.add(n);
            }
        }
        return optScoreFast(it, so);
    }

    /**
     * 用「预归一化」的屏幕选项算——v1.8 提速。
     *
     * <p>命中门槛是 0.85，而 LCS 分支最多只有 0.6 × score ≤ 0.6，永远到不了 0.85，
     * 所以只有「相等 / 包含」才可能命中。原来每条题库都要跑 4×4 次 LCS + 二元组扫描，
     * 现在直接跳过那部分，结果完全一致，但省掉绝大部分计算。
     */
    private static double optScoreFast(Item it, java.util.List<String> so) {
        if (it == null || it.nopts.isEmpty() || so == null || so.isEmpty()) return 0.5;
        int hit = 0;
        for (String b : it.nopts) {
            for (String s : so) {
                if (b.equals(s) || s.contains(b) || b.contains(s)) { hit++; break; }
            }
        }
        return (double) hit / Math.max(it.nopts.size(), so.size());
    }

    private static double score(String q, String nq) {
        return score(q, nq, null);
    }

    /**
     * @param qBg 屏幕文字 q 的相邻二元组集合（一次性算好）；为 null 时内部现算。
     *            用它替代 nq 每个二元组在 q 里做一次 contains 扫描，结果等价但快很多。
     */
    private static double score(String q, String nq, java.util.Set<String> qBg) {
        if (q.equals(nq)) return 1.0;
        double base;
        if (q.length() >= 10) {
            if (nq.startsWith(q)) return 0.90 + 0.10 * ((double) q.length() / nq.length());
            if (nq.contains(q)) return 0.85;
            if (q.contains(nq) && nq.length() >= 10) return 0.84;
        }
        int l = lcs(q, nq);
        base = 2.0 * l / (q.length() + nq.length());
        // 覆盖率兜底：题库题干的二元组有多少能在屏幕文字里找到。
        // 屏幕文字夹了杂七杂八的标签、或题干被拆散时，比单纯的 LCS 比值稳。
        double cov = coverage(nq, q, qBg);
        if (cov >= 0.6) base = Math.max(base, 0.95 * cov);
        return base;
    }

    /** nq 的相邻二元组（汉字/字母数字对）在 q 中出现的比例。 */
    static double coverage(String nq, String q) {
        return coverage(nq, q, null);
    }

    static double coverage(String nq, String q, java.util.Set<String> qBg) {
        if (nq.length() < 4) return 0;
        if (qBg == null) {
            if (q == null || q.isEmpty()) return 0;
            qBg = bigrams(q);
        }
        int total = 0, hit = 0;
        for (int i = 0; i + 1 < nq.length(); i++) {
            String bg = nq.substring(i, i + 2);
            total++;
            if (qBg.contains(bg)) hit++;
        }
        return total == 0 ? 0 : (double) hit / total;
    }

    /** q 的全部相邻二元组（用于 O(1) 判断某二元组是否在 q 中出现）。 */
    static java.util.Set<String> bigrams(String s) {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) set.add(s.substring(i, i + 2));
        return set;
    }

    private static int lcs(String a, String b) {
        int n = a.length(), m = b.length();
        if (n == 0 || m == 0) return 0;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                if (ca == b.charAt(j - 1)) cur[j] = prev[j - 1] + 1;
                else cur[j] = Math.max(prev[j], cur[j - 1]);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[m];
    }
}
