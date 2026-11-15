package com.dsh.aianswer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 记忆库答案落地（v7.6）。
 *
 * <p>为什么需要它：记忆库以前只存答案字母（`B`）。同一道题第二次出现时，若屏幕选项顺序被打乱，
 * 直接点 `B` 就点到别的选项上——题干一样、答案字母一样，结果却是错的，而且错得毫无察觉。
 *
 * <p>现在每条记忆同时存下「字母 → 选项原文」，命中时先把答案字母翻译成文本，
 * 再拿文本在当前屏幕上找，找到哪个字母就点哪个字母：
 *
 * <pre>
 *   记忆：  B → "220V"        屏幕：  A. 12V   B. 220V   C. 380V   D. 1000V
 *                                 → 文本「220V」在 B 位 → 点 B（顺序未变）
 *   记忆：  B → "220V"        屏幕：  A. 220V  B. 12V    C. 380V   D. 1000V
 *                                 → 文本「220V」在 A 位 → 点 A（顺序变了，字母跟着变）
 * </pre>
 *
 * <p>抽成纯 Java 类是为了能脱离真机验证：这套判断一旦出错，表现就是「静默点错」，
 * 没法靠肉眼在考试里发现。
 */
public final class AnsResolver {

    /** 文本定位成功、字母没变：选项顺序和录制时一致。 */
    public static final float CONF_SAME = 1.00f;
    /** 文本定位成功、字母变了：顺序被打乱，但按内容找到了正确选项。 */
    public static final float CONF_MOVED = 0.95f;
    /** 旧记录没存选项内容，只能按字母给，不敢自动点。 */
    public static final float CONF_NO_OPTS = 0.55f;
    /** 存了选项内容，但在当前屏幕上对不上。 */
    public static final float CONF_MISS = 0.40f;

    /** 文本相似度下限，与自选题库保持一致。 */
    private static final double MIN_SIM = 0.75;

    private AnsResolver() {}

    public static final class Out {
        /** 当前屏幕上的答案字母（可能和记忆里的不同）。 */
        public String letters = "";
        /** 是否靠选项文本成功定位。 */
        public boolean mapped = false;
        /** 定位成功但字母变了：选项顺序被打乱，已按内容纠正。 */
        public boolean orderChanged = false;
        /** 旧记录没有选项内容。 */
        public boolean noOpts = false;
        /** 建议置信：低于阈值就不会自动点。 */
        public float conf = 0f;
        /** 给用户看的一句话（可空）。 */
        public String note = null;
        /** 失败原因（内部日志用）。 */
        public String failReason = null;
    }

    /**
     * @param bankOpts    记忆里的选项：字母 → 原文
     * @param bankLetters 记忆里的答案字母，如 "B" / "ABD"
     * @param screenOpts  当前屏幕上各选项的文本（不含字母前缀）
     */
    public static Out resolve(Map<String, String> bankOpts, String bankLetters, List<String> screenOpts) {
        Out o = new Out();
        if (bankLetters == null || bankLetters.trim().isEmpty()) {
            o.failReason = "记忆里没有答案";
            return o;
        }
        String letters = bankLetters.trim().toUpperCase();
        o.letters = letters;          // 兜底：定位失败时仍按原字母显示

        if (bankOpts == null || bankOpts.isEmpty()) {
            o.noOpts = true;
            o.conf = CONF_NO_OPTS;
            o.note = "这条记忆是旧版本存的，没有选项内容，只能按字母 " + letters + " 给，没敢自动点";
            return o;
        }

        // 先把字母翻译成「要找的原文」，再统一走文本定位。
        // 缺任何一个字母的原文都不敢自动点：宁可问一次模型，也不要静默点错。
        List<String> wants = new ArrayList<>();
        for (int i = 0; i < letters.length(); i++) {
            String L = String.valueOf(letters.charAt(i));
            String want = bankOpts.get(L);
            if (want == null || want.trim().isEmpty()) {
                o.conf = CONF_MISS;
                o.failReason = "记忆里缺选项 " + L + " 的文本";
                o.note = "记忆里的选项不完整，没敢自动点";
                return o;
            }
            wants.add(want);
        }
        Out done = map(wants, screenOpts, o);
        if (done.mapped) {
            done.orderChanged = !sorted(done.letters).equals(sorted(letters));
            done.conf = done.orderChanged ? CONF_MOVED : CONF_SAME;
            if (done.orderChanged) {
                done.note = "选项顺序变了：记忆里是 " + letters + "，按内容找到的是 " + done.letters;
            }
        }
        return done;
    }

    /**
     * v7.8：只有「答案原文」时的定位（手改过答案的条目走这条）。
     *
     * <p>比 {@link #resolve} 更硬：不再经过「字母 → 选项表」这一层，
     * 所以选项表后来被任意刷新、顺序怎么变，都不影响它。
     */
    public static Out resolveTexts(List<String> okTexts, List<String> screenOpts) {
        Out o = new Out();
        if (okTexts == null || okTexts.isEmpty()) {
            o.noOpts = true;
            o.conf = CONF_NO_OPTS;
            o.failReason = "没有答案原文";
            return o;
        }
        return map(okTexts, screenOpts, o);
    }

    /** 一批原文 → 当前屏幕上的字母。两个入口共用这一套判定。 */
    private static Out map(List<String> wants, List<String> screenOpts, Out o) {
        if (screenOpts == null || screenOpts.isEmpty()) {
            o.conf = CONF_MISS;
            o.failReason = "屏幕上没读到选项";
            if (o.note == null) o.note = "没读到屏幕选项，没敢自动点";
            return o;
        }

        List<Integer> used = new ArrayList<>();
        for (String want : wants) {
            int idx = Matcher.findTextIndex(want, screenOpts, MIN_SIM, 0);
            if (idx < 0) {
                o.conf = CONF_MISS;
                o.failReason = "「" + want + "」在屏幕上找不到";
                o.note = "记忆里的选项文字和屏幕对不上，没敢自动点";
                return o;
            }
            if (used.contains(idx)) {
                o.conf = CONF_MISS;
                o.failReason = "两个答案定位到同一个选项";
                o.note = "记忆里的两个答案撞到同一个选项，没敢自动点";
                return o;
            }
            used.add(idx);
        }

        // 按屏幕位置升序输出字母（点哪几个才是关键，字母本身顺序无所谓）
        Collections.sort(used);
        StringBuilder got = new StringBuilder();
        for (int idx : used) got.append((char) ('A' + idx));

        o.mapped = true;
        o.letters = got.toString();
        o.conf = CONF_SAME;
        return o;
    }

    /** 把一组答案原文拼成一行，用于「顺序变了」这类提示。 */
    public static String textSig(List<String> texts) {
        if (texts == null || texts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String t : texts) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(t == null ? "" : t.trim());
        }
        return sb.toString();
    }

    /**
     * 两个答案是不是同一组选项。
     *
     * <p>多选题必须按集合比：`AB` 和 `BA` 是同一个答案（点哪几个才是关键），
     * 用字符串直比会把每道多选题都误报成「分歧」。
     */
    public static boolean sameSet(String a, String b) {
        if (a == null || b == null) return false;
        if (a.isEmpty() || b.isEmpty()) return false;
        return sorted(a.trim().toUpperCase()).equals(sorted(b.trim().toUpperCase()));
    }

    private static String sorted(String s) {
        List<Character> cs = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) cs.add(s.charAt(i));
        Collections.sort(cs);
        StringBuilder sb = new StringBuilder();
        for (char c : cs) sb.append(c);
        return sb.toString();
    }
}
