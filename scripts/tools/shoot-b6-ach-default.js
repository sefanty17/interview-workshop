const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/screenshots/b6/achievement";
(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.waitForTimeout(1200);
  await p.getByRole("button", { name: "成就", exact: true }).click();
  await p.waitForTimeout(700);
  await p.screenshot({ path: OUT + "/default.jpg", type: "jpeg", quality: 82 });
  const cards = await p.locator(".grid .group").count();
  console.log("ACH_CARDS:", cards);
  await b.close();
})();
