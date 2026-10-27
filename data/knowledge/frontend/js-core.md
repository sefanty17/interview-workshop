# JavaScript 核心：事件循环、闭包与 this

## 事件循环与任务队列

JS 是单线程的，靠事件循环调度。一次循环叫一个 tick：先清空调用栈，然后执行所有微任务（microtask），再取一个宏任务（macrotask）执行，渲染，进入下一轮。

宏任务包括：setTimeout、setInterval、setImmediate（Node）、I/O、UI 事件回调、MessageChannel。

微任务包括：Promise.then/catch/finally、queueMicrotask、MutationObserver、await 之后的代码。

关键结论：微任务在当前 tick 内全部执行完，宏任务每次只取一个。所以 Promise 的 then 会插队到下一个 setTimeout 之前。

await 的本质：await 后面的代码等价于注册在 Promise.then 上的回调，属于微任务。所以 async 函数里 await 之前的代码是同步执行的，这一点常被搞错。

Node.js 的事件循环分六个阶段：timers、pending callbacks、idle/prepare、poll、check、close callbacks。每个阶段之间清空微任务队列。process.nextTick 的优先级高于 Promise 微任务。

常见的输出顺序题套路：同步代码 → process.nextTick → Promise 微任务 → setTimeout 0 → setImmediate（在 I/O 回调里顺序不确定）。

## 闭包与作用域链

闭包指函数与其词法环境的组合。内部函数引用了外部函数的变量，即使外部函数已经返回，那些变量依然不会被回收，因为内部函数还持有对它们的引用。

用途：模块化（私有变量）、函数工厂、防抖节流、缓存记忆（memoize）、循环里绑定正确的变量。

经典陷阱：for (var i = 0; i < 3; i++) { setTimeout(() => console.log(i)) } 输出三个 3。因为 var 是函数作用域，三个回调共享同一个 i。改成 let 就输出 0 1 2，因为 let 是块级作用域，每轮迭代生成新的绑定。

内存角度：闭包持有的变量无法被 GC，滥用会造成内存泄漏。尤其是闭包里引用了 DOM 节点又没有及时解除引用。

## this 的判定规则

优先级从高到低：new 绑定 > 显式绑定（call/apply/bind）> 隐式绑定（obj.fn()）> 默认绑定（严格模式下 undefined，非严格下全局对象）。

箭头函数没有自己的 this，它的 this 来自定义时所在的词法作用域，且无法被 call/apply/bind 改变。所以箭头函数不能做构造函数，也没有 arguments（用剩余参数代替）。

隐式绑定丢失：const fn = obj.method; fn() 调用时 this 不再是 obj，而是 undefined 或全局。这是回调里 this 错乱的根本原因。

## 原型链与继承

每个对象有内部属性 [[Prototype]] 指向它的原型对象，通过 Object.getPrototypeOf 访问，__proto__ 是历史遗留的访问器。

构造函数有 prototype 属性，指向原型对象；原型对象有 constructor 属性指回构造函数。实例的 __proto__ 指向构造函数的 prototype。

属性查找：先在自身找，找不到沿原型链往上，直到 Object.prototype，最后是 null。

ES6 的 class 本质是语法糖，extends 建立原型链关系，super 调用父类。class 声明不会提升（存在暂时性死区），且类内部默认严格模式。

instanceof 判断的是原型链上是否存在某个构造函数的 prototype。typeof null 返回 "object" 是历史 bug。
