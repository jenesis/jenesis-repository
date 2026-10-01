package build.jenesis.repository.gate;

import module java.base;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The dispatch context a {@code screen()}-quarantined hosted upload needs so a release can replay the format's own
 * publish rather than link the raw upload blob. An upload is stored and screened before the claiming
 * {@code RepositoryFormat} lays it out, and a quarantine skips that dispatch; for an envelope-bodied format (npm, NuGet,
 * PyPI, RubyGems) the held blob is the publish envelope, not the served artifact, and Maven would miss its
 * cross-publish and metadata.
 *
 * <p>The quarantine leg records the format's {@link build.jenesis.repository.format.RepositoryFormat#name() name}, the
 * request method, the stored body hash and the framing headers at {@code holds/dispatch<path>}, beside the
 * {@code /quarantine<path>} pointer. {@code HoldLifecycle#release} replays {@code plugin.handle} from it, the same
 * dispatch seam the accept path drives, and the release or a discard deletes it. Writes are compare-and-set, so a
 * re-recorded upload overwrites and a re-run after a crash converges.
 *
 * <p>The import edge reuses the record with method {@link #IMPORT}: {@code format} is the target-layout ecosystem,
 * {@code hash} the stored blob, and the context carries the walked source path ({@link #IMPORT_SOURCE_PATH}); its
 * release routes to the importer's {@code importArtifact}.
 */
public record QuarantineDispatch(String format, String method, String hash, Map<String, String> headers) {

    /** The {@code method} of a dispatch recorded by the import edge, whose release replays the importer's
     *  {@code importArtifact} from the stored blob. */
    public static final String IMPORT = "IMPORT";

    /** The {@code method} of a dispatch recorded by the OCI manifest's quarantine leg. Its release completes the OCI
     *  layout that leg skipped - the {@code oci/.types/<hex>} media-type sidecar and, for a tag reference, the
     *  {@code oci/<name>/tags/<tag>} pointer - so the image is pullable by tag and digest. {@code hash} is the manifest
     *  hex, the media type rides the {@code Content-Type} key, and name and tag are read off the request path. */
    public static final String OCI = "OCI";

    /** The context key under which an {@link #IMPORT} dispatch carries the source path the migration walk reached,
     *  which the importer's {@code describe} and {@code importArtifact} are keyed on. Not an HTTP header. */
    public static final String IMPORT_SOURCE_PATH = "source-path";

    /** The descriptor's root, {@code holds/dispatch}, with the request path appended as the {@code /quarantine<path>}
     *  pointer appends it; {@link HoldRecords#kinds} excludes it from the kind index. */
    private static final String ROOT = HoldRecords.DISPATCH_ROOT;

    /** The headers a replay reads back: only the framing {@code Content-Type}, which carries a multipart boundary the
     *  stored body lacks. Never a credential header. */
    private static final List<String> REPLAYED_HEADERS = List.of("Content-Type");

    public QuarantineDispatch {
        headers = Map.copyOf(headers);
    }

    /** The replayed headers the upload carried, read from a header lookup. */
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
     * Records the dispatch context for a quarantined upload at {@code path}, compare-and-set, so a re-screen overwrites
     * and a concurrent writer retries.
     */
    public static void record(ArtifactStore store, String path, String format, String method, String hash,
                              Map<String, String> headers) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append("format=").append(format).append('\n');
        body.append("method=").append(method).append('\n');
        body.append("hash=").append(hash).append('\n');
        for (Map.Entry<String, String> header : headers.entrySet()) {
            // Only the first '=' splits on read, so a boundary containing '=' round-trips.
            body.append("header:").append(header.getKey()).append('=').append(header.getValue()).append('\n');
        }
        byte[] value = body.toString().getBytes(StandardCharsets.UTF_8);
        Retries.update(store, ROOT + path, _ -> value);
    }

    /** The dispatch context recorded for {@code path}, or empty, as for a retroactively held version. */
    public static Optional<QuarantineDispatch> read(ArtifactStore store, String path) throws IOException {
        return store.readVersioned(ROOT + path).map(versioned -> parse(versioned.content()));
    }

    /** Drops the descriptor once a release replayed it or a discard threw the upload away. Idempotent. */
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
