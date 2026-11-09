package com.dsh.aianswer;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 云端答疑：走 OpenAI 兼容的 /chat/completions。
 * 智谱、DeepSeek、以及任何兼容该协议的服务（硅基流动、OpenRouter、本地 vLLM）都用同一套代码，
 * 差别只在 base / model / key —— 所以做成「厂商预设 + 自定义」而不是写死两家。
 */
public final class CloudSolver {

    private static final String TAG = "AIQ";

    public static final class Provider {
        public final String id, label, base, model;
        public final boolean free;
        public final String office;   // 申请 Key 的官网，空表示没有

        Provider(String id, String label, String base, String model, boolean free, String office) {
            this.id = id; this.label = label; this.base = base; this.model = model;
            this.free = free; this.office = office;
        }
    }

    /** 模型名按 2026-10 现状写死：deepseek-chat / deepseek-reasoner 已于 2026-07-24 下线。 */
    public static final Provider ZHIPU = new Provider(
            "zhipu", "智谱 GLM-4.7-Flash（免费）",
            "https://open.bigmodel.cn/api/paas/v4", "glm-4.7-flash", true,
            "https://open.bigmodel.cn/usercenter/apikeys");

    public static final Provider DEEPSEEK = new Provider(
            "deepseek", "DeepSeek V4.1-Flash（付费）",
            "https://api.deepseek.com", "deepseek-flash", false,
            "https://platform.deepseek.com/api_keys");

    // ---- 以下四家均实测国内可直连、OpenAI 兼容、有长期免费档（2026-10）----
    // 千帆：ERNIE-Speed-128K 永久免费，RPM 60，实测端点 0.44s，最快且频率最宽松
    public static final Provider QIANFAN = new Provider(
            "qianfan", "百度千帆 ERNIE-Speed（免费 · 60 次/分）",
            "https://qianfan.baidubce.com/v2", "ernie-speed-128k", true,
            "https://console.bce.baidu.com/qianfan/ais/console/applicationConsole/application");

    // ModelScope：绑定阿里云并实名后每天 2000 次（单模型 200~500 次）。
    // 2026-10 实测免费列表只有 37 个模型，Qwen3-8B 并不在其中（预设曾写错，会 404）。
    // 选 DeepSeek-V4.1-Flash：与官方付费 API 同款；思考过程单独放在 reasoning_content，
    // content 就是最终答案，与现有「content 空则从 reasoning 兜底抽字母」的解析完全兼容。
    // 想换模型：列表见 https://api-inference.modelscope.cn/v1/models （如 Qwen/Qwen3.8-Flash-Next）
    public static final Provider MODELSCOPE = new Provider(
            "modelscope", "ModelScope 魔搭 DeepSeek-V4.1-Flash（免费 · 2000 次/天）",
            "https://api-inference.modelscope.cn/v1", "deepseek-ai/DeepSeek-V4.1-Flash", true,
            "https://modelscope.cn/my/myaccesstoken");

    // 硅基流动：9B 以下小模型长期免费，30 RPM / 60K TPM
    public static final Provider SILICON = new Provider(
            "silicon", "硅基流动 Qwen3-8B（免费 · 30 次/分）",
            "https://api.siliconflow.cn/v1", "Qwen/Qwen3-8B", true,
            "https://cloud.siliconflow.cn/account/ak");

    // 混元 Lite：永久免费，256K 上下文
    public static final Provider HUNYUAN = new Provider(
            "hunyuan", "腾讯混元 Lite（免费）",
            "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-lite", true,
            "https://console.cloud.tencent.com/hunyuan/api-key");

    public static final Provider CUSTOM = new Provider(
            "custom", "自定义（任何 OpenAI 兼容服务）", "", "", false, "");

    /** 顺序 = 弹窗里的排列顺序，按「免费额度 + 速度」从优到次。 */
    public static final Provider[] ALL =
            {QIANFAN, SILICON, MODELSCOPE, ZHIPU, HUNYUAN, DEEPSEEK, CUSTOM};

    public static Provider byId(String id) {
        for (Provider p : ALL) if (p.id.equals(id)) return p;
        return ZHIPU;
    }

    public static final class Out {
        public String letters = "";
        public String error  = null;
        public long   ms     = 0;
    }

    // 读超时 4.5 秒：glm-4.7-flash 正常 1~2 秒出结果，4.5 秒还没回就当卡住。
    // 原来 25 秒，一次卡顿整题等 25 秒（实测第 11 题）；加上一次重试最坏约 10 秒。
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS    = 4500;

