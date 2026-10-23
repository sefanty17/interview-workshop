const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const errs = [];
  p.on("pageerror", (e) => errs.push("PAGEERR: " + String(e)));
  p.on("console", (m) => { if (m.type() === "error") errs.push("CONSOLE: " + m.text()); });
  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.waitForTimeout(1500);
  await p.getByRole("button", { name: "剧本", exact: true }).click();
  await p.waitForTimeout(800);
  const txt = await p.evaluate(() => document.body.innerText.replace(/\n+/g, " | ").slice(0, 600));
  const hasNew = await p.getByText("新建剧本", { exact: true }).count();
  console.log("BODY:", txt);
  console.log("新建剧本 count:", hasNew);
  console.log("errors", JSON.stringify(errs, null, 1));
  await b.close();
})();
