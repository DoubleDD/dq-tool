package com.example.dq.dialect;

import com.example.dq.model.DbType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/** 按数据库类型返回对应方言 */
@Component
public class DialectFactory {

    private final Map<DbType, DbDialect> dialects = new EnumMap<>(DbType.class);

    public DialectFactory() {
        register(new MySqlDialect());
        register(new PostgresDialect());
        register(new DmDialect());
        register(new KingbaseDialect());
        register(new OceanBaseDialect());
        register(new SqlServerDialect());
        register(new OracleDialect());
    }

    private void register(DbDialect dialect) {
        dialects.put(dialect.type(), dialect);
    }

    public DbDialect get(DbType type) {
        DbDialect d = dialects.get(type);
        if (d == null) {
            throw new IllegalArgumentException("不支持的数据库类型: " + type);
        }
        return d;
    }
}
