const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/screenshots/review";
const fs = require("fs");
fs.mkdirSync(OUT, { recursive: true });
(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e)));
  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.waitForTimeout(1500);
  await p.screenshot({ path: OUT + "/char.jpg", type: "jpeg", quality: 82 });

  const card = await p.evaluate(() => {
    const c = document.querySelector(".group");
    const img = c?.querySelector("img");
    const wrap = img?.parentElement;
    const btns = c ? Array.from(c.querySelectorAll("button")).map((x) => ({
      t: x.textContent.trim(),
      h: Math.round(x.getBoundingClientRect().height),
      r: getComputedStyle(x).borderRadius,
      bg: getComputedStyle(x).backgroundColor,
      color: getComputedStyle(x).color,
    })).filter((x) => x.t) : [];
    const title = c?.querySelector(".tracking-widest");
    return {
      cardH: c && Math.round(c.getBoundingClientRect().height),
      avatar: wrap && { w: Math.round(wrap.getBoundingClientRect().width), r: getComputedStyle(wrap).borderRadius },
      titleFont: title && getComputedStyle(title).fontSize,
      titleColor: title && getComputedStyle(title).color,
      btns,
    };
  });
  console.log("CARD", JSON.stringify(card, null, 1));

  await p.goto("http://127.0.0.1:5173/", { waitUntil: "networkidle" });
  await p.waitForTimeout(800);
  await p.getByRole("button", { name: "开始面试", exact: true }).click();
  await p.waitForTimeout(500);
  await p.getByRole("button", { name: "自由面试", exact: true }).click();
  await p.waitForTimeout(2500);
  await p.screenshot({ path: OUT + "/room.jpg", type: "jpeg", quality: 82 });
  const room = await p.evaluate(() => ({
    text: document.body.innerText.replace(/\n+/g, " | ").slice(0, 200),
    hasTextarea: !!document.querySelector("textarea"),
    dialogW: (() => { const d = document.querySelector(".game-dialog > div"); return d ? Math.round(d.getBoundingClientRect().width) : null; })(),
  }));
  console.log("ROOM", JSON.stringify(room, null, 1));
  console.log("errors", errs);
  await b.close();
})();
