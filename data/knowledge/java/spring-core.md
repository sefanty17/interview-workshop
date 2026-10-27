# Spring 事务、AOP 与循环依赖

## 事务失效的六种情况

@Transactional 基于 AOP 代理实现，所以只有「通过代理对象调用」才生效。

一、同类内部方法自调用。this.method() 不经过代理，注解不生效。解法是注入自身代理、用 AopContext.currentProxy()，或者把方法拆到另一个 Bean。

二、方法不是 public。Spring 事务默认只对 public 方法生效（早期版本直接静默忽略）。

三、异常被吞掉。try-catch 之后不抛出，事务管理器认为正常结束就提交了。

四、抛的是检查异常。默认只对 RuntimeException 与 Error 回滚，要回滚检查异常必须写 rollbackFor = Exception.class。

五、数据库引擎不支持事务。MyISAM 没有事务。

六、事务传播行为配置错误。比如 REQUIRES_NEW 写在内层，外层回滚不影响内层，容易被误认为「没回滚」。

还有一个隐蔽的：事务方法里起子线程做异步操作，子线程不在事务上下文里，主事务回滚了子线程照样写进去了。

## 事务传播行为

REQUIRED（默认）：有事务就加入，没有就新建。绝大部分业务用它。

REQUIRES_NEW：总是新建事务，挂起外层。适合「日志必须记录，即使主业务回滚」。

NESTED：嵌套事务，靠 savepoint 实现，内层回滚不影响外层（但外层回滚会连带内层）。

SUPPORTS：有就加入，没有就以非事务方式执行。

NOT_SUPPORTED：以非事务方式执行，挂起外层事务。

MANDATORY：必须已有事务，否则抛异常。

NEVER：必须没有事务，否则抛异常。

注意 REQUIRES_NEW 会占用一个新的数据库连接，嵌套多层容易把连接池打满。

## AOP 的代理方式

JDK 动态代理：基于接口，生成的代理类实现同样的接口，通过反射调用目标方法。要求目标类至少实现一个接口。

CGLIB：基于继承，生成目标类的子类并重写方法。不能代理 final 类与 final 方法，private 方法也代理不了。

Spring Boot 2.x 起默认用 CGLIB（proxyTargetClass=true），因为这样不需要接口。两个都失效的场景：内部自调用、final 方法、private 方法、静态方法。

AOP 的执行顺序：多个切面时用 @Order 控制，值越小越靠外。事务切面的默认 order 是 Ordered.LOWEST_PRECEDENCE。

## 循环依赖与三级缓存

循环依赖指 A 依赖 B、B 依赖 A。构造器注入的循环依赖无法解决，直接报错；setter 或字段注入的可以解决，靠三级缓存。

一级缓存 singletonObjects：存完全初始化好的 Bean。

二级缓存 earlySingletonObjects：存提前暴露的原始 Bean（尚未填充属性）。

三级缓存 singletonFactories：存 ObjectFactory，用于生成早期引用（如果 Bean 需要 AOP，这里返回的是代理对象）。

流程：创建 A 时先把自己放进三级缓存 → 填充属性发现需要 B → 创建 B → B 填充属性需要 A → 从三级缓存拿到 A 的 ObjectFactory 生成早期引用，放进二级缓存并清空三级 → B 初始化完成 → A 拿到 B 完成初始化 → A 进一级缓存。

为什么需要三级而不是两级：如果没有三级缓存，AOP 代理对象就没地方提前生成，最终 B 里注入的是原始对象而一级缓存里的是代理对象，出现两个不同的实例。

Spring Boot 2.6 起默认禁止循环依赖（spring.main.allow-circular-references=false），出现循环依赖应当重构而不是打开开关。

## Bean 的生命周期

实例化（构造器）→ 属性填充（依赖注入）→ Aware 接口回调（BeanNameAware、BeanFactoryAware、ApplicationContextAware）→ BeanPostProcessor 前置处理 → @PostConstruct / InitializingBean.afterPropertiesSet / init-method → BeanPostProcessor 后置处理（AOP 代理在这里生成）→ 使用 → @PreDestroy / DisposableBean.destroy / destroy-method。

关键点：AOP 代理是在后置处理阶段生成的，所以如果在 @PostConstruct 里调用本类的事务方法，仍然不经过代理，事务失效。
