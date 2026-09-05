package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.history.HistoryQueryService;
import io.github.biglv666.statekit.history.HistoryRecorder;
import org.junit.jupiter.api.Test;
import io.github.biglv666.statekit.testapp.TestApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 历史模块关闭（默认）：启动零 DDL、容器内不存在任何历史相关 Bean。
 */
@SpringBootTest(classes = TestApplication.class,
        properties = "spring.datasource.url=jdbc:h2:mem:hist_disabled;DB_CLOSE_DELAY=-1")
class HistoryDisabledTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    org.springframework.beans.factory.ObjectProvider<HistoryRecorder> recorderProvider;

    @Autowired
    org.springframework.beans.factory.ObjectProvider<HistoryQueryService> queryServiceProvider;

    @Test
    void 历史表不存在_启动零DDL() {
        Boolean exists = jdbcTemplate.execute((ConnectionCallback<Boolean>) con -> {
            DatabaseMetaData meta = con.getMetaData();
            for (String name : new String[]{"SK_TRANSITION_HISTORY", "sk_transition_history"}) {
                try (ResultSet rs = meta.getTables(null, null, name, new String[]{"TABLE"})) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
            return false;
        });
        assertThat(exists).isFalse();
    }

    @Test
    void 容器内不存在历史相关Bean() {
        assertThat(recorderProvider.getIfAvailable()).isNull();
        assertThat(queryServiceProvider.getIfAvailable()).isNull();
    }
}
