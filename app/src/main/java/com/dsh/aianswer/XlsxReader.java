package com.dsh.aianswer;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

/** 直接读 xlsx（zip + XML），不依赖任何第三方库。返回按列字母分行的表格。 */
public class XlsxReader {

    public static List<Map<String, String>> read(File f) throws Exception {
        ZipFile zf = new ZipFile(f);
        try {
            List<String> shared = readSharedStrings(zf);
            String sheetPath = pickSheet(zf);
            ZipEntry se = zf.getEntry(sheetPath);
            if (se == null) throw new IllegalStateException("找不到工作表 " + sheetPath);
            Document doc = parse(zf.getInputStream(se));
            return readRows(doc, shared);
        } finally {
            zf.close();
        }
    }

    private static List<String> readSharedStrings(ZipFile zf) throws Exception {
        List<String> out = new ArrayList<>();
        ZipEntry e = zf.getEntry("xl/sharedStrings.xml");
        if (e == null) return out;
        Document doc = parse(zf.getInputStream(e));
        NodeList sis = doc.getElementsByTagName("si");
        for (int i = 0; i < sis.getLength(); i++) {
            Element si = (Element) sis.item(i);
            NodeList ts = si.getElementsByTagName("t");
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < ts.getLength(); j++) {
                sb.append(ts.item(j).getTextContent());
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** 跳过隐藏工作表；优先名字里带「题/选择」的那张，否则第一张可见的。 */
    private static String pickSheet(ZipFile zf) throws Exception {
        Map<String, String> rels = new LinkedHashMap<>();
        ZipEntry re = zf.getEntry("xl/_rels/workbook.xml.rels");
        if (re != null) {
            Document d = parse(zf.getInputStream(re));
            NodeList rs = d.getElementsByTagName("Relationship");
            for (int i = 0; i < rs.getLength(); i++) {
                Element r = (Element) rs.item(i);
                rels.put(r.getAttribute("Id"), r.getAttribute("Target"));
            }
        }
        ZipEntry we = zf.getEntry("xl/workbook.xml");
        if (we == null) return "xl/worksheets/sheet1.xml";
        Document d = parse(zf.getInputStream(we));
        NodeList sheets = d.getElementsByTagName("sheet");
        String first = null, preferred = null;
        String ns = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        for (int i = 0; i < sheets.getLength(); i++) {
            Element s = (Element) sheets.item(i);
            String state = s.getAttribute("state");
            if ("hidden".equals(state) || "veryHidden".equals(state)) continue;   // 「版本号」这类辅助表
            String rid = s.getAttributeNS(ns, "id");
            if (rid == null || rid.isEmpty()) rid = s.getAttribute("r:id");
            String target = rels.get(rid);
            if (target == null) continue;
            String path = target.startsWith("/") ? target.substring(1)
                    : (target.startsWith("xl/") ? target : "xl/" + target);
            if (first == null) first = path;
            String name = s.getAttribute("name");
            if (preferred == null && (name.contains("题") || name.contains("选择") || name.contains("单"))) {
                preferred = path;
            }
        }
        return preferred != null ? preferred : (first != null ? first : "xl/worksheets/sheet1.xml");
    }

    private static List<Map<String, String>> readRows(Document doc, List<String> shared) {
        List<Map<String, String>> rows = new ArrayList<>();
        NodeList rowNodes = doc.getElementsByTagName("row");
        for (int i = 0; i < rowNodes.getLength(); i++) {
            Element row = (Element) rowNodes.item(i);
            Map<String, String> cells = new LinkedHashMap<>();
            NodeList children = row.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node n = children.item(j);
                if (!(n instanceof Element) || !"c".equals(n.getNodeName())) continue;
                Element c = (Element) n;
                String ref = c.getAttribute("r");
                String col = letters(ref);
                if (col.isEmpty()) continue;
                String type = c.getAttribute("t");
                String val = "";
                if ("inlineStr".equals(type)) {
                    NodeList ts = c.getElementsByTagName("t");
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < ts.getLength(); k++) sb.append(ts.item(k).getTextContent());
                    val = sb.toString();
                } else {
                    NodeList vs = c.getElementsByTagName("v");
                    if (vs.getLength() > 0) {
                        val = vs.item(0).getTextContent();
                        if ("s".equals(type)) {
                            try {
                                int idx = Integer.parseInt(val.trim());
                                if (idx >= 0 && idx < shared.size()) val = shared.get(idx);
                            } catch (NumberFormatException ignored) { }
                        }
                    }
                }
                if (val != null && !val.isEmpty()) cells.put(col, val.trim());
            }
            if (!cells.isEmpty()) rows.add(cells);
        }
        return rows;
    }

    private static String letters(String ref) {
        if (ref == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') sb.append(ch);
            else break;
        }
        return sb.toString();
    }

    private static Document parse(InputStream in) throws Exception {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(in);
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }
}
