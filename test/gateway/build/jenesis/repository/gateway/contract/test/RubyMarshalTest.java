package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.gems.RubyMarshal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Ruby Marshal 4.8 writer behind the RubyGems quick spec, pinned byte-for-byte to what real Ruby's
 * {@code Marshal.dump} emits (references captured from {@code ruby -e 'Marshal.dump(...)'}). This verifies the
 * encoding {@code gem install} depends on offline - not only through the network-gated real-{@code gem} test, which
 * self-skips when the Ruby toolchain is absent.
 */
class RubyMarshalTest {

    @Test
    void primitives_match_ruby_marshal() {
        assertThat(dump(m -> m.string("hello"))).isEqualTo("040849220a68656c6c6f063a064554");
        assertThat(dump(m -> m.integer(42))).isEqualTo("0408692f");
        assertThat(dump(m -> m.integer(0))).isEqualTo("04086900");
        assertThat(dump(m -> m.integer(200))).as("a value needing an explicit length byte").isEqualTo("04086901c8");
        assertThat(dump(m -> m.integer(-1))).isEqualTo("040869fa");
        assertThat(dump(m -> m.integer(1000))).as("a two-byte little-endian value").isEqualTo("04086902e803");
        assertThat(dump(m -> m.bool(true))).isEqualTo("040854");
        assertThat(dump(RubyMarshal::nil)).isEqualTo("040830");
        assertThat(dump(m -> m.symbol("E"))).isEqualTo("04083a0645");
    }

    @Test
    void a_gem_version_envelope_matches_ruby_marshal() {
        // Marshal.dump(Gem::Version.new("1.2.3")) - the user-marshal envelope a quick spec is built from.
        assertThat(dump(m -> {
            m.userMarshal("Gem::Version");
            m.array(1);
            m.string("1.2.3");
        })).isEqualTo("0408553a1147656d3a3a56657273696f6e5b0649220a312e322e33063a064554");
    }

    private static String dump(Consumer<RubyMarshal> body) {
        RubyMarshal marshal = new RubyMarshal();
        marshal.header();
        body.accept(marshal);
        return HexFormat.of().formatHex(marshal.bytes());
    }
}
