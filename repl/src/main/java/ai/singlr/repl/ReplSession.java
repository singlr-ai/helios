/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.host.HostFunctionRegistry;
import ai.singlr.repl.host.SubmitFunction;
import ai.singlr.repl.sandbox.ExecuteParams;
import ai.singlr.repl.sandbox.ExecutionRequest;
import ai.singlr.repl.sandbox.ExecutionResult;
import ai.singlr.repl.sandbox.Sandbox;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;

/**
 * A stateful REPL session backed by a sandbox. Each session has its own sandbox instance, host
 * function registry, and execution history.
 *
 * <p>Sessions are bound to a single agent run and must be closed when done to release sandbox
 * resources and the concurrency semaphore permit.
 */
public final class ReplSession implements AutoCloseable {

  private final ReplConfig config;
  private final Sandbox sandbox;
  private final HostFunctionRegistry registry;
  private final AtomicReference<Object> submittedValue;
  private final AtomicInteger predictCallCount;
  private final AtomicInteger currentIteration;
  private final Set<String> calledSignatures;
  private final List<PredictCall> predictCalls;
  private final Map<String, AtomicInteger> hostFnCounts;
  private final List<ExecutionResult> history = new ArrayList<>();
  private final Semaphore semaphore;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private ReplSession(
      ReplConfig config,
      Sandbox sandbox,
      HostFunctionRegistry registry,
      Semaphore semaphore,
      AtomicReference<Object> submittedValue,
      AtomicInteger predictCallCount,
      AtomicInteger currentIteration,
      Set<String> calledSignatures,
      List<PredictCall> predictCalls,
      Map<String, AtomicInteger> hostFnCounts) {
    this.config = config;
    this.sandbox = sandbox;
    this.registry = registry;
    this.semaphore = semaphore;
    this.submittedValue = submittedValue;
    this.predictCallCount = predictCallCount;
    this.currentIteration = currentIteration;
    this.calledSignatures = calledSignatures;
    this.predictCalls = predictCalls;
    this.hostFnCounts = hostFnCounts;
  }

  /**
   * Create a new session from the given config, acquiring a semaphore permit.
   *
   * @param config the REPL configuration
   * @param semaphore the concurrency limiter
   * @return a new session ready for code execution
   * @throws ReplException if no permits are available or sandbox creation fails
   */
  public static ReplSession create(ReplConfig config, Semaphore semaphore) {
    if (config == null) {
      throw new IllegalArgumentException("Config must not be null");
    }
    if (semaphore == null) {
      throw new IllegalArgumentException("Semaphore must not be null");
    }
    if (!semaphore.tryAcquire()) {
      throw new ReplException(
          "Max concurrent sessions reached (" + (semaphore.availablePermits()) + " available)");
    }
    try {
      var registry = new HostFunctionRegistry();
      var predictCallCount = new AtomicInteger();
      var currentIteration = new AtomicInteger();
      var calledSignatures = Collections.synchronizedSet(new LinkedHashSet<String>());
      var predictCalls = Collections.synchronizedList(new ArrayList<PredictCall>());
      var hostFnCounts = new ConcurrentHashMap<String, AtomicInteger>();
      var matcher =
          config.signatureMatcher() != null
              ? config.signatureMatcher()
              : (BiPredicate<String, String>) String::equals;
      for (var fn : config.hostFunctions()) {
        registry.register(
            wrapForTracking(
                fn,
                predictCallCount,
                currentIteration,
                config.maxLlmCalls(),
                calledSignatures,
                predictCalls,
                hostFnCounts,
                config.requiredPredictSignatures(),
                matcher));
      }
      var submittedValue = new AtomicReference<>();
      if (registry.get("submit") == null) {
        registry.register(SubmitFunction.create(submittedValue, config.submitSchema()));
      }
      var sandbox = config.sandboxFactory().create(registry);
      return new ReplSession(
          config,
          sandbox,
          registry,
          semaphore,
          submittedValue,
          predictCallCount,
          currentIteration,
          calledSignatures,
          predictCalls,
          hostFnCounts);
    } catch (Exception e) {
      semaphore.release();
      if (e instanceof ReplException re) {
        throw re;
      }
      throw new ReplException("Failed to create session", e);
    }
  }

  /**
   * Names excluded from {@link #calledHostFunctions()}. Framework-reserved primitives ({@code
   * predict}, {@code submit}, {@code fetch}, {@code query}, {@code getInput}, {@code __getInput},
   * {@code __call}) are framework-provided and uninteresting for "what data tools did the model
   * use" metrics. {@code predict} additionally has its own structured {@link #predictCalls()}
   * accessor. The map contains exactly the Skill-registered host functions.
   */
  private static final Set<String> CALLED_HOST_FN_EXCLUDES =
      Set.of("predict", "submit", "fetch", "query", "getInput", "__getInput", "__call");

