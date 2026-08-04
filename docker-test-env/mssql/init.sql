-- SQL Server 测试数据脚本
-- 规模: users 100万, products 10万, orders 500万, order_items 1000万
CREATE DATABASE testdb;
GO
ALTER DATABASE testdb SET RECOVERY SIMPLE;
GO
USE testdb;
GO

CREATE TABLE dbo.users (
    id          BIGINT IDENTITY(1,1) PRIMARY KEY,
    username    NVARCHAR(50)   NOT NULL,
    email       NVARCHAR(100)  NOT NULL,
    phone       VARCHAR(20)    NULL,
    gender      TINYINT        NOT NULL,
    age         INT            NOT NULL,
    city        NVARCHAR(50)   NULL,
    balance     DECIMAL(18,2)  NOT NULL,
    status      TINYINT        NOT NULL DEFAULT 1,
    created_at  DATETIME2      NOT NULL,
    updated_at  DATETIME2      NOT NULL
);

CREATE TABLE dbo.products (
    id          BIGINT IDENTITY(1,1) PRIMARY KEY,
    sku         VARCHAR(40)    NOT NULL UNIQUE,
    name        NVARCHAR(100)  NOT NULL,
    category    NVARCHAR(50)   NOT NULL,
    price       DECIMAL(18,2)  NOT NULL,
    stock       INT            NOT NULL,
    status      TINYINT        NOT NULL DEFAULT 1,
    created_at  DATETIME2      NOT NULL
);

CREATE TABLE dbo.orders (
    id           BIGINT IDENTITY(1,1) PRIMARY KEY,
    order_no     VARCHAR(40)   NOT NULL UNIQUE,
    user_id      BIGINT        NOT NULL,
    total_amount DECIMAL(18,2) NOT NULL,
    pay_type     TINYINT       NOT NULL,
    status       TINYINT       NOT NULL,
    remark       NVARCHAR(200) NULL,
    created_at   DATETIME2     NOT NULL,
    paid_at      DATETIME2     NULL,
    INDEX ix_orders_user (user_id),
    INDEX ix_orders_created (created_at)
);

CREATE TABLE dbo.order_items (
    id         BIGINT IDENTITY(1,1) PRIMARY KEY,
    order_id   BIGINT        NOT NULL,
    product_id BIGINT        NOT NULL,
    quantity   INT           NOT NULL,
    unit_price DECIMAL(18,2) NOT NULL,
    amount     DECIMAL(18,2) NOT NULL,
    INDEX ix_items_order (order_id),
    INDEX ix_items_product (product_id)
);
GO

USE testdb;
GO

-- 数字生成器: 最多约 43 亿行
WITH L0 AS (SELECT 1 AS c UNION ALL SELECT 1),
     L1 AS (SELECT 1 AS c FROM L0 a CROSS JOIN L0 b),
     L2 AS (SELECT 1 AS c FROM L1 a CROSS JOIN L1 b),
     L3 AS (SELECT 1 AS c FROM L2 a CROSS JOIN L2 b),
     L4 AS (SELECT 1 AS c FROM L3 a CROSS JOIN L3 b),
     L5 AS (SELECT 1 AS c FROM L4 a CROSS JOIN L4 b),
     nums AS (SELECT ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS n FROM L5)
INSERT INTO dbo.users (username, email, phone, gender, age, city, balance, status, created_at, updated_at)
SELECT TOP 1000000
    N'user_' + CAST(n AS NVARCHAR(20)),
    N'user' + CAST(n AS NVARCHAR(20)) + N'@test.com',
    '13' + RIGHT('000000000' + CAST(ABS(CHECKSUM(NEWID())) % 1000000000 AS VARCHAR(9)), 9),
    n % 2,
    18 + (n % 60),
    N'city_' + CAST(n % 100 AS NVARCHAR(10)),
    CAST((n % 100000) AS DECIMAL(18,2)) / 10,
    CASE WHEN n % 50 = 0 THEN 0 ELSE 1 END,
    DATEADD(SECOND, -n % 63072000, GETDATE()),
    DATEADD(SECOND, -n % 7776000, GETDATE())
FROM nums
OPTION (MAXDOP 4);
GO
CHECKPOINT;
GO

