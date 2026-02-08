/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * Singular Agentic Framework - PostgreSQL Persistence Module.
 *
 * <p>Provides PostgreSQL-backed implementations of core persistence interfaces using Helidon
 * DbClient, starting with {@link ai.singlr.core.prompt.PromptRegistry}.
 */
module ai.singlr.persistence {
  requires ai.singlr.core;
  requires io.helidon.dbclient;
  requires io.helidon.common.mapper;
  requires tools.jackson.databind;
  requires java.sql;

  exports ai.singlr.persistence;

  provides io.helidon.common.mapper.spi.MapperProvider with
      ai.singlr.persistence.mapper.DbTypeMapperProvider;
}
