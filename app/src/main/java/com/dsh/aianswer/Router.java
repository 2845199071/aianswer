package com.dsh.aianswer;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 题型路由：决定这道题该给「在线大模型」还是「本地小模型」。
 *
 * <p>为什么需要它：本地 1.5B 跑常识短题又快又准（实测 100%/90%），但一碰到数学计算、
 * 文言文、长题干就明显掉分；而这些恰恰是在线模型的强项。按题型分流，等于用免费本地
 * 算力把便宜题吃掉，只在真正难的题上花在线额度。
 *
 * <p>策略是「宁可大不小」：只要命中任意一条难特征，就走在线；都不命中的短题才留给本地。
 * 规则全部是正则 + 长度 + 选项数，不引第三方 NLP，出问题看一眼就能改。
 */
public final class Router {

    /** 路由决策。 */
    public static final class Plan {
        public final boolean cloud;   // true = 在线大模型
        public final String reason;   // 「数学计算」「文言文」…直接显示在悬浮窗
        public final String tag;      // 规整后的一句话说明

        Plan(boolean cloud, String reason) {
            this.cloud = cloud;
            this.reason = reason;
            this.tag = cloud ? "大模型（在线）" : "小模型（本地）";
        }
    }

    private Router() {}

    // ---- 难特征：命中任一就走在线 ----

    /** 数学：运算符、分数、根号、百分比、常见计算动词。 */
    private static final Pattern MATH_SYM =
            Pattern.compile("[=＝+＋×÷×/√∑∫^<>≤≥%％]|\\d+\\s*[.．]\\s*\\d+");

    private static final Pattern MATH_WORD = Pattern.compile(
            "计算|求解|化简|等于多少|多少倍|平均|概率|面积|周长|体积|浓度|速度|利率|折旧|方差|方程|函数|不等式|导数|积分|排列组合");

    /** 文言文：虚词密集 / 加点词 / 古文引号。 */
    private static final Pattern CLASSIC = Pattern.compile(
            "之乎者也|焉|曰|文言文|加点词|下列句子中|古义|今义|通假|一词多义|句式|翻译|出自《|诗云|子曰");

    /** 专业条文：法规、医学、财务这类靠记忆+推理混合的题。 */
    private static final Pattern PRO = Pattern.compile(
            "法条第|条规定|应当|不得|责令|处罚|临床|诊断|剂量|会计|科目|税率|增值税|资产|负债|权益|规范|标准规定");

    /** 英语/外语：本地小模型对长英文题不稳。 */
    private static final Pattern ENGLISH = Pattern.compile(
            "\\b[A-Za-z]{4,}\\b.*\\b[A-Za-z]{4,}\\b.*\\b[A-Za-z]{4,}\\b");

    /**
     * 判断一道题走哪条路。
     *
     * @param question 题干（已剥离倒计时、题型前缀）
     * @param optLines 选项行（「A. 内容」）
     * @param multi    是否多选
     */
    public static Plan decide(String question, List<String> optLines, boolean multi) {
        String q = question == null ? "" : question.trim();
        int optCount = optLines == null ? 0 : optLines.size();

        if (q.isEmpty()) return new Plan(true, "题干为空");

        // 1) 数学
        if (MATH_SYM.matcher(q).find() || MATH_WORD.matcher(q).find()) {
            return new Plan(true, "数学计算");
        }
        // 2) 文言文
        if (CLASSIC.matcher(q).find()) {
            return new Plan(true, "文言文");
        }
        // 3) 长题干
        if (q.length() > 120) {
            return new Plan(true, "长题干");
        }
        // 4) 选项很多的多选
        if (multi && optCount >= 5) {
            return new Plan(true, "多选多项");
        }
        // 5) 专业条文
        if (PRO.matcher(q).find()) {
            return new Plan(true, "专业条文");
        }
        // 6) 英文长句
        if (ENGLISH.matcher(q).find() && q.length() > 60) {
            return new Plan(true, "英文长句");
        }

        // 7) 常识短题 → 本地
        boolean shortQ = q.length() <= 40;
        boolean shortOpts = true;
        if (optLines != null) {
            for (String l : optLines) {
                if (l != null && l.length() > 18) { shortOpts = false; break; }
            }
        }
        if (shortQ && shortOpts && !multi) {
            return new Plan(false, "短常识题");
        }

        // 8) 兜底：中等长度题给本地，省额度
        return new Plan(false, "常规题");
    }
}
