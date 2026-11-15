package com.dsh.aianswer;

import android.content.Context;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 读屏节点快照导出。
 *
 * <p>写到 app 专属外部目录（无需存储权限），用
 * <pre>
 *   adb pull /sdcard/Android/data/com.dsh.aianswer/files/nodesnap.txt
 * </pre>
 * 即可取出，用来定位「某个页面为什么读不到题」：到底是窗口拿不到、节点没文字、
 * 还是文字有但选项格式配不上。文件名用 ASCII，避免 adb 对中文路径的截断问题。
 */
public final class DiagDump {

    public static final String FILE = "nodesnap.txt";

    private DiagDump() { }

    public static String write(Context ctx, ScreenScan.Capture cap, String reason) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return "外部目录不可用";
            if (!dir.exists() && !dir.mkdirs()) return "目录创建失败";
            File f = new File(dir, FILE);

            StringBuilder sb = new StringBuilder();
            sb.append("=== AI答题 读屏节点快照 ===\n");
            sb.append("时间 ").append(new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date())).append('\n');
            sb.append("状态 ").append(nz(reason)).append('\n');
            sb.append("hasRoot ").append(cap != null && cap.hasRoot).append('\n');
            sb.append("包名 ").append(cap == null ? "" : nz(cap.pkg)).append('\n');

            List<String> texts = cap == null ? null : cap.texts;
            List<AccessibilityNodeInfo> nodes = cap == null ? null : cap.nodes;
            int n = texts == null ? 0 : texts.size();
            sb.append("文字节点 ").append(n).append('\n');

            if (cap != null && cap.parsed != null) {
                int oc = cap.parsed.options == null ? 0 : cap.parsed.options.size();
                sb.append("认出选项 ").append(oc).append('\n');
                sb.append("题干 ").append(one(nz(cap.parsed.question))).append('\n');
                if (cap.parsed.options != null) {
                    for (Layout.Opt o : cap.parsed.options) {
                        sb.append("  OPT ").append(nz(o.letter))
                          .append(" | ").append(one(nz(o.text))).append('\n');
                    }
                }
            }

            sb.append("\n==== 原始节点（已按视觉顺序排序）====\n");
            sb.append("序号 | top,left,right,bottom | 类名 | 可点 | text | content-desc\n");
            for (int i = 0; i < n; i++) {
                Rect b = new Rect();
                AccessibilityNodeInfo nd = nodes != null && i < nodes.size() ? nodes.get(i) : null;
                String cls = "", desc = "";
                boolean click = false;
                if (nd != null) {
                    try {
                        nd.getBoundsInScreen(b);
                        CharSequence c = nd.getClassName();
                        cls = c == null ? "" : c.toString();
                        CharSequence d = nd.getContentDescription();
                        desc = d == null ? "" : d.toString();
                        click = nd.isClickable();
                    } catch (Throwable ignored) { }
                }
                sb.append(i + 1).append(" | ")
                  .append(b.top).append(',').append(b.left).append(',')
                  .append(b.right).append(',').append(b.bottom)
                  .append(" | ").append(cls)
                  .append(" | ").append(click ? "Y" : "N")
                  .append(" | ").append(one(nz(texts.get(i))))
                  .append(" | ").append(one(desc)).append('\n');
            }

            Writer w = new OutputStreamWriter(new FileOutputStream(f, false), Charset.forName("UTF-8"));
            w.write(sb.toString());
            w.flush();
            w.close();
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return "写失败 " + t;
        }
    }

    /**
     * 图片识别的快照：把 ML Kit 认出来的**原始行 + 坐标**和版面解析结果一起写下来。
     * 排查「为什么只认出 2 个选项」「题干为什么是空的」全靠它。
     */
    public static String writeOcr(Context ctx, OcrEngine.Hit hit, Layout.Parsed p, String note) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return "外部目录不可用";
            if (!dir.exists() && !dir.mkdirs()) return "目录创建失败";
            File f = new File(dir, "ocrsnap.txt");

            StringBuilder sb = new StringBuilder();
            sb.append("=== 图片识别快照 ===\n");
            sb.append("时间 ").append(new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date())).append('\n');
            sb.append("备注 ").append(nz(note)).append('\n');
            if (hit != null) {
                sb.append("耗时 ").append(hit.ms).append("ms\n");
                sb.append("错误 ").append(hit.error == null ? "无" : hit.error).append('\n');
                sb.append("识别行数 ").append(hit.lines.size()).append('\n');
            }
            if (p != null) {
                sb.append("解析题干 ").append(one(nz(p.question))).append('\n');
                sb.append("解析选项 ").append(p.options == null ? 0 : p.options.size()).append('\n');
                if (p.options != null) {
                    for (Layout.Opt o : p.options) {
                        sb.append("  OPT ").append(nz(o.letter))
                          .append(" | ").append(one(nz(o.text))).append('\n');
                    }
                }
            }
            sb.append("\n==== ML Kit 原始行（top,left | 文字）====\n");
            if (hit != null) {
                int i = 1;
                for (OcrEngine.Line l : hit.lines) {
                    sb.append(i++).append(" | ").append(l.top).append(',').append(l.left)
                      .append(" | ").append(one(nz(l.text))).append('\n');
                }
            }

            Writer w = new OutputStreamWriter(new FileOutputStream(f, false), Charset.forName("UTF-8"));
            w.write(sb.toString());
            w.flush();
            w.close();
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return "写失败 " + t;
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String one(String s) {
        return s.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }
}
