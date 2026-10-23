const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/docs/design/screenshots/pages";
(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1280, height: 820 } });
  await p.goto("http://127.0.0.1:5173/", { waitUntil: "networkidle" });
  await p.evaluate(() => localStorage.clear());
  await p.reload({ waitUntil: "networkidle" });
  await p.getByRole("button", { name: "开始面试", exact: true }).click();
  await p.waitForTimeout(600);
  await p.getByRole("button", { name: "自由面试", exact: true }).click();
  await p.waitForTimeout(2200);

  const top = await p.evaluate(() => {
    const lines = document.body.innerText.split("\n").filter(Boolean).slice(0, 6);
    const bar = Array.from(document.querySelectorAll("div")).find(
      (d) => d.className.includes("bg-[#79d9ff]") && d.className.includes("transition-all"),
    );
    const mode = JSON.parse(localStorage.getItem("interview-lab-v1") || "{}").session?.mode;
    return { lines, hasBar: !!bar, mode };
  });
  console.log("进入自由面试:", JSON.stringify(top));
  await p.screenshot({ path: OUT + "/free-mode-top.png" });

  // 连答 7 轮，确认不会自动结束
  for (let i = 0; i < 7; i++) {
    await p.locator("textarea").fill("我用索引覆盖的方式优化慢查询，先看执行计划确认是否全表扫描，再考虑加联合索引。");
    await p.getByRole("button", { name: "▼", exact: true }).click();
    await p.waitForTimeout(1700);
  }
  const after = await p.evaluate(() => {
    const lines = document.body.innerText.split("\n").filter(Boolean).slice(0, 6);
    return { lines, ended: document.body.innerText.includes("本场面试结束") };
  });
  console.log("答完 7 轮:", JSON.stringify(after));
  await p.screenshot({ path: OUT + "/free-mode-after7.png" });
  await b.close();
})();
