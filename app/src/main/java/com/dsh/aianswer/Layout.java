package com.dsh.aianswer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 纯 Java 的版面解析：给一串屏幕文字，认出题干、选项、「下一题」。方便在 JVM 上测。 */
public class Layout {

    public static class Opt {
        public String letter;
        public String text;
        public int index;          // 对应 texts 下标（点击用）
    }

    public static class Parsed {
        public String question = "";
        public final List<Opt> options = new ArrayList<>();
        public final List<Integer> nextCandidates = new ArrayList<>();
        public boolean judge;
        public boolean inline;      // 题干与选项在同一节点
    }

    // 兼容 "A. 内容" / "A、内容" / "（A）内容" / "A 内容" 四种写法
    private static final Pattern OPT_FULL = Pattern.compile("^\\s*[（(]?([A-Ea-e])[)）.、．:：,，\\s]\\s*(\\S.*)$");
    // 字母单独成节点："A" / "A." / "（A）"
    private static final Pattern OPT_BARE = Pattern.compile("^\\s*[（(]?([A-Ea-e])[)）]?[.、．:：]?\\s*$");
    private static final Pattern NUM_ONLY = Pattern.compile("^\\s*[\\d.、．)）]+\\s*$");

    // 分值标记：题干末尾常带「（1分）」「(2.5分)」「【本题2分】」，对匹配是纯噪声
    private static final Pattern SCORE_BRACKET =
            Pattern.compile("[（(【\\[]\\s*[^（()）【】\\[\\]]{0,6}?[0-9０-９]+(?:\\.[0-9０-９]+)?\\s*分\\s*[)）】\\]]");
    // 尾部裸分值：…… 1分 / …… 2.5分
    private static final Pattern SCORE_TAIL =
            Pattern.compile("(?:^|\\s)[0-9０-９]+(?:\\.[0-9０-９]+)?\\s*分\\s*$");
    // 整行只有一个分值标记：这类节点不该占用题干的 4 段预算
    private static final Pattern SCORE_ONLY =
            Pattern.compile("^[（(【\\[]?\\s*[0-9０-９]+(?:\\.[0-9０-９]+)?\\s*分\\s*[)）】\\]]?$");

    // 纯时间/倒计时（"27:38"、"01:23:45"）：答题平台每秒都在刷，绝不能进题干，
    // 否则同一道题每拍都被当成"新题"重新推理
    private static final Pattern TIME_ONLY =
            Pattern.compile("^\\s*[0-9]{1,2}\\s*[:：]\\s*[0-9]{2}(\\s*[:：]\\s*[0-9]{2})?\\s*$");
    // "剩余 12:30" / "用时 03:20" / "倒计时 1:00" / "已答 3/10"
    private static final Pattern TIME_LABEL =
            Pattern.compile("^\\s*(剩余|用时|已用|倒计时|计时|已答|进度|第)\\s*[^\\u4e00-\\u9fa5]{0,6}$");
    // 题干里混进来的时间片段，只在首尾剥离，不动正文中间的
    private static final Pattern TIME_EDGE =
            Pattern.compile("^\\s*[0-9]{1,2}\\s*[:：]\\s*[0-9]{2}(\\s*[:：]\\s*[0-9]{2})?\\s*|\\s*[0-9]{1,2}\\s*[:：]\\s*[0-9]{2}(\\s*[:：]\\s*[0-9]{2})?\\s*$");

    // 题干开头的「13.」「13、」「第13题」——(?!\\d) 防止把「1.5 倍」的 1. 当成题号
    private static final Pattern NUM_PREFIX =
            Pattern.compile("^\\s*\\d{1,3}\\s*[.、．)）]\\s*(?![0-9])");
    private static final Pattern DI_PREFIX =
            Pattern.compile("^\\s*第\\s*\\d+\\s*题\\s*[.、．:：]?\\s*");

