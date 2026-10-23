const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/screenshots/b7";
const fs = require("fs");
fs.mkdirSync(OUT, { recursive: true });

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e)));

  await p.goto("http://127.0.0.1:5173/interview", { waitUntil: "networkidle" });
  await p.waitForTimeout(3500);

  // 1. 房间默认态（带工具条 / 顶部状态 / 左侧自由对话标签 / 右下角迷你频谱）
  await p.screenshot({ path: OUT + "/room-default.jpg", type: "jpeg", quality: 82 });

  // 2. 面试印象面板展开（点击含爱心液体的触发按钮）
  await p.evaluate(() => {
    const svg = document.querySelector(".heart-liquid");
    const btn = svg && svg.closest("button");
    if (btn) btn.click();
  });
  await p.waitForTimeout(900);
  await p.screenshot({ path: OUT + "/affection-panel.jpg", type: "jpeg", quality: 82 });

  // 3. 雷达（面板内左侧好感雷达元素特写）
  const radar = p.locator("svg[viewBox='0 0 400 360']").first();
  await radar.screenshot({ path: OUT + "/affection-radar.jpg", type: "jpeg", quality: 82 });

  // 4. 爱心液体（工具条触发按钮区域特写）
  const tools = p.locator(".free-mode-tools").first();
  const box = await tools.boundingBox();
  if (box) {
    await p.screenshot({
      path: OUT + "/heart-liquid.jpg",
      type: "jpeg",
      quality: 90,
      clip: { x: Math.max(0, box.x - 8), y: Math.max(0, box.y - 8), width: box.width + 16, height: box.height + 16 },
    });
  }

  // 关闭面板
  await p.evaluate(() => {
    const btns = Array.from(document.querySelectorAll("button"));
    const close = btns.find((x) => x.querySelector("svg path[d^='M6 18L18 6']"));
    if (close) close.click();
  });
  await p.waitForTimeout(500);

  // 5. 章节名
  await p.evaluate(() => ((window).__extraDemo.showChapter = true));
  await p.waitForTimeout(900);
  await p.screenshot({ path: OUT + "/chapter-name.jpg", type: "jpeg", quality: 82 });
  await p.evaluate(() => ((window).__extraDemo.showChapter = false));
  await p.waitForTimeout(700);

  // 6. Galgame 选项展示
  await p.evaluate(() => ((window).__extraDemo.showChoices = true));
  await p.waitForTimeout(1100);
  await p.screenshot({ path: OUT + "/game-choices.jpg", type: "jpeg", quality: 82 });
  await p.evaluate(() => ((window).__extraDemo.showChoices = false));
  await p.waitForTimeout(500);

  // 7. 音效面板（左下角「声效」按钮）
  await p.evaluate(() => {
    const h3 = Array.from(document.querySelectorAll("h3")).find(
      (x) => x.textContent.trim() === "声效",
    );
    const btn = h3 && h3.closest("button");
    if (btn) btn.click();
  });
  await p.waitForTimeout(900);
  await p.screenshot({ path: OUT + "/sound-panel.jpg", type: "jpeg", quality: 82 });

  // 关闭声效面板（点外部）
  await p.mouse.click(720, 200);
  await p.waitForTimeout(500);

  // 8. 频谱展开面板（右下角迷你频谱按钮）
  await p.evaluate(() => {
    const canvas = document.querySelectorAll("canvas");
    const mini = canvas.length ? canvas[canvas.length - 1] : null;
    const btn = mini && mini.closest("button");
    if (btn) btn.click();
  });
  await p.waitForTimeout(800);
  await p.screenshot({ path: OUT + "/spectrum.jpg", type: "jpeg", quality: 82 });

  console.log("errors", errs);
  await b.close();
})();
