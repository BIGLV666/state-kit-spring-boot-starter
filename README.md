# state-kit-spring-boot-starter

轻量级**声明式状态流转** Spring Boot Starter：业务只在 yml（或 Java DSL）里声明流转规则，框架启动时动态生成状态机 Bean；以数据库 **CAS 条件更新**保证并发正确，以**唯一写入口**保证状态不被绕改。**核心零建表、零业务流程类、零必选依赖。**

```
business ──fire(id, event, args)──▶ StateMachine（框架生成的 Bean）
                                        │ 读当前态 → 路由 → 守卫
                                        │ CAS：UPDATE t SET status=to[,附加列] WHERE id=? AND status=from
                                        │ 动作（同事务）→ 事件 →（历史）
                                        ▼
                                    业务表（status 唯一写入口）
```

## 一、定调的五个决策

| # | 决策 | 说明 |
|---|------|------|
| 1 | **纯声明式** | 流转规则只在 yml 或 Java DSL 里声明，框架启动时动态生成 `StateMachine` Bean，业务不写任何流程类 |
| 2 | **核心零表** | 路由在内存，并发靠数据库 CAS 条件更新；历史记录 opt-in，开启时启动自动建表 |
| 3 | **状态唯一写入口** | status 列只能由框架的 CAS 修改，动作/守卫里禁改（铁律，见第五节） |
| 4 | **事务边界** | fire 加入调用方已有事务，没有则自开；CAS 成功即持行锁至提交，同事务内后续业务写天然并发安全 |
| 5 | **param 与 set 严格分离** | `param` 是内存上下文（守卫/动作消费），`set` 是落库列（拼进 CAS 的 SET 子句），同名不互转 |

## 二、快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>io.github.biglv666</groupId>
    <artifactId>state-kit-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

要求：JDK 17+、Spring Boot 3.x；使用方需自带 JDBC 数据源访问能力（`spring-boot-starter-jdbc`、mybatis 等任意方式，容器里有 `DataSource` 即可）。 starter 本体**零传递依赖**（autoconfigure/jdbc/tx/slf4j 全部 provided）。

### 2. 声明流转规则（yml 通道，推荐）

```yaml
state-kit:
  machines:
    order:
      state-type: com.demo.OrderStatus        # 状态枚举全类名
      table: t_order                          # 业务表
      status-column: status                   # 默认 status
      id-column: id                           # 默认 id
      id-type: java.lang.Long                 # 默认 Long
      conflict-strategy: throw                # 默认 throw
      transitions:
        - { from: CREATED,         event: PAY,    to: PAID }
        - { from: PAID,            event: SHIP,   to: SHIPPED, action: orderShipAction }
        - { from: [CREATED, PAID], event: CANCEL, to: CANCELLED, guard: cancelGuard }   # 多源简写
        - { from: SHIPPED,         event: SIGN,   to: DONE, action: orderSignAction, guard: orderSignGuard }
```

状态列存枚举 `name()` 字符串（如 `CREATED`）。

### 3. 业务里注入并触发

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final StateMachine<OrderStatus, Long> orderFlow;   // 泛型匹配 + bean 名

    @Transactional
    public void pay(Long orderId, String txnNo, BigDecimal amount) {
        orderFlow.fire(orderId, "PAY",
                FireArg.param("txnNo", txnNo),     // 上下文：守卫/动作可读，不落库
                FireArg.set("pay_no", txnNo));     // 落库：status + pay_no 同一条 CAS SQL
        // 此处可继续其它业务写，同事务；任一步失败整体回滚
    }
}
```

多个同泛型状态机时，构造参数/字段名与 machine 名一致即可消歧，或用 `@Qualifier("machine名")`。

### 4. 钩子（可选，按需）

```java
@Component("orderSignGuard")
public class OrderSignGuard implements StateGuard<OrderStatus, Long> {
    public boolean test(StateTx<OrderStatus, Long> tx) {
        return tx.param("signer", String.class) != null;   // false = 拒绝本次流转
    }
}

