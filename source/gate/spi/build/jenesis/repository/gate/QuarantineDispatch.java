package build.jenesis.repository.gate;

import module java.base;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The stored dispatch context a {@code screen()}-quarantined hosted upload needs so a later release can replay the
 * format's own publish instead of linking the raw upload blob. The deploy choreography is store-then-screen:
 * an upload is streamed content-addressed and screened <em>before</em> the claiming {@code RepositoryFormat} lays it
 * out, and only an ACCEPT hands the stored blob to {@code plugin.handle}. On QUARANTINE the format dispatch is skipped,
 * so for an envelope-bodied format (npm/NuGet/PyPI/RubyGems, whose quarantined blob is the publish <em>packument or
 * multipart</em>, not the served tarball) the hold records only {@code (request-path, raw-body-hash)} - and a release
 * that merely links that blob materialises no version (no tarball stored, not installable) and strands a phantom
 * {@code publish/} pointer. Even Maven skips its module cross-publish and metadata.
 *
 * <p>So the QUARANTINE leg records this minimal descriptor beside the hold, keyed by the same request path the
 * {@code /quarantine<path>} pointer and the review queue key on ({@code holds/dispatch<path>}): the claiming format's
 * {@link build.jenesis.repository.format.RepositoryFormat#name() name}, the request method, the stored body hash, and
 * the request headers the dispatch replays from (a NuGet/PyPI publish reads its {@code Content-Type} for the multipart
 * boundary). On release {@code HoldLifecycle#release} replays {@code plugin.handle} from this context - the same
 * dispatch seam the accept path drives, never a forked second publish path - so the version is actually materialised.
 * The record is written before the release surface can be reached and deleted once the release (or a discard) consumes
 * it, through the store's bounded compare-and-set - the idiom {@link KevHold} and the inventory pointers use - so a
 * re-recorded upload overwrites rather than duplicates and a re-run after a crash converges.
 *
 * <p>The import edge reuses this same record for a quarantined <em>migration</em> asset with {@code method}
 * {@link #IMPORT}: the {@code format} is the target-layout ecosystem (which importer owns the layout), the {@code hash}
 * the stored blob, and the context map carries the one datum an import replay needs beyond a deploy - the source path
 * the walk reached ({@link #IMPORT_SOURCE_PATH}), the path the importer's {@code describe}/{@code importArtifact} are
 * keyed on. An IMPORT dispatch captures no HTTP framing header (an import reproduces its body from the blob and reads
 * none); {@code HoldLifecycle#release} routes it to {@code importArtifact} rather than {@code plugin.handle}.
 */
public record QuarantineDispatch(String format, String method, String hash, Map<String, String> headers) {

    /** The {@code method} value marking a dispatch recorded by the import edge (not a hosted-deploy HTTP verb): its
     *  release replays the matching importer's {@code importArtifact} from the stored blob, not a format {@code handle}. */
    public static final String IMPORT = "IMPORT";

    /** The {@code method} value marking a dispatch recorded at the OCI manifest choke point's {@code QUARANTINE} leg
     *  (again not a hosted-deploy HTTP verb). An OCI push is multi-request and serves by digest from {@code blobs/<hex>}
     *  under the native {@code withheld/<hex>} marker, never through a {@code publish/} pointer, so a held manifest's
     *  release neither replays a format {@code handle} nor an importer's {@code importArtifact}: it completes the
     *  deferred OCI layout the QUARANTINE leg skipped (the {@code oci/types/<hex>} media-type sidecar and, for a tag
     *  reference, the {@code oci/<name>/tags/<tag>} pointer) from the recorded context, so {@code HoldLifecycle#release}
     *  makes the image pullable by tag and digest again. The {@code format} is {@code oci}, the {@code hash} the stored
     *  manifest hex, and the media type rides the context map's {@code Content-Type} key (the same framing header a
     *  hosted publish captures) so the released sidecar reproduces the pushed manifest's type; the image name and tag
     *  are read back off the recorded request path. */
    public static final String OCI = "OCI";

    /** The context-map key under which an {@link #IMPORT} dispatch carries the source path the migration walk reached.
     *  An importer's {@code describe}/{@code importArtifact} are keyed on the source path (Maven's {@code importArtifact}
     *  prepends {@code /maven/} to it), so a replay from the target path alone would mis-lay the asset out; the source
     *  path rides the context map rather than being reconstructable from the target coordinate. Not an HTTP header. */
    public static final String IMPORT_SOURCE_PATH = "source-path";

    /** The store key namespace of the descriptor: {@code holds/dispatch<path>}, the request path (which begins with a
     *  {@code /}) appended exactly as the {@code /quarantine<path>} hold pointer appends it, so the two share a shape.
     *  Built from {@link HoldRecords#DISPATCH_ROOT} because that second segment is the one thing under {@code holds/} that
     *  is NOT a hold kind, and {@link HoldRecords#kinds} - which reads that level as an index of kinds - has to
     *  exclude exactly this one. Sharing the constant is what keeps the exclusion and the squat from drifting apart. */
    private static final String ROOT = HoldRecords.DISPATCH_ROOT;

    /** The request headers a hosted-publish dispatch reads back on replay. A publish body is reproduced from the stored
     *  blob; only the framing header (a multipart {@code Content-Type} carrying the boundary) is not in the body, so it
     *  is the one header captured - minimal, and never an {@code Authorization}/key header that has no dispatch role. */
    private static final List<String> REPLAYED_HEADERS = List.of("Content-Type");

    public QuarantineDispatch {
        headers = Map.copyOf(headers);
    }

    /** The request headers worth capturing for replay, read from a header lookup (a servlet request, a format
     *  exchange), skipping any the upload did not carry - so the stored descriptor stays minimal. */
    public static Map<String, String> capture(Function<String, String> header) {
        Map<String, String> captured = new LinkedHashMap<>();
        for (String name : REPLAYED_HEADERS) {
            String value = header.apply(name);
            if (value != null && !value.isBlank()) {
                captured.put(name, value);
            }
        }
        return captured;
    }

    /**
     * Record the dispatch context for a quarantined upload at {@code path}. A compare-and-set with the bounded retry
     * the sibling markers use, so a re-screen of the same path overwrites rather than duplicating and a concurrent
     * writer is a retry, not a lost descriptor.
     */
    public static void record(ArtifactStore store, String path, String format, String method, String hash,
                              Map<String, String> headers) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append("format=").append(format).append('\n');
        body.append("method=").append(method).append('\n');
        body.append("hash=").append(hash).append('\n');
        for (Map.Entry<String, String> header : headers.entrySet()) {
            // One header per line, value kept whole (only the first '=' splits on read), so a multipart Content-Type
            // with its own '=' in the boundary round-trips unmangled.
            body.append("header:").append(header.getKey()).append('=').append(header.getValue()).append('\n');
        }
        byte[] value = body.toString().getBytes(StandardCharsets.UTF_8);
        Retries.update(store, ROOT + path, _ -> value);
    }

    /** The dispatch context recorded for a quarantined upload at {@code path}, or empty when none was recorded - a
     *  retroactively-held version (no upload envelope to replay), or a hold predating this record. */
    public static Optional<QuarantineDispatch> read(ArtifactStore store, String path) throws IOException {
        return store.readVersioned(ROOT + path).map(versioned -> parse(versioned.content()));
    }

    /** Drop the descriptor once a release has replayed it or a discard has thrown the upload away, so it does not
     *  dangle past the hold it belonged to. Idempotent - a no-op when none is present. */
    public static void discard(ArtifactStore store, String path) throws IOException {
        if (store.readVersioned(ROOT + path).isPresent()) {
            store.delete(ROOT + path);
        }
    }

    private static QuarantineDispatch parse(byte[] content) {
        String format = "";
        String method = "PUT";
        String hash = "";
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : new String(content, StandardCharsets.UTF_8).split("\n")) {
            int split = line.indexOf('=');
            if (split < 0) {
                continue;
            }
            String key = line.substring(0, split);
            String value = line.substring(split + 1);
            switch (key) {
                case "format" -> format = value;
                case "method" -> method = value;
                case "hash" -> hash = value;
                default -> {
                    if (key.startsWith("header:")) {
                        headers.put(key.substring("header:".length()), value);
                    }
                }
            }
        }
        return new QuarantineDispatch(format, method, hash, headers);
    }
}
