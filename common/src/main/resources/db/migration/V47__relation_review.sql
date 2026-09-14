-- ER 关系人工审核留痕:原始关系(大模型推导产出快照)+ 最终关系的人工审核标记
-- 背景:关系由程序/大模型推导,须人工裁决;重新推导时结果高度相似,之前被否决/确认的关系会再次出现,
-- 必须保留上一次人工审核后的结论,只有「新关系」才留给人工审核。
-- 因此:table_relation 仍是最终关系(唯一事实来源,图上/接口上读它);
--      新增 table_relation_original 保存「大模型生成的关系」快照,每次推导刷新、人工审核不改变它,
--      导出 ER 关系时由两者相减得到「关系变化」(含人工新增 MANUAL / 人工删除的关系)。

CREATE TABLE IF NOT EXISTS table_relation_original (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    db_name       VARCHAR(256) NOT NULL DEFAULT '',
    schema_name   VARCHAR(256) NOT NULL,
    one_table     VARCHAR(256) NOT NULL,      -- 推导时的唯一侧(方向口径同 table_relation)
    one_column    VARCHAR(256) NOT NULL,
    many_table    VARCHAR(256) NOT NULL,      -- 推导时的重复侧
    many_column   VARCHAR(256) NOT NULL,
    cardinality   VARCHAR(32) NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE',  -- 推导产出恒为 CANDIDATE,人工审核不修改本表
    source        VARCHAR(16) NOT NULL,       -- NAME_MATCH/SEMANTIC/MANUAL
    confidence    VARCHAR(8),
    overlap_ratio DOUBLE,
    remark        VARCHAR(1024),              -- 推导时写入的说明(非人工否决原因)
    derived_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_table_relation_original ON table_relation_original(datasource_id, db_name, schema_name, one_table, one_column, many_table, many_column);
CREATE INDEX IF NOT EXISTS idx_table_relation_original_one ON table_relation_original(datasource_id, db_name, schema_name, one_table);
CREATE INDEX IF NOT EXISTS idx_table_relation_original_many ON table_relation_original(datasource_id, db_name, schema_name, many_table);

-- 最终关系的人工审核标记:reviewed=true 表示人工已裁决(确认/否决/人工补充),重新推导不得覆盖
ALTER TABLE table_relation ADD COLUMN IF NOT EXISTS reviewed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE table_relation ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP;

-- 存量数据回填:非候选态(确认/否决)与人工补充的关系视为已经过人工审核
UPDATE table_relation SET reviewed = TRUE, reviewed_at = updated_at
WHERE reviewed = FALSE AND (status <> 'CANDIDATE' OR source = 'MANUAL');

-- 存量关系的原始关系快照回填:历史推导数据无从还原,以当前推导属性 + 状态 CANDIDATE 作为「原始基线」,
-- 这样此前被人工确认/否决的关系在「关系变化」里能正确体现为 候选→确认/否决;人工补充(MANUAL)不填,算人工新增。
-- 同一字段对(方向无关)只保留 id 最小的一条,防止历史里可能存在反向重复行时撞唯一键。
INSERT INTO table_relation_original
    (datasource_id, db_name, schema_name, one_table, one_column, many_table, many_column,
     cardinality, status, source, confidence, overlap_ratio, remark, derived_at, updated_at)
SELECT t.datasource_id, t.db_name, t.schema_name, t.one_table, t.one_column, t.many_table, t.many_column,
       t.cardinality, 'CANDIDATE', t.source, t.confidence, t.overlap_ratio, t.remark, t.created_at, t.updated_at
FROM table_relation t
WHERE t.source <> 'MANUAL'
  AND t.id = (
      SELECT MIN(x.id) FROM table_relation x
      WHERE x.datasource_id = t.datasource_id AND x.db_name = t.db_name AND x.schema_name = t.schema_name
        AND x.source <> 'MANUAL'
        AND ((x.one_table = t.one_table AND x.one_column = t.one_column
              AND x.many_table = t.many_table AND x.many_column = t.many_column)
          OR (x.one_table = t.many_table AND x.one_column = t.many_column
              AND x.many_table = t.one_table AND x.many_column = t.one_column))
  );
