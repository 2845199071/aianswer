package com.dsh.aianswer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * OCR 结果的行整理层：ML Kit 的原始行不能直接喂给 {@link Layout#parse}。
 *
 * <p>读屏那边的节点天生是「一行一个节点」，而 OCR 出来的行有这样几种毛病（都是实测遇到的）：
 * <ul>
 *   <li>选项字母和选项文字被拆成两个框，而且文字框的 top 常常比字母框还小（顺序颠倒）；</li>
 *   <li>字母和文字粘连在一起没有空格：`c均匀涂抹无堆积`；</li>
 *   <li>把自家悬浮窗的字也读进去了：`识别中…`、`手动触发·正在读屏`；</li>
 *   <li>顶部状态栏、题型标签 `/多选`、底部按钮 `全选`/`提交答案` 混在正文里，
 *       其中 `/多选` 的 top 只比题干小 8px，会顶掉题干。</li>
 * </ul>
 *
 * <p>所以这里先清洗、再把「单独字母行」和它右边那一行合起来、最后严格按 top 排序
 * （不走读屏那套「12px 内算同一行」的规则——正是那条规则让 `/多选` 排到了题干前面）。
 */
public final class OcrText {

    private OcrText() { }

    // 自家悬浮窗的文案：截图会把它们一起拍进去，必须剔除
    private static final String[] SELF_UI = {
            "识别中", "手动触发", "正在读屏", "待命中", "读屏未通过",
            "已切图片识别", "图片识别中", "正在截屏认字", "截图认字",
            "校对中", "视觉读题中", "思考中", "复核中", "截屏",
    };

    // 页面按钮/底部统计：不是题目内容
    private static final String[] BUTTONS = {
            "全选", "提交答案", "交卷", "确认提交", "下一题", "下一頁", "下一页",
            "分享", "收藏", "已做题", "立即开通", "开通会员",
    };

    /** `/多选`、`（单选）`、`多选` 这类题型标签。 */
    private static final Pattern TYPE_TAG =
            Pattern.compile("^[/／|｜(（【\\[]*\\s*(单选题?|多选题?|判断题?|不定项选择题?|选择题)\\s*[)）】\\]]*$");

    /** 纯符号 / 纯数字的碎行：状态栏的 `.6259`、`l 82`、`•●.` 之类。 */
    private static final Pattern SYMBOL_ONLY = Pattern.compile("^[^A-Za-z\\u4e00-\\u9fa5]+$");

    /** 状态栏时间、网速、电量。 */
    private static final Pattern STATUS_LIKE =
            Pattern.compile("(KB/S|kB/s|Mbps|^\\d{1,2}[:：]\\d{2}$|^\\d+%$)");

    /** 字母和中文粘连：`c均匀涂抹无堆积` → `c 均匀涂抹无堆积`。只处理后面紧跟汉字的情况，避开 cm/kg 这类单位。 */
    private static final Pattern GLUED = Pattern.compile("^([A-Ea-e])([\\u4e00-\\u9fa5].*)$");

    /** 字母后跟杂符：`A /每年润滑維护一次` → `A 每年润滑維护一次`。 */
    private static final Pattern LETTER_JUNK = Pattern.compile("^([A-Ea-e])[\\s.、．:：)）]*[/／|｜]+\\s*");

    /** 单独成一个框的选项字母：`B`、`B.`、`（B）`。 */
    private static final Pattern LONE_LETTER = Pattern.compile("^[（(]?([A-Ea-e])[)）]?[.、．:：]?$");

    public static List<OcrEngine.Line> normalize(List<OcrEngine.Line> in) {
        return normalize(in, null);
    }

    public static List<OcrEngine.Line> normalize(List<OcrEngine.Line> in, Metrics metrics) {
        final Metrics mt = metrics != null ? metrics : new Metrics(1280, 2772);
        List<OcrEngine.Line> out = new ArrayList<>();
        if (in == null) return out;

        // ① 清洗
        for (OcrEngine.Line l : in) {
            if (l == null || l.text == null) continue;
            String t = l.text.trim();
            if (t.isEmpty()) continue;
            if (isSelfUi(t)) continue;
            if (isButton(t)) continue;
            if (TYPE_TAG.matcher(t).matches()) continue;
            if (SYMBOL_ONLY.matcher(t).matches()) continue;
            if (STATUS_LIKE.matcher(t).find()) continue;
            if (t.length() == 1 && !LONE_LETTER.matcher(t).matches()) continue;

            // 粘连与杂符规范化
            java.util.regex.Matcher g = GLUED.matcher(t);
            if (g.matches()) t = g.group(1) + " " + g.group(2);
            t = LETTER_JUNK.matcher(t).replaceFirst("$1 ");
            t = t.replaceAll("\\s{2,}", " ").trim();
            if (t.isEmpty()) continue;

            OcrEngine.Line c = new OcrEngine.Line();
            c.text = t;
            c.left = l.left; c.top = l.top; c.right = l.right; c.bottom = l.bottom;
            out.add(c);
        }

        // ② 单独字母行 + 它右边那一行，合成一行（B 和「使用专用导轨润滑剂」被拆开的场景）
        boolean[] used = new boolean[out.size()];
        for (int i = 0; i < out.size(); i++) {
            OcrEngine.Line a = out.get(i);
            if (used[i]) continue;
            java.util.regex.Matcher m = LONE_LETTER.matcher(a.text);
            if (!m.matches()) continue;

            int best = -1, bestDy = Integer.MAX_VALUE;
            for (int j = 0; j < out.size(); j++) {
                if (j == i || used[j]) continue;
                OcrEngine.Line b = out.get(j);
                int dy = Math.abs(b.top - a.top);
                if (dy > mt.sameLineTol) continue;            // 必须基本同一行
                if (b.left <= a.left) continue;               // 必须在字母右侧
                if (b.left > a.left + mt.rightReach) continue; // 不能跑太远（比例，不是写死 900px）
                if (LONE_LETTER.matcher(b.text).matches()) continue;
                if (dy < bestDy) { bestDy = dy; best = j; }
            }
            if (best >= 0) {
                OcrEngine.Line b = out.get(best);
                a.text = m.group(1) + " " + b.text;
                // 框取并集：点中心会更靠近整行，命中率比只点字母高
                a.left   = Math.min(a.left, b.left);
                a.top    = Math.min(a.top, b.top);
                a.right  = Math.max(a.right, b.right);
                a.bottom = Math.max(a.bottom, b.bottom);
                used[best] = true;
            }
        }

        List<OcrEngine.Line> merged = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) if (!used[i]) merged.add(out.get(i));

        // ③ 严格按 top 排序（同一 top 再按 left）。
        //    这里**故意不用**「差 12px 算同一行」那条规则：`/多选` 和题干只差 8px，
        //    一合并就会把题型标签排到题干前面，题干直接被顶掉。
        Collections.sort(merged, new Comparator<OcrEngine.Line>() {
            public int compare(OcrEngine.Line a, OcrEngine.Line b) {
                if (a.top != b.top) return a.top < b.top ? -1 : 1;
                return a.left < b.left ? -1 : (a.left == b.left ? 0 : 1);
            }
        });
        return merged;
    }

    /** 一列选项：行下标、字母、首尾范围。 */
    public static final class Block {
        public final List<Integer> idx = new ArrayList<>();
        public String letters = "";
    }

    /**
     * 几何阈值：**全部按屏幕尺寸的比例算，不写死像素**。
     *
     * <p>写死像素的版本只在 1280×2772 上验证过，换台设备就失准：
     * 720p 手机的选项行距只有 ~90px，会被「下限 120」直接刷掉；
     * 2K 屏行距 ~260px，又被「上限 230」拒掉；平板更离谱。
     * 现在统一用屏幕宽/高的百分比，任何分辨率、任何字号密度都按同一套相对尺度判断。
     */
    public static final class Metrics {
        public final int w, h;
        public final int gapMin, gapMax;   // 选项行距上下限
        public final int leftTol;          // 同一列左边界容差
        public final int qLeftTol;         // 题干与选项列的对齐容差
        public final int qBreakGap;        // 题干往上收时的断段距离
        public final int wideBox;          // 「框很宽却只有几个字」的宽度阈值
        public final int sameLineTol;      // 字母行与文字行算「同一行」的高度差
        public final int rightReach;       // 文字行相对字母行最远能右偏多少
        public final int vDedup, hDedup;   // 去重时的纵向/横向容差

        public Metrics(int w, int h) {
            this.w = Math.max(1, w);
            this.h = Math.max(1, h);
            gapMin       = Math.max(40,  Math.round(this.h * 0.030f));
            gapMax       = Math.max(100, Math.round(this.h * 0.105f));
            leftTol      = Math.max(16,  Math.round(this.w * 0.050f));
            qLeftTol     = Math.max(48,  Math.round(this.w * 0.120f));
            qBreakGap    = Math.max(60,  Math.round(this.h * 0.070f));
            wideBox      = Math.max(100, Math.round(this.w * 0.190f));
            sameLineTol  = Math.max(16,  Math.round(this.h * 0.016f));
            rightReach   = Math.max(240, Math.round(this.w * 0.700f));
            vDedup       = Math.max(10,  Math.round(this.h * 0.010f));
            hDedup       = Math.max(20,  Math.round(this.w * 0.050f));
        }
    }

    /**
     * 按**几何结构**认选项，而不是靠关键词排除广告。
     *
     * <p>答题页的选项是一个稳定结构：字母递增、纵向等距、左边界几乎重合。
     * 实测一题：A/B/C/D 的 top 是 985/1160/1329/1498（间距 175/169/169），
     * left 是 83/85/84/83（几乎完全一样）；而同一屏的广告行 left 是 295 和 545，
     * 根本不在这一列上。
     *
     * <p>所以只要「字母递增 + 间距 120~230 + 左边界差 ≤60」成立，它就一定是选项列；
     * 不在这一列里的文字，无论写的是什么，都不参与读题。广告文案可以天天换，
     * 但它改不了「跟选项不在同一列」这个事实。
     *
     * @return 找到的选项列；少于 2 个返回 null，交给原来的解析兜底
     */
    public static Block findOptionBlock(List<OcrEngine.Line> lines, Metrics m) {
        if (lines == null || lines.size() < 2 || m == null) return null;
        Block best = null;
        for (int i = 0; i < lines.size(); i++) {
            // 只从「认出了合法字母」的行起。字母全丢的行不可能是这一列的起点。
            if (letterOf(lines.get(i).text) == 0) continue;
            Block b = grow(lines, i, m);
            if (b == null) continue;
            if (best == null || b.idx.size() > best.idx.size()) best = b;
        }
        if (best == null || best.idx.size() < 2) return null;
        return best;
    }

    /**
     * 从 start 起向后吃同一列的行。
     *
     * <p>关键容错：**字母漏认或误认的行照样收**。实测有一题 A/C 正常，但 B 的字母整个没认出来
     * （只剩「6年」），D 被认成「×) 10年」——按「字母必须递增」的严格规则，这一列当场断掉。
     * 所以这里只要求：
     * <ul>
     *   <li>行距在 120~230 之间；</li>
     *   <li>**认出字母的行**必须左边界对齐（没字母的行允许文字左移，因为字母位空出来了）；</li>
     *   <li>整列至少 2 行真的认出了字母，避免把广告段落误认成选项。</li>
     * </ul>
     * 字母最终**按位置重新生成**（第 k 行 = 首行字母 + k），漏认的 B 和误认的「×)」都会被纠正。
     */
    private static Block grow(List<OcrEngine.Line> lines, int start, Metrics m) {
        Block b = new Block();
        b.idx.add(start);
        OcrEngine.Line prev = lines.get(start);
        // 列基准只用「认出字母的行」：字母漏认时文字会左移补位（实测「6年」left 178 vs A 的 82），
        // 拿它当基准会把后面的 C 判成「不在同一列」。
        OcrEngine.Line colBase = prev;
        int noLetter = 0;
        for (int j = start + 1; j < lines.size() && b.idx.size() < 6; j++) {
            OcrEngine.Line cur = lines.get(j);
            int gap = cur.top - prev.top;
            if (gap < m.gapMin) continue;           // 太近：同一行的小字或上一行的残余
            if (gap > m.gapMax) break;              // 太远：这一列结束了
            char c = letterOf(cur.text);
            if (c != 0) {
                // 有字母但不在这一列：多半是插进来的广告（实测「A 爆款推荐别错过」夹在 B 和 C 之间）。
                // 跳过它继续往下找，不要断列。
                if (Math.abs(cur.left - colBase.left) > m.leftTol) continue;
                colBase = cur;
            } else {
                // 结果页的「正确答案B您选择D」这类文字，位置正好落在 D 下面，不拦住就会变成第 5 个选项
                if (isResultLine(cur.text)) break;
                if (++noLetter > 2) break;
            }
            b.idx.add(j);
            prev = cur;
        }

        int letterRows = 0;
        for (int k : b.idx) if (letterOf(lines.get(k).text) != 0) letterRows++;
        if (letterRows < 2) return null;

        char firstLetter = letterOf(lines.get(start).text);
        if (firstLetter == 0) firstLetter = 'A';
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < b.idx.size(); k++) {
            char ch = (char) (firstLetter + k);
            if (ch > 'E') break;
            sb.append(ch);
        }
        while (b.idx.size() > sb.length()) b.idx.remove(b.idx.size() - 1);
        b.letters = sb.toString();
        return b;
    }

    /**
     * 题干噪声清洗：进度、题型标签。
     *
     * <p>实测一行题干经常被拼成「N 41/112 单进]梳齿极处梯级导轮轴向窜动量童厂标限」——
     * 前面的进度和「单选」的误识（`单进]`）都不是题目内容，发给模型只会干扰判断。
     */
    private static String stripQuestionNoise(String t) {
        String s = t.trim();
        s = s.replaceAll("^[A-Za-z]?\\s*\\d+\\s*/\\s*\\d+\\s*", "");                 // N 41/112
        s = s.replaceAll("^[/／]?\\s*(单|多|判)[选进]?题?[\\]）】)]?\\s*", "");        // 单进] / 单选
        return s.trim();
    }

    /**
     * 选项符号纠错。
     *
     * <p>`≤` 是 ML Kit 的重灾区：实测同一屏四个选项里，同一个符号被认成了
     * `A K`、`公`、`≤`、`不` 四种样子。这种字符靠单个规则永远补不全，
     * 但它们出现在**同一个位置**（选项正文开头、紧跟数字），所以这里统一归一化。
     *
     * <p>只处理「开头一个可疑字符 + 紧跟数字」的情况，正常的中文选项不会被碰到。
     */
    public static String fixOptionBody(String raw) {
        String t = stripLetter(raw);
        if (t.isEmpty()) return t;

        // ① 「K0.3」「A K0.3」：≤ 被认成 K/公/不，后面那个 O 其实是数字 0
        t = t.replaceAll("^([Aa]\\s*)?[Kk公不＜<〈Ss]\\s*[Oo](?=[0-9０-９.])", "≤0");
        // ② 「公.5mm」：没有 O 的情况，先补出 ≤
        t = t.replaceAll("^([Aa]\\s*)?[Kk公不＜<〈Ss]\\s*(?=[0-9０-９.])", "≤");
        // ③ 「≤O.8mm」：符号对了但 0 被认成 O
        t = t.replaceAll("^[≤＜<]\\s*[Oo](?=[0-9０-９.])", "≤0");
        // ④ 「公.5mm → ≤.5mm」再补回丢失的 0
        t = t.replaceAll("^[≤＜<]\\s*\\.", "≤0.");
        // ⑤ 数字中间的 O
        t = t.replaceAll("(?<=[0-9≤])[Oo](?=[0-9.])", "0");
        // 全角数字转半角，免得模型把 ０.８ 当成别的
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '０' && c <= '９') sb.append((char) ('0' + (c - '０')));
            else sb.append(c);
        }
        return sb.toString().trim();
    }

    /** 答题结果页的说明文字（不是选项）。 */
    private static boolean isResultLine(String t) {
        if (t == null) return false;
        return t.contains("正确答案") || t.contains("你选择") || t.contains("本人作答")
                || t.contains("试题详解") || t.contains("你的选择") || t.contains("纠错");
    }

    /** 这一行是不是以选项字母开头；不是返回 0。`Apple`/`Base` 这类英文单词要排除。 */
    private static char letterOf(String t) {
        if (t == null || t.isEmpty()) return 0;
        char c = Character.toUpperCase(t.charAt(0));
        if (c < 'A' || c > 'E') return 0;
        if (t.length() == 1) return c;
        char n = t.charAt(1);
        if (n < 128 && Character.isLetter(n)) return 0;   // 后面直接跟英文字母 → 不是选项
        return c;
    }

    /**
     * 去掉行首的选项字母和对错标记：
     * `A t` → `t`，`（B）内容` → `内容`，`×) 10年` → `10年`。
     * 最后那条是实测来的：字母认不出来时，答题页的「×」错题标记会顶到行首。
     */
    public static String stripLetter(String t) {
        if (t == null) return "";
        String s = t.replaceFirst("^[（(]?[A-Ea-e][)）]?[\\s.、．:：]*", "");
        s = s.replaceFirst("^[×√✓☑☐○●·\\s)）\\]】]+", "");
        return s.trim();
    }

    /**
     * OCR 专用的题干回收。
     *
     * <p>为什么需要：{@link Layout#parse} 里「相邻行距 > 90px 就断段」是为读屏写的
     * （读屏不会把一句话拆成两个节点），但 OCR 经常把长题干拆成两行，
     * 实测 717 和 825 相差 108px 就被切断，题干只剩最后一行的「是?」，长度 2 直接判失败。
     *
     * <p>这里从第一个选项往上走，规则放宽到 185px，并且：
     * 遇到「单选/多选/判断」这类题型标签时，把它剥掉、收下这一行、然后**立即停止**——
     * 因为这个标签就是题干的第一行；这样既能收全长题干，又不会把上面的广告并进来。
     *
     * @param lines    已经过 {@link #normalize} 的行（严格按 top 排序）
     * @param firstOpt 第一个选项行的下标，传 -1 表示没有选项
     */
    public static String extractQuestion(List<OcrEngine.Line> lines, int firstOpt, Metrics m) {
        if (lines == null || firstOpt <= 0 || firstOpt >= lines.size()) return "";
        final int optLeft = lines.get(firstOpt).left;
        StringBuilder sb = new StringBuilder();
        int prevTop = lines.get(firstOpt).top;

        for (int i = firstOpt - 1; i >= 0; i--) {
            OcrEngine.Line l = lines.get(i);
            if (l == null || l.text == null) continue;
            int gap = Math.abs(prevTop - l.top);
            if (m != null && gap > m.qBreakGap) break;   // 隔得太远：上面是广告或别的题

            String t = l.text.trim();
            if (t.isEmpty()) continue;

            // 题型标签是题干的**第一行**，剥掉它、收下、立刻结束
            boolean hasTag = t.contains("单选") || t.contains("多选")
                    || t.contains("判断") || t.contains("不定项");
            if (hasTag) {
                t = t.replaceFirst("^\\s*[/／]?(单选题?|多选题?|判断题?|不定项选择题?|选择题)\\s*[:：]?\\s*", "");
                if (!t.isEmpty()) {
                    if (sb.length() > 0) sb.insert(0, ' ');
                    sb.insert(0, t);
                }
                break;
            }

            // 进度「N 41/112」、误识标签「单进]」都不是题目内容，剥掉后再决定要不要收
            t = stripQuestionNoise(t);
            if (t.isEmpty()) {
                prevTop = l.top;
                continue;
            }

            /*
             * 关键约束：题干必须和选项列左对齐。
             *
             * 之前只按行距往上收，结果实测把整条京东广告都收进了题干（题干长度 109）：
             * 真题干 left 69、选项 left 84（差 15，对齐），
             * 而紧挨着的「&斩题」left 1079、广告行 left 300~550，全都不在这一列。
             * 所以一旦遇到左边界对不上的行，就说明已经走出题目区域了，直接停。
             */
            if (m != null && Math.abs(l.left - optLeft) > m.qLeftTol) break;

            if (sb.length() > 0) sb.insert(0, ' ');
            sb.insert(0, t);
            prevTop = l.top;
            if (sb.length() > 150) break;
        }
        return sb.toString().trim();
    }

    private static boolean isSelfUi(String t) {
        for (String s : SELF_UI) if (t.contains(s)) return true;
        return false;
    }

    private static boolean isButton(String t) {
        for (String s : BUTTONS) if (t.equals(s)) return true;
        return false;
    }

    /**
     * 这一屏是不是「已经答过」的结果页。
     *
     * <p>比文本相似度可靠得多：结果页会出现「正确答案 B」「您选择 A」这类字样，
     * 而题目页永远不会。用它来防「同一道题每隔几秒被重新答一遍」。
     */
    public static boolean looksAnswered(java.util.List<OcrEngine.Line> lines) {
        if (lines == null) return false;
        for (OcrEngine.Line l : lines) {
            if (l == null || l.text == null) continue;
            String t = l.text.replaceAll("\\s", "");
            if (t.contains("您选择") || t.contains("回答正确") || t.contains("回答错误")
                    || t.contains("我的答案") || t.contains("参考答案")) return true;
            // 「正确答案 B」这种，必须同时带字母，避免题干里出现「正确答案是」被误判
            if (t.contains("正确答案") && t.matches(".*[A-E].*")) return true;
        }
        return false;
    }

    /**
     * 两段文字有多像（0~1）：按最长公共子序列算。
     *
     * <p>OCR 每次结果都会有细微差别（少一个标点、认错一个字），
     * 用它来判断「屏幕还是同一道题」，比精确比对签名可靠。
     */
    public static float similar(String a, String b) {
        if (a == null || b == null) return 0f;
        int n = a.length(), m = b.length();
        if (n == 0 || m == 0) return 0f;
        if (a.equals(b)) return 1f;
        // 长度差太多就不必算了，肯定不是同一题
        if (Math.min(n, m) * 2 < Math.max(n, m)) return 0f;

        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                cur[j] = ca == b.charAt(j - 1)
                        ? prev[j - 1] + 1
                        : Math.max(prev[j], cur[j - 1]);
            }
            int[] t = prev; prev = cur; cur = t;
            java.util.Arrays.fill(cur, 0);
        }
        return (float) prev[m] / Math.max(n, m);
    }
}