    // 题干开头的题号进度：「7/50 」
    private static final Pattern PROGRESS_PREFIX = Pattern.compile("^\\s*\\d+\\s*/\\s*\\d+\\s*");
    // 题干开头的括号题型：「（单选题）」
    private static final Pattern BRACKET_TYPE = Pattern.compile(
            "^\\s*[（(【\\[]\\s*(单选题?|多选题?|判断题?|选择题|不定项选择题?)\\s*[）)】\\]]\\s*");

    // 题干开头的题型标签：「单选题」「多选题（第1题/共10题）」。
    // 括号段必须成对出现，否则 {0,24} 会把整句题干吃掉
    private static final Pattern TYPE_PREFIX = Pattern.compile(
            "^\\s*(单选题?|多选题?|判断题?|不定项选择题?|选择题)\\s*([（(【\\[]\\s*[^）)】\\]]{0,24}[）)】\\]]\\s*)?");

    static final String[] NEXT_WORDS = {"下一题", "下一頁", "下一页", "下一道", "下一", "继续答题", "继续", "下一步"};
    static final String[] STOP_WORDS = {"交卷", "提交试卷", "确认提交", "提交答案"};

    public static boolean isNextWord(String t) {
        for (String w : NEXT_WORDS) if (t.equals(w) || (t.length() <= 4 && t.contains(w))) return true;
        return false;
    }

    /** 题号、进度、题型标签这类不该进题干的文字。 */
    public static boolean isJunk(String t) {
        String s = t.trim();
        if (s.isEmpty()) return true;
        if (NUM_ONLY.matcher(s).matches()) return true;
        if (SCORE_ONLY.matcher(s).matches()) return true;                 // 单独一行的「（1分）」
        if (TIME_ONLY.matcher(s).matches()) return true;                  // 「27:38」倒计时
        if (TIME_LABEL.matcher(s).matches()) return true;                 // 「剩余 12:30」
        if (s.matches("^(单选题?|多选题?|判断题?|选择题|不定项)$")) return true;
        // 只挡「纯题号」形式（第12题 / 12题 / 43题未答）。必须限制长度：
        // 原来的 `\d+[题/].*` 会把「7/50 (单选题)环境25℃低温工况缓冲器用油？」整道题判成噪声，
        // 导致题干往前倒推时跳过题目、落到页面标题上——这正是"识别成标题"的真凶。
        if (s.length() <= 12 && s.matches("^第?\\s*\\d+\\s*[题/／].*$")) return true;
        if (s.matches("^\\d+\\s*[/／]\\s*\\d+\\s*题?$")) return true;      // 12/50
        if (s.matches("^共\\s*\\d+\\s*题.*$")) return true;
        if (s.matches("^本题.*$") || s.matches("^得分.*$") || s.matches("^分值.*$")) return true;
        return false;
    }

    /**
     * 去掉题干首尾的动态时间片段和题型前缀。
     * 答题页常见「单选题（第1题/共10题）以下哪个是……27:38」，前两种对模型是纯噪声，
     * 倒计时还会每秒变化导致同一道题算出不同题干、上层判重失效。
     */
    public static String stripDynamic(String s) {
        if (s == null) return "";
        String t = s;
        // 「7/50 (单选题)题干(2分)」这种前缀可能有好几段，跑几轮直到不变
        for (int i = 0; i < 3; i++) {
            String prev = t;
            t = DI_PREFIX.matcher(t).replaceFirst("");
            t = NUM_PREFIX.matcher(t).replaceFirst("");
            t = PROGRESS_PREFIX.matcher(t).replaceFirst("");
            t = BRACKET_TYPE.matcher(t).replaceFirst("");
            t = TYPE_PREFIX.matcher(t).replaceFirst("");
            if (t.equals(prev)) break;
        }
        t = TIME_EDGE.matcher(t).replaceAll("");
        return t.replaceAll("\\s+", " ").trim();
    }

    public static boolean isStopWord(String t) {
        for (String w : STOP_WORDS) if (t.contains(w)) return true;
        return false;
    }

