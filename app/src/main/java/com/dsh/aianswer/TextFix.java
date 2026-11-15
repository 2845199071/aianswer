package com.dsh.aianswer;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用**本地模型**修正 OCR 的错别字。
 *
 * <p>为什么需要：OCR 会把形近字认错 —— 实测「磨损」认成「唐损」、「更换」认成「更焕」、
 * 「围裙板」认成「国裙板」。这类错误几何校验（行距、对齐、广告隔离）和分辨率都救不了，
 * 因为它们不是漏字或错位，而是把字认成了另一个字。语言模型靠上下文能判出来。
 *
 * <p>约束：
 * <ul>
 *   <li><b>只修错别字</b>，prompt 里明确禁止改写、补充、改变题意；</li>
 *   <li>输出行数必须与输入选项数一致，否则整条结果作废、保留原文 ——
 *       宁可留着错别字，也不能让模型把题目改跑偏；</li>
 *   <li>全程本地推理，不联网、不产生费用。</li>
 * </ul>
 */
public final class TextFix {

    private static final String TAG = "AIQ";
    private static final int MAX_TOK = 220;

    /** 连续几次输出不合格就停用校对：否则每题白等 2 秒，用户看到的就是「一直卡在校对」。 */
    private static final int GIVE_UP = 3;
    private static volatile int failStreak = 0;

    private static final Pattern OPT_LINE =
            Pattern.compile("^[（(]?([A-Ea-e])[)）]?[.、．:：、\\s]\\s*(.+)$");

    private TextFix() { }

    public static final class Result {
        public String question = "";
        public final List<String> options = new ArrayList<>();
    }

    /**
     * @return 修正后的结果；本地模型没就绪、生成失败、或输出格式不对时返回 null（调用方用原文）
     */
    public static Result fix(String question, List<String> optionLines) {
        if (question == null || question.trim().isEmpty()) return null;
        if (optionLines == null || optionLines.isEmpty()) return null;
        if (!AnswerEngine.get().ready()) return null;
        if (failStreak >= GIVE_UP) return null;      // 这台设备上模型不听话，别再拖慢答题

        try {
            String out = AnswerEngine.get().genLocal(build(question, optionLines), MAX_TOK);
            Result r = parse(out, optionLines.size(), question);
            if (r == null) {
                if (++failStreak >= GIVE_UP) {
                    Log.i(TAG, "校对连续 " + GIVE_UP + " 次不合格，本次运行不再校对");
                }
                Log.i(TAG, "校对结果不可用，保留原文");
                return null;
            }
            failStreak = 0;
            Log.i(TAG, "校对：" + question + " → " + r.question);
            return r;
        } catch (Throwable t) {
            Log.w(TAG, "校对异常", t);
            return null;
        }
    }

    /** 重新载入本地模型后调用，让校对重新有机会跑。 */
    public static void reset() { failStreak = 0; }

    private static String build(String q, List<String> opts) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<|im_start|>system\n")
          .append("你是文字校对员。下面是一段从图片识别出来的选择题文字，可能有个别错别字（形近字被认错）。")
          .append("请只修正明显的识别错误，不要改写句子、不要补充内容、不要改变题意、不要给出答案。")
          .append("按原格式输出：第一行是题干，之后每行「A. 选项」。只输出文字，不要解释。")
          .append("<|im_end|>\n<|im_start|>user\n");
        sb.append(q.trim()).append('\n');
        for (String o : opts) sb.append(o).append('\n');
        sb.append("<|im_end|>\n<|im_start|>assistant\n");
        return sb.toString();
    }

    /** 严格解析：选项数对不上就整条作废，避免模型改出新选项或漏行。 */
    private static Result parse(String out, int wantOpts, String originQ) {
        if (out == null) return null;
        Result r = new Result();
        for (String raw : out.split("\n")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("<|") || s.startsWith("assistant")) continue;

            Matcher m = OPT_LINE.matcher(s);
            // 选项行：只要后半截有内容就收。原来要求「题干已在前面」，
            // 但小模型经常直接从 A 开始输出，那样会把整条结果废掉。
            if (m.matches()) {
                r.options.add(m.group(1).toUpperCase() + ". " + m.group(2).trim());
                continue;
            }
            if (r.question.isEmpty() && r.options.isEmpty()) {
                r.question = s.replaceFirst("^(题干|题目|修正后)[:：]\\s*", "").trim();
            }
        }
        // 模型没给题干（或者一上来就列选项）：沿用 OCR 原文，只采纳它对选项的修正
        if (r.question.isEmpty()) r.question = originQ == null ? "" : originQ.trim();
        if (r.question.isEmpty() || r.options.size() != wantOpts) return null;
        return r;
    }
}
