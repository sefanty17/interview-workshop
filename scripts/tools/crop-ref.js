const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const SRC = "D:/cxdownload/javaee课设/docs/design/screenshots/lingchat/settings-character.png";
(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1080, height: 608 }, deviceScaleFactor: 3 });
  await p.goto("file:///" + SRC);
  await p.waitForTimeout(600);
  await p.screenshot({ path: "D:/cxdownload/javaee课设/screenshots/ref-card-zoom.png", clip: { x: 176, y: 130, width: 400, height: 180 } });
  await p.screenshot({ path: "D:/cxdownload/javaee课设/screenshots/ref-buttons-zoom.png", clip: { x: 355, y: 248, width: 180, height: 45 } });
  await b.close();
})();
