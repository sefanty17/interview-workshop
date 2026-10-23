const { chromium } = require("playwright-core");
const fs = require("fs");

const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const BASE = "http://127.0.0.1:5173";
const OUT_SAVE = "D:/cxdownload/javaee课设/screenshots/b5/save";
const OUT_HIS = "D:/cxdownload/javaee课设/screenshots/b5/history";
fs.mkdirSync(OUT_SAVE, { recursive: true });
fs.mkdirSync(OUT_HIS, { recursive: true });

const shot = (p, path) => p.screenshot({ path, type: "jpeg", quality: 85 });

(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e)));
  p.on("console", (m) => {
    if (m.type() === "error") errs.push("console: " + m.text());
  });

  const openTab = async (label) => {
    await p.goto(BASE + "/settings", { waitUntil: "networkidle" });
    await p.waitForTimeout(600);
    await p.locator("nav button", { hasText: label }).first().click();
    await p.waitForTimeout(600);
  };

  // 归一化起点：清掉本地持久化，回到示例记录
  await p.goto(BASE + "/", { waitUntil: "networkidle" });
  await p.evaluate(() => localStorage.clear());
  await p.goto(BASE + "/settings", { waitUntil: "networkidle" });
  await p.reload({ waitUntil: "networkidle" });
  await p.waitForTimeout(600);

  // ============ 存档页 ============
  await openTab("存档");
  await shot(p, OUT_SAVE + "/01-default.jpg");

  const saveMetrics = await p.evaluate(() => {
    const root = document.querySelector(".settings-panel");
    const items = Array.from(root.querySelectorAll("section.menu-item"));
    const itemInfo = items.map((s) => {
      const cs = getComputedStyle(s);
      const r = s.getBoundingClientRect();
      return {
        title: s.querySelector("h4")?.textContent?.trim(),
        w: Math.round(r.width),
        h: Math.round(r.height),
        padding: cs.padding,
        bg: cs.backgroundColor,
        radius: cs.borderRadius,
        bottom: Math.round(r.bottom),
      };
    });
    const card = root.querySelector(".grid > div");
    const cardCS = card && getComputedStyle(card);
    const cardR = card?.getBoundingClientRect();
    const thumb = card?.querySelector("div.relative.h-48");
    const thumbR = thumb?.getBoundingClientRect();
    const input = root.querySelector("input.save-input");
    const inputCS = input && getComputedStyle(input);
    const createBtn = root.querySelector(".save-create-btn");
    const createCS = createBtn && getComputedStyle(createBtn);
    const btns = card ? Array.from(card.querySelectorAll("button")).map((x) => {
      const cs = getComputedStyle(x);
      const r = x.getBoundingClientRect();
      return { t: x.textContent.trim(), w: Math.round(r.width), h: Math.round(r.height), radius: cs.borderRadius, bg: cs.backgroundColor, border: cs.borderColor, fs: cs.fontSize };
    }) : [];
    const grid = root.querySelector(".grid");
    const gridCS = grid && getComputedStyle(grid);
    return {
      containerW: Math.round(root.querySelector(".mx-auto")?.getBoundingClientRect().width ?? 0),
      items: itemInfo,
      card: cardR && { w: Math.round(cardR.width), h: Math.round(cardR.height), radius: cardCS.borderRadius, bg: cardCS.backgroundColor, border: cardCS.borderTopColor, pad: cardCS.padding },
      thumb: thumbR && { w: Math.round(thumbR.width), h: Math.round(thumbR.height) },
      input: inputCS && { bg: inputCS.backgroundColor, radius: inputCS.borderRadius, pad: inputCS.padding, fs: inputCS.fontSize, h: Math.round(input.getBoundingClientRect().height) },
      create: createCS && { w: Math.round(createBtn.getBoundingClientRect().width), h: Math.round(createBtn.getBoundingClientRect().height), bg: createCS.backgroundColor, border: createCS.borderTopColor, radius: createCS.borderRadius },
      buttons: btns,
      gridGap: gridCS && gridCS.gap,
      scrollBoxH: Math.round(root.querySelector(".h-\\[calc\\(100dvh-24rem\\)\\]")?.getBoundingClientRect().height ?? 0),
      cardCount: root.querySelectorAll(".grid > div").length,
    };
  });
  console.log("SAVE_METRICS", JSON.stringify(saveMetrics, null, 1));

  // 创建
  await p.fill("input.save-input", "秋招提前批模拟面");
  await shot(p, OUT_SAVE + "/02-typed.jpg");
  await p.click(".save-create-btn");
  await p.waitForTimeout(700);
  await shot(p, OUT_SAVE + "/03-created.jpg");
  const afterCreate = await p.evaluate(() => ({
    toast: document.querySelector(".toast")?.textContent?.trim() ?? "",
    firstTitle: document.querySelector(".grid > div")?.querySelector("div.truncate, div.cursor-pointer")?.textContent?.trim() ?? "",
    count: document.querySelectorAll(".grid > div").length,
    inputEmpty: (document.querySelector("input.save-input")?.value ?? "") === "",
    currentBadge: document.querySelector(".grid > div span.absolute")?.textContent?.trim() ?? "",
    firstCardBg: getComputedStyle(document.querySelector(".grid > div")).backgroundColor,
    firstCardBorder: getComputedStyle(document.querySelector(".grid > div")).borderTopColor,
  }));
  console.log("AFTER_CREATE", JSON.stringify(afterCreate, null, 1));

  // 覆盖（第一张=刚创建的，是当前存档 → 走「更新存档」，不弹确认）
  await p.locator(".grid > div button", { hasText: "更新存档" }).first().click();
  await p.waitForTimeout(600);
  const afterUpdate = await p.evaluate(() => ({
    toast: document.querySelector(".toast")?.textContent?.trim() ?? "",
    date: document.querySelector(".grid > div .font-mono span:last-child")?.textContent?.trim() ?? "",
  }));
  console.log("AFTER_UPDATE", JSON.stringify(afterUpdate, null, 1));

  // 覆盖非当前存档 → 弹确认
  await p.locator(".grid > div button", { hasText: "覆盖存档" }).first().click();
  await p.waitForTimeout(500);
  await shot(p, OUT_SAVE + "/04-overwrite-confirm.jpg");
  const confirmText = await p.evaluate(() => {
    const dlg = document.querySelector(".fixed.inset-0.z-\\[10001\\]");
    return dlg ? { title: dlg.querySelector("h2,h3,.title")?.textContent?.trim(), text: dlg.innerText.replace(/\n+/g, " | ") } : null;
  });
  console.log("CONFIRM", JSON.stringify(confirmText, null, 1));
  // 取消
  await p.locator(".fixed.inset-0.z-\\[10001\\] button", { hasText: "取消" }).first().click();
  await p.waitForTimeout(400);

  // 删除刚创建的存档
  await p.locator(".grid > div button", { hasText: "删除存档" }).first().click();
  await p.waitForTimeout(500);
  await shot(p, OUT_SAVE + "/05-delete-confirm.jpg");
  await p.locator(".fixed.inset-0.z-\\[10001\\] button", { hasText: "确定" }).first().click();
  await p.waitForTimeout(700);
  const afterDelete = await p.evaluate(() => ({
    toast: document.querySelector(".toast")?.textContent?.trim() ?? "",
    count: document.querySelectorAll(".grid > div").length,
    badge: document.querySelector(".grid > div span.absolute")?.textContent?.trim() ?? "(none)",
  }));
  console.log("AFTER_DELETE", JSON.stringify(afterDelete, null, 1));
  await shot(p, OUT_SAVE + "/06-after-delete.jpg");

  // 读取存档：确认后跳到该场的面试报告
  await p.locator(".grid > div button", { hasText: "读取存档" }).first().click();
  await p.waitForTimeout(500);
  await shot(p, OUT_SAVE + "/07-load-confirm.jpg");
  await p.locator(".fixed.inset-0.z-\\[10001\\] button", { hasText: "确定" }).first().click();
  await p.waitForTimeout(900);
  console.log("LOAD_RESULT", JSON.stringify({ url: p.url(), toast: await p.evaluate(() => document.querySelector(".toast")?.textContent?.trim() ?? "") }));
  await shot(p, OUT_SAVE + "/08-load-report.jpg");

  // ============ 对话历史：有记录 ============
  await openTab("对话历史");
  await shot(p, OUT_HIS + "/01-list.jpg");
  const historyMetrics = await p.evaluate(() => {
    const root = document.querySelector(".settings-panel");
    const item = root.querySelector("section.menu-item");
    const card = root.querySelector(".grid > button");
    const cs = card && getComputedStyle(card);
    return {
      itemH: Math.round(item.getBoundingClientRect().height),
      itemTitle: item.querySelector("h4")?.textContent?.trim(),
      cardCount: root.querySelectorAll(".grid > button").length,
      card: card && { w: Math.round(card.getBoundingClientRect().width), h: Math.round(card.getBoundingClientRect().height), radius: cs.borderRadius, bg: cs.backgroundColor },
      firstText: card?.innerText.replace(/\n+/g, " | "),
    };
  });
  console.log("HISTORY_METRICS", JSON.stringify(historyMetrics, null, 1));

  // 会话详情
  await p.locator(".grid > button").first().click();
  await p.waitForTimeout(600);
  await shot(p, OUT_HIS + "/02-session.jpg");
  const sessionInfo = await p.evaluate(() => {
    const modal = document.querySelector(".fixed.inset-0.z-\\[9000\\] > div");
    const body = modal?.querySelector(".overflow-y-auto");
    return {
      modalW: modal ? Math.round(modal.getBoundingClientRect().width) : 0,
      modalH: modal ? Math.round(modal.getBoundingClientRect().height) : 0,
      radius: modal ? getComputedStyle(modal).borderRadius : "",
      bg: modal ? getComputedStyle(modal).backgroundColor : "",
      crumbs: Array.from(modal?.querySelectorAll("button") ?? []).slice(0, 3).map((x) => x.textContent.trim()),
      lines: Array.from(body?.querySelectorAll(".message-item") ?? []).slice(0, 4).map((x) => x.innerText.replace(/\n+/g, " ")),
      lineCount: body?.querySelectorAll(".message-item").length ?? 0,
    };
  });
  console.log("SESSION", JSON.stringify(sessionInfo, null, 1));

  // 角色详情（点第一句面试官台词）
  await p.locator(".fixed.inset-0.z-\\[9000\\] .chararter-reply").first().click();
  await p.waitForTimeout(500);
  await shot(p, OUT_HIS + "/03-character.jpg");
  const charInfo = await p.evaluate(() => {
    const modal = document.querySelector(".fixed.inset-0.z-\\[9000\\] > div");
    return {
      crumb: modal?.querySelector(".bg-white\\/10.font-semibold")?.textContent?.trim(),
      rows: Array.from(modal?.querySelectorAll(".rounded-lg.border.border-white\\/10") ?? []).map((x) => x.innerText.replace(/\n+/g, ": ")),
      text: modal?.querySelector(".message")?.innerText.replace(/\n+/g, " "),
    };
  });
  console.log("CHARACTER", JSON.stringify(charInfo, null, 1));

  // 用户详情（返回后点求职者台词）
  await p.locator(".fixed.inset-0.z-\\[9000\\] button", { hasText: "返回会话详情" }).first().click();
  await p.waitForTimeout(300);
  await p.locator(".fixed.inset-0.z-\\[9000\\] .player-message").first().click();
  await p.waitForTimeout(500);
  await shot(p, OUT_HIS + "/04-user.jpg");
  const userInfo = await p.evaluate(() => {
    const modal = document.querySelector(".fixed.inset-0.z-\\[9000\\] > div");
    return { text: modal?.querySelector(".rounded-xl")?.innerText.replace(/\n+/g, " ") };
  });
  console.log("USER", JSON.stringify(userInfo, null, 1));
  await p.keyboard.press("Escape");
  await p.waitForTimeout(300);

  // ============ 清空全部记录 → 空态 ============
  await openTab("存档");
  for (let i = 0; i < 10; i += 1) {
    const del = p.locator(".grid > div button", { hasText: "删除存档" }).first();
    if ((await del.count()) === 0) break;
    await del.click();
    await p.waitForTimeout(350);
    await p.locator(".fixed.inset-0.z-\\[10001\\] button", { hasText: "确定" }).first().click();
    await p.waitForTimeout(450);
  }
  const emptySave = await p.evaluate(() => ({
    cards: document.querySelectorAll(".grid > div").length,
    text: document.querySelector(".overflow-y-auto.p-8, p-8")?.textContent?.trim() ?? "",
  }));
  console.log("SAVE_EMPTY", JSON.stringify(emptySave, null, 1));
  await shot(p, OUT_SAVE + "/09-empty.jpg");

  await openTab("对话历史");
  await shot(p, OUT_HIS + "/05-empty.jpg");
  const emptyHistory = await p.evaluate(() => {
    const root = document.querySelector(".settings-panel");
    const item = root.querySelector("section.menu-item");
    const txt = item.querySelector(".text-2xl");
    const cs = txt && getComputedStyle(txt);
    return {
      text: txt?.textContent?.trim(),
      fontSize: cs?.fontSize,
      fontWeight: cs?.fontWeight,
      color: cs?.color,
      itemH: Math.round(item.getBoundingClientRect().height),
      itemW: Math.round(item.getBoundingClientRect().width),
      textBox: txt && { h: Math.round(txt.getBoundingClientRect().height) },
    };
  });
  console.log("EMPTY_HISTORY", JSON.stringify(emptyHistory, null, 1));

  console.log("ERRORS", JSON.stringify(errs));
  await b.close();
})();
