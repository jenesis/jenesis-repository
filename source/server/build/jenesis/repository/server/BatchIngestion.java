package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.store.ArtifactStore;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Batch archive ingestion: a single publish request carrying an archive and the {@value #EXPLODE_HEADER} header is
 * walked sequentially with {@link ZipInputStream} (java.base, no dependency) and each entry is dispatched as its own
 * synthesized publish - method {@code PUT}, path = the request's base path joined with the entry path, body = the
 * entry's stream - through the shared {@link ScreenedDispatch} ingress edge (or any {@link EntryPublisher} a caller
 * wraps), so it is screened at the edge exactly as a single deploy is: the body streams into the content-addressed
 * store, the discovered publication-screen chain runs <em>per entry</em>, and an accepted entry is restreamed into its
 * format's layout while a held or rejected one lays nothing out - a bad entry never taints its siblings. The contract is "each entry is a publish
 * request", so a raw-body-{@code PUT} format works natively and a protocol format works by carrying its protocol body
 * at its protocol path - no format learns anything about batching.
 *
 * <p>Nothing is materialized: neither the archive nor an entry is ever read whole into memory - the entry stream is
 * handed to the format, which streams it hash-on-write. The archive is opt-in (a deployment gate, {@code batch-upload},
 * off by default) and bounded three ways, each read live: in entries ({@code batch-upload-max-entries}), in the bytes
 * its entries inflate to in all ({@code batch-upload-max-bytes}), and in how far that exceeds the compressed bytes
 * read ({@code batch-upload-max-ratio}). Streaming bounds memory, not the store: a single entry compressed a thousand
 * to one writes its inflated size, so the byte bounds are what stop a zip bomb from filling the store, and an entry
 * that crosses one is refused whole - nothing of it is kept - while the entries before it stand. Nested archives are
 * not re-exploded (an entry is just published, never re-walked), and entry paths are
 * traversal-guarded before any store touch. The response is a per-entry manifest ({@code path -> stored | quarantined
 * | rejected | unclaimed}) so a client sees exactly what each member became.
 */
public final class BatchIngestion {

    /** The request header naming the archive encoding to explode; only {@code zip} is understood. Bare, like every
     *  header of this product's: the {@code X-} prefix was deprecated (RFC 6648) because a header that graduates
     *  from experiment to protocol keeps its name forever, and it was cut over rather than accepted beside the
     *  new name. */
    public static final String EXPLODE_HEADER = "Jenesis-Explode";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** What one exploded entry became once its synthesized publish ran the format and its screen chain. */
    public enum Outcome {
        /** The format accepted and linked the entry (the empty-chain default, or an {@code ACCEPT} verdict). */
        STORED,
        /** A screen quarantined the entry: stored for review, not served. */
        QUARANTINED,
        /** A screen rejected the entry: nothing linked, the orphan blob left for garbage collection. */
        REJECTED,
        /** No installed format claimed the entry's path - it is not part of any layout on this deployment. */
        UNCLAIMED
    }

    /** Publishes one exploded entry's stream at a synthesized path and reports what it became. */
    @FunctionalInterface
    public interface EntryPublisher {
        Outcome publish(String path, InputStream body) throws IOException;
    }

    /** Inflated bytes the ratio bound allows before it applies, so an ordinary small archive of well-compressing
     *  text is never refused for compressing well: the ratio is a question about a large archive. */
    static final long RATIO_FLOOR = 1024L * 1024L;

    private final BooleanSupplier enabled;
    private final IntSupplier maxEntries;
    private final LongSupplier maxBytes;
    private final IntSupplier maxRatio;

    /** @param enabled whether batch ingestion is switched on for this deployment, {@code maxEntries} the most members
     *  one archive may explode into, {@code maxBytes} the most bytes its entries may inflate to in all and
     *  {@code maxRatio} how many times the compressed bytes read that may be - every one read live. */
    public BatchIngestion(BooleanSupplier enabled, IntSupplier maxEntries, LongSupplier maxBytes, IntSupplier maxRatio) {
        this.enabled = enabled;
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.maxRatio = maxRatio;
    }

