package com.dsh.aianswer;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * 自选题库检索门面（v7.2）：负责加载题库与统计，匹配逻辑在 {@link SelfBankMatch}。
 *
 * <p>题库有多份时全部合并检索——用户不用来回切库；
 * 同题干不同版本由 {@link Matcher#buildVariants} 自动合并答案。
 */
public final class SelfBank {

    private static Matcher matcher;
    private static String loadedSig;

    private SelfBank() {}

    public static synchronized Matcher get(Context c) {
        String sig = signature(c);
        if (matcher == null || !sig.equals(loadedSig)) {
            Matcher m = new Matcher();
            try {
                m.loadJson(SelfBankStore.mergedJson(c));
            } catch (Throwable ignored) {}
            matcher = m;
            loadedSig = sig;
        }
        return matcher;
    }

    public static synchronized void invalidate() {
        matcher = null;
        loadedSig = null;
    }

    private static String signature(Context c) {
        StringBuilder sb = new StringBuilder();
        for (SelfBankStore.Entry e : SelfBankStore.list(c)) {
            sb.append(e.id).append(':').append(e.count).append(';');
        }
        return sb.toString();
    }

    /**
     * 候选题干：从短到长。
     *
     * <p>读屏拼出来的题干常常夹着上一题残留、说明文字或标题，夹了噪声相似度会掉。
     * 这里额外给「最后一句」「最后两句」两种切分，让 Matcher 挑得分最高的那个。
     */
    public static List<String> stemsOf(String question) {
        List<String> out = new ArrayList<>();
        if (question == null) return out;
        String s = question.trim();
        if (s.isEmpty()) return out;

        String[] parts = s.split("[。？！?!]\\s*");
        if (parts.length >= 2) {
            String last = parts[parts.length - 1].trim();
            if (last.length() >= 6) out.add(last);
            if (parts.length >= 3) {
                String last2 = (parts[parts.length - 2] + "。" + last).trim();
                if (last2.length() > last.length()) out.add(last2);
            }
        }
        if (!out.contains(s)) out.add(s);
        return out;
    }

    /** 在自选题库里找这道题。没导入题库、或没命中都返回 null。 */
    public static SelfBankMatch.Hit find(Context c, List<String> stems,
                                         List<String> optTexts, String[] letters) {
        if (!SelfBankStore.hasBank(c)) return null;
        return SelfBankMatch.find(get(c), stems, optTexts, letters);
    }
}
