# 浏览器渲染与 HTTP 缓存

## 从输入 URL 到页面出现

大致流程：URL 解析 → DNS 查询（浏览器缓存 → 系统缓存 → hosts → 递归 DNS）→ 建立 TCP 连接（三次握手，HTTPS 还要 TLS 握手）→ 发 HTTP 请求 → 服务端响应 → 浏览器解析 HTML 构建 DOM 树。

遇到 CSS 阻塞渲染：浏览器会等 CSSOM 构建完才渲染，因为要计算样式。所以 CSS 放在 head 里，JS 放在 body 末尾或用 defer/async。

defer 与 async 的区别：两者都是并行下载不阻塞解析；defer 保证按顺序在 DOMContentLoaded 之前执行，async 下载完立刻执行、顺序不保证。模块脚本（type="module"）默认是 defer 行为。

解析 CSS 构建 CSSOM → DOM + CSSOM 合成渲染树（Render Tree，不含 display: none 的元素）→ 布局（Layout/Reflow，计算几何位置）→ 绘制（Paint）→ 合成（Composite，分成图层交给 GPU）。

## 重排、重绘与合成

重排（reflow）：元素的几何属性变化（宽高、位置、字体大小、增删节点），浏览器要重新计算布局。代价最高。

重绘（repaint）：只改外观不改布局（颜色、背景、阴影、visibility）。代价中等。

合成（composite）：只改 transform 或 opacity，浏览器可以跳过布局与绘制，直接在新的图层上做变换交给 GPU。代价最低。

所以动画优先用 transform 与 opacity。改 top/left 会触发重排，改 transform: translate 只走合成。

注意：transform 会创建层叠上下文，也会触发图层的提升。图层过多会消耗内存，不是越多越好（will-change 滥用是性能问题）。

批量读写避免布局抖动（layout thrashing）：不要在循环里交错读 offsetWidth 和写 style。先全部读，再全部写，或者用 requestAnimationFrame 分隔。

## HTTP 缓存机制

强缓存：Cache-Control（max-age=秒、no-cache、no-store、public/private、immutable）、Expires（绝对时间，已被 Cache-Control 取代）。

no-cache 不是不缓存，而是每次都要协商验证；no-store 才是完全不存。

协商缓存：ETag/If-None-Match（内容指纹），Last-Modified/If-Modified-Since（时间戳）。都命中返回 304，不传 body。ETag 精度更高，优先级也更高。

前端工程化实践：HTML 用 no-cache（每次都验证，保证拿到新版本），JS/CSS 用 hash 文件名 + max-age=31536000 一年的强缓存，文件内容变了 hash 就变，URL 变成新资源。

Service Worker 可以完全接管缓存策略，但要注意 SW 文件本身不能被长时间缓存，否则更新不生效。

## 跨域与安全

同源策略要求协议、域名、端口三者完全一致。跨域请求会被浏览器拦截响应（注意：请求其实发出去了，只是浏览器不给 JS 读）。

CORS 的关键响应头：Access-Control-Allow-Origin、Allow-Methods、Allow-Headers、Allow-Credentials。带 cookie 时 Allow-Origin 不能是 *，必须是具体域名，且前端要设 credentials: 'include'。

简单请求直接发，非简单请求（自定义头、非 GET/POST/HEAD、Content-Type 不是那三种）先发 OPTIONS 预检。

代理方案：开发时用 vite/webpack 的 proxy 转发到自己后端，绕开跨域（同源）。生产用 Nginx 同域部署。

XSS：把用户输入当 HTML 执行。存储型（入库）、反射型（URL 参数）、DOM 型（前端拼接）。防御：输出转义、用 textContent 而不是 innerHTML、CSP 限制脚本来源、HttpOnly cookie。

CSRF：诱导用户在已登录状态下发请求。防御：SameSite cookie、CSRF token、校验 Referer/Origin。

点击劫持：用 iframe 套你的页面。防御：X-Frame-Options: DENY 或 CSP 的 frame-ancestors。
