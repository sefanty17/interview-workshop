const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const fs = require("fs");
const OUT = "D:/cxdownload/javaee课设/screenshots/b5";
fs.mkdirSync(OUT + "/text", { recursive: true });
fs.mkdirSync(OUT + "/sound", { recursive: true });

(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
  const p = await ctx.newPage();
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e)));
  p.on("console", (m) => { if (m.type() === "error") errs.push("console:" + m.text()); });

  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.waitForTimeout(800);
  await p.getByText("通用", { exact: true }).click();
  await p.waitForTimeout(950);
  await p.screenshot({ path: OUT + "/text/01-default.jpg", type: "jpeg", quality: 88 });

  // 1) 拖滑块：文字显示速度点击 20% 处
  const speed = p.locator('input[aria-label="文字显示速度"]');
  const sb = await speed.boundingBox();
  const before = await p.evaluate(() => document.querySelectorAll('.b2-value').length);
  await p.mouse.click(sb.x + sb.width * 0.2, sb.y + sb.height / 2);
  await p.waitForTimeout(400);
  const speedVal = await speed.inputValue();
  await p.screenshot({ path: OUT + "/text/02-slider-speed.jpg", type: "jpeg", quality: 88 });

  // 2) 开关切换：内联动作文本
  const toggleBefore = await p.evaluate(() => {
    const l = Array.from(document.querySelectorAll('label')).find((x) => x.textContent.includes('开启后动作文本'));
    return l ? l.querySelector('input[type=checkbox]').checked : null;
  });
  await p.getByText("开启后动作文本将与台词同时显示，无需二次点击").click();
  await p.waitForTimeout(300);
  const toggleAfter = await p.evaluate(() => {
    const l = Array.from(document.querySelectorAll('label')).find((x) => x.textContent.includes('开启后动作文本'));
    return l ? l.querySelector('input[type=checkbox]').checked : null;
  });
  await p.screenshot({ path: OUT + "/text/03-toggle.jpg", type: "jpeg", quality: 88 });

  // 3) 字体下拉：选宋体
  await p.selectOption('select[title="选择界面显示字体"]', "宋体");
  await p.waitForTimeout(400);
  const demoFont = await p.evaluate(() => {
    const el = document.querySelector('.font-demo');
    return el ? getComputedStyle(el).fontFamily : null;
  });
  await p.screenshot({ path: OUT + "/text/04-font.jpg", type: "jpeg", quality: 88 });

  await p.selectOption('select[title="选择界面显示字体"]', "");
  await p.waitForTimeout(200);
  await p.mouse.move(20, 20);

  // 4) 弹窗：导入一个假字体文件 → FontFace 解析失败 → alertDialog
  const fake = "D:/cxdownload/javaee课设/screenshots/b5/text/_fake.ttf";
  fs.writeFileSync(fake, Buffer.from("not a font file"));
  await p.setInputFiles('input[type=file]', fake);
  await p.waitForTimeout(800);
  const dialogText = await p.evaluate(() => document.body.innerText.replace(/\n+/g, " | "));
  await p.screenshot({ path: OUT + "/text/05-dialog.jpg", type: "jpeg", quality: 88 });
  // 关掉弹窗
  const ok = p.getByRole("button", { name: "确定" });
  if (await ok.count()) await ok.first().click();
  await p.waitForTimeout(300);

  await p.mouse.move(20, 20);
  // 5) 滚到底部，截完整区块
  await p.evaluate(() => {
    const el = document.querySelector('.settings-text-container');
    const sc = el?.querySelector('.overflow-y-auto') || el;
    if (sc) sc.scrollTop = sc.scrollHeight;
  });
  await p.waitForTimeout(500);
  await p.screenshot({ path: OUT + "/text/06-bottom.jpg", type: "jpeg", quality: 88 });

  await p.evaluate(() => {
    const el = document.querySelector('.settings-text-container');
    const sc = el?.querySelector('.overflow-y-auto') || el;
    if (sc) sc.scrollTop = 0;
  });
  await p.waitForTimeout(300);

  // ===== 声音页 =====
  await p.getByText("声音", { exact: true }).click();
  await p.waitForTimeout(700);
  await p.screenshot({ path: OUT + "/sound/01-default.jpg", type: "jpeg", quality: 88 });

  // 获取设备名称 → 未授权提示
  await p.getByRole("button", { name: /获取设备名称/ }).click();
  await p.waitForTimeout(700);
  await p.screenshot({ path: OUT + "/sound/02-device.jpg", type: "jpeg", quality: 88 });

  // 音量滑块：角色音量拖到 30%
  const vol = p.locator('input[aria-label="角色音量"]');
  const vb = await vol.boundingBox();
  await p.mouse.click(vb.x + vb.width * 0.3, vb.y + vb.height / 2);
  await p.waitForTimeout(300);
  const volVal = await vol.inputValue();
  await p.screenshot({ path: OUT + "/sound/03-volume.jpg", type: "jpeg", quality: 88 });

  // 声音测试按钮
  await p.getByRole("button", { name: "测试角色" }).click();
  await p.waitForTimeout(400);
  await p.screenshot({ path: OUT + "/sound/04-test.jpg", type: "jpeg", quality: 88 });

  // 背景音乐：选曲 → 播放
  await p.selectOption('.bgm-select', "focus");
  await p.waitForTimeout(300);
  await p.getByRole("button", { name: "播放" }).click();
  await p.waitForTimeout(600);
  await p.screenshot({ path: OUT + "/sound/05-bgm-playing.jpg", type: "jpeg", quality: 88 });

  const state = await p.evaluate(() => {
    const raw = localStorage.getItem("interview-lab-v1");
    const s = JSON.parse(raw).settings;
    return {
      textSpeed: s.textSpeed,
      inlineAction: s.inlineAction,
      fontFamily: s.fontFamily,
      totalWidth: document.querySelector('.settings-text-container') ? 'text-page' : 'sound-page',
      volumeSpeaker: s.volumeSpeaker,
      bgmTrack: s.bgmTrack,
      bgmMode: s.bgmMode,
      outputDevice: s.outputDevice,
    };
  });

  console.log(JSON.stringify({ before, speedVal, toggleBefore, toggleAfter, demoFont, volVal, state, dialogHas: dialogText.includes("导入失败"), errs }, null, 1));
  await b.close();
})();
