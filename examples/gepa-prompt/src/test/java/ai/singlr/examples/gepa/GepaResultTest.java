/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.gepa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.common.Ids;
import ai.singlr.core.eval.ParetoFrontier;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.tool.Tool;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class GepaResultTest {

  private static Model fakeModel() {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder().withContent("x").withFinishReason(FinishReason.STOP).build();
      }

      @Override
      public String id() {
        return "fake";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  @Test
  void applyToProducesNewConfigWithSwappedPrompt() {
    var original =
        AgentConfig.newBuilder()
            .withName("student")
            .withModel(fakeModel())
            .withSystemPrompt("original")
            .build();
    var result =
        new GepaResult(
            "improved", 0.9, new ParetoFrontier<>(1), new CandidateLineage(), 5, 10, 5, Ids.now());
    var applied = result.applyTo(original);
    assertEquals("improved", applied.systemPrompt());
    assertEquals("original", original.systemPrompt(), "original is untouched");
    assertNotSame(original, applied);
  }

  @Test
  void applyToRejectsNull() {
    var r =
        new GepaResult(
            "p",
            0.5,
            new ParetoFrontier<>(1),
            new CandidateLineage(),
            1,
            1,
            1,
            OffsetDateTime.now());
    assertThrows(IllegalArgumentException.class, () -> r.applyTo(null));
  }
}
