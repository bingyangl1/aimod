package com.aimod.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LLMResponseParser Tests")
class LLMResponseParserTest {

    @Nested @DisplayName("parseResponse")
    class ParseResponseTests {
        @Test @DisplayName("parses valid OpenAI format") void validFormat() {
            String json = "{\"choices\": [{\"message\": {\"content\": \"hello\"}}]}";
            LLMResponse r = LLMResponseParser.parseResponse(json);
            assertTrue(r.isSuccess());
            assertEquals("hello", r.getRawResponse());
        }

        @Test @DisplayName("extracts actions from content") void extractsActions() {
            String content = "{\"actions\": [{\"type\":\"say\",\"message\":\"hi\"}]}";
            String json = "{\"choices\": [{\"message\": {\"content\": \"" + content.replace("\"", "\\\"") + "\"}}]}";
            LLMResponse r = LLMResponseParser.parseResponse(json);
            assertTrue(r.isSuccess());
            assertEquals(1, r.getActions().size());
        }

        @Test @DisplayName("fails on invalid JSON") void invalidJson() {
            assertFalse(LLMResponseParser.parseResponse("not json").isSuccess());
        }

        @Test @DisplayName("fails on empty choices") void emptyChoices() {
            assertFalse(LLMResponseParser.parseResponse("{\"choices\": []}").isSuccess());
        }

        @Test @DisplayName("fails on missing choices key") void missingChoices() {
            assertFalse(LLMResponseParser.parseResponse("{\"data\": null}").isSuccess());
        }
    }

    @Nested @DisplayName("parseActionsFromContent")
    class ParseActionsTests {
        @Test @DisplayName("extracts actions array") void extractsArray() {
            String c = "{\"actions\": [{\"type\":\"move\"}, {\"type\":\"say\"}]}";
            assertEquals(2, LLMResponseParser.parseActionsFromContent(c).size());
        }

        @Test @DisplayName("empty for no actions key") void noActionsKey() {
            assertTrue(LLMResponseParser.parseActionsFromContent("{\"other\": 1}").isEmpty());
        }

        @Test @DisplayName("empty for plain text") void plainText() {
            assertTrue(LLMResponseParser.parseActionsFromContent("hello world").isEmpty());
        }

        @Test @DisplayName("handles text wrapping JSON") void textWrapped() {
            String c = "Plan: {\"actions\": [{\"type\":\"say\"}]} done.";
            assertEquals(1, LLMResponseParser.parseActionsFromContent(c).size());
        }

        @Test @DisplayName("empty for empty array") void emptyArray() {
            assertTrue(LLMResponseParser.parseActionsFromContent("{\"actions\": []}").isEmpty());
        }

        @Test @DisplayName("handles null-like input") void nullInput() {
            assertTrue(LLMResponseParser.parseActionsFromContent("").isEmpty());
        }
    }

    @Nested @DisplayName("parseActionDescriptors")
    class ParseDescriptorsTests {
        @Test @DisplayName("parses descriptors with type and params") void parsesDescriptors() {
            LLMResponse r = LLMResponse.success("raw");
            r.setActions(List.of("{\"type\":\"say\",\"message\":\"hi\"}", "{\"type\":\"wait\",\"ticks\":40}"));
            List<ActionDescriptor> ds = LLMResponseParser.parseActionDescriptors(r);
            assertEquals(2, ds.size());
            assertEquals("say", ds.get(0).getType());
            assertEquals("hi", ds.get(0).getString("message", ""));
            assertEquals("wait", ds.get(1).getType());
            assertEquals(40, ds.get(1).getInt("ticks", 0));
        }

        @Test @DisplayName("handles empty actions") void emptyActions() {
            LLMResponse r = LLMResponse.success("raw");
            assertTrue(LLMResponseParser.parseActionDescriptors(r).isEmpty());
        }

        @Test @DisplayName("skips malformed JSON") void malformedJson() {
            LLMResponse r = LLMResponse.success("raw");
            r.setActions(List.of("not json", "{\"type\":\"say\"}"));
            List<ActionDescriptor> ds = LLMResponseParser.parseActionDescriptors(r);
            assertEquals(1, ds.size());
            assertEquals("say", ds.get(0).getType());
        }
    }

    @Nested @DisplayName("ActionDescriptor")
    class ActionDescriptorTests {
        @Test @DisplayName("getString returns value or default") void getString() {
            JsonObject p = new JsonObject(); p.addProperty("k", "v");
            ActionDescriptor d = new ActionDescriptor("t", p);
            assertEquals("v", d.getString("k", "def"));
            assertEquals("def", d.getString("missing", "def"));
        }

