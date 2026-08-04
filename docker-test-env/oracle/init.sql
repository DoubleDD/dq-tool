-- Oracle 测试数据脚本 (在 testuser 下执行)
-- 规模: users 100万, products 10万, orders 500万, order_items 1000万

CREATE TABLE users (
    id          NUMBER(19)    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
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
    id          NUMBER(19)    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku         VARCHAR2(40)   NOT NULL UNIQUE,
    name        NVARCHAR2(100) NOT NULL,
    category    NVARCHAR2(50)  NOT NULL,
    price       NUMBER(18,2)   NOT NULL,
    stock       NUMBER(9)      NOT NULL,
    status      NUMBER(1)      DEFAULT 1 NOT NULL,
    created_at  TIMESTAMP      NOT NULL
);

CREATE TABLE orders (
    id           NUMBER(19)    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
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
    id         NUMBER(19)    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id   NUMBER(19)    NOT NULL,
    product_id NUMBER(19)    NOT NULL,
    quantity   NUMBER(5)     NOT NULL,
    unit_price NUMBER(18,2)  NOT NULL,
    amount     NUMBER(18,2)  NOT NULL
);
CREATE INDEX ix_items_order ON order_items(order_id);
CREATE INDEX ix_items_product ON order_items(product_id);

-- users: 100万
INSERT /*+ APPEND */ INTO users (username, email, phone, gender, age, city, balance, status, created_at, updated_at)
SELECT 'user_' || lvl,
       'user' || lvl || '@test.com',
       '13' || LPAD(TRUNC(DBMS_RANDOM.VALUE(0, 999999999)), 9, '0'),
       MOD(lvl, 2),
       18 + MOD(lvl, 60),
       'city_' || MOD(lvl, 100),
       MOD(lvl, 100000) / 10,
       CASE WHEN MOD(lvl, 50) = 0 THEN 0 ELSE 1 END,
       SYSTIMESTAMP - NUMTODSINTERVAL(MOD(lvl, 63072000), 'SECOND'),
       SYSTIMESTAMP - NUMTODSINTERVAL(MOD(lvl, 7776000), 'SECOND')
FROM (SELECT LEVEL AS lvl FROM dual CONNECT BY LEVEL <= 1000000);
COMMIT;

-- products: 10万
INSERT /*+ APPEND */ INTO products (sku, name, category, price, stock, status, created_at)
SELECT 'SKU' || LPAD(lvl, 8, '0'),
       'product_' || lvl,
       'category_' || MOD(lvl, 50),
       (10 + MOD(lvl, 50000)) / 10,
       MOD(lvl, 10000),
       CASE WHEN MOD(lvl, 100) = 0 THEN 0 ELSE 1 END,
       SYSTIMESTAMP - NUMTODSINTERVAL(MOD(lvl, 31536000), 'SECOND')
FROM (SELECT LEVEL AS lvl FROM dual CONNECT BY LEVEL <= 100000);
COMMIT;

-- orders: 500万
INSERT /*+ APPEND */ INTO orders (order_no, user_id, total_amount, pay_type, status, remark, created_at, paid_at)
SELECT 'ORD' || LPAD(lvl, 10, '0'),
       TRUNC(DBMS_RANDOM.VALUE(1, 1000001)),
       (100 + MOD(lvl, 100000)) / 10,
       MOD(lvl, 4),
       MOD(lvl, 5),
       CASE WHEN MOD(lvl, 10) = 0 THEN 'remark for order ' || lvl ELSE NULL END,
       SYSTIMESTAMP - NUMTODSINTERVAL(MOD(lvl, 31536000), 'SECOND'),
       CASE WHEN MOD(lvl, 5) <> 0 THEN SYSTIMESTAMP - NUMTODSINTERVAL(MOD(lvl, 31400000), 'SECOND') ELSE NULL END
FROM (SELECT LEVEL AS lvl FROM dual CONNECT BY LEVEL <= 5000000);
COMMIT;

-- order_items: 1000万, 每单平均 2 条
INSERT /*+ APPEND */ INTO order_items (order_id, product_id, quantity, unit_price, amount)
SELECT TRUNC((lvl - 1) / 2) + 1,
       TRUNC(DBMS_RANDOM.VALUE(1, 100001)),
       MOD(lvl, 5) + 1,
       (10 + MOD(lvl, 50000)) / 10,
       (10 + MOD(lvl, 50000)) * (MOD(lvl, 5) + 1) / 10
FROM (SELECT LEVEL AS lvl FROM dual CONNECT BY LEVEL <= 10000000);
COMMIT;

BEGIN
  DBMS_STATS.GATHER_TABLE_STATS(USER, 'USERS');
  DBMS_STATS.GATHER_TABLE_STATS(USER, 'PRODUCTS');
  DBMS_STATS.GATHER_TABLE_STATS(USER, 'ORDERS');
  DBMS_STATS.GATHER_TABLE_STATS(USER, 'ORDER_ITEMS');
END;
/

SELECT 'users' AS tbl, COUNT(*) AS cnt FROM users
UNION ALL SELECT 'products', COUNT(*) FROM products
UNION ALL SELECT 'orders', COUNT(*) FROM orders
UNION ALL SELECT 'order_items', COUNT(*) FROM order_items;
EXIT;
