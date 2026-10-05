CREATE TABLE IF NOT EXISTS t_order (
    id BIGINT PRIMARY KEY,
    status VARCHAR(32),
    pay_no VARCHAR(64),
    sign_time TIMESTAMP,
    remark VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS t_task (
    id BIGINT PRIMARY KEY,
    status VARCHAR(32),
    note VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS t_flow (
    id VARCHAR(64) PRIMARY KEY,
    status VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS t_order2 (
    id BIGINT PRIMARY KEY,
    status VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS t_orderv (
    id BIGINT PRIMARY KEY,
    status VARCHAR(32),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_retry (
    id BIGINT PRIMARY KEY,
    status VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS t_comp (
    id BIGINT PRIMARY KEY,
    status VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS t_wf (
    id BIGINT PRIMARY KEY,
    status VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS t_wf_item (
    id BIGINT PRIMARY KEY,
    status VARCHAR(32),
    workflow_id BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS t_timer_order (
    id BIGINT PRIMARY KEY,
    status VARCHAR(32),
    create_time TIMESTAMP,
    note VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS t_timer_str (
    id VARCHAR(64) PRIMARY KEY,
    status VARCHAR(32),
    create_time TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_timer_g (
    id BIGINT PRIMARY KEY,
    status VARCHAR(32),
    create_time TIMESTAMP
);
