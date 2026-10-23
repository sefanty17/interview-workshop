const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";

(async () => {
  const browser = await chromium.launch({ executablePath: CHROME, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await page.getByRole("button", { name: "通用", exact: true }).click();
  await page.waitForTimeout(600);

  const info = await page.evaluate(() => {
    const r = document.querySelector("input[type=range]");
    const box = r.getBoundingClientRect();
    const cs = getComputedStyle(r);
    // 找父容器
    const wrap = r.parentElement;
    const wbox = wrap.getBoundingClientRect();
    return {
      rangeRect: { x: Math.round(box.x), y: Math.round(box.y), w: Math.round(box.width), h: Math.round(box.height) },
      computedWidth: cs.width,
      computedHeight: cs.height,
      classes: r.className,
      wrapperRect: { x: Math.round(wbox.x), w: Math.round(wbox.width) },
      wrapperClass: wrap.className,
      siblings: Array.from(wrap.children).map((c) => ({
        tag: c.tagName,
        x: Math.round(c.getBoundingClientRect().x),
        w: Math.round(c.getBoundingClientRect().width),
        text: (c.textContent || "").trim().slice(0, 12),
      })),
    };
  });
  console.log(JSON.stringify(info, null, 2));
  await browser.close();
})();
