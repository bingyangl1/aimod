import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * Low-level writer for Minecraft's Named Binary Tag (NBT) format.
 * Supports writing GZip-compressed NBT files including compounds,
 * strings, ints, int arrays, and lists.
 *
 * CRITICAL: In NBT format, elements inside a TAG_List do NOT carry
 * a type byte or name — they are raw payloads only. Use
 * writeAnonymousCompoundBegin() for list elements.
 */
public class NbtWriter implements Closeable {
    private final DataOutputStream out;

    public NbtWriter(OutputStream rawOut) throws IOException {
        this.out = new DataOutputStream(new GZIPOutputStream(rawOut));
    }

    /**
     * Write a named compound as a field inside another compound.
     * Writes: TAG_Compound type (0x0a) + name + [body]
     */
    public void writeCompoundBegin(String name) throws IOException {
        out.writeByte(0x0a);
        writeStringPayload(name);
    }

    /**
     * Write an anonymous compound as an element inside a list.
     * Writes ONLY the compound body (no type byte, no name).
     * Must still be closed with writeEnd().
     */
    public void writeAnonymousCompoundBegin() throws IOException {
        // No type byte, no name — just the compound body
    }

    public void writeEnd() throws IOException {
        out.writeByte(0x00);
    }

    public void writeByte(String name, byte value) throws IOException {
        out.writeByte(0x01);
        writeStringPayload(name);
        out.writeByte(value);
    }

    public void writeShort(String name, short value) throws IOException {
        out.writeByte(0x02);
        writeStringPayload(name);
        out.writeShort(value);
    }

    public void writeInt(String name, int value) throws IOException {
        out.writeByte(0x03);
        writeStringPayload(name);
        out.writeInt(value);
    }

    public void writeString(String name, String value) throws IOException {
        out.writeByte(0x08);
        writeStringPayload(name);
        writeStringPayload(value);
    }

    public void writeListBegin(String name, byte elementTypeId, int length) throws IOException {
        out.writeByte(0x09);
        writeStringPayload(name);
        out.writeByte(elementTypeId);
        out.writeInt(length);
    }

    public void writeIntArray(String name, int[] values) throws IOException {
        out.writeByte(0x0b);
        writeStringPayload(name);
        out.writeInt(values.length);
        for (int v : values) {
            out.writeInt(v);
        }
    }

    private void writeStringPayload(String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