    private static final String SYS_SINGLE =
            "你是答题助手。阅读题目和选项，选出唯一正确的选项。只输出一个选项字母（如 A），不要输出任何解释、标点或题干内容。";
    private static final String SYS_MULTI =
            "你是答题助手。阅读题目和选项，选出所有正确的选项。只输出选项字母组合（如 ABD），不要输出任何解释、标点或题干内容。";

    // 连续的大写字母段（ABD 这种多选连写要整体吃下），且前后不能是字母，
    // 避免把 "Apple"/"Because" 里的首字母误认成选项
    private static final Pattern RUN = Pattern.compile("(?<![A-Za-z])([A-Fa-f]+)(?![A-Za-z])");

    private CloudSolver() {}

    // ---------------- 熔断 ----------------
    // 免费档并发 1，连续 1302/1305 时每题都要白等一轮重试（实测每题多 5 秒）。
    // 连续失败两次就短暂停用云端，期间直接走端侧，保证 1 秒内出答案。
    private static volatile int  failStreak   = 0;
    private static volatile long cooldownUntil = 0;
    private static volatile String lastFail   = "";

    private static final long COOLDOWN_TRANSIENT = 90_000;    // 限流/过载：90 秒
    private static final long COOLDOWN_PERMANENT = 600_000;   // 欠费/额度用尽：10 分钟

    public static boolean cooling() { return System.currentTimeMillis() < cooldownUntil; }
    public static long cooldownLeftSec() {
        return Math.max(0, (cooldownUntil - System.currentTimeMillis()) / 1000);
    }
    public static String lastFailReason() { return lastFail; }
    public static void resetBreaker() { failStreak = 0; cooldownUntil = 0; lastFail = ""; }

    private static void onFail(boolean permanent, String reason) {
        lastFail = reason;
        failStreak++;
        if (permanent) {
            cooldownUntil = System.currentTimeMillis() + COOLDOWN_PERMANENT;
            failStreak = 0;
        } else if (failStreak >= 2) {
            cooldownUntil = System.currentTimeMillis() + COOLDOWN_TRANSIENT;
            failStreak = 0;
        }
    }

    public static Out ask(Provider p, String key, String baseOverride, String modelOverride,
                          String question, List<String> optionLines, boolean multi) {
        Out out = new Out();
        long t0 = System.currentTimeMillis();
        try {
            String base  = isBlank(baseOverride)  ? p.base  : baseOverride.trim();
            String model = isBlank(modelOverride) ? p.model : modelOverride.trim();
            if (isBlank(base) || isBlank(model)) { out.error = "没填 Base URL 或模型名"; return out; }
            if (isBlank(key)) { out.error = "没填 API Key"; return out; }
            if (cooling()) {
                out.error = "在线暂停中（" + cooldownLeftSec() + " 秒后重试）";
                return out;
            }

            String url = base.endsWith("/") ? base + "chat/completions" : base + "/chat/completions";

            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content", multi ? SYS_MULTI : SYS_SINGLE));
            msgs.put(new JSONObject().put("role", "user").put("content", buildUser(question, optionLines)));

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", msgs);
            body.put("temperature", 0);
            body.put("max_tokens", multi ? 48 : 24);
            body.put("stream", false);
            // 思考模型（GLM-4.7 / DeepSeek V4 等默认开 thinking）会先输出思考过程把 max_tokens 吃光，
            // content 直接为空。这里显式关掉；不认这个参数的服务，postWithRetry 会自动去掉再试。
            body.put("thinking", new JSONObject().put("type", "disabled"));

            String resp = postWithRetry(url, key, body);
            JSONObject choice = new JSONObject(resp).getJSONArray("choices").getJSONObject(0);
            JSONObject msgObj = choice.getJSONObject("message");
            String content = msgObj.optString("content", "");
            String reasoning = msgObj.optString("reasoning_content", "");
            String finish = choice.optString("finish_reason", "");

            resetBreaker();
            out.letters = pickLetters(content, multi);
            if (out.letters.isEmpty() && !reasoning.isEmpty()) {
                // 思考没被关掉：答案往往混在思考内容里，兜底再抽一次
                out.letters = pickLetters(reasoning, multi);
            }
            if (out.letters.isEmpty()) {
                if ("length".equals(finish) && content.trim().isEmpty()) {
                    out.error = "模型还在思考就被截断了，没写出答案 · 再点一次试试";
                } else {
                    out.error = "模型没给出字母：" + shorten(content.trim().isEmpty() ? reasoning : content);
                }
            }
        } catch (HttpError he) {
            out.error = he.getMessage();
            onFail(he.permanent, he.getMessage());
            Log.w(TAG, "云端失败 " + he.code + " permanent=" + he.permanent + " " + he.getMessage());
        } catch (Throwable t) {
            out.error = "网络异常：" + t.getClass().getSimpleName();
            onFail(false, out.error);
            Log.w(TAG, "云端异常", t);
        }
        out.ms = System.currentTimeMillis() - t0;
        return out;
    }

