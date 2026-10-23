const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";

(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const pending = new Map();
  const finished = new Set();
  p.on("request", (r) => pending.set(r.url(), Date.now()));
  p.on("requestfinished", (r) => {
    finished.add(r.url());
    pending.delete(r.url());
  });
  p.on("requestfailed", (r) => {
    finished.add(r.url());
    pending.delete(r.url());
  });
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e).slice(0, 200)));
  p.on("console", (m) => {
    if (m.type() === "error") errs.push("console: " + m.text().slice(0, 200));
  });

  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "domcontentloaded" });
  await p.waitForTimeout(4000);

  const still = [...pending.keys()];
  console.log("pending(" + still.length + "):");
  still.slice(0, 15).forEach((u) => console.log("  " + u.slice(0, 140)));
  console.log("errors(" + errs.length + "):");
  errs.slice(0, 8).forEach((e) => console.log("  " + e));
  const txt = await p.evaluate(() => document.body.innerText.replace(/\s+/g, " ").trim());
  console.log("text len=" + txt.length);
  console.log(txt.slice(0, 160));
  await b.close();
})();
