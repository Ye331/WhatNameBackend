package com.whatname.iam.common.config;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Read-only check: refuse to run the new application against the legacy 23-table database. */
@Component
@Order(-100)
public class SchemaGuard implements ApplicationRunner {
  private final DataSource source;

  public SchemaGuard(DataSource source) {
    this.source = source;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    try (var c = source.getConnection()) {
      var tables = new HashSet<String>();
      try (var rows =
          c.getMetaData()
              .getTables(c.getCatalog(), c.getSchema(), "sys_%", new String[] {"TABLE"})) {
        while (rows.next()) tables.add(rows.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
      }
      if (tables.size() != 11 || tables.contains("sys_admin"))
        throw new IllegalStateException("需要新版 11 表数据库。请先运行 scripts/dev.ps1 setup，使用新库，不要对旧库删表。");
      boolean scope = false;
      try (var rows = c.getMetaData().getColumns(c.getCatalog(), c.getSchema(), "sys_user", null)) {
        while (rows.next())
          if (rows.getString("COLUMN_NAME").equalsIgnoreCase("management_scope")) scope = true;
      }
      if (!scope) throw new IllegalStateException("当前数据库不是简化版结构，请切换新库。");
    }
  }
}
