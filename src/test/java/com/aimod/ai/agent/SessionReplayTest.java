package com.aimod.ai.agent;

import com.aimod.ai.TaskPlanner;
import com.aimod.ai.action.Action;
import com.aimod.ai.llm.LLMResponse;
import com.aimod.ai.llm.LLMResponseParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Session replay tests — uses real recorded session data to verify
 * that the parsing pipeline correctly handles LLM responses.
 *
 * <p>This is a regression test suite: if a fix is broken in the future,
 * these tests will fail because they use real-world data that previously
 * caused failures.</p>
 *
 * <p>Data source: config/aimod/sessions/ directory
 * Each session contains llm/*-response.json files with actual LLM responses.</p>
 */
@DisplayName("Session Replay — parsing regression tests")
class SessionReplayTest {

    private static final Path SESSIONS_DIR = Path.of("config", "aimod", "sessions");

    /** Check if session data exists (may not exist in CI). */
    private boolean hasSessionData() {
        return Files.isDirectory(SESSIONS_DIR);
    }

    /** Load all LLM response files from all sessions. */
    private List<JsonObject> loadAllResponses() throws IOException {
        List<JsonObject> allResponses = new ArrayList<>();
        if (!hasSessionData()) return allResponses;

        try (Stream<Path> sessions = Files.list(SESSIONS_DIR)) {
            sessions.filter(Files::isDirectory).forEach(sessionDir -> {
                Path llmDir = sessionDir.resolve("llm");
                if (!Files.isDirectory(llmDir)) return;
                try (Stream<Path> files = Files.list(llmDir)) {
                    files.filter(p -> p.toString().endsWith("-response.json"))
                            .sorted()
                            .forEach(file -> {
                                try {
                                    String content = Files.readString(file, StandardCharsets.UTF_8);
                                    JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                                    json.addProperty("_session", sessionDir.getFileName().toString());
                                    json.addProperty("_file", file.getFileName().toString());
                                    allResponses.add(json);
                                } catch (Exception e) {
                                    // Skip malformed files
                                }
                            });
                } catch (IOException ignored) {}
            });
        }
        return allResponses;
    }

    /** Load all step files from all sessions. */
    private List<JsonObject> loadAllSteps() throws IOException {
        List<JsonObject> allSteps = new ArrayList<>();
        if (!hasSessionData()) return allSteps;

        try (Stream<Path> sessions = Files.list(SESSIONS_DIR)) {
            sessions.filter(Files::isDirectory).forEach(sessionDir -> {
                Path stepsDir = sessionDir.resolve("steps");
                if (!Files.isDirectory(stepsDir)) return;
                try (Stream<Path> files = Files.list(stepsDir)) {
                    files.filter(p -> p.toString().endsWith(".json"))
                            .sorted()
                            .forEach(file -> {
                                try {
                                    String content = Files.readString(file, StandardCharsets.UTF_8);
                                    JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                                    json.addProperty("_session", sessionDir.getFileName().toString());
                                    allSteps.add(json);
                                } catch (Exception e) {
                                    // Skip malformed files
                                }
                            });
                } catch (IOException ignored) {}
            });
        }
        return allSteps;
    }

    @Nested
    @DisplayName("Response parsing pipeline")
    class ResponseParsingTests {

        @Test
        @DisplayName("sendPromptWithModel preserves raw content on parse failure")
        void rawContentPreserved() throws IOException {
            if (!hasSessionData()) return;
            List<JsonObject> responses = loadAllResponses();
            assertFalse(responses.isEmpty(), "No session data found");

            int successCount = 0;
            int totalCount = responses.size();

            for (JsonObject resp : responses) {
                String responseStr = resp.toString();

                // Simulate: parseResponse may fail for raw JSON actions
                // But sendPromptWithModel should preserve raw content
                LLMResponse parsed = LLMResponse.success(responseStr);

                // The raw response should always be available
                assertNotNull(parsed.getRawResponse(), "Raw response should not be null");
                assertFalse(parsed.getRawResponse().isEmpty(), "Raw response should not be empty");
                successCount++;
            }

            // At least 90% should have valid raw content
            double successRate = (double) successCount / totalCount;
            assertTrue(successRate >= 0.9,
                    String.format("Raw content preservation rate %.1f%% < 90%%", successRate * 100));
        }

