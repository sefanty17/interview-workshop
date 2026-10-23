const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/screenshots/b1";
(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e)));
  await p.goto("http://127.0.0.1:5173/", { waitUntil: "networkidle" });
  await p.evaluate(() => localStorage.clear());
  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.waitForTimeout(1500);
  const m = await p.evaluate(() => {
    const nav = document.querySelector("nav");
    const btns = nav ? Array.from(nav.querySelectorAll("button")) : [];
    const ind = document.querySelector(".bg-brand, [class*='absolute'][class*='bottom-\\[3px\\]']");
    const ir = ind ? ind.getBoundingClientRect() : null;
    const first = btns[0] ? btns[0].getBoundingClientRect() : null;
    return {
      tabs: btns.map((x) => x.textContent.trim()),
      btnBox: first && { x: Math.round(first.x), y: Math.round(first.y), w: Math.round(first.width), h: Math.round(first.height) },
      btnRadius: btns[0] ? getComputedStyle(btns[0]).borderRadius : null,
      indBox: ir && { x: Math.round(ir.x), y: Math.round(ir.y), w: Math.round(ir.width), h: Math.round(ir.height) },
      indBg: ind ? getComputedStyle(ind).backgroundColor : null,
      viewportW: window.innerWidth,
    };
  });
  console.log(JSON.stringify(m, null, 1));
  console.log("errors:", errs);
  await p.screenshot({ path: OUT + "/v2-character.png" });
  await p.getByRole("button", { name: "声音", exact: true }).click();
  await p.waitForTimeout(600);
  await p.screenshot({ path: OUT + "/v2-sound.png" });
  await b.close();
})();
