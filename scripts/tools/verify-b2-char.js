const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/screenshots/b2/character";
(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1366, height: 800 } });
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e)));
  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.waitForTimeout(1200);
  const m = await p.evaluate(() => {
    const card = document.querySelector(".group");
    const img = card?.querySelector("img");
    const wrap = img?.parentElement;
    const btns = card ? Array.from(card.querySelectorAll("button")).map((x) => x.textContent.trim()).filter(Boolean) : [];
    const cs = card ? getComputedStyle(card) : null;
    const iw = wrap ? getComputedStyle(wrap) : null;
    return {
      cardBox: card && { w: Math.round(card.getBoundingClientRect().width), h: Math.round(card.getBoundingClientRect().height) },
      cardRadius: cs?.borderRadius,
      cardBg: cs?.backgroundColor,
      cardBorder: cs?.borderColor,
      avatarBox: wrap && { w: Math.round(wrap.getBoundingClientRect().width), h: Math.round(wrap.getBoundingClientRect().height) },
      avatarRadius: iw?.borderRadius,
      avatarBorder: iw?.borderColor,
      btns,
      text: card?.innerText.slice(0, 120).replace(/\n+/g, " | "),
    };
  });
  console.log(JSON.stringify(m, null, 1));
  console.log("errors:", errs);
  await p.screenshot({ path: OUT + "/x1366-default.png" });
  await b.close();
})();
