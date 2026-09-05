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
