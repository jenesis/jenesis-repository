package build.jenesis.repository.cache.storage.test;

import module org.junit.jupiter.api;
import build.jenesis.repository.cache.storage.CacheStorage.Entry;
import build.jenesis.repository.cache.storage.Names;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boundary coverage for the write-path addressability screens {@code Names.isEntry} / {@code isPath} / {@code isFile}
 * in isolation, beside the {@code CacheStorageContract} legs that prove all four backends really apply them.
 *
 * <p>They exist because the four backends did three different things with an unaddressable name: the filesystem
 * confined its resolved path and refused (and, for a {@code <step>/..} that normalises back to the project folder,
 * returned normally having stored <em>nothing</em>), while the three object-store backends composed an opaque key and
 * stored it literally - at a key their own {@code entries()} then skips forever, because all four
 * already screen a non-hex {@code <step>/<inputs>} pair when they enumerate. An object nothing enumerates is never
 * counted toward a size cap, never aged out by the ttl and never reclaimed by the free-space sweep.
 *
 * <p>The screens are therefore deliberately the <em>same</em> predicates the enumeration and the dispatcher already
 * use, applied earlier; these cases pin the edges a backend's own path handling would otherwise decide differently.
 */
class NamesAddressabilityTest {

    @Test
    void an_entry_needs_a_valid_project_and_two_hex_segments() {
        assertThat(Names.isEntry(new Entry("libs", "aa", "01"))).isTrue();
        assertThat(Names.isEntry(new Entry("libs_2", "AbCd", "0123456789"))).isTrue();
    }

    @Test
    void a_traversal_shaped_entry_is_refused_in_every_position() {
        assertThat(Names.isEntry(new Entry("..", "aa", "01"))).as("the project may not escape").isFalse();
        assertThat(Names.isEntry(new Entry("libs", "..", "01"))).as("nor the step segment").isFalse();
        assertThat(Names.isEntry(new Entry("libs", "aa", ".."))).as("nor the inputs segment").isFalse();
        assertThat(Names.isEntry(new Entry("libs/../escape", "aa", "01")))
                .as("a separator inside the project is not a project name").isFalse();
        assertThat(Names.isEntry(new Entry("libs", "aa", "0/1")))
                .as("nor inside a segment that becomes one key component").isFalse();
    }

    @Test
    void a_non_hex_entry_segment_is_refused_because_no_enumeration_would_ever_show_it() {
        assertThat(Names.isEntry(new Entry("libs", "zz", "01"))).isFalse();
        assertThat(Names.isEntry(new Entry("libs", "aa", "zz"))).isFalse();
        assertThat(Names.isEntry(new Entry("libs", "cache.properties", "01")))
                .as("a project's own config document is not an entry coordinate").isFalse();
    }

    @Test
    void a_null_or_empty_part_never_addresses_an_entry() {
        assertThat(Names.isEntry(null)).isFalse();
        assertThat(Names.isEntry(new Entry(null, "aa", "01"))).isFalse();
        assertThat(Names.isEntry(new Entry("libs", null, "01"))).isFalse();
        assertThat(Names.isEntry(new Entry("libs", "aa", null))).isFalse();
        assertThat(Names.isEntry(new Entry("", "aa", "01"))).isFalse();
        assertThat(Names.isEntry(new Entry("libs", "", "01"))).isFalse();
        assertThat(Names.isEntry(new Entry("libs", "aa", ""))).isFalse();
    }

    @Test
    void a_config_path_may_nest_but_may_not_escape() {
        assertThat(Names.isPath(".users/members/Z2l0aHViLzEwMjQwMjU/member.properties"))
                .as("one console member's own object").isTrue();
        assertThat(Names.isPath(".users/2f6b/projects.properties")).as("nesting is legal").isTrue();
        assertThat(Names.isPath("auth/keylogin/keys.properties")).as("as is a deeper branch").isTrue();
        assertThat(Names.isPath("cache.properties")).as("and a single segment").isTrue();

        assertThat(Names.isPath("../escape.properties")).isFalse();
        assertThat(Names.isPath("a/../b.properties")).as("a traversal anywhere in the path").isFalse();
        assertThat(Names.isPath("./x.properties")).as("a current-directory segment is not a name").isFalse();
        assertThat(Names.isPath("/absolute.properties")).as("an absolute path is not relative to the scope").isFalse();
        assertThat(Names.isPath("a//b.properties")).as("an empty segment").isFalse();
        assertThat(Names.isPath("trailing/")).as("a trailing separator addresses a container, not a document")
                .isFalse();
        assertThat(Names.isPath("a\\b.properties")).as("a backslash is a separator on some backends and a literal "
                + "character on others, so it never addresses the same object twice").isFalse();
        assertThat(Names.isPath("")).isFalse();
        assertThat(Names.isPath(null)).isFalse();
        assertThat(Names.isPath("a".repeat(1025))).as("a path past the cap would reach an object store as a "
                + "protocol error rather than a clean refusal").isFalse();
    }

    @Test
    void a_project_config_file_name_is_one_segment() {
        assertThat(Names.isFile("cache.properties")).isTrue();
        assertThat(Names.isFile(".hidden")).as("a dot-prefixed name is a name, not a traversal").isTrue();

        assertThat(Names.isFile("nested/cache.properties")).as("a separator makes it a path, not a file name")
                .isFalse();
        assertThat(Names.isFile("../../escape.properties")).isFalse();
        assertThat(Names.isFile("..")).isFalse();
        assertThat(Names.isFile(".")).isFalse();
        assertThat(Names.isFile("")).isFalse();
        assertThat(Names.isFile(null)).isFalse();
    }

    @Test
    void a_control_character_in_a_file_name_is_refused() {
        // The store SPI's safeSegment refuses these; the cache's file names are checked one layer above it and used
        // to accept what the store below would reject.
        assertThat(Names.isFile("notes\n.txt")).isFalse();
        assertThat(Names.isFile("notes\r.txt")).isFalse();
        assertThat(Names.isFile("notes\u0001.txt")).isFalse();
        assertThat(Names.isFile("notes.txt")).isTrue();
    }

}
