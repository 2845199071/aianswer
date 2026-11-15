package com.dsh.aianswer;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

/** 读 .docx 的正文段落（zip + word/document.xml），不依赖第三方库。 */
public class DocxReader {

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    public static List<String> paragraphs(File f) throws Exception {
        ZipFile zf = new ZipFile(f);
        try {
            ZipEntry e = zf.getEntry("word/document.xml");
            if (e == null) throw new IllegalStateException("不是有效的 .docx（缺 word/document.xml）");
            Document doc = parse(zf.getInputStream(e));
            List<String> out = new ArrayList<String>();

            NodeList ps = doc.getElementsByTagNameNS(W, "p");
            if (ps.getLength() == 0) ps = doc.getElementsByTagName("w:p");
            for (int i = 0; i < ps.getLength(); i++) {
                StringBuilder sb = new StringBuilder();
                collect(ps.item(i), sb);
                out.add(sb.toString());
            }
            return out;
        } finally {
            zf.close();
        }
    }

    /** 递归取文字：w:t 取文本，w:tab 转制表符，w:br/w:cr 转换行。 */
    private static void collect(Node n, StringBuilder sb) {
        NodeList ch = n.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node c = ch.item(i);
            if (c.getNodeType() != Node.ELEMENT_NODE) continue;
            String name = c.getLocalName() != null ? c.getLocalName() : c.getNodeName();
            if ("t".equals(name)) {
                sb.append(c.getTextContent());
            } else if ("tab".equals(name)) {
                sb.append('\t');
            } else if ("br".equals(name) || "cr".equals(name)) {
                sb.append('\n');
            } else if ("instrText".equals(name) || "delText".equals(name)) {
                // 域代码与修订删除内容，跳过
            } else {
                collect(c, sb);
            }
        }
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
