package com.example.aimod.ai.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LLMService Rate Limiting and Retry Tests")
class LLMRateLimitRetryTest {

    @Nested
    @DisplayName("RateLimiter - Basic Functionality")
    class RateLimiterBasicTests {

        @Test
        @DisplayName("Allows requests when under the limit")
        void allowsUnderLimit() throws InterruptedException {
            var limiter = new LLMService.RateLimiter(5, 60_000L);
            limiter.acquire();
            limiter.acquire();
            assertEquals(2, limiter.getCount());
        }

        @Test
        @DisplayName("Blocks when over limit and slides window after wait")
        void blocksOverLimit() throws InterruptedException {
            var limiter = new LLMService.RateLimiter(2, 500L);

            limiter.acquire();
            limiter.acquire();

            long start = System.nanoTime();
            limiter.acquire();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertTrue(elapsedMs >= 400, "Should have waited for window to slide, got " + elapsedMs + "ms");
            // Only the 3rd request is within the window; first two expired during wait
            assertEquals(1, limiter.getCount());
        }

        @Test
        @DisplayName("Window slides correctly after all entries expire")
        void windowSlidesAfterExpiry() throws InterruptedException {
            var limiter = new LLMService.RateLimiter(3, 300L);

            limiter.acquire();
            limiter.acquire();
            limiter.acquire();
            assertEquals(3, limiter.getCount());

            Thread.sleep(350L);

            assertEquals(0, limiter.getCount());

            limiter.acquire();
            assertEquals(1, limiter.getCount());
        }

        @Test
        @DisplayName("getCount returns zero for empty limiter")
        void getCountEmpty() {
            var limiter = new LLMService.RateLimiter(10, 60_000L);
            assertEquals(0, limiter.getCount());
        }

        @Test
        @DisplayName("Works with maxRequests = 1")
        void singleRequestLimit() throws InterruptedException {
            var limiter = new LLMService.RateLimiter(1, 500L);

            limiter.acquire();
            assertEquals(1, limiter.getCount());

            long start = System.nanoTime();
            limiter.acquire();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertTrue(elapsedMs >= 400, "Should have waited, got " + elapsedMs + "ms");
            assertEquals(1, limiter.getCount());
        }

        @Test
        @DisplayName("Rapid sequential acquires within limit do not block")
        void rapidAcquiresUnderLimit() throws InterruptedException {
            var limiter = new LLMService.RateLimiter(100, 60_000L);

            long start = System.nanoTime();
            for (int i = 0; i < 10; i++) {
                limiter.acquire();
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertTrue(elapsedMs < 500, "10 acquires should be fast, took " + elapsedMs + "ms");
            assertEquals(10, limiter.getCount());
        }
    }

    @Nested
    @DisplayName("RateLimiter - Edge Cases")
    class RateLimiterEdgeCaseTests {

        @Test
        @DisplayName("Constructor clamps maxRequests to at least 1")
        void clampsMinimum() throws InterruptedException {
            var limiter = new LLMService.RateLimiter(0, 1_000L);
            limiter.acquire();
            assertEquals(1, limiter.getCount());
        }

        @Test
        @DisplayName("Constructor handles negative input")
        void handlesNegativeInput() throws InterruptedException {
            var limiter = new LLMService.RateLimiter(-5, 1_000L);
            limiter.acquire();
            assertEquals(1, limiter.getCount());
        }
    }

    @Nested
    @DisplayName("Retry Mechanism - Structure")
    class RetryStructureTests {

        @Test
        @DisplayName("LLMService has maxRetries field")
        void hasMaxRetriesField() throws Exception {
            var field = LLMService.class.getDeclaredField("maxRetries");
            assertEquals(int.class, field.getType());
        }

        @Test
        @DisplayName("LLMService has RateLimiter field")
        void hasRateLimiterField() throws Exception {
            var fields = LLMService.class.getDeclaredFields();
            boolean hasField = false;
            for (var f : fields) {
                if (f.getType().getName().contains("RateLimiter")) {
                    hasField = true;
                    break;
                }
            }
            assertTrue(hasField, "LLMService should have a RateLimiter field");
        }

        @Test
        @DisplayName("RateLimiter is a static inner class of LLMService")
        void rateLimiterIsStaticInnerClass() {
            assertNotNull(LLMService.RateLimiter.class);
            assertTrue(
                (LLMService.RateLimiter.class.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0,
                "RateLimiter should be static"
            );
        }
    }

    @Nested
    @DisplayName("Retry Logic - isRetryable Behavior")
    class RetryLogicTests {

        @Test
        @DisplayName("SocketTimeoutException is retryable")
        void socketTimeoutIsRetryable() throws Exception {
            var method = LLMService.class.getDeclaredMethod("isRetryable", java.io.IOException.class);
            method.setAccessible(true);
            var e = new java.net.SocketTimeoutException("read timed out");
            var result = method.invoke(null, e);
            assertEquals(true, result);
        }

        @Test
        @DisplayName("IOException with status 429 is retryable")
        void status429IsRetryable() throws Exception {
            var method = LLMService.class.getDeclaredMethod("isRetryable", java.io.IOException.class);
            method.setAccessible(true);
            var e = new java.io.IOException("API returned status 429: Too Many Requests");
            var result = method.invoke(null, e);
            assertEquals(true, result);
        }

        @Test
        @DisplayName("IOException with status 500 is retryable")
        void status500IsRetryable() throws Exception {
            var method = LLMService.class.getDeclaredMethod("isRetryable", java.io.IOException.class);
            method.setAccessible(true);
            var e = new java.io.IOException("API returned status 500: Internal Server Error");
            var result = method.invoke(null, e);
            assertEquals(true, result);
        }

        @Test
        @DisplayName("IOException with status 400 is NOT retryable")
        void status400IsNotRetryable() throws Exception {
            var method = LLMService.class.getDeclaredMethod("isRetryable", java.io.IOException.class);
            method.setAccessible(true);
            var e = new java.io.IOException("API returned status 400: Bad Request");
            var result = method.invoke(null, e);
            assertEquals(false, result);
        }

        @Test
        @DisplayName("IOException with status 401 is NOT retryable")
        void status401IsNotRetryable() throws Exception {
            var method = LLMService.class.getDeclaredMethod("isRetryable", java.io.IOException.class);
            method.setAccessible(true);
            var e = new java.io.IOException("API returned status 401: Unauthorized");
            var result = method.invoke(null, e);
            assertEquals(false, result);
        }

        @Test
        @DisplayName("IOException with 'timed out' message is retryable")
        void timedOutIsRetryable() throws Exception {
            var method = LLMService.class.getDeclaredMethod("isRetryable", java.io.IOException.class);
            method.setAccessible(true);
            var e = new java.io.IOException("Connection timed out");
            var result = method.invoke(null, e);
            assertEquals(true, result);
        }

        @Test
        @DisplayName("IOException with 'refused' message is retryable")
        void refusedIsRetryable() throws Exception {
            var method = LLMService.class.getDeclaredMethod("isRetryable", java.io.IOException.class);
            method.setAccessible(true);
            var e = new java.io.IOException("Connection refused");
            var result = method.invoke(null, e);
            assertEquals(true, result);
        }

        @Test
        @DisplayName("IOException with null message is not retryable")
        void nullMessageIsNotRetryable() throws Exception {
            var method = LLMService.class.getDeclaredMethod("isRetryable", java.io.IOException.class);
            method.setAccessible(true);
            var e = new java.io.IOException((String) null);
            var result = method.invoke(null, e);
            assertEquals(false, result);
        }
    }
}
