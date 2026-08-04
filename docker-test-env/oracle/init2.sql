-- Oracle 造数(第二部分): 用 CROSS JOIN 替代超长 CONNECT BY, 规避 ORA-30009
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
FROM (
  SELECT (a.n - 1) * 2000 + b.n AS lvl
  FROM (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 2500) a
  CROSS JOIN (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 2000) b
);
COMMIT;

-- order_items: 1000万, 每单平均 2 条
INSERT /*+ APPEND */ INTO order_items (order_id, product_id, quantity, unit_price, amount)
SELECT TRUNC((lvl - 1) / 2) + 1,
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

BEGIN
  DBMS_STATS.GATHER_TABLE_STATS(USER, 'ORDERS');
  DBMS_STATS.GATHER_TABLE_STATS(USER, 'ORDER_ITEMS');
END;
/

SELECT 'users' AS tbl, COUNT(*) AS cnt FROM users
UNION ALL SELECT 'products', COUNT(*) FROM products
UNION ALL SELECT 'orders', COUNT(*) FROM orders
UNION ALL SELECT 'order_items', COUNT(*) FROM order_items;
EXIT;
