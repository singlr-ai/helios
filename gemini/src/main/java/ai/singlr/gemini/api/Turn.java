/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A conversation turn in the Interactions API.
 *
 * @param role the role ("user" or "model")
 * @param content the content items in this turn
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Turn(String role, List<ContentItem> content) {

  public static Turn user(String text) {
    return new Turn("user", List.of(ContentItem.text(text)));
  }

  public static Turn user(List<ContentItem> content) {
    return new Turn("user", content);
  }

  public static Turn model(String text) {
    return new Turn("model", List.of(ContentItem.text(text)));
  }

  public static Turn model(List<ContentItem> content) {
    return new Turn("model", content);
  }
}
