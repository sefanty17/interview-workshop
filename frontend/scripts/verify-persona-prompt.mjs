/**
 * 验收：角色编辑页「系统提示词」的默认值必须逐字等于 data/seed/interviewers.json 的 persona_prompt。
 * 做法：用 esbuild 把 data/interviewers.ts 打成一个临时 ESM，读 defaultRoleSettings 的产出，逐字 diff。
 */
import { build } from "esbuild";
import fs from "node:fs";
import path from "node:path";

const seed = JSON.parse(fs.readFileSync("../data/seed/interviewers.json", "utf8"));
const outfile = path.resolve("node_modules/.cache/_vertify-interviewers.mjs");

await build({
  entryPoints: ["src/data/interviewers.ts"],
  bundle: true,
  format: "esm",
  platform: "node",
  outfile,
  logLevel: "error",
});

const mod = await import(`file://${outfile.replace(/\\/g, "/")}?t=${Date.now()}`);

let failed = 0;
for (const row of seed) {
  const card = mod.findInterviewer(row.code);
  const actual = card.personaPrompt;
  const expected = row.persona_prompt;
  const same = actual === expected;
  console.log(`[${same ? "OK " : "DIFF"}] ${row.code.padEnd(9)} len=${actual.length} seed=${expected.length}`);
  if (!same) {
    failed += 1;
    let i = 0;
    while (i < Math.min(actual.length, expected.length) && actual[i] === expected[i]) i += 1;
    console.log(`  首个差异位置: ${i}`);
    console.log(`  实际: ${JSON.stringify(actual.slice(Math.max(0, i - 40), i + 40))}`);
    console.log(`  期望: ${JSON.stringify(expected.slice(Math.max(0, i - 40), i + 40))}`);
  }
}

// exampleDialogue / thinkingMessage 一并核对
for (const row of seed) {
  const card = mod.findInterviewer(row.code);
  const okEx = card.exampleDialogue === (row.example_dialogue || "");
  const okTh = card.thinkingMessage === (row.thinking_message || "");
  console.log(`        ${row.code.padEnd(9)} example_dialogue=${okEx ? "OK" : "DIFF"} thinking_message=${okTh ? "OK" : "DIFF"} -> ${JSON.stringify(card.thinkingMessage)}`);
  if (!okEx || !okTh) failed += 1;
}

fs.rmSync(outfile, { force: true });
console.log(failed === 0 ? "\n全部逐字一致" : `\n有 ${failed} 处不一致`);
process.exit(failed === 0 ? 0 : 1);
