package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.Documents;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Documents#bytes}: a properties document renders to the same bytes whenever it holds the same content.
 *
 * <p>Three places in this product wrote that sentence and none of them delivered it, because
 * {@link Properties#store(OutputStream, String)} emits a {@code #<date>} line whether or not a comment was asked
 * for. Byte-equality is load-bearing in three separate ways - a store dedupes equal objects, a reader compares
 * documents rather than tokens, and a compare-and-set refused by an object store's own retry is recognised as a
 * write that landed by re-applying the mutation and finding a fixed point. A document that differs from itself
 * every second satisfies none of them.
 *
 * <p>The suite does not sleep to prove it. The timestamp was the only part of the rendering that varied with
 * anything other than the content, so a rendering with no comment line at all cannot vary with time - and that is
 * checkable instantly, where a test that waits a second is both slower and weaker.
 */
class DocumentsTest {

    @Test
    void a_rendered_document_carries_no_comment_line() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("digest", "sha256:abc");
        properties.setProperty("bytes", "17");

        assertThat(rendered(properties).lines())
                .as("the #<date> line store() writes unasked is what made a document differ from itself")
                .noneMatch(line -> line.startsWith("#"));
    }

    @Test
    void a_value_that_begins_with_a_hash_is_escaped_and_so_cannot_read_as_a_comment() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("note", "#not-a-comment");

        assertThat(rendered(properties)).isEqualTo("note=\\#not-a-comment\n");
    }

    @Test
    void two_documents_of_equal_content_render_to_equal_bytes() throws IOException {
        Properties first = new Properties();
        first.setProperty("b", "2");
        first.setProperty("a", "1");
        Properties second = new Properties();
        second.setProperty("a", "1");
        second.setProperty("b", "2");

        assertThat(Documents.bytes(second)).isEqualTo(Documents.bytes(first));
    }

    /**
     * The key order is independent of the order the keys were set in, and this is asserted rather than assumed.
     * It is a property of the JDK's own map and not of anything here, which is exactly why it is worth a test: it
     * is the reason this rendering need not sort, and nothing in this repository would notice it changing.
     */
    @Test
    void the_rendering_does_not_depend_on_the_order_the_keys_were_set() throws IOException {
        List<String> keys = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            keys.add("upstream.host" + index + ".token");
        }
        Properties forward = new Properties();
        keys.forEach(key -> forward.setProperty(key, "v-" + key));
        Properties backward = new Properties();
        keys.reversed().forEach(key -> backward.setProperty(key, "v-" + key));
        Properties shuffled = new Properties();
        List<String> mixed = new ArrayList<>(keys);
        Collections.shuffle(mixed, new Random(7));
        mixed.forEach(key -> shuffled.setProperty(key, "v-" + key));

        assertThat(Documents.bytes(backward)).isEqualTo(Documents.bytes(forward));
        assertThat(Documents.bytes(shuffled)).isEqualTo(Documents.bytes(forward));
    }

    /**
     * The escaping is the one {@code store(OutputStream, ..)} does and not the one {@code store(Writer, ..)} does,
     * which is why the comment is filtered out of the rendered bytes rather than by handing {@code store} a
     * filtering writer. One of these documents holds artifact paths, and a path is not required to be ASCII.
     */
    @Test
    void a_value_outside_latin_1_is_escaped_and_read_back_unchanged() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("path", "libs/caf\u00e9/\u4e2d\u6587-1.0.jar");
        byte[] document = Documents.bytes(properties);

        assertThat(new String(document, StandardCharsets.ISO_8859_1))
                .as("escaped rather than emitted as bytes, so the reader's charset cannot corrupt it")
                .isEqualTo("path=libs/caf\\u00E9/\\u4E2D\\u6587-1.0.jar\n");
        Properties read = new Properties();
        read.load(new ByteArrayInputStream(document));
        assertThat(read.getProperty("path")).isEqualTo("libs/caf\u00e9/\u4e2d\u6587-1.0.jar");
    }

    @Test
    void every_line_ends_the_same_way_on_every_platform() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("a", "1");
        properties.setProperty("b", "2");

        String document = rendered(properties);
        assertThat(document).as("store() ends its lines with the local separator; a fleet is not one platform")
                .doesNotContain("\r")
                .isEqualTo("a=1\nb=2\n");
    }

    @Test
    void an_empty_document_renders_to_no_bytes_at_all() throws IOException {
        assertThat(Documents.bytes(new Properties())).isEmpty();
    }

    private static String rendered(Properties properties) throws IOException {
        return new String(Documents.bytes(properties), StandardCharsets.ISO_8859_1);
    }
}
