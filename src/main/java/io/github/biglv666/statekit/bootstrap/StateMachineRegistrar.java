package io.github.biglv666.statekit.bootstrap;

import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.config.StateKitProperties;
import io.github.biglv666.statekit.context.OperatorResolver;
import io.github.biglv666.statekit.context.TraceIdResolver;
import io.github.biglv666.statekit.core.DefaultStateMachine;
import io.github.biglv666.statekit.core.MachineRuntime;
import io.github.biglv666.statekit.core.TransitionRouter;
import io.github.biglv666.statekit.define.MachineDefinition;
import io.github.biglv666.statekit.history.HistoryRecorder;
import io.github.biglv666.statekit.store.JdbcStateStore;
import io.github.biglv666.statekit.store.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动装配核心：收集双通道状态机定义 → 校验 → 注册动态 {@code StateMachine} Bean。
 *
 * <p>执行时机：作为 {@link BeanDefinitionRegistryPostProcessor} 在常规单例实例化之前运行
 * （实现 {@link Ordered} 声明最低优先级，确保排在 {@code ConfigurationClassPostProcessor}
 * 之后，此时组件扫描与 @Bean 定义均已就绪）。</p>
 *
 * <p>动态 Bean 的注入体验：每个状态机注册为一个合成 Bean，
 * {@code targetType} 带 ResolvableType 泛型（如 {@code StateMachine<OrderStatus, Long>}），
 * 实例为 JDK 代理包装的 {@link DefaultStateMachine}。同泛型多状态机时用
 * {@code @Qualifier("machine名")} 区分。</p>
 */
public class StateMachineRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, Ordered {

    private static final Logger log = LoggerFactory.getLogger(StateMachineRegistrar.class);

    private Environment environment;
    /** 容器事件发布器（即应用上下文），由自动装配注入 */
    private final ApplicationEventPublisher eventPublisher;

