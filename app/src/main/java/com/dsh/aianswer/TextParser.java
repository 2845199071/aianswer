package com.dsh.aianswer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯文本 / Word 题库解析：把「1、题干 / A．选项 / 答案：A」这类排版切成题目。
 *
 * 兼容这些常见写法：
 *   1、题干（ ）            /  1. 题干            /  （1）题干       /  第1题 题干
 *   A、选项   B. 选项       /  A．甲  B．乙  C．丙（同一行）
 *   答案：A  【答案】A  参考答案：AB  答案：正确   答案：A，解析：……
 *   【单选题】题干          /  （多选题）题干
 */
public class TextParser {

    private static final Pattern Q_NUM = Pattern.compile("^\\s*(?:第\\s*)?(\\d{1,3})\\s*[.、．)）]\\s*(.*)$");
    private static final Pattern Q_BRACKET = Pattern.compile("^\\s*[（(]\\s*(\\d{1,3})\\s*[)）]\\s*(.*)$");
    private static final Pattern Q_PREFIX = Pattern.compile(
            "^\\s*第\\s*(?:[一二三四五六七八九十百]+|\\d{1,3})\\s*[题条]\\s*[.、．:：]?\\s*(.*)$");
    private static final Pattern OPT_LINE = Pattern.compile("^\\s*([A-Ha-h])\\s*[.、．)）:：]\\s*(.+)$");
    private static final Pattern ANS_LINE = Pattern.compile(
            "^\\s*[【\\[]?\\s*(?:正确|参考|标准)?答案\\s*[】\\]]?\\s*[:：]?\\s*(.*)$");
    private static final Pattern EXP_LINE = Pattern.compile(
            "^\\s*[【\\[]?\\s*(?:答案)?(?:解析|解释)\\s*[】\\]]?\\s*[:：]?\\s*(.*)$");
    private static final Pattern TYPE_TAG = Pattern.compile(
            "^\\s*[【\\[（(]\\s*(单选题?|多选题?|判断题?|填空题?|简答题?|不定项(?:选择题?)?|单项选择|多项选择)"
                    + "[^】\\]）)]{0,6}[】\\]）)]\\s*");
    private static final Pattern INLINE_ANS = Pattern.compile(
            "[【\\[]\\s*(?:正确|参考)?答案\\s*[】\\]]\\s*[:：]?\\s*(.*)$");
    private static final Pattern INLINE_OPT_MARK = Pattern.compile("([A-H])\\s*[.、．)）]\\s*");

