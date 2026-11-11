package com.dsh.aianswer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 自选题库命中核心（纯 Java，无 Android 依赖，可在 JVM 上直接测）。
 *
 * <p>只做两件事：在题库里找题干；把题库的答案文本映射成**屏幕上**的字母。
 * Android 侧的 {@link SelfBank} 只负责加载 Matcher 和开关判断。
 *
 * <p>为什么必须映射：题库里答案是「B」，但屏幕上选项顺序常被打乱，
 * 直接按字母点会点错。刷题助手实测「打乱选项 100 次仍能定位 97+ 次」靠的就是这条。
 */
public final class SelfBankMatch {

    public static final class Hit {
        public Matcher.Hit raw;
        public String letters = "";       // 最终用于显示的字母（屏幕字母）
        public String bankLetters = "";   // 题库原始字母
        public boolean mapped;            // 是否每个答案都在屏幕上按文本定位成功
        public String stem = "";          // 命中的题干（用于显示）
        public String source = "";        // 展示用来源文案
        /** 题库自带的解析（导入时 e 字段）。有就直接显示，不必再花一次在线调用去生成。 */
        public String explain = "";
    }

    private SelfBankMatch() {}

    /**
     * @param m        已加载的题库
     * @param stems    候选题干（短→长）
     * @param optTexts 屏幕选项文字（顺序与 letters 一致）
     * @param letters  屏幕选项字母（A/B/C…）
     * @return 命中结果；题库里没有、或不满足阈值返回 null
     */
    public static Hit find(Matcher m, List<String> stems, List<String> optTexts, String[] letters) {
        if (m == null || !m.isLoaded() || m.size() == 0) return null;
        if (stems == null || stems.isEmpty()) return null;

        Matcher.Hit h = m.matchBest(stems, optTexts);
        if (h == null || h.item == null) return null;

        Hit out = new Hit();
        out.raw = h;
        out.stem = (h.screen == null || h.screen.trim().isEmpty()) ? h.item.q : h.screen;
        out.bankLetters = join(h.item.ans);
        out.explain = h.item.explain == null ? "" : h.item.explain.trim();

        Set<String> mappedLetters = new LinkedHashSet<>();
        List<String> texts = h.item.okTexts;
        boolean all = true;
        if (texts != null && !texts.isEmpty() && letters != null && letters.length > 0) {
            for (String t : texts) {
                int idx = Matcher.findTextIndex(t, optTexts, 0.75, 0);
                if (idx >= 0 && idx < letters.length) mappedLetters.add(letters[idx]);
                else all = false;
            }
        } else {
            all = false;
        }

        if (!mappedLetters.isEmpty()) {
            out.letters = join(new ArrayList<>(mappedLetters));
            out.mapped = all && mappedLetters.size() == h.item.okTexts.size();
        } else {
            // 定位不到就退回题库字母；mapped=false，调用方不会自动点击
            out.letters = out.bankLetters;
            out.mapped = false;
        }

        StringBuilder src = new StringBuilder("自选题库");
        src.append(" · 相似 ").append(Math.round(h.score * 100)).append('%');
        if (h.item.variants > 1) src.append(" · 合并").append(h.item.variants).append("版");
        if (!out.mapped) src.append(" · 未定位到屏幕");
        out.source = src.toString();
        return out;
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        if (list != null) for (String s : list) if (s != null) sb.append(s);
        return sb.toString();
    }
}
