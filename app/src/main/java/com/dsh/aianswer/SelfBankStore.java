package com.dsh.aianswer;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 自选题库存储（v7.2）。
 *
 * <p>每份题库一个 JSON 文件，放在应用私有目录，不需要存储权限。
 * 导入的题库可以有多份，检索时**全部合并**——用户不用来回切库，
 * 同题干不同版本由 {@link Matcher} 的 buildVariants 自动合并答案。
 *
 * <p>数据格式沿用刷题助手的内部题库 JSON：
 * <pre>[{"t":"单选","q":"题干","a":["B"],"o":{"A":"…","B":"…"},"e":"解析"}]</pre>
 */
public final class SelfBankStore {

    public static final class Entry {
        public String id = "";
        public String name = "";
        public int count = 0;
        public long ts = 0;
        /** 索引里有、文件却不见了（被清理工具删掉等）。界面要如实告诉用户，不能装作没导入过。 */
        public boolean lost = false;
    }

    private static String mergedCache;
    private static String mergedSig;

    private SelfBankStore() {}

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "selfbanks");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        android.util.Log.i("AIQ", "自选题库目录 " + dir(c).getAbsolutePath()
                + " 文件数=" + (files == null ? -1 : files.length));
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".json") || n.endsWith(".meta.json")) continue;
            Entry e = new Entry();
            e.id = n.substring(0, n.length() - 5);
            e.name = e.id;
            File meta = new File(dir(c), e.id + ".meta.json");
            try {
                if (meta.exists()) {
                    JSONObject o = new JSONObject(readText(meta));
                    e.name = o.optString("name", e.id);
                    e.count = o.optInt("count", 0);
                    e.ts = o.optLong("ts", 0);
                }
            } catch (Throwable ignored) {}
            out.add(e);
        }
        // 索引里登记过、但文件已经不在了 → 标为丢失，界面提示重新导入
        java.util.Set<String> alive = new java.util.HashSet<>();
        for (Entry e : out) alive.add(e.id);
        for (Entry e : readIndex(c)) {
            if (alive.contains(e.id)) continue;
            e.lost = true;
            out.add(e);
        }
        Collections.sort(out, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) { return Long.compare(b.ts, a.ts); }
        });
        return out;
    }

    // ---------- 索引（SharedPreferences）----------
    // 文件是唯一事实来源，索引只用来「发现文件丢失」，不参与检索。

    private static final String P_INDEX = "selfbank_index";

    private static List<Entry> readIndex(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            String raw = c.getSharedPreferences(P_INDEX, Context.MODE_PRIVATE).getString("list", "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.id = o.optString("id", "");
                e.name = o.optString("name", "");
                e.count = o.optInt("count", 0);
                e.ts = o.optLong("ts", 0);
                if (!e.id.isEmpty()) out.add(e);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static void writeIndex(Context c, List<Entry> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Entry e : list) {
                arr.put(new JSONObject().put("id", e.id).put("name", e.name)
                        .put("count", e.count).put("ts", e.ts));
            }
        } catch (Throwable ignored) {}
        c.getSharedPreferences(P_INDEX, Context.MODE_PRIVATE)
                .edit().putString("list", arr.toString()).apply();
    }

    private static void indexAdd(Context c, Entry e) {
        List<Entry> list = readIndex(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(e.id)) { list.set(i, e); writeIndex(c, list); return; }
        }
        list.add(e);
        writeIndex(c, list);
    }

    private static void indexRemove(Context c, String id) {
        List<Entry> list = readIndex(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) list.remove(i);
        }
        writeIndex(c, list);
    }

    public static int totalCount(Context c) {
        int n = 0;
        for (Entry e : list(c)) if (!e.lost) n += e.count;
        return n;
    }

    /** 有「文件还在」的题库才算能用；只剩索引条目（文件被删）不算。 */
    public static boolean hasBank(Context c) {
        for (Entry e : list(c)) if (!e.lost) return true;
        return false;
    }

    public static String add(Context c, String displayName, String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        String id = "b" + System.currentTimeMillis();
        writeText(new File(dir(c), id + ".json"), json);
        JSONObject meta = new JSONObject();
        meta.put("name", displayName == null || displayName.isEmpty() ? id : displayName);
        meta.put("count", arr.length());
        meta.put("ts", System.currentTimeMillis());
        writeText(new File(dir(c), id + ".meta.json"), meta.toString());
        Entry e = new Entry();
        e.id = id;
        e.name = meta.optString("name", id);
        e.count = arr.length();
        e.ts = meta.optLong("ts", 0);
        indexAdd(c, e);
        invalidate();
        return id;
    }

    public static void remove(Context c, String id) {
        new File(dir(c), id + ".json").delete();
        new File(dir(c), id + ".meta.json").delete();
        indexRemove(c, id);
        invalidate();
    }

    public static void clear(Context c) {
        for (Entry e : list(c)) remove(c, e.id);
        invalidate();
    }

    /** 所有题库合并后的 JSON（给 Matcher 一次性加载）。带缓存，题库没变就不重复读盘。 */
    public static String mergedJson(Context c) {
        List<Entry> es = list(c);
        StringBuilder sig = new StringBuilder();
        for (Entry e : es) sig.append(e.id).append(':').append(e.count).append(';');
        String s = sig.toString();
        if (mergedCache != null && s.equals(mergedSig)) return mergedCache;

        JSONArray out = new JSONArray();
        for (Entry e : es) {
            File f = new File(dir(c), e.id + ".json");
            if (!f.exists()) continue;
            try {
                JSONArray one = new JSONArray(readText(f));
                for (int i = 0; i < one.length(); i++) out.put(one.getJSONObject(i));
            } catch (Throwable ignored) {}
        }
        mergedCache = out.toString();
        mergedSig = s;
        return mergedCache;
    }

    public static void invalidate() {
        mergedCache = null;
        mergedSig = null;
        SelfBank.invalidate();
    }

    private static String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static void writeText(File f, String s) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(s.getBytes("UTF-8"));
        out.close();
    }
}
