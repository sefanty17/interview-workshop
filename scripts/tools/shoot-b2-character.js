const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const OUT = "D:/cxdownload/javaee课设/screenshots/b2/character";
const fs = require("fs");

const sizes = [
  { w: 1280, h: 820, tag: "1280" },
  { w: 1440, h: 900, tag: "1440" },
];

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const b = await chromium.launch({
    executablePath: CHROME,
    headless: true,
    // 本机环境变量里挂着 HTTP_PROXY，会把 127.0.0.1 的 dev server 也代理掉
    args: ["--no-proxy-server"],
  });
  const allErrors = [];

  for (const s of sizes) {
    const p = await b.newPage({ viewport: { width: s.w, height: s.h }, deviceScaleFactor: 1 });
    const errs = [];
    p.on("pageerror", (e) => errs.push(String(e)));
    p.on("console", (m) => {
      if (m.type() === "error") errs.push("console:" + m.text());
    });

    await p.goto("http://127.0.0.1:5173/", { waitUntil: "networkidle" });
    await p.evaluate(() => localStorage.clear());
    await p.goto("http://127.0.0.1:5173/settings", { waitUntil: "networkidle" });
    await p.waitForTimeout(1200);

    // 1. 默认态
    await p.screenshot({ path: `${OUT}/${s.tag}-01-default.png` });

    // 2. hover 第一张卡（收藏星出现 + 上浮）
    const card = p.locator(".group.relative").first();
    await card.hover();
    await p.waitForTimeout(500);
    await p.screenshot({ path: `${OUT}/${s.tag}-02-hover.png` });

    // 3. 点「选择」→ 已选中
    const firstCard = p.locator(".group.relative").first();
    await firstCard.getByRole("button", { name: "选择", exact: true }).click();
    await p.waitForTimeout(700);
    await p.screenshot({ path: `${OUT}/${s.tag}-03-selected.png` });

    // 4. 详情弹窗
    await firstCard.getByRole("button", { name: "详情", exact: true }).click();
    await p.waitForTimeout(600);
    await p.screenshot({ path: `${OUT}/${s.tag}-04-detail.png` });
    await p.keyboard.press("Escape");
    await p.locator("body").click({ position: { x: 5, y: 5 } });
    await p.waitForTimeout(400);

    // 5. 齿轮设置弹窗
    await firstCard.locator('button[title="角色设置"]').click();
    await p.waitForTimeout(600);
    await p.screenshot({ path: `${OUT}/${s.tag}-05-settings.png` });
    await p.keyboard.press("Escape");
    await p.locator("body").click({ position: { x: 5, y: 5 } });
    await p.waitForTimeout(400);

    // 6. 新建卡弹窗
    await p.getByRole("button", { name: /新建角色/ }).click();
    await p.waitForTimeout(600);
    await p.screenshot({ path: `${OUT}/${s.tag}-06-new.png` });
    await p.keyboard.press("Escape");
    await p.waitForTimeout(300);

    // 7. 滚到底部看「我的身份」
    await p.evaluate(() => {
      const scroller = document.querySelector(".lc-reset-radius");
      if (scroller) scroller.scrollTop = scroller.scrollHeight;
    });
    await p.waitForTimeout(400);
    await p.screenshot({ path: `${OUT}/${s.tag}-07-identity.png` });
    await p.evaluate(() => {
      const scroller = document.querySelector(".lc-reset-radius");
      if (scroller) scroller.scrollTop = 0;
    });

    // 8. 度量：卡片 / 头像 / 短横线 / 按钮 / 间距 / 菜单项
    const metrics = await p.evaluate(() => {
      const px = (v) => Math.round(parseFloat(v) * 100) / 100;
      const cs = (el, ...props) => {
        if (!el) return null;
        const s = getComputedStyle(el);
        return Object.fromEntries(props.map((k) => [k, s[k]]));
      };
      const card = document.querySelector(".group.relative");
      const cr = card?.getBoundingClientRect();
      const left = card?.children[3];
      const avatarHost = card?.querySelector("img")?.parentElement;
      const ar = avatarHost?.getBoundingClientRect();
      const dash = avatarHost?.nextElementSibling;
      const dr = dash?.getBoundingClientRect();
      const h4 = card?.querySelector("h4");
      const btns = card ? Array.from(card.querySelectorAll("button")) : [];
      const sizeBtns = btns.filter((b) =>
        ["详情", "选择", "加入", "已选中", "✓ 已选中", "✓ 已弃", "已在场"].some((t) =>
          b.textContent.trim().includes(t.replace("✓ ", "")),
        ),
      );
      const btnBox = sizeBtns.map((b) => {
        const r = b.getBoundingClientRect();
        return {
          text: b.textContent.trim(),
          w: px(r.width),
          h: px(r.height),
          radius: getComputedStyle(b).borderRadius,
          fontSize: getComputedStyle(b).fontSize,
          bg: getComputedStyle(b).backgroundColor,
          color: getComputedStyle(b).color,
        };
      });
      // 卡片间距：第一行两张卡的 left 差值 - 卡宽
      const cards = Array.from(document.querySelectorAll(".group.relative")).map((c) =>
        c.getBoundingClientRect(),
      );
      const gapX =
        cards.length > 1 ? px(cards[1].left - (cards[0].left + cards[0].width)) : null;
      const gapY = cards.length > 2 ? px(cards[2].top - (cards[0].top + cards[0].height)) : null;
      const menu = document.querySelector("section.menu-item");
      const mcs = menu ? getComputedStyle(menu) : null;
      const menuTitle = menu?.querySelector("h4");
      const titleBar = menu?.firstElementChild;
      return {
        card: cr && {
          w: px(cr.width),
          h: px(cr.height),
          radius: getComputedStyle(card).borderRadius,
          borderColor: getComputedStyle(card).borderColor,
          borderWidth: getComputedStyle(card).borderWidth,
          bg: getComputedStyle(card).backgroundColor,
          padding: getComputedStyle(card).padding,
          backdrop: getComputedStyle(card).backdropFilter,
        },
        avatar: ar && {
          w: px(ar.width),
          h: px(ar.height),
          radius: avatarHost ? getComputedStyle(avatarHost).borderRadius : null,
          border: avatarHost ? getComputedStyle(avatarHost).borderColor : null,
          borderWidth: avatarHost ? getComputedStyle(avatarHost).borderWidth : null,
        },
        dash: dr && {
          w: px(dr.width),
          h: px(dr.height),
          bg: dash ? getComputedStyle(dash).backgroundColor : null,
        },
        name: h4 && {
          text: h4.textContent.trim(),
          fontSize: getComputedStyle(h4).fontSize,
          fontWeight: getComputedStyle(h4).fontWeight,
          top: px(h4.getBoundingClientRect().top - cr.top),
        },
        subRow: (() => {
          const small = card?.querySelector(".text-brand.text-sm");
          const big = card?.querySelector(".text-brand.text-xl");
          return {
            small: small && {
              fontSize: getComputedStyle(small).fontSize,
              color: getComputedStyle(small).color,
              text: small.textContent.trim(),
            },
            big: big && {
              fontSize: getComputedStyle(big).fontSize,
              color: getComputedStyle(big).color,
              text: big.textContent.trim(),
            },
          };
        })(),
        desc: (() => {
          const p = card?.querySelector("p.line-clamp-3");
          if (!p) return null;
          const s = getComputedStyle(p);
          const lineH = parseFloat(s.lineHeight);
          return {
            fontSize: s.fontSize,
            lineHeight: s.lineHeight,
            color: s.color,
            opacity: s.opacity,
            lines: Math.round(p.getBoundingClientRect().height / lineH),
            clamp: s.webkitLineClamp,
          };
        })(),
        buttons: btnBox,
        gapX,
        gapY,
        catRotate: (() => {
          const cat = card?.children[0];
          return cat ? getComputedStyle(cat).transform : null;
        })(),
        menu: mcs && {
          radius: mcs.borderRadius,
          padding: mcs.padding,
          bg: mcs.backgroundColor,
          width: px(menu.getBoundingClientRect().width),
          titleBarBorder: titleBar ? getComputedStyle(titleBar).borderBottomColor : null,
          titleFont: menuTitle ? getComputedStyle(menuTitle).fontSize : null,
          titleWeight: menuTitle ? getComputedStyle(menuTitle).fontWeight : null,
        },
        grid: (() => {
          const g = document.querySelector(".grid.w-full");
          return g ? { gap: getComputedStyle(g).gap, padding: getComputedStyle(g).padding } : null;
        })(),
      };
    });
    console.log(`--- ${s.tag} metrics ---`);
    console.log(JSON.stringify(metrics, null, 1));

    allErrors.push({ tag: s.tag, errs });
    await p.close();
  }
  console.log("errors:", JSON.stringify(allErrors, null, 1));
  await b.close();
})();
