const { chromium } = require("playwright-core");
const path = require("path");
const fs = require("fs");

const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/docs/design/screenshots/tabs";

const TABS = [
  ["persona", "面试官"],
  ["adventure", "剧本冒险"],
  ["text", "通用"],
  ["background", "背景"],
  ["sound", "声音"],
  ["achievement", "成就"],
  ["save", "存档"],
  ["advance", "高级设置"],
  ["log", "日志"],
  ["plugins", "插件"],
  ["rules", "面试规则"],
];

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch({ executablePath: CHROME, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await page.waitForTimeout(800);

  const report = [];
  for (const [id, label] of TABS) {
    await page.getByRole("button", { name: label, exact: true }).click();
    await page.waitForTimeout(550);

    const m = await page.evaluate(() => {
      const scroller = document.querySelector(".absolute.inset-0.overflow-y-auto");
      const sections = Array.from(document.querySelectorAll("section"));
      const visible = sections.filter((s) => s.getBoundingClientRect().height > 0);
      const widths = visible.map((s) => Math.round(s.getBoundingClientRect().width));
      const rowTops = visible.map((s) => Math.round(s.getBoundingClientRect().top));
      const placeholders = document.body.innerText.includes("待迁移");
      const sliders = document.querySelectorAll("input[type=range]").length;
      const toggles = document.querySelectorAll("[role=switch]").length;
      const selects = document.querySelectorAll("select").length;
      return {
        sections: visible.length,
        widths,
        rowTops,
        scrollH: scroller ? scroller.scrollHeight : 0,
        placeholders,
        sliders,
        toggles,
        selects,
        textLen: (scroller?.innerText || "").length,
      };
    });

    await page.screenshot({ path: path.join(OUT, `${id}.png`) });
    report.push({ id, label, ...m });
  }

  await browser.close();

  console.log("Tab          区块  滑块 开关 下拉  内容字数  滚动高  半宽并排");
  for (const r of report) {
    // 半宽并排检测：宽度近似相等的相邻 section 是否 top 相同
    const half = r.widths.filter((w) => w < 600).length;
    const paired = new Set(r.rowTops).size < r.rowTops.length ? "有" : (half ? "?" : "-");
    console.log(
      `${r.label.padEnd(10)} ${String(r.sections).padStart(4)} ${String(r.sliders).padStart(5)} ${String(r.toggles).padStart(4)} ${String(r.selects).padStart(4)} ${String(r.textLen).padStart(8)} ${String(r.scrollH).padStart(7)}  ${paired}${r.placeholders ? "  ⚠待迁移" : ""}`,
    );
  }
})();
