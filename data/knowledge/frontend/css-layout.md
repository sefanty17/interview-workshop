# CSS 布局：盒模型、Flex 与层叠上下文

## 盒模型与 box-sizing

CSS 盒模型由内容区（content）、内边距（padding）、边框（border）、外边距（margin）组成。

标准盒模型（box-sizing: content-box）里 width 只算内容区，padding 与 border 会额外撑大元素。IE 盒模型（border-box）里 width 包含 content + padding + border。

现代项目一律用 * { box-sizing: border-box }，因为按设计稿标注的尺寸直接写 width 更符合直觉。

margin 折叠：相邻兄弟元素的上下 margin 会取较大值而不是相加；父元素与第一个子元素的上 margin 也会折叠（父元素没有 border/padding/bfc 时），这是「子元素 margin-top 把父元素顶下去」的原因。解法是给父元素加 overflow: hidden 或 display: flow-root 建立 BFC。

负 margin 可以让元素重叠，margin: auto 只能水平居中（块级元素且宽度固定），垂直居中需要配合绝对定位或 flex。

## BFC 与层叠上下文

BFC（块级格式化上下文）是一个独立的渲染区域，内部布局不影响外部。触发方式：根元素、float 不为 none、position 为 absolute/fixed、display 为 inline-block/table-cell/flow-root、overflow 不为 visible、flex/grid 的子项。

BFC 的三个实用价值：包含浮动（清除浮动）、阻止 margin 折叠、阻止元素被浮动元素覆盖（两栏自适应布局）。

层叠上下文（stacking context）决定 z-index 的比较范围。触发条件：根元素、position 加 z-index 不为 auto、opacity 小于 1、transform/filter/perspective 不为 none、will-change 指定了上述属性、isolation: isolate。

关键结论：z-index 只在同一个层叠上下文里比较。父元素有了 transform，子元素的 z-index 再大也跳不出父元素的层级。这是「弹窗被遮住」类 bug 的常见原因。

同一上下文内的层叠顺序（从低到高）：背景与边框 → 负 z-index → 块级盒 → 浮动盒 → 行内盒 → z-index: 0/auto → 正 z-index。

## Flex 布局的常见坑

flex: 1 是 flex-grow:1、flex-shrink:1、flex-basis:0% 的简写。basis 为 0 意味着宽度完全由剩余空间分配决定。

如果写 flex: auto，等价于 flex-grow:1、flex-shrink:1、flex-basis:auto，宽度先按内容算再分配剩余空间，两者视觉差异很大。

子元素被压缩到比内容还小：因为 flex-shrink 默认是 1。要防止压缩设 flex-shrink: 0，或者给 min-width: 0（这是文本省略号失效的根因——flex 子项默认 min-width 是 auto，不会小于内容宽度）。

文本溢出省略三件套：overflow: hidden、text-overflow: ellipsis、white-space: nowrap，并且父级要有确定的宽度。在 flex 子项里还要加 min-width: 0。

align-items 控制交叉轴对齐，justify-content 控制主轴对齐。主轴方向由 flex-direction 决定，row 时主轴是水平，column 时主轴是垂直，所以两个属性的作用方向会互换。

gap 在 flex 里已经全浏览器支持，比用 margin 处理间距干净得多。

## 响应式与单位选择

px 是绝对单位。em 相对于当前元素的 font-size（用在 font-size 上时相对于父元素）。rem 相对于根元素 html 的 font-size。

vw/vh 相对于视口，1vw 等于视口宽度的 1%。移动端适配常用 rem + 动态设置 html font-size，或者直接用 vw。

dvh 解决移动端浏览器地址栏导致的 100vh 溢出问题（100vh 包含地址栏高度，dvh 是动态视口高度）。

媒体查询断点应该按内容断而不是按设备断：先写移动端样式，再用 min-width 逐级增强。

container queries（容器查询）让组件按父容器宽度响应，而不是按视口，适合做可复用组件。主流浏览器已支持。
