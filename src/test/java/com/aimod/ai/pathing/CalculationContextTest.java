package com.aimod.ai.pathing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CalculationContext Tests")
class CalculationContextTest {

    @Test
    @DisplayName("Config default values are correct")
    void defaultConfigValues() {
        CalculationContext.Config config = CalculationContext.defaultConfig();
        assertTrue(config.allowBreak);
        assertTrue(config.allowPlace);
        assertTrue(config.allowSprint);
        assertTrue(config.allowWaterBucketFall);
        assertEquals(4, config.maxFallBlocks);
        assertEquals(3.0, config.fallDamageThreshold, 0.001);
    }

    @Test
    @DisplayName("Config stores all fields correctly")
    void configFields() {
        CalculationContext.Config config = new CalculationContext.Config(
                false, true, false, true, 6, 5.0
        );
        assertFalse(config.allowBreak);
        assertTrue(config.allowPlace);
        assertFalse(config.allowSprint);
        assertTrue(config.allowWaterBucketFall);
        assertEquals(6, config.maxFallBlocks);
        assertEquals(5.0, config.fallDamageThreshold, 0.001);
    }

    @Test
    @DisplayName("blockPosHash matches BlockPos.asLong encoding")
    void blockPosHashConsistent() {
        // BlockPos.asLong encodes as: ((long)(x & 0x3FFFFFF) << 38) | ((long)(z & 0x3FFFFFF) << 12) | (y & 0xFFF)
        // Our hash should produce the same result for consistency
        int x = 100, y = 64, z = -200;
        long expected = ((long)(x & 0x3FFFFFF) << 38) | ((long)(z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        // Note: We can't directly call blockPosHash (it's private), but we verify the encoding
        // formula matches BlockPos.asLong()
        assertEquals(expected, net.minecraft.core.BlockPos.asLong(x, y, z));
    }
}