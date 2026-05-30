package com.aimod.ai.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BotMemoryStore — three-tier memory management.
 */
@DisplayName("BotMemoryStore — memory management")
class BotMemoryStoreTest {

    @TempDir
    Path tempDir;

    private BotMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new BotMemoryStore(tempDir, 50);
    }

    @Nested
    @DisplayName("Working memory")
    class WorkingMemory {
        @Test
        @DisplayName("Initial working memory is empty")
        void initialEmpty() {
            assertEquals(0, store.workingMemorySize());
        }

        @Test
        @DisplayName("getRecent returns empty list when no observations")
        void getRecentEmpty() {
            var recent = store.getRecent(10);
            assertNotNull(recent);
            assertTrue(recent.isEmpty());
        }
    }

    @Nested
    @DisplayName("Resource locations")
    class ResourceLocations {
        @Test
        @DisplayName("Initial resource count is 0")
        void initialCount() {
            assertEquals(0, store.resourceLocationCount());
        }

        @Test
        @DisplayName("markChunkExplored and isChunkExplored work together")
        void chunkExploration() {
            assertFalse(store.isChunkExplored(0, 0, "overworld"));
            store.markChunkExplored(0, 0, "overworld");
            assertTrue(store.isChunkExplored(0, 0, "overworld"));
            assertFalse(store.isChunkExplored(1, 0, "overworld"));
            assertFalse(store.isChunkExplored(0, 0, "nether"));
        }

        @Test
        @DisplayName("exploredChunkCount tracks unique chunks")
        void chunkCount() {
            assertEquals(0, store.exploredChunkCount());
            store.markChunkExplored(0, 0, "overworld");
            assertEquals(1, store.exploredChunkCount());
            store.markChunkExplored(0, 0, "overworld"); // duplicate
            assertEquals(1, store.exploredChunkCount());
            store.markChunkExplored(1, 0, "overworld");
            assertEquals(2, store.exploredChunkCount());
        }
    }

    @Nested
    @DisplayName("Summaries")
    class Summaries {
        @Test
        @DisplayName("Initial summaries is empty")
        void initialEmpty() {
            assertTrue(store.getShortTermSummaries().isEmpty());
        }
    }

    @Nested
    @DisplayName("Stats")
    class Stats {
        @Test
        @DisplayName("getStats returns non-empty string")
        void getStats() {
            String stats = store.getStats();
            assertNotNull(stats);
            assertFalse(stats.isEmpty());
            assertTrue(stats.contains("Working:"));
            assertTrue(stats.contains("Summaries:"));
            assertTrue(stats.contains("Resources:"));
            assertTrue(stats.contains("Chunks:"));
        }
    }

    @Nested
    @DisplayName("Clear")
    class ClearMemory {
        @Test
        @DisplayName("clear resets all counters")
        void clearResets() {
            store.markChunkExplored(0, 0, "overworld");
            store.markChunkExplored(1, 1, "overworld");
            assertEquals(2, store.exploredChunkCount());

            store.clear();
            assertEquals(0, store.workingMemorySize());
            assertEquals(0, store.resourceLocationCount());
            assertEquals(0, store.exploredChunkCount());
            assertTrue(store.getShortTermSummaries().isEmpty());
        }
    }

    @Nested
    @DisplayName("ResourceLocation")
    class ResourceLocationTest {
        @Test
        @DisplayName("ResourceLocation stores values correctly")
        void storeValues() {
            var rl = new BotMemoryStore.ResourceLocation("minecraft:iron_ore", 10, 64, 20, "overworld", 12345L);
            assertEquals("minecraft:iron_ore", rl.blockType);
            assertEquals(10, rl.x);
            assertEquals(64, rl.y);
            assertEquals(20, rl.z);
            assertEquals("overworld", rl.dimension);
            assertEquals(12345L, rl.discoveredAt);
            assertFalse(rl.mined);
        }

        @Test
        @DisplayName("shortName extracts name after colon")
        void shortName() {
            var rl = new BotMemoryStore.ResourceLocation("minecraft:iron_ore", 0, 0, 0, "overworld", 0);
            assertEquals("iron_ore", rl.shortName());
        }

        @Test
        @DisplayName("shortName returns full name if no colon")
        void shortNameNoColon() {
            var rl = new BotMemoryStore.ResourceLocation("iron_ore", 0, 0, 0, "overworld", 0);
            assertEquals("iron_ore", rl.shortName());
        }

        @Test
        @DisplayName("toString includes position and mined status")
        void toStringFormat() {
            var rl = new BotMemoryStore.ResourceLocation("minecraft:iron_ore", 10, 64, 20, "overworld", 0);
            String s = rl.toString();
            assertTrue(s.contains("iron_ore"));
            assertTrue(s.contains("(10,64,20)"));
            assertFalse(s.contains("[mined]"));

            rl.mined = true;
            assertTrue(rl.toString().contains("[mined]"));
        }
    }
}
