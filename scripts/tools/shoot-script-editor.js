// 剧本编辑器（章节 + 事件模型）自查 + 截图
// 运行：NODE_PATH=... node scripts/tools/shoot-script-editor.js
const { chromium } = require("playwright-core");
const path = require("path");
const fs = require("fs");

const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/docs/design/screenshots/pages";
const BASE = "http://127.0.0.1:5173";

const result = [];
const log = (ok, name, detail) => {
  result.push({ ok, name, detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}  ${detail ?? ""}`);
};

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch({ executablePath: CHROME, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

  let nativeDialogs = 0;
  page.on("dialog", async (d) => {
    nativeDialogs++;
    console.log("  ⚠ 捕获到原生弹窗:", d.type(), d.message());
    await d.dismiss();
  });

  await page.goto(BASE + "/script-editor", { waitUntil: "networkidle" });
  await page.waitForTimeout(600);

  // 左栏剧本列表
  const rows = page.locator("aside .group");
  const rowCount = await rows.count();
  const names = [];
  for (let i = 0; i < rowCount; i++) {
    names.push((await rows.nth(i).locator("div.truncate").first().innerText()).trim());
  }
  log(rowCount === 4, "左栏剧本数量", `${rowCount} → ${names.join(" | ")}`);

  // 章节流程：首剧本 6 张卡 + 开场角标
  const cards = page.locator("button", { hasText: "个事件" });
  const cardCount = await cards.count();
  const firstCardText = await cards.first().innerText();
  log(cardCount === 6, "首剧本章节卡数量", String(cardCount));
  log(firstCardText.includes("开场"), "首张卡带「开场」角标", firstCardText.replace(/\n/g, " / "));
  log(!firstCardText.includes("无人进入"), "开场章节未被标「无人进入」");

  // 点第 2 张卡 → 时间线事件数与卡片标注一致
  const secondExpected = Number((await cards.nth(1).innerText()).match(/(\d+)\s*个事件/)[1]);
  await cards.nth(1).click();
  await page.waitForTimeout(250);
  const eventRows = await page.locator("button[title='删除']").count();
  log(secondExpected === eventRows, "点击章节卡切换时间线", `期望 ${secondExpected} 条，实际 ${eventRows} 条`);

  // 截图 1：章节流程
  await cards.first().click();
  await page.waitForTimeout(250);
  await page.screenshot({ path: path.join(OUT, "script-editor.png") });

  // 截图 2：剧本设置
  await page.getByRole("button", { name: "剧本设置", exact: true }).click();
  await page.waitForTimeout(350);
  await page.screenshot({ path: path.join(OUT, "script-editor-config.png") });
  log((await page.locator("input[readonly]").count()) === 1, "设置页 code 只读输入框存在");

  // 截图 3：校验 + 4 个种子剧本逐个校验
  await page.getByRole("button", { name: "校验", exact: true }).click();
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(OUT, "script-editor-validate.png") });

  const validatePanel = page
    .locator("h4", { hasText: "校验结果" })
    .locator("xpath=ancestor::div[contains(@class,'rounded-xl')][1]");

  const validateReport = [];
  for (let i = 0; i < rowCount; i++) {
    await rows.nth(i).click();
    await page.waitForTimeout(200);
    const name = (await rows.nth(i).locator("div.truncate").first().innerText()).trim();
    const panelText = (await validatePanel.innerText()).replace(/\s+/g, " ");
    validateReport.push(`${name}: ${panelText.includes("未发现问题") ? "未发现问题" : "有问题 → " + panelText.slice(0, 100)}`);
    log(panelText.includes("未发现问题"), `校验 · ${name}`, panelText.slice(0, 60));
  }

  // 修改 ai_dialogue prompt → 刷新后仍在
  await rows.first().click();
  await page.getByRole("button", { name: "章节流程", exact: true }).click();
  await page.waitForTimeout(200);
  const marker = `SELFCHECK-${Date.now()}`;
  const promptBox = page.locator("textarea[placeholder*='给 LLM 的提示词']").first();
  await promptBox.fill(marker);
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await page.waitForTimeout(300);
  await page.reload({ waitUntil: "networkidle" });
  await page.waitForTimeout(500);
  const afterReload = await page.locator("textarea[placeholder*='给 LLM 的提示词']").first().inputValue();
  log(afterReload.includes(marker), "修改 prompt 后刷新仍在（localStorage）", afterReload.slice(0, 40));

  // 禁止使用的原生弹窗检查
  log(nativeDialogs === 0, "无 window.alert/confirm/prompt 弹窗", String(nativeDialogs));

  console.log("\n--- 校验汇总 ---");
  validateReport.forEach((r) => console.log(r));
  console.log("\n--- 截图 ---");
  ["script-editor.png", "script-editor-config.png", "script-editor-validate.png"].forEach((f) =>
    console.log(path.join(OUT, f)),
  );
  const failed = result.filter((r) => !r.ok).length;
  console.log(`\n合计 ${result.length} 项，失败 ${failed} 项`);

  await browser.close();
})();
