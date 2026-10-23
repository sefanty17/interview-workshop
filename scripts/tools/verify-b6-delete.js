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
  await p.waitForTimeout(500);

  // 删除第一个剧本：点卡片→面板→删除→确认
  const before = await p.locator(".grid .group").count();
  await p.locator(".grid .group").first().click();
  await p.waitForTimeout(400);
  await p.getByText("删除", { exact: true }).first().click();
  await p.waitForTimeout(400);
  await p.getByRole("button", { name: "确定", exact: true }).first().click();
  await p.waitForTimeout(600);
  const after = await p.locator(".grid .group").count();
  console.log("CARD_BEFORE:", before, "CARD_AFTER:", after, "DELETED_OK:", after === before - 1);
  console.log("ERRORS:", JSON.stringify(errs));
  await b.close();
})();
