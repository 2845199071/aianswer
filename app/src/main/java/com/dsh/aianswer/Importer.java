package com.dsh.aianswer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把本地文件（xlsx / xlsm / csv / tsv / txt / docx / json）转成内部题库 JSON。
 *
 * v1.2 起不再要求「第一行就是表头、列位置固定」：
 *  - 自动在文件前 30 行里找真正的表头，跳过「导入须知」这类说明行
 *  - 表头同义词：题干/题目/标题/试题、题型/类型、正确答案/答案、解析、选项A..H
 *  - 题型归一化：单选题→单选、多选题/不定项→多选、判断题→判断、填空题→填空
 *  - 判断题没写选项时自动补「正确 / 错误」
 *  - 答案支持 ABCD 连写、A,B、正确/错误、√/×，以及直接写选项原文
 *  - .docx 与纯文本按「题号 + 选项行 + 答案行」切题
 */
public class Importer {

    /** 一条尚未归一化的题目；表格解析和文本解析都产出这个。 */
    static class Raw {
        String type = "";
        String q = "";
        String ansRaw = "";
        String exp = "";
        LinkedHashMap<String, String> opts = new LinkedHashMap<String, String>();
        int line;
    }

    /** 导入结果：JSON + 统计，用来在界面上说清「这份文档被识别成了什么」。 */
    public static class Result {
        public String json = "[]";
        public int count;
        public int skipped;
        public int noOpt;
        public int noAns;
        public int headerRow = -1;
        public String format = "";

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(count).append(" 题");
            if (skipped > 0) sb.append("，跳过 ").append(skipped).append(" 行");
            if (noAns > 0) sb.append("，缺答案 ").append(noAns);
            if (noOpt > 0) sb.append("，无选项 ").append(noOpt);
            if (headerRow >= 0) sb.append("，表头第 ").append(headerRow + 1).append(" 行");
            return sb.toString();
        }
    }

    private static Result last = new Result();

    /** 最近一次导入的统计（界面用来显示识别概况）。 */
    public static Result lastResult() {
        return last;
    }

    public static String toBankJson(File file, String displayName) throws Exception {
        return importFile(file, displayName).json;
    }

    public static Result importFile(File file, String displayName) throws Exception {
        String lower = displayName == null ? "" : displayName.toLowerCase();
        Result r = new Result();

        if (lower.endsWith(".xlsx") || lower.endsWith(".xlsm")) {
            r.format = "Excel";
            buildTable(XlsxReader.read(file), r);
        } else if (lower.endsWith(".xls")) {
            throw new IllegalArgumentException("不支持老式 .xls，请另存为 .xlsx 再导入");
        } else if (lower.endsWith(".docx")) {
            r.format = "Word";
            buildText(DocxReader.paragraphs(file), r);
        } else if (lower.endsWith(".json")) {
            r.format = "JSON";
            buildJson(readText(file), r);
        } else {
            String text = readText(file);
            List<Map<String, String>> rows = parseDelimited(text);
            if (looksLikeTable(rows)) {
                r.format = "表格";
                buildTable(rows, r);
            } else {
                r.format = "文本";
                buildText(splitLines(text), r);
                if (r.count == 0) {          // 不是「题号+选项」的排版，退回列表格
                    r.format = "表格";
                    buildTable(rows, r);
                }
            }
        }
        if (r.count == 0) throw new IllegalArgumentException(hintFor(lower));
        last = r;
        return r;
    }

    private static String hintFor(String lower) {
        if (lower.endsWith(".xlsx") || lower.endsWith(".xlsm")) {
            return "没解析出题目：表头要有「题干」和「正确答案」两列（或「选项A」起至少两列选项）";
        }
        if (lower.endsWith(".docx")) {
            return "没解析出题目：文档里每题要以「1、题干」开头，选项以「A、」开头，答案用「答案：A」单独一行";
        }
        return "没解析出题目：既不是带表头的表格，也找不到「1、题干 / A、选项 / 答案：A」这类排版";
    }

    // ---------- 表格（xlsx / csv / tsv） ----------

    /** 表头在某一行：列字母 -> 用途。 */
    static class Cols {
        int row = -1;
        String colQ;
        String colType;
        String colAns;
        String colExp;
        LinkedHashMap<String, String> optCols = new LinkedHashMap<String, String>();
    }

    static void buildTable(List<Map<String, String>> rows, Result r) throws Exception {
        if (rows.isEmpty()) throw new IllegalArgumentException("文件里没有内容");

        Cols lay = detectHeader(rows);
        int start;
        if (lay != null) {
            start = lay.row + 1;
            r.headerRow = lay.row;
        } else {
            lay = guessLayout(rows);
            start = lay.row + 1;
        }

        List<Raw> raws = new ArrayList<Raw>();
        for (int i = start; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            String q = clean(row.get(lay.colQ));
            if (q.isEmpty()) { r.skipped++; continue; }
            if (isSectionTitle(row, q)) { r.skipped++; continue; }
            Raw raw = new Raw();
            raw.line = i + 1;
            raw.q = q;
            raw.type = lay.colType == null ? "" : clean(row.get(lay.colType));
            raw.ansRaw = lay.colAns == null ? "" : clean(row.get(lay.colAns));
            raw.exp = lay.colExp == null ? "" : clean(row.get(lay.colExp));
            for (Map.Entry<String, String> e : lay.optCols.entrySet()) {
                String v = clean(row.get(e.getValue()));
                if (!v.isEmpty()) raw.opts.put(e.getKey(), v);
            }
            raws.add(raw);
        }
        finish(raws, r);
    }

    /** 在前 30 行里挑得分最高的那一行当表头；说明行、标题行会被自动排除。 */
    static Cols detectHeader(List<Map<String, String>> rows) {
        int limit = Math.min(rows.size(), 30);
        Cols best = null;
        int bestScore = 0;
        for (int i = 0; i < limit; i++) {
            Cols lay = tryHeader(rows.get(i));
            if (lay == null) continue;
            int sc = headerScore(lay);
            if (sc > bestScore) {
                bestScore = sc;
                best = lay;
                lay.row = i;
            }
        }
        return bestScore >= 5 ? best : null;
    }

    private static int headerScore(Cols l) {
        int sc = 0;
        if (l.colQ != null) sc += 3;
        if (l.colAns != null) sc += 3;
        if (l.colType != null) sc += 2;
        if (l.colExp != null) sc += 1;
        sc += l.optCols.size();
        return sc;
    }

    /** 一行是否像表头；不像则返回 null。 */
    static Cols tryHeader(Map<String, String> row) {
        int nonEmpty = 0;
        for (String v : row.values()) {
            String c = clean(v);
            if (c.isEmpty()) continue;
            nonEmpty++;
            // 表头单元格都是短词；长句一律当说明文字
            if (c.length() > 24 || c.indexOf('\n') >= 0) return null;
        }
        if (nonEmpty < 2) return null;

        Cols lay = new Cols();
        for (Map.Entry<String, String> e : row.entrySet()) {
            String h = headKey(e.getValue());
            if (h.isEmpty()) continue;
            if (h.contains("解析") || h.contains("解释") || h.contains("说明") || h.contains("explain")
                    || h.contains("analysis")) {
                lay.colExp = e.getKey();
            } else if (h.contains("答案") || h.contains("answer") || h.contains("correct")) {
                lay.colAns = e.getKey();
            } else if (h.contains("题型") || h.contains("类型") || h.equals("type")) {
                lay.colType = e.getKey();
            } else {
                String letter = optLetter(h);
                if (letter != null) {
                    if (letter.isEmpty()) letter = nextOptLetter(lay.optCols);
                    lay.optCols.put(letter, e.getKey());
                } else if (h.contains("题干") || h.contains("题目") || h.contains("标题") || h.contains("问题")
                        || h.contains("试题") || h.contains("question") || h.contains("content")) {
                    lay.colQ = e.getKey();
                }
            }
        }
        if (lay.colQ == null) return null;
        if (lay.colAns == null && lay.optCols.size() < 2) return null;
        return lay;
    }

    /** 表头文字归一化：全角转半角、去空白、去「（必填）」「(勿删)」这类注记。 */
    static String headKey(String s) {
        String t = half(clean(s));
        t = t.replaceAll("[（(][^）)]{0,12}[）)]", "");
        t = t.replaceAll("[\\s\\u00a0]+", "");
        return t.toLowerCase();
    }

    /** 返回选项字母；空串表示「是个选项列但没写字母」；null 表示不是选项列。 */
    static String optLetter(String h) {
        if (!h.contains("选项") && !h.contains("option") && !h.contains("选择")) return null;
        String s = h.replace("选项", "").replace("option", "").replace("选择", "")
                .replace("correct", "").replaceAll("[^a-z0-9]", "");
        if (s.length() == 1 && s.charAt(0) >= 'a' && s.charAt(0) <= 'j') return s.toUpperCase();
        if (s.length() == 1 && s.charAt(0) >= '1' && s.charAt(0) <= '9') {
            return String.valueOf((char) ('A' + (s.charAt(0) - '1')));
        }
        return "";
    }

    private static String nextOptLetter(Map<String, String> used) {
        for (char c = 'A'; c <= 'J'; c++) {
            if (!used.containsKey(String.valueOf(c))) return String.valueOf(c);
        }
        return "A";
    }

    /** 没找到表头时猜列：先找「题型列」，再找「答案列」，剩下最长的当题干。 */
    static Cols guessLayout(List<Map<String, String>> rows) {
        int probe = Math.min(rows.size(), 10);
        List<String> cols = columns(rows, probe);
        Cols lay = new Cols();
        lay.row = -1;

        // 题型列：多行取值都是题型词
        String typeCol = null;
        int bestType = 0;
        for (String c : cols) {
            int hits = 0;
            for (int i = 0; i < probe; i++) {
                String v = clean(rows.get(i).get(c));
                if (!v.isEmpty() && isTypeWord(v)) hits++;
            }
            if (hits > bestType) { bestType = hits; typeCol = c; }
        }
        if (bestType >= 2) lay.colType = typeCol;

        // 答案列：多行取值是「A」「AB」「正确」这类
        String ansCol = null;
        int bestAns = 0;
        for (String c : cols) {
            if (c.equals(typeCol)) continue;
            int hits = 0;
            for (int i = 0; i < probe; i++) {
                String v = clean(rows.get(i).get(c));
                if (looksLikeAnswer(v)) hits++;
            }
            if (hits > bestAns) { bestAns = hits; ansCol = c; }
        }
        if (bestAns >= 2) lay.colAns = ansCol;

        // 题干列：最长的文本列
        String qCol = null;
        double bestLen = 0;
        for (String c : cols) {
            if (c.equals(typeCol) || c.equals(ansCol)) continue;
            double sum = 0;
            int n = 0;
            for (int i = 0; i < probe; i++) {
                String v = clean(rows.get(i).get(c));
                if (!v.isEmpty()) { sum += v.length(); n++; }
            }
            double avg = n == 0 ? 0 : sum / n;
            if (avg > bestLen) { bestLen = avg; qCol = c; }
        }
        if (qCol == null || bestLen < 4) {
            // 完全没有线索：退回老布局 A=类型 B=题干 C=答案 D=解析 F~J=选项A~E
            lay.colType = "A";
            lay.colQ = "B";
            lay.colAns = "C";
            lay.colExp = "D";
            String[] cols5 = {"F", "G", "H", "I", "J"};
            for (int i = 0; i < cols5.length; i++) {
                lay.optCols.put(String.valueOf((char) ('A' + i)), cols5[i]);
            }
            return lay;
        }
        lay.colQ = qCol;

        // 选项列：题干右侧、答案列左侧的那些列
        if (ansCol != null) {
            for (String c : cols) {
                if (c.equals(qCol) || c.equals(ansCol) || c.equals(typeCol)) continue;
                if (compareCol(c, qCol) > 0 && compareCol(c, ansCol) < 0) {
                    lay.optCols.put(nextOptLetter(lay.optCols), c);
                }
            }
        }
        // 老布局把选项放在答案列右边（A=类型 B=题干 C=答案 D=解析 E空 F~J=选项），
        // 取右边第一个连续填充块当选项
        if (lay.optCols.isEmpty() && ansCol != null) {
            List<String> filled = new ArrayList<String>();
            for (String c : cols) {
                if (compareCol(c, ansCol) <= 0 || c.equals(typeCol) || c.equals(qCol)) continue;
                int n = 0;
                for (int i = 0; i < probe; i++) if (!clean(rows.get(i).get(c)).isEmpty()) n++;
                if (n >= 2) filled.add(c);
            }
            // 只取最长的一段连续列，避免把「解析/章节/难度」也当选项
            List<String> run = new ArrayList<String>();
            List<String> best = new ArrayList<String>();
            for (String c : filled) {
                if (!run.isEmpty() && compareCol(c, run.get(run.size() - 1)) != 1) {
                    if (run.size() > best.size()) best = new ArrayList<String>(run);
                    run.clear();
                }
                run.add(c);
            }
            if (run.size() > best.size()) best = run;
            for (String c : best) lay.optCols.put(nextOptLetter(lay.optCols), c);
        }
        return lay;
    }

    private static int compareCol(String a, String b) {
        return colIndex(a) - colIndex(b);
    }

    private static int colIndex(String c) {
        int n = 0;
        for (int i = 0; i < c.length(); i++) n = n * 26 + (c.charAt(i) - 'A' + 1);
        return n;
    }

    private static List<String> columns(List<Map<String, String>> rows, int probe) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < probe; i++) {
            for (String c : rows.get(i).keySet()) {
                if (!out.contains(c)) out.add(c);
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<String>() {
            public int compare(String a, String b) { return colIndex(a) - colIndex(b); }
        });
        return out;
    }

    static boolean isTypeWord(String v) {
        String s = norm(v);
        return s.contains("单选") || s.contains("多选") || s.contains("判断") || s.contains("填空")
                || s.contains("选择") || s.contains("不定项") || s.contains("简答") || s.contains("问答");
    }

    static boolean looksLikeAnswer(String v) {
        String s = clean(v);
        if (s.isEmpty()) return false;
        String t = s.replaceAll("[\\s，,、;；/|]", "");
        if (t.matches("[A-Ja-j]{1,8}")) return true;
        return s.contains("正确") || s.equals("√") || s.equals("×") || s.equals("对") || s.equals("错");
    }

    /** 「一、单选题」「第二章 …」这类分节标题，只有一列内容时跳过。 */
    static boolean isSectionTitle(Map<String, String> row, String q) {
        int nonEmpty = 0;
        for (String v : row.values()) if (!clean(v).isEmpty()) nonEmpty++;
        if (nonEmpty > 1) return false;
        if (q.length() > 24) return false;
        return q.matches("^[一二三四五六七八九十\\d]{1,3}\\s*[、.．].*")
                || q.matches("^第[一二三四五六七八九十\\d]{1,3}\\s*[章节部分篇].*");
    }

    // ---------- 文本 / Word ----------

    static void buildText(List<String> lines, Result r) throws Exception {
        finish(TextParser.parse(lines), r);
    }

    static List<String> splitLines(String text) {
        List<String> out = new ArrayList<String>();
        String[] arr = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String s : arr) out.add(s);
        return out;
    }

    static boolean looksLikeTable(List<Map<String, String>> rows) {
        if (rows.size() < 2) return false;
        int checked = 0, multi = 0;
        for (int i = 0; i < Math.min(rows.size(), 10); i++) {
            checked++;
            if (rows.get(i).size() >= 3) multi++;
        }
        return checked > 0 && multi * 2 > checked;
    }

    // ---------- 归一化：题型 / 答案 / 选项 ----------

    static void finish(List<Raw> raws, Result r) throws Exception {
        JSONArray out = new JSONArray();
        for (Raw raw : raws) {
            String q = Layout.stripScore(clean(raw.q));
            if (q.isEmpty()) { r.skipped++; continue; }

            String type = normType(raw.type);
            LinkedHashMap<String, String> opts = stripOptionLetters(raw.opts);
            // 没写题型、但答案就是「正确 / 错误」→ 先按判断题处理，下面才好补选项
            if (type.isEmpty() && looksJudgeAnswer(raw.ansRaw)) type = "判断";

            // 判断题通常不写选项，补成「正确 / 错误」，否则答案字母无处落
            if (type.contains("判断") && opts.isEmpty()) {
                opts.put("A", "正确");
                opts.put("B", "错误");
            }

            List<String> ans = parseAnswer(raw.ansRaw, opts, type);
            if (type.isEmpty()) type = inferType(opts, ans, raw.ansRaw);

            // 单选却给了多个答案 → 其实就是多选
            if (ans.size() > 1 && !type.contains("多选")) type = "多选";

            List<String> valid = new ArrayList<String>();
            for (String a : ans) {
                if (opts.isEmpty() || opts.containsKey(a)) valid.add(a);
            }
            if (valid.isEmpty() && !ans.isEmpty()) {
                // 答案字母不在选项里：说明列错位/答案是文字，保留原样但记一笔
                r.noAns++;
            }
            if (valid.isEmpty()) r.noAns++;
            if (opts.isEmpty()) r.noOpt++;

            JSONObject o = new JSONObject();
            o.put("t", type);
            o.put("q", q);
            JSONArray arr = new JSONArray();
            for (String a : valid) arr.put(a);
            o.put("a", arr);
            JSONObject oo = new JSONObject();
            for (Map.Entry<String, String> e : opts.entrySet()) oo.put(e.getKey(), e.getValue());
            o.put("o", oo);
            o.put("e", clean(raw.exp));
            out.put(o);
        }
        r.count = out.length();
        r.json = out.toString();
    }

    /** 答案是不是「正确 / 错误 / √ / ×」这种判断题写法。 */
    static boolean looksJudgeAnswer(String raw) {
        String s = norm(raw);
        if (s.isEmpty()) return false;
        return s.equals("正确") || s.equals("错误") || s.equals("对") || s.equals("错")
                || s.equals("√") || s.equals("×") || s.equals("是") || s.equals("否")
                || s.equals("t") || s.equals("f");
    }

    static String normType(String raw) {
        String s = norm(raw);
        if (s.isEmpty()) return "";
        if (s.contains("判断")) return "判断";
        if (s.contains("多选") || s.contains("不定项") || s.contains("多项")) return "多选";
        if (s.contains("单选") || s.contains("单项")) return "单选";
        if (s.contains("填空")) return "填空";
        if (s.contains("简答") || s.contains("问答") || s.contains("论述") || s.contains("案例")) return "简答";
        if (s.equals("选择") || s.equals("选择题")) return "单选";
        return clean(raw);
    }

    static String inferType(Map<String, String> opts, List<String> ans, String ansRaw) {
        if (opts.size() == 2) {
            String t = norm(opts.get("A")) + "|" + norm(opts.get("B"));
            if (t.contains("正确") || t.contains("对")) return "判断";
        }
        if (ans.size() > 1) return "多选";
        if (opts.isEmpty()) {
            String s = clean(ansRaw);
            if (s.equals("正确") || s.equals("错误") || s.equals("对") || s.equals("错")
                    || s.equals("√") || s.equals("×")) return "判断";
            return "";
        }
        return "单选";
    }

    /** 剥掉选项自带的字母前缀：「A.补充对应型号液压油」→「补充对应型号液压油」。 */
    static LinkedHashMap<String, String> stripOptionLetters(Map<String, String> opts) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : opts.entrySet()) {
            String v = clean(e.getValue());
            String k = java.util.regex.Pattern.quote(e.getKey());
            v = v.replaceAll("(?i)^" + k + "\\s*[.、．)）:：]\\s*", "");
            v = v.replaceAll("(?i)^[（(]\\s*" + k + "\\s*[)）]\\s*", "");
            if (!v.isEmpty()) out.put(e.getKey(), v);
        }
        return out;
    }

    /** 解析答案列：字母连写 / 分隔符 / 判断题文字 / 直接写选项原文。 */
    static List<String> parseAnswer(String raw, Map<String, String> opts, String type) {
        List<String> out = new ArrayList<String>();
        String s = clean(raw);
        if (s.isEmpty()) return out;
        s = s.replaceAll("^[【\\[（(]?\\s*(?:正确|参考|标准)?答案\\s*[】\\]）)]?\\s*[:：]?\\s*", "");
        s = s.replaceAll("[（(]\\s*\\d+(?:\\.\\d+)?\\s*分\\s*[）)]", "").trim();
        if (s.isEmpty()) return out;

        String compact = s.replaceAll("[\\s，,、;；/|＆&.。:：]", "");
        List<String> letters = lettersOf(compact);
        if (!letters.isEmpty()) return letters;

        boolean judge = type.contains("判断") || opts.size() == 2;
        if (judge) {
            boolean yes = s.contains("正确") || s.contains("√") || s.equals("对") || s.equals("是")
                    || s.equalsIgnoreCase("T") || s.equalsIgnoreCase("TRUE");
            boolean no = s.contains("错误") || s.contains("×") || s.equals("错") || s.equals("否")
                    || s.equalsIgnoreCase("F") || s.equalsIgnoreCase("FALSE");
            if (yes && !no) return pickJudge(opts, true);
            if (no && !yes) return pickJudge(opts, false);
        }

        // 答案写的是选项原文
        String ns = norm(s);
        for (Map.Entry<String, String> e : opts.entrySet()) {
            String nv = norm(e.getValue());
            if (!nv.isEmpty() && (nv.equals(ns) || ns.contains(nv) || (nv.length() >= 2 && nv.contains(ns)))) {
                out.add(e.getKey());
                return out;
            }
        }
        return out;
    }

    static List<String> lettersOf(String compact) {
        List<String> out = new ArrayList<String>();
        if (compact.isEmpty() || compact.length() > 8) return out;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (c >= 'a' && c <= 'j') c = (char) (c - 32);
            char letter;
            if (c >= 'A' && c <= 'J') letter = c;
            else if (c >= '1' && c <= '9') letter = (char) ('A' + (c - '1'));
            else return new ArrayList<String>();     // 混了别的字，不是纯字母答案
            String s = String.valueOf(letter);
            if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    private static List<String> pickJudge(Map<String, String> opts, boolean isTrue) {
        List<String> out = new ArrayList<String>();
        String[] yes = {"正确", "对", "√", "是", "T"};
        String[] no = {"错误", "错", "×", "否", "F"};
        String[] want = isTrue ? yes : no;
        for (Map.Entry<String, String> e : opts.entrySet()) {
            String v = norm(e.getValue());
            for (String w : want) {
                if (v.equals(norm(w))) { out.add(e.getKey()); return out; }
            }
        }
        for (Map.Entry<String, String> e : opts.entrySet()) {
            String v = norm(e.getValue());
            for (String w : want) {
                if (v.contains(norm(w))) { out.add(e.getKey()); return out; }
            }
        }
        out.add(isTrue ? "A" : "B");
        return out;
    }

    // ---------- JSON ----------

    static void buildJson(String text, Result r) throws Exception {
        JSONArray in = new JSONArray(stripBom(text));
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject src = in.getJSONObject(i);
            String q = clean(first(src, "q", "题目", "题干", "question", "title"));
            if (q.isEmpty()) { r.skipped++; continue; }
            q = Layout.stripScore(q);
            LinkedHashMap<String, String> opts = new LinkedHashMap<String, String>();
            JSONObject srcOpts = src.optJSONObject("o");
            if (srcOpts != null) {
                java.util.Iterator<String> it = srcOpts.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String key = norm(k).toUpperCase();
                    if (key.length() >= 2 && key.contains("选项")) key = key.substring(key.length() - 1);
                    opts.put(key, clean(srcOpts.optString(k, "")));
                }
            } else {
                for (String k : new String[]{"选项A", "选项B", "选项C", "选项D", "选项E"}) {
                    String v = clean(src.optString(k, ""));
                    if (!v.isEmpty()) opts.put(k.substring(2), v);
                }
            }
            String type = normType(first(src, "t", "类型", "题型", "type"));
            if (type.contains("判断") && opts.isEmpty()) { opts.put("A", "正确"); opts.put("B", "错误"); }

            List<String> ans = new ArrayList<String>();
            Object rawAns = firstObj(src, "a", "答案", "answer", "correct");
            if (rawAns instanceof JSONArray) {
                JSONArray arr = (JSONArray) rawAns;
                for (int k = 0; k < arr.length(); k++) {
                    for (String a : parseAnswer(String.valueOf(arr.get(k)), opts, type)) {
                        if (!ans.contains(a)) ans.add(a);
                    }
                }
            } else if (rawAns != null) {
                ans = parseAnswer(String.valueOf(rawAns), opts, type);
            }
            if (type.isEmpty()) type = inferType(opts, ans, "");
            if (ans.size() > 1 && !type.contains("多选")) type = "多选";
            if (opts.isEmpty()) r.noOpt++;
            if (ans.isEmpty()) r.noAns++;

            JSONObject o = new JSONObject();
            o.put("t", type);
            o.put("q", q);
            JSONArray arr = new JSONArray();
            for (String a : ans) arr.put(a);
            o.put("a", arr);
            JSONObject oo = new JSONObject();
            for (Map.Entry<String, String> e : opts.entrySet()) oo.put(e.getKey(), e.getValue());
            o.put("o", oo);
            o.put("e", clean(first(src, "e", "解析", "explain")));
            out.put(o);
        }
        r.count = out.length();
        r.json = out.toString();
    }

    // ---------- 工具 ----------

    static String[] splitAnswer(String raw) {
        if (raw == null) return new String[0];
        String s = clean(raw).toUpperCase().replace('，', ';').replace(',', ';').replace('、', ';')
                .replace(' ', ';').replace('/', ';');
        List<String> out = new ArrayList<String>();
        String[] parts = s.split(";");
        for (String part : parts) {
            String p = part.trim();
            if (p.length() >= 1 && p.length() <= 2 && p.charAt(0) >= 'A' && p.charAt(0) <= 'J') {
                out.add(String.valueOf(p.charAt(0)));
            }
        }
        return out.toArray(new String[0]);
    }

    static String clean(String s) {
        if (s == null) return "";
        return s.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
                .replaceAll(" {2,}", " ").replace('\u00a0', ' ').trim();
    }

    /** 去空白、全角转半角、小写——只用于比较，不用于展示。 */
    static String norm(String s) {
        return half(clean(s)).replaceAll("[\\s\\u00a0]+", "").toLowerCase();
    }

    /** 全角 ASCII → 半角。 */
    static String half(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0);
            else if (c == 0x3000) c = ' ';
            sb.append(c);
        }
        return sb.toString();
    }

    private static String stripBom(String s) {
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    private static String first(JSONObject o, String... keys) {
        Object v = firstObj(o, keys);
        return v == null ? "" : String.valueOf(v);
    }

    private static Object firstObj(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.isNull(k)) return o.opt(k);
        }
        return null;
    }

    static String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        byte[] bytes = bo.toByteArray();
        String s = new String(bytes, "UTF-8");
        if (s.indexOf('\uFFFD') >= 0) {                 // 大概率是 GBK 的老 txt
            try { s = new String(bytes, "GBK"); } catch (Exception ignored) { }
        }
        return stripBom(s.replace("\u0000", ""));
    }

    // ---------- 兼容旧接口 ----------

    static List<Map<String, String>> parseDelimited(String text) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        char delim = '\t';
        int tab = 0, comma = 0;
        for (int i = 0; i < Math.min(5, lines.length); i++) {
            tab += count(lines[i], '\t');
            comma += count(lines[i], ',');
        }
        if (comma > tab) delim = ',';
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            List<String> cells = splitLine(line, delim);
            Map<String, String> row = new LinkedHashMap<String, String>();
            for (int i = 0; i < cells.size(); i++) row.put(cellName(i), cells.get(i).trim());
            rows.add(row);
        }
        return rows;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private static List<String> splitLine(String line, char delim) {
        List<String> out = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; }
                else quoted = !quoted;
            } else if (ch == delim && !quoted) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(ch);
            }
        }
        out.add(sb.toString());
        return out;
    }

    private static String cellName(int i) {
        StringBuilder sb = new StringBuilder();
        int n = i;
        do {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }
}