    /**
     * 去掉题干里的分值标记。
     * 屏幕上的题干常以「（1分）」收尾，归一化时「1分」会被当成正文留下来，把题干污染成
     * 「……红表笔插入孔1分」，导致本来能精确命中的题只能靠相似度硬扛。
     * 这里只清洗待匹配的文本，题库本身不动。
     */
    public static String stripScore(String s) {
        if (s == null) return null;
        String t = SCORE_BRACKET.matcher(s).replaceAll("");
        t = SCORE_TAIL.matcher(t).replaceAll("");
        return t.trim();
    }

    public static Parsed parse(List<String> texts) {
        return parse(texts, null);
    }

    /** pos 为每个 text 的屏幕坐标 {top, left}，可为 null（手动粘贴时没有坐标）。 */
    public static Parsed parse(List<String> texts, List<int[]> pos) {
        Parsed r = new Parsed();
        int firstOpt = -1;
        for (int i = 0; i < texts.size(); i++) {
            if (isOptionLine(texts, i)) { firstOpt = i; break; }
        }

        // 题干
        if (firstOpt > 0) {
            if (pos != null && pos.size() == texts.size()) {
                extractByPosition(r, texts, pos, firstOpt);
            } else {
                extractByOrder(r, texts, firstOpt);
            }
        } else {
            String best = "";
            for (String raw : texts) {
                String t = raw.trim();
                if (isNextWord(t) || isStopWord(t) || isJunk(t)) continue;
                if (t.length() > best.length()) best = t;
            }
            r.question = stripScore(best);
        }

        // 选项
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            Matcher m = OPT_FULL.matcher(t);
            if (m.matches()) {
                Opt o = new Opt();
                o.letter = m.group(1).toUpperCase();
                o.text = m.group(2).trim();
                o.index = i;
                r.options.add(o);
                continue;
            }
            if (OPT_BARE.matcher(t).matches() && i + 1 < texts.size()) {
                String nx = texts.get(i + 1).trim();
                if (!nx.isEmpty() && !isNextWord(nx)) {
                    Opt o = new Opt();
                    o.letter = t.trim().replaceAll("[（()）\\s]", "").toUpperCase();
                    o.text = nx;
                    o.index = i + 1;
                    r.options.add(o);
                }
                continue;
            }
            String tt = t.trim();
            if (tt.equals("正确") || tt.equals("错误") || tt.equals("对") || tt.equals("错")
                    || tt.equals("√") || tt.equals("×")) {
                Opt o = new Opt();
                o.letter = (tt.equals("正确") || tt.equals("对") || tt.equals("√")) ? "A" : "B";
                o.text = tt;
                o.index = i;
                r.options.add(o);
                r.judge = true;
            }
        }

        // 兜底：题干和选项挤在同一个节点（"A.xx B.xx C.xx"）
        if (r.options.isEmpty()) {
            for (int i = 0; i < texts.size(); i++) {
                String t = texts.get(i);
                String[] parts = t.split("(?=[A-Ea-e][.、．)）])");
                if (parts.length < 3) continue;
                for (String p2 : parts) {
                    Matcher m = OPT_FULL.matcher(p2.trim());
                    if (m.matches()) {
                        Opt o = new Opt();
                        o.letter = m.group(1).toUpperCase();
                        o.text = m.group(2).trim();
                        o.index = i;
                        r.options.add(o);
                        if (r.options.size() == 1) {
                            int cut = t.indexOf(p2.trim());
                            if (cut > 6) { r.question = stripScore(t.substring(0, cut).trim()); r.inline = true; }
                        }
                    }
                }
                if (!r.options.isEmpty()) break;
            }
        }

        // 只保留字母连续递增的那一组。
        // 答题页常混着解析区、题卡列表、别的题的预览，全是「A. xxx」开头，
        // 全收进来就会把不同题的选项拼成一道，答案是必然错的。
        if (r.options.size() > 1) {
            List<Opt> kept = longestConsecutive(r.options);
            r.options.clear();
            r.options.addAll(kept);
        }

