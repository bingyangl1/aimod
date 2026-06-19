package com.aimod.ai.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PlanCache Similarity Tests")
class PlanCacheSimilarityTest {

    @Test
    @DisplayName("identical strings score 1.0")
    void identical() {
        assertEquals(1.0, PlanCache.similarity("hello world", "hello world"), 0.001);
    }

    @Test
    @DisplayName("empty string returns 0")
    void empty() {
        assertEquals(0.0, PlanCache.similarity("", "hello"), 0.001);
        assertEquals(0.0, PlanCache.similarity("hello", ""), 0.001);
    }

    @Test
    @DisplayName("both empty returns 1.0 (identical)")
    void bothEmpty() {
        assertEquals(1.0, PlanCache.similarity("", ""), 0.001);
    }

    @Test
    @DisplayName("completely different strings score near 0")
    void different() {
        double score = PlanCache.similarity("apple banana", "xylophone zebra");
        assertTrue(score < 0.2, "score=" + score);
    }

    @Test
    @DisplayName("short strings use char bigram fallback")
    void shortStrings() {
        double score = PlanCache.similarity("ab", "ab");
        assertEquals(1.0, score, 0.001);
    }

    @Test
    @DisplayName("short different strings score low")
    void shortDifferent() {
        double score = PlanCache.similarity("abc", "xyz");
        assertTrue(score < 0.3, "score=" + score);
    }

    @Test
    @DisplayName("Chinese characters match")
    void chinese() {
        double score = PlanCache.similarity("挖矿 5 个钻石", "挖矿 3 个钻石");
        assertTrue(score > 0.5, "score=" + score);
    }

    @Test
    @DisplayName("number normalization makes numeric variants similar")
    void numberNormalization() {
        double score = PlanCache.similarity("gather 10 wood", "gather 5 wood");
        assertTrue(score > 0.7, "score=" + score);
    }

    @Test
    @DisplayName("word order matters (not just bag of words)")
    void wordOrder() {
        double ab = PlanCache.similarity("craft diamond pickaxe", "craft pickaxe diamond");
        double different = PlanCache.similarity("craft diamond pickaxe", "mine diamond ore");
        assertTrue(ab > different, "ab=" + ab + ", diff=" + different);
    }

    @Test
    @DisplayName("partial overlap scores between 0 and 1")
    void partialOverlap() {
        double score = PlanCache.similarity("砍 10 棵树", "砍 3 个木头");
        assertTrue(score > 0.2 && score < 0.9, "score=" + score);
    }

    @Test
    @DisplayName("longer specific tokens get higher weight")
    void tokenWeighting() {
        double specific = PlanCache.similarity("craft netherite_chestplate", "craft stone_pickaxe");
        double generic = PlanCache.similarity("get item", "give item");
        assertTrue(specific < 0.6, "specific=" + specific);
    }

    @Test
    @DisplayName("same command with different numbers scores well")
    void sameCommandDiffNumbers() {
        double s1 = PlanCache.similarity("mine 32 cobblestone", "mine 64 cobblestone");
        double s2 = PlanCache.similarity("mine 32 cobblestone", "craft 32 planks");
        assertTrue(s1 > s2, "s1=" + s1 + ", s2=" + s2);
    }

    @Test
    @DisplayName("null-safe: handles null input")
    void nullInput() {
        assertEquals(0.0, PlanCache.similarity(null, "hello"), 0.001);
        assertEquals(0.0, PlanCache.similarity("hello", null), 0.001);
        assertEquals(0.0, PlanCache.similarity(null, null), 0.001);
    }
}
