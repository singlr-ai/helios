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
 * <p><b>The primary security boundary is the database user.</b> Configure the backing {@link
 * DataSource} with a database role that has no mutation privileges (PostgreSQL: {@code REVOKE
 * INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA ... FROM ...}; MySQL: {@code GRANT
 * SELECT ON ... TO ...}; etc.). The framework checks below are defense-in-depth, not a replacement
 * for that.
 *
 * <p>Framework checks, in order:
 *
 * <ol>
 *   <li><b>Reject MySQL executable comments</b>. {@code /*! …*}{@code /} is a MySQL extension that
 *       executes the wrapped SQL while looking like a comment to naive parsers. Rejected outright —
 *       the legitimate use is server-version-gated DDL, not user-typed read queries.
 *   <li><b>Reject multi-statement payloads</b>. {@code SELECT 1; DROP TABLE …} relies on the JDBC
 *       driver's allow-multi-queries flag. We refuse any semicolon followed by non-whitespace
 *       (trailing semicolons are allowed).
 *   <li><b>First-keyword allowlist</b>. Only {@code SELECT}, {@code WITH}, {@code SHOW}, {@code
 *       DESCRIBE}, {@code DESC}, {@code EXPLAIN}, {@code VALUES}, {@code TABLE} are accepted.
 *       Catches the obvious cases; would not catch {@code SELECT … INTO}, conditional-write CTEs,
 *       or other backend-specific bypasses — hence the "primary boundary is the DB user" rule.
 *   <li><b>JDBC read-only flag</b>. {@link java.sql.Connection#setReadOnly setReadOnly(true)} is
 *       advisory; driver behaviour varies. PostgreSQL's JDBC driver enforces it strictly via {@code
 *       SET TRANSACTION READ ONLY}; MySQL's driver historically did not until 5.1 and even now only
 *       sets a server var that DDL bypasses.
 * </ol>
 *
 * <p>The {@link DataSource} is provided by the application developer and can be backed by any JDBC
 * driver (PostgreSQL, MySQL, Snowflake, DuckDB, etc.) with any connection pool.
 */
public final class QueryFunction {

  static final Set<String> ALLOWED_KEYWORDS =
      Set.of("SELECT", "WITH", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "VALUES", "TABLE");

  private static final Pattern FIRST_KEYWORD_PATTERN =
      Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*)*(?:--[^\\n]*\\n\\s*)*(\\w+)", Pattern.DOTALL);

  /**
   * Detects MySQL executable comments ({@code /*! …*}{@code /} and version-gated {@code /*!50000
   * …*}{@code /}). MySQL evaluates the body of these comments; other engines treat them as regular
   * comments, so a payload like {@code /*!}{@code INSERT INTO x …*}{@code /} would pass the keyword
   * check but mutate on MySQL.
   */
  private static final Pattern MYSQL_EXECUTABLE_COMMENT = Pattern.compile("/\\*!");

  /**
   * Matches a statement separator: a semicolon followed (after optional whitespace) by any
   * non-whitespace character. A bare trailing semicolon is fine; {@code ; SELECT 1} is not.
   */
  private static final Pattern MULTI_STATEMENT = Pattern.compile(";\\s*\\S");

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
    if (MYSQL_EXECUTABLE_COMMENT.matcher(sql).find()) {
      throw new IllegalArgumentException(
          "MySQL executable comments (/*! ... */) are not allowed in read-only queries");
    }
    if (MULTI_STATEMENT.matcher(sql).find()) {
      throw new IllegalArgumentException(
          "Multi-statement payloads are not allowed; submit a single read-only statement");
    }
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
