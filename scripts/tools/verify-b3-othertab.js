const { chromium } = require("playwright-core");
const OUT = "D:/cxdownload/javaee课设/screenshots/b3/character";
(async () => {
  const b = await chromium.launch({
    executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe",
    headless: true,
  });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e)));
  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.waitForTimeout(800);
  // 顺带确认 token 重置没有把别的设置页搞坏
  await p.getByRole("button", { name: "通用" }).click().catch(() => {});
  await p.waitForTimeout(700);
  await p.screenshot({ path: OUT + "/1440-b3-othertab-text.png" });
  console.log("errors:", errs);
  await b.close();
})();
