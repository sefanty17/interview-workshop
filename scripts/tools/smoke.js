const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const ROUTES = ["/", "/settings", "/records", "/report/r-1024", "/script-editor", "/workshop", "/interview"];

(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e).slice(0, 200)));
  p.on("console", (m) => {
    if (m.type() === "error") errs.push("console: " + m.text().slice(0, 200));
  });

  const go = async (r) => {
    await p.goto("http://127.0.0.1:5173" + r, { waitUntil: "domcontentloaded" });
    await p.waitForTimeout(1600);
  };

  await go("/");
  await p.evaluate(() => localStorage.clear());

  for (const r of ROUTES) {
    errs.length = 0;
    await go(r);
    const txt = (await p.evaluate(() => document.body.innerText.replace(/\s+/g, " ").trim())).slice(0, 60);
    console.log([r.padEnd(16), "err=" + errs.length, txt].join(" | "));
    if (errs.length) errs.slice(0, 2).forEach((e) => console.log("     " + e));
  }

  await go("/settings");
  const tabs = ["角色", "剧本", "通用", "背景", "声音", "对话历史", "成就", "存档", "高级设置", "日志"];
  for (const t of tabs) {
    errs.length = 0;
    try {
      await p.getByRole("button", { name: t, exact: true }).click();
      await p.waitForTimeout(900);
      const h = (await p.evaluate(() => document.body.innerText.replace(/\s+/g, " ").trim())).slice(0, 45);
      console.log(["tab " + t.padEnd(5), "err=" + errs.length, h].join(" | "));
      if (errs.length) errs.slice(0, 2).forEach((e) => console.log("     " + e));
    } catch (e) {
      console.log("tab " + t + " | CLICK_FAIL " + String(e).slice(0, 70));
    }
  }
  await b.close();
})();
