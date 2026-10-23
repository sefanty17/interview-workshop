const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const BASE = "http://127.0.0.1:5173";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1280, height: 820 } });
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e)));
  await p.goto(BASE + "/", { waitUntil: "networkidle" });
  await p.evaluate(() => localStorage.clear());
  await p.goto(BASE + "/interview", { waitUntil: "networkidle" });
  await sleep(1500);
  await p.evaluate(() => {
    const key = "interview-lab-v1";
    const raw = JSON.parse(localStorage.getItem(key));
    raw.settings.questionCount = 3;
    raw.session = { active: true, mode: "script", jobId: "java", personaId: "strict", questionIndex: 0, messages: [], startedAt: Date.now(), elapsed: 0 };
    localStorage.setItem(key, JSON.stringify(raw));
  });
  await p.goto(BASE + "/interview", { waitUntil: "networkidle" });
  await sleep(1500);
  for (let round = 1; round <= 3; round++) {
    for (let i = 0; i < 14; i++) {
      if (await p.locator("#inputMessage").isVisible().catch(() => false)) break;
      if (!(await p.locator(".response-display").count())) break;
      await p.locator(".response-display").click().catch(() => {});
      await sleep(320);
    }
    await p.locator("#inputMessage").fill("索引失效主要有隐式转换、前导模糊、函数包裹列、or 混合条件，用 explain 看 type 与 rows。");
    await p.locator("#inputMessage").press("Enter");
    await sleep(1200);
    console.log("after answer", round, "url:", p.url());
  }
  for (let i = 0; i < 8; i++) {
    if (!(await p.locator(".response-display").count().catch(() => 0))) break;
    await p.locator(".response-display").click().catch(() => {});
    await sleep(400);
  }
  await sleep(1200);
  console.log("final url:", p.url());
  console.log("record visible:", await p.evaluate(() => document.body.innerText.includes("面试报告") || document.body.innerText.includes("维度")));
  console.log("errors:", errs);
  await b.close();
})();
