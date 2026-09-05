package io.github.biglv666.statekit;

/**
 * fire 的可变参数令牌，通过静态工厂区分两类语义，二者严格分离、同名也不互转：
 *
 * <ul>
 *     <li>{@link #param(String, Object)}：内存上下文。仅供守卫 / 动作通过
 *         {@link StateTx#param(String, Class)} 消费，<b>不落库</b>；</li>
 *     <li>{@link #set(String, Object)}：落库列。拼进 CAS 那条 UPDATE 的 SET 子句，
 *         与 status 同一条 SQL 落库（写入同一行、同一事务、同一行锁覆盖），
 *         <b>不会出现在 {@link StateTx#param(String, Class)} 里</b>。</li>
 * </ul>
 *
 * <p>示例：支付动作既要让守卫拿到交易号做校验，又要把交易号落到业务表列上：</p>
 *
 * <pre>{@code
 * orderFlow.fire(orderId, "PAY",
 *         param("txnNo", txnNo),      // 上下文：守卫/动作可读
 *         set("pay_no", txnNo));      // 落库：UPDATE t_order SET status='PAID', pay_no=? WHERE ...
 * }</pre>
 *
 * <p>set 的列名在创建时即做白名单校验（{@code ^[A-Za-z][A-Za-z0-9_]*$}），
 * 非法列名立即抛 {@link IllegalArgumentException}，杜绝 SQL 注入；
 * 列值一律走 PreparedStatement 参数绑定，不参与 SQL 拼接。</p>
 *
 * @see StateMachine#fire(Object, String, FireArg...)
 */
public final class FireArg {

    /** 列名白名单：字母开头，只允许字母、数字、下划线 */
    private static final String COLUMN_PATTERN = "^[A-Za-z][A-Za-z0-9_]*$";

    /** 令牌类型（框架内部使用，public 供 core 包 switch 使用） */
    public enum Kind { PARAM, SET }

    private final Kind kind;
    private final String key;
    private final Object value;

    private FireArg(Kind kind, String key, Object value) {
        this.kind = kind;
        this.key = key;
        this.value = value;
    }

    /**
     * 声明一个内存上下文参数，供守卫 / 动作通过 {@link StateTx#param(String, Class)} 读取。
     *
     * @param key   参数名，不允许为空
     * @param value 参数值，允许为 null
     * @return 参数令牌
     */
    public static FireArg param(String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("param 的 key 不允许为空");
        }
        return new FireArg(Kind.PARAM, key, value);
    }

    /**
     * 声明一个落库列，与 status 同一条 CAS UPDATE 语句写入业务表。
     *
     * @param column 业务表列名，必须匹配 {@code ^[A-Za-z][A-Za-z0-9_]*$}（防 SQL 注入白名单）
     * @param value  列值，走 PreparedStatement 参数绑定，允许为 null
     * @return 落库令牌
     */
    public static FireArg set(String column, Object value) {
        if (column == null || !column.matches(COLUMN_PATTERN)) {
            throw new IllegalArgumentException(
                    "set 的列名必须是合法标识符（字母开头，仅含字母/数字/下划线），收到: \"" + column + "\"");
        }
        return new FireArg(Kind.SET, column, value);
    }

    /** 令牌类型（框架内部使用） */
    public Kind kind() {
        return kind;
    }

    /** 参数名或列名（框架内部使用） */
    public String key() {
        return key;
    }

    /** 参数值或列值（框架内部使用） */
    public Object value() {
        return value;
    }
}
