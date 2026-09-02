-- Oracle 11g 初始化(第一部分): 建用户与表结构
-- 由 wnameless/oracle-xe-11g 以 SYSTEM 身份执行, 仅首次建库时跑一次
-- 注意: 11g 无 IDENTITY 列(12c+ 特性), dq-tool 只读不写, 主键由造数脚本显式赋值
-- 密码不用含 @ 的 Test@12345, sqlplus 连接串会把 @ 当主机分隔符解析

CREATE USER testuser IDENTIFIED BY Test12345
  DEFAULT TABLESPACE users QUOTA UNLIMITED ON users;
GRANT CONNECT, RESOURCE TO testuser;

CONN testuser/Test12345@localhost:1521/xe

CREATE TABLE users (
    id          NUMBER(19)     PRIMARY KEY,
    username    NVARCHAR2(50)  NOT NULL,
    email       NVARCHAR2(100) NOT NULL,
    phone       VARCHAR2(20),
    gender      NUMBER(1)      NOT NULL,
    age         NUMBER(3)      NOT NULL,
    city        NVARCHAR2(50),
    balance     NUMBER(18,2)   NOT NULL,
    status      NUMBER(1)      DEFAULT 1 NOT NULL,
    created_at  TIMESTAMP      NOT NULL,
    updated_at  TIMESTAMP      NOT NULL
);

CREATE TABLE products (
    id          NUMBER(19)     PRIMARY KEY,
    sku         VARCHAR2(40)   NOT NULL UNIQUE,
    name        NVARCHAR2(100) NOT NULL,
    category    NVARCHAR2(50)  NOT NULL,
    price       NUMBER(18,2)   NOT NULL,
    stock       NUMBER(9)      NOT NULL,
    status      NUMBER(1)      DEFAULT 1 NOT NULL,
    created_at  TIMESTAMP      NOT NULL
);

CREATE TABLE orders (
    id           NUMBER(19)    PRIMARY KEY,
    order_no     VARCHAR2(40)  NOT NULL UNIQUE,
    user_id      NUMBER(19)    NOT NULL,
    total_amount NUMBER(18,2)  NOT NULL,
    pay_type     NUMBER(1)     NOT NULL,
    status       NUMBER(1)     NOT NULL,
    remark       NVARCHAR2(200),
    created_at   TIMESTAMP     NOT NULL,
    paid_at      TIMESTAMP
);
CREATE INDEX ix_orders_user ON orders(user_id);
CREATE INDEX ix_orders_created ON orders(created_at);

CREATE TABLE order_items (
    id         NUMBER(19)    PRIMARY KEY,
    order_id   NUMBER(19)    NOT NULL,
    product_id NUMBER(19)    NOT NULL,
    quantity   NUMBER(5)     NOT NULL,
    unit_price NUMBER(18,2)  NOT NULL,
    amount     NUMBER(18,2)  NOT NULL
);
CREATE INDEX ix_items_order ON order_items(order_id);
CREATE INDEX ix_items_product ON order_items(product_id);
