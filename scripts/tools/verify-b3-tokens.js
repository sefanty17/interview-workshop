const { chromium } = require("playwright-core");
(async () => {
  const b = await chromium.launch({
    executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe",
    headless: true,
  });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.waitForTimeout(800);
  const r = await p.evaluate(() => {
    const panel = document.querySelector(".settings-panel");
    const root = getComputedStyle(document.documentElement);
    const pcs = getComputedStyle(panel);
    const card = document.querySelector("div.group");
    const box = card.getBoundingClientRect();
    const imgWrap = card.querySelector("img").parentElement;
    const ir = imgWrap.getBoundingClientRect();
    const rad = getComputedStyle(imgWrap).borderRadius;
    return {
      panelHasClass: panel.classList.contains("lc-reset-radius"),
      rootRadiusLg: root.getPropertyValue("--radius-lg"),
      panelRadiusLg: pcs.getPropertyValue("--radius-lg"),
      panelRadiusSm: pcs.getPropertyValue("--radius-sm"),
      cardH: Math.round(box.height),
      avatarBox: { w: Math.round(ir.width), h: Math.round(ir.height) },
      avatarRadiusComputed: rad,
      avatarRadiusClippedPct:
        Math.round(
          (Math.min(parseFloat(rad), Math.min(ir.width, ir.height) / 2) / (Math.min(ir.width, ir.height) / 2)) *
            1000,
        ) / 10 + "%",
    };
  });
  console.log(JSON.stringify(r, null, 1));
  await b.close();
})();
