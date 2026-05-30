package com.aimod.ai.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ContextAssembler — token math and budget allocation.
 */
@DisplayName("ContextAssembler — token math")
class ContextAssemblerTest {

    @Nested
    @DisplayName("Token conversion")
    class TokenConversion {
        @Test
        @DisplayName("charsToTokens returns positive value")
        void charsToTokens() {
            int tokens = ContextAssembler.charsToTokens(100);
            assertTrue(tokens > 0);
            // 100 chars / 3.5 chars per token ≈ 28 tokens
            assertEquals(28, tokens);
        }

        @Test
        @DisplayName("tokensToChars returns positive value")
        void tokensToChars() {
            int chars = ContextAssembler.tokensToChars(100);
            assertTrue(chars > 0);
            // 100 tokens * 3.5 chars per token = 350 chars
            assertEquals(350, chars);
        }

        @Test
        @DisplayName("charsToTokens handles zero")
        void charsToTokensZero() {
            int tokens = ContextAssembler.charsToTokens(0);
            assertEquals(1, tokens); // min 1
        }

        @Test
        @DisplayName("tokensToChars handles zero")
        void tokensToCharsZero() {
            int chars = ContextAssembler.tokensToChars(0);
            assertEquals(0, chars);
        }

        @Test
        @DisplayName("round-trip conversion is approximately identity")
        void roundTrip() {
            int original = 1000;
            int tokens = ContextAssembler.charsToTokens(original);
            int chars = ContextAssembler.tokensToChars(tokens);
            // Should be approximately the same (within rounding)
            assertTrue(Math.abs(original - chars) < 5,
                    "Round-trip: " + original + " -> " + tokens + " -> " + chars);
        }
    }

    @Nested
    @DisplayName("estimateTotalTokens")
    class EstimateTokens {
        @Test
        @DisplayName("Returns 0 for empty store")
        void emptyStore() {
            var store = new BotMemoryStore(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")), 50);
            int tokens = ContextAssembler.estimateTotalTokens(store);
            assertEquals(0, tokens);
        }
    }

    @Nested
    @DisplayName("assemble")
    class Assemble {
        @Test
        @DisplayName("Returns non-null for null task")
        void nullTask() {
            var store = new BotMemoryStore(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")), 50);
            String ctx = ContextAssembler.assemble(store, null, 1000);
            assertNotNull(ctx);
        }

        @Test
        @DisplayName("Returns non-null with replan attempts")
        void withReplanAttempts() {
            var store = new BotMemoryStore(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")), 50);
            var attempts = java.util.List.of("failed action 1", "failed action 2");
            String ctx = ContextAssembler.assemble(store, null, 1000, attempts);
            assertNotNull(ctx);
            assertTrue(ctx.contains("Recent Failed Attempts"));
            assertTrue(ctx.contains("failed action 1"));
        }

        @Test
        @DisplayName("Returns non-null with empty replan attempts")
        void emptyReplanAttempts() {
            var store = new BotMemoryStore(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")), 50);
            String ctx = ContextAssembler.assemble(store, null, 1000, java.util.List.of());
            assertNotNull(ctx);
        }
    }
}
