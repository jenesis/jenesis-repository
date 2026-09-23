package build.jenesis.repository.compliance.spi.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.QualityInspector;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A bridged inspector's completeness covers the sibling reads too, not only the body length.
 *
 * <p>The bridge answers "did this read run to completion" by asking whether the artifact fits the prefix tier,
 * which is exact for the artifact itself and blind to the other half of what a bridged inspector reads. An SBOM
 * attachment or a co-located attestation is fetched through the lookup under its own bound, and the inspectors fold
 * a truncated one into "declares nothing" and carry on - reasonable about an optional declaration, and false as a
 * report, because the facts that declaration would have carried are unknown rather than absent. The screen behind
 * it then records a whole-body verdict no inspector claimed.
 *
 * <p>So the bridge watches the lookup it hands out. That puts the answer where the truncation happens rather than
 * in twenty-six implementations that each default to "complete" and each have to remember.
 */
class BridgedCompletenessTest {

    /** A bridged inspector: it implements only the {@code byte[]} leg, and reads one sibling through the lookup. */
    private record Bridged(String sibling) implements QualityInspector {

        @Override
        public boolean handles(String path) {
            return true;
        }

        @Override
        public List<ComplianceGate.Subject> inspect(String path, byte[] content, Lookup lookup) {
            return List.of();
        }

        @Override
        public boolean incompleteOnTruncatedSibling() {
            return true;
        }

        @Override
        public List<ComplianceGate.Subject> inspectArtifact(String path, byte[] content, Lookup lookup)
                throws IOException {
            // Exactly what the format inspectors do: read an optional declaration, and treat one that came back
            // past its bound as declaring nothing.
            lookup.fetchBounded(sibling, 1024).filter(bounded -> !bounded.truncated());
            return List.of();
        }
    }

    private static QualityInspector.Content body(int size) {
        byte[] bytes = new byte[size];
        return new QualityInspector.Content() {
            @Override
            public InputStream open() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public long size() {
                return bytes.length;
            }
        };
    }

    private static QualityInspector.Lookup lookup(boolean truncated) {
        return new QualityInspector.Lookup() {
            @Override
            public Optional<byte[]> fetch(String path) {
                return Optional.empty();
            }

            @Override
            public Optional<Bounded> fetchBounded(String path, int limit) {
                return Optional.of(new Bounded(new byte[8], truncated));
            }
        };
    }

    @Test
    void a_whole_body_with_whole_siblings_is_complete() throws IOException {
        assertThat(new Bridged("sbom.json").inspectArtifact("/x/a.jar", body(64), lookup(false)).complete())
                .as("nothing fell short, so the answer stands for the whole artifact")
                .isTrue();
    }

    @Test
    void a_truncated_sibling_read_makes_the_inspection_incomplete() throws IOException {
        assertThat(new Bridged("sbom.json").inspectArtifact("/x/a.jar", body(64), lookup(true)).complete())
                .as("the declaration the inspector folded into 'declares nothing' was unknown, not absent - and a "
                        + "screen must not record that as a whole-body verdict")
                .isFalse();
    }

    @Test
    void the_body_length_test_still_stands_on_its_own() throws IOException {
        int past = QualityInspector.PREFIX_INSPECTION_LIMIT + 1;
        assertThat(new Bridged("sbom.json").inspectArtifact("/x/a.jar", body(past), lookup(false)).complete())
                .as("an artifact past the prefix tier is incomplete however well its siblings read")
                .isFalse();
    }

    /**
     * The completeness answer is about the bytes that were actually read, so it follows the operator's
     * tier rather than the compiled default.
     *
     * <p>The bridge reads {@code prefixInspectionLimit()} bytes and then says whether that covered the
     * artifact. While the second half of that sentence was written against the constant, a deployment that
     * LOWERED the key read less and still reported "complete" for everything under 32 MiB - a whole-body
     * verdict over a read that stopped a long way short of the body, which is the direction that lets a
     * screen record a clean answer nobody gave it.
     */
    @Test
    void the_completeness_answer_follows_a_lowered_tier() throws IOException {
        System.setProperty(QualityInspector.PREFIX_INSPECTION_LIMIT_KEY, "1024");
        try {
            assertThat(new Bridged("sbom.json").inspectArtifact("/x/a.jar", body(4096), lookup(false))
                    .complete())
                    .as("the inspector saw 1 KiB of a 4 KiB artifact, so its answer covers a prefix and not "
                            + "the artifact")
                    .isFalse();
            assertThat(new Bridged("sbom.json").inspectArtifact("/x/a.jar", body(512), lookup(false))
                    .complete())
                    .as("and an artifact inside the lowered tier is still read whole")
                    .isTrue();
        } finally {
            System.clearProperty(QualityInspector.PREFIX_INSPECTION_LIMIT_KEY);
        }
    }

    @Test
    void an_inspector_on_the_plain_bridge_is_unaffected() throws IOException {
        // The default bridge does not watch, deliberately: an inspector with a designed degrade for a truncated
        // sibling reached a conclusion rather than falling short, and reporting that as incomplete would feed a
        // documented degrade to the fail-open machinery.
        QualityInspector none = new QualityInspector() {
            @Override
            public boolean handles(String path) {
                return true;
            }

            @Override
            public List<ComplianceGate.Subject> inspect(String path, byte[] content, Lookup lookup) {
                return List.of();
            }

            @Override
            public List<ComplianceGate.Subject> inspectArtifact(String path, byte[] content, Lookup lookup) {
                return List.of();
            }
        };
        assertThat(none.inspectArtifact("/x/a.jar", body(64), lookup(true)).complete())
                .as("the plain bridge answers on the body length alone, whatever the siblings did")
                .isTrue();
    }
}
