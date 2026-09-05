package io.github.biglv666.statekit.bypass;

import io.github.biglv666.statekit.context.OperatorResolver;
import io.github.biglv666.statekit.event.StateBypassDetectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BYPASS 语句判定器（0.2.0+）：判断一条 SQL 是否为「绕过 fire 的 status 修改」。
 *
 * <p>判定规则：语句是 {@code UPDATE <受监视表> SET ...} 且 SET 子句包含该机器的
 * status 列，而执行线程不在框架 fire 上下文（{@link FireContext#inFire()}）。
 * 实体 id 尽力从 WHERE 的字面量解析（{@code WHERE ... id = 123}），
 * 参数化语句（{@code id = ?}）无法解析，entityId 为 null。</p>
 */
public final class BypassSqlMatcher {

    private static final Logger log = LoggerFactory.getLogger(BypassSqlMatcher.class);

    private final BypassWatchlist watchlist;
    private final ApplicationEventPublisher eventPublisher;
    private final OperatorResolver operatorResolver;
    private final io.github.biglv666.statekit.context.TraceIdResolver traceIdResolver;
    private final boolean publishEvent;

    public BypassSqlMatcher(BypassWatchlist watchlist, ApplicationEventPublisher eventPublisher,
                     OperatorResolver operatorResolver,
                     io.github.biglv666.statekit.context.TraceIdResolver traceIdResolver,
                     boolean publishEvent) {
        this.watchlist = watchlist;
        this.eventPublisher = eventPublisher;
        this.operatorResolver = operatorResolver;
        this.traceIdResolver = traceIdResolver;
        this.publishEvent = publishEvent;
    }

    /**
     * 检查并处置一条即将执行的 SQL。
     *
     * @param sql SQL 文本；PreparedStatement 的 SQL 在 prepare 时已捕获
     */
    void check(String sql) {
        if (sql == null || sql.isBlank() || FireContext.inFire()) {
            return; // 框架自身的 CAS，合法
        }
        String normalized = sql.replaceAll("\\s+", " ").trim();
        for (BypassWatchlist.Watched w : watchlist.all()) {
            Pattern p = Pattern.compile(
                    "(?i)\\bUPDATE\\s+[`\"\\[]?" + Pattern.quote(w.table()) + "[`\"\\]]?\\s+SET\\b");
            if (!p.matcher(normalized).find()) {
                continue;
            }
            String setClause = setClauseOf(normalized);
            if (setClause == null
                    || !Pattern.compile("(?i)\\b" + Pattern.quote(w.statusColumn()) + "\\b").matcher(setClause).find()) {
                continue;
            }
            detected(w, normalized);
            return;
        }
    }

    private void detected(BypassWatchlist.Watched w, String sql) {
        Object entityId = tryParseEntityId(w, sql);
        log.warn("检测到 BYPASS 绕改: 状态机 [{}] 的 status 列被绕过 fire 直接修改, sql=[{}], entityId=[{}]",
                w.machine(), sql, entityId);
        if (publishEvent) {
            String operatorId = operatorResolver == null ? null : operatorResolver.resolve();
            String traceId = traceIdResolver == null ? null : traceIdResolver.resolve();
            eventPublisher.publishEvent(new StateBypassDetectedEvent(
                    w.machine(), w.table(), sql, entityId, operatorId, traceId, LocalDateTime.now()));
        }
    }

    /** 截取 SET 子句（到 WHERE / 语句结束），用于 status 列匹配，避免把 WHERE 里的 status 误判为 SET */
    private String setClauseOf(String sql) {
        Matcher where = Pattern.compile("(?i)\\bWHERE\\b").matcher(sql);
        int end = where.find() ? where.start() : sql.length();
        return sql.substring(0, end);
    }

    private Object tryParseEntityId(BypassWatchlist.Watched w, String sql) {
        Matcher m = Pattern.compile("(?i)\\b" + Pattern.quote(w.idColumn()) + "\\s*=\\s*'?([A-Za-z0-9_-]+)'?")
                .matcher(sql);
        if (m.find()) {
            String value = m.group(1);
            try {
                return Long.valueOf(value);
            } catch (NumberFormatException e) {
                return value;
            }
        }
        return null;
    }
}
