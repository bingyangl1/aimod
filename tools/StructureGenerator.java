import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone tool (no dependencies) for generating Minecraft 1.21
 * structure block NBT files. These files define the test area terrain
 * for NeoForge GameTest integration tests.
 *
 * CRITICAL: In NBT format, list elements do NOT carry type bytes or
 * names. Use writeAnonymousCompoundBegin() for list elements.
 *
 * Usage:
 *   javac tools/NbtWriter.java tools/StructureGenerator.java
 *   java -cp tools StructureGenerator <output-dir>
 */
public class StructureGenerator {

    public static void main(String[] args) throws Exception {
        String outputDir = args.length > 0
            ? args[0]
            : "src/main/resources/data/aimod/structure";

        File dir = new File(outputDir);
        dir.mkdirs();

        generateStonePlatform(new File(dir, "gametestregistry.empty.nbt"), 5, 1, 5);
        generateStonePlatform(new File(dir, "gametestregistry.pathfind.nbt"), 21, 1, 21);
        generateObstacleCourse(new File(dir, "gametestregistry.obstacle.nbt"));

        System.out.println("All structures generated in: " + dir.getAbsolutePath());
    }

    static void generateStonePlatform(File file, int w, int h, int d) throws IOException {
        int count = w * h * d;
        try (NbtWriter nbt = new NbtWriter(new FileOutputStream(file))) {
            nbt.writeCompoundBegin("");                        // root compound

            nbt.writeIntArray("size", new int[]{w, h, d});

            // blocks list — elements are anonymous compounds
            nbt.writeListBegin("blocks", (byte) 0x0a, count);
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    for (int z = 0; z < d; z++) {
                        nbt.writeAnonymousCompoundBegin();     // no type/name in list
                        nbt.writeIntArray("pos", new int[]{x, y, z});
                        nbt.writeInt("state", 0);
                        nbt.writeEnd();
                    }
                }
            }

            // palette list — 1 element, anonymous compound
            nbt.writeListBegin("palette", (byte) 0x0a, 1);
            nbt.writeAnonymousCompoundBegin();
            nbt.writeString("Name", "minecraft:stone");
            nbt.writeEnd();

            // entities list — empty
            nbt.writeListBegin("entities", (byte) 0x0a, 0);

            nbt.writeEnd();  // root compound
        }
    }

    static void generateObstacleCourse(File file) throws IOException {
        int w = 11, h = 5, d = 11;
        List<int[]> blocks = new ArrayList<>();
        List<String> palette = new ArrayList<>();
        palette.add("minecraft:stone");       // state 0
        palette.add("minecraft:cobblestone"); // state 1

        // Floor (y=0) — all stone
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                blocks.add(new int[]{x, 0, z, 0});
            }
        }

        // Walls (y=1..3) — cobblestone with a gap in front wall at x=5
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x < w; x++) {
                blocks.add(new int[]{x, y, d - 1, 1}); // back wall
                blocks.add(new int[]{0, y, x, 1});     // left wall
                blocks.add(new int[]{w - 1, y, x, 1}); // right wall
            }
            for (int x = 0; x < w; x++) {
                if (x == 5) continue;
                blocks.add(new int[]{x, y, 0, 1});     // front wall with gap
            }
        }

        try (NbtWriter nbt = new NbtWriter(new FileOutputStream(file))) {
            nbt.writeCompoundBegin("");
            nbt.writeIntArray("size", new int[]{w, h, d});

            nbt.writeListBegin("blocks", (byte) 0x0a, blocks.size());
            for (int[] b : blocks) {
                nbt.writeAnonymousCompoundBegin();
                nbt.writeIntArray("pos", new int[]{b[0], b[1], b[2]});
                nbt.writeInt("state", b[3]);
                nbt.writeEnd();
            }

            nbt.writeListBegin("palette", (byte) 0x0a, palette.size());
            for (String p : palette) {
                nbt.writeAnonymousCompoundBegin();
                nbt.writeString("Name", p);
                nbt.writeEnd();
            }

            nbt.writeListBegin("entities", (byte) 0x0a, 0);
            nbt.writeEnd();
        }
    }
}
