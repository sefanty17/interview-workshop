const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/screenshots/b4";
const BASE = "http://127.0.0.1:5173";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function measureScript() {
  const root = document.querySelector(".game-dialog");
  const inner = root.querySelector(":scope > div");
  const cs = getComputedStyle(root);
  const ir = inner.getBoundingClientRect();
  const r = root.getBoundingClientRect();
  const name = document.querySelector("#character");
  const sub = document.querySelector("#character-sub");
  const emo = document.querySelector("#character-emotion");
  const divider = document.querySelector(".dialog-divider-glow");
  const send = document.querySelector("#sendButton");
  const dcs = getComputedStyle(divider);
  const sr = send.getBoundingClientRect();
  const before = getComputedStyle(root, "::before");
  const layer = document.querySelector(".role-container-transition");
  const img = layer ? layer.querySelector("img") : null;
  const ics = img ? getComputedStyle(img) : null;
  const alr = layer.getBoundingClientRect();
  const imgR = img ? img.getBoundingClientRect() : null;
  const input = document.querySelector("#inputMessage");
  const inputR = input ? input.getBoundingClientRect() : null;
  return {
    viewport: { w: innerWidth, h: innerHeight },
    panel: {
      top: Math.round(r.top),
      height: Math.round(r.height),
      heightDvhPct: +((r.height / innerHeight) * 100).toFixed(1),
      paddingLeft: cs.paddingLeft,
      paddingRight: cs.paddingRight,
      paddingBottom: cs.paddingBottom,
      bg: cs.backgroundImage,
      radius: cs.borderRadius,
      beforeBand: { height: before.height, bg: before.backgroundImage.slice(0, 80) },
    },
    content: {
      widthStyle: inner.style.width,
      widthPx: Math.round(ir.width),
      widthPctOfViewport: +((ir.width / innerWidth) * 100).toFixed(2),
      left: Math.round(ir.left),
      right: Math.round(ir.right),
    },
    title: {
      name: name && { text: name.textContent.trim(), fontSize: getComputedStyle(name.parentElement).fontSize },
      sub: sub && { text: sub.textContent.trim(), fontSize: getComputedStyle(sub.parentElement).fontSize },
      emotion: emo && {
        text: emo.textContent.trim(),
        fontSize: getComputedStyle(emo).fontSize,
        color: getComputedStyle(emo).color,
      },
    },
    divider: {
      height: dcs.height,
      bgColor: dcs.backgroundColor,
      anim: dcs.animationName,
      width: Math.round(divider.getBoundingClientRect().width),
    },
    sendButton: {
      text: send.textContent.trim(),
      left: Math.round(sr.left),
      right: Math.round(sr.right),
      contentRight: Math.round(ir.right),
      fullyOutsideContent: sr.left >= ir.right,
      color: getComputedStyle(send).color,
      fontSize: getComputedStyle(send).fontSize,
    },
    inputBox: inputR && Math.round(inputR.height),
    avatar: img
      ? {
          fit: ics.objectFit,
          objectPosition: ics.objectPosition,
          layerTransform: getComputedStyle(layer).transform,
          layerTop: Math.round(alr.top),
          layerBottom: Math.round(alr.bottom),
          layerH: Math.round(alr.height),
          imgTop: Math.round(imgR.top),
          imgBottom: Math.round(imgR.bottom),
          imgH: Math.round(imgR.height),
          imgW: Math.round(imgR.width),
          bottomOverflowPx: Math.round(imgR.bottom - innerHeight),
        }
      : null,
  };
}

async function run(browser, w, h, tag) {
  const page = await browser.newPage({ viewport: { width: w, height: h } });
  const errs = [];
  page.on("pageerror", (e) => errs.push(String(e)));
  page.on("console", (m) => {
    if (m.type() === "error") errs.push("console: " + m.text());
  });

  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await page.evaluate(() => localStorage.clear());
  await page.goto(BASE + "/interview", { waitUntil: "networkidle" });
  await sleep(1200);

  const resp = page.locator(".response-display");
  await resp.click();
  await sleep(250);
  await resp.click();
  await sleep(900);
  await resp.click();
  await sleep(250);
  await resp.click();
  await sleep(500);

  await page.screenshot({ path: `${OUT}/${tag}-01-default.png` });
  const inputState = await page.evaluate(measureScript);

  const input = page.locator("#inputMessage");
  await input.fill("我用 Redis 做了缓存层，热点数据命中率 92%，回源压力降了七成。");
  await input.press("Enter");
  await sleep(300);
  await page.screenshot({ path: `${OUT}/${tag}-02-thinking.png` });

  await sleep(1500);
  await page.screenshot({ path: `${OUT}/${tag}-03-responding.png` });
  await sleep(1800);
  await page.screenshot({ path: `${OUT}/${tag}-03b-responding-done.png` });
  const respondState = await page.evaluate(measureScript);

  await page.locator('button[title="历史对话"]').click();
  await sleep(500);
  await page.screenshot({ path: `${OUT}/${tag}-04-history.png` });
  await page.locator(".glass-effect button", { hasText: "×" }).first().click();
  await sleep(400);

  await page.getByRole("button", { name: "结束面试" }).click();
  await sleep(500);
  await page.screenshot({ path: `${OUT}/${tag}-05-confirm.png` });

  console.log(`\n=== ${tag} (${w}x${h}) ===`);
  console.log("-- 输入态 --");
  console.log(JSON.stringify(inputState, null, 1));
  console.log("-- 回应态 --");
  console.log(JSON.stringify(respondState, null, 1));
  console.log("errors:", errs);
  await page.close();
}

(async () => {
  const browser = await chromium.launch({ executablePath: CHROME, headless: true });
  await run(browser, 1280, 820, "1280x820");
  await run(browser, 1440, 900, "1440x900");
  await browser.close();
})();