@Component("orderSignAction")
public class OrderSignAction implements StateAction<OrderStatus, Long> {
    public void execute(StateTx<OrderStatus, Long> tx) {
        orderMapper.updateSignInfo(tx.entityId(), ...);    // 行锁已持有，同事务，安全
    }
}
```

就这么多。没有任何流程类、没有任何状态判断 switch-case。

### Java DSL 通道（等价，给要编译期检查的团队）

```java
@Bean
StateMachineDefinition orderFlow() {
    return StateMachine.define("order", OrderStatus.class)
            .table("t_order", "status", "id")
            .idType(Long.class)
            .transition(CREATED, PAID, "PAY")
            .transition(PAID, SHIPPED, "SHIP")
            .transition(List.of(CREATED, PAID), CANCELLED, "CANCEL")   // 多源简写
            .transition(SHIPPED, DONE, "SIGN")
            .guard("orderSignGuard")        // 作用于最近一条 transition
            .action("orderSignAction")
            .build();
}
```

两条通道产出同一种 `MachineDefinition` 运行时模型，可并存；machine 名重复会在启动期报错。
**注意**：定义 Bean 会在启动早期被框架实例化（用于校验与动态注册），请保持无重依赖——只依赖常量或其它定义 Bean。

## 三、框架生成的 API

### StateMachine<S, ID>

| 方法 | 说明 |
|------|------|
| `void fire(ID id, String event, FireArg... args)` | 统一流转入口。事务语义见第五节 |
| `Optional<S> currentState(ID id)` | 当前状态；实体不存在或状态列为空返回 empty |
| `boolean isFinal(ID id)` | 当前状态是否无出边（终态）；实体不存在抛 `EntityNotFoundException` |
| `Set<S> nextStates(ID id)` | 当前状态的直接后继集合，供前端渲染可用操作。**状态图层面**的可达后继，未经守卫校验 |

### FireArg（fire 可变参数令牌）

| 工厂 | 语义 | 去向 |
|------|------|------|
| `FireArg.param(key, value)` | 内存上下文 | 守卫/动作经 `StateTx.param(key, type)` 消费，**不落库** |
| `FireArg.set(column, value)` | 落库列 | 拼进 CAS UPDATE 的 SET 子句，与 status **同一条 SQL** |

约束：列名必须匹配 `^[A-Za-z][A-Za-z0-9_]*$`（白名单，创建时即校验，杜绝 SQL 注入）；列值一律 PreparedStatement 参数绑定；同名 set 列 / 同名 param 重复声明立即抛 `IllegalArgumentException`。

### StateTx<S, ID>（守卫/动作上下文）

`entityId()` / `event()` / `from()` / `to()` / `param(key, type)` / `operatorId()` / `traceId()`。
守卫与动作共享同一不可变快照；`from()` 是数据库实读的状态，不是调用方假设。

## 四、配置总览

```yaml
state-kit:
  history:
    enabled: false                    # 默认关：零 DDL、零历史 Bean
    table-name: sk_transition_history
  operator: auto                      # auto=类路径有 auth-kit 取 AuthContext；none=强制空
  trace:
    key: traceId                      # 从 MDC 取 traceId 的键名
  machines:
    order: { ... }                    # 见第二节
```

| 配置 | 默认 | 说明 |
|------|------|------|
| `state-kit.machines.<name>.state-type` | 必填 | 状态枚举全类名 |
| `state-kit.machines.<name>.table` | 必填 | 业务表名 |
| `state-kit.machines.<name>.status-column` | `status` | 状态列名（存枚举 name()） |
| `state-kit.machines.<name>.id-column` | `id` | 主键列名 |
| `state-kit.machines.<name>.id-type` | `Long` | 实体主键类型（影响注入泛型） |
| `state-kit.machines.<name>.conflict-strategy` | `throw` | `throw`=CAS 未命中抛异常；`log`=仅 WARN 日志、fire 正常返回（**静默，慎用**，见第五节风险说明） |
| `state-kit.machines.<name>.transitions[].from` | 必填 | 单值或数组（多源简写） |
| `state-kit.machines.<name>.transitions[].event` / `.to` | 必填 | 事件名 / 目标状态 |
| `state-kit.machines.<name>.transitions[].action` / `.guard` | 可选 | 动作/守卫 bean 名 |
| `state-kit.history.enabled` | `false` | **默认不建表不写历史**；显式 `true` 才自动建表并记录 |
| `state-kit.history.table-name` | `sk_transition_history` | 历史表名 |
| `state-kit.operator` | `auto` | `auto`/`none`；自建登录态时注册自定义 `OperatorResolver` Bean 替换 |
| `state-kit.trace.key` | `traceId` | MDC 键名；micrometer-tracing（api-governance 底座）默认即此键 |

IDE 补全：`META-INF/additional-spring-configuration-metadata.json` 已内置提示。

## 五、核心语义（重要，务必阅读）

### 1. fire 主流程与事务边界

```
读当前态 → 路由查边 → 守卫 → CAS → 动作 → 发事件 →（记历史）
└────────────── 整体包在 TransactionTemplate(REQUIRED) ──────────────┘
```

- 调用方已有事务（如 `@Transactional` 方法）：fire **加入**该事务，任一步失败整体回滚；
- 无外部事务：fire **自开**事务；
- CAS 成功即持有行锁至事务提交，同事务内后续业务字段更新天然并发安全；
- 动作抛任何异常：整体回滚，状态不变、set 列不变、历史不写；
- 容器无事务管理器时退化为逐语句自动提交（历史与 CAS 不再原子），生产环境务必配置事务管理器。

### 2. 并发正确性（CAS）

并发双流转时，数据库行锁保证恰好一方 CAS 命中：

```
线程A: SELECT status=CREATED → CAS UPDATE ... WHERE id=? AND status='CREATED' ✓（持锁至提交）
线程B: SELECT status=CREATED → CAS UPDATE 阻塞在行锁 → A 提交 → WHERE 重评不命中 → affected=0
     → 抛 StateConflictException（含期望态 CREATED / 实际态 PAID）