        // 下一题：完全匹配优先，其次包含
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i).trim();
            for (String w : NEXT_WORDS) if (t.equals(w)) { r.nextCandidates.add(i); break; }
        }
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i).trim();
            if (r.nextCandidates.contains(i)) continue;
            if (isNextWord(t)) r.nextCandidates.add(i);
        }
        return r;
    }

    /**
     * 按坐标取题干：选项正上方、离得最近的那一块文字。
     * H5 页面上「从选项往前倒推 N 段」会拿到页面标题（DOM 里标题就在选项前），
     * 用坐标找「最近的」才靠谱。
     */
    private static void extractByPosition(Parsed r, List<String> texts, List<int[]> pos, int firstOpt) {
        int optTop = pos.get(firstOpt)[0];
        int anchor = -1, anchorTop = Integer.MIN_VALUE;
        for (int i = 0; i < firstOpt; i++) {
            String t = texts.get(i).trim();
            if (t.isEmpty() || NUM_ONLY.matcher(t).matches()) continue;
            if (isNextWord(t) || isStopWord(t) || isJunk(t)) continue;
            int top = pos.get(i)[0];
            if (top > anchorTop) { anchorTop = top; anchor = i; }
        }
        if (anchor < 0) return;
        if (optTop - anchorTop > 600) return;          // 离选项太远，肯定不是这道题的题干

        StringBuilder qb = new StringBuilder(texts.get(anchor).trim());
        int prevTop = anchorTop;
        for (int i = anchor - 1; i >= 0 && qb.length() < 260; i--) {
            String t = texts.get(i).trim();
            if (t.isEmpty() || NUM_ONLY.matcher(t).matches()) continue;
            if (isNextWord(t) || isStopWord(t) || isJunk(t)) continue;
            int top = pos.get(i)[0];
            if (prevTop - top > 90) break;             // 行距突然变大，说明是另一段
            qb.insert(0, t);
            prevTop = top;
        }
        r.question = stripScore(qb.toString());
    }

    /** 无坐标时的旧逻辑：从选项往前倒推，最多 4 段。 */
    private static void extractByOrder(Parsed r, List<String> texts, int firstOpt) {
        StringBuilder qb = new StringBuilder();
        int got = 0;
        boolean continuation = false;
        for (int i = firstOpt - 1; i >= 0 && got < 4; i--) {
            String t = texts.get(i).trim();
            if (t.isEmpty() || NUM_ONLY.matcher(t).matches()) continue;
            if (isNextWord(t) || isStopWord(t)) continue;
            if (isJunk(t)) continue;
            qb.insert(0, t);
            got++;
            continuation = "）)，,。、；;：:！？".indexOf(t.charAt(0)) >= 0;
            if (!continuation && qb.length() >= 12) break;
        }
        r.question = stripScore(qb.toString());
    }

    /** 取最长的一组「字母严格递增」的选项，把串进来的别的题的选项丢掉。 */
    private static List<Opt> longestConsecutive(List<Opt> all) {
        List<Opt> best = new ArrayList<>();
        List<Opt> run = new ArrayList<>();
        char expect = 0;
        for (Opt o : all) {
            if (o.letter == null || o.letter.isEmpty()) continue;
            char c = o.letter.charAt(0);
            if (run.isEmpty()) {
                run.add(o);
                expect = (char) (c + 1);
            } else if (c == expect) {
                run.add(o);
                expect = (char) (c + 1);
            } else {
                if (run.size() > best.size()) best = new ArrayList<>(run);
                run.clear();
                run.add(o);
                expect = (char) (c + 1);
            }
        }
        if (run.size() > best.size()) best = new ArrayList<>(run);
        return best;
    }

    private static boolean isOptionLine(List<String> texts, int i) {
        String t = texts.get(i);
        if (OPT_FULL.matcher(t).matches()) return true;
        if (OPT_BARE.matcher(t).matches() && i + 1 < texts.size()
                && !texts.get(i + 1).trim().isEmpty()) return true;
        String tt = t.trim();
        return tt.equals("正确") || tt.equals("错误");
    }
}
