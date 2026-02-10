/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import ai.singlr.core.agent.Agent;
import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.common.Result;
import ai.singlr.core.model.Response;
import java.util.function.Function;

/**
 * A step that runs an {@link Agent} and converts its response to a {@link StepResult}.
 *
 * <p>When the {@link StepContext} carries a {@link SessionContext}, its session ID is reused so
 * that memory history is scoped to that session. Otherwise a fresh session is created.
 *
 * @param name the step name
 * @param agent the agent to run
 * @param inputMapper extracts the agent input message from the step context
 */
public record AgentStep(String name, Agent agent, Function<StepContext, String> inputMapper)
    implements Step {

  /** Creates an AgentStep that uses the context input directly. */
  public AgentStep(String name, Agent agent) {
    this(name, agent, StepContext::input);
  }

  @Override
  public StepResult execute(StepContext context) {
    try {
      var input = inputMapper.apply(context);
      var session =
          context.session() != null
              ? SessionContext.newBuilder()
                  .withUserId(context.session().userId())
                  .withSessionId(context.session().sessionId())
                  .withUserInput(input)
                  .build()
              : SessionContext.of(input);
      var result = agent.run(session);
      return switch (result) {
        case Result.Success<Response> s -> StepResult.success(name, s.value().content());
        case Result.Failure<Response> f -> StepResult.failure(name, f.error());
      };
    } catch (Exception e) {
      var msg = e.getMessage();
      return StepResult.failure(
          name, "Agent step failed: " + (msg != null ? msg : e.getClass().getSimpleName()));
    }
  }
}
