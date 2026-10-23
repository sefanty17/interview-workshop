const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const errs = [];
  p.on("pageerror", (e) => errs.push("PAGEERR: " + String(e)));
  p.on("console", (m) => { if (m.type() === "error") errs.push("CONSOLE: " + m.text()); });
  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.waitForTimeout(1200);
  await p.getByRole("button", { name: "剧本", exact: true }).click();
  await p.waitForTimeout(600);

  // 1) 新建剧本弹窗内容
  await p.getByText("新建剧本", { exact: true }).click();
  await p.waitForTimeout(400);
  const dialog = await p.evaluate(() => {
    const dlg = document.querySelector(".z-\\[10003\\]");
    return dlg ? dlg.innerText.replace(/\n+/g, " | ") : "NO DIALOG";
  });
  console.log("NEW_DIALOG:", dialog);
  // 输入名字并创建
  await p.locator('input[placeholder^="例如：算法岗"]').fill("测试剧本 ABC");
  await p.getByText("创建", { exact: true }).click();
  await p.waitForTimeout(800);
  const afterCreate = await p.evaluate(() => document.body.innerText.includes("测试剧本 ABC"));
  console.log("CREATED_PRESENT:", afterCreate);

  // 回到剧本页（创建后可能跳转到编辑器，回退）
  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.waitForTimeout(1000);
  await p.getByRole("button", { name: "剧本", exact: true }).click();
  await p.waitForTimeout(500);

  // 2) AdventurePanel：点第一张卡
  await p.locator(".grid .group").first().click();
  await p.waitForTimeout(500);
  const panel = await p.evaluate(() => {
    const el = document.querySelector(".z-\\[10002\\]");
    return el ? el.innerText.replace(/\n+/g, " | ") : "NO PANEL";
  });
  console.log("PANEL:", panel);
  await p.locator('button[title="关闭"]').first().click();
  await p.waitForTimeout(300);

  // 3) 导入反馈：构造一个 JSON 文件并 setInputFiles
  const fs = require("fs");
  const tmp = "D:/cxdownload/javaee课设/scripts/tools/_imp.json";
  fs.writeFileSync(tmp, JSON.stringify({
    script_name: "导入测试剧本", intro_chapter: "intro",
    chapters: [{ code: "intro", name: "开场", order_num: 1, events: [{ type: "narration", text: "x" }, { type: "chapter_end", end_type: "linear" }] }],
  }));
  await p.locator('input[type="file"]').setInputFiles(tmp);
  await p.waitForTimeout(700);
  const imported = await p.evaluate(() => document.body.innerText.includes("导入测试剧本"));
  console.log("IMPORTED_PRESENT:", imported);

  // 4) 成就页点击反馈
  await p.getByRole("button", { name: "成就", exact: true }).click();
  await p.waitForTimeout(600);
  await p.locator(".grid .group").first().click();
  await p.waitForTimeout(400);
  const toast = await p.evaluate(() => {
    const t = document.querySelector(".toast") || document.querySelector("[class*='toast']");
    return t ? t.innerText : "NO TOAST ELEMENT";
  });
  console.log("ACH_TOAST:", toast);

  console.log("ERRORS:", JSON.stringify(errs));
  await b.close();
})();
