const { chromium } = require("playwright-core");
const fs = require("fs");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/screenshots/b8/review";
fs.mkdirSync(OUT, { recursive: true });

async function shotPair(b, type) {
  const p = await b.newPage({ viewport: { width: 1280, height: 820 } });
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e)));
  await p.goto("http://127.0.0.1:5173/", { waitUntil: "networkidle" });
  await p.evaluate((t) => {
    const raw = localStorage.getItem("interview-lab-v1");
    const d = raw ? JSON.parse(raw) : {};
    d.settings = { ...(d.settings || {}), particleType: t };
    localStorage.setItem("interview-lab-v1", JSON.stringify(d));
  }, type);
  await p.goto("http://127.0.0.1:5173/interview", { waitUntil: "networkidle" });
  await p.waitForTimeout(1800);
  const a = await p.screenshot();
  await p.waitForTimeout(900);
  const c = await p.screenshot();
  fs.writeFileSync(`${OUT}/${type}-a.png`, a);
  fs.writeFileSync(`${OUT}/${type}-b.png`, c);
  // 逐字节相同 = 静态；不同 = 在动
  const diff = a.length !== c.length ? 1 : (a.equals(c) ? 0 : 1);
  const canvasCount = await p.evaluate(() => document.querySelectorAll("canvas").length);
  console.log(`${type}: changed=${diff} canvas=${canvasCount} errors=${errs.length}`);
  await p.close();
}

(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  for (const t of ["rain", "sakura", "starfield", "snow", "fireflies"]) {
    await shotPair(b, t);
  }
  await b.close();
})();
