const { chromium } = require("playwright-core");
const path = require("path");
const fs = require("fs");

const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/docs/design/screenshots/pages";

const PAGES = [
  ["records", "/records"],
  ["report", "/report/r-1024"],
  ["script-editor", "/script-editor"],
  ["workshop", "/workshop"],
];

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch({ executablePath: CHROME, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

  console.log("页面              区块  内容字数  滚动高  后端残留");
  for (const [name, url] of PAGES) {
    await page.goto("http://127.0.0.1:5173" + url, { waitUntil: "networkidle" });
    await page.waitForTimeout(700);

    const m = await page.evaluate(() => {
      const sections = Array.from(document.querySelectorAll("section"));
      const visible = sections.filter((s) => s.getBoundingClientRect().height > 0);
      const scroller = Array.from(document.querySelectorAll("div")).find(
        (d) => d.scrollHeight > d.clientHeight + 20 && d.className.includes("overflow-y-auto"),
      );
      return {
        sections: visible.length,
        textLen: document.body.innerText.length,
        scrollH: scroller ? scroller.scrollHeight : 0,
        legacy: document.querySelector(".console, .sidebar, .console-top, .panel") !== null,
        hasShell: document.querySelector(".console") !== null,
      };
    });

    await page.screenshot({ path: path.join(OUT, `${name}.png`) });
    console.log(
      `${name.padEnd(14)} ${String(m.sections).padStart(4)} ${String(m.textLen).padStart(8)} ${String(m.scrollH).padStart(7)}  ${m.hasShell || m.legacy ? "⚠ 有" : "无"}`,
    );
  }

  // 首页 → 二级菜单
  await page.goto("http://127.0.0.1:5173/", { waitUntil: "networkidle" });
  await page.waitForTimeout(900);
  await page.screenshot({ path: path.join(OUT, "home-main.png") });

  await page.getByRole("button", { name: "开始面试", exact: true }).click();
  await page.waitForTimeout(800);
  await page.screenshot({ path: path.join(OUT, "home-submenu.png") });

  const sub = await page.evaluate(() => {
    const items = Array.from(document.querySelectorAll("button"))
      .map((b) => b.innerText.trim())
      .filter((t) => t && t.length < 12);
    const disabled = Array.from(document.querySelectorAll("button"))
      .filter((b) => getComputedStyle(b).opacity === "0.5")
      .map((b) => b.innerText.trim());
    return { items, disabled };
  });
  console.log("\n二级菜单项:", sub.items.join(" / "));
  console.log("禁用项:", sub.disabled.join(" / ") || "无");

  await browser.close();
})();