    /**
     * Whether this request is a batch explode this deployment will handle: the feature is enabled, the request is a
     * {@code PUT} or {@code POST}, and it carries the {@value #EXPLODE_HEADER} header. A dispatcher checks this before
     * its normal single-artifact dispatch; when it is false the header is inert and the request is a plain upload (so
     * an archive is stored verbatim as one artifact when the feature is off).
     */
    public boolean claims(FormatExchange exchange) {
        if (!enabled.getAsBoolean()) {
            return false;
        }
        String header = exchange.requestHeader(EXPLODE_HEADER);
        String method = exchange.method();
        return header != null && !header.isBlank() && ("PUT".equals(method) || "POST".equals(method));
    }

    /**
     * Explode by screening each entry through the shared {@link ScreenedDispatch} ingress edge - the same
     * screen &rarr; (ACCEPT) restream-into-layout &rarr; {@code published} choreography a single deploy runs, with the
     * same {@link EdgeHooks} the deploy edge holds - so a batch upload is screened exactly like a series of individual
     * deploys: the discovered {@link build.jenesis.repository.store.PublishInterceptor} chain (a compliance gate, a
     * quarantine audit, an inventory recorder) reaches a verdict per entry, and a held ({@code 202}) or rejected
     * ({@code 422}) entry lays nothing out while its siblings are untouched. An unscreened format (OCI) entry bypasses
     * the screen and dispatches as before. A write never proxies (see {@link PullThroughCache}), so an entry whose
     * format has an upstream configured still publishes locally.
     */
    public void explode(FormatExchange outer, ArtifactStore store, ScreenedDispatch screened) throws IOException {
        explode(outer, (path, body) -> dispatch(screened, path, body, store));
    }

    /**
     * Explode the archive carried by {@code outer}, publishing each safe entry through {@code publisher} and writing a
     * per-entry JSON manifest as the response. The walk is sequential and streaming; it stops at the configured entry
     * cap (recording {@code "capped": true}) and refuses a path-traversing entry name before it reaches the store.
     */
    public void explode(FormatExchange outer, EntryPublisher publisher) throws IOException {
        String type = outer.requestHeader(EXPLODE_HEADER);
        if (type == null || !type.trim().equalsIgnoreCase("zip")) {
            outer.respond(415, ("unsupported explode archive encoding: " + type).getBytes(StandardCharsets.UTF_8));
            return;
        }
        String base = outer.path();
        int limit = Math.max(1, maxEntries.getAsInt());
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("explode", "zip");
        ArrayNode entries = manifest.putArray("entries");
        boolean capped = false;
        boolean malformed = false;
        String tooLarge = null;
        int processed = 0;
        Inflation inflation = new Inflation(outer.requestStream(), Math.max(1, maxBytes.getAsLong()),
                Math.max(1, maxRatio.getAsInt()));
        try (ZipInputStream zip = new ZipInputStream(inflation.compressed)) {
            while (true) {
                ZipEntry entry;
                try {
                    entry = zip.getNextEntry();
                } catch (ZipException _) {
                    malformed = true;
                    break;
                }
                if (entry == null) {
                    break;
                }
                if (entry.isDirectory()) {
                    continue; // directory markers carry no bytes to publish
                }
                if (processed >= limit) {
                    capped = true; // bounded: stop at the cap without reading the rest of the archive
                    break;
                }
                processed++;
                String name = entry.getName();
                String safe = safeEntryPath(name);
                ObjectNode record = entries.addObject();
                if (safe == null) {
                    record.put("path", name);
                    record.put("status", "rejected");
                    record.put("reason", "path-traversal");
                    continue;
                }
                String path = join(base, safe);
                record.put("path", path);
                try {
                    record.put("status", label(publisher.publish(path, inflation.entry(zip))));
                } catch (IOException | RuntimeException failure) {
                    if (inflation.exceeded == null) {
                        throw failure;
                    }
                    // Refused whole: the publish failed as its body crossed the bound, so nothing of it was stored,
                    // and the walk stops here rather than inflating the rest of the archive.
                    record.put("status", "rejected");
                    record.put("reason", inflation.exceeded);
                    tooLarge = inflation.exceeded;
                    break;
                }
            }
        }
        manifest.put("capped", capped || tooLarge != null);
        if (malformed) {
            manifest.put("error", "malformed-archive");
        } else if (tooLarge != null) {
            manifest.put("error", tooLarge);
        }
        outer.setResponseHeader("Content-Type", "application/json");
        outer.respond(malformed ? 400 : tooLarge != null ? 413 : 200, JSON.writeValueAsBytes(manifest));
    }

