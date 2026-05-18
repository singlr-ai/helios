/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import ai.singlr.core.model.Model;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.repl.InputBindings;
import ai.singlr.repl.ReplConfig;
import ai.singlr.repl.execution.JShellExecutionProvider;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.sandbox.JvmSandbox;
import ai.singlr.session.SessionOptions;
import ai.singlr.session.SessionPreset;
import ai.singlr.session.execution.ExecuteTool;
import ai.singlr.session.hooks.Hook;
import ai.singlr.session.memory.MemoryBackend;
import ai.singlr.session.permissions.Permission;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * CodeAct preset — the constrained agent loop where the model writes Java in a stateful JShell
 * sandbox to solve the task. Two flavours:
 *
 * <ul>
 *   <li>{@link #typed(Class, Class, Object)} — pure CodeAct. The only agent-loop tool is {@code
 *       Execute(runtime=JSHELL, script=...)}. Sandbox is pre-seeded with the typed input record as
 *       JShell variables. The model's final assistant message is the answer (a JSON object matching
 *       the output schema). {@code RequireExecuteCodeHook} prevents the model from claiming
 *       completion without ever running code.
 *   <li>{@link #withSubLm(Class, Class, Object, Model)} — RLM (Recursive Language Model) flavour.
 *       Adds {@code predict(instructions, input)} and {@code submit(output)} as in-sandbox host
 *       functions (callable from JShell code). The model's final answer is delivered via {@code
 *       submit(...)}; {@link OnSubmitStopHook} captures the submission and terminates the loop with
 *       the typed value.
 * </ul>
 *
 * <p>Both presets apply identical hard restrictions: {@link Permission#lockedDown()} (only {@code
 * Execute} and {@code AskUserQuestion} pass the permission gate) and {@link
 * MemoryBackend#disabled()} (no filesystem memory) so the sandbox is the world. Every preset
 * application constructs its own per-session state — {@link SubmittedValueHolder}, host functions
 * closed over the input record, execution provider — so applying the same preset to two builders
 * produces two genuinely independent sessions.
 *
 * @see CodeActStrategy
 * @see RlmStrategy
 * @see JShellExecutionProvider#singleSandbox(ReplConfig, String)
 */
public final class CodeActPreset {

  /** Schema fixture: the input record's class wrapped as an OutputSchema for prompt rendering. */
  private CodeActPreset() {}

  /**
   * Pure CodeAct preset. The agent loop has exactly one tool ({@code Execute}) and the model's
   * deliverable is its final assistant message — a JSON object matching {@code outputType}'s
   * schema, which the typed {@code runBlocking(message, schema)} path parses.
   *
   * @param inputType the input record class; non-null
   * @param outputType the output record class; non-null
   * @param inputValue the input value, exposed in the sandbox via {@link InputBindings}; may be
   *     null when the model is expected to read the user-message JSON instead
   * @param <I> input record type
   * @param <O> output record type
   * @return a {@link SessionPreset} that wires the CodeAct loop
   * @throws NullPointerException if {@code inputType} or {@code outputType} is null
   */
  public static <I, O> SessionPreset typed(Class<I> inputType, Class<O> outputType, I inputValue) {
    Objects.requireNonNull(inputType, "inputType must not be null");
    Objects.requireNonNull(outputType, "outputType must not be null");
    return builder -> applyTyped(builder, inputType, outputType, inputValue);
  }

  /**
   * RLM preset. Same as {@link #typed} plus in-sandbox {@code predict(instructions, input)} and
   * {@code submit(output)} host functions. The model produces its answer by calling {@code
   * submit(Map.of("field", value, ...))} from inside an {@code Execute} call; the {@link
   * OnSubmitStopHook} translates the holder's typed value into the loop's terminal {@code
   * ResultMessage.Success(json)}, which the typed {@code runBlocking(message, schema)} path
   * re-parses into the user's record type.
   *
   * @param inputType the input record class; non-null
   * @param outputType the output record class; non-null
   * @param inputValue the input value; may be null
   * @param subModel the sub-LM that backs {@code predict(...)}; non-null
   * @param <I> input record type
   * @param <O> output record type
   * @return a {@link SessionPreset} that wires the RLM loop
   * @throws NullPointerException if any of {@code inputType}, {@code outputType}, or {@code
   *     subModel} is null
   */
  public static <I, O> SessionPreset withSubLm(
      Class<I> inputType, Class<O> outputType, I inputValue, Model subModel) {
    Objects.requireNonNull(inputType, "inputType must not be null");
    Objects.requireNonNull(outputType, "outputType must not be null");
    Objects.requireNonNull(subModel, "subModel must not be null");
    return builder -> applyRlm(builder, inputType, outputType, inputValue, subModel);
  }

  private static <I, O> SessionOptions.Builder applyTyped(
      SessionOptions.Builder builder, Class<I> inputType, Class<O> outputType, I inputValue) {
    var outputSchema = OutputSchema.of(outputType);
    var inputSchema = OutputSchema.of(inputType);
    var boundFieldNames = InputBindings.boundFieldNames(inputType);
    var bindingsSnippet = InputBindings.snippet(inputType);
    var hostFunctions = new ArrayList<HostFunction>();
    hostFunctions.add(InputFunction.create(inputValue));
    var replConfig =
        ReplConfig.newBuilder()
            .withSandboxFactory(JvmSandbox.factory())
            .withHostFunctions(hostFunctions)
            .build();
    var provider = buildOwnedProvider(replConfig, bindingsSnippet);
    var skill =
        CodeActStrategy.skill(
            inputSchema,
            outputSchema,
            replConfig.maxOutputCharsToModel(),
            boundFieldNames,
            List.of(),
            null);
    return composeBase(builder, outputSchema, skill.instructions(), provider, List.of());
  }

  private static <I, O> SessionOptions.Builder applyRlm(
      SessionOptions.Builder builder,
      Class<I> inputType,
      Class<O> outputType,
      I inputValue,
      Model subModel) {
    var outputSchema = OutputSchema.of(outputType);
    var inputSchema = OutputSchema.of(inputType);
    var boundFieldNames = InputBindings.boundFieldNames(inputType);
    var bindingsSnippet = InputBindings.snippet(inputType);
    var holder = new SubmittedValueHolder();
    var hostFunctions = new ArrayList<HostFunction>();
    hostFunctions.add(InputFunction.create(inputValue));
    hostFunctions.add(PredictFunction.create(subModel));
    hostFunctions.add(SubmitFunction.create(outputSchema, holder));
    var replConfig =
        ReplConfig.newBuilder()
            .withSandboxFactory(JvmSandbox.factory())
            .withHostFunctions(hostFunctions)
            .build();
    var provider = buildOwnedProvider(replConfig, bindingsSnippet);
    var skill =
        RlmStrategy.skill(
            inputSchema,
            outputSchema,
            replConfig.maxOutputCharsToModel(),
            OptionalInt.empty(),
            boundFieldNames,
            List.of(),
            null);
    return composeBase(
        builder,
        outputSchema,
        skill.instructions(),
        provider,
        List.of(new OnSubmitStopHook(holder)));
  }

  private static SessionOptions.Builder composeBase(
      SessionOptions.Builder builder,
      OutputSchema<?> outputSchema,
      String systemPrompt,
      OwnedExecutionProvider provider,
      List<Hook> extraHooks) {
    var tools = new ArrayList<ToolBinding>();
    tools.add(ExecuteTool.binding(provider));
    builder
        .withTools(new ToolRegistry(tools))
        .withOutputSchema(outputSchema)
        .withSystemPrompt(systemPrompt)
        .withExecutionProvider(provider)
        .withMemoryBackend(MemoryBackend.disabled())
        .withPermission(Permission.lockedDown())
        .withHook(new RequireExecuteCodeHook());
    for (var hook : extraHooks) {
      builder.withHook(hook);
    }
    return builder;
  }

  /**
   * Construct a preset-internal {@link JShellExecutionProvider} wired for session-scoped lifetime:
   * the JVM shutdown hook is disabled (the session owns the provider, not the JVM) and the result
   * is wrapped in {@link OwnedExecutionProvider} so the session's {@code onSessionEnd} cascade
   * closes it deterministically. Builds get many session-options each from a long-lived service no
   * longer leak shutdown hooks.
   */
  private static OwnedExecutionProvider buildOwnedProvider(
      ReplConfig replConfig, String bindingsSnippet) {
    var provider =
        JShellExecutionProvider.newBuilder()
            .withReplConfig(replConfig)
            .withStartupSnippet(bindingsSnippet)
            .withShutdownHook(false)
            .build();
    return new OwnedExecutionProvider(provider);
  }
}
