// 背景页截图与实测（1440x900 · JPEG）
const { chromium } = require("playwright-core");
const fs = require("fs");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/screenshots/b5/background";
fs.mkdirSync(OUT, { recursive: true });

const shot = async (p, name) => {
  await p.screenshot({ path: `${OUT}/${name}.jpg`, type: "jpeg", quality: 88 });
  console.log("shot", name);
};

(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
  const p = await ctx.newPage();
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e)));
  p.on("console", (m) => { if (m.type() === "error") errs.push("console:" + m.text()); });

  // 造一张上传用的测试图
  const IMG = `${OUT}/_upload-test.png`;
  await p.setContent(`<body style="margin:0"><div style="width:640px;height:360px;background:linear-gradient(135deg,#1b4a6b,#0b1220 60%),radial-gradient(circle at 30% 30%,#7fd7ff55,transparent 60%);"></div></body>`);
  await p.locator("div").first().screenshot({ path: IMG, type: "png" });

  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.evaluate(() => localStorage.removeItem("interview-lab-v1"));
  await p.reload({ waitUntil: "networkidle" });
  await p.getByText("背景", { exact: true }).click();
  await p.waitForTimeout(900);

  // 1) 默认态
  await shot(p, "01-default");

  // 量基础几何
  const geom = await p.evaluate(() => {
    const card = document.querySelector("section .grid > div");
    const thumb = card.querySelector("img, .aspect-video");
    const info = card.querySelector(".border-t");
    const cs = getComputedStyle(info);
    const r = card.getBoundingClientRect();
    const tr = thumb.getBoundingClientRect();
    const grid = card.parentElement;
    return {
      cards: grid.children.length,
      cols: getComputedStyle(grid).gridTemplateColumns,
      cardW: +r.width.toFixed(1),
      cardH: +r.height.toFixed(1),
      thumbH: +tr.height.toFixed(1),
      ratio: +(r.width / tr.height).toFixed(2),
      infoPad: [cs.paddingTop, cs.paddingRight, cs.paddingBottom, cs.paddingLeft].join(" "),
      infoBg: cs.backgroundColor,
      cardBg: getComputedStyle(card).backgroundColor,
      cardRadius: getComputedStyle(card).borderRadius,
      gap: getComputedStyle(grid).gap,
      pageW: document.querySelector("section").getBoundingClientRect().width.toFixed(1),
    };
  });
  console.log("GEOM", JSON.stringify(geom));

  // 量按钮颜色
  const colors = await p.evaluate(() => {
    const btns = [...document.querySelectorAll("section button")];
    const pick = (t) => {
      const el = btns.find((b) => b.textContent.trim() === t);
      if (!el) return null;
      const s = getComputedStyle(el);
      return { bg: s.backgroundColor, color: s.color, border: s.borderColor, radius: s.borderRadius, h: el.getBoundingClientRect().height };
    };
    return {
      create: pick("创建场景"),
      upload: pick("上传背景"),
      del: pick("删除"),
      current: getComputedStyle(document.querySelector("section .font-bold.text-\\[\\#79d9ff\\]") || document.body).color,
    };
  });
  console.log("COLORS", JSON.stringify(colors));

  // 2) hover 卡片（上浮 + 描边）
  await p.locator("section .grid > div").nth(1).hover();
  await p.waitForTimeout(450);
  await shot(p, "02-hover");

  // 3) 点星标收藏第 3 张 → 收藏置顶
  const firstName = await p.locator("section .grid > div").nth(2).locator("span.font-medium").innerText();
  await p.locator('section .grid > div button[title="收藏（移到最前）"]').nth(2).click();
  await p.waitForTimeout(450);
  const favOrder = await p.locator("section .grid > div span.font-medium").allInnerTexts();
  const starFilled = await p.evaluate(() => {
    const s = document.querySelector('section .grid > div button[title="取消收藏"] svg');
    return s ? { fill: getComputedStyle(s).fill, cls: s.getAttribute("class") } : null;
  });
  console.log("FAV", firstName, "->", JSON.stringify(favOrder), "starFill:", starFilled);
  await shot(p, "03-favorite");

  // 4) 选中第 2 张 → 当前场景
  await p.locator("section .grid > div").nth(1).click();
  await p.waitForTimeout(400);
  const currentText = await p.locator("section .font-bold.text-\\[\\#79d9ff\\]").innerText();
  console.log("CURRENT", currentText);
  await shot(p, "04-selected");

  // 5) 下一页
  const pageInfo = await p.getByText(/第 1 \/ \d 页/).innerText();
  await p.getByText("下一页", { exact: true }).click();
  await p.waitForTimeout(400);
  const page2Count = await p.locator("section .grid > div").count();
  console.log("PAGINATION", pageInfo, "page2 cards:", page2Count);
  await shot(p, "05-page2");

  // 6) 分类过滤：插件
  await p.locator('section button[title="插件"]').click();
  await p.waitForTimeout(450);
  const pluginCount = await p.locator("section .grid > div").count();
  const uploadDisabled = await p.getByText("上传背景", { exact: true }).isDisabled();
  const pagerVisible = await p.getByText(/第 \d \/ \d 页/).count();
  console.log("PLUGIN", pluginCount, "uploadDisabled:", uploadDisabled, "pager:", pagerVisible);
  await shot(p, "06-category-plugin");

  // 7) 新建分类 → 空态
  await p.locator('section button[title="全部"]').click();
  await p.getByText("新建分类", { exact: true }).first().click();
  await p.waitForTimeout(250);
  await p.locator('input[placeholder="新分类名"]').fill("大厂会议室");
  await p.getByRole("button", { name: "新建分类" }).last().click();
  await p.waitForTimeout(500);
  const emptyText = await p.getByText("该分类下暂无场景").count();
  console.log("EMPTY", emptyText, "cats:", await p.locator("section .mb-5 button").allInnerTexts());
  await shot(p, "07-category-empty");

  // 8) 创建场景弹窗
  await p.getByText("创建场景", { exact: true }).click();
  await p.waitForTimeout(400);
  await shot(p, "08-create-modal");
  await p.locator('input[placeholder="例如：海边日落"]').fill("海边日落");
  await p.locator("textarea").fill("黄昏的海边，适合轻松的开场闲聊。");
  await p.locator("select").first().selectOption("大厂会议室");
  await p.getByRole("button", { name: "创建", exact: true }).click();
  await p.waitForTimeout(500);
  const afterCreate = await p.locator("section .grid > div").count();
  console.log("CREATE", "cards in category:", afterCreate, await p.locator("section .grid > div span.font-medium").allInnerTexts());

  // 9) 排序弹窗（先回全部）
  await p.locator('section button[title="全部"]').click();
  await p.waitForTimeout(300);
  await p.getByText("场景排序", { exact: true }).click();
  await p.waitForTimeout(450);
  await shot(p, "09-sort-modal");
  const firstRow = await p.locator("div.fixed .space-y-2 > div span.flex-1").allInnerTexts();
  await p.locator("div.fixed button[title='下移']").first().click();
  await p.waitForTimeout(250);
  const afterMove = await p.locator("div.fixed .space-y-2 > div span.flex-1").allInnerTexts();
  console.log("SORT", JSON.stringify(firstRow), "->", JSON.stringify(afterMove));
  await p.getByText("保存排序", { exact: true }).click();
  await p.waitForTimeout(400);
  const afterSave = await p.locator("section .grid > div span.font-medium").allInnerTexts();
  console.log("SORT-SAVED", JSON.stringify(afterSave));

  // 10) 刷新场景：确认弹窗
  await p.getByText("刷新场景", { exact: true }).click();
  await p.waitForTimeout(400);
  await shot(p, "10-refresh-confirm");
  await p.getByRole("button", { name: "确定", exact: true }).click();
  await p.waitForTimeout(900);
  const toast = await p.locator(".toast").innerText().catch(() => "");
  console.log("REFRESH toast:", toast);

  // 11) 打开文件夹
  await p.getByText("打开文件夹", { exact: true }).click();
  await p.waitForTimeout(400);
  const dlgText = await p.locator("div.fixed.z-\\[10001\\]").innerText();
  console.log("FOLDER", JSON.stringify(dlgText.replace(/\n+/g, " | ")));
  await shot(p, "11-open-folder-dialog");
  await p.getByRole("button", { name: "确定", exact: true }).click();
  await p.waitForTimeout(300);

  // 12) 上传背景：新卡片出现
  await p.setInputFiles('input[type="file"]', IMG);
  await p.waitForTimeout(700);
  const afterUpload = await p.locator("section .grid > div span.font-medium").allInnerTexts();
  console.log("UPLOAD", JSON.stringify(afterUpload));
  await shot(p, "12-upload");

  // 13) 删除当前场景（确认框）
  await p.locator("section .grid > div").first().click();
  await p.waitForTimeout(300);
  await p.getByText("删除", { exact: true }).click();
  await p.waitForTimeout(400);
  console.log("DELETE-DIALOG", (await p.locator("div.fixed.z-\\[10001\\]").innerText()).replace(/\n+/g, " | "));
  await shot(p, "13-delete-confirm");
  await p.getByRole("button", { name: "确定", exact: true }).click();
  await p.waitForTimeout(500);
  console.log("AFTER-DELETE", JSON.stringify(await p.locator("section .grid > div span.font-medium").allInnerTexts()));
  await p.locator('section button[title="全部"]').click();
  await p.waitForTimeout(300);
  await shot(p, "14-after-delete");

  // 14) 刷新页面后持久化检查
  await p.reload({ waitUntil: "networkidle" });
  await p.getByText("背景", { exact: true }).click();
  await p.waitForTimeout(800);
  console.log("RELOAD", JSON.stringify(await p.locator("section .grid > div span.font-medium").allInnerTexts()));
  console.log("CURRENT-AFTER-RELOAD", await p.locator("section .font-bold.text-\\[\\#79d9ff\\]").innerText());
  await shot(p, "15-reload");

  console.log("ERRORS", JSON.stringify(errs));
  await b.close();
})();