    private static String buildUser(String question, List<String> optionLines) {
        StringBuilder sb = new StringBuilder();
        sb.append(question == null ? "" : question.trim());
        if (optionLines != null) for (String l : optionLines) sb.append('\n').append(l);
        return sb.toString();
    }

    static String pickLetters(String content, boolean multi) {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        Matcher m = RUN.matcher(content);
        while (m.find()) {
            String run = m.group(1);
            for (int i = 0; i < run.length(); i++) {
                char c = Character.toUpperCase(run.charAt(i));
                if (sb.indexOf(String.valueOf(c)) >= 0) continue;
                sb.append(c);
                if (!multi) return sb.toString();
            }
        }
        return sb.toString();
    }

    private static String postWithRetry(String url, String key, JSONObject body) throws Exception {
        try {
            return postOnce(url, key, body.toString(), READ_TIMEOUT_MS);
        } catch (HttpError e) {
            // 永久性错误（欠费/额度用尽/权限）：重试不会变结果，直接抛
            if (e.permanent) throw e;
            // 不认 thinking 参数的服务：去掉再试一次
            if (e.code == 400 && body.has("thinking")) {
                body.remove("thinking");
                return postOnce(url, key, body.toString(), READ_TIMEOUT_MS);
            }
            // 1302 限流 / 1305 过载 / 5xx：只退避一次，间隔 0.8 秒。
            // 原来重试两次（1.6s + 3.2s），实测每题白等 5 秒才回退端侧，得不偿失。
            if (e.code == 429 || e.code == 500 || e.code == 502 || e.code == 503) {
                Thread.sleep(800);
                return postOnce(url, key, body.toString(), READ_TIMEOUT_MS);
            }
            throw e;
        }
    }

    private static String postOnce(String url, String key, String json, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(readTimeout);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Accept", "application/json");
        // 智谱在没有 UA 时会直接回 1305「服务过载」，必须显式带上
        c.setRequestProperty("User-Agent", "AIAnswer/3.0 (Android)");

        OutputStream os = c.getOutputStream();
        os.write(json.getBytes(StandardCharsets.UTF_8));
        os.close();

        int code = c.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        String text = readAll(is);
        c.disconnect();

        if (code < 200 || code >= 300) {
            // 把原始响应打出来：光看「429」没法判断是 1302/1305/1308，排查全靠它
            Log.w(TAG, "HTTP " + code + " body=" + shortenBody(text));
            throw new HttpError(code, explain(code, text), isPermanent(text));
        }
        return text;
    }

    /** 欠费、额度用尽、套餐无权限这类重试也不会好的错误。 */
    private static boolean isPermanent(String body) {
        if (body == null) return false;
        // 1113 欠费 / 1308 额度上限 / 1310 周月上限 / 1309 套餐到期 / 1311 套餐无此模型 / 1313 公平使用限制
        return body.contains("1113") || body.contains("1308") || body.contains("1310")
                || body.contains("1309") || body.contains("1311") || body.contains("1313");
    }

    private static String shortenBody(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    private static String explain(int code, String body) {
        if (body != null && body.contains("1113")) return "余额不足，或该模型需要付费（1113）";
        switch (code) {
            case 401: return "Key 无效或已失效（401）";
            case 402: return "账户余额不足（402）";
            case 403: return "无权限或未实名（403）";
            case 404: return "接口地址不对（404），检查 Base URL";
            case 429:
                if (body != null && body.contains("1302")) return "免费档限流（1302 · 限 1 并发）· 已自动重试，稍等再点";
                if (body != null && body.contains("1305")) return "服务端过载（1305）· 已自动重试，稍等再点";
                return "请求太频繁（429）· 已自动重试，稍等再点";
            case 500: case 502: case 503: return "服务端故障（" + code + "），稍后再试";
            default:  return "HTTP " + code + "：" + shorten(body);
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > 90 ? s.substring(0, 90) + "…" : s;
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    /** 把 HTTP 状态码带出来，好让上层决定要不要回退。 */
    public static final class HttpError extends Exception {
        public final int code;
        public final boolean permanent;
        HttpError(int code, String msg, boolean permanent) {
            super(msg);
            this.code = code;
            this.permanent = permanent;
        }
    }
}
