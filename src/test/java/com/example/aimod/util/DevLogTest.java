package com.example.aimod.util;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DevLogTest {
    @Test void compactNull() { assertEquals("null", DevLog.compact(null)); }
    @Test void compactShort() { assertEquals("hello", DevLog.compact("hello")); }
    @Test void compactNewlines() { assertEquals("a b c", DevLog.compact("a\nb\nc")); }
    @Test void compactTrims() { assertEquals("hello", DevLog.compact("  hello  ")); }
    @Test void compactTruncates() { assertTrue(DevLog.compact("x".repeat(5000)).endsWith("...<truncated>")); }
    @Test void compactMax() { assertEquals("y".repeat(4000), DevLog.compact("y".repeat(4000))); }
    @Test void keyNonEmpty() { assertFalse(DevLog.KEY.isEmpty()); }
}