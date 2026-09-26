package build.jenesis.repository.gateway;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.MalformedArtifactException;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The hardened proxy fetch firewall for a repository declared {@code fallback <url> harden}: the untrusted-upstream
 * strictening of {@link ProxyScreen}, the keystone of the hardening proxy. Where the ordinary proxy screen reads
 * a bounded prefix and then streams the un-screened remainder straight to the client on {@code ALLOW}
 * ({@code prefix-screen-stream-through}, so the verdict is reached before the body has fully transited), the hardened
 * screen <b>spools the fetched body to completion, screens it, and only then releases a verified stream</b>
 * ({@code spool -> screen -> release-verified-stream}): the client's download is re-opened from the budgeted
 * {@link SpoolStore} spool - never the live upstream socket - and only after a verdict has been reached over the
 * materialised body, so no un-screened byte ever reaches the client (a materialise -> screen ->
 * decide -> serve lifecycle).
 *
 * <p><b>Bounded heap (§1).</b> The body streams into the {@link SpoolStore}'s bounded, owner-only temp file,
 * digested as it lands; it is never pulled whole into a {@code byte[]}. Only the bounded inspection prefix is read into
 * heap for the inspectors, exactly the cap the publish and ordinary proxy screens already apply. Disk - governed by the
 * spool budget - is the resource spent; a spool that exhausts its budget raises {@link SpoolStore.BudgetExhausted},
 * which the router answers as a {@code 503}.
 *
 * <p><b>Structural refusal set (§9).</b> When the hardened leg cannot screen a body it does not fall back to
 * serving it unscreened, nor swallow the failure into an anonymous error: the outcome is a typed, named {@link Refusal}
 * - the upstream fetch truncated ({@link Refusal#FETCH_INTERRUPTED}), the body grown past the per-artifact size
 * ceiling ({@link Refusal#OVERSIZE}), a stalled or over-long fetch ({@link Refusal#FETCH_TIMEOUT}), an inspector that
 * claimed the body but could not parse it ({@link Refusal#UNPARSEABLE}), or one that failed while screening it
 * ({@link Refusal#INSPECTOR_ERROR}) - which
 * is recorded in the durable {@code QuarantineLog} with its reason and logged at {@code WARNING}, while the wire stays
 * non-disclosive (an empty result the pull-through serves as a {@code 404}, never leaking why the upstream content was
 * withheld).
 *
 * <p><b>Full-body screen tier.</b> The verdict is reached over the <em>whole</em> spooled body, not a bounded
 * 32 MiB prefix: every claiming inspector screens the complete artifact through a re-openable {@link
 * QualityInspector.Content} spool handle ({@link ProxyScreen#inspectFullBody}) - the embedded-secret content scanner
 * streaming past the prefix window so a credential beyond 32 MiB is caught, a format inspector default-bridged to the
 * same front prefix it read before. Decompression/scan stays bounded by the shared
 * {@link QualityInspector#FULL_BODY_INSPECTION_LIMIT full-body tier} (and each inspector's own entry/finding/nesting
 * caps), so full-body is not unbounded (a decompression bomb cannot exhaust the node, §1). Every claiming
 * inspector <em>reports</em> whether its own read reached the end of the body or one of those bounds
 * ({@link QualityInspector.Inspection#complete()}), and the screen is complete only if all of them were: an artifact
 * this leg could not screen whole is decided, recorded and counted as such rather than passing as a clean whole-body
 * screen because some other inspector happened to produce a subject.
 *
 * <p><b>Digest-pinned verdict record + reuse-dedup (§5/§7).</b> When the leg screens a body it records a
 * {@link VerdictSection#TAG verdict} section into the coordinate's consolidated metadata document, <em>pinned to the
 * artifact's content digest</em> (the SHA-256 the spool computed as the body streamed in), capturing the verdict, when
 * it was {@code screenedAt}, the screening {@code profile}, the {@code source} the bytes came from and the
 * {@code validators} that ran. On a subsequent fetch of the <em>same</em> digest the recorded {@code ALLOW} verdict is
 * reused - the spool is still paid, but the inspection/gate cost is not (§7 the reader pays for nothing a prior screen
 * already did). Reuse is digest-exact: a different-bytes artifact at the same coordinate never reuses a verdict reached
 * over changed content, it re-screens. A hardened artifact about to be served whose verdict is <em>absent</em> (never
 * recorded, or the document lost it) is never served unscreened: {@link #serveVerified} fails closed and, when the
 * bytes are local, re-screens them from the local store and re-records the verdict (idempotent self-healing, §5), then
 * serves per the fresh verdict; when the bytes are not local it is a MISS that falls to the normal fetch+screen path.
 * With no metadata persistence module installed the {@link MetadataStore} is absent and the leg degrades to screening
 * every fetch (always safe, never a reuse).
 *
 * <p><b>Transient full-enforcement screen ({@code harden nocache}).</b> A hardened leg constructed with
 * verdict-reuse disabled fully screens <em>every</em> fetch and durably caches nothing: it still records the
 * digest-pinned verdict for audit and still enforces drift, but never reuse-serves a recorded {@code ALLOW}, so a
 * re-fetch of the same digest re-screens the whole body from the spool rather than reusing the prior decision. This is
 * the "don't grow my store" deployment - the router pairs it with a throwaway post-verdict cache, so no cached blob or
 * publish pointer lands in the durable store. Plain {@code harden} keeps verdict reuse (its durably cached copy is the
 * trusted backing the reuse dedups against).
 *
 * <p>The screening <em>decision</em> - inspectors, gate policy, immaturity hold - is reused wholesale from
 * {@link ProxyScreen}; no compliance mechanism is copied. The hardened leg owns only the spool, the
 * decide-then-serve disposition, the full-body inspection driver, the refusal typing and the digest-pinned verdict
 * record. Its buffered {@link ProxyFormat.Fetcher#fetch} path (small mutable indexes, screened whole today) is
 * delegated to the ordinary complete-body screen unchanged.
 */
public final class HardenedScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HardenedScreen.class);

    /** The screening tier this leg records in the verdict's {@code profile}: the untrusted-upstream full-body screen. */
    static final String PROFILE = "hardened/full-body";

    /** The reason-line prefix every hardened structural refusal (and the drift alarm) records into the durable
     *  {@link build.jenesis.repository.gate.QuarantineLog}. It names the leg so a review surface can tell a hardened
     *  refusal apart from an ordinary publish/proxy gate hold without re-deriving the reason text - the read side
     *  ({@link HardeningVerdicts}) keys off exactly this constant rather than a copied literal. */
    public static final String REFUSAL_REASON_PREFIX = "Hardened proxy screen refused (";

    /** The {@code source} recorded when a verdict is (re-)reached over bytes already in the local store rather than a
     *  freshly-fetched upstream body - the self-healing repair path ({@link #serveVerified}). */
    static final String LOCAL_SOURCE = "local-store";

    private static final String BLOB_PREFIX = "blobs/";

    /** A structural cannot-screen outcome on the hardened leg: named, recorded, never served, never swallowed. */
    public enum Refusal {

        FETCH_INTERRUPTED("the upstream fetch was truncated or the connection was lost before the whole body arrived"),
        OVERSIZE("the upstream body exceeded the per-artifact full-screen size ceiling before it could be screened"),
        FETCH_TIMEOUT("the upstream fetch stalled or fell below the minimum throughput floor before the whole body "
                + "arrived (a slow-loris upstream)"),
        DRIFT("the upstream served different bytes under an immutable coordinate than the digest previously screened "
                + "and pinned (upstream tampering/drift)"),
        UNPARSEABLE("an inspector claimed the artifact but could not parse it"),
        INSPECTOR_ERROR("an inspector failed while screening the artifact");

        private final String detail;

        Refusal(String detail) {
            this.detail = detail;
        }

        String detail() {
            return detail;
        }
    }

    /**
     * The untrusted-upstream fetch bounds a hardened leg enforces while spooling a body, since a {@code harden} proxy
     * pulls from an <em>untrusted</em> upstream. Three defensive ceilings, all deploy-time resource
     * dials sized to the node (like the {@link SpoolStore.Budget spool budget}), read from
     * {@code spool.max-artifact-bytes} / {@code spool.fetch-timeout-millis} / {@code spool.fetch-min-throughput-bytes}
     * (see {@link #fromConfig}):
     * <ul>
     *   <li><b>Per-artifact size ceiling</b> ({@link #maxArtifactBytes()}). A hardened fetch spools the WHOLE body
     *       before serving, so a malicious upstream could stream an unbounded body to exhaust disk. A body that grows
     *       past the ceiling while spooling is <b>refused</b> ({@link Refusal#OVERSIZE}), never truncated-and-served.
     *       This is a per-artifact <em>policy</em> limit (⇒ 404-shaped refuse), distinct from the {@link SpoolStore}'s
     *       global {@link SpoolStore.Budget budget} (a shared resource cap ⇒ 503). The two are enforced by different
     *       code over the same bytes, so the policy limit is only <em>reachable</em> while it sits at or below the
     *       budget: a body big enough to trip a higher ceiling exhausts the shared budget first and is answered 503,
     *       and the per-artifact refusal an operator configured can never fire. {@link #fromConfig} therefore reads the
     *       ceiling <em>against</em> the budget it will be spooled under and refuses the pair when it is unreachable
     *       (§9) rather than letting a configured policy quietly do nothing - see
     *       {@link #reachableWithin(SpoolStore.Budget)}.</li>
     *   <li><b>Fetch duration ceiling</b> ({@link #fetchTimeout()}). An absolute wall-clock cap on the whole body
     *       transfer, a backstop against a fetch that is slow overall; exceeding it is a {@link Refusal#FETCH_TIMEOUT}.</li>
     *   <li><b>Minimum throughput floor</b> ({@link #minThroughputBytesPerSecond()}). A slow-loris upstream that
     *       trickles bytes or stalls mid-stream would otherwise pin a spool + connection indefinitely (the transport
     *       request timeout deliberately does not clip body transfer). Measured as the <em>cumulative average</em>
     *       (total bytes / elapsed) after a short {@link #THROUGHPUT_GRACE grace} so a legitimately slow-starting fetch
     *       is not killed; a rate below the floor is a {@link Refusal#FETCH_TIMEOUT}. A cumulative average tolerates a
     *       momentary pause on a genuinely large artifact over a slow-but-real link and only bites a sustained stall.
     *       A non-positive floor disables the throughput guard (the duration ceiling still applies).</li>
     * </ul>
     * The guard streams the body through, never buffering it (§1); a bound violation aborts the spool mid-stream and
     * the partial spool file is reclaimed exactly as any other refused fetch. Immutable; a non-positive size or
     * duration is refused at construction so a misconfiguration fails loud rather than disabling a ceiling silently.
     */
    public record Bounds(long maxArtifactBytes, Duration fetchTimeout, long minThroughputBytesPerSecond) {

        /** A generous but finite per-artifact full-screen size ceiling: 2 GiB. Larger than the overwhelming majority
         *  of real artifacts, small enough that a single hostile body cannot fill the disk on its own. */
        public static final long DEFAULT_MAX_ARTIFACT_BYTES = 2L * 1024 * 1024 * 1024;

        /** A generous absolute fetch duration ceiling: one hour. Long enough that a large artifact over a slow-but-real
         *  link finishes; a fetch still transferring after an hour is a backstop refusal. */
        public static final Duration DEFAULT_FETCH_TIMEOUT = Duration.ofHours(1);

        /** A conservative minimum-throughput floor: 256 B/s (cumulative average). Far below any real link (even a poor
         *  mobile connection sustains tens of KiB/s), so it bites only a true trickle/stall, not a slow-but-real fetch. */
        public static final long DEFAULT_MIN_THROUGHPUT_BYTES_PER_SECOND = 256;

        /** The grace window before the throughput floor is measured, so a legitimately slow-starting fetch (a TLS
         *  handshake, a cold upstream) is not judged on its first bytes. */
        public static final Duration THROUGHPUT_GRACE = Duration.ofSeconds(5);

        public Bounds {
            if (maxArtifactBytes <= 0) {
                throw new IllegalArgumentException("Per-artifact size ceiling must be positive: " + maxArtifactBytes);
            }
            Objects.requireNonNull(fetchTimeout, "fetchTimeout");
            if (fetchTimeout.isNegative() || fetchTimeout.isZero()) {
                throw new IllegalArgumentException("Fetch duration ceiling must be positive: " + fetchTimeout);
            }
        }

        /** The default bounds. */
        public static Bounds standard() {
            return new Bounds(DEFAULT_MAX_ARTIFACT_BYTES, DEFAULT_FETCH_TIMEOUT, DEFAULT_MIN_THROUGHPUT_BYTES_PER_SECOND);
        }

        /**
         * Bounds read from {@code config} ({@code spool.max-artifact-bytes}, {@code spool.fetch-timeout-millis},
         * {@code spool.fetch-min-throughput-bytes}), falling back to {@link #standard()} for each unset or unparseable
         * key - deploy-time resource dials a deployment sizes to its disk and links, exactly as the spool budget is.
         *
         * <p><b>The ceiling is read against the budget it will be spooled under, and the pair is validated.</b>
         * The per-artifact ceiling and the shared {@code budget} are enforced by different code over the same bytes and
         * used to validate only their own positivity, so a ceiling <em>above</em> the budget was accepted and then
         * never reachable: every body large enough to trip it exhausted the shared budget first and was answered 503,
         * and the configured per-artifact policy refusal could not fire once. The budget parameter is what makes that
         * unrepresentable - there is no way to read the ceiling without naming the budget it lives under.
         * <ul>
         *   <li>An <b>explicitly configured</b> ceiling above the budget is an operator selection that cannot be
         *       honoured, so it throws here, naming both keys and both values (&sect;9 fail fast: a config error names
         *       what was selected and what is missing, and a configuration that cannot do what it says is never
         *       accepted silently).</li>
         *   <li>An <b>unset</b> ceiling above an explicitly lowered budget is not an operator selection - the budget
         *       the operator did choose is honoured exactly - but the packaged ceiling is then decorative, so it is
         *       logged once at {@code WARNING} rather than either failing a boot the operator's own numbers do not
         *       contradict or leaving the dead dial unsaid.</li>
         * </ul>
         */
        public static Bounds fromConfig(UnaryOperator<String> config, SpoolStore.Budget budget) {
            Objects.requireNonNull(budget, "budget");
            String configured = config.apply("spool.max-artifact-bytes");
            long maxBytes = positiveLong(configured, DEFAULT_MAX_ARTIFACT_BYTES);
            long timeoutMillis = positiveLong(config.apply("spool.fetch-timeout-millis"),
                    DEFAULT_FETCH_TIMEOUT.toMillis());
            long minThroughput = nonNegativeLong(config.apply("spool.fetch-min-throughput-bytes"),
                    DEFAULT_MIN_THROUGHPUT_BYTES_PER_SECOND);
            Bounds bounds = new Bounds(maxBytes, Duration.ofMillis(timeoutMillis), minThroughput);
            if (!bounds.reachableWithin(budget)) {
                String detail = "the hardened proxy's per-artifact size ceiling spool.max-artifact-bytes="
                        + maxBytes + " sits above the spool in-flight size budget spool.max-bytes="
                        + budget.maxInFlightBytes() + " it is spooled under, so the per-artifact refusal ("
                        + Refusal.OVERSIZE + ", a 404-shaped policy refusal) can never fire: every body that large "
                        + "exhausts the shared budget first and is answered 503. Lower spool.max-artifact-bytes to at "
                        + "most spool.max-bytes, or raise spool.max-bytes to at least spool.max-artifact-bytes";
                if (parseLong(configured) != null) {
                    throw new IllegalArgumentException(detail);
                }
                LOGGER.warn("Unreachable spool configuration: " + detail
                        + " (spool.max-artifact-bytes is unset, so this is the packaged default ceiling rather than a "
                        + "configured one - the budget below it is honoured exactly)");
            }
            return bounds;
        }

        /** Whether this per-artifact ceiling can actually be reached under {@code budget} - it is at or below the
         *  shared in-flight budget the guarded body is spooled through. At equality the ceiling still fires first for
         *  a single spool (the guard counts a chunk before the store reserves it), so equality is reachable; above the
         *  budget nothing can reach it. The one place the two records meet, so neither restates the other's arithmetic. */
        public boolean reachableWithin(SpoolStore.Budget budget) {
            return maxArtifactBytes <= budget.maxInFlightBytes();
        }

        /** Wrap {@code body} so the size ceiling, duration ceiling and throughput floor are enforced as it streams,
         *  drawing wall-clock time from {@code nanoTime} (production {@code System::nanoTime}; a test a controllable
         *  clock). A violation throws {@link Oversize} or {@link SlowFetch} - both {@link IOException}s so they ride
         *  the spool write path and abort it mid-stream, reclaiming the partial spool. */
        InputStream guard(InputStream body, LongSupplier nanoTime) {
            return new Guarded(body, this, nanoTime);
        }

        private static long positiveLong(String value, long fallback) {
            Long parsed = parseLong(value);
            return parsed != null && parsed > 0 ? parsed : fallback;
        }

        private static long nonNegativeLong(String value, long fallback) {
            Long parsed = parseLong(value);
            return parsed != null && parsed >= 0 ? parsed : fallback;
        }

        private static Long parseLong(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException malformed) {
                return null;
            }
        }
    }

    /** Raised when a spooling body grows past the per-artifact {@link Bounds#maxArtifactBytes() size ceiling}: an
     *  {@link IOException} so it aborts the spool write mid-stream, mapped by the leg to a {@link Refusal#OVERSIZE}. */
    public static final class Oversize extends IOException {
        private Oversize(String message) {
            super(message);
        }
    }

    /** Raised when a fetch exceeds the {@link Bounds#fetchTimeout() duration ceiling} or falls below the
     *  {@link Bounds#minThroughputBytesPerSecond() throughput floor}: an {@link IOException} so it aborts the spool
     *  write mid-stream, mapped by the leg to a {@link Refusal#FETCH_TIMEOUT}. */
    public static final class SlowFetch extends IOException {
        private SlowFetch(String message) {
            super(message);
        }
    }

    /** The stream wrapper enforcing the untrusted-upstream {@link Bounds} as the body streams through the spool: it
     *  counts bytes (size ceiling) and watches elapsed wall-clock against the duration ceiling and the cumulative
     *  throughput floor, aborting with a typed {@link Oversize}/{@link SlowFetch} rather than letting an unbounded or
     *  trickling body pin the spool. It never buffers the body (§1); it only counts and times it. */
    private static final class Guarded extends FilterInputStream {

        private final Bounds bounds;
        private final LongSupplier nanoTime;
        private final long start;
        private long bytesRead;

        private Guarded(InputStream in, Bounds bounds, LongSupplier nanoTime) {
            super(in);
            this.bounds = bounds;
            this.nanoTime = nanoTime;
            this.start = nanoTime.getAsLong();
        }

        @Override
        public int read() throws IOException {
            checkTime();
            int b = in.read();
            if (b >= 0) {
                bytesRead++;
                checkSize();
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            checkTime();
            int read = in.read(buffer, offset, length);
            if (read > 0) {
                bytesRead += read;
                checkSize();
            }
            return read;
        }

        /** Refuse the moment the spooled body has grown past the per-artifact ceiling - a slight overshoot of one
         *  chunk is spooled then reclaimed, never served. */
        private void checkSize() throws Oversize {
            if (bytesRead > bounds.maxArtifactBytes()) {
                throw new Oversize("upstream body exceeded the per-artifact size ceiling of "
                        + bounds.maxArtifactBytes() + " bytes");
            }
        }

        /** Refuse a fetch that has run past the absolute duration ceiling, or whose cumulative average throughput has
         *  fallen below the floor once past the grace window - a slow-loris upstream trickling or stalling. */
        private void checkTime() throws SlowFetch {
            long elapsedNanos = nanoTime.getAsLong() - start;
            if (elapsedNanos <= 0) {
                return;
            }
            Duration elapsed = Duration.ofNanos(elapsedNanos);
            if (elapsed.compareTo(bounds.fetchTimeout()) > 0) {
                throw new SlowFetch("upstream fetch exceeded the duration ceiling of " + bounds.fetchTimeout()
                        + " (elapsed " + elapsed + ", " + bytesRead + " bytes)");
            }
            if (bounds.minThroughputBytesPerSecond() > 0 && elapsed.compareTo(Bounds.THROUGHPUT_GRACE) > 0) {
                double seconds = elapsedNanos / 1_000_000_000.0;
                double rate = bytesRead / seconds;
                if (rate < bounds.minThroughputBytesPerSecond()) {
                    throw new SlowFetch("upstream fetch throughput " + (long) rate + " B/s fell below the floor of "
                            + bounds.minThroughputBytesPerSecond() + " B/s after " + elapsed + " (" + bytesRead
                            + " bytes) - a stalled or slow-loris upstream");
                }
            }
        }
    }

    /** Immutable-coordinate drift alarm events (each a refused re-fetch of an immutable coordinate whose bytes changed
     *  under it) since the gateway started - the loud {@code jenreg.gateway.hardened.drift} counter (§9). Static so a
     *  per-request screen still contributes to the one gateway-wide alarm the {@link HardeningObservability} reports. */
    private static final AtomicLong DRIFT_EVENTS = new AtomicLong();

    /** The number of upstream drift alarms raised - a re-fetch of an immutable coordinate whose digest differed from
     *  the previously screened, pinned one, refused as tampering. Surfaced as a metric by {@link HardeningObservability}. */
    public static long driftEvents() {
        return DRIFT_EVENTS.get();
    }

    /** A re-openable source of the verified bytes to release on {@code ALLOW} - the spool file on the fetch path, the
     *  local store handle on the repair path. */
    @FunctionalInterface
    private interface ByteSource {
        InputStream open() throws IOException;
    }

    /**
     * The coordinate a hardened-leg verdict is recorded under in the consolidated metadata document. It is derived
     * <em>from the request path alone</em> - deterministically, without inspecting the body - so the recorded verdict
     * can be looked up for reuse before any inspection runs (the hot-path dedup, §7): the leading path segment is the
     * ecosystem, the whole request path the coordinate (URL-encoded into one segment by {@link
     * build.jenesis.repository.metadata.MetadataKey}), the filename the version. The identity that actually binds the
     * verdict to the bytes is the content digest carried <em>inside</em> the section, not this coordinate.
     */
    public record Coordinate(String ecosystem, String coordinate, String version) {
    }

    /**
     * The coordinate an <b>origin</b> row is written and read under: the coordinate the owning format parses out of
     * the path, falling back to {@link #coordinate(String)} only where no format claims it.
     *
     * <p>Separate from the verdict coordinate above, and deliberately. A verdict is about <em>these bytes at this
     * request path</em> and is looked up before anything is inspected, so a path-derived key is the right one and is
     * the only one available that early. An origin row is about <em>this artifact</em>, and a hand upload has always
     * folded its {@code local-upload} row under the format coordinate - so keying the {@code fallback} row by the
     * path split one artifact's acquisition history across two documents. One artifact, one origin document.
     *
     * <p>It lives here rather than being derived at each caller because there are three - the router that writes the
     * row, the 409 message that reads it, and the console panel - and the last two used to <em>restate</em> the
     * derivation with a javadoc saying it was "keyed exactly as the fallback-fetch path records it". A screen that
     * describes itself as a copy of another is one that will fall behind it, and this one did the moment the writer
     * moved.
     *
     * <p>{@link StoreRepositoryInventory#describe} is a pure path parse that opens no blob, so a caller on the serve
     * path pays only the format lookup it already does.
     */
    public static Coordinate originCoordinate(ArtifactStore store, String path) {
        Optional<ArtifactDescriptor> described = new StoreRepositoryInventory(store).describe(path);
        String ecosystem = described.map(ArtifactDescriptor::ecosystem).filter(Objects::nonNull).orElse("");
        String coordinate = described.map(ArtifactDescriptor::coordinate).filter(Objects::nonNull).orElse("");
        String version = described.map(ArtifactDescriptor::version).filter(Objects::nonNull).orElse("");
        return coordinate.isEmpty() || version.isEmpty()
                // No format claims the path, or it claims it without a coordinate (a checksum, a generated index):
                // the path-derived key is the only one there is.
                ? coordinate(path)
                : new Coordinate(ecosystem, coordinate, version);
    }

    /** Derive the verdict-record coordinate from the request path - deterministic, no body inspection. */
    public static Coordinate coordinate(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        String ecosystem = slash < 0 ? trimmed : trimmed.substring(0, slash);
        if (ecosystem.isEmpty()) {
            ecosystem = "proxy";
        }
        String file = ProxyScreen.fileName(path);
        return new Coordinate(ecosystem, path, file.isEmpty() ? "artifact" : file);
    }

    private final ProxyScreen screen;
    private final ArtifactStore spool;
    private final MetadataStore metadata;
    private final Bounds bounds;
    private final LongSupplier nanoTime;
    private final boolean reuseVerdict;

    /**
     * A hardened screen backed by the compliance {@code gate}, recording withholdings and refusals in {@code records}'
     * durable {@code QuarantineLog} and {@code /quarantine}, holding upstream versions younger than {@code holdDays},
     * and spooling the pre-verdict body into the budgeted {@code spool} - a per-request {@link SpoolStore} scratch the
     * router reclaims once the request is served. With no consolidated metadata store the leg records no verdict and
     * reuses none; the router binds one from the discovered persistence module. The
     * untrusted-upstream fetch {@link Bounds} default to {@link Bounds#standard()}.
     */
    public HardenedScreen(ComplianceGate gate, ArtifactStore records, int holdDays, ArtifactStore spool) {
        this(gate, records, holdDays, spool, null);
    }

    /** As {@link #HardenedScreen(ComplianceGate, ArtifactStore, int, ArtifactStore)}, binding the consolidated
     *  {@code metadata} store the digest-pinned verdict is recorded in and reused from; {@code null} degrades
     *  to screening every fetch. The untrusted-upstream fetch {@link Bounds} default to {@link Bounds#standard()}. */
    public HardenedScreen(ComplianceGate gate, ArtifactStore records, int holdDays, ArtifactStore spool,
                          MetadataStore metadata) {
        this(gate, records, holdDays, spool, metadata, Bounds.standard());
    }

    /** As {@link #HardenedScreen(ComplianceGate, ArtifactStore, int, ArtifactStore, MetadataStore)}, with the
     *  untrusted-upstream fetch {@code bounds} (size ceiling, duration ceiling, throughput floor). */
    public HardenedScreen(ComplianceGate gate, ArtifactStore records, int holdDays, ArtifactStore spool,
                          MetadataStore metadata, Bounds bounds) {
        this(gate, records, holdDays, spool, metadata, bounds, System::nanoTime);
    }

    /** As {@link #HardenedScreen(ComplianceGate, ArtifactStore, int, ArtifactStore, MetadataStore, Bounds)}, with
     *  {@code reuseVerdict} choosing whether a recorded {@code ALLOW} verdict may be reused to skip re-screening: a
     *  store-on-pass leg passes {@code true} (a durably cached copy is the trusted backing the reuse dedups against),
     *  the transient {@code harden nocache} leg passes {@code false} so every fetch re-screens the full body while the
     *  verdict is still recorded for audit and drift stays enforced. */
    public HardenedScreen(ComplianceGate gate, ArtifactStore records, int holdDays, ArtifactStore spool,
                          MetadataStore metadata, Bounds bounds, boolean reuseVerdict) {
        this(gate, records, holdDays, spool, metadata, bounds, System::nanoTime, reuseVerdict);
    }

    /** As {@link #HardenedScreen(ComplianceGate, ArtifactStore, int, ArtifactStore, MetadataStore, Bounds)}, with an
     *  explicit nanosecond time source for the fetch duration/throughput guard - production passes
     *  {@code System::nanoTime}; a test passes a controllable clock to drive the guard deterministically without real
     *  waiting. Verdict reuse is enabled (the store-on-pass default). */
    public HardenedScreen(ComplianceGate gate, ArtifactStore records, int holdDays, ArtifactStore spool,
                          MetadataStore metadata, Bounds bounds, LongSupplier nanoTime) {
        this(gate, records, holdDays, spool, metadata, bounds, nanoTime, true);
    }

    /** The canonical constructor: {@code reuseVerdict} gates whether a recorded {@code ALLOW} may be reuse-served (see
     *  {@link #HardenedScreen(ComplianceGate, ArtifactStore, int, ArtifactStore, MetadataStore, Bounds, boolean)}) and
     *  {@code nanoTime} drives the fetch guard. */
    public HardenedScreen(ComplianceGate gate, ArtifactStore records, int holdDays, ArtifactStore spool,
                          MetadataStore metadata, Bounds bounds, LongSupplier nanoTime, boolean reuseVerdict) {
        this.screen = new ProxyScreen(gate, records, holdDays);
        this.spool = Objects.requireNonNull(spool, "spool");
        this.metadata = metadata;
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.reuseVerdict = reuseVerdict;
    }

    /** Wrap an upstream fetcher so a hardened-proxy download is spooled, screened, and released only as a verified
     *  stream. The buffered {@code fetch} path (small mutable indexes) reuses the ordinary complete-body screen.
     *
     *  <p><b>The metadata leg ({@link ProxyFormat.Fetcher#head}).</b> Like {@code fetch}, it is handed to the ordinary
     *  {@link ProxyScreen} - which delegates the real HTTP {@code HEAD} to the transport below and holds the answer to
     *  the body-free half of the screen. The hardened strictening deliberately does <em>not</em> apply: it is
     *  spool-screen-release over a body, and a {@code HEAD} carries no body to spool, no bytes to digest and nothing to
     *  pin a verdict to. That is not a hole in the fail-closed posture - fail-closed guards a <em>byte</em> reaching the
     *  client, and this leg releases none. Refusing every {@code HEAD} outright would protect nothing and would push a
     *  client that only wanted a size onto the {@code GET} path, spooling a multi-gigabyte untrusted body to answer a
     *  metadata question: the precise inversion the three declared legs exist to prevent. The screen is a decorator, so
     *  it is never a {@link ProxyFormat.Fetcher.Buffered}; inheriting the derivation here would have spooled and
     *  full-body screened the whole artifact for every {@code HEAD}. */
    public ProxyFormat.Fetcher wrap(ProxyFormat.Fetcher upstream, String path) {
        ProxyFormat.Fetcher screened = screen.wrap(upstream, path);
        return new ProxyFormat.Fetcher() {
            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) throws IOException {
                // An index/metadata document is small and already screened whole (never a truncated prefix) by the
                // ordinary buffered screen; the hardened strictening is about the streaming artifact body, below.
                return screened.fetch(url, headers);
            }

            @Override
            public Optional<ProxyFormat.Head> head(URI url, Map<String, String> headers) throws IOException {
                // A metadata answer carries no body, so there is nothing to spool, digest, full-body screen or pin a
                // verdict to - the hardened tier has no subject here. It is handed to the ordinary screen, whose head
                // delegates the real HTTP HEAD below and holds the answer to the body-free deny-list/immaturity
                // assessment. Deriving head (a Fetcher.Buffered) would have spooled and full-body screened an entire
                // untrusted artifact to answer a question about its size.
                return screened.head(url, headers);
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> headers) throws IOException {
                Optional<ProxyFormat.Download> opened = upstream.download(url, headers);
                if (opened.isEmpty() || opened.get().status() != 200) {
                    return opened;   // an upstream miss - nothing to spool, screen or withhold
                }
                return harden(path, opened.get(), url.toString());
            }
        };
    }

    /**
     * Spool the fetched body to completion, screen it (or reuse a recorded verdict for the same digest), and release
     * only a verified stream on {@code ALLOW}; withhold a policy-failed body (recorded, {@code 404}-shaped) and refuse
     * a cannot-screen one (typed, recorded, {@code 404}-shaped). The verified spool file lives until the router
     * reclaims the per-request spool after serving.
     */
    private Optional<ProxyFormat.Download> harden(String path, ProxyFormat.Download response, String source)
            throws IOException {
        Instant lastModified = ProxyScreen.lastModified(response.header("last-modified"));
        String key;
        try (response) {
            // Spool the whole untrusted body to a bounded temp file - streamed and digested, never a heap byte[] -
            // through the untrusted-upstream fetch guard (size ceiling, duration ceiling, throughput floor). A
            // SpoolStore budget exhaustion propagates for the router to answer 503 (a shared-resource cap); a
            // per-artifact bound violation, or a truncated/lost body, is a typed structural refusal (never a partial
            // screen served as if whole).
            try {
                key = BLOB_PREFIX + spool.writeBlob(bounds.guard(response.body(), nanoTime));
            } catch (SpoolStore.BudgetExhausted budget) {
                throw budget;
            } catch (Oversize oversize) {
                return refuse(path, Refusal.OVERSIZE, oversize);
            } catch (SlowFetch slow) {
                return refuse(path, Refusal.FETCH_TIMEOUT, slow);
            } catch (IOException fetchError) {
                return refuse(path, Refusal.FETCH_INTERRUPTED, fetchError);
            }
        }
        String digest = key.substring(BLOB_PREFIX.length());
        Coordinate coordinate = coordinate(path);
        Optional<VerdictSection.Recorded> prior = priorVerdict(coordinate);
        // Verdict-reuse dedup (§7): a prior screen of these EXACT bytes recorded an ALLOW verdict, so release the
        // verified spool without paying the inspection/gate cost again. Reuse is digest-exact - a changed-bytes
        // artifact at the same coordinate never reuses and re-screens (or drifts) below. Reuse is gated on
        // reuseVerdict: a transient `harden nocache` leg disables it so every fetch re-screens the full body
        // (nothing is durably cached to reuse-serve), while still recording the verdict and enforcing drift below.
        if (reuseVerdict && prior.map(recorded -> recorded.allows(digest,
                QualityInspector.fullBodyInspectionLimit())).orElse(false)) {
            return Optional.of(new ProxyFormat.Download(200, spool.open(key), response.headers()));
        }
        // Drift detection: an immutable coordinate whose previously-recorded verdict pins DIFFERENT bytes than
        // this re-fetch is upstream tampering - a released version whose content changed under it. Refuse + ALARM (§9),
        // never silently serve the changed bytes; the original digest stays the pinned baseline, so every re-fetch of
        // the swapped bytes keeps tripping the alarm until an operator intervenes.
        if (prior.isPresent() && immutableCoordinate(path) && !prior.get().pins(digest)) {
            return driftRefuse(path, digest, prior.get());
        }
        // Full-body screen: the whole body is on disk, so every claiming inspector screens the COMPLETE
        // artifact through a re-openable spool handle, and each REPORTS whether its own read ran to completion.
        // The screen no longer derives that from the body's length: the length is the right test for a bridged
        // inspector (it sees the front prefix and nothing else) but not for a full-body one, whose byte, entry, finding
        // and nesting ceilings can stop it over a body of any size - and it was invisible altogether whenever some
        // other inspector produced a subject. Decompression/scan stays bomb-bounded by the shared full-body tier and
        // each inspector's own caps.
        return screenAndRecord(path, coordinate, digest, spooled(key), lastModified, source,
                () -> spool.open(key), response.headers());
    }

    /**
     * Serve a hardened artifact that is about to be released from the local store, fail-closed on an absent verdict.
     * When the bytes are not local this is a MISS (empty) - the caller falls to the normal fetch+screen path. When
     * they are local and a recorded {@code ALLOW} verdict pins these exact bytes, they are served without re-screening
     * (reuse-dedup, §7). When no verdict is recorded (never was, or the document lost it) the artifact is <em>never</em>
     * served unscreened: the local bytes are re-screened from scratch (self-healing repair, §5), the fresh verdict is
     * recorded, and the artifact is served only if that fresh verdict is {@code ALLOW}.
     */
    public Optional<ProxyFormat.Download> serveVerified(String path, Optional<QualityInspector.Content> local)
            throws IOException {
        if (local.isEmpty()) {
            return Optional.empty();   // bytes not local: a MISS, the normal fetch+screen path takes over
        }
        QualityInspector.Content body = local.get();
        String digest = digest(body);
        Coordinate coordinate = coordinate(path);
        if (reuseAllows(coordinate, digest)) {
            return Optional.of(new ProxyFormat.Download(200, body.open(), Map.of()));
        }
        // Fail-closed: no recorded ALLOW verdict pins these local bytes. Never serve unscreened - re-screen the local
        // bytes, record the fresh verdict, and serve only per it.
        return screenAndRecord(path, coordinate, digest, body, null, LOCAL_SOURCE, body::open, Map.of());
    }

    /** Inspect a fully-materialised body, reach the verdict, record it digest-pinned, and return the disposition:
     *  release the verified stream on {@code ALLOW}, withhold (recorded, {@code 404}-shaped) otherwise, and refuse a
     *  cannot-screen body as a typed, recorded, named structural refusal. Shared by the fetch path and the repair
     *  path so a repaired serve reaches the identical decision the fetch would. */
    private Optional<ProxyFormat.Download> screenAndRecord(String path, Coordinate coordinate, String digest,
            QualityInspector.Content body, Instant lastModified, String source,
            ByteSource release, Map<String, String> headers) throws IOException {
        List<VerdictSection.Validator> validators = screen.validators(path);
        QualityInspector.Inspection inspection;
        try {
            inspection = screen.inspectFullBody(path, body);
        } catch (MalformedArtifactException unparseable) {
            // Hardened posture: an inspector claimed the body but could not parse it is REFUSED (recorded, named), not
            // waved down to a path-derived deny-list check the way the lenient proxy screen falls back.
            recordVerdict(coordinate, digest, Verdict.REJECT, Refusal.UNPARSEABLE, source, validators);
            return refuse(path, Refusal.UNPARSEABLE, unparseable);
        } catch (IOException inspectorError) {
            // An inspector failed while screening - never swallowed into an anonymous 500 nor a silent serve (§9).
            recordVerdict(coordinate, digest, Verdict.REJECT, Refusal.INSPECTOR_ERROR, source, validators);
            return refuse(path, Refusal.INSPECTOR_ERROR, inspectorError);
        }
        ProxyScreen.Screening screening = screen.assessSubjects(path, inspection, lastModified);
        recordVerdict(coordinate, digest, screening.verdict(), null, source, validators);
        if (screening.verdict() == Verdict.ALLOW) {
            // Decide-then-serve: only now is a stream released, re-opened from the verified body - never the upstream
            // socket. The pull-through caches it into the durable store (store-on-pass) and serves it.
            return Optional.of(new ProxyFormat.Download(200, release.open(), headers));
        }
        // Withheld: the pull-through sees an empty result and serves a 404. A held body is copied durably under
        // /quarantine for review; a rejected one is discarded. Either way the reason is recorded before we return.
        if (screening.verdict() == Verdict.QUARANTINE) {
            try (InputStream held = body.open()) {
                screen.quarantine(path, held);
            }
        }
        screen.log(path, screening);
        return Optional.empty();
    }

    /** Whether a recorded {@code ALLOW} verdict pins exactly these bytes at this coordinate - the digest-exact reuse
     *  check, and the completeness check with it: a verdict reached under a lower full-body ceiling than the one now
     *  in force is not reused, because raising that ceiling is exactly the change that would have let the screen
     *  finish. A missing metadata store, an absent verdict, a withholding, a refusal, or a verdict over different
     *  bytes all return false, so the leg re-screens (fail-closed) rather than reusing; a read failure re-screens
     *  too. */
    private boolean reuseAllows(Coordinate coordinate, String digest) {
        return priorVerdict(coordinate)
                .map(recorded -> recorded.allows(digest, QualityInspector.fullBodyInspectionLimit()))
                .orElse(false);
    }

    /** The verdict previously recorded for this coordinate, if any - the reuse source (its {@code ALLOW}-and-pins check)
     *  and the drift baseline (its pinned digest). A missing metadata store, an absent verdict, or a read failure all
     *  return empty, so the leg re-screens (fail-closed) rather than reusing or false-alarming. */
    private Optional<VerdictSection.Recorded> priorVerdict(Coordinate coordinate) {
        if (metadata == null) {
            return Optional.empty();
        }
        try {
            return VerdictSection.recorded(metadata.section(coordinate.ecosystem(), coordinate.coordinate(),
                    coordinate.version(), VerdictSection.TAG));
        } catch (IOException e) {
            LOGGER.warn("Could not read the verdict record for " + coordinate.coordinate() + "; re-screening", e);
            return Optional.empty();
        }
    }

    /** Whether the request {@code path} names an immutable released coordinate (drift-protected) rather than a mutable
     *  one. The hardened leg's {@code download} path serves immutable artifacts by contract (a mutable index rides the
     *  buffered {@code fetch} path, revalidated fresh and never drift-pinned); the one download-path exception is a
     *  Maven snapshot version, whose bytes re-publish under the same coordinate by design, so a changed digest there is
     *  expected content movement, not drift.
     *
     *  <p>Public so the deploy path reuses this one drift signal for release-version immutability:
     *  the {@code publish/<path>}-pointer guard and the proxy-drift alarm agree on what "an immutable release
     *  coordinate" is, rather than inventing a parallel release-detection heuristic. */
    public static boolean immutableCoordinate(String path) {
        String upper = path.toUpperCase(Locale.ROOT);
        return !upper.contains("-SNAPSHOT") && !upper.contains("/SNAPSHOT/");
    }

    /** Refuse an immutable-coordinate re-fetch whose bytes drifted from the pinned baseline, and raise the loud drift
     *  ALARM (§9): a {@code WARNING} log, the {@code jenreg.gateway.hardened.drift} counter, and a durable
     *  {@link build.jenesis.repository.gate.QuarantineLog} {@code REJECT} row - never silently serving the changed
     *  bytes. The baseline verdict is left untouched so the swapped bytes stay refused on every subsequent re-fetch. */
    private Optional<ProxyFormat.Download> driftRefuse(String path, String digest, VerdictSection.Recorded baseline)
            throws IOException {
        DRIFT_EVENTS.incrementAndGet();
        String reason = REFUSAL_REASON_PREFIX + Refusal.DRIFT + "): " + Refusal.DRIFT.detail()
                + " - previously screened and pinned " + baseline.digest() + ", upstream now serves sha256:" + digest;
        LOGGER.warn("DRIFT ALARM: upstream tampering under immutable coordinate " + path + " - " + reason);
        screen.log(path, new ProxyScreen.Screening(Verdict.REJECT, ProxyScreen.fileName(path), List.of(reason)));
        return Optional.empty();
    }

    /** Record the digest-pinned verdict into the coordinate's metadata document through the section-scoped CAS
     *  mutate. Best-effort: the screen has already decided and the screened bytes are safe to serve, so a record
     *  failure is logged (never silent, §9) rather than failing the serve - it only costs a re-screen next time. */
    private void recordVerdict(Coordinate coordinate, String digest, Verdict verdict, Refusal refusal, String source,
                               List<VerdictSection.Validator> validators) {
        if (metadata == null) {
            return;
        }
        try {
            metadata.mutate(coordinate.ecosystem(), coordinate.coordinate(), coordinate.version(), VerdictSection.TAG,
                    VerdictSection.record(digest, verdict, refusal == null ? null : refusal.name(), PROFILE, source,
                            validators, Instant.now(), QualityInspector.fullBodyInspectionLimit()));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not record the hardened screening verdict for " + coordinate.coordinate(), e);
        }
    }

    /** A re-openable {@link QualityInspector.Content} over a spooled blob - the whole body streamable from byte zero as
     *  many times as an inspector (or the digest) needs, never a heap {@code byte[]}. */
    private QualityInspector.Content spooled(String key) {
        return new QualityInspector.Content() {
            @Override
            public long size() throws IOException {
                return spool.size(key);
            }

            @Override
            public InputStream open() throws IOException {
                return spool.open(key);
            }
        };
    }

    /** The SHA-256 hex of a re-openable body, streamed once - the identity a local-bytes repair pins its verdict to,
     *  computed over the whole body (never a bounded prefix) so it matches the spool's digest exactly. */
    private static String digest(QualityInspector.Content body) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = body.open()) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                sha.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    /** Record a structural, named refusal and withhold the body: a durable {@code QuarantineLog} REJECT row whose
     *  reason names the {@link Refusal}, a {@code WARNING} log, and an empty result so the wire stays a non-disclosive
     *  {@code 404} - the refusal is captured server-side, never swallowed and never disclosed to the client (§9). */
    private Optional<ProxyFormat.Download> refuse(String path, Refusal refusal, Exception cause) throws IOException {
        String reason = REFUSAL_REASON_PREFIX + refusal + "): " + refusal.detail()
                + (cause == null || cause.getMessage() == null ? "" : " - " + cause.getMessage());
        LOGGER.warn("REFUSED hardened proxy artifact " + path + ": " + reason, cause);
        screen.log(path, new ProxyScreen.Screening(Verdict.REJECT, ProxyScreen.fileName(path), List.of(reason)));
        return Optional.empty();
    }
}
