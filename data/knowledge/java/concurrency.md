# 并发编程：线程池与锁

## 线程池的七个参数

ThreadPoolExecutor 的构造参数：核心线程数 corePoolSize、最大线程数 maximumPoolSize、空闲存活时间 keepAliveTime、时间单位 unit、任务队列 workQueue、线程工厂 threadFactory、拒绝策略 handler。

执行流程：线程数小于 core 时直接新建线程；否则入队；队列满了且线程数小于 max 时新建线程；都满了走拒绝策略。

顺序很反直觉：先排队再扩容。所以如果用了无界队列（LinkedBlockingQueue 不传容量，默认 Integer.MAX_VALUE），maximumPoolSize 永远不会生效，线程数最多就是 core，队列堆积到 OOM。这是线上事故最常见的一个形态。

推荐用有界队列 + 明确的拒绝策略。拒绝策略四种：AbortPolicy（抛 RejectedExecutionException，默认）、CallerRunsPolicy（由提交任务的线程自己执行，天然限流）、DiscardPolicy（静默丢弃）、DiscardOldestPolicy（丢队头最老的）。

线程数怎么定：CPU 密集型约等于核数加一（多一个是为了在偶发缺页时补位）；IO 密集型按 核数 ×（1 + 平均等待时间 / 平均计算时间）估算。公式只是起点，最终要靠压测。

## 线程池打满怎么排查

典型现象：接口大面积超时，日志里大量拒绝异常，或者请求卡住不返回。

排查顺序：先看线程池监控（活跃线程数、队列长度、已完成任务数、拒绝次数），确认是「线程不够」还是「任务卡住」。如果活跃线程数等于 max 且队列持续增长，先怀疑任务里有阻塞调用（远程调用没设超时、数据库慢查询、死锁）。

拿线程快照最有效：jstack 连打三到五次，间隔 5 秒，对比哪些线程一直停在同一处。如果四十多个线程都卡在同一个方法的同一个行号，基本就是锁竞争或者某个同步阻塞调用。

止血手段：把上游流量切掉一部分、把关键接口的线程池隔离出来、给所有远程调用加上超时、临时调大队列或线程数（这是缓解不是解决）。之后必须做复盘：为什么单点阻塞会拖垮整个池，为什么没有隔离，为什么没有超时。

## synchronized 与 Lock 的区别

synchronized 是 JVM 层面的内置锁，自动加锁释放（异常也会释放），不可中断、不可超时、非公平。Java 6 之后有偏向锁、轻量级锁、重量级锁的升级过程（JDK 15 起偏向锁默认关闭，18 移除）。

ReentrantLock 是 JDK 层面的实现，基于 AQS。支持可中断（lockInterruptibly）、可超时（tryLock）、可公平（构造函数传 true）、支持多个条件变量（newCondition）。

选择原则：能用 synchronized 就用，简单且不会忘记解锁。需要超时、中断、公平、多条件时才用 Lock。

AQS 的核心是一个 volatile int state 加一个 CLH 双向队列。独占模式下 state 为 1 表示占用，竞争失败的线程包装成 Node 入队并 park。释放时唤醒后继节点。

## volatile 与内存可见性

volatile 保证可见性与有序性，不保证原子性。可见性来自：写操作会立即刷回主内存并使其他线程的工作内存副本失效；读操作会从主内存重新读取。

有序性靠内存屏障：写前插入 StoreStore、写后插入 StoreLoad；读后插入 LoadLoad 与 LoadStore。这也顺带禁止了指令重排。

双重检查锁（DCL）单例必须给实例字段加 volatile，否则可能出现「分配内存 → 赋值引用 → 初始化」被重排成「分配内存 → 赋值引用」的情况，别的线程拿到一个还没初始化完的对象。

## 死锁的四个必要条件与排查

互斥、占有且等待、不可抢占、循环等待，四者同时成立才死锁。破坏任意一个即可，工程上最常用的是「破坏循环等待」——固定加锁顺序。

排查：jstack 输出里会直接给出 "Found one Java-level deadlock"，列出互相等待的线程与持有的锁。如果是数据库死锁，看 innodb status 里的 LATEST DETECTED DEADLOCK 段。

避免手段：锁粒度尽量小、不在持锁时调用外部服务、给锁加超时（tryLock 带时间）、用无锁结构或并发容器（ConcurrentHashMap、CopyOnWriteArrayList、LongAdder）。

CAS 是无锁的基础，靠 CPU 的 cmpxchg 指令，ABA 问题用版本号（AtomicStampedReference）解决。高竞争下 CAS 自旋会浪费 CPU，LongAdder 用分段累加缓解了这点。
