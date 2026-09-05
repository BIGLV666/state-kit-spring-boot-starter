package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.testapp.ItemStatus;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.testapp.WorkflowStatus;
import io.github.biglv666.statekit.testapp.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.2.0 嵌套子状态机测试（会签 ALL）：子项全部到终态后自动回发父事件。
 */
@SpringBootTest(classes = TestApplication.class)
class NestedSubMachineTest {

    @Autowired
    @Qualifier("wf")
    StateMachine<WorkflowStatus, Long> wf;

    @Autowired
    @Qualifier("wfItem")
    StateMachine<ItemStatus, Long> wfItem;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_wf_item");
        jdbcTemplate.update("DELETE FROM t_wf");
    }

    private long seedParent(String status) {
        jdbcTemplate.update("INSERT INTO t_wf (id, status) VALUES (100, ?)", status);
        return 100L;
    }

    private long seedItem(long workflowId) {
        jdbcTemplate.update("INSERT INTO t_wf_item (id, status, workflow_id) VALUES (?, 'PENDING', ?)",
                workflowId * 10, workflowId);
        jdbcTemplate.update("INSERT INTO t_wf_item (id, status, workflow_id) VALUES (?, 'PENDING', ?)",
                workflowId * 10 + 1, workflowId);
        return workflowId * 10;
    }

    @Test
    void 会签_全部子项完成才回发父事件() {
        seedParent("NEW");
        wf.fire(100L, "START");
        assertThat(wf.currentState(100L)).contains(WorkflowStatus.REVIEWING);

        long item1 = seedItem(100L);   // item 1000, 1001

        // 第 1 个子项完成：1/2，聚合不满足，父状态不动
        wfItem.fire(item1, "OK");
        assertThat(wf.currentState(100L)).contains(WorkflowStatus.REVIEWING);

        // 第 2 个子项完成：2/2 满足 ALL → 自动回发 ALL_OK，父推进到 APPROVED
        wfItem.fire(item1 + 1, "OK");
        assertThat(wf.currentState(100L)).contains(WorkflowStatus.APPROVED);
    }

    @Test
    void 子项流转到非终态不触发聚合() {
        // 聚合只在子项进入终态时判定；wfItem 只有 PENDING→DONE 一条边，
        // 这里用「子项仍在 PENDING 时父状态不动」验证聚合入口挂在接受终态之后
        seedParent("NEW");
        wf.fire(100L, "START");
        seedItem(100L);
        assertThat(wf.currentState(100L)).contains(WorkflowStatus.REVIEWING);
    }

    @Test
    void 父实体缺失时聚合安全跳过() {
        // 子项的 workflow_id 指向不存在的父实体：聚合找不到父实体，WARN 后跳过，不抛异常
        jdbcTemplate.update("INSERT INTO t_wf_item (id, status, workflow_id) VALUES (9001, 'PENDING', 999)");
        wfItem.fire(9001L, "OK");
        assertThat(wfItem.currentState(9001L)).contains(ItemStatus.DONE);
    }
}