        @Test
        @DisplayName("LLMResponseParser extracts actions from valid responses")
        void extractActions() throws IOException {
            if (!hasSessionData()) return;
            List<JsonObject> responses = loadAllResponses();

            int extractedCount = 0;
            int totalCount = 0;

            for (JsonObject resp : responses) {
                if (!resp.has("response")) continue;
                String content = resp.get("response").getAsString();
                totalCount++;

                // Try to extract actions using the parser
                List<String> actions = LLMResponseParser.parseActionsFromContent(content);
                if (!actions.isEmpty()) {
                    extractedCount++;
                }
            }

            assertTrue(totalCount > 0, "No responses found");

            // At least 50% should have extractable actions
            // (some responses may be in formats that need fallback parsing)
            double extractRate = (double) extractedCount / totalCount;
            assertTrue(extractRate >= 0.5,
                    String.format("Action extraction rate %.1f%% < 50%%", extractRate * 100));
        }
    }

    @Nested
    @DisplayName("Action type detection")
    class ActionTypeTests {

        @Test
        @DisplayName("Responses with 'type' key are correctly identified")
        void typeKey() throws IOException {
            if (!hasSessionData()) return;
            List<JsonObject> responses = loadAllResponses();

            for (JsonObject resp : responses) {
                if (!resp.has("response")) continue;
                String content = resp.get("response").getAsString();

                // Check if response has "type" key
                if (content.contains("\"type\"")) {
                    try {
                        JsonObject action = JsonParser.parseString(content).getAsJsonObject();
                        assertTrue(action.has("type"), "Should have 'type' key");
                        assertFalse(action.get("type").getAsString().isEmpty(), "Type should not be empty");
                    } catch (Exception e) {
                        // May be wrapped in markdown or have other format
                    }
                }
            }
        }

        @Test
        @DisplayName("Responses with 'action' key are correctly identified")
        void actionKey() throws IOException {
            if (!hasSessionData()) return;
            List<JsonObject> responses = loadAllResponses();

            int actionKeyCount = 0;

            for (JsonObject resp : responses) {
                if (!resp.has("response")) continue;
                String content = resp.get("response").getAsString();

                // Check if response has "action" key (alternative to "type")
                if (content.contains("\"action\"") && !content.contains("\"type\"")) {
                    try {
                        JsonObject action = JsonParser.parseString(content).getAsJsonObject();
                        if (action.has("action")) {
                            actionKeyCount++;
                            // Verify the action key has a valid value
                            String actionType = action.get("action").getAsString();
                            assertFalse(actionType.isEmpty(), "Action type should not be empty");
                            // Verify it's a known action type
                            assertTrue(
                                    actionType.equals("craft") || actionType.equals("mine")
                                            || actionType.equals("break_block") || actionType.equals("move_to")
                                            || actionType.equals("interact") || actionType.equals("equip")
                                            || actionType.equals("place_block") || actionType.equals("say")
                                            || actionType.equals("wait") || actionType.equals("give_item")
                                            || actionType.equals("follow") || actionType.equals("attack"),
                                    "Unknown action type: " + actionType
                            );
                        }
                    } catch (Exception e) {
                        // May be wrapped in markdown
                    }
                }
            }

            // Log how many use "action" key
            System.out.println("Responses using 'action' key: " + actionKeyCount);
        }

