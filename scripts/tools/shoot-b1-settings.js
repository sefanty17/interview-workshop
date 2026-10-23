const { chromium } = require("playwright-core");
const path = require("path");
const fs = require("fs");

const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const BASE = "http://127.0.0.1:5173";
const OUT = "D:/cxdownload/javaee课设/screenshots/b1";

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch({ executablePath: CHROME, headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 820 } });

  // 1. 默认态（应落在角色页）
  await page.goto(BASE + "/settings", { waitUntil: "networkidle" });
  await page.waitForTimeout(800);
  await page.screenshot({ path: path.join(OUT, "settings-character.png") });

  const measure = await page.evaluate(() => {
    const px = (v) => parseFloat(v) || 0;
    const nav = document.querySelector("nav");
    const navWrap = nav.parentElement;
    const btns = Array.from(nav.querySelectorAll("button"));
    const ind = nav.querySelector("div");
    const logo = navWrap.querySelector("img");
    const close = document.querySelector("span[style*='width:40px']") || document.querySelector("span.rounded-full");
    const cs = (el) => getComputedStyle(el);
    const b = btns[0];
    const bc = cs(b);
    const navRect = nav.getBoundingClientRect();
    const indRect = ind.getBoundingClientRect();
    // 内容区根节点（页面占位容器）
    const pageRoot = document.querySelector(".settings-panel > div:nth-child(2) > * > div");
    const rootDiv = Array.from(document.querySelectorAll("div")).find((d) =>
      d.className.includes("mx-auto") && d.className.includes("flex-wrap"),
    );
    return {
      navWrapPadding: cs(navWrap).padding,
      navPadding: cs(nav).padding,
      navHeight: Math.round(navRect.height),
      btnPadding: bc.padding,
      btnMargin: bc.margin,
      btnRadius: bc.borderRadius,
      btnFontSize: bc.fontSize,
      btnFontWeight: bc.fontWeight,
      btnTextShadow: bc.textShadow,
      btnCount: btns.length,
      btnLabels: btns.map((x) => x.innerText.trim()),
      btnWidth: Math.round(b.getBoundingClientRect().width),
      btnHeight: Math.round(b.getBoundingClientRect().height),
      btnGap: cs(nav).columnGap,
      indHeight: Math.round(indRect.height),
      indBottomGap: Math.round(navRect.bottom - (indRect.bottom - 0)),
      indBottomFromNavBottom: Math.round(navRect.bottom - indRect.bottom),
      indBottomCss: cs(ind).bottom,
      indRadius: cs(ind).borderRadius,
      indBg: cs(ind).backgroundColor,
      indShadow: cs(ind).boxShadow,
      indWidth: Math.round(indRect.width),
      indLeft: Math.round(indRect.left - navRect.left),
      logoWidth: logo ? Math.round(logo.getBoundingClientRect().width) : null,
      logoMarginLeft: logo ? cs(logo).marginLeft : null,
      logoVisible: logo ? logo.getBoundingClientRect().width > 0 : null,
      closeW: close ? Math.round(close.getBoundingClientRect().width) : null,
      closeH: close ? Math.round(close.getBoundingClientRect().height) : null,
      closePadding: close ? cs(close).padding : null,
      closeRadius: close ? cs(close).borderRadius : null,
      closeTransition: close ? cs(close).transition : null,
      contentWidth: rootDiv ? Math.round(rootDiv.getBoundingClientRect().width) : null,
      contentPercent: rootDiv
        ? Math.round((rootDiv.getBoundingClientRect().width / window.innerWidth) * 1000) / 10
        : null,
      bodyText: document.body.innerText.replace(/\s+/g, " ").trim().slice(0, 120),
    };
  });
  console.log("=== 默认态（角色）测量 ===");
  console.log(JSON.stringify(measure, null, 2));

  // 2. 点「声音」
  await page.getByRole("button", { name: "声音" }).click();
  await page.waitForTimeout(250);
  // 动画中途抓一帧，确认指示条在滑动（非瞬移）
  const mid = await page.evaluate(() => {
    const ind = document.querySelector("nav div");
    const btns = Array.from(document.querySelectorAll("nav button"));
    const t = btns.find((b) => b.innerText.trim() === "声音").getBoundingClientRect();
    const i = ind.getBoundingClientRect();
    return {
      indicatorLeft: Math.round(i.left),
      indicatorWidth: Math.round(i.width),
      soundLeft: Math.round(t.left),
      soundWidth: Math.round(t.width),
      transition: ind.style.transition,
    };
  });
  await page.waitForTimeout(700);
  await page.screenshot({ path: path.join(OUT, "settings-sound.png") });

  const after = await page.evaluate(() => {
    const ind = document.querySelector("nav div");
    const btns = Array.from(document.querySelectorAll("nav button"));
    const t = btns.find((b) => b.innerText.trim() === "声音").getBoundingClientRect();
    const nav = document.querySelector("nav").getBoundingClientRect();
    const i = ind.getBoundingClientRect();
    const active = document.querySelector("nav button.text-\\[var\\(--accent-color\\)\\]");
    const rootDiv = Array.from(document.querySelectorAll("div")).find((d) =>
      d.className.includes("mx-auto") && d.className.includes("flex-wrap"),
    );
    return {
      indicatorLeft: Math.round(i.left - nav.left),
      indicatorWidth: Math.round(i.width),
      soundBtnLeft: Math.round(t.left - nav.left),
      soundBtnWidth: Math.round(t.width),
      alignOk: Math.abs(i.left - t.left) < 1.5 && Math.abs(i.width - t.width) < 1.5,
      activeText: active ? active.innerText.trim() : null,
      activeBg: active ? getComputedStyle(active).backgroundColor : null,
      activeColor: active ? getComputedStyle(active).color : null,
      pageLabel: rootDiv ? rootDiv.innerText.trim() : null,
      transitionAfter: ind.style.transition,
    };
  });
  console.log("=== 动画中途 ===", JSON.stringify(mid));
  console.log("=== 点声音后 ===", JSON.stringify(after, null, 2));

  // 3. 点 ✕ 回首页
  await page.locator("span.rounded-full").first().click();
  await page.waitForTimeout(900);
  const url = page.url();
  await page.screenshot({ path: path.join(OUT, "back-home.png") });
  console.log("=== 关闭后 URL ===", url);

  await browser.close();
})();
