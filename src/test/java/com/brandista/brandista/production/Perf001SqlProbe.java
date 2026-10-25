package com.brandista.brandista.production;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Captures only parameterized catalogue search SQL; never account or credential queries. */
public class Perf001SqlProbe implements StatementInspector {
    static final Set<String> searches = ConcurrentHashMap.newKeySet();
    @Override public String inspect(String sql) {
        if (sql.startsWith("select ") && sql.contains("from products ") && sql.contains("lower(")) searches.add(sql);
        return sql;
    }
}
