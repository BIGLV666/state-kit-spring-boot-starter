package io.github.biglv666.statekit.history;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/**
 * 响应式历史写入默认实现（0.2.0+）：R2DBC DatabaseClient 写历史表，
 * 与 Reactive fire 同一响应式事务。
 */
public class R2dbcHistoryRecorder implements ReactiveHistoryRecorder {

    private final DatabaseClient databaseClient;
    private final String tableName;

    public R2dbcHistoryRecorder(DatabaseClient databaseClient, String tableName) {
        this.databaseClient = databaseClient;
        this.tableName = tableName;
    }

    @Override
    public Mono<Void> record(HistoryRecord record) {
        return databaseClient.sql("""
                        INSERT INTO %s (machine, entity_id, from_state, to_state, event, operator_id, trace_id, create_time)
                        VALUES (:machine, :entityId, :fromState, :toState, :event, :operatorId, :traceId, :createTime)"""
                        .formatted(tableName))
                .bind("machine", record.getMachine())
                .bind("entityId", record.getEntityId())
                .bind("fromState", record.getFromState())
                .bind("toState", record.getToState())
                .bind("event", record.getEvent())
                .bind("operatorId", record.getOperatorId())
                .bind("traceId", record.getTraceId())
                .bind("createTime", record.getCreateTime() == null
                        ? java.time.LocalDateTime.now() : record.getCreateTime())
                .fetch().rowsUpdated().then();
    }
}
