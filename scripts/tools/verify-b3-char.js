const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/screenshots/b3/character";
const fs = require("fs");
if (!fs.existsSync(OUT)) fs.mkdirSync(OUT, { recursive: true });

(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const errs = [];
  p.on("pageerror", (e) => errs.push(String(e)));
  await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
  await p.waitForTimeout(1200);

  const m = await p.evaluate(() => {
    const cards = Array.from(document.querySelectorAll("div.group"));
    const first = cards[0];
    const imgWrap = first?.querySelector("img")?.parentElement;
    const dash = imgWrap?.nextElementSibling;
    const leftName = first?.querySelector("h4");
    const btnBoxes = first ? Array.from(first.querySelectorAll("button")) : [];
    const rightCol = first?.lastElementChild;
    const titleRow = rightCol?.firstElementChild?.firstElementChild;

    const rect = (el) => {
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { w: Math.round(r.width), h: Math.round(r.height), x: Math.round(r.x), y: Math.round(r.y) };
    };
    const btn = (t) =>
      btnBoxes
        .filter((x) => x.textContent.trim() === t || x.textContent.trim().endsWith(t))
        .map((x) => {
          const cs = getComputedStyle(x);
          const r = x.getBoundingClientRect();
          return {
            text: x.textContent.trim(),
            h: Math.round(r.height),
            w: Math.round(r.width),
            radius: cs.borderRadius,
            bg: cs.backgroundColor,
            color: cs.color,
            border: cs.borderColor,
            fs: cs.fontSize,
          };
        })[0] ?? null;

    const grid = cards[0]?.parentElement;
    const newCard = grid ? Array.from(grid.children).find((c) => c.tagName === "BUTTON") : null;

    return {
      card: rect(first),
      cardRadius: first && getComputedStyle(first).borderRadius,
      avatar: rect(imgWrap),
      avatarRadius: imgWrap && getComputedStyle(imgWrap).borderRadius,
      avatarBorder: imgWrap && getComputedStyle(imgWrap).borderColor,
      avatarBorderWidth: imgWrap && getComputedStyle(imgWrap).borderWidth,
      dash: dash && {
        ...rect(dash),
        bg: getComputedStyle(dash).backgroundColor,
        cls: dash.className,
      },
      leftName: leftName && {
        ...rect(leftName),
        fs: getComputedStyle(leftName).fontSize,
        fw: getComputedStyle(leftName).fontWeight,
        textAlign: getComputedStyle(leftName).textAlign,
        lineClamp: getComputedStyle(leftName).webkitLineClamp,
        text: leftName.textContent.trim().replace(/\s+/g, " "),
      },
      titleRowHtml: titleRow && titleRow.innerHTML.replace(/\s+/g, " ").slice(0, 200),
      titleRowBox: rect(titleRow),
      titleParts: titleRow
        ? Array.from(titleRow.children).map((c) => ({
            text: c.textContent.trim(),
            fs: getComputedStyle(c).fontSize,
            lh: getComputedStyle(c).lineHeight,
            fw: getComputedStyle(c).fontWeight,
            color: getComputedStyle(c).color,
          }))
        : null,
      desc: (() => {
        const d = rightCol?.querySelector("p");
        if (!d) return null;
        const cs = getComputedStyle(d);
        return { ...rect(d), fs: cs.fontSize, lh: cs.lineHeight, clamp: cs.webkitLineClamp, color: cs.color };
      })(),
      btns: ["详情", "加入", "选择", "已弃", "已选中", "已在场"]
        .map((t) => btn(t))
        .filter(Boolean),
      grid: grid && {
        cls: grid.className,
        cols: getComputedStyle(grid).gridTemplateColumns,
        rows: getComputedStyle(grid).gridTemplateRows,
        childCount: grid.children.length,
        childBoxes: Array.from(grid.children).map((c) => rect(c)),
      },
      newCard: newCard && {
        ...rect(newCard),
        radius: getComputedStyle(newCard).borderRadius,
        border: getComputedStyle(newCard).border,
        cls: newCard.className,
      },
    };
  });
  console.log(JSON.stringify(m, null, 1));
  console.log("errors:", errs);
  await p.screenshot({ path: OUT + "/1440-b3-default.png" });
  await b.close();
})();
