package com.example.dq.dialect

import com.example.dq.model.DbType

/** OceanBase(MySQL 模式)方言:元数据与 SQL 语法同 MySQL */
class OceanBaseDialect : MySqlDialect() {

    override fun type(): DbType {
        return DbType.OCEANBASE
    }

    override fun driverClassName(): String {
        return "com.oceanbase.jdbc.Driver"
    }

    /** 在 MySQL 系统库之外,oceanbase 库存放集群/租户元数据 */
    override fun systemSchemas(): Set<String> {
        return super.systemSchemas() + "oceanbase"
    }
}
