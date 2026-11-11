package com.dsh.aianswer;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型市场清单（assets/models.json）。
 *
 * <p>为什么放到 assets 而不是写死代码：加模型、改简介、换下载地址都不用发版，
 * 只要替换这个 JSON 就能上线新档位。
 *
 * <p>每个模型必须写清四件事，否则用户没法选：
 * 适用题型 / 体积与速度 / 一句话定位 / 模板格式。
 */
public final class ModelMarket {

    private static final String TAG = "AIQ";
    private static final String FILE = "models.json";

    public static final class Item {
        public String id = "";
        public String name = "";
        public String badge = "";
        public String fit = "";        // 适用题型
        public String speed = "";      // 体积 · 速度
        public String brief = "";      // 一句话定位
        public String template = "";   // 模板格式
        public String url = "";
        public long bytes = 0;

        /** 尚未上线（没有下载地址）。 */
        public boolean planned() { return url == null || url.trim().isEmpty() || bytes <= 0; }

        /** 列表里的四行简介，按固定顺序拼。 */
        public String lines() {
            StringBuilder sb = new StringBuilder();
            sb.append("适用：").append(fit);
            sb.append('\n').append(speed);
            sb.append('\n').append(brief);
            sb.append('\n').append("模板：").append(template);
            return sb.toString();
        }
    }

    private static List<Item> cache;
    private static long cacheAt = 0;

    private ModelMarket() {}

    public static synchronized List<Item> all(Context c) {
        if (cache != null && System.currentTimeMillis() - cacheAt < 60_000) return cache;
        List<Item> list = new ArrayList<>();
        try {
            String raw = read(c);
            JSONObject root = new JSONObject(raw);
            JSONArray arr = root.optJSONArray("models");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Item it = new Item();
                    it.id       = o.optString("id", "");
                    it.name     = o.optString("name", "");
                    it.badge    = o.optString("badge", "");
                    it.fit      = o.optString("fit", "");
                    it.speed    = o.optString("speed", "");
                    it.brief    = o.optString("brief", "");
                    it.template = o.optString("template", "");
                    it.url      = o.optString("url", "");
                    it.bytes    = o.optLong("bytes", 0);
                    if (!it.id.isEmpty()) list.add(it);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取模型清单失败", t);
        }
        if (list.isEmpty()) {
            // 兜底：assets 丢了也要能用内置三档
            for (ModelManager.Spec s : ModelManager.ALL) {
                Item it = new Item();
                it.id = s.id; it.name = s.name; it.badge = s.badge;
                it.fit = "通用选择题"; it.speed = "约 " + (s.bytes / 1048576) + " MB";
                it.brief = s.brief; it.template = "ChatML";
                it.url = s.url; it.bytes = s.bytes;
                list.add(it);
            }
        }
        cache = list;
        cacheAt = System.currentTimeMillis();
        return list;
    }

    public static Item byId(Context c, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Item it : all(c)) if (id.equals(it.id)) return it;
        return null;
    }

    /** 清单项 → 可下载/可加载的 Spec。 */
    public static ModelManager.Spec specOf(Item it) {
        if (it == null) return null;
        return ModelManager.make(it.id, it.name, it.badge, it.brief, it.url, it.bytes);
    }

    private static String read(Context c) throws Exception {
        InputStream in = c.getAssets().open(FILE);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }
}
