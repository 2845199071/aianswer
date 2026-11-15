package com.dsh.aianswer;

import java.util.List;

/**
 * Qwen2.5 的 ChatML 模板。
 * 结尾停在「答案：」而不是让模型自由发挥——配合 Llama.nativeScore 只比字母 token 的
 * logits，单题耗时只等于一次 prefill，不会拖出整段解释。
 */
public final class PromptBuilder {

    private static final String SYS_SINGLE =
            "你是严谨的答题助手。阅读题目和选项，选出唯一正确的选项，只回答一个字母。";
    private static final String SYS_MULTI =
            "你是严谨的答题助手。阅读题目和选项，选出所有正确的选项，只回答字母组合，例如 ABD。";

    private PromptBuilder() {}

    public static String build(String question, List<String> optionLines, boolean multi) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<|im_start|>system\n")
          .append(multi ? SYS_MULTI : SYS_SINGLE)
          .append("<|im_end|>\n");
        sb.append("<|im_start|>user\n")
          .append(question == null ? "" : question.trim());
        if (optionLines != null && !optionLines.isEmpty()) {
            sb.append('\n');
            for (String l : optionLines) sb.append(l).append('\n');
        }
        sb.append("<|im_end|>\n<|im_start|>assistant\n答案：");
        return sb.toString();
    }

    /**
     * 解析用提示词：先给题目和已定答案，再让模型用一句话说依据。
     * 结尾同样停在「依据：」，避免模型又开始复述题目，只生成 28 token 就够。
     */
    public static String buildExplain(String question, List<String> optionLines, String answer) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<|im_start|>system\n")
          .append("你是答题助手。用一句不超过30字的中文说明选这个答案的依据，不复述题目，不分点。")
          .append("<|im_end|>\n");
        sb.append("<|im_start|>user\n")
          .append(question == null ? "" : question.trim());
        if (optionLines != null && !optionLines.isEmpty()) {
            sb.append('\n');
            for (String l : optionLines) sb.append(l).append('\n');
        }
        sb.append("\n已选答案：").append(answer == null ? "" : answer)
          .append("<|im_end|>\n<|im_start|>assistant\n依据：");
        return sb.toString();
    }
}
