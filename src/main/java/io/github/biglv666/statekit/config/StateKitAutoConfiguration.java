package io.github.biglv666.statekit.config;

import io.github.biglv666.statekit.bootstrap.StateMachineRegistrar;
import io.github.biglv666.statekit.context.AuthKitOperatorResolver;
import io.github.biglv666.statekit.context.OperatorResolver;
import io.github.biglv666.statekit.context.TraceIdResolver;
import io.github.biglv666.statekit.history.HistoryQueryService;
import io.github.biglv666.statekit.history.HistoryRecorder;
import io.github.biglv666.statekit.history.JdbcHistoryRecorder;
import io.github.biglv666.statekit.store.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * state-kit 自动装配入口。
 *
 * <ul>
 *     <li>{@link StateMachineRegistrar}：收集双通道定义、启动期校验、注册动态状态机 Bean；</li>
 *     <li>{@link OperatorResolver}：auth-kit 类路径检测装配，{@code operator=none} 强制关闭；</li>
 *     <li>{@link TraceIdResolver}：MDC 读取（micrometer-tracing / api-governance 自动填充）；</li>
 *     <li>{@link TransactionTemplate}：容器有事务管理器时创建，fire 用它实现「加入外部事务/自开事务」；</li>
 *     <li>历史模块：仅在 {@code state-kit.history.enabled=true} 且存在 DataSource 时装配，
 *         关闭时框架内不存在任何历史相关 Bean、启动零 DDL。</li>
 * </ul>
 *
 * <p>自定义 {@link StateStore} Bean（Redis/ES 等非标存储）注册后对所有状态机生效，
 * 整体替换默认 JDBC 存储。</p>
 */
@AutoConfiguration
@AutoConfigureAfter({DataSourceAutoConfiguration.class, TransactionAutoConfiguration.class,
        SqlInitializationAutoConfiguration.class})
