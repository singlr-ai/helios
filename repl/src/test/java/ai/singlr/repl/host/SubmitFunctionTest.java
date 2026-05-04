/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.schema.JsonSchema;
import ai.singlr.core.schema.OutputSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SubmitFunctionTest {

  @Test
  void nullHolderThrows() {
    assertThrows(IllegalArgumentException.class, () -> SubmitFunction.create(null));
  }

  @Test
  void createReturnsHostFunction() {
    var fn = SubmitFunction.create(new AtomicReference<>());
    assertEquals("submit", fn.name());
    assertNotNull(fn.description());
    assertNotNull(fn.handler());
  }

  @Test
  @SuppressWarnings("unchecked")
  void submitStoresValue() throws Exception {
    var holder = new AtomicReference<>();
    var fn = SubmitFunction.create(holder);

    var result = (Map<String, Object>) fn.handler().handle(Map.of("output", "final answer"));

    assertEquals("final answer", holder.get());
    assertEquals("accepted", result.get("status"));
  }

  @Test
  void submitNullOutputThrows() {
    var fn = SubmitFunction.create(new AtomicReference<>());
    assertThrows(IllegalArgumentException.class, () -> fn.handler().handle(Map.of()));
  }

  @Test
  void doubleSubmitThrows() throws Exception {
    var holder = new AtomicReference<>();
    var fn = SubmitFunction.create(holder);

    fn.handler().handle(Map.of("output", "first"));
    assertThrows(
        IllegalStateException.class, () -> fn.handler().handle(Map.of("output", "second")));

    assertEquals("first", holder.get());
  }

  @Test
  void typedSubmitWithNullSchemaBehavesLikeUntyped() throws Exception {
    var holder = new AtomicReference<>();
    var fn = SubmitFunction.create(holder, null);

    fn.handler().handle(Map.of("output", "x"));
    assertEquals("x", holder.get());
  }

  public record Report(String query, List<String> sources, int totalCount) {}

  @Test
  @SuppressWarnings("unchecked")
  void typedSubmitAcceptsValidPayload() throws Exception {
    var holder = new AtomicReference<>();
    var schema = OutputSchema.of(Report.class);
    var fn = SubmitFunction.create(holder, schema);

    var payload =
        Map.<String, Object>of(
            "query",
            "what is helios",
            "sources",
            List.of("docs.singlr.ai/helios"),
            "totalCount",
            1);
    var result = (Map<String, Object>) fn.handler().handle(Map.of("output", payload));

    assertEquals(payload, holder.get());
    assertEquals("accepted", result.get("status"));
  }

  @Test
  void typedSubmitRejectsMissingRequiredFieldWithActionableMessage() {
    var holder = new AtomicReference<>();
    var schema = OutputSchema.of(Report.class);
    var fn = SubmitFunction.create(holder, schema);

    var payload =
        Map.<String, Object>of(
            "query", "what is helios", "sources", List.of("docs.singlr.ai/helios"));
    var error =
        assertThrows(
            IllegalArgumentException.class, () -> fn.handler().handle(Map.of("output", payload)));

    assertTrue(error.getMessage().startsWith("Submit validation failed"));
    assertTrue(error.getMessage().contains("totalCount"));
    assertTrue(
        error.getMessage().contains("Required fields:"),
        "error message must list required fields so the model can correct");
    assertTrue(
        error.getMessage().contains("Fix the output value and call submit(...) again"),
        "error message must instruct the model to retry");
    assertNull(holder.get(), "holder must remain unset on validation failure");
  }

  @Test
  void typedSubmitRejectsWrongType() {
    var holder = new AtomicReference<>();
    var schema = OutputSchema.of(Report.class);
    var fn = SubmitFunction.create(holder, schema);

    var payload =
        Map.<String, Object>of(
            "query", "x",
            "sources", "not-a-list",
            "totalCount", 1);
    var error =
        assertThrows(
            IllegalArgumentException.class, () -> fn.handler().handle(Map.of("output", payload)));
    assertTrue(error.getMessage().contains("expected array"));
    assertTrue(error.getMessage().contains("sources"));
  }

  @Test
  void typedSubmitFailsRetryStillSucceeds() throws Exception {
    var holder = new AtomicReference<>();
    var schema = OutputSchema.of(Report.class);
    var fn = SubmitFunction.create(holder, schema);

    var bad = Map.<String, Object>of("query", "x", "sources", List.of());
    assertThrows(IllegalArgumentException.class, () -> fn.handler().handle(Map.of("output", bad)));
    assertNull(holder.get());

    var good = Map.<String, Object>of("query", "x", "sources", List.of("a"), "totalCount", 0);
    fn.handler().handle(Map.of("output", good));
    assertEquals(good, holder.get());
  }

  @Test
  void typedSubmitDescriptionMentionsSchema() {
    var schema = OutputSchema.of(Report.class);
    var fn = SubmitFunction.create(new AtomicReference<>(), schema);
    assertTrue(fn.description().contains("schema"));
  }

  @Test
  void typedSubmitRejectsNonObjectAtRoot() {
    var holder = new AtomicReference<>();
    var schema = OutputSchema.of(Report.class);
    var fn = SubmitFunction.create(holder, schema);

    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> fn.handler().handle(Map.of("output", "not an object")));
    assertTrue(error.getMessage().contains("expected object"));
  }

  @Test
  void rawJsonSchemaCanBeUsedDirectly() {
    var holder = new AtomicReference<>();
    var schema =
        OutputSchema.of(
            Object.class,
            JsonSchema.object()
                .withProperty("name", JsonSchema.string(), true)
                .withProperty("count", JsonSchema.integer(), true)
                .build());
    var fn = SubmitFunction.create(holder, schema);

    assertThrows(
        IllegalArgumentException.class,
        () -> fn.handler().handle(Map.of("output", Map.of("name", "x"))));
    assertNull(holder.get());
  }

  // --- Provenanced submit ---

  public record Pick(String name, String reason) {}

  private static Map<String, Object> validProvenancedPayload() {
    return Map.of(
        "output",
        Map.of("name", "alice", "reason", "fits"),
        "provenance",
        List.of(
            Map.of(
                "field", "name",
                "sources", List.of(Map.of("url", "https://x.com", "excerpts", List.of("alice"))),
                "reasoning", "she said her name",
                "confidence", "HIGH"),
            Map.of(
                "field", "reason",
                "sources", List.of(),
                "reasoning", "best guess",
                "confidence", "LOW")));
  }

  @Test
  @SuppressWarnings("unchecked")
  void provenancedSubmitStoresReconstructed() throws Exception {
    var holder = new AtomicReference<>();
    var schema = OutputSchema.provenancedOf(Pick.class);
    var fn = SubmitFunction.create(holder, schema);

    fn.handler().handle(Map.of("output", validProvenancedPayload()));

    var stored = holder.get();
    assertNotNull(stored);
    assertTrue(stored instanceof ai.singlr.core.common.Provenanced<?>);
    var prov = (ai.singlr.core.common.Provenanced<Pick>) stored;
    assertEquals("alice", prov.output().name());
    assertEquals("fits", prov.output().reason());
    assertEquals(2, prov.provenance().size());
    assertEquals(ai.singlr.core.common.Confidence.HIGH, prov.forField("name").confidence());
  }

  @Test
  void provenancedSubmitRejectsHighWithoutSources() {
    var holder = new AtomicReference<>();
    var schema = OutputSchema.provenancedOf(Pick.class);
    var fn = SubmitFunction.create(holder, schema);

    var bad =
        Map.<String, Object>of(
            "output",
            Map.of(
                "output",
                Map.of("name", "alice", "reason", "fits"),
                "provenance",
                List.of(
                    Map.of(
                        "field", "name",
                        "sources", List.of(),
                        "reasoning", "guess",
                        "confidence", "HIGH"),
                    Map.of(
                        "field", "reason",
                        "sources", List.of(),
                        "reasoning", "guess",
                        "confidence", "LOW"))));
    var error = assertThrows(IllegalArgumentException.class, () -> fn.handler().handle(bad));
    assertTrue(error.getMessage().contains("Provenance validation failed"));
    assertTrue(error.getMessage().contains("requires at least one source"));
    assertNull(holder.get());
  }

  @Test
  void provenancedSubmitRejectsMissingFieldEntry() {
    var holder = new AtomicReference<>();
    var schema = OutputSchema.provenancedOf(Pick.class);
    var fn = SubmitFunction.create(holder, schema);

    var missingReason =
        Map.<String, Object>of(
            "output",
            Map.of(
                "output",
                Map.of("name", "alice", "reason", "fits"),
                "provenance",
                List.of(
                    Map.of(
                        "field", "name",
                        "sources", List.of(Map.of("url", "https://x.com", "excerpts", List.of())),
                        "reasoning", "ok",
                        "confidence", "MEDIUM"))));
    var error =
        assertThrows(IllegalArgumentException.class, () -> fn.handler().handle(missingReason));
    assertTrue(error.getMessage().contains("missing provenance entry for output field 'reason'"));
  }

  @Test
  void provenancedSubmitRejectsUnknownFieldEntry() {
    var holder = new AtomicReference<>();
    var schema = OutputSchema.provenancedOf(Pick.class);
    var fn = SubmitFunction.create(holder, schema);

    var unknown =
        Map.<String, Object>of(
            "output",
            Map.of(
                "output",
                Map.of("name", "alice", "reason", "fits"),
                "provenance",
                List.of(
                    Map.of(
                        "field", "name",
                        "sources", List.of(),
                        "reasoning", "ok",
                        "confidence", "LOW"),
                    Map.of(
                        "field", "reason",
                        "sources", List.of(),
                        "reasoning", "ok",
                        "confidence", "LOW"),
                    Map.of(
                        "field", "ghost",
                        "sources", List.of(),
                        "reasoning", "ok",
                        "confidence", "LOW"))));
    var error = assertThrows(IllegalArgumentException.class, () -> fn.handler().handle(unknown));
    assertTrue(error.getMessage().contains("unknown field 'ghost'"));
  }

  @Test
  void provenancedSubmitRejectsDuplicateFieldEntry() {
    var holder = new AtomicReference<>();
    var schema = OutputSchema.provenancedOf(Pick.class);
    var fn = SubmitFunction.create(holder, schema);

    var duplicates =
        Map.<String, Object>of(
            "output",
            Map.of(
                "output",
                Map.of("name", "alice", "reason", "fits"),
                "provenance",
                List.of(
                    Map.of(
                        "field", "name",
                        "sources", List.of(),
                        "reasoning", "first",
                        "confidence", "LOW"),
                    Map.of(
                        "field", "name",
                        "sources", List.of(),
                        "reasoning", "second",
                        "confidence", "LOW"),
                    Map.of(
                        "field", "reason",
                        "sources", List.of(),
                        "reasoning", "ok",
                        "confidence", "LOW"))));
    var error = assertThrows(IllegalArgumentException.class, () -> fn.handler().handle(duplicates));
    assertTrue(error.getMessage().contains("duplicate provenance entry for field 'name'"));
  }

  @Test
  void provenancedSubmitRejectsNonObjectEnvelope() {
    var holder = new AtomicReference<>();
    var schema = OutputSchema.provenancedOf(Pick.class);
    var fn = SubmitFunction.create(holder, schema);

    assertThrows(
        IllegalArgumentException.class, () -> fn.handler().handle(Map.of("output", "not-a-map")));
  }

  @Test
  void provenancedSubmitWithCustomValidator() throws Exception {
    var holder = new AtomicReference<>();
    var permissive =
        (ai.singlr.core.common.ProvenanceValidator)
            entry -> ai.singlr.core.common.ValidationResult.success();
    var schema = OutputSchema.provenancedOf(Pick.class, permissive);
    var fn = SubmitFunction.create(holder, schema);

    var noSourcesAtHigh =
        Map.<String, Object>of(
            "output",
            Map.of(
                "output",
                Map.of("name", "alice", "reason", "fits"),
                "provenance",
                List.of(
                    Map.of(
                        "field", "name",
                        "sources", List.of(),
                        "reasoning", "ok",
                        "confidence", "HIGH"),
                    Map.of(
                        "field", "reason",
                        "sources", List.of(),
                        "reasoning", "ok",
                        "confidence", "HIGH"))));
    fn.handler().handle(noSourcesAtHigh);

    assertNotNull(holder.get());
    assertTrue(holder.get() instanceof ai.singlr.core.common.Provenanced<?>);
  }

  // --- SubmitValidator (semantic checks beyond structural schema validation) ---

  public record Synthesis(String synthesis) {}

  @Test
  void submitValidatorPredicatePasses() throws Exception {
    var holder = new AtomicReference<>();
    var schema =
        OutputSchema.of(Synthesis.class)
            .withSubmitValidator(s -> s.synthesis().split("\\s+").length >= 5, "too short");
    var fn = SubmitFunction.create(holder, schema);

    var payload = Map.<String, Object>of("output", Map.of("synthesis", "this is a longer answer"));
    fn.handler().handle(payload);

    assertTrue(holder.get() instanceof Synthesis);
    assertEquals("this is a longer answer", ((Synthesis) holder.get()).synthesis());
  }

  @Test
  void submitValidatorPredicateFailureSurfacesCorrection() {
    var holder = new AtomicReference<>();
    var schema =
        OutputSchema.of(Synthesis.class)
            .withSubmitValidator(
                s -> s.synthesis().split("\\s+").length >= 50,
                "synthesis is too short — write at least 50 words");
    var fn = SubmitFunction.create(holder, schema);

    var stub = Map.<String, Object>of("output", Map.of("synthesis", "stub"));
    var error = assertThrows(IllegalArgumentException.class, () -> fn.handler().handle(stub));
    assertTrue(error.getMessage().contains("Submit validation failed"));
    assertTrue(error.getMessage().contains("synthesis is too short"));
    assertTrue(error.getMessage().contains("Fix the output value and call submit"));
    assertNull(holder.get(), "holder must remain unset on validator failure");
  }

  @Test
  void submitValidatorRetrySucceedsAfterFailure() throws Exception {
    var holder = new AtomicReference<>();
    var schema =
        OutputSchema.of(Synthesis.class)
            .withSubmitValidator(s -> s.synthesis().split("\\s+").length >= 5, "too short");
    var fn = SubmitFunction.create(holder, schema);

    var stub = Map.<String, Object>of("output", Map.of("synthesis", "stub"));
    assertThrows(IllegalArgumentException.class, () -> fn.handler().handle(stub));
    assertNull(holder.get());

    var realAnswer = Map.<String, Object>of("output", Map.of("synthesis", "this is the real one"));
    fn.handler().handle(realAnswer);
    assertNotNull(holder.get());
    assertEquals("this is the real one", ((Synthesis) holder.get()).synthesis());
  }

  @Test
  void submitValidatorThrowingPredicateIsCaught() {
    var holder = new AtomicReference<>();
    var schema =
        OutputSchema.of(Synthesis.class)
            .withSubmitValidator(
                (java.util.function.Predicate<Synthesis>)
                    s -> {
                      throw new RuntimeException("buggy operator predicate");
                    },
                "n/a");
    var fn = SubmitFunction.create(holder, schema);

    var payload = Map.<String, Object>of("output", Map.of("synthesis", "anything"));
    var error = assertThrows(IllegalArgumentException.class, () -> fn.handler().handle(payload));
    assertTrue(
        error.getMessage().contains("submit validator threw: buggy operator predicate"),
        "operator-thrown exception must surface as a validation failure rather than abort");
    assertNull(holder.get());
  }

  @Test
  void submitValidatorReturningNullSurfacesAsFailure() {
    var holder = new AtomicReference<>();
    var schema =
        OutputSchema.of(Synthesis.class)
            .withSubmitValidator((ai.singlr.core.common.SubmitValidator<Synthesis>) s -> null);
    var fn = SubmitFunction.create(holder, schema);

    var payload = Map.<String, Object>of("output", Map.of("synthesis", "anything"));
    var error = assertThrows(IllegalArgumentException.class, () -> fn.handler().handle(payload));
    assertTrue(error.getMessage().contains("validator returned null"));
    assertNull(holder.get());
  }

  @Test
  void submitValidatorComposesWithProvenanced() throws Exception {
    var holder = new AtomicReference<>();
    var schema =
        OutputSchema.provenancedOf(Pick.class)
            .withSubmitValidator(
                p ->
                    !p.output().reason().isEmpty()
                        ? ai.singlr.core.common.ValidationResult.success()
                        : ai.singlr.core.common.ValidationResult.failure("reason must be set"));
    var fn = SubmitFunction.create(holder, schema);

    fn.handler().handle(Map.of("output", validProvenancedPayload()));

    assertNotNull(holder.get());
    assertTrue(holder.get() instanceof ai.singlr.core.common.Provenanced<?>);
  }

  public record StrictSynthesis(String synthesis) {
    public StrictSynthesis {
      if ("trigger-record-validation".equals(synthesis)) {
        throw new IllegalArgumentException("record compact constructor rejects this value");
      }
    }
  }

  @Test
  void submitValidatorParseFailureSurfacesAsValidationError() {
    var holder = new AtomicReference<>();
    var schema =
        OutputSchema.of(StrictSynthesis.class)
            .withSubmitValidator(s -> true, "always passes if we reach this point");
    var fn = SubmitFunction.create(holder, schema);

    var payload =
        Map.<String, Object>of("output", Map.of("synthesis", "trigger-record-validation"));
    var error = assertThrows(IllegalArgumentException.class, () -> fn.handler().handle(payload));
    assertTrue(
        error.getMessage().contains("could not parse output as StrictSynthesis"),
        "Jackson convertValue failures must surface as a clean validation error, not bubble out");
    assertTrue(error.getMessage().contains("Fix the output value and call submit"));
    assertNull(holder.get());
  }

  @Test
  void submitValidatorRunsAfterProvenanceValidator() {
    var holder = new AtomicReference<>();
    var schema =
        OutputSchema.provenancedOf(Pick.class)
            .withSubmitValidator(
                (ai.singlr.core.common.SubmitValidator<ai.singlr.core.common.Provenanced<Pick>>)
                    p ->
                        ai.singlr.core.common.ValidationResult.failure(
                            "submit validator should not run when provenance failed"));
    var fn = SubmitFunction.create(holder, schema);

    var bad =
        Map.<String, Object>of(
            "output",
            Map.of(
                "output",
                Map.of("name", "alice", "reason", "fits"),
                "provenance",
                List.of(
                    Map.of(
                        "field", "name",
                        "sources", List.of(),
                        "reasoning", "guess",
                        "confidence", "HIGH"),
                    Map.of(
                        "field", "reason",
                        "sources", List.of(),
                        "reasoning", "guess",
                        "confidence", "LOW"))));
    var error = assertThrows(IllegalArgumentException.class, () -> fn.handler().handle(bad));
    assertTrue(
        error.getMessage().contains("Provenance validation failed"),
        "provenance failure must short-circuit before submit validator runs");
    assertNull(holder.get());
  }
}