  /**
   * Wrap every host function so the session tracks downstream-relevant trajectory data:
   *
   * <ul>
   *   <li><b>Per-call counts</b> for non-excluded functions go into {@code hostFnCounts}. Excluded
   *       names ({@link #CALLED_HOST_FN_EXCLUDES}): {@code predict} (has its own structured {@link
   *       PredictCall} accessor), {@code __getInput} (framework-internal).
   *   <li><b>Predict-specific instrumentation</b>: every {@code predict()} call becomes a {@link
   *       PredictCall} stamped with the current iteration index. Registered {@link
   *       RequiredPredictSignature}s are matched (subject to the configured matcher) and recorded
   *       in {@code calledSignatures}. Per-session {@code maxLlmCalls} budget is enforced via
   *       {@link SandboxBudgetExceededException}.
   * </ul>
   *
   * <p>The wrapper is always installed (no early return). Cost per non-predict call: a single
   * {@link AtomicInteger#incrementAndGet}. Cost per predict call: appending a {@link PredictCall}
   * to a synchronized list plus signature matching.
   */
  private static HostFunction wrapForTracking(
      HostFunction fn,
      AtomicInteger predictCallCount,
      AtomicInteger currentIteration,
      int maxLlmCalls,
      Set<String> calledSignatures,
      List<PredictCall> predictCalls,
      Map<String, AtomicInteger> hostFnCounts,
      List<RequiredPredictSignature> required,
      BiPredicate<String, String> matcher) {
    var inner = fn.handler();
    var name = fn.name();
    var isPredict = "predict".equals(name);
    var trackingEnabled = required != null && !required.isEmpty();
    var budgetEnabled = isPredict && maxLlmCalls > 0;
    return new HostFunction(
        name,
        fn.description(),
        fn.parameters(),
        params -> {
          if (!CALLED_HOST_FN_EXCLUDES.contains(name)) {
            hostFnCounts.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
          }
          if (isPredict) {
            var instructions = params.get("instructions") instanceof String s ? s : "";
            var input = params.get("input") instanceof String s ? s : "";
            predictCalls.add(new PredictCall(instructions, input, currentIteration.get()));
            if (trackingEnabled && !instructions.isEmpty()) {
              for (var sig : required) {
                if (matcher.test(sig.instructions(), instructions)) {
                  calledSignatures.add(sig.name());
                }
              }
            }
            if (budgetEnabled) {
              var n = predictCallCount.incrementAndGet();
              if (n > maxLlmCalls) {
                throw new SandboxBudgetExceededException(
                    SandboxBudgetExceededException.BudgetKind.LLM_CALLS,
                    maxLlmCalls,
                    n,
                    "predict() budget of "
                        + maxLlmCalls
                        + " calls exhausted (this would be call "
                        + n
                        + "). Stop calling predict() and submit() your best answer with the data "
                        + "already gathered in your variables.");
              }
            } else {
              predictCallCount.incrementAndGet();
            }
          }
          return inner.handle(params);
        });
  }

  /**
   * Execute code in the sandbox.
   *
   * @param code the source code to execute
   * @return the execution result
   * @throws ReplException if the session is closed or the sandbox is dead
   */
  public ExecutionResult execute(String code) {
    if (closed.get()) {
      throw new ReplException("Session is closed");
    }
    if (!sandbox.isAlive()) {
      throw new ReplException("Sandbox is no longer alive");
    }
    // Stamp the iteration index BEFORE the sandbox runs. predict() callbacks fire on virtual
    // threads from inside the sandbox call and read this atomic to mark each PredictCall with
    // the correct turn. By the contract, iteration N == history.size() when execute() begins.
    currentIteration.set(history.size());
    var request =
        ExecutionRequest.newBuilder().withCode(code).withTimeout(config.executionTimeout()).build();
    var executeParams =
        new ExecuteParams(
            config.sandboxBindingsListener() != null,
            config.maxBindingValueChars(),
            config.maxBindingSnapshotChars());
    var rawResult = sandbox.execute(request, executeParams);
    var result = applyExecutedCodeCap(rawResult, config.maxExecutedCodeChars());
    history.add(result);
    var listener = config.sandboxBindingsListener();
    if (listener != null) {
      try {
        listener.onBindings(result.bindings(), result);
      } catch (Throwable t) {
        // Listener contract: must not throw. Swallow so a misbehaving listener can't tank the run.
      }
    }
    return result;
  }