    /**
     * What an archive has cost so far: the compressed bytes read from the request and the bytes its entries have
     * inflated to, with the bounds they are held to. An entry's stream fails the read that crosses a bound, so the
     * publish reading it stores nothing, and records which bound it was for the manifest.
     */
    private static final class Inflation {

        private final long maxBytes;
        private final int maxRatio;
        private long compressedBytes;
        private long inflatedBytes;
        private String exceeded;
        private final InputStream compressed;

        private Inflation(InputStream request, long maxBytes, int maxRatio) {
            this.maxBytes = maxBytes;
            this.maxRatio = maxRatio;
            this.compressed = new FilterInputStream(request) {
                @Override
                public int read() throws IOException {
                    int one = super.read();
                    if (one >= 0) {
                        compressedBytes++;
                    }
                    return one;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int read = super.read(buffer, offset, length);
                    if (read > 0) {
                        compressedBytes += read;
                    }
                    return read;
                }
            };
        }

        /** The current entry's body, counted against the bounds and kept open for the walk. */
        private InputStream entry(ZipInputStream zip) {
            return new Unclosable(zip) {
                @Override
                public int read() throws IOException {
                    int one = super.read();
                    if (one >= 0) {
                        inflated(1);
                    }
                    return one;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int read = super.read(buffer, offset, length);
                    if (read > 0) {
                        inflated(read);
                    }
                    return read;
                }
            };
        }

        private void inflated(long bytes) throws IOException {
            inflatedBytes += bytes;
            if (inflatedBytes > maxBytes) {
                exceeded = "archive-bytes";
                throw new IOException("the archive inflates past batch-upload-max-bytes=" + maxBytes);
            }
            if (inflatedBytes > RATIO_FLOOR && inflatedBytes > (long) maxRatio * Math.max(1, compressedBytes)) {
                exceeded = "archive-ratio";
                throw new IOException("the archive inflates past batch-upload-max-ratio=" + maxRatio + " times the "
                        + compressedBytes + " compressed bytes read");
            }
        }
    }

    /** Screen one entry at the shared ingress edge and read its outcome off the status the edge set: an accepted entry
     *  is restreamed into its format's layout ({@code 2xx} &rarr; stored), a held one answers {@code 202} (quarantined)
     *  and a rejected one {@code 422} (rejected), all off the same {@link ScreenedDispatch} choreography a single deploy
     *  runs; a path no format claims is unclaimed. */
    private static Outcome dispatch(ScreenedDispatch screened, String path, InputStream body, ArtifactStore store)
            throws IOException {
        CapturingExchange exchange = new CapturingExchange(path, body);
        if (!screened.dispatch(exchange, store)) {
            return Outcome.UNCLAIMED;
        }
        return switch (exchange.status()) {
            case 200, 201, 204 -> Outcome.STORED;
            case 202 -> Outcome.QUARANTINED;
            case 422 -> Outcome.REJECTED;
            default -> Outcome.UNCLAIMED;
        };
    }

    private static String label(Outcome outcome) {
        return switch (outcome) {
            case STORED -> "stored";
            case QUARANTINED -> "quarantined";
            case REJECTED -> "rejected";
            case UNCLAIMED -> "unclaimed";
        };
    }

    /** The base request path joined with a guarded (relative, no leading slash) entry path, exactly one {@code /}
     *  between them - the synthesized publish path the entry is dispatched to. */
    private static String join(String base, String entry) {
        String prefix = base == null || base.isEmpty() ? "/" : base;
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        return prefix + entry;
    }

    /** The entry name as a safe relative path, or {@code null} when it escapes the base (absolute, a {@code ..}
     *  segment, a NUL): Windows separators are folded to {@code /} first so a {@code ..\\..\\} traversal is caught by
     *  the same segment check. A safe name is relative and stays at or below the base prefix. */
    private static String safeEntryPath(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.indexOf('\0') >= 0) {
            return null;
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                return null;
            }
        }
        return normalized;
    }

    /** Keeps the shared {@link ZipInputStream} open when a format closes the per-entry stream it was handed, so the
     *  next entry can still be read; the walk advances the zip itself. */
    private static class Unclosable extends FilterInputStream {
        private Unclosable(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // the archive walk owns the ZipInputStream; a format closing its entry body must not close the archive
        }
    }

}