    public StateMachineRegistrar(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        // 排在 ConfigurationClassPostProcessor（PriorityOrdered）之后，保证 DSL 定义 bean 已被扫描
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        // 全部工作延后到 postProcessBeanFactory：此时才允许 getBeanNamesForType 拉取 DSL 定义
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        StateKitProperties properties = bindProperties();

        // 1. 收集 yml 通道定义
        Map<String, MachineDefinition> definitions = new LinkedHashMap<>();
        properties.getMachines().forEach((name, props) ->
                definitions.put(name, MachineDefinitionConverter.convert(name, props)));

        // 2. 收集 Java DSL 通道定义（此时定义 bean 会被提前实例化，定义必须无重依赖）
        java.util.Set<String> dslDefinitionBeanNames = new java.util.HashSet<>();
        for (String beanName : beanFactory.getBeanNamesForType(MachineDefinition.class, false, false)) {
            dslDefinitionBeanNames.add(beanName);
            MachineDefinition definition = (MachineDefinition) beanFactory.getBean(beanName);
            MachineDefinition prev = definitions.put(definition.getName(), definition);
            if (prev != null) {
                throw new IllegalStateException("状态机名重复: [%s]，yml 与 Java DSL 中只能声明一处"
                        .formatted(definition.getName()));
            }
        }

        if (definitions.isEmpty()) {
            log.info("state-kit 未发现任何状态机定义（检查 state-kit.machines 或 StateMachineDefinition Bean）");
            return;
        }

        // 3. 逐个校验（fail fast）
        for (MachineDefinition definition : definitions.values()) {
            DefinitionValidator.validate(definition, beanFactory);
        }

        // 4. 注册动态 Bean
        if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
            throw new IllegalStateException("state-kit 需要 BeanDefinitionRegistry 类型的容器");
        }
        for (MachineDefinition definition : definitions.values()) {
            MachineRuntime<?> runtime = buildRuntime(definition);
            registerMachineBean(registry, beanFactory, runtime, dslDefinitionBeanNames);
            log.info("state-kit 已注册状态机 [{}] -> 泛型 StateMachine<{}, {}>",
                    definition.getName(), definition.getStateType().getSimpleName(),
                    definition.getIdType().getSimpleName());
        }
    }

    /** yml 绑定：用 Binder 直读 Environment，避免在启动早期实例化配置属性 Bean */
    private StateKitProperties bindProperties() {
        return Binder.get(environment)
                .bind("state-kit", Bindable.of(StateKitProperties.class))
                .orElseGet(StateKitProperties::new);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private MachineRuntime<?> buildRuntime(MachineDefinition definition) {
        Class enumType = definition.getStateType();
        TransitionRouter router = new TransitionRouter();
        router.compile(definition.getTransitions());
        return new MachineRuntime<>(definition, enumType, router);
    }

    /**
     * 注册单个状态机动态 Bean：ResolvableType 泛型 targetType + JDK 代理实例。
     * 依赖在实例化时按名从容器解析（历史记录器可能不存在，用 ObjectProvider 优雅降级）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerMachineBean(BeanDefinitionRegistry registry,
                                     ConfigurableListableBeanFactory beanFactory,
                                     MachineRuntime runtime,
                                     java.util.Set<String> dslDefinitionBeanNames) {
        MachineDefinition definition = runtime.getDefinition();

        RootBeanDefinition beanDefinition = new RootBeanDefinition();
        beanDefinition.setTargetType(ResolvableType.forClassWithGenerics(
                StateMachine.class, definition.getStateType(), definition.getIdType()));
        beanDefinition.setBeanClassName(DefaultStateMachine.class.getName());
        beanDefinition.setRole(BeanDefinition.ROLE_APPLICATION);
        beanDefinition.setInstanceSupplier(() -> createMachine(beanFactory, runtime));
        beanDefinition.setAttribute("factoryBeanObjectType", DefaultStateMachine.class.getName());

        String beanName = definition.getName();
        boolean overwritingDslDefinition = dslDefinitionBeanNames.contains(beanName);
        if (registry.containsBeanDefinition(beanName) && !overwritingDslDefinition) {
            // DSL 定义 bean 与状态机 bean 同名是常态（@Bean 方法名 = machine 名），
            // 定义已被消费，允许覆盖；与其余业务 Bean 同名则是真冲突
            throw new IllegalStateException("状态机 [%s] 的 Bean 名与容器已有 Bean 冲突".formatted(beanName));
        }
        if (overwritingDslDefinition && registry.containsBeanDefinition(beanName)) {
            // Boot 默认禁止 Bean 定义覆盖：先摘除 DSL 定义，再注册状态机 Bean
            registry.removeBeanDefinition(beanName);
        }
        registry.registerBeanDefinition(beanName, beanDefinition);
        if (overwritingDslDefinition && beanFactory.containsSingleton(beanName)) {
            // 收集阶段为读机器名而提前实例化的定义单例必须清掉，否则状态机 Bean 会取到定义实例
            ((org.springframework.beans.factory.support.DefaultSingletonBeanRegistry) beanFactory)
                    .destroySingleton(beanName);
        }
    }

    /**
     * 组装单个状态机实例。存储解析优先级：容器中的自定义 {@link StateStore} Bean（SPI 替换）
     * → 按状态机声明构造 {@link JdbcStateStore}（需要 DataSource）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private DefaultStateMachine createMachine(ConfigurableListableBeanFactory beanFactory,
                                              MachineRuntime runtime) {
        StateStore stateStore = beanFactory.getBeanProvider(StateStore.class)
                .getIfAvailable();
        if (stateStore == null) {
            DataSource dataSource = beanFactory.getBean(DataSource.class);
            stateStore = new JdbcStateStore(resolveJdbcTemplate(beanFactory, dataSource),
                    runtime.getDefinition().getTable(),
                    runtime.getDefinition().getStatusColumn(),
                    runtime.getDefinition().getIdColumn());
        }

        TransactionTemplate transactionTemplate = null;
        if (beanFactory.getBeanNamesForType(PlatformTransactionManager.class, false, false).length > 0) {
            transactionTemplate = new TransactionTemplate(
                    beanFactory.getBean(PlatformTransactionManager.class));
        }

        HistoryRecorder historyRecorder = beanFactory.getBeanProvider(HistoryRecorder.class)
                .getIfAvailable();

        return new DefaultStateMachine(
                runtime,
                stateStore,
                transactionTemplate,
                beanFactory.getBeanProvider(OperatorResolver.class).getIfAvailable(),
                beanFactory.getBeanProvider(TraceIdResolver.class).getIfAvailable(),
                historyRecorder,
                eventPublisher,
                beanFactory);
    }

    /** JdbcTemplate 解析：优先复用容器已有 Bean（共享数据源配置），否则按 DataSource 新建 */
    private JdbcTemplate resolveJdbcTemplate(ConfigurableListableBeanFactory beanFactory, DataSource dataSource) {
        JdbcTemplate existing = beanFactory.getBeanProvider(JdbcTemplate.class).getIfAvailable();
        return existing != null ? existing : new JdbcTemplate(dataSource);
    }
}
