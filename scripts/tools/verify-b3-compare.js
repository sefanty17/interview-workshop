const fs = require("fs");
const { chromium } = require("playwright-core");
const OUT = "D:/cxdownload/javaee课设/screenshots/b3/character/1440-b3-compare-card1.png";
const REF = "D:/cxdownload/javaee课设/docs/design/screenshots/lingchat/settings-character.png";
const OUR = "D:/cxdownload/javaee课设/screenshots/b3/character/1440-b3-default.png";
const b64 = (f) => "data:image/png;base64," + fs.readFileSync(f).toString("base64");

(async () => {
  const b = await chromium.launch({
    executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe",
    headless: true,
  });
  const p = await b.newPage({ viewport: { width: 760, height: 700 } });
  // 参考图 1871x1038（=1080 展示图 ×1.732），卡片1 实际约 (333,229)-(927,473)
  // 我们的 1440x900，卡片1 约 (258,166)-(710,360)
  const html = `<!doctype html><html><body style="margin:0;background:#111;font:13px sans-serif;color:#79d9ff">
  <div style="padding:6px 10px">参考 settings-character.png · 卡片1（放大 ~1.18x）</div>
  <div style="position:relative;width:720px;height:300px;overflow:hidden">
    <img src="${b64(REF)}" style="position:absolute;left:-385px;top:-264px;transform-origin:0 0;transform:scale(1.18)">
  </div>
  <div style="padding:6px 10px">本次实现 1440x900 · 卡片1（放大 ~1.55x）</div>
  <div style="position:relative;width:720px;height:310px;overflow:hidden">
    <img src="${b64(OUR)}" style="position:absolute;left:-395px;top:-250px;transform-origin:0 0;transform:scale(1.55)">
  </div></body></html>`;
  await p.setContent(html, { waitUntil: "networkidle" });
  await p.waitForTimeout(500);
  await p.screenshot({ path: OUT, clip: { x: 0, y: 0, width: 720, height: 664 } });
  await b.close();
  console.log("saved", OUT);
})();
