const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/screenshots/b6/llm";
const fs = require("fs");
fs.mkdirSync(OUT, { recursive: true });

const readProviders = () =>
  JSON.parse(localStorage.getItem("interview-lab-v1") || "{}")?.settings?.llmProviders ?? [];

(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
  const p = await ctx.newPage();
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e)));
  p.on("console", (m) => {
    if (m.type() === "error") errs.push("CONSOLE: " + m.text());
  });

  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.waitForTimeout(1200);

  // 高级设置 → 大模型管理（已接入，直接是本页）
  await p.getByRole("button", { name: "高级设置" }).click();
  await p.waitForTimeout(600);
  await p.getByRole("button", { name: "大模型管理", exact: true }).click();
  await p.waitForTimeout(800);

  // 01 默认态
  await p.screenshot({ path: OUT + "/01-default.jpg", type: "jpeg", quality: 82 });

  const form = p.locator("form");

  // 打开编辑面板
  await p.getByRole("button", { name: "编辑" }).first().click();
  await p.waitForTimeout(600);
  await p.screenshot({ path: OUT + "/02-edit-panel.jpg", type: "jpeg", quality: 82 });

  // 02b 切到 LM Studio：验证 base_url / api_key 自动填充联动
  await p.locator("form select").first().selectOption("lmstudio");
  await p.waitForTimeout(400);
  const lm = await p.evaluate(() => {
    const inputs = [...document.querySelectorAll("form input")];
    const val = (el) => (el ? el.value : null);
    // 顺序：名称 / 模型 / API 密钥 / API 地址 / Temperature / Top P ...
    return {
      model: val(inputs[1]),
      apiKey: val(inputs[2]),
      baseUrl: val(inputs[3]),
    };
  });
  await p.screenshot({ path: OUT + "/03-provider-lmstudio.jpg", type: "jpeg", quality: 82 });

  // 03 切到 OpenAI Codex：API 密钥/地址隐藏，出现订阅登录区 + Fast Mode
  await p.locator("form select").first().selectOption("codex");
  await p.waitForTimeout(400);
  await p.locator("form").evaluate((f) => f.scrollTo(0, 99999));
  await p.waitForTimeout(300);
  await p.screenshot({ path: OUT + "/04-provider-codex.jpg", type: "jpeg", quality: 82 });
  const codexVisible = await p.getByText("ChatGPT 订阅账号").isVisible();

  // 04 切回 OpenAI 兼容：识图开关 → 压缩参数联动
  await p.locator("form select").first().selectOption("openai");
  await p.waitForTimeout(300);
  const visionSwitch = p.getByText("支持识图（原生多模态）").locator("xpath=../..").locator("button");
  await visionSwitch.click();
  await p.waitForTimeout(300);
  await p.getByText("识图时压缩图片").locator("xpath=../..").locator("button").click();
  await p.waitForTimeout(300);
  await p.locator("form").evaluate((f) => f.scrollTo(0, 99999));
  await p.waitForTimeout(300);
  await p.screenshot({ path: OUT + "/05-vision-compress.jpg", type: "jpeg", quality: 82 });
  const maxEdgeVisible = await p.getByText("最大边长（像素）").isVisible();

  // 05 新增并保存一个「智谱 GLM」服务商（预设 + 保存真实落 localStorage）
  await p.getByRole("button", { name: "取消" }).click();
  await p.waitForTimeout(400);
  await p.getByRole("button", { name: "+ 添加模型" }).click();
  await p.waitForTimeout(400);
  await p.getByRole("button", { name: "智谱 GLM", exact: true }).click();
  await p.waitForTimeout(300);
  await p.getByRole("button", { name: "保存", exact: true }).click();
  await p.waitForTimeout(600);
  const afterAdd = await p.evaluate(readProviders);
  await p.screenshot({ path: OUT + "/06-after-add.jpg", type: "jpeg", quality: 82 });

  // 06 角色下拉切换：视觉模型指向新增的服务商
  await p.locator(".grid.grid-cols-2 select").nth(3).selectOption(afterAdd[1].id);
  await p.waitForTimeout(400);
  const roles = await p.evaluate(() => {
    const s = JSON.parse(localStorage.getItem("interview-lab-v1")).settings;
    return {
      chat: s.llmChatProviderId,
      vision: s.llmVisionProviderId,
    };
  });
  await p.screenshot({ path: OUT + "/07-role-vision.jpg", type: "jpeg", quality: 82 });

  // 07 测试连接面板 → 需要后端服务弹窗
  await p.getByRole("button", { name: "测试" }).first().click();
  await p.waitForTimeout(600);
  await p.screenshot({ path: OUT + "/08-test-panel.jpg", type: "jpeg", quality: 82 });
  await p.locator('input[placeholder="输入测试消息..."]').fill("你好，做个自我介绍");
  await p.waitForTimeout(200);
  await p.getByRole("button", { name: "发送" }).click();
  await p.waitForTimeout(900);
  await p.screenshot({ path: OUT + "/09-test-need-backend.jpg", type: "jpeg", quality: 82 });
  const dialogText = await p.locator(".lc-modal, [class*=modal], body").first().innerText();
  await p.getByRole("button", { name: "确定" }).click().catch(() => {});
  await p.waitForTimeout(400);

  // 08 删除二次确认弹窗
  await p.getByRole("button", { name: "删除" }).nth(1).click();
  await p.waitForTimeout(500);
  await p.screenshot({ path: OUT + "/10-delete-confirm.jpg", type: "jpeg", quality: 82 });
  await p.getByRole("button", { name: "取消" }).click();
  await p.waitForTimeout(400);

  // 09 滚到底全页
  await p.evaluate(() => {
    document.querySelectorAll("*").forEach((el) => {
      if (el.scrollHeight > el.clientHeight + 4 && el.clientHeight > 200) {
        el.scrollTop = el.scrollHeight;
      }
    });
  });
  await p.waitForTimeout(500);
  await p.screenshot({ path: OUT + "/11-full-page-bottom.jpg", type: "jpeg", quality: 82 });

  // 10 清空后的空态
  await p.evaluate(() => {
    const raw = JSON.parse(localStorage.getItem("interview-lab-v1"));
    raw.settings.llmProviders = [];
    raw.settings.llmChatProviderId = null;
    localStorage.setItem("interview-lab-v1", JSON.stringify(raw));
  });
  await p.reload({ waitUntil: "networkidle" });
  await p.waitForTimeout(1000);
  await p.getByRole("button", { name: "高级设置" }).click();
  await p.waitForTimeout(500);
  await p.getByRole("button", { name: "大模型管理", exact: true }).click();
  await p.waitForTimeout(800);
  await p.screenshot({ path: OUT + "/12-empty.jpg", type: "jpeg", quality: 82 });

  console.log(
    JSON.stringify(
      {
        lmstudioFilled: lm,
        codexVisible,
        maxEdgeVisible,
        providerCountAfterAdd: afterAdd.length,
        addedLabel: afterAdd[1]?.label,
        roles,
        dialogHasNeedBackend: /需要后端服务/.test(dialogText),
        errors: errs,
      },
      null,
      1,
    ),
  );
  await b.close();
})();
