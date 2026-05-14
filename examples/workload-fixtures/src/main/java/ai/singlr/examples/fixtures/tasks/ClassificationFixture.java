/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.fixtures.tasks;

import ai.singlr.core.agent.Agent;
import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.common.Result;
import ai.singlr.core.events.CollectingEventSink;
import ai.singlr.core.events.HeliosEvent;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.trace.Trace;
import ai.singlr.examples.fixtures.Fixture;
import ai.singlr.examples.fixtures.FixtureOutcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bare-{@link Agent} + {@link OutputSchema} workload. No REPL substrate. The trajectory is one or
 * two iterations, the schema retry path is the only self-correction lever in scope. Validates that
 * the suite shape can absorb fixtures whose harness isn't CodeAct/RLM.
 */
public final class ClassificationFixture implements Fixture {

  public record Out(String label, String reasoning) {}

  @Override
  public String name() {
    return "classification";
  }

  @Override
  public String description() {
    return "Bare Agent + OutputSchema; 3-class sentiment classification on a deliberately unambiguous text.";
  }

  @Override
  public FixtureOutcome run(Model model) {
    var userInput =
        "Classify the sentiment of the following text as exactly one of: positive, negative,"
            + " neutral. Return your structured answer.\n\nText: \"This product completely changed"
            + " my life — best purchase I have ever made!\"";
    var expectedLabel = "positive";

    var sink = new CollectingEventSink();
    var config =
        AgentConfig.newBuilder()
            .withModel(model)
            .withSystemPrompt(
                "You are a precise sentiment classifier. Output exactly one of the three"
                    + " allowed labels in the 'label' field and a brief 'reasoning'.")
            .withMaxIterations(3)
            .withEventSink(sink)
            .build();
    var agent = new Agent(config);

    var started = System.nanoTime();
    var result = agent.run(SessionContext.of(userInput), OutputSchema.of(Out.class));
    var elapsed = Duration.ofNanos(System.nanoTime() - started);

    var notes = new ArrayList<String>();
    Trace trace = null;
    for (var event : sink.events()) {
      if (event instanceof HeliosEvent.RunCompleted rc) {
        trace = rc.trace();
      } else if (event instanceof HeliosEvent.RunFailed rf) {
        trace = rf.trace();
        notes.add("RunFailed: " + rf.error());
      }
    }

    boolean passed = false;
    switch (result) {
      case Result.Success<Response<Out>>(var response) -> {
        if (response.parsed() != null) {
          var rawLabel = response.parsed().label();
          var label = rawLabel == null ? "" : rawLabel.toLowerCase(Locale.ROOT).trim();
          passed = label.contains(expectedLabel);
          if (!passed) {
            notes.add("label mismatch: got '" + label + "', expected '" + expectedLabel + "'");
          }
        } else {
          notes.add("response had no parsed output");
        }
      }
      case Result.Failure<Response<Out>> f -> notes.add("agent run failed: " + f.error());
    }
    return new FixtureOutcome(passed, 0, List.of(), List.of(), trace, elapsed, notes);
  }
}
