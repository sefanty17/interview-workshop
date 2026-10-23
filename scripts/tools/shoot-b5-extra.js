const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const fs = require("fs");
const BASE = "D:/cxdownload/javaee课设/screenshots/b5";
fs.mkdirSync(BASE + "/text2", { recursive: true });
fs.mkdirSync(BASE + "/sound2", { recursive: true });

const Q = { type: "jpeg", quality: 88 };

(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
  const p = await ctx.newPage();
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e)));
  p.on("console", (m) => { if (m.type() === "error") errs.push("console:" + m.text()); });

  const out = {};
  // 每轮从干净的 localStorage 起步，保证长图是默认状态
  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.evaluate(() => localStorage.removeItem("interview-lab-v1"));
  await p.reload({ waitUntil: "networkidle" });
  await p.waitForTimeout(500);
  const gotoText = async () => {
    await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
    await p.waitForTimeout(700);
    await p.getByText("通用", { exact: true }).click();
    await p.waitForTimeout(800);
  };
  const card = (t) => p.locator("section.menu-item").filter({ hasText: t }).first();

  // ---------- 1) 通用页完整长图（拉高视口，保留布局不裁切） ----------
  await gotoText();
  out.textBlocks = await p.locator("section.menu-item").count();
  const textH = await p.evaluate(() => {
    const sc = document.querySelector(".settings-text-container .overflow-y-auto");
    return sc ? sc.scrollHeight : document.body.scrollHeight;
  });
  await p.setViewportSize({ width: 1440, height: Math.min(6000, textH + 60) });
  await p.waitForTimeout(700);
  await p.screenshot({ path: BASE + "/text2/01-full.jpg", ...Q, fullPage: true });

  // ---------- 2) 新补区块逐张 ----------
  await p.setViewportSize({ width: 1440, height: 900 });
  await gotoText();
  const shots = [
    ["语音推理引擎下载（SBV2）", "02-engine-download.jpg"],
    ["启用永久记忆", "03-permanent-memory.jpg"],
    ["语音缓存", "04-tts-cache.jpg"],
    ["版本更新", "05-update.jpg"],
    ["局域网数据同步", "06-lan-sync.jpg"],
    ["了解 LingChat 的相关文档", "07-docs.jpg"],
  ];
  for (const [title, file] of shots) {
    await card(title).scrollIntoViewIfNeeded();
    await p.waitForTimeout(300);
    await card(title).screenshot({ path: BASE + "/text2/" + file, ...Q });
  }

  // ---------- 3) 实测：版本更新 / 局域网 / 文档 / 引擎下载 → 桌面版提示 ----------
  await card("版本更新").getByRole("button", { name: "检查更新" }).click();
  await p.waitForTimeout(1400);
  out.updateDialog = await p.evaluate(() => document.body.innerText.includes("桌面版功能，Web 端不适用"));
  await p.screenshot({ path: BASE + "/text2/08-update-dialog.jpg", ...Q });
  await p.getByRole("button", { name: "确定" }).first().click();
  await p.waitForTimeout(300);

  // ---------- 4) 实测：启用永久记忆（能存 localStorage，刷新后仍在） ----------
  const memLabel = p.locator("label", { hasText: "开启后记忆将自动压缩" }).first();
  const memBefore = await p.evaluate(() => {
    const l = Array.from(document.querySelectorAll("label")).find((x) => x.textContent.includes("开启后记忆将自动压缩"));
    return l.querySelector("input[type=checkbox]").checked;
  });
  await memLabel.click();
  await p.waitForTimeout(400);
  const memAfter = await p.evaluate(() => JSON.parse(localStorage.getItem("interview-lab-v1")).settings.permanentMemory);
  await card("启用永久记忆").screenshot({ path: BASE + "/text2/09-memory-on.jpg", ...Q });
  await p.reload({ waitUntil: "networkidle" });
  await p.waitForTimeout(600);
  await p.getByText("通用", { exact: true }).click();
  await p.waitForTimeout(700);
  const memPersisted = await p.evaluate(() => {
    const l = Array.from(document.querySelectorAll("label")).find((x) => x.textContent.includes("开启后记忆将自动压缩"));
    return l ? l.querySelector("input[type=checkbox]").checked : null;
  });
  out.memory = { memBefore, memAfter, memPersisted };

  // ---------- 5) 实测：清理语音缓存（confirmDialog → 占用归零） ----------
  const cacheBefore = await p.evaluate(() => {
    const s = Array.from(document.querySelectorAll("section.menu-item")).find((x) => x.textContent.includes("当前缓存"));
    return s.innerText.replace(/\n+/g, " | ");
  });
  await card("语音缓存").getByRole("button", { name: /清理孤立语音缓存/ }).click();
  await p.waitForTimeout(600);
  out.cacheDialog = await p.evaluate(() => document.body.innerText.replace(/\n+/g, " | ").slice(-160));
  await p.screenshot({ path: BASE + "/text2/10-cache-confirm.jpg", ...Q });
  await p.getByRole("button", { name: "确定" }).first().click();
  await p.waitForTimeout(600);
  const cacheAfter = await p.evaluate(() => {
    const raw = localStorage.getItem("interview-lab-v1");
    const s = JSON.parse(raw).settings;
    return {
      size: s.ttsCacheSize,
      files: s.ttsCacheFiles,
      text: Array.from(document.querySelectorAll("section.menu-item")).find((x) => x.textContent.includes("当前缓存"))?.innerText.replace(/\n+/g, " | "),
    };
  });
  await card("语音缓存").screenshot({ path: BASE + "/text2/11-cache-cleared.jpg", ...Q });
  out.cache = { cacheBefore, cacheAfter };

  // ---------- 6) 声音页：完整长图 + 环境音卡 ----------
  await p.getByText("声音", { exact: true }).click();
  await p.waitForTimeout(900);
  out.soundBlocks = await p.locator("section.menu-item").count();
  const soundH = await p.evaluate(() => {
    const sc = document.querySelector(".settings-text-container .overflow-y-auto") || document.querySelector(".overflow-y-auto");
    return sc ? sc.scrollHeight : document.body.scrollHeight;
  });
  await p.setViewportSize({ width: 1440, height: Math.min(6000, soundH + 60) });
  await p.waitForTimeout(700);
  await p.screenshot({ path: BASE + "/sound2/01-full.jpg", ...Q, fullPage: true });
  await p.setViewportSize({ width: 1440, height: 900 });
  await p.waitForTimeout(400);

  const amb = card("环境音管理");
  await amb.scrollIntoViewIfNeeded();
  await p.waitForTimeout(300);
  await amb.screenshot({ path: BASE + "/sound2/02-ambient.jpg", ...Q });

  // 增：添加一条
  const countBefore = await p.evaluate(() => document.querySelectorAll('section.menu-item button[title="删除"]').length);
  await amb.getByRole("button", { name: /添加环境音/ }).click();
  await p.waitForTimeout(500);
  const countAfterAdd = await p.evaluate(() => JSON.parse(localStorage.getItem("interview-lab-v1")).settings.ambientTracks.length);
  await amb.screenshot({ path: BASE + "/sound2/03-ambient-added.jpg", ...Q });

  // 改：第一条音量拖到 ~30%，循环改「单曲循环」
  const slider = amb.locator('input[type=range]').first();
  const sb = await slider.boundingBox();
  await p.mouse.click(sb.x + sb.width * 0.3, sb.y + sb.height / 2);
  await p.waitForTimeout(400);
  await amb.locator("select").first().selectOption("single");
  await p.waitForTimeout(400);
  const afterEdit = await p.evaluate(() => JSON.parse(localStorage.getItem("interview-lab-v1")).settings.ambientTracks[0]);
  await amb.screenshot({ path: BASE + "/sound2/04-ambient-edited.jpg", ...Q });

  // 删：删掉最后一条
  const nameToDelete = await p.evaluate(() => {
    const t = JSON.parse(localStorage.getItem("interview-lab-v1")).settings.ambientTracks;
    return t[t.length - 1].name;
  });
  await amb.locator('button[title="删除"]').last().click();
  await p.waitForTimeout(500);
  const tracksAfter = await p.evaluate(() => JSON.parse(localStorage.getItem("interview-lab-v1")).settings.ambientTracks);
  await amb.screenshot({ path: BASE + "/sound2/05-ambient-deleted.jpg", ...Q });

  out.ambient = {
    countBefore,
    countAfterAdd,
    nameToDelete,
    countAfterDelete: tracksAfter.length,
    namesAfterDelete: tracksAfter.map((t) => t.name),
    afterEdit,
  };

  // 刷新校验持久化
  await p.reload({ waitUntil: "networkidle" });
  await p.waitForTimeout(600);
  await p.getByText("声音", { exact: true }).click();
  await p.waitForTimeout(700);
  out.ambientPersisted = await p.evaluate(() => JSON.parse(localStorage.getItem("interview-lab-v1")).settings.ambientTracks.length);

  out.errs = errs.slice(0, 6);
  console.log(JSON.stringify(out, null, 1));
  await b.close();
})();