@EnableConfigurationProperties(StateKitProperties.class)
public class StateKitAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StateKitAutoConfiguration.class);

    /** auth-kit 类路径标记 */
    private static final String AUTH_KIT_PRESENT =
            "io.github.biglv666.authkit.core.AuthContext";

    /**
     * 动态状态机 Bean 注册器。必须为 static：BeanFactoryPostProcessor
     * 需要在普通 Bean 实例化之前生效，避免提前实例化配置类。
     */
    @Bean
    public static StateMachineRegistrar stateMachineRegistrar(ApplicationEventPublisher eventPublisher) {
        return new StateMachineRegistrar(eventPublisher);
    }

    /**
     * 操作人解析器：默认 auto——类路径有 auth-kit 读 AuthContext，否则恒 null。
     * 业务自建登录态时注册自己的 OperatorResolver Bean 即可替换。
     */
    @Bean
    @ConditionalOnMissingBean(OperatorResolver.class)
    public OperatorResolver operatorResolver(StateKitProperties properties) {
        if ("none".equalsIgnoreCase(properties.getOperator())) {
            return () -> null;
        }
        if (ClassUtilsPresent.isPresent(AUTH_KIT_PRESENT)) {
            log.debug("state-kit 检测到 auth-kit, operatorId 取自 AuthContext");
            return new AuthKitOperatorResolver();
        }
        return () -> null;
    }

    /**
     * 链路追踪解析器：从 MDC 读取 traceId，键名可配。
     */
    @Bean
    @ConditionalOnMissingBean(TraceIdResolver.class)
    public TraceIdResolver traceIdResolver(StateKitProperties properties) {
        return new TraceIdResolver(properties.getTrace().getKey());
    }

    /**
     * 事务模板：容器存在唯一事务管理器时创建（REQUIRED 传播），
     * fire 用它实现「加入调用方事务 / 无事务时自开」。
     */
    @Bean
    @ConditionalOnSingleCandidate(PlatformTransactionManager.class)
    @ConditionalOnMissingBean(TransactionTemplate.class)
    public TransactionTemplate stateKitTransactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * 历史写入器：显式开启 history 且存在 DataSource 才装配；
     * Bean 初始化时执行幂等建表（表/索引已存在则跳过）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "state-kit.history", name = "enabled", havingValue = "true")
    @ConditionalOnSingleCandidate(DataSource.class)
    public HistoryRecorder historyRecorder(DataSource dataSource, StateKitProperties properties) {
        JdbcHistoryRecorder recorder = new JdbcHistoryRecorder(
                new JdbcTemplate(dataSource),
                dataSource,
                properties.getHistory().getTableName());
        recorder.initialize();
        return recorder;
    }

    /**
     * 历史查询服务：随历史模块一同装配（同条件）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "state-kit.history", name = "enabled", havingValue = "true")
    @ConditionalOnSingleCandidate(DataSource.class)
    public HistoryQueryService historyQueryService(DataSource dataSource, StateKitProperties properties) {
        return new HistoryQueryService(new JdbcTemplate(dataSource),
                properties.getHistory().getTableName());
    }

    /**
     * BYPASS 监视清单：Registrar 启动期登记机器，装饰器读取。始终注册（开销可忽略），
     * 装饰器本身按 bypass.mode 条件装配。
     */
    @Bean
    public io.github.biglv666.statekit.bypass.BypassWatchlist bypassWatchlist() {
        return new io.github.biglv666.statekit.bypass.BypassWatchlist();
    }

    /**
     * BYPASS 数据源装饰器（0.2.0+）：仅当 bypass.mode 为 log / event 时装配，
     * 默认 off 时容器内不存在本 Bean、DataSource 零包装。
     */
    @Bean
    public static io.github.biglv666.statekit.bypass.BypassDataSourceDecorator bypassDataSourceDecorator(
            StateKitProperties properties,
            io.github.biglv666.statekit.bypass.BypassWatchlist watchlist,
            ApplicationEventPublisher eventPublisher,
            ObjectProvider<OperatorResolver> operatorResolver,
            ObjectProvider<TraceIdResolver> traceIdResolver) {
        String mode = properties.getBypass().getMode() == null
                ? "off" : properties.getBypass().getMode().toLowerCase().trim();
        if ("off".equals(mode)) {
            // off：零包装透传（Bean 仍注册，保证装配路径统一）
            return new io.github.biglv666.statekit.bypass.BypassDataSourceDecorator(null);
        }
        boolean publish = "event".equals(mode);
        if (!publish && !"log".equals(mode)) {
            throw new IllegalStateException("state-kit.bypass.mode 非法: [" + mode + "]，只支持 off / log / event");
        }
        var matcher = new io.github.biglv666.statekit.bypass.BypassSqlMatcher(
                watchlist, eventPublisher,
                operatorResolver.getIfAvailable(), traceIdResolver.getIfAvailable(), publish);
        return new io.github.biglv666.statekit.bypass.BypassDataSourceDecorator(matcher);
    }

    /**
     * 状态停留时长统计服务（0.2.0+）：随历史模块一同装配（history.enabled=true）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "state-kit.history", name = "enabled", havingValue = "true")
    @ConditionalOnSingleCandidate(DataSource.class)
    public io.github.biglv666.statekit.history.HistoryDwellService historyDwellService(
            DataSource dataSource, StateKitProperties properties) {
        return new io.github.biglv666.statekit.history.HistoryDwellService(
                new JdbcTemplate(dataSource), properties.getHistory().getTableName());
    }

    /**
     * 响应式历史写入器（0.2.0+）：history.enabled=true 且容器有 DatabaseClient 时装配，
     * 供 Reactive 状态机使用。
     */
    @Bean
    @ConditionalOnProperty(prefix = "state-kit.history", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "org.springframework.r2dbc.core.DatabaseClient")
    public io.github.biglv666.statekit.history.ReactiveHistoryRecorder reactiveHistoryRecorder(
            org.springframework.beans.factory.ObjectProvider<org.springframework.r2dbc.core.DatabaseClient> databaseClient,
            StateKitProperties properties) {
        var client = databaseClient.getIfAvailable();
        if (client == null) {
            return record -> reactor.core.publisher.Mono.empty();
        }
        return new io.github.biglv666.statekit.history.R2dbcHistoryRecorder(
                client, properties.getHistory().getTableName());
    }

    /** auth-kit 类路径检测辅助（避免在本类 import auth-kit 类） */
    static final class ClassUtilsPresent {
        private ClassUtilsPresent() {
        }

        static boolean isPresent(String className) {
            try {
                Class.forName(className, false, StateKitAutoConfiguration.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        }
    }

    /**
     * 可视化导出 REST 端点（0.3.0+）：仅当 spring-web 在类路径时注册。
     * exporter 按 bean 名（machine名 + Exporter）反查机器名，业务方自行实现的
     * 导出端点会被本方法 @ConditionalOnMissingBean 避让。
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    @ConditionalOnMissingBean(io.github.biglv666.statekit.export.StateKitDiagramController.class)
    public io.github.biglv666.statekit.export.StateKitDiagramController stateKitDiagramController(
            org.springframework.context.ApplicationContext applicationContext) {
        java.util.Map<String, io.github.biglv666.statekit.export.StateMachineExporter> byMachine =
                new java.util.LinkedHashMap<>();
        for (String beanName : applicationContext
                .getBeanNamesForType(io.github.biglv666.statekit.export.StateMachineExporter.class)) {
            if (beanName.endsWith("Exporter")) {
                String machine = beanName.substring(0, beanName.length() - "Exporter".length());
                byMachine.put(machine,
                        applicationContext.getBean(beanName,
                                io.github.biglv666.statekit.export.StateMachineExporter.class));
            }
        }
        return new io.github.biglv666.statekit.export.StateKitDiagramController(byMachine);
    }
}
