const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/screenshots/b3/character";
const fs = require("fs");
if (!fs.existsSync(OUT)) fs.mkdirSync(OUT, { recursive: true });

(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e)));
  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.waitForTimeout(1000);

  // 已选中态（默认 store 里的 persona）
  await p.screenshot({ path: OUT + "/1440-b3-selected.png" });

  // hover + 已弃态：第 3 张卡（钦灵，非选中）点「加入」
  const cards = p.locator("div.group");
  const card3 = cards.nth(2);
  await card3.getByRole("button", { name: "加入" }).click();
  await p.waitForTimeout(400);
  const joined = await card3.evaluate((el) =>
    Array.from(el.querySelectorAll("button")).map((x) => x.textContent.trim()),
  );
  const joinedBtn = await card3
    .locator("button", { hasText: "已弃" })
    .evaluate((x) => {
      const cs = getComputedStyle(x);
      const r = x.getBoundingClientRect();
      return { h: Math.round(r.height), radius: cs.borderRadius, color: cs.color, bg: cs.backgroundColor, fs: cs.fontSize };
    })
    .catch(() => null);

  const selBtn = await p
    .locator("button", { hasText: "已选中" })
    .evaluate((x) => {
      const cs = getComputedStyle(x);
      const r = x.getBoundingClientRect();
      return { h: Math.round(r.height), radius: cs.borderRadius, color: cs.color, border: cs.borderColor, bg: cs.backgroundColor, fs: cs.fontSize };
    })
    .catch(() => null);

  await card3.hover();
  await p.waitForTimeout(400);
  await p.screenshot({ path: OUT + "/1440-b3-joined-hover.png" });

  console.log(JSON.stringify({ joined, joinedBtn, selBtn }, null, 1));
  console.log("errors:", errs);
  await b.close();
})();
