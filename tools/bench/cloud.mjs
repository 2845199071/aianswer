// 云端准确率基准：跟 tools/bench/bench.mjs 同一套题，方便横向比
// 用法：node cloud.mjs <provider> <apiKey> [cases文件]
//   provider: zhipu | deepseek | 自定义时直接给 "base|model"
// 例：node cloud.mjs zhipu sk-xxxx
//     node cloud.mjs "https://api.siliconflow.cn/v1|Qwen/Qwen2.5-7B-Instruct" sk-xxxx
const PRESET = {
  zhipu:    { base: "https://open.bigmodel.cn/api/paas/v4", model: "glm-4.7-flash", name: "智谱 GLM-4.7-Flash" },
  deepseek: { base: "https://api.deepseek.com",              model: "deepseek-flash",  name: "DeepSeek V4.1-Flash" },
};

const prov = process.argv[2];
const key = process.argv[3];
const casesFile = process.argv[4] || "./cases.mjs";
if (!prov || !key) {
  console.error("用法: node cloud.mjs <zhipu|deepseek|base|model> <apiKey> [cases.mjs]");
  process.exit(1);
}

const cfg = PRESET[prov] || (() => {
  const [base, model] = prov.split("|");
  return { base, model, name: model };
})();

const CASES = (await import(casesFile)).CASES;
const SYS = "你是答题助手。阅读题目和选项，选出唯一正确的选项。只输出一个选项字母（如 A），不要输出任何解释、标点或题干内容。";

const RUN = /(?<![A-Za-z])([A-Fa-f]+)(?![A-Za-z])/g;
function pickLetters(s) {
  if (!s) return "";
  for (const m of s.matchAll(RUN)) return m[1][0].toUpperCase();
  return "";
}

console.log(`模型: ${cfg.name}  (${cfg.model})`);
let ok = 0;
const times = [];
for (const c of CASES) {
  const user = c.q + "\n" + c.o.map((t, i) => `${"ABCD"[i]}. ${t}`).join("\n");
  const t0 = Date.now();
  let got = "", err = "";
  try {
    const r = await fetch(cfg.base.replace(/\/$/, "") + "/chat/completions", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${key}` },
      body: JSON.stringify({
        model: cfg.model,
        messages: [{ role: "system", content: SYS }, { role: "user", content: user }],
        temperature: 0, max_tokens: 8, stream: false,
      }),
    });
    const txt = await r.text();
    if (!r.ok) throw new Error(`HTTP ${r.status} ${txt.slice(0, 80)}`);
    got = pickLetters(JSON.parse(txt).choices[0].message.content);
  } catch (e) { err = e.message; }
  const ms = Date.now() - t0;
  times.push(ms);
  const good = got === c.a;
  if (good) ok++;
  console.log(`${good ? "✓" : "✗"} 期望${c.a} 得到${got || "?"} ${String(ms).padStart(5)}ms | ${c.q.slice(0, 16)}${err ? "  ERR " + err : ""}`);
}

times.sort((a, b) => a - b);
console.log(`\n准确率 ${ok}/${CASES.length} = ${(ok / CASES.length * 100).toFixed(0)}%`);
console.log(`单题中位耗时 ${times[Math.floor(times.length / 2)]}ms`);
