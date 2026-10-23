const { chromium } = require("playwright-core");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  for (const n of ["role-stage-single", "role-stage-dual"]) {
    const p = await b.newPage({ viewport: { width: 1280, height: 720 } });
    await p.goto("file:///D:/cxdownload/javaee课设/screenshots/" + n + ".png");
    await p.waitForTimeout(400);
    await p.evaluate(() => {
      const img = document.querySelector("img");
      if (img) { img.style.width = "100%"; img.style.height = "auto"; }
      document.body.style.margin = "0";
    });
    await p.screenshot({ path: "D:/cxdownload/javaee课设/screenshots/" + n + "-small.jpg", type: "jpeg", quality: 82 });
    await p.close();
  }
  await b.close();
})();
