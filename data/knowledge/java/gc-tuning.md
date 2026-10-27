# 垃圾回收器与调优

## 分代回收的基本假设

GC 的理论基础是弱分代假说：绝大多数对象朝生夕灭，少数对象能活很久。所以堆按年龄分代，新生代用复制算法（只复制少量存活对象，成本低），老年代用标记-清除或标记-整理（存活率高，复制不划算）。

新生代分 Eden 与两块 Survivor（默认比例 8:1:1），可用空间是 Eden + 一块 Survivor，另一块空着作为复制目标。所以新生代回收一次能腾出约 90% 空间。

Stop-The-World 是所有 GC 都无法完全避免的：GC 期间要么移动对象（需要暂停应用线程保证引用一致），要么并发标记（用读屏障/写屏障记录变化）。调优的核心目标不是「消灭 STW」，而是「把单次停顿压到业务可接受范围内」。

## 主流回收器对比

Serial：单线程复制，客户端模式默认。简单高效，GC 时全程 STW。

ParNew：Serial 的多线程版，常与 CMS 搭配。

Parallel Scavenge / Parallel Old：吞吐量优先，JDK 8 服务端默认。关注的是「应用运行时间 /（应用运行时间 + GC 时间）」，适合后台批处理，不适合交互式服务。

CMS（Concurrent Mark Sweep）：以最短停顿为目标，标记阶段与用户线程并发，只有初始标记与重新标记需要 STW。缺点是使用标记-清除会产生碎片，并发阶段占用 CPU 且可能触发并发模式失败（Concurrent Mode Failure）导致退化成单线程 Full GC。JDK 9 起废弃，14 移除。

G1（Garbage First）：把堆分成大小相等的 Region（1~32MB），按「回收收益」优先回收垃圾最多的 Region。用 Remembered Set 记录跨 Region 引用，用 SATB（Snapshot At The Beginning）做并发标记。可以设定目标停顿时间 -XX:MaxGCPauseMillis，默认 200ms。JDK 9 起是默认回收器，适合大堆（4GB 以上）。

ZGC：着色指针 + 读屏障，标记、转移、重定位几乎全并发，停顿稳定在 1ms 以内，与堆大小基本无关。JDK 11 实验、15 转正、21 起支持分代。适合超大堆与极低延迟场景。

Shenandoah：与 ZGC 目标类似，用 Brooks 转发指针，停顿与堆大小无关，OpenJDK 提供。

## 关键参数与调优思路

-Xms 与 -Xmx 设成一样，避免堆动态扩缩容带来的抖动。-Xmn 或 -XX:NewRatio 控制新生代大小，新生代太小会导致对象提前晋升、老年代压力大。

-XX:SurvivorRatio 控制 Eden 与单个 Survivor 的比例，默认 8。-XX:MaxTenuringThreshold 控制晋升年龄。-XX:+HeapDumpOnOutOfMemoryError 与 -XX:HeapDumpPath 线上必开，出事时留下现场。

调优的顺序应该是：先明确目标（吞吐优先还是延迟优先）→ 选回收器 → 定堆大小 → 看 GC 日志调比例 → 最后才考虑换收集器或改代码。跳过目标直接调参数是浪费时间。

判断有没有问题的三个指标：GC 频率（多久一次）、单次停顿（多长）、GC 后老年代占用（是否持续上涨）。第三项持续上涨说明有内存泄漏，调参数救不了。

## GC 日志怎么读

JDK 9 之后统一用 -Xlog:gc*:file=gc.log:time,uptime:filecount=5,filesize=50M。

一行典型的 G1 日志包含：时间戳、回收类型（Young/Mixed/Full）、停顿时长、回收前后各 Region 的占用。

要盯的是 Mixed GC 的触发频率与老年代回收比例。如果 Mixed GC 越来越频繁但老年代占用一直降不下来，说明存活对象太多，不是垃圾回收能解决的问题，要回到对象生命周期去找。

Full GC 在 G1 里是「最后的兜底」，一旦出现说明前面的策略失效（转移失败、大对象分配失败等），必须查原因，不要只看到它偶发就忽略。