  /**
   * The value passed to {@code submit()} from sandbox code, or {@code null} if not yet called.
   * Populated by the default {@link SubmitFunction} that {@link #create} auto-registers when the
   * config does not supply one.
   *
   * @return the submitted output
   */
  public Object submittedOutput() {
    return submittedValue.get();
  }

  /**
   * The atomic reference populated by the auto-registered {@link SubmitFunction}. Exposed so
   * callers that register their own {@code SubmitFunction} (by including one in {@link
   * ReplConfig#hostFunctions()}) can still read the session's ref — though in that case the
   * caller-registered function writes to its own holder, and {@link #submittedOutput()} will stay
   * {@code null}.
   *
   * @return the atomic reference for the submitted value
   */
  public AtomicReference<Object> submitHolder() {
    return submittedValue;
  }

  /**
   * Cumulative {@code predict()} call count for this session. Useful for telemetry, IterationHook
   * decisions, or post-run reporting.
   *
   * @return the number of {@code predict()} invocations attempted so far (includes the call that
   *     tripped the budget, if any)
   */
  public int predictCallCount() {
    return predictCallCount.get();
  }

  /**
   * Names of {@link RequiredPredictSignature}s that have been invoked (i.e. their {@code
   * instructions} string matched the argument to a {@code predict()} call) so far in this session.
   * Empty when no signatures are configured or none have been called yet.
   *
   * @return immutable snapshot of the matched signature names
   */
  public Set<String> calledSignatures() {
    synchronized (calledSignatures) {
      return Set.copyOf(calledSignatures);
    }
  }

  /**
   * Apply the configured per-call cap to the {@code executedCode} field. Truncated values get a
   * {@code ... (len=N)} marker so consumers know the original length. Cap of {@code 0} returns the
   * result unchanged.
   */
  private static ExecutionResult applyExecutedCodeCap(ExecutionResult raw, int cap) {
    var code = raw.executedCode();
    if (cap <= 0 || code == null || code.length() <= cap) {
      return raw;
    }
    var truncated = code.substring(0, cap) + "... (len=" + code.length() + ")";
    return new ExecutionResult(
        truncated, raw.stdout(), raw.stderr(), raw.exitCode(), raw.submitted(), raw.bindings());
  }

  /**
   * Every {@code predict()} call the trajectory made, in call order, stamped with the iteration
   * index when each fired. The full transcript downstream evaluators need to decide whether a
   * registered {@link RequiredPredictSignature} actually ran, count distinct sub-LLM invocations,
   * or correlate predicts with specific {@link #history()} entries — without grepping JShell source
   * via {@link ExecutionResult#executedCode()}.
   *
   * @return immutable snapshot of every predict call
   */
  public List<PredictCall> predictCalls() {
    synchronized (predictCalls) {
      return List.copyOf(predictCalls);
    }
  }

  /**
   * The {@code instructions} arg of every {@code predict()} call recorded in this session, in call
   * order. Convenience derivative of {@link #predictCalls()} for callers that only need the
   * instructions strings (e.g. post-mortem comparison against registered {@link
   * RequiredPredictSignature}s).
   *
   * @return immutable snapshot of every predict call's instructions text
   */
  public List<String> predictInstructions() {
    synchronized (predictCalls) {
      var result = new ArrayList<String>(predictCalls.size());
      for (var call : predictCalls) {
        result.add(call.instructions());
      }
      return List.copyOf(result);
    }
  }

  /**
   * Per-host-function call counts for the trajectory, keyed by function name. Excludes {@code
   * predict} (use {@link #predictCalls()}) and {@code __getInput} (framework-internal). Includes
   * {@code submit}, {@code fetch}, {@code query}, and every custom Skill-registered host function
   * the model invoked.
   *
   * @return immutable map of {@code name -> callCount}; absent keys mean zero calls
   */
  public Map<String, Integer> calledHostFunctions() {
    var snapshot = new LinkedHashMap<String, Integer>();
    for (var entry : hostFnCounts.entrySet()) {
      snapshot.put(entry.getKey(), entry.getValue().get());
    }
    return Map.copyOf(snapshot);
  }

  /**
   * The execution history for this session.
   *
   * @return unmodifiable list of execution results
   */
  public List<ExecutionResult> history() {
    return Collections.unmodifiableList(history);
  }

  /** The host function registry for this session. */
  public HostFunctionRegistry registry() {
    return registry;
  }

  /** The config this session was created from. */
  public ReplConfig config() {
    return config;
  }

  /** Whether this session is still open. */
  public boolean isOpen() {
    return !closed.get() && sandbox.isAlive();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      try {
        sandbox.close();
      } finally {
        semaphore.release();
      }
    }
  }
}
