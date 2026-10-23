const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/screenshots/b6";
const fs = require("fs");
fs.mkdirSync(OUT + "/adventure", { recursive: true });
fs.mkdirSync(OUT + "/achievement", { recursive: true });

(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e)));
  p.on("console", (m) => { if (m.type() === "error") errs.push("CONSOLE: " + m.text()); });

  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.waitForTimeout(1500);

  // 切到「剧本」页
  await p.getByRole("button", { name: "剧本", exact: true }).click();
  await p.waitForTimeout(800);
  await p.screenshot({ path: OUT + "/adventure/default.jpg", type: "jpeg", quality: 82 });

  // 空态：临时把脚本清空（注入 localStorage 后刷新）
  // 这里先截默认（有 4 个剧本卡），再截空态
  await p.evaluate(() => {
    const raw = JSON.parse(localStorage.getItem("interview-lab-v1") || "{}");
    raw.scripts = [];
    localStorage.setItem("interview-lab-v1", JSON.stringify(raw));
  });
  await p.reload({ waitUntil: "networkidle" });
  await p.waitForTimeout(800);
  await p.getByRole("button", { name: "剧本", exact: true }).click();
  await p.waitForTimeout(800);
  await p.screenshot({ path: OUT + "/adventure/empty.jpg", type: "jpeg", quality: 82 });

  // 恢复脚本数据
  await p.evaluate(() => {
    const raw = JSON.parse(localStorage.getItem("interview-lab-v1") || "{}");
    delete raw.scripts;
    localStorage.setItem("interview-lab-v1", JSON.stringify(raw));
  });
  await p.reload({ waitUntil: "networkidle" });
  await p.waitForTimeout(800);
  await p.getByRole("button", { name: "剧本", exact: true }).click();
  await p.waitForTimeout(800);

  // 打开新建剧本弹窗
  await p.getByText("新建剧本", { exact: true }).click();
  await p.waitForTimeout(500);
  await p.screenshot({ path: OUT + "/adventure/new-dialog.jpg", type: "jpeg", quality: 82 });
  // 关闭弹窗
  await p.getByText("取消", { exact: true }).click().catch(() => {});
  await p.waitForTimeout(300);

  // 点开第一张剧本卡 → AdventurePanel 面板
  await p.locator(".grid .group").first().click();
  await p.waitForTimeout(600);
  await p.screenshot({ path: OUT + "/adventure/panel.jpg", type: "jpeg", quality: 82 });

  // 关闭面板（点右上角 X）
  await p.locator('button[title="关闭"]').first().click();
  await p.waitForTimeout(400);

  // 切到「成就」页
  await p.getByRole("button", { name: "成就", exact: true }).click();
  await p.waitForTimeout(800);
  await p.screenshot({ path: OUT + "/achievement/default.jpg", type: "jpeg", quality: 82 });

  // 点一个成就 → 触发 toast 反馈
  await p.locator(".grid .group").first().click();
  await p.waitForTimeout(400);
  await p.screenshot({ path: OUT + "/achievement/click-feedback.jpg", type: "jpeg", quality: 82 });

  console.log("errors", JSON.stringify(errs, null, 1));
  await b.close();
})();
