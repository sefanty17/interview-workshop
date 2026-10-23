// E5b 真机验收：编辑器 → 试玩 → 停止。只读 DOM 断言，不看图。
const { chromium } = require("playwright-core");

const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";

(async () => {
  const browser = await chromium.launch({ executablePath: CHROME, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on("pageerror", (e) => errors.push("pageerror: " + e.message));
  page.on("console", (m) => {
    if (m.type() === "error") errors.push("console.error: " + m.text());
  });

  await page.goto("http://127.0.0.1:5173/script-editor", { waitUntil: "networkidle" });
  await page.waitForTimeout(1200);

  // 打开第一个剧本
  const card = page.locator("text=Java 后端").first();
  await card.click().catch(() => {});
  await page.waitForTimeout(1500);

  const out = { steps: [] };
  out.scriptsVisible = await page.locator(".editor-root").count();

  // 点「试玩」
  const previewBtn = page.locator("button", { hasText: "试玩" }).first();
  out.hasPreviewButton = (await previewBtn.count()) > 0;
  if (out.hasPreviewButton) {
    await previewBtn.click();
    await page.waitForTimeout(6000);
    out.badge = await page.locator("text=试玩中").count();
    out.mainBox = await page.locator(".main-box").count();
    out.stopBtn = await page.locator("button", { hasText: "停止试玩" }).count();
    out.label = (await page.locator("text=MAIN =").first().textContent().catch(() => null)) || "";
    out.dialogLines = await page.locator(".main-box").innerHTML().then((h) => h.length);
    await page.screenshot({ path: "screenshots/e5b/01-preview.png" });

    await page.keyboard.press("Escape");
    await page.waitForTimeout(1500);
    out.badgeAfterEsc = await page.locator("text=试玩中").count();
    await page.screenshot({ path: "screenshots/e5b/02-stopped.png" });
  }

  out.errors = errors.slice(0, 10);
  console.log(JSON.stringify(out, null, 2));
  await browser.close();
})();
