package com.dsh.aianswer;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 倒计时检测（v7.5）。
 *
 * <p>考试/限时练习页面上通常有 `03:04`、`剩余 12 秒`、`倒计时` 这类文字。
 * 检测到就进入「限时抢答」：投票从 3 家缩到 2 家、总预算从 9 秒砍到 4.5 秒，
 * 在线也只试一家就问本地——倒计时里等 9 秒等于交白卷。
 *
 * <p>单独抽成纯 Java 类是为了能在 JVM 上测：{@link ScreenScan} 依赖无障碍 API，
 * 拿不到真机就没法验证规则，而误判/漏判直接决定会不会超时。
 */
public final class TimerDetect {

    private TimerDetect() {}

    /** 03:04 / 1:23 / 100:59（分钟位可以更长，小时级考试也有）。 */
    private static final Pattern MMSS = Pattern.compile("(^|\\D)(\\d{1,4}):([0-5]\\d)(\\D|$)");

    /** 剩余 12 秒 / 还有 30s。 */
    private static final Pattern SEC = Pattern.compile("(剩余|还有)\\s*\\d{1,4}\\s*(秒|s\\b)");

    /** 明确写了计时相关的字样。 */
    private static final String[] WORDS = {"倒计时", "剩余时间", "考试剩余", "距结束", "答题时间"};

    /**
     * @param texts 屏幕上所有可见文字
     * @return 是否有倒计时
     */
    public static boolean has(List<String> texts) {
        if (texts == null) return false;
        for (String t : texts) {
            if (t == null) continue;
            String s = t.trim();
            if (s.isEmpty() || s.length() > 40) continue;   // 长句是题干/材料，不是计时器

            // 「已用 28:39」是正计时（练习模式），不会把人逼到超时，别当成倒计时去提速，
            // 否则界面上写「抢答模式」会让人莫名其妙。只有「剩余/倒计时/距结束」才算。
            boolean forward = s.contains("已用") || s.contains("用时") || s.contains("耗时");
            boolean backward = s.contains("剩余") || s.contains("倒计时")
                    || s.contains("距结束") || s.contains("考试剩余");
            if (forward && !backward) continue;

            for (String w : WORDS) if (s.contains(w)) return true;
            if (MMSS.matcher(s).find()) return true;
            if (SEC.matcher(s).find()) return true;
        }
        return false;
    }
}
