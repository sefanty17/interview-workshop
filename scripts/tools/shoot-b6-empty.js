const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/screenshots/b6/adventure";
(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.waitForTimeout(1200);
  await p.getByRole("button", { name: "剧本", exact: true }).click();
  await p.waitForTimeout(600);

  // 删除全部 4 个剧本（点卡片→面板→删除→确认），直至列表为空
  for (let i = 0; i < 6; i++) {
    const card = p.locator(".grid .group").first();
    if (!(await card.count())) break;
    await card.click();
    await p.waitForTimeout(400);
    await p.getByText("删除", { exact: true }).first().click();
    await p.waitForTimeout(300);
    // confirm 弹窗：点「确定」（对话框在 Teleport 到 body，用 role 定位）
    await p.getByRole("button", { name: "确定", exact: true }).first().click();
    await p.waitForTimeout(500);
  }
  const emptyShown = await p.evaluate(() => document.body.innerText.includes("暂无独立剧本"));
  console.log("EMPTY_SHOWN:", emptyShown);
  await p.screenshot({ path: OUT + "/empty.jpg", type: "jpeg", quality: 82 });
  await b.close();
})();
