const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const BASE = "http://127.0.0.1:5173";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1280, height: 820 } });
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e)));
  await p.goto(BASE + "/", { waitUntil: "networkidle" });
  await p.evaluate(() => localStorage.clear());
  await p.goto(BASE + "/interview", { waitUntil: "networkidle" });
  await sleep(1200);
  const focusInfo = await p.evaluate(() => document.activeElement?.className?.toString().slice(0, 40));
  console.log("focus on mount:", focusInfo);
  // Enter 打断打字
  await p.keyboard.press("Enter");
  await sleep(200);
  const t1 = await p.locator(".response-display").innerText();
  await p.keyboard.press("Enter"); // 推进到第一问
  await sleep(600);
  const t2 = await p.locator(".response-display").innerText();
  console.log("line1:", JSON.stringify(t1.slice(0, 24)), "| line2:", JSON.stringify(t2.slice(0, 24)));
  console.log("still responding (same element re-typed):", t2 !== t1);
  // Shift+Enter 换行不应发送
  await p.keyboard.press("Enter").catch(() => {});
  await sleep(400);
  await p.keyboard.press("Enter").catch(() => {});
  await sleep(400);
  const inInput = await p.locator("#inputMessage").isVisible();
  console.log("reached input:", inInput);
  await p.locator("#inputMessage").type("第一行");
  await p.keyboard.down("Shift");
  await p.keyboard.press("Enter");
  await p.keyboard.up("Shift");
  await p.locator("#inputMessage").type("第二行");
  const val = await p.locator("#inputMessage").inputValue();
  console.log("textarea value has newline:", JSON.stringify(val));
  await p.keyboard.press("Enter");
  await sleep(300);
  const thinkingPlaceholder = await p.locator("#inputMessage").getAttribute("placeholder");
  console.log("after enter, placeholder:", thinkingPlaceholder, "readonly:", await p.locator("#inputMessage").getAttribute("readonly"));
  console.log("errors:", errs);
  await b.close();
})();
