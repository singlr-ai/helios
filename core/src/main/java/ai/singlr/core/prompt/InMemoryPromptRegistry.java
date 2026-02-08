/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.prompt;

import ai.singlr.core.common.Strings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory implementation of PromptRegistry. Thread-safe.
 *
 * <p>Useful for testing and applications that load prompts from configuration at startup.
 */
public class InMemoryPromptRegistry implements PromptRegistry {

  private final Map<String, List<Prompt>> prompts = new LinkedHashMap<>();

  @Override
  public synchronized Prompt register(String name, String content) {
    if (Strings.isBlank(name)) {
      throw new IllegalArgumentException("Prompt name must not be blank");
    }
    if (content == null) {
      throw new IllegalArgumentException("Prompt content must not be null");
    }

    var versions = prompts.computeIfAbsent(name, k -> new ArrayList<>());

    for (int i = 0; i < versions.size(); i++) {
      var p = versions.get(i);
      if (p.active()) {
        versions.set(i, Prompt.newBuilder(p).withActive(false).build());
      }
    }

    var nextVersion = versions.size() + 1;
    var prompt =
        Prompt.newBuilder()
            .withName(name)
            .withContent(content)
            .withVersion(nextVersion)
            .withActive(true)
            .build();

    versions.add(prompt);
    return prompt;
  }

  @Override
  public synchronized Prompt resolve(String name) {
    var versions = prompts.get(name);
    if (versions == null) {
      return null;
    }
    return versions.getLast();
  }

  @Override
  public synchronized Prompt resolve(String name, int version) {
    var versions = prompts.get(name);
    if (versions == null || version < 1 || version > versions.size()) {
      return null;
    }
    return versions.get(version - 1);
  }

  @Override
  public synchronized List<Prompt> versions(String name) {
    var versions = prompts.get(name);
    if (versions == null) {
      return List.of();
    }
    return List.copyOf(versions);
  }
}
