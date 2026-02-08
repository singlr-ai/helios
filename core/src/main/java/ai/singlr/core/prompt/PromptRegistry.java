/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.prompt;

import java.util.List;

/**
 * Registry for versioned prompt templates.
 *
 * <p>Each prompt is identified by name and version. Only one version per name is active at a time.
 * Registering a new version automatically activates it and deactivates the previous active version.
 */
public interface PromptRegistry {

  /**
   * Registers a new prompt version. The version number is auto-assigned (previous max + 1). The new
   * version becomes active; the previous active version is deactivated.
   *
   * @param name the prompt name
   * @param content the template content with {variable} placeholders
   * @return the created prompt with version, id, and variables assigned
   */
  Prompt register(String name, String content);

  /**
   * Resolves the active version of a prompt by name.
   *
   * @param name the prompt name
   * @return the active prompt, or null if no prompt exists with that name
   */
  Prompt resolve(String name);

  /**
   * Resolves a specific version of a prompt.
   *
   * @param name the prompt name
   * @param version the version number
   * @return the prompt at that version, or null if not found
   */
  Prompt resolve(String name, int version);

  /**
   * Lists all versions of a prompt, ordered by version ascending.
   *
   * @param name the prompt name
   * @return all versions, or an empty list if no prompt exists with that name
   */
  List<Prompt> versions(String name);
}
