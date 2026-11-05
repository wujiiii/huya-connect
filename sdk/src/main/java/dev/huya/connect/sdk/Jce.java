package dev.huya.connect.sdk;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Lossless wire fields: lists preserve duplicate tags and duplicate map keys. */
public final class Jce {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public record Field(int tag, int type, Object value, String rawBase64) {
        public String text() { return String.valueOf(value); }
        public long number() { return Long.parseLong(text()); }
        @SuppressWarnings("unchecked")
        public List<Field> fields() { return (List<Field>) value; }
    }
    public record Entry(Field key, Field value) { }
    private Jce() { }

    public static List<Field> decode(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("JCE byte limit");
        return new Reader(bytes).fields(0, false);
    }
    public static Field find(List<Field> fields, int tag) {
        return fields.stream().filter(f -> f.tag == tag).findFirst().orElse(null);
    }
    static long number(List<Field> fields, int tag, long fallback) {
        Field f = find(fields, tag);
        if (f == null) return fallback;
        if (f.type > 3 && f.type != 12) throw new IllegalArgumentException("Expected integer at tag " + tag);
        return f.number();
    }
    static String text(List<Field> fields, int tag, String fallback) {
        Field f = find(fields, tag);
        if (f == null) return fallback;
        if (f.type != 6 && f.type != 7) throw new IllegalArgumentException("Expected string at tag " + tag);
        return f.text();
    }
    static String id(List<Field> fields, int tag) {
        Field f = find(fields, tag);
        return f == null ? null : Long.toUnsignedString(number(fields, tag, 0));
    }
    static byte[] bytes(List<Field> fields, int tag) {
        Field f = find(fields, tag);
        if (f == null) return new byte[0];
        if (f.type != 13) throw new IllegalArgumentException("Expected bytes at tag " + tag);
        return Base64.getDecoder().decode(f.text());
    }
    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }
    public static byte[] head(int tag, int type) {
        if (tag < 0 || tag > 255 || type < 0 || type > 13) throw new IllegalArgumentException("Invalid JCE header");
        return tag < 15 ? new byte[]{(byte) (tag << 4 | type)} : new byte[]{(byte) (240 | type), (byte) tag};
    }
    public static byte[] integer(int tag, long value) {
        if (value == 0) return head(tag, 12);
        int kind = value >= -128 && value <= 127 ? 0 : value >= -32768 && value <= 32767 ? 1
                : value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? 2 : 3;
        ByteBuffer out = ByteBuffer.allocate(1 << kind);
        switch (kind) { case 0 -> out.put((byte) value); case 1 -> out.putShort((short) value);
            case 2 -> out.putInt((int) value); default -> out.putLong(value); }
        return concat(head(tag, kind), out.array());
    }
    public static byte[] string(int tag, String value) {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        return concat(head(tag, data.length < 256 ? 6 : 7), data.length < 256
                ? new byte[]{(byte) data.length} : ByteBuffer.allocate(4).putInt(data.length).array(), data);
    }
    public static byte[] blob(int tag, byte[] value) {
        return concat(head(tag, 13), head(0, 0), integer(0, value.length), value);
    }

    private static final class Reader {
        final ByteBuffer input;
        int nodes;
        Reader(byte[] bytes) { input = ByteBuffer.wrap(bytes); }
        byte[] take(int size) {
            if (size < 0 || size > input.remaining()) throw new IllegalArgumentException("Truncated JCE");
            byte[] result = new byte[size]; input.get(result); return result;
        }
        int count(int depth, int minimum) {
            Field f = field(depth, false);
            if (f.tag != 0 || (f.type > 2 && f.type != 12)) throw new IllegalArgumentException("Invalid count");
            long count = f.number();
            if (count < 0 || count > 100_000 || count > input.remaining() / minimum)
                throw new IllegalArgumentException("JCE container limit");
            return (int) count;
        }
        List<Field> fields(int depth, boolean nested) {
            List<Field> result = new ArrayList<>();
            while (input.hasRemaining()) {
                Field f = field(depth, nested);
                if (f.type == 11) return List.copyOf(result);
                result.add(f);
            }
            if (nested) throw new IllegalArgumentException("Unterminated JCE struct");
            return List.copyOf(result);
        }
        Field field(int depth, boolean allowEnd) {
            if (depth > 32 || ++nodes > 250_000) throw new IllegalArgumentException("JCE complexity limit");
            int header = Byte.toUnsignedInt(take(1)[0]);
            int tag = header >>> 4, type = header & 15;
            if (tag == 15) tag = Byte.toUnsignedInt(take(1)[0]);
            Object value; String raw = null;
            switch (type) {
                case 0 -> value = (int) take(1)[0];
                case 1 -> value = (int) ByteBuffer.wrap(take(2)).getShort();
                case 2 -> value = ByteBuffer.wrap(take(4)).getInt();
                case 3 -> value = Long.toString(ByteBuffer.wrap(take(8)).getLong());
                case 4 -> { float f = ByteBuffer.wrap(take(4)).getFloat(); value = Float.isFinite(f) ? f : Float.toString(f); }
                case 5 -> { double d = ByteBuffer.wrap(take(8)).getDouble(); value = Double.isFinite(d) ? d : Double.toString(d); }
                case 6, 7 -> {
                    int size = type == 6 ? Byte.toUnsignedInt(take(1)[0]) : ByteBuffer.wrap(take(4)).getInt();
                    byte[] data = take(size); value = new String(data, StandardCharsets.UTF_8);
                    raw = Base64.getEncoder().encodeToString(data);
                }
                case 8 -> {
                    int size = count(depth + 1, 2); List<Entry> entries = new ArrayList<>();
                    for (int i = 0; i < size; i++) entries.add(new Entry(field(depth + 1, false), field(depth + 1, false)));
                    value = List.copyOf(entries);
                }
                case 9 -> {
                    int size = count(depth + 1, 1); List<Field> items = new ArrayList<>();
                    for (int i = 0; i < size; i++) items.add(field(depth + 1, false));
                    value = List.copyOf(items);
                }
                case 10 -> value = fields(depth + 1, true);
                case 11 -> { if (!allowEnd) throw new IllegalArgumentException("Unexpected struct end"); value = null; }
                case 12 -> value = 0;
                case 13 -> {
                    if (take(1)[0] != 0) throw new IllegalArgumentException("Invalid byte subtype");
                    Field size = field(depth + 1, false);
                    if (size.tag != 0 || (size.type > 2 && size.type != 12)) throw new IllegalArgumentException("Invalid byte length");
                    value = Base64.getEncoder().encodeToString(take(Math.toIntExact(size.number())));
                }
                default -> throw new IllegalArgumentException("Unsupported JCE type " + type);
            }
            return new Field(tag, type, value, raw);
        }
    }
}
