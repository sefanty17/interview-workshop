import { chromium } from "file:///C:/Users/候选人/.workbuddy/binaries/node/workspace/node_modules/playwright/index.mjs";
import path from "node:path";

const OUT = "D:/cxdownload/javaee课设/screenshots/b6/memory";
const browser = await chromium.launch({
  executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe",
  headless: true,
  args: ["--no-proxy-server"],
});
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on("console", (m) => {
  if (m.type() === "error") console.log("CONSOLE ERROR:", m.text());
});
page.on("pageerror", (e) => console.log("PAGE ERROR:", e.message));

await page.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
await page.waitForTimeout(800);

// 进入「高级设置」
await page.locator("text=高级设置").first().click();
await page.waitForTimeout(600);
await page.locator("text=永久记忆调试").first().click();
await page.waitForTimeout(800);

const shot = async (name) => {
  await page.screenshot({ path: path.join(OUT, `${name}.jpg`), type: "jpeg", quality: 82 });
  console.log("shot", name);
};

await shot("01-default");

// 搜索
const input = page.getByPlaceholder("搜索记忆摘要或原文关键词");
const scroller = page.locator("section.min-h-0.flex-1.overflow-y-auto").last();
await input.fill("索引");
await page.waitForTimeout(500);
await page.getByText("记忆条目", { exact: true }).scrollIntoViewIfNeeded();
await page.waitForTimeout(300);
await scroller.evaluate((el) => {
  el.scrollTop -= 120;
});
await page.waitForTimeout(400);
await shot("02-search");

// 清空搜索 + 展开第一条
await input.fill("");
await page.waitForTimeout(300);
await page.locator("ul li button").first().click();
await page.waitForTimeout(500);
await shot("03-expanded");

// 滚到底（右栏）
await scroller.evaluate((el) => {
  el.scrollTop = el.scrollHeight;
});
await page.waitForTimeout(600);
await shot("04-bottom");

// 交互自检：删除弹窗
await scroller.evaluate((el) => {
  el.scrollTop = 0;
});
await page.waitForTimeout(300);
const delBtn = page.locator('button[aria-label^="删除记忆条目"]').first();
await delBtn.click();
await page.waitForTimeout(500);
await shot("05-confirm-delete");
const dialogText = await page.locator("body").innerText();
console.log("has confirm dialog:", dialogText.includes("确定删除这条记忆吗"));

// 确认删除后条目数应下降
await page.getByRole("button", { name: "确定" }).click();
await page.waitForTimeout(600);
const bodyAfter = await page.locator("body").innerText();
const countLine = bodyAfter.match(/记忆条目数\s*(\d+)\s*条/);
console.log("条目数 after delete:", countLine ? countLine[1] : "未读到");
await shot("06-after-delete");

// 模拟触发：开两个开关 → 手动触发压缩应改为本地演练（压缩次数 +1）
await page.getByRole("switch", { name: "显示压缩过程日志" }).click();
await page.getByRole("switch", { name: "模拟触发" }).click();
await page.waitForTimeout(300);
await page.getByRole("button", { name: "手动触发压缩" }).click();
await page.waitForTimeout(1200);
const bodySim = await page.locator("body").innerText();
const compressLine = bodySim.match(/压缩次数\s*(\d+)\s*次/);
console.log("压缩次数 after simulate:", compressLine ? compressLine[1] : "未读到");
console.log("日志面板出现:", bodySim.includes("压缩过程日志"));
await shot("07-simulate");

await browser.close();
