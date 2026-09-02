-- Oracle 11g 初始化(第二部分): 造数 + 统计信息
-- 规模为 23c 版本(init.sql/init2.sql)的 1/10: XE 限 1 CPU / 1GB RAM,
-- 且在 Apple Silicon 上走 Rosetta 仿真, 全量 1600 万行会跑数小时
-- 全部用 CROSS JOIN 生成行号, 规避 ORA-30009; 不用 /*+ APPEND */, 兼容触发器场景

CONN testuser/Test12345@localhost:1521/xe

-- users: 10万
INSERT INTO users (id, username, email, phone, gender, age, city, balance, status, created_at, updated_at)
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
FROM (
  SELECT (a.n - 1) * 400 + b.n AS lvl
  FROM (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 250) a
  CROSS JOIN (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 400) b
);
COMMIT;

-- products: 1万
INSERT INTO products (id, sku, name, category, price, stock, status, created_at)
SELECT lvl,
       'SKU' || LPAD(lvl, 8, '0'),
       'product_' || lvl,
       'category_' || MOD(lvl, 50),
       (10 + MOD(lvl, 50000)) / 10,
       MOD(lvl, 10000),
       CASE WHEN MOD(lvl, 100) = 0 THEN 0 ELSE 1 END,
       SYSTIMESTAMP - NUMTODSINTERVAL(MOD(lvl, 31536000), 'SECOND')
FROM (
  SELECT (a.n - 1) * 100 + b.n AS lvl
  FROM (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 100) a
  CROSS JOIN (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 100) b
);
COMMIT;

-- orders: 50万
INSERT INTO orders (id, order_no, user_id, total_amount, pay_type, status, remark, created_at, paid_at)
SELECT lvl,
       'ORD' || LPAD(lvl, 10, '0'),
       TRUNC(DBMS_RANDOM.VALUE(1, 100001)),
       (100 + MOD(lvl, 100000)) / 10,
       MOD(lvl, 4),
       MOD(lvl, 5),
       CASE WHEN MOD(lvl, 10) = 0 THEN 'remark for order ' || lvl ELSE NULL END,
       SYSTIMESTAMP - NUMTODSINTERVAL(MOD(lvl, 31536000), 'SECOND'),
       CASE WHEN MOD(lvl, 5) <> 0 THEN SYSTIMESTAMP - NUMTODSINTERVAL(MOD(lvl, 31400000), 'SECOND') ELSE NULL END
FROM (
  SELECT (a.n - 1) * 1000 + b.n AS lvl
  FROM (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 500) a
  CROSS JOIN (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 1000) b
);
COMMIT;

-- order_items: 100万, 每单平均 2 条
INSERT INTO order_items (id, order_id, product_id, quantity, unit_price, amount)
SELECT lvl,
       TRUNC((lvl - 1) / 2) + 1,
       TRUNC(DBMS_RANDOM.VALUE(1, 10001)),
       MOD(lvl, 5) + 1,
       (10 + MOD(lvl, 50000)) / 10,
       (10 + MOD(lvl, 50000)) * (MOD(lvl, 5) + 1) / 10
FROM (
  SELECT (a.n - 1) * 1000 + b.n AS lvl
  FROM (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 1000) a
  CROSS JOIN (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 1000) b
);
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
