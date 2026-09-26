package build.jenesis.repository.compliance.testkit;

import module java.base;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * A {@link QualityInspector.Lookup} over a fixed set of already-published siblings - the lookup a fixture supplies
 * when its inspector really reads a companion (a jar's sibling POM, a CycloneDX attachment, the artifact an
 * attestation referrer names). A fixture whose inspector reads no companion uses {@link QualityInspector.Lookup#none()}
 * instead, which {@link InspectorFixture#lookup()} already defaults to.
 *
 * <p><b>Why this exists rather than a lambda per fixture.</b> The SPI defaults <em>neither</em> sibling leg:
 * the bounded read used to inherit a default that routed through the whole-document one and trimmed the result in
 * heap, which meant a caller asking for a 32 MiB bounded fact got an exception at 8 MiB on one leg and a truncated
 * answer on the other. With the default gone, every supplier owes both legs - and a test double that got them subtly
 * wrong would let an inspector pass a contract the production screens would fail it on. So the two legs are written
 * once, here, in the shape a real store implements them:
 * <ul>
 *   <li>{@link #fetch(String)} reads one byte past {@link PublishInterceptor.Content#LARGEST_SIBLING} and <b>throws</b>
 *       past it - the whole document or nothing, never a prefix a caller would read as complete;</li>
 *   <li>{@link #fetchBounded(String, int)} reads one byte past the <em>caller's</em> limit and reports the overflow
 *       through {@link QualityInspector.Lookup.Bounded#truncated()}, never raising on size. The boundary is
 *       {@code >}: a sibling of exactly {@code limit} bytes comes back whole, because every byte of it is in hand.</li>
 * </ul>
 * Both legs stream from the recorded body rather than handing the stored array out and trimming it, so the code here
 * is the same shape the screens' own lookups have and cannot drift into the read-whole-then-trim shape that was
 * removed.
 *
 * <p>A fixture whose bounded leg must prove that an over-window sibling is never <em>materialised</em> (rather than
 * merely reported truncated) supplies its own lookup over a generated stream instead: this one holds its bodies in
 * heap by construction, so it can prove the reported outcome but not the heap ceiling.
 */
public final class Siblings implements QualityInspector.Lookup {

    private final Map<String, byte[]> published;

    private Siblings(Map<String, byte[]> published) {
        this.published = Map.copyOf(published);
    }

    /** The lookup over these published request paths and their bodies. */
    public static Siblings of(Map<String, byte[]> published) {
        return new Siblings(published);
    }

    /** The lookup over a single published sibling - the common fixture shape (one companion beside one artifact). */
    public static Siblings of(String path, byte[] body) {
        return new Siblings(Map.of(path, body));
    }

    @Override
    public Optional<byte[]> fetch(String path) throws IOException {
        byte[] body = published.get(path);
        if (body == null) {
            return Optional.empty();
        }
        try (InputStream in = new ByteArrayInputStream(body)) {
            byte[] read = in.readNBytes(PublishInterceptor.Content.LARGEST_SIBLING + 1);
            if (read.length > PublishInterceptor.Content.LARGEST_SIBLING) {
                throw new IOException("Published sibling exceeds the "
                        + PublishInterceptor.Content.LARGEST_SIBLING + "-byte whole-document cap, refusing to buffer "
                        + "it whole: " + path);
            }
            return Optional.of(read);
        }
    }

    @Override
    public Optional<Bounded> fetchBounded(String path, int limit) throws IOException {
        byte[] body = published.get(path);
        if (body == null) {
            return Optional.empty();
        }
        try (InputStream in = new ByteArrayInputStream(body)) {
            // One byte past the caller's limit, so a sibling of EXACTLY limit bytes is reported whole rather than
            // pessimistically flagged truncated. The boundary is `>`, never `>=`.
            byte[] read = in.readNBytes(limit + 1);
            return Optional.of(read.length > limit
                    ? new Bounded(Arrays.copyOf(read, limit), true)
                    : new Bounded(read, false));
        }
    }
}
