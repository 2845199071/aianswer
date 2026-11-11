package com.dsh.aianswer;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/** 无障碍树 → 文字列表 → 交给 Layout 解析 → 映射回节点。 */
public class ScreenScan {

    public static class Opt {
        public String letter;
        public String text;
        public AccessibilityNodeInfo node;
        /** 图片识别模式下没有节点，只有视觉模型给的矩形 {l,t,r,b}，点击靠它。 */
        public int[] box;
    }

    public static class Result {
        public String pkg = "";
        public String question = "";
        public final List<Opt> options = new ArrayList<>();
        public final List<String> texts = new ArrayList<>();   // 原始屏幕文字，题型判断用
        /** 题干节点的屏幕坐标 {l,t,r,b}：悬浮窗要避开它，否则会压住题目第一行。 */
        public int[] questionRect;
        /** 页面上有倒计时（考试/限时练习）。有的话不能多模型投票，慢慢问会超时。 */
        public boolean hasTimer;
        public AccessibilityNodeInfo next;
    }

    /** 一次抓取的完整结果（供界面与导出调试用）。 */
    public static class Capture {
        public String pkg = "";
        public List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        public List<String> texts = new ArrayList<>();
        public Layout.Parsed parsed = new Layout.Parsed();
        public boolean hasRoot;
    }

