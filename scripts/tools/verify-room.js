const { chromium } = require("playwright-core");
const path = require("path");
const fs = require("fs");

const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/docs/design/screenshots/pages";
const BASE = "http://127.0.0.1:5173";

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch({ executablePath: CHROME, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

  // ---------- 1. 面试房间 ----------
  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await page.evaluate(() => localStorage.clear());
  await page.goto(BASE + "/interview", { waitUntil: "networkidle" });
  await page.waitForTimeout(2200);

  const room = await page.evaluate(() => {
    const img = document.querySelector("img[src*='/avatars/']");
    const r = img ? img.getBoundingClientRect() : null;
    const cs = img ? getComputedStyle(img) : null;
    const body = document.body.innerText;
    const progress = Array.from(document.querySelectorAll("div")).find(
      (d) => d.className.includes("bg-[#79d9ff]") && d.className.includes("transition-all"),
    );
    return {
      avatarSrc: img ? img.getAttribute("src") : null,
      avatarLoaded: img ? img.complete && img.naturalWidth > 0 : false,
      avatarNatural: img ? `${img.naturalWidth}x${img.naturalHeight}` : null,
      centerX: r ? Math.round(r.left + r.width / 2) : null,
      centerY: r ? Math.round(r.top + r.height / 2) : null,
      viewport: `${window.innerWidth}x${window.innerHeight}`,
      transform: cs ? cs.transform : null,
      hasProgressText: /题目\s*\d+\s*\/\s*\d+/.test(body),
      hasTimer: /\d{2}:\d{2}/.test(body),
      progressWidth: progress ? progress.style.width : null,
      bubbleBg: (() => {
        const b = Array.from(document.querySelectorAll("div")).find(
          (d) => d.className.includes("backdrop-blur-2xl") && d.className.includes("rounded-2xl"),
        );
        return b ? getComputedStyle(b).backgroundColor : null;
      })(),
      textHead: body.slice(0, 160).replace(/\n+/g, " | "),
    };
  });
  await page.screenshot({ path: path.join(OUT, "room.png") });
  console.log("=== 面试房间 ===");
  console.log(JSON.stringify(room, null, 2));

  // 发一条回答，看追问 + 情绪/立绘是否仍稳定
  const ta = page.locator("textarea");
  await ta.fill("我会用索引覆盖的方式优化这条慢查询，先看执行计划确认是否走全表扫。");
  await page.getByRole("button", { name: "▼", exact: true }).click();
  await page.waitForTimeout(4500);
  const after = await page.evaluate(() => {
    const img = document.querySelector("img[src*='/avatars/']");
    const r = img ? img.getBoundingClientRect() : null;
    return {
      centerX: r ? Math.round(r.left + r.width / 2) : null,
      msgLen: document.querySelectorAll("textarea").length,
      bodyLen: document.body.innerText.length,
      progressWidth: (() => {
        const p = Array.from(document.querySelectorAll("div")).find(
          (d) => d.className.includes("bg-[#79d9ff]") && d.className.includes("transition-all"),
        );
        return p ? p.style.width : null;
      })(),
    };
  });
  await page.screenshot({ path: path.join(OUT, "room-after-answer.png") });
  console.log("发答后:", JSON.stringify(after));

  // 结束面试弹窗（自定义 AppDialog，非原生 confirm）
  await page.getByRole("button", { name: "结束面试", exact: true }).first().click();
  await page.waitForTimeout(500);
  const dlg = await page.evaluate(() => {
    const t = document.body.innerText;
    return {
      hasCustom: /结束面试[\s\S]{0,120}结束本场面试并生成报告/.test(t),
      hasCancel: /取消/.test(t),
      hasConfirm: /确定|确认/.test(t),
    };
  });
  await page.screenshot({ path: path.join(OUT, "room-end-dialog.png") });
  console.log("结束弹窗:", JSON.stringify(dlg));

  // ---------- 2. 设置-面试官 Tab ----------
  await page.keyboard.press("Escape").catch(() => {});
  await page.goto(BASE + "/settings", { waitUntil: "networkidle" });
  await page.waitForTimeout(900);
  await page.screenshot({ path: path.join(OUT, "settings-persona.png") });

  const personaCards = await page.evaluate(() => {
    const imgs = Array.from(document.querySelectorAll("img")).map((i) => i.getAttribute("src"));
    return imgs.filter((s) => s && s.includes("/avatars/"));
  });
  console.log("\n=== 设置-面试官 ===");
  console.log("角色立绘:", personaCards.join(" , ") || "无");

  // 打开详情弹窗
  const detailBtn = page.getByRole("button", { name: /详情|查看/ }).first();
  if (await detailBtn.count()) {
    await detailBtn.click();
    await page.waitForTimeout(700);
    const modal = await page.evaluate(() => {
      const t = document.body.innerText;
      return {
        hasOrch: /面试编排|流程|轮次/.test(t),
        snippet: t.slice(0, 300).replace(/\n+/g, " | "),
      };
    });
    await page.screenshot({ path: path.join(OUT, "settings-persona-detail.png") });
    console.log("详情弹窗:", JSON.stringify(modal));
  } else {
    console.log("详情弹窗: 未找到入口按钮");
  }

  await browser.close();
})();
