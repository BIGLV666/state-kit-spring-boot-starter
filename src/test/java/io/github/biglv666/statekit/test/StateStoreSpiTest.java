package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.config.StateKitAutoConfiguration;
import io.github.biglv666.statekit.store.StateStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StateStore SPI 测试：注册自定义 StateStore Bean 后整体替换默认 JDBC 存储，
 * 核心路由/守卫/事件逻辑不变，且不依赖 DataSource。
 */
class StateStoreSpiTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StateKitAutoConfiguration.class))
            .withUserConfiguration(StoreConfig.class)
            .withPropertyValues(
                    "state-kit.machines.mem.state-type=" + OrderStatus.class.getName(),
                    "state-kit.machines.mem.table=t_mem",
                    "state-kit.machines.mem.transitions[0].from=CREATED",
                    "state-kit.machines.mem.transitions[0].event=PAY",
                    "state-kit.machines.mem.transitions[0].to=PAID");

    @Test
    @SuppressWarnings("unchecked")
    void 自定义Store接管状态读写且不依赖DataSource() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var machine = (StateMachine<OrderStatus, Long>) context.getBean("mem");

            MemStore store = context.getBean(MemStore.class);
            store.states.put(1L, "CREATED");

            assertThat(machine.currentState(1L)).contains(OrderStatus.CREATED);
            machine.fire(1L, "PAY");

            assertThat(store.states.get(1L)).isEqualTo("PAID");
            assertThat(store.casCalls.get()).isEqualTo(1);
            assertThat(machine.currentState(1L)).contains(OrderStatus.PAID);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class StoreConfig {

        @Bean
        MemStore memStore() {
            return new MemStore();
        }
    }

    /** 内存实现：记录 CAS 调用次数供断言 */
    static class MemStore implements StateStore {

        final Map<Object, String> states = new HashMap<>();
        final AtomicInteger casCalls = new AtomicInteger();

        @Override
        public Optional<String> readState(Object id) {
            return Optional.ofNullable(states.get(id));
        }

        @Override
        public int casTransition(Object id, String from, String to, Map<String, Object> setColumns) {
            casCalls.incrementAndGet();
            if (from.equals(states.get(id))) {
                states.put(id, to);
                return 1;
            }
            return 0;
        }
    }
}
