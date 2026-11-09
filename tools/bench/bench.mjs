// PC 侧基准 v3：用 LlamaCompletion 走原始 prompt（不套 chat 模板），
// 完整复刻 App 里的 ChatML + 「答案：」结尾
import { getLlama, LlamaCompletion } from "node-llama-cpp";
const CASES = (await import(process.argv[3] || "./cases.mjs")).CASES;

const modelPath = process.argv[2] || "models/q05.gguf";
const sys = "你是严谨的答题助手。阅读题目和选项，只输出正确选项的字母，不要输出解释、不要重复题干。";

const llama = await getLlama();
console.log("加载模型:", modelPath);
const t0 = Date.now();
const model = await llama.loadModel({ modelPath });
const ctx = await model.createContext({ contextSize: 1024, threads: 8 });
console.log(`模型就绪 ${Date.now() - t0}ms`);

const completion = new LlamaCompletion({ contextSequence: ctx.getSequence() });

let ok = 0;
const times = [];
for (const c of CASES) {
  const body = `${c.q}\n` + c.o.map((t, i) => `${"ABCD"[i]}. ${t}`).join("\n");
  const prompt =
    `<|im_start|>system\n${sys}<|im_end|>\n` +
    `<|im_start|>user\n${body}<|im_end|>\n` +
    `<|im_start|>assistant\n答案：`;

  if (completion.resetChatHistory) completion.resetChatHistory();
  if (completion.resetCompletion) completion.resetCompletion();

  const t = Date.now();
  const out = await completion.generateCompletion(prompt, { maxTokens: 4, temperature: 0 });
  const ms = Date.now() - t;
  times.push(ms);
  const hit = (/[A-D]/.exec(out) || [""])[0];
  const good = hit === c.a;
  if (good) ok++;
  console.log(`${good ? "✓" : "✗"} 期望${c.a} 得到${hit || "?"} ${String(ms).padStart(4)}ms | ${c.q.slice(0, 16)} → ${JSON.stringify(out.slice(0, 14))}`);
}

times.sort((a, b) => a - b);
console.log(`\n准确率 ${ok}/${CASES.length} = ${(ok / CASES.length * 100).toFixed(0)}%`);
console.log(`单题中位耗时 ${times[Math.floor(times.length / 2)]}ms`);
await model.dispose();
