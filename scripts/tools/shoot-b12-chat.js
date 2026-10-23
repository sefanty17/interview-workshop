/**
 * B12 前端联调实测：进房间 → 建会话（后端）→ 发消息 → SSE 台词落到台词区。
 *
 * 前置：后端 8080 + vite 5173 都在跑。产出 4 张 JPEG 到 screenshots/b12/chat/。
 */
const { chromium } = require("playwright-core");
const fs = require("fs");

const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/screenshots/b12/chat";
const BASE = "http://127.0.0.1:5173";

fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.launch({ executablePath: CHROME, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errs = [];
  page.on("pageerror", (e) => errs.push(String(e)));

  // 干净开局：清掉上次留下的本地存档（否则会恢复旧会话，看不到新开场白）
  await page.goto(BASE + "/interview", { waitUntil: "domcontentloaded" });
  await page.evaluate(() => localStorage.clear());
  await page.reload({ waitUntil: "networkidle" });

  // 等后端会话建好 + 开场白打完
  await sleep(4500);
  console.log(
    "opening line:",
    JSON.stringify(
      await page.evaluate(
        () => document.querySelector(".response-display div")?.textContent ?? "",
      ),
    ),
  );

  // 开场白处于「回应态」：第一下 ▼ 收掉打字机，第二下才推进到输入态
  for (let i = 0; i < 6; i++) {
    if (await page.isVisible("#inputMessage")) break;
    await page.click("#sendButton");
    await sleep(600);
  }
  const editing = await page.isEditable("#inputMessage");
  console.log("input editable:", editing);
  if (!editing) throw new Error("没能切到输入态");

  await page.fill("#inputMessage", "我叫蔡智豪，软工专业");
  await sleep(300);
  await page.screenshot({ path: OUT + "/01-before-send.jpg", type: "jpeg", quality: 82 });

  await page.press("#inputMessage", "Enter");
  // 思考态存在时间取决于模型首字延迟，尽早截（首字通常 0.5s 起陆续到达）
  await sleep(280);
  const thinkingPlaceholder = await page.getAttribute("#inputMessage", "placeholder");
  console.log("thinking placeholder:", JSON.stringify(thinkingPlaceholder));
  await page.screenshot({ path: OUT + "/02-thinking.jpg", type: "jpeg", quality: 82 });

  // 等第一句台词出现（SSE 收完后才 pushLine，模型通常 10-20s）
  const deadline = Date.now() + 90000;
  let seen = false;
  while (Date.now() < deadline) {
    const len = await page.evaluate(() => {
      const el = document.querySelector(".response-display div");
      return el ? el.textContent.trim().length : 0;
    });
    if (len >= 4) {
      seen = true;
      break;
    }
    await sleep(400);
  }
  console.log("first line appeared:", seen);
  await page.screenshot({ path: OUT + "/03-reply-first.jpg", type: "jpeg", quality: 82 });

  // 等打字机把第一句打完（文本 1.2s 不再变化）
  let prev = "";
  for (let i = 0; i < 40; i++) {
    const cur = await page.evaluate(
      () => document.querySelector(".response-display div")?.textContent ?? "",
    );
    if (cur && cur === prev) break;
    prev = cur;
    await sleep(600);
  }
  await sleep(400);
  const shown = await page.evaluate(() => ({
    text: document.querySelector(".response-display div")?.textContent ?? "",
    motion: document.querySelectorAll(".response-display div")[1]?.textContent ?? "",
    emotion: document.querySelector("#character-emotion")?.textContent?.trim() ?? "",
  }));
  console.log("shown:", JSON.stringify(shown, null, 2));
  await page.screenshot({ path: OUT + "/04-reply-full.jpg", type: "jpeg", quality: 82 });

  // 顺带验证会话确实落在后端：前端拿到的 sessionId 在 store 之外，这里只核对历史面板条数
  const lines = await page.evaluate(() => {
    const raw = localStorage.getItem("interview-lab-v1");
    const s = raw ? JSON.parse(raw).session : null;
    return s ? s.messages.map((m) => `${m.role}:${m.text.slice(0, 30)}`) : [];
  });
  console.log("localStore messages:", JSON.stringify(lines, null, 2));
  console.log("pageerrors:", errs.length ? errs : "none");

  await browser.close();
})();
