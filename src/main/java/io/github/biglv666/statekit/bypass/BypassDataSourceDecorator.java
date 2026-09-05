package io.github.biglv666.statekit.bypass;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;

/**
 * BYPASS 检测的数据源装饰器（0.2.0+）：以 {@link BeanPostProcessor} 把容器中的
 * {@link DataSource} 包成 JDK 代理，逐层包装 Connection / Statement，
 * 在语句执行入口调用 {@link BypassSqlMatcher#check(String)}。
 *
 * <p>仅当 {@code state-kit.bypass.mode} 为 log / event 时携带 matcher 注册；
 * mode=off（默认）时 matcher 为 null，DataSource 零包装直接透传。</p>
 */
public class BypassDataSourceDecorator implements BeanPostProcessor {

    private final BypassSqlMatcher matcher;

    public BypassDataSourceDecorator(BypassSqlMatcher matcher) {
        this.matcher = matcher;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (matcher != null && bean instanceof DataSource dataSource) {
            return proxyDataSource(dataSource);
        }
        return bean;
    }

    private DataSource proxyDataSource(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(
                BypassDataSourceDecorator.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(target, method, args);
                    if (result instanceof Connection connection && method.getName().equals("getConnection")) {
                        return proxyConnection(connection);
                    }
                    return result;
                });
    }

    private Connection proxyConnection(Connection target) {
        return (Connection) Proxy.newProxyInstance(
                BypassDataSourceDecorator.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(target, method, args);
                    String name = method.getName();
                    // 三类语句创建入口都拦截，SQL 在 prepare 时捕获
                    if (result instanceof java.sql.Statement statement) {
                        String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : null;
                        return proxyStatement(statement, sql);
                    }
                    return result;
                });
    }

    private Object proxyStatement(Object target, String preparedSql) {
        return Proxy.newProxyInstance(
                BypassDataSourceDecorator.class.getClassLoader(),
                new Class<?>[]{java.sql.Statement.class, java.sql.PreparedStatement.class,
                        java.sql.CallableStatement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    // 执行入口：显式带 SQL 的 Statement 方法用入参，PreparedStatement 用 prepare 时的 SQL
                    if (name.equals("execute") || name.equals("executeQuery") || name.equals("executeUpdate")
                            || name.equals("executeLargeUpdate") || name.equals("executeBatch")
                            || name.equals("executeLargeBatch")) {
                        String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : preparedSql;
                        matcher.check(sql);
                    }
                    return invoke(target, method, args);
                });
    }

    private Object invoke(Object target, Method method, Object[] args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }
}
