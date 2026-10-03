package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.SummarisedDocuments;
import build.jenesis.repository.store.testkit.FaultInjectingStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Uploaded documents and their roll-ups over a real filesystem store: paged newest first, recorded once however often
 * delivered, and reconciled against the documents, which are the authority - so a removal a crash interrupts is
 * finished by the reconcile and never undone by it.
 */
class SummarisedDocumentsTest {

    /** A roll-up of a {@code title|instant} document: the title, and the instant it is ordered by. */
    private record Summary(String title, Instant at) {
    }

    private static final SummarisedDocuments.Summaries<Summary> SUMMARIES = new SummarisedDocuments.Summaries<>() {
        @Override
        public byte[] write(Summary summary) {
            return (summary.title() + "|" + summary.at()).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Summary read(byte[] content) {
            String[] parts = new String(content, StandardCharsets.UTF_8).split("\\|");
            return new Summary(parts[0], Instant.parse(parts[1]));
        }

        @Override
        public Instant orderedAt(Summary summary) {
            return summary.at();
        }

        @Override
        public Optional<Summary> derive(String id, byte[] document) {
            try {
                return Optional.of(read(document));
            } catch (RuntimeException _) {
                return Optional.empty();
            }
        }
    };

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    private static byte[] document(String title, String at) {
        return (title + "|" + at).getBytes(StandardCharsets.UTF_8);
    }

    private static String ingest(SummarisedDocuments<Summary> documents, byte[] bytes) throws IOException {
        String id = documents.put(bytes);
        documents.record(id, SUMMARIES.read(bytes));
        return id;
    }

    private static List<String> titles(SummarisedDocuments<Summary> documents) throws IOException {
        List<String> titles = new ArrayList<>();
        String after = null;
        do {
            SummarisedDocuments.Window<Summary> window = documents.recent(after, 2);
            window.summaries().forEach(summary -> titles.add(summary.title()));
            after = window.next();
        } while (after != null);
        return titles;
    }

    @Test
    void documents_page_newest_first_and_a_redelivery_is_recorded_once() throws IOException {
        SummarisedDocuments<Summary> documents = new SummarisedDocuments<>(store, SUMMARIES);
        byte[] middle = document("middle", "2026-02-01T00:00:00Z");
        ingest(documents, document("oldest", "2026-01-01T00:00:00Z"));
        String id = ingest(documents, middle);
        ingest(documents, document("newest", "2026-03-01T00:00:00Z"));

        assertThat(documents.record(documents.put(middle), SUMMARIES.read(middle)))
                .as("a document delivered again is not new").isFalse();
        assertThat(titles(documents)).containsExactly("newest", "middle", "oldest");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(documents.read(id, out)).isTrue();
        assertThat(out.toByteArray()).isEqualTo(middle);
    }

    @Test
    void a_removal_a_crash_interrupts_is_finished_by_the_reconcile_not_undone() throws IOException {
        String id = ingest(new SummarisedDocuments<>(store, SUMMARIES), document("withdrawn", "2026-01-01T00:00:00Z"));
        FaultInjectingStore crashing = FaultInjectingStore.wrap(store)
                .failNthOn(FaultInjectingStore.Op.DELETE, FaultInjectingStore.anyKey(), 2);

        assertThatThrownBy(() -> new SummarisedDocuments<>(crashing, SUMMARIES).delete(id))
                .isInstanceOf(IOException.class);
        SummarisedDocuments<Summary> documents = new SummarisedDocuments<>(store, SUMMARIES);
        documents.reconcile();

        assertThat(documents.document(id)).as("the document went first, so nothing re-derives it").isEmpty();
        assertThat(documents.summary(id)).as("the roll-up it left is reaped").isEmpty();
        assertThat(titles(documents)).isEmpty();
    }

    @Test
    void the_reconcile_derives_what_is_missing_and_reaps_what_is_orphaned() throws IOException {
        SummarisedDocuments<Summary> documents = new SummarisedDocuments<>(store, SUMMARIES);
        String unindexed = documents.put(document("unindexed", "2026-01-01T00:00:00Z"));
        String corrupt = ingest(documents, document("corrupt", "2026-02-01T00:00:00Z"));
        store.write("index/" + corrupt + ".json", new ByteArrayInputStream("not a roll-up".getBytes(StandardCharsets.UTF_8)));
        String orphan = ingest(documents, document("orphan", "2026-03-01T00:00:00Z"));
        store.delete("blobs/" + orphan);
        String settled = ingest(documents, document("settled", "2026-04-01T00:00:00Z"));

        assertThat(documents.reconcile()).as("one derived, one re-derived, one reaped").isEqualTo(3);

        assertThat(titles(documents)).containsExactly("settled", "corrupt", "unindexed");
        assertThat(documents.summary(orphan)).isEmpty();
        assertThat(documents.summary(settled)).isPresent();
        assertThat(documents.summary(unindexed)).isPresent();
    }

    @Test
    void a_settled_store_is_reconciled_without_a_write() throws IOException {
        SummarisedDocuments<Summary> documents = new SummarisedDocuments<>(store, SUMMARIES);
        for (int index = 0; index < 5; index++) {
            ingest(documents, document("document-" + index, "2026-01-0" + (index + 1) + "T00:00:00Z"));
        }
        FaultInjectingStore counting = FaultInjectingStore.wrap(store);

        assertThat(new SummarisedDocuments<>(counting, SUMMARIES).reconcile()).isZero();

        assertThat(counting.calls(FaultInjectingStore.Op.WRITE_VERSIONED)).isZero();
        assertThat(counting.calls(FaultInjectingStore.Op.DELETE)).isZero();
    }
}
