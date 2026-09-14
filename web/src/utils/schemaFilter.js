/**
 * 库过滤白名单的提交口径(数据源编辑对话框「库过滤」页签、库列表页「库过滤」弹窗共用)。
 *
 * 后端规则:`data_source.schema_filter` 为空 = 未配置白名单 = **默认规则 —— 全部业务库(排除方言系统库)**,
 * 与页面上「系统库默认不勾选」的展示一致;配了白名单则只保留名单内的库。
 *
 * 因此:
 * - 勾选恰好等于默认集合(全部非系统库)时提交 `null`,继续走后端默认规则(存成「未配置」);
 * - 勾了系统库、或只勾了一部分时提交**显式白名单**,否则「全部勾选(含系统库)」会被后端默认规则吃掉,
 *   页面上勾的与接口返回的又不一致;
 * - 全不勾无法表达「一个都不要」(空名单在存储上归一为不过滤),按不过滤处理。
 *
 * @param {string[]} checked 当前勾选的库
 * @param {string[]} all 拉到的全量库(含系统库,来自 `?all=true`)
 * @param {(name: string) => boolean} isSystem 是否系统库(前端口径,与后端 `DbDialect.systemSchemas` 对齐)
 * @returns {string[]|null} 提交给 `PUT /datasources/{id}/schema-filter` 或随数据源保存的白名单
 */
export function toSchemaFilter(checked, all, isSystem) {
  if (!checked.length) return null
  const defaults = all.filter((db) => !isSystem(db))
  const sameAsDefault = checked.length === defaults.length && checked.every((db) => !isSystem(db))
  return sameAsDefault ? null : [...checked]
}
