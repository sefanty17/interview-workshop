/**
 * 验收（第二层）：编辑页真正读的是 store/roleEditor.ts 的 loadRoleSettings()——
 * 它把 seed 默认值与本地覆盖层合并。这里在 node 里跑真实的 loadRoleSettings，
 * 断言四张卡的 system_prompt 逐字等于 seed 的 persona_prompt（覆盖层为空时）。
 */
import { build } from "esbuild";
import fs from "node:fs";
import path from "node:path";

const seed = JSON.parse(fs.readFileSync("../data/seed/interviewers.json", "utf8"));
const outfile = path.resolve("node_modules/.cache/_verify-role-settings.mjs");

// localStorage 桩：空覆盖层
globalThis.localStorage = {
  _m: new Map(),
  getItem(k) {
    return this._m.has(k) ? this._m.get(k) : null;
  },
  setItem(k, v) {
    this._m.set(k, v);
  },
  removeItem(k) {
    this._m.delete(k);
  },
};

await build({
  entryPoints: ["src/store/roleEditor.ts"],
  bundle: true,
  format: "esm",
  platform: "node",
  outfile,
  logLevel: "error",
});

const mod = await import(`file://${outfile.replace(/\\/g, "/")}?t=${Date.now()}`);

let failed = 0;
for (const row of seed) {
  const settings = mod.loadRoleSettings(row.code);
  const same = settings.system_prompt === row.persona_prompt;
  console.log(
    `[${same ? "OK " : "DIFF"}] ${row.code.padEnd(9)} system_prompt len=${settings.system_prompt.length}/${row.persona_prompt.length}` +
      `  info换行=${settings.info.split("\n").length} ai_subtitle=${JSON.stringify(settings.ai_subtitle)}` +
      ` old=${JSON.stringify(settings.system_prompt_example_old)}`,
  );
  if (!same) {
    failed += 1;
    let i = 0;
    while (i < settings.system_prompt.length && settings.system_prompt[i] === row.persona_prompt[i]) i += 1;
    console.log(`  首个差异位置 ${i}`);
    console.log(`  实际: ${JSON.stringify(settings.system_prompt.slice(Math.max(0, i - 30), i + 60))}`);
    console.log(`  期望: ${JSON.stringify(row.persona_prompt.slice(Math.max(0, i - 30), i + 60))}`);
  }
  if ("user_name" in settings || "user_subtitle" in settings) {
    failed += 1;
    console.log("  仍存在 user_name / user_subtitle 键");
  }
}

fs.rmSync(outfile, { force: true });
console.log(failed === 0 ? "\nloadRoleSettings 四张卡全部逐字一致" : `\n有 ${failed} 处不一致`);
process.exit(failed === 0 ? 0 : 1);
