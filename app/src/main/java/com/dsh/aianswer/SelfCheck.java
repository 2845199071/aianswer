package com.dsh.aianswer;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 环境自检（v7.8）。
 *
 * <p>为什么要有它：这个 App 在「作者自己的手机」上开箱即用，因为作者早就
 * 开好了读屏、授了悬浮窗、配了 Key、下过模型。换一个人装，上面四件事一件都没做，
 * 表现就是「装上了但什么都不出」——用户不会知道是缺权限还是缺 Key，只会说「用不了」。
 *
 * <p>所以把「能不能用」拆成可判定的几条，逐条给出结论和下一步动作：
 * <ol>
 *   <li>原生库：只有 arm64 的 so，32 位机型或加载失败 → 本地模型不可用（在线仍可）</li>
 *   <li>系统版本：截图识别要 Android 11+，更低版本只能读屏</li>
 *   <li>读屏（无障碍服务）：没开 → 读不到屏幕，一切功能失效</li>
 *   <li>悬浮窗权限：没授 → 不出悬浮球</li>
 *   <li>存储可写：模型/题库都写在私有目录，写不进去就存不住东西</li>
 *   <li>解题方式：在线 Key 或本地模型，至少得有一个</li>
 * </ol>
 *
 * <p>全部检查都是只读 + 一次小文件写入测试，不联网、不改设置。
 */
public final class SelfCheck {

    /** 每一项要做什么（给界面挂按钮用）。 */
    public static final int ACT_NONE = 0;
    public static final int ACT_ACC = 1;        // 去系统无障碍设置
    public static final int ACT_OVERLAY = 2;    // 去悬浮窗授权页
    public static final int ACT_SOLVER = 3;     // 打开解题方式设置
    public static final int ACT_MODEL = 4;      // 打开本地模型下载

    public static final class Item {
        public final String title;
        public final String detail;   // 现状 / 为什么
        public final boolean ok;
        public final boolean fatal;   // 致命：不解决就完全用不了
        public final int action;

        Item(String title, String detail, boolean ok, boolean fatal, int action) {
            this.title = title;
            this.detail = detail;
            this.ok = ok;
            this.fatal = fatal;
            this.action = action;
        }
    }

    private SelfCheck() {}

    public static List<Item> run(Context c) {
        List<Item> out = new ArrayList<Item>();

        // ① 原生库（llama.cpp 四个 so + 自己那个）：只会 catch，不会崩
        boolean so = Llama.ensureLoaded();
        String abis = Build.SUPPORTED_ABIS == null ? "" : String.join("/", Build.SUPPORTED_ABIS);
        out.add(new Item("本地模型引擎",
                so ? "已加载（" + abis + "）"
                   : "加载失败：" + Llama.loadError() + "\n本版只带 arm64-v8a 原生库，"
                     + "32 位机器或非 arm64 芯片只能走在线模型",
                so, false, ACT_NONE));

        // ② 截图识别能力：takeScreenshot 是 Android 11（API 30）才有的
        boolean shot = Build.VERSION.SDK_INT >= 30;
        out.add(new Item("截图识别（图片题）",
                shot ? "可用（Android " + Build.VERSION.SDK_INT + "）"
                     : "Android 11 以下不支持：微信小程序这类「把题目画成图片」的页面做不了，"
                       + "只能答原生文本框的题",
                shot, false, ACT_NONE));

        // ③ 读屏
        boolean acc = QService.serviceAlive() || accEnabledInSettings(c);
        out.add(new Item("读屏（无障碍服务）",
                acc ? "已开启" : "没开 → 读不到题干和选项，悬浮球也不会出现",
                acc, true, ACT_ACC));

        // ④ 悬浮窗
        boolean ov = canDrawOverlay(c);
        out.add(new Item("悬浮窗权限",
                ov ? "已授权" : "没授权 → 出不了悬浮球，只能回 App 里手动点",
                ov, true, ACT_OVERLAY));

        // ⑤ 存储：真写一次再删，比只看目录存在可靠（ROM 可能把 files 目录挂成只读）
        String store = null;
        try {
            File d = new File(c.getFilesDir(), "models");
            if (!d.exists() && !d.mkdirs()) store = "建目录失败：" + d.getAbsolutePath();
            if (store == null) {
                File t = new File(c.getFilesDir(), "selfcheck.tmp");
                FileOutputStream fo = new FileOutputStream(t);
                fo.write(1);
                fo.close();
                if (!t.delete()) t.deleteOnExit();
                File self = new File(c.getFilesDir(), "selfbanks");
                if (!self.exists() && !self.mkdirs()) store = "题库目录建不出来";
            }
        } catch (Throwable e) {
            store = "写入失败：" + e;
        }
        out.add(new Item("存储可写",
                store == null ? "正常（应用私有目录）" : store + "\n模型与题库都存这里，写不进去会存不住",
                store == null, true, ACT_NONE));

        // ⑥ 解题方式：云端 Key 或本地模型，至少一个
        int cloud = Prefs.cloudReadyList(c).size();
        boolean local = ModelManager.pickReady(c, Prefs.specId(c)) != null;
        boolean solverOk = cloud > 0 || local;
        out.add(new Item("解题方式",
                solverOk
                        ? (cloud > 0 ? "在线 · 已配 " + cloud + " 家 Key" : "本地模型 · 已就绪")
                        : "还没配：去「在线模型」填一个 Key，或去「本地模型」下载一个（约 0.5~2.3 GB）",
                solverOk, true, ACT_SOLVER));

        return out;
    }

    /** 全部致命项都过了没有（非致命项不影响「能用」）。 */
    public static boolean usable(Context c) {
        for (Item i : run(c)) {
            if (i.fatal && !i.ok) return false;
        }
        return true;
    }

    public static int fatalMissing(Context c) {
        int n = 0;
        for (Item i : run(c)) if (i.fatal && !i.ok) n++;
        return n;
    }

    private static boolean canDrawOverlay(Context c) {
        try {
            return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 服务刚被系统杀掉的那几秒 serviceAlive 是 false，查一下系统里的启用列表做兜底。 */
    private static boolean accEnabledInSettings(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }
}
