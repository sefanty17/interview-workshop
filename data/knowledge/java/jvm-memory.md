# Java 虚拟机内存与对象生命周期

## 运行时内存区域划分

JVM 在启动时把内存划成几块，按《Java 虚拟机规范》分为线程私有与线程共享两大类。

线程私有的三块：程序计数器（PC Register）、虚拟机栈（VM Stack）、本地方法栈（Native Method Stack）。程序计数器记录当前线程执行到哪条字节码，是唯一不会抛 OutOfMemoryError 的区域。虚拟机栈按栈帧存放局部变量表、操作数栈、动态链接、方法返回地址，栈深度超过限制抛 StackOverflowError，扩展失败抛 OutOfMemoryError。

线程共享的两块：堆（Heap）与方法区（Method Area，Java 8 之后由元空间 Metaspace 实现，位于本地内存）。堆存放对象实例与数组，是 GC 的主战场；方法区存放类元信息、常量、静态变量。

还有一块不属于规范强制、但实际存在的直接内存（Direct Memory），由 NIO 的 ByteBuffer.allocateDirect 使用，不受堆大小限制，但受 -XX:MaxDirectMemorySize 约束。

常见误解：把「堆外内存泄漏」当成 JVM bug。多数情况是自己用 Unsafe 或 DirectByteBuffer 分配后没释放，堆 dump 里看不出来，要用 Native Memory Tracking（-XX:NativeMemoryTracking=summary + jcmd VM.native_memory）才看得到。

## 对象在堆中的分配与流转

新对象优先在 Eden 区分配。Eden 满了触发 Minor GC，存活对象复制到 Survivor 区（S0/S1 交替），每熬过一次 GC 年龄加一，默认到 15 岁晋升老年代（-XX:MaxTenuringThreshold）。

大对象直接进老年代（-XX:PretenureSizeThreshold），避免在 Eden 与 Survivor 之间反复复制。长期存活的对象也会通过「动态年龄判定」提前晋升：Survivor 中同龄对象总和超过一半，年龄大于等于该年龄的对象直接进老年代。

TLAB（Thread Local Allocation Buffer）是 Eden 里为每个线程预留的小块，对象优先在 TLAB 内分配，避免多线程竞争指针。TLAB 用完才走共享的 Eden。

对象在内存中的布局分三部分：对象头（Mark Word 存哈希码、GC 分代年龄、锁状态标志；Klass Pointer 指向类元数据）、实例数据、对齐填充（保证 8 字节对齐）。

## 引用类型与可达性

Java 有四种引用，强度从高到低：强引用、软引用（SoftReference）、弱引用（WeakReference）、虚引用（PhantomReference）。

强引用只要可达就绝不回收。软引用在内存不足时回收，适合做缓存。弱引用在下一次 GC 必定回收，ThreadLocalMap 的 key 就是弱引用，用来避免 Entry 无法释放。虚引用无法通过它拿到对象，只能配合 ReferenceQueue 在对象被回收时收到通知，用于管理堆外内存（DirectByteBuffer 的 Cleaner 就是这么做的）。

可达性分析从 GC Roots 出发：虚拟机栈中引用的对象、方法区静态属性引用的对象、常量引用的对象、本地方法栈 JNI 引用的对象、活跃线程、同步锁持有的对象。不可达的对象才可能被回收。

## 内存泄漏的典型形态

内存泄漏指对象已经没用了，但仍被 GC Root 引用着，回收不掉。堆内存持续上涨、Full GC 后仍不下降，就是典型信号。

常见形态有六种：

一是静态集合只增不减，比如 static Map 做缓存却没有淘汰策略。二是监听器与回调注册后没有反注册，尤其是长生命周期的对象持有短生命周期对象。三是 ThreadLocal 用完不 remove，线程池里的线程复用导致 value 一直挂着。四是内部类持有外部类引用，非静态内部类默认持有 outer.this。五是资源未关闭，连接、流、游标。六是 equals 与 hashCode 没同时重写，导致 HashMap 里放了重复 key 又删不掉。

排查顺序：先看监控上老年代占用是否随时间单调上升，再用 jstat -gcutil 看 GC 频率与回收效果，然后 jmap -histo:live 看哪个类的实例数异常增长，最后 jmap -dump 导出堆、用 MAT 或 VisualVM 分析支配树（Dominator Tree）与 GC Root 路径。

关键判据：不是「哪个对象最多」，而是「谁持有它、为什么还活着」。MAT 里的 Path to GC Roots 排除弱引用与虚引用，剩下的才是真凶。
