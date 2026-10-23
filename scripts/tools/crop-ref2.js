const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1080, height: 608 }, deviceScaleFactor: 3 });
  await p.goto("file:///D:/cxdownload/javaee课设/docs/design/screenshots/lingchat/settings-character-selected.png");
  await p.waitForTimeout(500);
  await p.screenshot({ path: "D:/cxdownload/javaee课设/screenshots/ref-selected-zoom.png", clip: { x: 340, y: 240, width: 220, height: 55 } });
  await b.close();
})();
