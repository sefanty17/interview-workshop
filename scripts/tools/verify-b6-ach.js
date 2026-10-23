const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.waitForTimeout(1200);
  await p.getByRole("button", { name: "成就", exact: true }).click();
  await p.waitForTimeout(600);
  const cards = await p.locator(".grid .group").count();
  const hidden = await p.evaluate(() => document.body.innerText.includes("???"));
  const rare = await p.evaluate(() => document.body.innerText.includes("稀有"));
  const normal = await p.evaluate(() => document.body.innerText.includes("普通"));
  console.log("ACH_CARDS:", cards, "HAS_HIDDEN_??? :", hidden, "HAS_稀有:", rare, "HAS_普通:", normal);
  await b.close();
})();
