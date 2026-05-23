package com.aimod.ai.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Cached block state data for a single 16x16 chunk.
 * Stores complete BlockState for every block in the chunk.
 */
public class CachedChunkData {

    private final int chunkX, chunkZ;
    private final int minY;
    private final BlockState[][][] blocks; // [y][z][x]

    private static final int SECTION_HEIGHT = 16;
    private static final int SECTIONS = 24; // -64 to 320 in 1.21

    public CachedChunkData(int chunkX, int chunkZ, int minY) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = minY;
        this.blocks = new BlockState[SECTIONS * SECTION_HEIGHT][16][16];
        // Fill with AIR
        for (int y = 0; y < blocks.length; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    blocks[y][z][x] = Blocks.AIR.defaultBlockState();
                }
            }
        }
    }

    public void setBlock(int x, int y, int z, BlockState state) {
        int yi = y - minY;
        if (yi >= 0 && yi < blocks.length && x >= 0 && x < 16 && z >= 0 && z < 16) {
            blocks[yi][z][x] = state;
        }
    }

    public BlockState getBlock(int x, int y, int z) {
        int yi = y - minY;
        if (yi >= 0 && yi < blocks.length && x >= 0 && x < 16 && z >= 0 && z < 16) {
            return blocks[yi][z][x];
        }
        return Blocks.AIR.defaultBlockState();
    }

    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }

    public static long key(int chunkX, int chunkZ) {
        return (long) chunkX & 0xFFFFFFFFL | ((long) chunkZ & 0xFFFFFFFFL) << 32;
    }

    public static int chunkKeyToX(long key) { return (int) key; }
    public static int chunkKeyToZ(long key) { return (int) (key >> 32); }

    public static int blockToChunkX(BlockPos pos) { return pos.getX() >> 4; }
    public static int blockToChunkZ(BlockPos pos) { return pos.getZ() >> 4; }
}
