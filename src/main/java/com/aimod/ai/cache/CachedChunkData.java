package com.aimod.ai.cache;

import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Cached block state data for a single 16x16 chunk.
 * Uses flat int[] for block state IDs (~393KB/chunk) instead of
 * BlockState[][][] 3D array (~917KB/chunk) to halve memory per chunk.
 *
 * <p>Also maintains a 2-bit pathing-type overlay (~24KB/chunk) for
 * fast pathfinding queries without resolving full BlockState.</p>
 *
 * <p>Pathing type encoding (2 bits per block):</p>
 * <ul>
 *   <li>0 = AIR (walk through / fall through)</li>
 *   <li>1 = WATER (swim or avoid)</li>
 *   <li>2 = AVOID (lava, cactus, fire — pathfinder must route around)</li>
 *   <li>3 = SOLID (walk on / break through)</li>
 * </ul>
 */
public class CachedChunkData {

    private static final int SECTION_HEIGHT = 16;
    private static final int SECTIONS = 24; // -64 to 320 in 1.21
    private static final int Y_SIZE = SECTIONS * SECTION_HEIGHT; // 384
    private static final int BLOCKS_PER_CHUNK = Y_SIZE * 16 * 16; // 98,304
    private static final int TYPES_SIZE = BLOCKS_PER_CHUNK / 4; // 24,576 bytes

    // Pathing type constants
    public static final byte TYPE_AIR = 0;
    public static final byte TYPE_WATER = 1;
    public static final byte TYPE_AVOID = 2;
    public static final byte TYPE_SOLID = 3;

    private final int chunkX, chunkZ;
    private final int minY;

    /** Flat array of block state IDs (compact, no array-of-arrays overhead). */
    private final int[] blocks;

    /** 2-bit type overlay: 4 blocks per byte, ~24KB per chunk. */
    private final byte[] pathingTypes;

    public CachedChunkData(int chunkX, int chunkZ, int minY) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = minY;
        this.blocks = new int[BLOCKS_PER_CHUNK];
        this.pathingTypes = new byte[TYPES_SIZE];
        // All blocks default to AIR (int id 0, type 0) — already zeroed
    }

    /**
     * Set a block state at local chunk coordinates.
     * Also computes and stores the 2-bit pathing type.
     */
    public void setBlock(int x, int y, int z, BlockState state) {
        int yi = y - minY;
        if (yi < 0 || yi >= Y_SIZE || x < 0 || x >= 16 || z < 0 || z >= 16) return;
        int idx = yi * 256 + z * 16 + x;
        blocks[idx] = Block.getId(state);
        // Set pathing type in the bit-packed byte
        int byteIdx = idx >>> 2;
        int bitShift = (idx & 3) << 1;
        pathingTypes[byteIdx] = (byte)(pathingTypes[byteIdx] & ~(3 << bitShift) | (classifyBlock(state) << bitShift));
    }

    /**
     * Get full BlockState at local chunk coordinates.
     */
    public BlockState getBlock(int x, int y, int z) {
        int yi = y - minY;
        if (yi < 0 || yi >= Y_SIZE || x < 0 || x >= 16 || z < 0 || z >= 16) {
            return Blocks.AIR.defaultBlockState();
        }
        return Block.stateById(blocks[yi * 256 + z * 16 + x]);
    }

    /**
     * Get the pathing type (0-3) at local chunk coordinates.
     * Fast path — no BlockState resolution needed.
     */
    public byte getPathingType(int x, int y, int z) {
        int yi = y - minY;
        if (yi < 0 || yi >= Y_SIZE || x < 0 || x >= 16 || z < 0 || z >= 16) {
            return TYPE_AIR;
        }
        int idx = yi * 256 + z * 16 + x;
        return (byte)((pathingTypes[idx >>> 2] >>> ((idx & 3) << 1)) & 3);
    }

    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }

    // ---- Static helpers ----

    public static long key(int chunkX, int chunkZ) {
        return (long) chunkX & 0xFFFFFFFFL | ((long) chunkZ & 0xFFFFFFFFL) << 32;
    }

    public static int chunkKeyToX(long key) { return (int) key; }
    public static int chunkKeyToZ(long key) { return (int) (key >> 32); }

    public static int blockToChunkX(BlockPos pos) { return pos.getX() >> 4; }
    public static int blockToChunkZ(BlockPos pos) { return pos.getZ() >> 4; }

    /**
     * Classify a BlockState into one of the 4 pathing types.
     */
    static byte classifyBlock(BlockState state) {
        if (state.isAir()) return TYPE_AIR;
        if (state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.BUBBLE_COLUMN) return TYPE_WATER;
        if (state.getBlock() == Blocks.LAVA
                || state.getBlock() == Blocks.CACTUS
                || state.getBlock() == Blocks.FIRE
                || state.getBlock() == Blocks.SOUL_FIRE
                || state.getBlock() == Blocks.MAGMA_BLOCK
                || state.getBlock() == Blocks.CAMPFIRE
                || state.getBlock() == Blocks.SWEET_BERRY_BUSH
                || state.getBlock() == Blocks.POWDER_SNOW) return TYPE_AVOID;
        return TYPE_SOLID;
    }

    /**
     * Count blocks of given pathing types in chunk (for debugging).
     */
    public int countType(byte type) {
        int count = 0;
        for (int i = 0; i < TYPES_SIZE; i++) {
            byte b = pathingTypes[i];
            for (int s = 0; s < 4; s++) {
                if ((b & 3) == type) count++;
                b >>= 2;
            }
        }
        return count;
    }

    // ---- Serialization ----

    private static final int CACHE_VERSION = 1;

    /**
     * Save this chunk as a GZIP-compressed file.
     * Format: version(int), minY(int), blocks(int[]), pathingTypes(byte[]).
     */
    public void save(Path file) throws IOException {
        try (var dos = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(
                Files.newOutputStream(file))))) {
            dos.writeInt(CACHE_VERSION);
            dos.writeInt(minY);
            for (int id : blocks) dos.writeInt(id);
            dos.write(pathingTypes);
        }
    }

    /**
     * Load a chunk from a GZIP-compressed file.
     * Returns null if the file is invalid or incompatible version.
     */
    public static CachedChunkData load(Path file, int chunkX, int chunkZ) {
        if (!Files.exists(file)) return null;
        try (var dis = new DataInputStream(new GZIPInputStream(new BufferedInputStream(
                Files.newInputStream(file))))) {
            int version = dis.readInt();
            if (version != CACHE_VERSION) {
                DevLog.warn("CACHE_LOAD_VERSION", "file={}, version={}, expected={}", file, version, CACHE_VERSION);
                return null;
            }
            int minY = dis.readInt();
            var data = new CachedChunkData(chunkX, chunkZ, minY);
            for (int i = 0; i < BLOCKS_PER_CHUNK; i++) data.blocks[i] = dis.readInt();
            int read = dis.read(data.pathingTypes);
            if (read != TYPES_SIZE) {
                DevLog.warn("CACHE_LOAD_TRUNCATED", "file={}, read={}, expected={}", file, read, TYPES_SIZE);
                return null;
            }
            return data;
        } catch (Exception e) {
            DevLog.warn("CACHE_LOAD_FAIL", "file={}, err={}", file, e.getMessage());
            return null;
        }
    }
}
