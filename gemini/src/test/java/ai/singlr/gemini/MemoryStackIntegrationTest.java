/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.agent.Agent;
import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.agent.CompactionConfig;
import ai.singlr.core.agent.DefaultContextCompactor;
import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.common.Result;
import ai.singlr.core.memory.ConsolidationContext;
import ai.singlr.core.memory.InMemoryMemory;
import ai.singlr.core.memory.LlmMemoryConsolidator;
import ai.singlr.core.memory.MemoryBlocks;
import ai.singlr.core.memory.MemoryConsolidator;
import ai.singlr.core.memory.behavior.TopicThreadingExtractor;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.Response;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * End-to-end exercise of the Helios memory stack against a real Gemini model. Verifies three
 * production behaviours:
 *
 * <ol>
 *   <li>Compaction preserves user-stated facts (name + role + project) across multiple rounds that
 *       cross the configured threshold.
 *   <li>{@link TopicThreadingExtractor} wired as a {@link ai.singlr.core.memory.MemoryListener}
 *       surfaces a {@code recurring_topics} entry in the {@code user_profile} block after the topic
 *       is mentioned repeatedly.
 *   <li>{@link LlmMemoryConsolidator} produces a sensible {@link
 *       ai.singlr.core.memory.ConsolidationReport} after the session, with at least one block
 *       update targeting {@code user_profile}.
 * </ol>
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class MemoryStackIntegrationTest {

  private static GeminiModel model;

  @BeforeAll
  static void setUpModel() {
    var apiKey = System.getenv("GEMINI_API_KEY");
    var config = ModelConfig.newBuilder().withApiKey(apiKey).build();
    model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
  }

  @Test
  void memoryStackRecallsUserFactsAndConsolidates() {
    var memory = InMemoryMemory.withDefaults();
    var userId = "priya-42";
    var sessionId = UUID.randomUUID();
    memory.registerSession(userId, sessionId);

    var topicExtractor =
        new TopicThreadingExtractor(memory, 6, 2, TopicThreadingExtractor.DEFAULT_STOPWORDS);

    var agentConfig =
        AgentConfig.newBuilder()
            .withName("MemoryStackAgent")
            .withModel(model)
            .withSystemPrompt(
                "You are a helpful research assistant. Keep responses under 2 sentences.\n"
                    + "\n## Core Memory\n${core_memory}\n")
            .withMemory(memory)
            .withIncludeMemoryTools(false)
            .withMemoryListener(topicExtractor)
            .withContextCompactor(
                new DefaultContextCompactor(
                    model,
                    java.util.List.of(),
                    new CompactionConfig(0.20, 0.40, 1, 4, 0.10, 200, 3)))
            .build();

    // Turn 1 — introduce Priya, her role, and her project. After this turn the topic extractor
    // has not yet flushed (threshold is 2 turns).
    runTurn(
        agentConfig,
        userId,
        sessionId,
        "Hi! I'm Priya, a data scientist working on portfolio risk modeling.");

    // Turn 2 — second mention of risk, portfolio, modeling. After this turn the extractor should
    // flush a recurring_topics entry to the user_profile block.
    runTurn(
        agentConfig,
        userId,
        sessionId,
        "Today I'm modeling tail risk for a portfolio with concentrated tech exposure.");

    // Turn 3 — third mention. Conversation should now be long enough to cross the compaction
    // threshold on subsequent turns.
    runTurn(
        agentConfig,
        userId,
        sessionId,
        "What types of stress tests would you recommend for portfolio tail risk?");

    // Turn 4 — recall question. Compaction should fire before this turn because cumulative
    // content is well over 40% of the model's context window.
    var recallResult =
        runTurn(
            agentConfig,
            userId,
            sessionId,
            "Earlier I told you my name, role, and the project I'm working on."
                + " Repeat all three in one sentence so I know you remember.");
    var recallText = recallResult.content() == null ? "" : recallResult.content().toLowerCase();
    assertTrue(
        recallText.contains("priya"), "recall response must mention 'Priya' — got: " + recallText);
    assertTrue(
        recallText.contains("data scientist"),
        "recall response must mention 'data scientist' — got: " + recallText);
    assertTrue(
        recallText.contains("portfolio") || recallText.contains("risk"),
        "recall response must reference the project — got: " + recallText);

    // (2) BehaviorExtractor — recurring_topics should be in user_profile.
    var userProfile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    var topics = (String) userProfile.value("recurring_topics");
    assertNotNull(topics, "TopicThreadingExtractor must have flushed recurring_topics");
    assertTrue(
        topics.contains("portfolio") || topics.contains("risk"),
        "recurring_topics must include the topic Priya kept raising — got: " + topics);

    // (3) MemoryConsolidator — pass the session over the consolidator and check the report.
    var consolidator = new LlmMemoryConsolidator(model);
    var report =
        consolidator.consolidate(
            new ConsolidationContext(
                "MemoryStackAgent", userId, memory, memory.history(userId, sessionId)));
    assertNotNull(report);
    assertTrue(
        report.suggestedBlockUpdates().stream()
            .anyMatch(u -> MemoryBlocks.USER_PROFILE.equals(u.blockName())),
        "consolidator must propose at least one user_profile update — got: "
            + report.suggestedBlockUpdates());

    // SUGGEST_ONLY mode is the safe default — verify it doesn't mutate memory.
    var beforeCount = memory.coreBlocks().size();
    report.apply(memory, MemoryConsolidator.ApplyMode.SUGGEST_ONLY);
    assertEquals(beforeCount, memory.coreBlocks().size());

    // QUARANTINE mode parks the suggestions in a pending block — verify it lands.
    report.apply(memory, MemoryConsolidator.ApplyMode.QUARANTINE);
    assertTrue(
        memory.block("pending_consolidation").isPresent(),
        "QUARANTINE mode must create a pending_consolidation block");
  }

  private static Response<?> runTurn(
      AgentConfig config, String userId, UUID sessionId, String userInput) {
    var agent = new Agent(config);
    var session =
        SessionContext.newBuilder()
            .withUserId(userId)
            .withSessionId(sessionId)
            .withUserInput(userInput)
            .build();
    var result = agent.run(session);
    assertTrue(result.isSuccess(), "turn must succeed: " + userInput);
    return ((Result.Success<Response>) result).value();
  }
}
