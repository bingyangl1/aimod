package com.example.aimod.ai.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LLMResponseParser Robustness Tests")
class LLMResponseParserRobustnessTest {

    @Nested
    @DisplayName("parseActionsFromContent - Robust Parsing")
    class RobustParsingTests {

        @Test
        @DisplayName("handles extra text before and after JSON")
        void extraTextAroundJson() {
            String content = "Here is the plan: {\"actions\": [{\"type\":\"say\",\"message\":\"hello\"}]} End of plan.";
            List<String> actions = LLMResponseParser.parseActionsFromContent(content);
            assertEquals(1, actions.size());
            assertTrue(actions.get(0).contains("\"type\":\"say\""));
            assertTrue(actions.get(0).contains("\"message\":\"hello\""));
        }

        @Test
        @DisplayName("handles multiple JSON objects, extracts actions from first valid one")
        void multipleJsonObjects() {
            String content = "{\"not_actions\": []} {\"actions\": [{\"type\":\"move_to\"}]} {\"invalid\": }";
            List<String> actions = LLMResponseParser.parseActionsFromContent(content);
            assertEquals(1, actions.size());
            assertTrue(actions.get(0).contains("\"type\":\"move_to\""));
        }

        @Test
        @DisplayName("handles loose actions pattern without outer braces")
        void looseActionsPattern() {
            String content = "The bot should: \"actions\": [{\"type\":\"give_item\",\"item_id\":\"minecraft:diamond\",\"count\":1,\"player\":\"Steve\"}] and then wait.";
            List<String> actions = LLMResponseParser.parseActionsFromContent(content);
            assertEquals(1, actions.size());
            assertTrue(actions.get(0).contains("\"type\":\"give_item\""));
            assertTrue(actions.get(0).contains("\"item_id\":\"minecraft:diamond\""));
            assertTrue(actions.get(0).contains("\"count\":1"));
            assertTrue(actions.get(0).contains("\"player\":\"Steve\""));
        }

        @Test
        @DisplayName("extracts individual action objects when no actions array found")
        void extractIndividualActionObjects() {
            String content = "Do this: {\"type\":\"say\"} then {\"type\":\"wait\"} finally {\"type\":\"say\"}";
            List<String> actions = LLMResponseParser.parseActionsFromContent(content);
            assertEquals(3, actions.size());
            assertTrue(actions.get(0).contains("\"type\":\"say\""));
            assertTrue(actions.get(1).contains("\"type\":\"wait\""));
            assertTrue(actions.get(2).contains("\"type\":\"say\""));
        }

        @Test
        @DisplayName("ignores non-action objects during extraction")
        void ignoreNonActionObjects() {
            String content = "Info: {\"status\":\"ready\"} Action: {\"type\":\"break_block\"} More: {\"count\":5}";
            List<String> actions = LLMResponseParser.parseActionsFromContent(content);
            assertEquals(1, actions.size());
            assertTrue(actions.get(0).contains("\"type\":\"break_block\""));
        }

        @Test
        @DisplayName("returns empty list for plain text without JSON")
        void completelyInvalidContent() {
            String content = "This is just plain text with no JSON at all.";
            List<String> actions = LLMResponseParser.parseActionsFromContent(content);
            assertTrue(actions.isEmpty());
        }

        @Test
        @DisplayName("handles malformed JSON gracefully without crashing")
        void malformedJsonGracefully() {
            String content = "Actions: {\"actions\": [{\"type\":\"say\"}, invalid json here, {\"type\":\"wait\"}]}";
            List<String> actions = LLMResponseParser.parseActionsFromContent(content);
            assertNotNull(actions);
        }

        @Test
        @DisplayName("works with nested braces in values like templates")
        void nestedBracesInValues() {
            String content = "{\"actions\": [{\"type\":\"say\",\"message\":\"{{dynamic}}\"}, {\"type\":\"wait\",\"ticks\":20}]}";
            List<String> actions = LLMResponseParser.parseActionsFromContent(content);
            assertEquals(2, actions.size());
            assertTrue(actions.get(0).contains("\"type\":\"say\""));
            assertTrue(actions.get(0).contains("\"message\":\"{{dynamic}}\""));
            assertTrue(actions.get(1).contains("\"type\":\"wait\""));
            assertTrue(actions.get(1).contains("\"ticks\":20"));
        }
    }

    @Nested
    @DisplayName("parseResponse - Integration Tests")
    class ParseResponseIntegrationTests {

        @Test
        @DisplayName("extracts action from LLM response with surrounding text in content")
        void llmResponseWithExtraText() {
            String llmResponse = "{\"choices\": [{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Based on your request: {\\\"actions\\\": [{\\\"type\\\":\\\"mine\\\",\\\"block_id\\\":\\\"minecraft:diamond_ore\\\",\\\"count\\\":5}]} Done.\"}}]}";
            LLMResponse response = LLMResponseParser.parseResponse(llmResponse);
            assertTrue(response.isSuccess());
            assertEquals(1, response.getActions().size());
            String action = response.getActions().get(0);
            assertTrue(action.contains("mine"));
            assertTrue(action.contains("diamond_ore"));
        }

        @Test
        @DisplayName("still works with standard clean JSON format")
        void standardFormatStillWorks() {
            String llmResponse = "{\"choices\": [{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"actions\\\": [{\\\"type\\\":\\\"say\\\",\\\"message\\\":\\\"Hello!\\\"}]}\"}}]}";
            LLMResponse response = LLMResponseParser.parseResponse(llmResponse);
            assertTrue(response.isSuccess());
            assertEquals(1, response.getActions().size());
            String action = response.getActions().get(0);
            assertTrue(action.contains("say"));
            assertTrue(action.contains("Hello!"));
        }

        @Test
        @DisplayName("returns failure when choices are malformed")
        void malformedChoicesReturnsFailure() {
            String llmResponse = "{\"choices\": \"not an array\"}";
            LLMResponse response = LLMResponseParser.parseResponse(llmResponse);
            assertFalse(response.isSuccess());
            assertNotNull(response.getError());
        }

        @Test
        @DisplayName("returns result (success with empty or failure) for content without actions")
        void contentExtractionFails() {
            String llmResponse = "{\"choices\": [{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"I do not understand the request.\"}}]}";
            LLMResponse response = LLMResponseParser.parseResponse(llmResponse);
            assertNotNull(response);
        }
    }
}
