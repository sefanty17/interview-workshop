/**
 * 验收：历史对话流的分段与分组逻辑，必须和上游 `SettingsHistory.vue` 一致。
 *
 * 做法：用 esbuild 把 `historyFlow.ts` 打成临时 ESM，喂真实 API 形状的台词，
 * 断言块划分 / 正文动作切分 / 旁白斜体判定 / 思考链取最后一条非空。
 *
 * 跑法（在 frontend/ 下）：node scripts/verify-history-flow.mjs
 */
import { build } from "esbuild";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";

const SRC = path.resolve("src/components/settings/pages/history/historyFlow.ts");
const OUT = path.join(os.tmpdir(), `history-flow-${Date.now()}.mjs`);

await build({
  entryPoints: [SRC],
  outfile: OUT,
  bundle: true,
  format: "esm",
  platform: "node",
  logLevel: "silent",
});

const { groupLines, parseSegments, PAGE_SIZE, NARRATION_NAMES } = await import(
  pathToFileURL(OUT).href
);
fs.rmSync(OUT, { force: true });

let failed = 0;
function check(name, actual, expected) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) {
    console.log(`  ok   ${name}`);
  } else {
    failed += 1;
    console.log(`  FAIL ${name}\n       期望 ${e}\n       实际 ${a}`);
  }
}

console.log("historyFlow 验收（基准 5a778fab SettingsHistory.vue）");

// ── 1. parseSegments：全角（…）切段 + 结尾句号剥除 ──────────────────────
check(
  "正文按全角括号切段，动作单独成段",
  parseSegments("说具体点。（翻简历）你做过什么。", "翻简历", false),
  [
    { type: "dialogue", text: "说具体点" },
    { type: "dialogue", text: "你做过什么" },
    { type: "action", text: "翻简历" },
  ],
);

// 半角括号不该被切（prompt 里定死的是全角）
check(
  "半角括号不参与切段",
  parseSegments("状态机(就是个表)", undefined, false),
  [{ type: "dialogue", text: "状态机(就是个表)" }],
);

// 旁白不剥结尾句号
check("旁白保留结尾句号", parseSegments("他沉默了一会儿。", undefined, true), [
  { type: "dialogue", text: "他沉默了一会儿。" },
]);
check("非旁白剥结尾句号", parseSegments("他沉默了一会儿。", undefined, false), [
  { type: "dialogue", text: "他沉默了一会儿" },
]);

// ── 2. groupLines：连续同人合并、旁白不冠名、思考链取最后一条非空 ──────────
const messages = [
  { role: "user", content: "我做过一个在线商城", displayName: "小桉", userMessageSeq: 1 },
  { role: "assistant", content: "说具体点。（翻简历）", action: "翻简历", displayName: "风雪", thinking: null },
  { role: "assistant", content: "订单模块呢？", displayName: "风雪", thinking: "先看技术栈再说。" },
  { role: "system", content: "（面试官端起水杯）", displayName: null },
  { role: "user", content: "用的 Spring Boot", displayName: "小桉", userMessageSeq: 2 },
  { role: "assistant", content: "JPA 啊。", displayName: "风雪", thinking: null },
];

const blocks = groupLines(messages);

check("块数：小桉 / 风雪(2条) / 旁白 / 小桉 / 风雪", blocks.length, 5);
check("第 0 块是玩家小桉", [blocks[0].displayName, blocks[0].isNarration], ["小桉", false]);
check("第 0 块带 userMessageSeq", blocks[0].userMessageSeq, 1);
check("第 1 块是风雪，合并 2 条", [blocks[1].displayName, blocks[1].lines.length], ["风雪", 2]);
check("第 1 块思考链取最后一条非空", blocks[1].thinking, "先看技术栈再说。");
check("第 2 块是旁白：不冠名 + isNarration", [blocks[2].displayName, blocks[2].isNarration], ["", true]);
check("旁白不做动作切分之外的命名", blocks[2].lines[0].segments.length >= 0, true);
check("第 3 块是第二轮玩家，序号 2", [blocks[3].displayName, blocks[3].userMessageSeq], ["小桉", 2]);
check("第 4 块是风雪（被用户行打断后另起一块）", blocks[4].displayName, "风雪");

// ── 3. 用户输入不做动作切分 ────────────────────────────────────────────
check(
  "用户消息里的（…）不切段",
  groupLines([{ role: "user", content: "我做过（商城）项目", displayName: "小桉" }])[0].lines[0]
    .segments,
  [{ type: "dialogue", text: "我做过（商城）项目" }],
);

// ── 4. 缺失显示名 → 直接判为旁白（上游同样如此，兜底分支不可达）──────────
// 判据：narrationNames 含空串，所以 displayName 为 null/"" 时 isNarration 必为 true，
// 名字走不到 `displayName || (role==="user" ? "我" : "未知的声音")` 那个兜底。
// ← 这正是「面试官行必须落 display_name」的原因：不落就会被渲染成旁白（斜体不冠名），
//   批次 7 已修后端（ChatService.saveMessage 加 speakerName）。
check("无 displayName 的 AI 行判为旁白", [groupLines([{ role: "assistant", content: "嗯" }])[0].displayName, groupLines([{ role: "assistant", content: "嗯" }])[0].isNarration], ["", true]);
check("无 displayName 的用户行同样判为旁白", groupLines([{ role: "user", content: "嗯" }])[0].isNarration, true);

// ── 5. 常量 ────────────────────────────────────────────────────────────
check("PAGE_SIZE 对齐上游 100", PAGE_SIZE, 100);
check("旁白名集合含「系统」", NARRATION_NAMES.has("系统"), true);

// ── 6. 空正文跳过 ──────────────────────────────────────────────────────
check("空正文不生成块", groupLines([{ role: "assistant", content: "   " }]).length, 0);

console.log(failed === 0 ? "\n全部通过" : `\n${failed} 条失败`);
process.exit(failed === 0 ? 0 : 1);