    public static List<Importer.Raw> parse(List<String> lines) {
        List<Importer.Raw> out = new ArrayList<Importer.Raw>();
        Importer.Raw cur = null;
        boolean inExp = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = Importer.clean(lines.get(i));
            if (line.isEmpty()) { inExp = false; continue; }
            if (line.length() > 300) continue;              // 整段说明文字，直接丢
            if (isPageFooter(line)) continue;

            // 解析行
            Matcher me = EXP_LINE.matcher(line);
            if (cur != null && me.matches()) {
                String v = me.group(1).trim();
                if (!v.isEmpty()) { cur.exp = v; inExp = true; continue; }
            }
            // 答案行
            Matcher ma = ANS_LINE.matcher(line);
            if (cur != null && ma.matches() && !me.matches()) {
                String v = ma.group(1).trim();
                String exp = null;
                int cut = indexOfExp(v);
                if (cut >= 0) { exp = v.substring(cut).trim(); v = v.substring(0, cut).trim(); }
                if (!v.isEmpty()) cur.ansRaw = v;
                if (exp != null && !exp.isEmpty()) { cur.exp = exp; inExp = true; }
                continue;
            }
            // 解析的续行
            if (inExp && cur != null && !startsQuestion(line) && !OPT_LINE.matcher(line).matches()) {
                cur.exp = (cur.exp + " " + line).trim();
                continue;
            }
            // 选项行（含「A．甲 B．乙」挤在一行的情况）
            if (cur != null && cur.opts.size() < 8 && addOptions(cur, line)) {
                inExp = false;
                continue;
            }
            // 新的一题
            String stem = questionStem(line);
            if (stem != null) {
                cur = flush(out, cur);
                cur = newRaw(stem, i + 1);
                continue;
            }
            if (cur == null) {
                if (isTitle(lines, i, line)) continue;      // 「电梯题库」「模块5 …」这类标题
                cur = newRaw(line, i + 1);
                continue;
            }
            // 没编号的题干：上一题已经收尾（有选项或答案）就开新题
            if (!cur.opts.isEmpty() || !cur.ansRaw.isEmpty()) {
                cur = flush(out, cur);
                cur = newRaw(line, i + 1);
            } else {
                cur.q = (cur.q + " " + line).trim();
            }
        }
        flush(out, cur);
        return out;
    }

    private static Importer.Raw flush(List<Importer.Raw> out, Importer.Raw cur) {
        if (cur != null && !cur.q.isEmpty()) out.add(cur);
        return null;
    }

    private static Importer.Raw newRaw(String stem, int line) {
        Importer.Raw raw = new Importer.Raw();
        raw.line = line;
        String s = stem.trim();

        Matcher mt = TYPE_TAG.matcher(s);
        if (mt.find()) {
            raw.type = mt.group(1);
            s = s.substring(mt.end()).trim();
        }
        // 题干里自带【答案】A
        Matcher mi = INLINE_ANS.matcher(s);
        if (mi.find()) {
            String after = mi.group(1).trim();
            s = s.substring(0, mi.start()).trim();
            raw.ansRaw = after;
        }
        raw.q = s;
        splitInlineOptions(raw);
        return raw;
    }

    /** 「A．甲 B．乙 C．丙」这种挤在一行的选项拆开；成功返回 true。 */
    private static boolean addOptions(Importer.Raw raw, String line) {
        Matcher mo = OPT_LINE.matcher(line);
        if (!mo.matches()) return false;
        if (takeInlineOptions(raw, line)) return true;      // 一行挤了多个选项
        String letter = mo.group(1).toUpperCase();
        String text = mo.group(2).trim();
        if (text.isEmpty() || raw.opts.containsKey(letter)) return false;
        raw.opts.put(letter, text);
        return true;
    }

    /** 从 A 开始的连续「X．」标记拆成选项；前缀（题干）允许为空。 */
    private static boolean takeInlineOptions(Importer.Raw raw, String s) {
        List<int[]> marks = new ArrayList<int[]>();
        List<String> letters = new ArrayList<String>();
        Matcher m = INLINE_OPT_MARK.matcher(s);
        while (m.find()) {
            marks.add(new int[]{m.start(), m.end()});
            letters.add(m.group(1));
        }
        if (marks.size() < 2) return false;
        for (int i = 0; i < letters.size(); i++) {
            if (!letters.get(i).equals(String.valueOf((char) ('A' + i)))) return false;
        }
        List<String> texts = new ArrayList<String>();
        for (int i = 0; i < marks.size(); i++) {
            int from = marks.get(i)[1];
            int to = i + 1 < marks.size() ? marks.get(i + 1)[0] : s.length();
            String text = s.substring(from, to).trim();
            if (text.isEmpty()) return false;
            texts.add(text);
        }
        for (int i = 0; i < texts.size(); i++) raw.opts.put(letters.get(i), texts.get(i));
        return true;
    }

    /** 题干里自带选项时拆开，并把题干截到第一个选项之前。 */
    private static void splitInlineOptions(Importer.Raw raw) {
        String s = raw.q;
        int first = firstMark(s);
        if (first <= 1) return;
        String stem = s.substring(0, first).trim();
        if (stem.length() < 2) return;
        Importer.Raw tmp = new Importer.Raw();
        if (!takeInlineOptions(tmp, s)) return;
        raw.opts.putAll(tmp.opts);
        raw.q = stem;
    }

    private static int firstMark(String s) {
        Matcher m = INLINE_OPT_MARK.matcher(s);
        return m.find() ? m.start() : -1;
    }

    /** 首行标题（「电梯题库」「模块5 直梯保养知识」）别当题干。 */
    private static boolean isTitle(List<String> lines, int i, String line) {
        if (line.length() > 40) return false;
        if (firstMark(line) >= 0) return false;
        for (int j = i + 1; j < lines.size() && j <= i + 2; j++) {
            String nx = Importer.clean(lines.get(j));
            if (nx.isEmpty()) continue;
            return !OPT_LINE.matcher(nx).matches();       // 下一行是选项 → 这行是题干
        }
        return true;
    }

    private static boolean startsQuestion(String line) {
        return questionStem(line) != null;
    }

    /** 是题目开头的行就返回去掉编号后的题干，否则 null。 */
    private static String questionStem(String line) {
        Matcher m = Q_NUM.matcher(line);
        if (m.matches()) {
            String rest = m.group(2).trim();
            // 「1.5分」「2024年…」这类不是题号
            if (rest.length() >= 2 && !Character.isDigit(rest.charAt(0))) return rest;
            return null;
        }
        Matcher b = Q_BRACKET.matcher(line);
        if (b.matches()) {
            String rest = b.group(2).trim();
            if (rest.length() >= 2 && !Character.isDigit(rest.charAt(0))) return rest;
            return null;
        }
        Matcher p = Q_PREFIX.matcher(line);
        if (p.matches()) {
            String rest = p.group(1).trim();
            if (rest.length() >= 2) return rest;
        }
        return null;
    }

    private static int indexOfExp(String s) {
        String[] keys = {"解析：", "解析:", "答案解析", "【解析】", "解释：", "解释:"};
        int best = -1;
        for (String k : keys) {
            int i = s.indexOf(k);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private static boolean isPageFooter(String line) {
        return line.matches("^\\s*[-—]?\\s*\\d{1,3}\\s*[-—]?\\s*$")      // 单独一个页码
                || line.matches("^第\\s*\\d+\\s*页.*$");
    }
}