```

- `StateConflictException` 是**竞态窗口**问题，重读状态后重试可能成功；
- `IllegalTransitionException` 是**确定性**错误（状态图不允许），重试不会成功；
- `conflict-strategy: log` 时冲突不抛异常、仅 WARN 日志、fire 正常返回。**风险**：fire 签名为 void，调用方无法感知冲突，只适合「冲突可忽略」的补偿性批量场景。

### 3. 守卫与动作的分工

- **守卫**：CAS 之前的只读校验，返回 false 即拒绝（`GuardRejectedException`）。守卫通过到 CAS 之间存在间隙，由 CAS 的 `WHERE status=from` 兜底，因此守卫只做最佳-effort 拦截，不做业务写；需要携带具体拒绝原因时在守卫里直接抛 `BusinessException(自定义错误码, "原因")`；
- **动作**：CAS 之后的业务写，与状态变更同事务、共持行锁。动作里**禁止**修改 status 列。

### 4. 状态唯一写入口（铁律）

业务表的 status 列**只能**由 `fire` 的 CAS 修改。守卫/动作里直接 UPDATE status、业务代码绕过 fire 改状态，都会破坏并发正确性与流转记录的完整性。绕改检测（BYPASS）规划在 V2 与 data-audit 合流实现，V1 靠这条铁律约束。

### 5. 事件（旁路观测，不做投递承诺）

`StateTransitedEvent` 在 CAS 与动作成功后、事务提交前发布（进程内同步）。定位：统计、缓存失效等**无副作用旁路**。框架**不承诺**基于事件的任何投递——流转成功后的业务推进请写在动作里或业务方法 fire 之后显式调用：

```java
// 有副作用的监听（写库等）务必用 AFTER_COMMIT：事务回滚时不会触发
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onTransited(StateTransitedEvent event) { ... }

// 纯内存统计类旁路用普通 @EventListener 即可
@EventListener
public void onMetrics(StateTransitedEvent event) { ... }
```

## 六、历史模块（默认关闭）

- `enabled: false`（默认）：启动**零 DDL**、容器内不存在任何历史相关 Bean；
- `enabled: true`：启动检查并自动建表（表/索引已存在则跳过并打日志，幂等可重复执行），每次 fire 在业务**同一事务**写入流转记录——业务回滚历史也回滚；
- 开启需要容器存在 `DataSource`。

表结构（`entity_id` 统一 VARCHAR(64) 字符串化，兼容任意主键类型；自增主键按数据库方言生成 MySQL 用 AUTO_INCREMENT，其余用标准 IDENTITY）：

```sql
CREATE TABLE sk_transition_history (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT/IDENTITY,
    machine     VARCHAR(64) NOT NULL,
    entity_id   VARCHAR(64) NOT NULL,
    from_state  VARCHAR(64) NOT NULL,
    to_state    VARCHAR(64) NOT NULL,
    event       VARCHAR(64) NOT NULL,
    operator_id VARCHAR(64),
    trace_id    VARCHAR(64),
    create_time TIMESTAMP   NOT NULL
);
CREATE INDEX idx_sk_history_machine_entity ON sk_transition_history (machine, entity_id, create_time);
```

查询：

```java
@Autowired HistoryQueryService historyService;

