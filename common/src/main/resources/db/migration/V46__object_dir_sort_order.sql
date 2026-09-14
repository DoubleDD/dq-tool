-- 对象管理(数据目录):object_dir.sort_order 列(同级目录手工排序序号,越小越靠前)。
-- 默认按创建时间排序:存量行按同级 (created_at, id) 顺序回填 0..n-1 连续序号;
-- 之后新建目录取同级 max+1(即追加到同级末尾,创建时间最晚 == 排最后),默认顺序与创建时间顺序一致;
-- 用户拖动重排后由 service 按期望顺序把同级 sort_order 整体重写为 0..n-1。
ALTER TABLE object_dir ADD COLUMN IF NOT EXISTS sort_order INT;

UPDATE object_dir d SET sort_order = (
    SELECT COUNT(*) FROM object_dir x
    WHERE x.datasource_id = d.datasource_id
      AND x.parent_id = d.parent_id
      AND (x.created_at < d.created_at OR (x.created_at = d.created_at AND x.id < d.id))
)
WHERE d.sort_order IS NULL;
