import java.io.*;
import java.util.zip.GZIPInputStream;

/**
 * Verifies a GZip-compressed NBT file by reading it back
 * and printing its structure.
 */
public class NbtVerifier {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java NbtVerifier <nbt-file>");
            return;
        }
        File f = new File(args[0]);
        System.out.println("File: " + f.getAbsolutePath());
        System.out.println("Size: " + f.length() + " bytes");
        
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(f)))) {
            readTag(in, "root", 0);
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    static void readTag(DataInputStream in, String name, int depth) throws IOException {
        int type = in.readUnsignedByte();
        String prefix = "  ".repeat(depth);
        
        if (type == 0) { // TAG_End
            System.out.println(prefix + "TAG_End('" + name + "')");
            return;
        }
        
        if (type != readTagTypeForName && type != 0) {
            // Not the root - read the name
            String tagName = in.readUTF();
            readTagValue(in, type, tagName, depth);
        } else if (type != 0) {
            String tagName = in.readUTF();
            readTagValue(in, type, tagName, depth);
        }
    }
    
    static final int readTagTypeForName = -1;
    
    static void readTagValue(DataInputStream in, int type, String name, int depth) throws IOException {
        String prefix = "  ".repeat(depth);
        
        switch (type) {
            case 1: // TAG_Byte
                System.out.println(prefix + "TAG_Byte('" + name + "') = " + in.readByte());
                break;
            case 2: // TAG_Short
                System.out.println(prefix + "TAG_Short('" + name + "') = " + in.readShort());
                break;
            case 3: // TAG_Int
                System.out.println(prefix + "TAG_Int('" + name + "') = " + in.readInt());
                break;
            case 4: // TAG_Long
                System.out.println(prefix + "TAG_Long('" + name + "') = " + in.readLong());
                break;
            case 5: // TAG_Float
                System.out.println(prefix + "TAG_Float('" + name + "') = " + in.readFloat());
                break;
            case 6: // TAG_Double
                System.out.println(prefix + "TAG_Double('" + name + "') = " + in.readDouble());
                break;
            case 7: { // TAG_Byte_Array
                int len = in.readInt();
                byte[] data = new byte[len];
                in.readFully(data);
                System.out.println(prefix + "TAG_Byte_Array('" + name + "') len=" + len);
                break;
            }
            case 8: // TAG_String
                System.out.println(prefix + "TAG_String('" + name + "') = \"" + in.readUTF() + "\"");
                break;
            case 9: { // TAG_List
                byte elemType = in.readByte();
                int len = in.readInt();
                System.out.println(prefix + "TAG_List('" + name + "') type=" + elemType + " len=" + len);
                for (int i = 0; i < len; i++) {
                    readTagValue(in, elemType, "[" + i + "]", depth + 1);
                }
                break;
            }
            case 10: { // TAG_Compound
                System.out.println(prefix + "TAG_Compound('" + name + "') {");
                while (true) {
                    byte fieldType = in.readByte();
                    if (fieldType == 0) break;
                    String fieldName = in.readUTF();
                    readTagValue(in, fieldType, fieldName, depth + 1);
                }
                System.out.println(prefix + "}");
                break;
            }
            case 11: { // TAG_Int_Array
                int len = in.readInt();
                int[] data = new int[len];
                for (int i = 0; i < len; i++) data[i] = in.readInt();
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < Math.min(len, 6); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(data[i]);
                }
                if (len > 6) sb.append(", ...");
                sb.append("]");
                System.out.println(prefix + "TAG_Int_Array('" + name + "') = " + sb);
                break;
            }
            case 12: { // TAG_Long_Array
                int len = in.readInt();
                long[] data = new long[len];
                for (int i = 0; i < len; i++) data[i] = in.readLong();
                System.out.println(prefix + "TAG_Long_Array('" + name + "') len=" + len);
                break;
            }
            default:
                System.out.println(prefix + "UNKNOWN(" + type + ")");
        }
    }
}
