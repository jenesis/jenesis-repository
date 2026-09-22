package build.jenesis.repository.format.gems;

import module java.base;

/**
 * A minimal Ruby Marshal 4.8 writer - just enough of the format to emit a RubyGems quick spec from Java rather than
 * shelling out to Ruby: fixnums, UTF-8 strings (with the encoding ivar), symbols (kept in the symbol table Marshal
 * requires), arrays, an empty hash, and the three object envelopes the gem objects use - user-marshal ({@code U}, for
 * {@code Gem::Version} / {@code Gem::Requirement}), plain object ({@code o}, for {@code Gem::Dependency}) and
 * user-defined ({@code u}, for {@code Gem::Specification}). It deliberately omits Marshal's object-link table (a
 * writer may always emit objects fresh), which a small, acyclic gem spec never needs; the loader accepts the result.
 */
public final class RubyMarshal {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final Map<String, Integer> symbols = new HashMap<>();

    /** The {@code 04 08} Marshal 4.8 stream header. */
    public void header() {
        out.write(4);
        out.write(8);
    }

    public void nil() {
        out.write('0');
    }

    public void bool(boolean value) {
        out.write(value ? 'T' : 'F');
    }

    public void integer(long value) {
        out.write('i');
        fixnum(value);
    }

    /** A UTF-8 string, wrapped with the {@code E} encoding ivar the way Ruby emits source strings. */
    public void string(String value) {
        out.write('I');
        out.write('"');
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        fixnum(bytes.length);
        out.writeBytes(bytes);
        fixnum(1);
        symbol("E");
        out.write('T');
    }

    /** A symbol, defined once and referenced by its table index thereafter, as Marshal requires. */
    public void symbol(String name) {
        Integer index = symbols.get(name);
        if (index != null) {
            out.write(';');
            fixnum(index);
            return;
        }
        symbols.put(name, symbols.size());
        out.write(':');
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        fixnum(bytes.length);
        out.writeBytes(bytes);
    }

    /** Open an array of {@code size} elements; the caller writes the elements next. */
    public void array(int size) {
        out.write('[');
        fixnum(size);
    }

    public void emptyHash() {
        out.write('{');
        fixnum(0);
    }

    /** Open a user-marshaled object ({@code marshal_dump} / {@code marshal_load}); the caller writes the dumped value. */
    public void userMarshal(String className) {
        out.write('U');
        symbol(className);
    }

    /** Open a plain object with {@code ivars} instance variables; the caller writes {@code symbol}/value pairs. */
    public void object(String className, int ivars) {
        out.write('o');
        symbol(className);
        fixnum(ivars);
    }

    /** A user-defined object ({@code _dump} / {@code _load}) whose payload is an opaque byte string. */
    public void userDefined(String className, byte[] payload) {
        out.write('u');
        symbol(className);
        fixnum(payload.length);
        out.writeBytes(payload);
    }

    public byte[] bytes() {
        return out.toByteArray();
    }

    /** Marshal's variable-length signed integer encoding: a length byte then that many little-endian value bytes,
     *  with small values (and zero) packed into the length byte itself. */
    private void fixnum(long value) {
        if (value == 0) {
            out.write(0);
        } else if (value > 0 && value < 123) {
            out.write((int) (value + 5));
        } else if (value < 0 && value > -124) {
            out.write((int) (value - 5) & 0xff);
        } else {
            byte[] buffer = new byte[4];
            int length = 0;
            long remaining = value;
            for (int i = 0; i < 4; i++) {
                buffer[i] = (byte) (remaining & 0xff);
                remaining >>= 8;
                length = i + 1;
                if (remaining == 0) {
                    out.write(length);
                    break;
                }
                if (remaining == -1) {
                    out.write((256 - length) & 0xff);
                    break;
                }
            }
            out.write(buffer, 0, length);
        }
    }
}
