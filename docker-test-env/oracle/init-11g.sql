-- Oracle 11g 造数脚本 (在 testuser 下执行)
-- 规模: users 100万, products 10万, orders 500万, order_items 1000万
-- 11g 适配:
--   1) 无 IDENTITY 列, 改用 序列+触发器, 且序列在数据装载后按 MAX(id)+1 创建
--   2) 11g CONNECT BY 超过约 200万行报 ORA-30009, orders/order_items 改用 CROSS JOIN 展开
--   3) 大批量 INSERT 显式写入 id, 保证 /*+ APPEND */ 直接路径插入生效

CREATE TABLE users (
    id          NUMBER(19)    PRIMARY KEY,
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
    id          NUMBER(19)    PRIMARY KEY,
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

-- users: 100万
INSERT /*+ APPEND */ INTO users (id, username, email, phone, gender, age, city, balance, status, created_at, updated_at)
SELECT lvl,
       'user_' || lvl,
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
INSERT /*+ APPEND */ INTO products (id, sku, name, category, price, stock, status, created_at)
SELECT lvl,
       'SKU' || LPAD(lvl, 8, '0'),
       'product_' || lvl,
       'category_' || MOD(lvl, 50),
       (10 + MOD(lvl, 50000)) / 10,
       MOD(lvl, 10000),
       CASE WHEN MOD(lvl, 100) = 0 THEN 0 ELSE 1 END,
       SYSTIMESTAMP - NUMTODSINTERVAL(MOD(lvl, 31536000), 'SECOND')
FROM (SELECT LEVEL AS lvl FROM dual CONNECT BY LEVEL <= 100000);
COMMIT;

-- orders: 500万 (CROSS JOIN 展开, 规避 ORA-30009)
INSERT /*+ APPEND */ INTO orders (id, order_no, user_id, total_amount, pay_type, status, remark, created_at, paid_at)
SELECT lvl,
       'ORD' || LPAD(lvl, 10, '0'),
       TRUNC(DBMS_RANDOM.VALUE(1, 1000001)),
       (100 + MOD(lvl, 100000)) / 10,
       MOD(lvl, 4),
       MOD(lvl, 5),
       CASE WHEN MOD(lvl, 10) = 0 THEN 'remark for order ' || lvl ELSE NULL END,
       SYSTIMESTAMP - NUMTODSINTERVAL(MOD(lvl, 31536000), 'SECOND'),
       CASE WHEN MOD(lvl, 5) <> 0 THEN SYSTIMESTAMP - NUMTODSINTERVAL(MOD(lvl, 31400000), 'SECOND') ELSE NULL END
FROM (
  SELECT (a.n - 1) * 2000 + b.n AS lvl
  FROM (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 2500) a
  CROSS JOIN (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 2000) b
);
COMMIT;

-- order_items: 1000万, 每单平均 2 条 (CROSS JOIN 展开, 规避 ORA-30009)
INSERT /*+ APPEND */ INTO order_items (id, order_id, product_id, quantity, unit_price, amount)
SELECT lvl,
       TRUNC((lvl - 1) / 2) + 1,
       TRUNC(DBMS_RANDOM.VALUE(1, 100001)),
       MOD(lvl, 5) + 1,
       (10 + MOD(lvl, 50000)) / 10,
       (10 + MOD(lvl, 50000)) * (MOD(lvl, 5) + 1) / 10
FROM (
  SELECT (a.n - 1) * 2000 + b.n AS lvl
  FROM (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 5000) a
  CROSS JOIN (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 2000) b
);
COMMIT;

-- 序列: 按当前最大 id 起始, 供触发器使用
DECLARE
  n NUMBER;
BEGIN
  SELECT MAX(id) + 1 INTO n FROM users;
  EXECUTE IMMEDIATE 'CREATE SEQUENCE seq_users START WITH ' || n || ' INCREMENT BY 1 NOCACHE';
  SELECT MAX(id) + 1 INTO n FROM products;
  EXECUTE IMMEDIATE 'CREATE SEQUENCE seq_products START WITH ' || n || ' INCREMENT BY 1 NOCACHE';
  SELECT MAX(id) + 1 INTO n FROM orders;
  EXECUTE IMMEDIATE 'CREATE SEQUENCE seq_orders START WITH ' || n || ' INCREMENT BY 1 NOCACHE';
  SELECT MAX(id) + 1 INTO n FROM order_items;
  EXECUTE IMMEDIATE 'CREATE SEQUENCE seq_order_items START WITH ' || n || ' INCREMENT BY 1 NOCACHE';
END;
/

-- 触发器: 模拟 IDENTITY 列, 插入时不写 id 则自动取序列值
CREATE OR REPLACE TRIGGER trg_users_bi BEFORE INSERT ON users FOR EACH ROW
BEGIN IF :NEW.id IS NULL THEN SELECT seq_users.NEXTVAL INTO :NEW.id FROM dual; END IF; END;
/
CREATE OR REPLACE TRIGGER trg_products_bi BEFORE INSERT ON products FOR EACH ROW
BEGIN IF :NEW.id IS NULL THEN SELECT seq_products.NEXTVAL INTO :NEW.id FROM dual; END IF; END;
/
CREATE OR REPLACE TRIGGER trg_orders_bi BEFORE INSERT ON orders FOR EACH ROW
BEGIN IF :NEW.id IS NULL THEN SELECT seq_orders.NEXTVAL INTO :NEW.id FROM dual; END IF; END;
/
CREATE OR REPLACE TRIGGER trg_order_items_bi BEFORE INSERT ON order_items FOR EACH ROW
BEGIN IF :NEW.id IS NULL THEN SELECT seq_order_items.NEXTVAL INTO :NEW.id FROM dual; END IF; END;
/

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
