/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QueryFunctionTest {

  private static DataSource dataSource;

  @BeforeAll
  static void setUp() throws Exception {
    var ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:querytest;DB_CLOSE_DELAY=-1");
    dataSource = ds;

    try (var conn = dataSource.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute(
          "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100), email VARCHAR(200))");
      stmt.execute("INSERT INTO users VALUES (1, 'Alice', 'alice@example.com')");
      stmt.execute("INSERT INTO users VALUES (2, 'Bob', 'bob@example.com')");
      stmt.execute("INSERT INTO users VALUES (3, 'Carol', 'carol@example.com')");
    }
  }

  @AfterAll
  static void tearDown() throws Exception {
    try (var conn = dataSource.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE users");
    }
  }

  @Test
  void nullDataSourceThrows() {
    assertThrows(IllegalArgumentException.class, () -> QueryFunction.create(null));
  }

  @Test
  void createReturnsHostFunction() {
    var fn = QueryFunction.create(dataSource);
    assertEquals("query", fn.name());
    assertNotNull(fn.description());
    assertNotNull(fn.handler());
  }

  @Test
  @SuppressWarnings("unchecked")
  void selectReturnsRows() throws Exception {
    var fn = QueryFunction.create(dataSource);
    var result =
        (List<Map<String, Object>>)
            fn.handler().handle(Map.of("sql", "SELECT * FROM users ORDER BY id"));

    assertEquals(3, result.size());
    assertEquals(1, result.get(0).get("ID"));
    assertEquals("Alice", result.get(0).get("NAME"));
    assertEquals("alice@example.com", result.get(0).get("EMAIL"));
    assertEquals("Bob", result.get(1).get("NAME"));
    assertEquals("Carol", result.get(2).get("NAME"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void selectWithWhereClause() throws Exception {
    var fn = QueryFunction.create(dataSource);
    var result =
        (List<Map<String, Object>>)
            fn.handler().handle(Map.of("sql", "SELECT name FROM users WHERE id = 2"));

    assertEquals(1, result.size());
    assertEquals("Bob", result.get(0).get("NAME"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void emptyResultSet() throws Exception {
    var fn = QueryFunction.create(dataSource);
    var result =
        (List<Map<String, Object>>)
            fn.handler().handle(Map.of("sql", "SELECT * FROM users WHERE id = 999"));

    assertTrue(result.isEmpty());
  }

  @Test
  @SuppressWarnings("unchecked")
  void selectWithCommentPrefix() throws Exception {
    var fn = QueryFunction.create(dataSource);
    var result =
        (List<Map<String, Object>>)
            fn.handler().handle(Map.of("sql", "/* analytics */ SELECT COUNT(*) AS cnt FROM users"));

    assertEquals(1, result.size());
    assertEquals(3L, result.get(0).get("CNT"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void selectWithLineCommentPrefix() throws Exception {
    var fn = QueryFunction.create(dataSource);
    var result =
        (List<Map<String, Object>>)
            fn.handler().handle(Map.of("sql", "-- user query\nSELECT COUNT(*) AS cnt FROM users"));

    assertEquals(1, result.size());
    assertEquals(3L, result.get(0).get("CNT"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT",
        "WITH",
        "SHOW",
        "DESCRIBE",
        "DESC",
        "EXPLAIN",
        "VALUES",
        "TABLE",
        "select",
        "Select"
      })
  void validateReadOnlyAllowsReadKeywords(String keyword) {
    QueryFunction.validateReadOnly(keyword + " something");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "INSERT INTO users VALUES (4, 'x', 'x')",
        "UPDATE users SET name = 'hacked'",
        "DELETE FROM users",
        "DROP TABLE users",
        "ALTER TABLE users ADD COLUMN x INT",
        "TRUNCATE TABLE users",
        "CREATE TABLE evil (id INT)",
        "GRANT ALL ON users TO PUBLIC",
        "REVOKE ALL ON users FROM PUBLIC",
        "MERGE INTO users USING dual ON (id=1) WHEN MATCHED THEN UPDATE SET name='x'",
        "UPSERT INTO users VALUES (1, 'x', 'x')",
        "REPLACE INTO users VALUES (1, 'x', 'x')",
        "CALL some_procedure()",
        "EXEC some_procedure",
        "EXECUTE some_procedure"
      })
  void validateReadOnlyRejectsMutationKeywords(String sql) {
    var ex =
        assertThrows(IllegalArgumentException.class, () -> QueryFunction.validateReadOnly(sql));
    assertTrue(ex.getMessage().contains("Only read-only queries are allowed"));
  }

  @Test
  void validateReadOnlyRejectsEmptySql() {
    assertThrows(IllegalArgumentException.class, () -> QueryFunction.validateReadOnly("   "));
  }

  @Test
  void missingSqlParamThrows() {
    var fn = QueryFunction.create(dataSource);
    assertThrows(IllegalArgumentException.class, () -> fn.handler().handle(Map.of()));
  }

  @Test
  void nonStringSqlParamThrows() {
    var fn = QueryFunction.create(dataSource);
    assertThrows(IllegalArgumentException.class, () -> fn.handler().handle(Map.of("sql", 12345)));
  }

  // --- Audit H9 hardening: MySQL executable comments + multi-statement rejection --------------

  @Test
  void rejectsMysqlExecutableCommentInsert() {
    assertThrows(
        IllegalArgumentException.class,
        () -> QueryFunction.validateReadOnly("/*! INSERT INTO x VALUES (1) */ SELECT 1"));
  }

  @Test
  void rejectsMysqlVersionGatedExecutableComment() {
    assertThrows(
        IllegalArgumentException.class,
        () -> QueryFunction.validateReadOnly("SELECT /*!50000 UPDATE y SET z=1 */ 1 FROM dual"));
  }

  @Test
  void rejectsMultiStatementSemicolonFollowedByStatement() {
    assertThrows(
        IllegalArgumentException.class,
        () -> QueryFunction.validateReadOnly("SELECT 1; DROP TABLE x"));
  }

  @Test
  void rejectsMultiStatementEvenWithWhitespace() {
    assertThrows(
        IllegalArgumentException.class,
        () -> QueryFunction.validateReadOnly("SELECT 1;\n  UPDATE x SET y=1"));
  }

  @Test
  void allowsTrailingSemicolon() {
    QueryFunction.validateReadOnly("SELECT 1;");
    QueryFunction.validateReadOnly("SELECT 1; ");
    QueryFunction.validateReadOnly("SELECT 1;\n");
  }

  @Test
  void allowsRegularBlockCommentBeforeKeyword() {
    // Plain /* ... */ comments (without the leading ! that makes them executable on MySQL) are
    // still legitimate and must be tolerated.
    QueryFunction.validateReadOnly("/* docs */ SELECT 1");
  }

  @Test
  void invalidSqlThrowsSqlException() {
    var fn = QueryFunction.create(dataSource);
    assertThrows(
        SQLException.class,
        () -> fn.handler().handle(Map.of("sql", "SELECT * FROM nonexistent_table")));
  }
}