        @Test @DisplayName("getInt returns value or default") void getInt() {
            JsonObject p = new JsonObject(); p.addProperty("n", 42);
            ActionDescriptor d = new ActionDescriptor("t", p);
            assertEquals(42, d.getInt("n", 0));
            assertEquals(0, d.getInt("missing", 0));
        }

        @Test @DisplayName("getDouble returns value or default") void getDouble() {
            JsonObject p = new JsonObject(); p.addProperty("d", 3.14);
            ActionDescriptor d = new ActionDescriptor("t", p);
            assertEquals(3.14, d.getDouble("d", 0.0), 0.001);
            assertEquals(0.0, d.getDouble("missing", 0.0), 0.001);
        }

        @Test @DisplayName("getItemsMap parses item array") void getItemsMap() {
            JsonObject p = new JsonObject();
            JsonArray items = new JsonArray();
            JsonObject i1 = new JsonObject(); i1.addProperty("item_id", "minecraft:stone"); i1.addProperty("count", 5);
            JsonObject i2 = new JsonObject(); i2.addProperty("item", "minecraft:wood"); i2.addProperty("count", 3);
            items.add(i1); items.add(i2);
            p.add("items", items);
            ActionDescriptor d = new ActionDescriptor("require_items", p);
            Map<String, Integer> m = d.getItemsMap("items");
            assertEquals(5, m.get("minecraft:stone"));
            assertEquals(3, m.get("minecraft:wood"));
        }

        @Test @DisplayName("getItemsMap empty for missing key") void getItemsMapMissing() {
            ActionDescriptor d = new ActionDescriptor("t", new JsonObject());
            assertTrue(d.getItemsMap("items").isEmpty());
        }

        @Test @DisplayName("null type defaults to empty") void nullType() {
            ActionDescriptor d = new ActionDescriptor(null, null);
            assertEquals("", d.getType());
            assertNotNull(d.getParams());
        }

        @Test @DisplayName("toString contains type") void toStringFmt() {
            ActionDescriptor d = new ActionDescriptor("say", new JsonObject());
            assertTrue(d.toString().contains("say"));
        }
    }

    @Nested @DisplayName("getString/getInt/getDouble static helpers")
    class StaticHelperTests {
        @Test @DisplayName("getString from JsonObject") void getString() {
            JsonObject o = new JsonObject(); o.addProperty("k", "v");
            assertEquals("v", LLMResponseParser.getString(o, "k", "def"));
            assertEquals("def", LLMResponseParser.getString(o, "x", "def"));
        }

        @Test @DisplayName("getInt from JsonObject") void getInt() {
            JsonObject o = new JsonObject(); o.addProperty("n", 7);
            assertEquals(7, LLMResponseParser.getInt(o, "n", 0));
            assertEquals(0, LLMResponseParser.getInt(o, "x", 0));
        }

        @Test @DisplayName("getDouble from JsonObject") void getDouble() {
            JsonObject o = new JsonObject(); o.addProperty("d", 1.5);
            assertEquals(1.5, LLMResponseParser.getDouble(o, "d", 0.0), 0.001);
        }
    }

    @Nested @DisplayName("appendStreamingChunk")
    class StreamingTests {
        @Test @DisplayName("extracts content from delta") void deltaContent() {
            JsonObject delta = new JsonObject(); delta.addProperty("content", "hello");
            JsonObject choice = new JsonObject(); choice.add("delta", delta);
            JsonArray choices = new JsonArray(); choices.add(choice);
            JsonObject chunk = new JsonObject(); chunk.add("choices", choices);
            StringBuilder sb = new StringBuilder();
            LLMResponseParser.appendStreamingChunk(chunk, sb);
            assertEquals("hello", sb.toString());
        }

        @Test @DisplayName("handles empty choices") void emptyChoices() {
            JsonObject chunk = new JsonObject();
            chunk.add("choices", new JsonArray());
            StringBuilder sb = new StringBuilder();
            LLMResponseParser.appendStreamingChunk(chunk, sb);
            assertEquals("", sb.toString());
        }

        @Test @DisplayName("handles null choices") void nullChoices() {
            JsonObject chunk = new JsonObject();
            StringBuilder sb = new StringBuilder();
            LLMResponseParser.appendStreamingChunk(chunk, sb);
            assertEquals("", sb.toString());
        }

        @Test @DisplayName("handles message format") void messageFormat() {
            JsonObject msg = new JsonObject(); msg.addProperty("content", "world");
            JsonObject choice = new JsonObject(); choice.add("message", msg);
            JsonArray choices = new JsonArray(); choices.add(choice);
            JsonObject chunk = new JsonObject(); chunk.add("choices", choices);
            StringBuilder sb = new StringBuilder();
            LLMResponseParser.appendStreamingChunk(chunk, sb);
            assertEquals("world", sb.toString());
        }
    }
}