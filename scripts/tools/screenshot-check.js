const { chromium } = require("playwright");

(async () => {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

  // 设置页：切占位 Tab 验证指示条
  await page.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await page.waitForTimeout(1200);
  await page.locator("nav button", { hasText: "面试历史" }).click();
  await page.waitForTimeout(600);
  await page.screenshot({ path: "D:/cxdownload/javaee课设/docs/design/screenshots/settings-history-v2.png" });

  // 量化复查：立绘 mousemove 后是否仍在屏幕内 + 菜单字号/字体
  await page.goto("http://127.0.0.1:5173/", { waitUntil: "networkidle" });
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(800);
  await page.mouse.move(1000, 200);
  await page.waitForTimeout(300);
  const check = await page.evaluate(() => {
    const img = document.querySelector("img");
    const rect = img.getBoundingClientRect();
    const item = document.querySelector(".start-item");
    const cs = getComputedStyle(item);
    return {
      imgRect: { x: Math.round(rect.x), y: Math.round(rect.y), w: Math.round(rect.width) },
      fontSize: cs.fontSize,
      fontFamily: cs.fontFamily.split(",")[0],
      color: cs.color,
    };
  });
  console.log(JSON.stringify(check, null, 2));

  await browser.close();
})();
