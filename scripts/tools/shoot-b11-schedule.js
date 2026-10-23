/**
 * b11 日程模块截图 + 实测（零依赖：直接走 Chrome DevTools Protocol）
 *
 * 项目里没有安装 playwright，这里用 Node 22 自带的 WebSocket 直连 CDP：
 * 启动 headless Chrome → 打开 /interview → 点左上角「日程」→ 五个 tab 全走一遍。
 */
const { spawn } = require("child_process");
const http = require("http");
const fs = require("fs");

const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/screenshots/b11/schedule";
const BASE = "http://127.0.0.1:5173";
const PORT = 9333;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function fetchJSON(url) {
  return new Promise((resolve, reject) => {
    http
      .get(url, (res) => {
        let data = "";
        res.on("data", (c) => (data += c));
        res.on("end", () => {
          try {
            resolve(JSON.parse(data));
          } catch (e) {
            reject(e);
          }
        });
      })
      .on("error", reject);
  });
}

class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.seq = 0;
    this.pending = new Map();
  }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((res, rej) => {
      ws.onopen = res;
      ws.onerror = (e) => rej(new Error("ws error"));
    });
    const client = new Cdp(ws);
    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id && client.pending.has(msg.id)) {
        const { resolve, reject } = client.pending.get(msg.id);
        client.pending.delete(msg.id);
        if (msg.error) reject(new Error(JSON.stringify(msg.error)));
        else resolve(msg.result);
      }
    };
    return client;
  }
  send(method, params = {}) {
    const id = ++this.seq;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const userDataDir = `${require("os").tmpdir()}/b11-cdp-profile`;
  const chrome = spawn(
    CHROME,
    [
      "--headless=new",
      `--remote-debugging-port=${PORT}`,
      `--user-data-dir=${userDataDir}`,
      "--window-size=1440,900",
      "--hide-scrollbars",
      "--disable-gpu",
      "--no-first-run",
      "--no-default-browser-check",
      "about:blank",
    ],
    { stdio: "ignore" },
  );

  let target = null;
  for (let i = 0; i < 60 && !target; i++) {
    try {
      const list = await fetchJSON(`http://127.0.0.1:${PORT}/json/list`);
      target = list.find((t) => t.type === "page");
    } catch {
      await sleep(300);
    }
  }
  if (!target) throw new Error("无法连接到 Chrome DevTools");

  const cdp = await Cdp.connect(target.webSocketDebuggerUrl);
  await cdp.send("Page.enable");
  await cdp.send("Runtime.enable");
  await cdp.send("Emulation.setDeviceMetricsOverride", {
    width: 1440,
    height: 900,
    deviceScaleFactor: 1,
    mobile: false,
  });

  const errors = [];
  cdp.ws.addEventListener("message", (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.method === "Runtime.exceptionThrown") {
      errors.push("EXCEPTION: " + JSON.stringify(msg.params.exceptionDetails.text));
    }
    if (msg.method === "Runtime.consoleAPICalled" && msg.params.type === "error") {
      errors.push("CONSOLE: " + msg.params.args.map((a) => a.value ?? a.description).join(" "));
    }
  });

  const evalJS = async (expression) => {
    const res = await cdp.send("Runtime.evaluate", {
      expression,
      returnByValue: true,
      awaitPromise: true,
    });
    if (res.exceptionDetails) throw new Error(JSON.stringify(res.exceptionDetails));
    return res.result.value;
  };

  const goto = async (path, wait = 1500) => {
    await cdp.send("Page.navigate", { url: BASE + path });
    await sleep(wait);
    for (let i = 0; i < 40; i++) {
      const ready = await evalJS("document.readyState").catch(() => "loading");
      if (ready === "complete") break;
      await sleep(200);
    }
    await sleep(wait);
  };

  const shot = async (name) => {
    await sleep(350);
    const { data } = await cdp.send("Page.captureScreenshot", {
      format: "jpeg",
      quality: 85,
      captureBeyondViewport: false,
    });
    fs.writeFileSync(`${OUT}/${name}.jpg`, Buffer.from(data, "base64"));
  };

  const clickByText = (text, scope = "button") =>
    evalJS(`(() => {
      const nodes = Array.from(document.querySelectorAll(${JSON.stringify(scope)}));
      const el = nodes.find((n) => (n.textContent || "").trim().includes(${JSON.stringify(text)}));
      if (!el) return false;
      el.click();
      return true;
    })()`);

  const header = () =>
    evalJS(`(() => {
      const h2 = document.querySelector("main h2");
      const sub = document.querySelector("main h2 + p");
      return {
        title: h2 ? h2.textContent.trim() : null,
        subtitle: sub ? sub.textContent.trim() : null,
      };
    })()`);

  const report = {};

  // 1. 干净状态进入房间（清掉旧演示数据，验证首次灌种子）
  await goto("/", 1200);
  await evalJS("localStorage.removeItem('interview-lab-schedule-v1')");
  await goto("/interview", 2500);

  const opened = await clickByText("日程");
  report.toolbarClicked = opened;
  await sleep(900);
  report.popupHasContent = await evalJS(
    "!!document.querySelector('aside') && document.body.innerText.includes('日程主题')",
  );

  report.schedule = await header();
  report.schedule.groupCards = await evalJS("document.querySelectorAll('main .group').length");
  await shot("01-schedule-groups");

  // 2. 进第一个主题 → 按日期分组
  await evalJS("document.querySelector('main .group').click()");
  await sleep(700);
  report.scheduleDetail = await header();
  report.scheduleDetail.dateGroups = await evalJS(`(() => {
    const nodes = Array.from(document.querySelectorAll("main .uppercase"));
    return nodes.map((n) => n.textContent.trim()).filter((t) => /月|今天|未定/.test(t));
  })()`);
  report.scheduleDetail.items = await evalJS("document.querySelectorAll('main .glass-effect').length");
  await shot("02-schedule-detail");

  // 3. 新增日程弹窗
  await clickByText("新建");
  await sleep(600);
  report.createModalTitle = await evalJS(`(() => {
    const h3 = document.querySelector(".z-9999 h3");
    return h3 ? h3.textContent.trim() : null;
  })()`);
  await shot("03-schedule-create-modal");

  // 3b. 真填一条日程 → 确认创建 → 看条数与存储
  const setField = (index, value) =>
    evalJS(`(() => {
      const m = document.querySelector(".z-9999");
      if (!m) return null;
      const el = m.querySelectorAll("input, textarea")[${index}];
      if (!el) return null;
      const proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, "value").set.call(el, ${JSON.stringify(value)});
      el.dispatchEvent(new Event("input", { bubbles: true }));
      return el.value;
    })()`);

  await setField(0, "手写一个线程池拒绝策略 demo");
  await setField(2, "19:30");
  await setField(3, "阿里笔试前热身，先跑通再讲参数");
  await sleep(300);
  await evalJS(`(() => {
    const m = document.querySelector(".z-9999");
    const b = m && Array.from(m.querySelectorAll("button")).find((x) => x.textContent.includes("确认创建"));
    if (b) b.click();
    return !!b;
  })()`);
  await sleep(700);
  report.createSubmit = {
    modalGone: await evalJS("!document.querySelector('.z-9999')"),
    itemsAfterAdd: await evalJS("document.querySelectorAll('main .glass-effect').length"),
    storedScheduleItems: await evalJS(`(() => {
      const st = JSON.parse(localStorage.getItem("interview-lab-schedule-v1"));
      return Object.values(st.scheduleGroups).reduce((n, g) => n + g.items.length, 0);
    })()`),
  };
  await shot("03b-schedule-created");

  // 3c. 删掉刚加的那条（走确认弹窗）→ 回到 8 条
  await evalJS(`(() => {
    const btns = Array.from(document.querySelectorAll('main button[aria-label="删除日程"]'));
    const last = btns[btns.length - 1];
    if (last) last.click();
    return btns.length;
  })()`);
  await sleep(500);
  const dialogText = await evalJS(`(() => {
    const d = document.querySelector(".z-\\\\[10001\\\\]");
    return d ? d.innerText.replace(/\\n+/g, " | ") : null;
  })()`);
  await evalJS(`(() => {
    const d = document.querySelector(".z-\\\\[10001\\\\]");
    if (!d) return false;
    const b = Array.from(d.querySelectorAll("button")).find((x) => x.textContent.trim() === "确定");
    if (b) b.click();
    return true;
  })()`);
  await sleep(700);
  report.deleteItem = {
    confirmText: dialogText,
    itemsAfterDelete: await evalJS("document.querySelectorAll('main .glass-effect').length"),
    storedScheduleItems: await evalJS(`(() => {
      const st = JSON.parse(localStorage.getItem("interview-lab-schedule-v1"));
      return Object.values(st.scheduleGroups).reduce((n, g) => n + g.items.length, 0);
    })()`),
  };

  // 4. 待办
  await clickByText("待办事项", "aside button");
  await sleep(700);
  report.todo = await header();
  report.todo.groupCards = await evalJS("document.querySelectorAll('main .group').length");
  report.todo.pendingRows = await evalJS("document.querySelectorAll('main .border-l-4').length");
  await shot("04-todo");

  const before = await evalJS("localStorage.getItem('interview-lab-schedule-v1')");
  await evalJS("document.querySelector('main .border-l-4 button').click()");
  await sleep(700);
  const after = await evalJS("localStorage.getItem('interview-lab-schedule-v1')");
  const countCompleted = (raw) => {
    const st = JSON.parse(raw);
    return Object.values(st.todoGroups).reduce(
      (n, g) => n + g.todos.filter((t) => t.completed).length,
      0,
    );
  };
  report.todo.afterCheck = {
    localStorageChanged: before !== after,
    completedBefore: countCompleted(before),
    completedAfter: countCompleted(after),
    pendingRowsNow: await evalJS("document.querySelectorAll('main .border-l-4').length"),
  };
  await shot("05-todo-checked");

  // 5. 日历
  await clickByText("重要日子", "aside button");
  await sleep(800);
  report.calendar = await header();
  const gridSig = () =>
    evalJS(`(() => {
      const cells = Array.from(document.querySelectorAll("main .day-cell"));
      const h3 = document.querySelector("main .text-brand.text-lg");
      return {
        monthTitle: h3 ? h3.textContent.trim() : null,
        cells: cells.length,
        firstCell: cells.length ? cells[0].textContent.trim().slice(0, 4) : null,
        lastCell: cells.length ? cells[cells.length - 1].textContent.trim().slice(0, 4) : null,
        activeCells: cells.filter((c) => !c.className.includes("opacity-30")).length,
        eventChips: document.querySelectorAll("main .day-cell .bg-cyan-100").length,
      };
    })()`);
  report.calendar.gridBefore = await gridSig();
  await shot("06-calendar");

  await evalJS("document.querySelector('button[aria-label=\"下一月\"]').click()");
  await sleep(600);
  report.calendar.gridAfterNextMonth = await gridSig();
  await shot("07-calendar-next-month");
  await evalJS("document.querySelector('button[aria-label=\"上一月\"]').click()");
  await sleep(500);

  await evalJS(`(() => {
    const cells = Array.from(document.querySelectorAll("main .day-cell"));
    const target = cells.find((c) => c.querySelector(".bg-cyan-100")) || cells[10];
    target.click();
    return true;
  })()`);
  await sleep(500);
  report.calendar.dayFilter = await evalJS(`(() => {
    const p = document.querySelector("main .text-xs.text-white\\\\/60");
    return p ? p.textContent.trim() : null;
  })()`);
  await shot("08-calendar-day-filter");
  await evalJS("document.querySelector('main .day-cell').click()");
  await sleep(300);

  // 6. 主动对话
  await clickByText("主动对话", "aside button");
  await sleep(800);
  report.proactive = await header();
  report.proactive.ruleCards = await evalJS("document.querySelectorAll('main select').length");
  await shot("09-proactive");

  // 6b. 打开第三条规则 + 保存 → 写进 localStorage
  const proactiveBefore = await evalJS(
    "JSON.parse(localStorage.getItem('interview-lab-schedule-v1')).proactive.rules.map(r=>r.enabled).join(',')",
  );
  // Toggle 的原生 input 是 display:none，点 label 不会翻转，直接派发 change
  await evalJS(`(() => {
    const input = document.querySelectorAll("main input[type=checkbox]")[2];
    if (!input) return false;
    input.checked = true;
    input.dispatchEvent(new Event("change", { bubbles: true }));
    return input.checked;
  })()`);
  await sleep(400);
  await evalJS(`(() => {
    const el = Array.from(document.querySelectorAll("main div")).find(
      (d) => d.childElementCount === 0 && d.textContent.trim() === "保存",
    );
    if (el) el.click();
    return !!el;
  })()`);
  await sleep(700);
  report.proactive.saveTest = {
    enabledBefore: proactiveBefore,
    enabledAfter: await evalJS(
      "JSON.parse(localStorage.getItem('interview-lab-schedule-v1')).proactive.rules.map(r=>r.enabled).join(',')",
    ),
    statusText: await evalJS(`(() => {
      const p = Array.from(document.querySelectorAll("main p")).find((n) =>
        n.textContent.includes("已保存成功"),
      );
      return p ? p.textContent.trim() : null;
    })()`),
  };
  await evalJS(`(() => {
    const sc = document.querySelector("main .custom-scrollbar");
    if (sc) sc.scrollTop = sc.scrollHeight;
    return sc ? sc.scrollTop : null;
  })()`);
  await sleep(400);
  await shot("09b-proactive-save");

  // 7. 工具调用
  await clickByText("工具调用", "aside button");
  await sleep(800);
  report.toolCalls = await header();
  report.toolCalls.records = await evalJS("document.querySelectorAll('main ul li').length");
  await shot("10-tool-calls");
  await evalJS("document.querySelector('main ul li').click()");
  await sleep(400);
  report.toolCalls.expanded = await evalJS(
    "document.querySelectorAll('main ul li pre').length",
  );
  await shot("11-tool-calls-expanded");

  // 7b. 设置页形态：把 currentSettingsTab 置为 "schedule"，验证 SettingsPanel 的
  //     tab → 组件映射能渲染出 SettingsSchedule（nav 里没有这个按钮，只能这样验）
  await goto("/settings", 1800);
  await evalJS(`(async () => {
    const mod = await import("/src/store/settingsUi.ts");
    mod.settingsUi.currentSettingsTab = "schedule";
    mod.settingsUi.showSettings = true;
    return true;
  })()`);
  await sleep(1000);
  report.settingsSchedule = {
    rendered: await evalJS("document.body.innerText.includes('日程主题')"),
    title: await evalJS(`(() => {
      const h2 = document.querySelector(".settings-panel main h2");
      return h2 ? h2.textContent.trim() : null;
    })()`),
    navHasScheduleButton: await evalJS(`(() => {
      const nav = document.querySelector(".settings-panel nav");
      return nav ? nav.innerText.includes("日程") : null;
    })()`),
  };
  await shot("12-settings-schedule");

  // 7c. 工具调用清空：回到房间重开日程 → 清空记录 → 确认弹窗
  await goto("/interview", 2500);
  await clickByText("日程");
  await sleep(900);
  await clickByText("工具调用", "aside button");
  await sleep(700);
  const beforeClear = await evalJS("document.querySelectorAll('main ul li').length");
  await clickByText("清空记录", "main button");
  await sleep(500);
  await evalJS(`(() => {
    const d = document.querySelector('[class*="10001"]');
    if (!d) return false;
    const b = Array.from(d.querySelectorAll("button")).find((x) => x.textContent.trim() === "确定");
    if (b) b.click();
    return true;
  })()`);
  await sleep(600);
  report.toolCalls.clearTest = {
    rowsBefore: beforeClear,
    rowsAfter: await evalJS("document.querySelectorAll('main ul li').length"),
    emptyHint: await evalJS(
      "document.body.innerText.includes('还没有工具调用记录，和ta聊聊需要查资料的话题试试吧')",
    ),
    storedAfter: await evalJS(
      "JSON.parse(localStorage.getItem('interview-lab-schedule-v1')).toolCalls.length",
    ),
  };
  await shot("13-tool-calls-cleared");

  // 8. 持久化统计
  report.storage = await evalJS(`(() => {
    const st = JSON.parse(localStorage.getItem("interview-lab-schedule-v1"));
    return {
      scheduleItems: Object.values(st.scheduleGroups).reduce((n, g) => n + g.items.length, 0),
      scheduleGroups: Object.keys(st.scheduleGroups).length,
      todos: Object.values(st.todoGroups).reduce((n, g) => n + g.todos.length, 0),
      completedTodos: Object.values(st.todoGroups).reduce(
        (n, g) => n + g.todos.filter((t) => t.completed).length,
        0,
      ),
      importantDays: st.importantDays.length,
      toolCalls: st.toolCalls.length,
      proactiveRules: st.proactive.rules.length,
    };
  })()`);

  // 9. 刷新后是否仍在
  await goto("/interview", 2500);
  report.storageAfterReload = await evalJS(`(() => {
    const st = JSON.parse(localStorage.getItem("interview-lab-schedule-v1"));
    return {
      completedTodos: Object.values(st.todoGroups).reduce(
        (n, g) => n + g.todos.filter((t) => t.completed).length,
        0,
      ),
      scheduleItems: Object.values(st.scheduleGroups).reduce((n, g) => n + g.items.length, 0),
    };
  })()`);

  console.log(JSON.stringify(report, null, 2));
  console.log("ERRORS", JSON.stringify(errors.slice(0, 6), null, 1));

  try {
    cdp.ws.close();
  } catch {}
  chrome.kill();
  process.exit(0);
})().catch((e) => {
  console.error("FAILED", e);
  process.exit(1);
});