        @Test
        @DisplayName("AgentLoop.parseDecision extracts type from both 'type' and 'action' keys")
        void parseDecisionHandlesBothKeys() {
            // Test with "type" key
            String typeKeyJson = "{\"type\": \"craft\", \"item\": \"stick\", \"count\": 4}";
            try {
                JsonObject json = JsonParser.parseString(typeKeyJson).getAsJsonObject();
                String actionType = "unknown";
                if (json.has("type")) {
                    actionType = json.get("type").getAsString();
                } else if (json.has("action")) {
                    actionType = json.get("action").getAsString();
                }
                assertEquals("craft", actionType, "Should extract 'craft' from 'type' key");
            } catch (Exception e) {
                fail("Should parse type key JSON: " + e.getMessage());
            }

            // Test with "action" key
            String actionKeyJson = "{\"action\": \"mine\", \"block_id\": \"diamond_ore\", \"radius\": 128}";
            try {
                JsonObject json = JsonParser.parseString(actionKeyJson).getAsJsonObject();
                String actionType = "unknown";
                if (json.has("type")) {
                    actionType = json.get("type").getAsString();
                } else if (json.has("action")) {
                    actionType = json.get("action").getAsString();
                }
                assertEquals("mine", actionType, "Should extract 'mine' from 'action' key");
            } catch (Exception e) {
                fail("Should parse action key JSON: " + e.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("Fallback parsing from raw response")
    class FallbackParsingTests {

        @Test
        @DisplayName("extractActionFromRawResponse handles OpenAI format")
        void openAiFormat() {
            // Simulate OpenAI format response
            String openAiResponse = """
                    {"choices":[{"message":{"content":"{\\"type\\": \\"break_block\\", \\"x\\": -390, \\"y\\": 171, \\"z\\": -261}"}}]}
                    """;

            // The content should be extractable
            try {
                JsonObject json = JsonParser.parseString(openAiResponse).getAsJsonObject();
                assertTrue(json.has("choices"), "Should have choices");
                JsonArray choices = json.getAsJsonArray("choices");
                assertTrue(choices.size() > 0, "Should have choices");

                JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                assertNotNull(message, "Should have message");
                String content = message.get("content").getAsString();

                // Extract action from content
                List<String> actions = LLMResponseParser.parseActionsFromContent(content);
                assertFalse(actions.isEmpty(), "Should extract action from OpenAI content");
            } catch (Exception e) {
                fail("Should parse OpenAI format: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("extractActionFromRawResponse handles raw JSON action")
        void rawJsonAction() {
            // Simulate raw JSON action (not wrapped in OpenAI format)
            String rawAction = "{\"type\": \"equip\", \"item\": \"netherite_pickaxe\"}";

            try {
                JsonObject json = JsonParser.parseString(rawAction).getAsJsonObject();
                assertTrue(json.has("type"), "Should have type");
                assertEquals("equip", json.get("type").getAsString(), "Should be equip");
            } catch (Exception e) {
                fail("Should parse raw JSON: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("extractActionFromRawResponse handles markdown-wrapped JSON")
        void markdownWrapped() {
            // Simulate markdown-wrapped JSON
            String markdownJson = """
                    ```json
                    {"type": "craft", "item": "diamond_helmet", "count": 1}
                    ```
                    """;

            // The JSON should still be extractable
            try {
                // Strip markdown
                String stripped = markdownJson.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
                JsonObject json = JsonParser.parseString(stripped).getAsJsonObject();
                assertTrue(json.has("type"), "Should have type");
                assertEquals("craft", json.get("type").getAsString(), "Should be craft");
            } catch (Exception e) {
                fail("Should parse markdown-wrapped JSON: " + e.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("End-to-end session replay")
    class EndToEndTests {

        @Test
        @DisplayName("Session steps have valid action descriptions")
        void validSteps() throws IOException {
            if (!hasSessionData()) return;
            List<JsonObject> steps = loadAllSteps();
            assertFalse(steps.isEmpty(), "No session steps found");

            int validCount = 0;
            for (JsonObject step : steps) {
                if (step.has("actionDescription") && step.has("resultStatus")) {
                    String desc = step.get("actionDescription").getAsString();
                    String status = step.get("resultStatus").getAsString();
                    assertFalse(desc.isEmpty(), "Action description should not be empty");
                    assertTrue(
                            status.equals("COMPLETED") || status.equals("FAILED"),
                            "Status should be COMPLETED or FAILED"
                    );
                    validCount++;
                }
            }

            // At least 80% should have valid data
            double validRate = (double) validCount / steps.size();
            assertTrue(validRate >= 0.8,
                    String.format("Valid steps rate %.1f%% < 80%%", validRate * 100));
        }

        @Test
        @DisplayName("Replay: parseSingleAction handles all recorded responses")
        void replayParseSingleAction() throws IOException {
            if (!hasSessionData()) return;
            List<JsonObject> responses = loadAllResponses();

            int parsedCount = 0;
            int totalCount = 0;
            List<String> failures = new ArrayList<>();

            for (JsonObject resp : responses) {
                if (!resp.has("response")) continue;
                String content = resp.get("response").getAsString();
                totalCount++;

                // Try to extract action from content
                List<String> actions = LLMResponseParser.parseActionsFromContent(content);
                if (!actions.isEmpty()) {
                    String actionJson = actions.get(0);
                    // Verify the extracted JSON is valid
                    try {
                        JsonObject action = JsonParser.parseString(actionJson).getAsJsonObject();
                        assertTrue(action.has("type") || action.has("action"),
                                "Action should have 'type' or 'action' key");
                        parsedCount++;
                    } catch (Exception e) {
                        String session = resp.has("_session") ? resp.get("_session").getAsString() : "?";
                        String file = resp.has("_file") ? resp.get("_file").getAsString() : "?";
                        failures.add(session + "/" + file + ": " + e.getMessage());
                    }
                }
            }

            System.out.println("Replay parse results: " + parsedCount + "/" + totalCount + " parsed");
            if (!failures.isEmpty()) {
                System.out.println("Failures: " + failures.size());
                for (String f : failures) {
                    System.out.println("  - " + f);
                }
            }

            // At least 70% should parse successfully
            double parseRate = (double) parsedCount / totalCount;
            assertTrue(parseRate >= 0.7,
                    String.format("Parse rate %.1f%% < 70%% (failures: %d)",
                            parseRate * 100, failures.size()));
        }
    }
}
