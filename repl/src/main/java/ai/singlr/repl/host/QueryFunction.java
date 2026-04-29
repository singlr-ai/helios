/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import ai.singlr.core.tool.ParameterType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Factory for the {@code query} host function. Executes read-only SQL against a JDBC {@link
 * DataSource}, returning rows as a list of maps.
 *
 * <p>Two layers of read-only enforcement:
 *
 * <ol>
 *   <li><b>Allowlist</b> — only {@code SELECT}, {@code WITH}, {@code SHOW}, {@code DESCRIBE},
 *       {@code EXPLAIN}, {@code VALUES}, and {@code TABLE} are accepted as the first SQL keyword.
 *       All other statements are rejected before reaching the database.
 *   <li><b>JDBC read-only</b> — the connection is opened with {@link
 *       java.sql.Connection#setReadOnly setReadOnly(true)}, so drivers that enforce this will
 *       reject any mutation that slips through.
 * </ol>
 *
 * <p>For defense-in-depth, the backing {@link DataSource} should connect as a <b>read-only database
 * user</b> with minimal privileges. The allowlist and {@code setReadOnly} are secondary safeguards;
 * the database-level permissions are the primary security boundary.
 *
 * <p>The {@link DataSource} is provided by the application developer and can be backed by any JDBC
 * driver (PostgreSQL, MySQL, Snowflake, DuckDB, etc.) with any connection pool.
 */
public final class QueryFunction {

  static final Set<String> ALLOWED_KEYWORDS =
      Set.of("SELECT", "WITH", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "VALUES", "TABLE");

  private static final Pattern FIRST_KEYWORD_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*(?:--[^\\n]*\\n\\s*)*(\\w+)", Pattern.DOTALL);

  private QueryFunction() {}

  /**
   * Create a query host function backed by the given data source.
   *
   * @param dataSource the JDBC data source to query
   * @return a host function that sandbox code can call as {@code query(sql)}
   */
  public static HostFunction create(DataSource dataSource) {
    if (dataSource == null) {
      throw new IllegalArgumentException("DataSource must not be null");
    }
    return new HostFunction(
        "query",
        "Execute a read-only SQL query. Parameters: sql (string). Returns a list of row maps.",
        List.of(
            HostParameter.required(
                "sql", ParameterType.STRING, "Read-only SQL statement (SELECT/WITH/EXPLAIN/...)")),
        params -> executeQuery(dataSource, params));
  }

  private static Object executeQuery(DataSource dataSource, Map<String, Object> params)
      throws SQLException {
    var sql = HostParams.requireString(params, "sql");
    validateReadOnly(sql);

    try (var connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      try (var statement = connection.createStatement();
          var rs = statement.executeQuery(sql)) {
        return resultSetToList(rs);
      }
    }
  }

  static void validateReadOnly(String sql) {
    var matcher = FIRST_KEYWORD_PATTERN.matcher(sql);
    if (!matcher.find()) {
      throw new IllegalArgumentException("Cannot determine SQL statement type from empty query");
    }
    var keyword = matcher.group(1).toUpperCase(Locale.ROOT);
    if (!ALLOWED_KEYWORDS.contains(keyword)) {
      throw new IllegalArgumentException("Only read-only queries are allowed, got: " + keyword);
    }
  }

  private static List<Map<String, Object>> resultSetToList(ResultSet rs) throws SQLException {
    var meta = rs.getMetaData();
    var columnCount = meta.getColumnCount();
    var rows = new ArrayList<Map<String, Object>>();

    while (rs.next()) {
      var row = new LinkedHashMap<String, Object>();
      for (var i = 1; i <= columnCount; i++) {
        row.put(meta.getColumnLabel(i), rs.getObject(i));
      }
      rows.add(row);
    }
    return rows;
  }
}