List<HistoryEntry> trajectory = historyService.query("order", orderId);  // 时间序轨迹
long count = historyService.count("order", orderId);
```

## 七、异常体系

| 异常 | 触发场景 | web-common 映射 | 语义 |
|------|----------|-----------------|------|
| `IllegalTransitionException` | 当前状态无该事件出边 / 实体不存在 | `CONFLICT (40900)` | 确定性错误，重试无意义 |
| `StateConflictException` | CAS 未命中（conflict-strategy=throw） | `CONFLICT (40900)` | 竞态问题，重读后重试可能成功 |
| `GuardRejectedException` | 守卫返回 false | `BIZ_ERROR (50000)` | 业务规则拒绝 |
| `EntityNotFoundException` | currentState 类查询时实体不存在 | `NOT_FOUND (40400)` | — |

类路径存在 web-common 时，异常类上的 `@DefaultErrorCode` 注解自动映射统一 Result，业务零 try-catch；不存在时异常原样抛出，由应用自行处理。

## 八、StateStore SPI（非标存储）

默认 `JdbcStateStore` 面向关系型业务表。需要把状态落到 Redis/ES 等，实现接口注册为 Bean 即可整体替换（对所有状态机生效），路由/守卫/动作/事件/历史全部不变：

```java
@Bean
public StateStore redisStateStore() {
    return new StateStore() {
        public Optional<String> readState(Object id) { ... }
        public int casTransition(Object id, String from, String to, Map<String, Object> setColumns) { ... }
    };
}
```

**实现约束**：`casTransition` 必须是单条原子语句，并发正确性完全依赖 `WHERE ... AND status = from` 语义与存储层行锁；值必须参数绑定，禁止拼接。

## 九、启动期校验（fail fast）

以下问题全部在启动阶段报错阻止应用带病启动，错误信息定位到 machine + 具体流转：

- from/to 不在状态枚举内；state-type 缺失或非枚举；table 缺失；
- 表名/状态列/主键列不是合法 SQL 标识符（白名单，防注入）；
- 同一 `(from, event)` 重复定义；
- action/guard bean 不存在或类型不符；
- machine 名在 yml 与 Java DSL 间重复、或与容器已有 Bean 冲突；
- 状态无任何入边 → **WARN 告警**（初始状态属正常），不阻止启动。

## 十、与四件套的咬合

| 组件 | 咬合方式 | 依赖 |
|------|----------|------|
| web-common | 三类状态异常经 `@DefaultErrorCode` 自动映射统一 Result | optional，类路径检测 |
| auth-kit | `AuthContext.getUserId()` 自动填充 StateTx.operatorId 与历史表 | optional，类路径检测；`operator: none` 强制关闭 |
| api-governance | traceId 从 MDC 读取（其 trace 底座 micrometer-tracing 自动填充） | 无类依赖，MDC 有值即生效 |

## 十一、测试

`src/test` 覆盖 55 个用例（H2 内存库，`mvnw test` 一键运行），包括：

- **并发双流转**（8 线程同 fire，CAS 保证恰好一成一败）；
- **竞态窗口精确复现**（未提交事务持锁 + 另一线程 fire，CAS 阻塞后 WHERE 重评）；
- CAS 失败后旧状态不可再 fire、set 列冲突时不写入；
- 守卫拒绝后状态与附带列均未动、动作抛异常整体回滚（含动作自身的写与 set 列）；
- fire 加入外部事务（提交/回滚两边界）与自开事务；
- 多源流转、同一事件多边路由、String 主键、param/set 严格分离、SQL 注入列名拦截；
- history 关闭零 DDL / 开启自动建表 / 建表幂等 / 历史与业务同事务回滚 / 轨迹查询；
- 启动期校验每种错误形态（11 例）、自定义 StateStore SPI 整体替换、无登录态/无链路降级路径。

## 十二、版本路线

- **V1.5**：状态停留时长统计（基于历史表）、可选乐观锁双保险（业务表带 version 列）、冲突自动重试策略（可配次数）、`fire` 的 skipHistory 重载；
- **V2**：BYPASS 绕改检测（与 data-audit 共用变更捕获底座）、嵌套子状态机（会签/或签）、Reactive/WebFlux 支持。

## 十三、已知限制

- 状态机 Bean 在启动期注册，不支持运行期增删流转规则（改 yml 重启即可）；
- Java DSL 定义 Bean 会被提前实例化，不能注入业务 Bean；
- `fire` 的 void 返回值 + `conflict-strategy: log` 组合会静默吞掉冲突，调用方无感知（V1.5 提供 `tryFire` 返回式重载）；
- 历史表 DDL 内置方言：MySQL/MariaDB 与标准（H2/PostgreSQL）两类，其它数据库未验证；
- 不支持在同一事件上对同一状态挂不同目标（`(from, event)` 唯一性校验会拦截）。
