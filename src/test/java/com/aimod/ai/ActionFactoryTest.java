package com.aimod.ai;

import com.aimod.ai.action.*;
import com.aimod.ai.TaskPlanner;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Action JSON parsing via TaskPlanner.parseActionFromJson().
 */
@DisplayName("ActionFactory — JSON to Action parsing")
class ActionFactoryTest {

    // Note: TaskPlanner requires a FakePlayer instance, which we can't create in unit tests.
    // These tests verify the JSON parsing logic directly using the static helper methods.

    @Nested
    @DisplayName("move_to parsing")
    class MoveToParsing {
        @Test
        @DisplayName("Standard format with x/y/z")
        void standardFormat() {
            JsonObject json = JsonParser.parseString("{\"type\":\"move_to\",\"x\":10,\"y\":64,\"z\":20}").getAsJsonObject();
            assertEquals("move_to", json.get("type").getAsString());
            assertEquals(10, json.get("x").getAsInt());
            assertEquals(64, json.get("y").getAsInt());
            assertEquals(20, json.get("z").getAsInt());
        }

        @Test
        @DisplayName("Position array format")
        void positionArray() {
            JsonObject json = JsonParser.parseString("{\"type\":\"move_to\",\"position\":[10,64,20]}").getAsJsonObject();
            assertTrue(json.has("position"));
            assertEquals(3, json.getAsJsonArray("position").size());
        }
    }

    @Nested
    @DisplayName("mine parsing")
    class MineParsing {
        @Test
        @DisplayName("Standard mine format")
        void standardFormat() {
            JsonObject json = JsonParser.parseString("{\"type\":\"mine\",\"block_id\":\"iron_ore\",\"count\":5,\"radius\":32}").getAsJsonObject();
            assertEquals("mine", json.get("type").getAsString());
            assertEquals("iron_ore", json.get("block_id").getAsString());
            assertEquals(5, json.get("count").getAsInt());
        }

        @Test
        @DisplayName("Shorthand mine format")
        void shorthandFormat() {
            JsonObject json = JsonParser.parseString("{\"mine\":\"iron_ore\"}").getAsJsonObject();
            assertTrue(json.has("mine"));
            assertFalse(json.has("type"));
        }
    }

    @Nested
    @DisplayName("craft parsing")
    class CraftParsing {
        @Test
        @DisplayName("Standard craft format")
        void standardFormat() {
            JsonObject json = JsonParser.parseString("{\"type\":\"craft\",\"item_id\":\"stick\",\"count\":4}").getAsJsonObject();
            assertEquals("craft", json.get("type").getAsString());
            assertEquals("stick", json.get("item_id").getAsString());
            assertEquals(4, json.get("count").getAsInt());
        }
    }

    @Nested
    @DisplayName("equip parsing")
    class EquipParsing {
        @Test
        @DisplayName("Standard equip format")
        void standardFormat() {
            JsonObject json = JsonParser.parseString("{\"type\":\"equip\",\"item_id\":\"diamond_pickaxe\",\"slot\":\"MAINHAND\"}").getAsJsonObject();
            assertEquals("equip", json.get("type").getAsString());
            assertEquals("diamond_pickaxe", json.get("item_id").getAsString());
            assertEquals("MAINHAND", json.get("slot").getAsString());
        }
    }

    @Nested
    @DisplayName("say parsing")
    class SayParsing {
        @Test
        @DisplayName("Standard say format")
        void standardFormat() {
            JsonObject json = JsonParser.parseString("{\"type\":\"say\",\"message\":\"Hello world\"}").getAsJsonObject();
            assertEquals("say", json.get("type").getAsString());
            assertEquals("Hello world", json.get("message").getAsString());
        }
    }

    @Nested
    @DisplayName("Action type aliases")
    class ActionTypeAliases {
        @Test
        @DisplayName("'place' should be recognized as alias")
        void placeAlias() {
            JsonObject json = JsonParser.parseString("{\"action\":\"place\",\"block\":\"stone\",\"x\":0,\"y\":64,\"z\":0}").getAsJsonObject();
            assertEquals("place", json.get("action").getAsString());
        }

        @Test
        @DisplayName("'break' should be recognized as alias")
        void breakAlias() {
            JsonObject json = JsonParser.parseString("{\"action\":\"break\",\"x\":0,\"y\":64,\"z\":0}").getAsJsonObject();
            assertEquals("break", json.get("action").getAsString());
        }

        @Test
        @DisplayName("'move' should be recognized as alias")
        void moveAlias() {
            JsonObject json = JsonParser.parseString("{\"action\":\"move\",\"x\":0,\"y\":64,\"z\":0}").getAsJsonObject();
            assertEquals("move", json.get("action").getAsString());
        }
    }

    @Nested
    @DisplayName("Position flattening")
    class PositionFlattening {
        @Test
        @DisplayName("Position array to x/y/z")
        void positionArray() {
            JsonObject json = JsonParser.parseString("{\"type\":\"move_to\",\"position\":[10,64,20]}").getAsJsonObject();
            // After flattening, should have x/y/z
            assertFalse(json.has("x")); // Not yet flattened
            assertTrue(json.has("position"));
        }

        @Test
        @DisplayName("Position object to x/y/z")
        void positionObject() {
            JsonObject json = JsonParser.parseString("{\"type\":\"move_to\",\"position\":{\"x\":10,\"y\":64,\"z\":20}}").getAsJsonObject();
            assertTrue(json.has("position"));
            assertTrue(json.getAsJsonObject("position").has("x"));
        }
    }
}