WITH L0 AS (SELECT 1 AS c UNION ALL SELECT 1),
     L1 AS (SELECT 1 AS c FROM L0 a CROSS JOIN L0 b),
     L2 AS (SELECT 1 AS c FROM L1 a CROSS JOIN L1 b),
     L3 AS (SELECT 1 AS c FROM L2 a CROSS JOIN L2 b),
     L4 AS (SELECT 1 AS c FROM L3 a CROSS JOIN L3 b),
     nums AS (SELECT ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS n FROM L4)
INSERT INTO dbo.products (sku, name, category, price, stock, status, created_at)
SELECT TOP 100000
    'SKU' + RIGHT('00000000' + CAST(n AS VARCHAR(8)), 8),
    N'product_' + CAST(n AS NVARCHAR(20)),
    N'category_' + CAST(n % 50 AS NVARCHAR(10)),
    CAST(10 + (n % 50000) AS DECIMAL(18,2)) / 10,
    n % 10000,
    CASE WHEN n % 100 = 0 THEN 0 ELSE 1 END,
    DATEADD(SECOND, -n % 31536000, GETDATE())
FROM nums
OPTION (MAXDOP 4);
GO
CHECKPOINT;
GO

WITH L0 AS (SELECT 1 AS c UNION ALL SELECT 1),
     L1 AS (SELECT 1 AS c FROM L0 a CROSS JOIN L0 b),
     L2 AS (SELECT 1 AS c FROM L1 a CROSS JOIN L1 b),
     L3 AS (SELECT 1 AS c FROM L2 a CROSS JOIN L2 b),
     L4 AS (SELECT 1 AS c FROM L3 a CROSS JOIN L3 b),
     L5 AS (SELECT 1 AS c FROM L4 a CROSS JOIN L4 b),
     nums AS (SELECT ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS n FROM L5)
INSERT INTO dbo.orders (order_no, user_id, total_amount, pay_type, status, remark, created_at, paid_at)
SELECT TOP 5000000
    'ORD' + RIGHT('0000000000' + CAST(n AS VARCHAR(10)), 10),
    (ABS(CHECKSUM(NEWID())) % 1000000) + 1,
    CAST(100 + (n % 100000) AS DECIMAL(18,2)) / 10,
    n % 4,
    n % 5,
    CASE WHEN n % 10 = 0 THEN N'remark for order ' + CAST(n AS NVARCHAR(20)) ELSE NULL END,
    DATEADD(SECOND, -n % 31536000, GETDATE()),
    CASE WHEN n % 5 <> 0 THEN DATEADD(SECOND, -n % 31400000, GETDATE()) ELSE NULL END
FROM nums
OPTION (MAXDOP 4);
GO
CHECKPOINT;
GO

-- 1000万条明细, 每单平均 2 条
WITH L0 AS (SELECT 1 AS c UNION ALL SELECT 1),
     L1 AS (SELECT 1 AS c FROM L0 a CROSS JOIN L0 b),
     L2 AS (SELECT 1 AS c FROM L1 a CROSS JOIN L1 b),
     L3 AS (SELECT 1 AS c FROM L2 a CROSS JOIN L2 b),
     L4 AS (SELECT 1 AS c FROM L3 a CROSS JOIN L3 b),
     L5 AS (SELECT 1 AS c FROM L4 a CROSS JOIN L4 b),
     nums AS (SELECT ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS n FROM L5)
INSERT INTO dbo.order_items (order_id, product_id, quantity, unit_price, amount)
SELECT TOP 10000000
    ((n - 1) / 2) + 1,
    (ABS(CHECKSUM(NEWID())) % 100000) + 1,
    (n % 5) + 1,
    CAST(10 + (n % 50000) AS DECIMAL(18,2)) / 10,
    CAST((10 + (n % 50000)) * ((n % 5) + 1) AS DECIMAL(18,2)) / 10
FROM nums
OPTION (MAXDOP 4);
GO
CHECKPOINT;
GO

UPDATE STATISTICS dbo.users;
UPDATE STATISTICS dbo.products;
UPDATE STATISTICS dbo.orders;
UPDATE STATISTICS dbo.order_items;
GO

SELECT 'users' AS tbl, COUNT_BIG(*) AS cnt FROM dbo.users
UNION ALL SELECT 'products', COUNT_BIG(*) FROM dbo.products
UNION ALL SELECT 'orders', COUNT_BIG(*) FROM dbo.orders
UNION ALL SELECT 'order_items', COUNT_BIG(*) FROM dbo.order_items;
GO