    /**
     * 取要读的那个窗口的根节点。
     *
     * <p>不能直接用 getRootInActiveWindow()：悬浮窗（TYPE_APPLICATION_OVERLAY）在部分机型上
     * 会被算成活动窗口，那样读到的是我们自己的面板文字，题干自然对不上题库。
     * 这里优先从 getWindows() 里挑「不是本应用、且是应用窗口」的那个。
     */
    private static AccessibilityNodeInfo pickRoot(AccessibilityService svc) {
        // ① 优先用系统给的「焦点窗口」根节点。
        // 先遍历 getWindows() 找 isActive 有个坑：多窗口时（微信小程序 + 后台 LauncherUI）
        // 可能先拿到后台那个，collect 全是不可见节点，最后读出 0 段文字。
        try {
            AccessibilityNodeInfo focus = svc.getRootInActiveWindow();
            if (focus != null) {
                CharSequence fp = focus.getPackageName();
                String fpkg = fp == null ? "" : fp.toString();
                if (!fpkg.equals(svc.getPackageName())) return focus;
            }
        } catch (Throwable ignored) { }

        // ② 回退：遍历窗口，挑第一个「不是自己、且有内容」的
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = svc.getWindows();
            if (ws != null) {
                AccessibilityNodeInfo any = null;
                for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                    if (w == null) continue;
                    if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                        continue;                     // 跳过输入法、状态栏、悬浮窗等
                    }
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root == null) continue;
                    CharSequence p = root.getPackageName();
                    String pkg = p == null ? "" : p.toString();
                    if (pkg.equals(svc.getPackageName())) continue;   // 跳过自己
                    if (any == null) any = root;
                    if (w.isActive()) return root;
                }
                if (any != null) return any;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    public static Capture capture(AccessibilityService svc) {
        Capture c = new Capture();
        AccessibilityNodeInfo root = pickRoot(svc);
        if (root == null) return c;
        c.hasRoot = true;
        CharSequence p = root.getPackageName();
        c.pkg = p == null ? "" : p.toString();
        List<int[]> pos = new ArrayList<>();
        collect(root, c.nodes, c.texts, pos, 0);
        sortByPosition(c.nodes, c.texts, pos);
        c.parsed = Layout.parse(c.texts, pos);
        return c;
    }

    /**
     * 按屏幕坐标重排。
     * 关键：H5 / WebView 页面的无障碍节点顺序跟随 DOM，不代表视觉上从上到下，
     * 直接拿遍历顺序去「从选项往前倒推题干」会拼错题（实测墨学云页面把标题当成题干）。
     * 原生 App 顺序通常是对的，排序对它们没有副作用。
     */
    private static void sortByPosition(List<AccessibilityNodeInfo> nodes, List<String> texts, List<int[]> pos) {
        int n = texts.size();
        if (n < 2 || pos.size() != n) return;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        final List<int[]> pp = pos;
        java.util.Arrays.sort(idx, new java.util.Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                int ta = pp.get(a)[0], tb = pp.get(b)[0];
                if (Math.abs(ta - tb) > 12) return ta < tb ? -1 : 1;   // 不同行，按上边界
                int la = pp.get(a)[1], lb = pp.get(b)[1];               // 同一行，按左边界
                return la < lb ? -1 : (la > lb ? 1 : 0);
            }
        });
        List<String> nt = new ArrayList<>(n);
        List<AccessibilityNodeInfo> nn = new ArrayList<>(n);
        List<int[]> np = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            nt.add(texts.get(idx[i]));
            nn.add(nodes.get(idx[i]));
            np.add(pos.get(idx[i]));
        }
        texts.clear();
        texts.addAll(nt);
        nodes.clear();
        nodes.addAll(nn);
        pos.clear();
        pos.addAll(np);
    }

    public static Result scan(AccessibilityService svc) {
        Result r = new Result();
        Capture cap = capture(svc);
        r.pkg = cap.pkg;
        r.texts.addAll(cap.texts);
        List<AccessibilityNodeInfo> nodes = cap.nodes;
        Layout.Parsed parsed = cap.parsed;
        r.question = parsed.question;
        for (Layout.Opt lo : parsed.options) {
            Opt o = new Opt();
            o.letter = lo.letter;
            o.text = lo.text;
            o.node = lo.index >= 0 && lo.index < nodes.size() ? nodes.get(lo.index) : null;
            r.options.add(o);
        }
        // 题干节点坐标：用题干前 8 个字反查节点。悬停窗避让要连题干一起避开，
        // 只避选项的话，窗口一高就压住题目正文（实测第 1 行被盖住）。
        String head = parsed.question == null ? "" : parsed.question.replace("\n", " ").trim();
        if (head.length() > 8) head = head.substring(0, 8);
        if (!head.isEmpty()) {
            for (AccessibilityNodeInfo n : nodes) {
                CharSequence t = n == null ? null : n.getText();
                if (t == null) continue;
                String tx = t.toString().replace("\n", " ").trim();
                if (!tx.contains(head)) continue;
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                if (b.width() > 0 && b.height() > 0) {
                    r.questionRect = new int[]{b.left, b.top, b.right, b.bottom};
                    break;
                }
            }
        }

        for (int idx : parsed.nextCandidates) {
            if (idx < 0 || idx >= nodes.size()) continue;
            AccessibilityNodeInfo n = nodes.get(idx);
            if (r.next == null) r.next = n;
            if (n.isClickable()) { r.next = n; break; }
        }
        r.hasTimer = detectTimer(r.texts);
        return r;
    }

    /** 倒计时检测交给纯 Java 的 {@link TimerDetect}：规则能单测，改起来不用担心无障碍环境。 */
    static boolean detectTimer(java.util.List<String> texts) {
        return TimerDetect.has(texts);
    }

    private static void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> nodes,
                                List<String> texts, List<int[]> pos, int depth) {
        if (n == null || depth > 40) return;
        // v1.8 提速：不可见节点连同整棵子树直接跳过（H5 页面隐藏节点常占一大半）
        if (!n.isVisibleToUser()) return;
        String text = n.getText() == null ? "" : n.getText().toString().trim();
        if (text.isEmpty() && n.getContentDescription() != null) {
            text = n.getContentDescription().toString().trim();
        }
        text = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (!text.isEmpty() && n.isVisibleToUser()) {
            android.graphics.Rect r = new android.graphics.Rect();
            n.getBoundsInScreen(r);
            nodes.add(n);
            texts.add(text);
            pos.add(new int[]{r.top, r.left});
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) collect(c, nodes, texts, pos, depth + 1);
        }
    }

    /** 点击节点：ACTION_CLICK → 父链可点击祖先 → 手势点中心。 */
    public static boolean click(AccessibilityService svc, AccessibilityNodeInfo node) {
        return click(svc, node, false);
    }

    /**
     * 点击节点。
     *
     * <p><b>节点点击永远优先，拟人模式也不例外。</b>这一条是踩坑后改的：
     * 早期版本「拟人」会跳过 ACTION_CLICK、只发手势，实测在部分机型/部分 App 上
     * {@code dispatchGesture} 返回 true 但界面毫无反应——选项没被选中，
     * 界面上却显示「已答」，比不够拟人严重得多。
     *
     * <p>所以 {@code human} 现在只影响**手势兜底那条路**：按压 90~180ms、落点偏 ±3dp。
     * 标准控件走 ACTION_CLICK（瞬时、可靠），间隔感由调用方的随机延迟提供。
     */
    public static boolean click(AccessibilityService svc, AccessibilityNodeInfo node, boolean human) {
        if (node == null) return false;
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            AccessibilityNodeInfo p = cur.getParent();
            if (p == null) break;
            cur = p;
        }
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        if (b.width() > 0 && b.height() > 0 && Build.VERSION.SDK_INT >= 24) {
            float density = svc.getResources().getDisplayMetrics().density;
            int cx = b.centerX();
            int cy = b.centerY();
            long dur = 60;
            if (human) {
                java.util.Random rnd = new java.util.Random();
                cx += (int) ((rnd.nextInt(7) - 3) * density);   // ±3dp
                cy += (int) ((rnd.nextInt(7) - 3) * density);
                cx = Math.max(b.left + 2, Math.min(cx, b.right - 2));
                cy = Math.max(b.top + 2, Math.min(cy, b.bottom - 2));
                dur = 90 + rnd.nextInt(90);                     // 90~180ms
            }
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(cx, cy);
            android.accessibilityservice.GestureDescription.Builder gb =
                    new android.accessibilityservice.GestureDescription.Builder();
            gb.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, dur));
            return svc.dispatchGesture(gb.build(), null, null);
        }
        return false;
    }

    /**
     * 按矩形中心点一下（图片识别专用）。
     * 小程序 / Canvas 页面的选项不是可点击节点，只能按坐标发手势。
     *
     * @param box {l,t,r,b} 屏幕像素坐标，来自视觉模型给的相对矩形
     */
    public static boolean tapAt(AccessibilityService svc, int[] box, boolean human) {
        if (box == null || box.length != 4 || Build.VERSION.SDK_INT < 24) return false;
        int cx = (box[0] + box[2]) / 2;
        int cy = (box[1] + box[3]) / 2;
        long dur = 60;
        if (human) {
            java.util.Random rnd = new java.util.Random();
            float density = svc.getResources().getDisplayMetrics().density;
            cx += (int) ((rnd.nextInt(7) - 3) * density);
            cy += (int) ((rnd.nextInt(7) - 3) * density);
            dur = 90 + rnd.nextInt(90);
        }
        if (cx <= 0 || cy <= 0) return false;
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(cx, cy);
        android.accessibilityservice.GestureDescription.Builder gb =
                new android.accessibilityservice.GestureDescription.Builder();
        gb.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, dur));
        return svc.dispatchGesture(gb.build(), null, null);
    }
}
