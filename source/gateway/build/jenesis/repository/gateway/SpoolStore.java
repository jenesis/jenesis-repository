package build.jenesis.repository.gateway;

import module java.base;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.OwnerOnly;

/**
 * The budgeted pre-verdict staging store for the hardening proxy: the scratch {@link ArtifactStore} an untrusted
 * upstream body is spooled into while it is fully screened, before any byte is released to a client. It is the
 * first-class promotion of the {@code nocache} pass-through's former in-line {@code PassThroughStore} - each blob is
 * spooled to a secure temp file, digested while it streams through and content-addressed like the real stores, never
 * pulled whole into a heap {@code byte[]} - now governed by an explicit resource {@link Budget} so an untrusted or
 * hostile upstream can spend only bounded disk and only bounded concurrency, and pressure is <em>refused and
 * measured</em> rather than absorbed as unbounded growth.
 *
 * <p><b>Contract.</b>
 * <ul>
 *   <li><b>Budgets.</b> A {@link Budget} caps two things: {@link Budget#maxInFlightBytes()} - the total bytes spooled
 *       to temp files across every in-flight spool at once - and {@link Budget#maxConcurrentSpools()} - the number of
 *       spools streaming a body at once. Both are counted live; the size budget is enforced <em>while</em> a body
 *       streams (a chunk at a time, so an oversize body is refused mid-stream, never after it has already landed on
 *       disk), the concurrency budget when a spool first writes a blob.</li>
 *   <li><b>503 on exhaustion.</b> Crossing either budget aborts the spool with a {@link BudgetExhausted} - an
 *       {@link IOException} whose message names the exhausted budget - which the {@link RepositoryRouter} turns into a
 *       {@code 503} (Insufficient resources) to the client (§9 fail-fast, errors visible). No byte of the refused body
 *       is ever served, and the store never grows past its ceiling (§1 the spool is bounded, never a heap buffer nor
 *       unbounded disk).</li>
 *   <li><b>Cleanup.</b> Every temp file is created owner-only ({@code 0600} on a POSIX filesystem) and is
 *       <em>always</em> reclaimed - on success, on a budget refusal, and on a mid-stream error - so a crashed or
 *       refused request leaves no orphaned spool. A per-request spool is an {@link AutoCloseable}; the router closes it
 *       once the request is served (see {@link #acquire()}).</li>
 *   <li><b>Metrics.</b> It is its own {@link ObservabilitySource}: a bounded {@code jenreg.gateway.spool.bytes} gauge
 *       (spooled bytes vs the size budget - a used-vs-available signal), a bounded {@code jenreg.gateway.spool.count}
 *       gauge (spools in flight vs the concurrency budget) and a {@code jenreg.gateway.spool.exhausted} counter
 *       (budget-exhaustion events, each a 503). The store is its own source, reported from the context that built
 *       it, without touching a meter registry - as {@code RevalidatingFetcher} reports its cache gauge.</li>
 * </ul>
 *
 * <p><b>Threading and state.</b> The budget counters ({@link #bytesInFlight}, {@link #activeSpools},
 * {@link #exhaustionEvents}) are the deliberate shared mutable state - held as atomics and confined to this store - so
 * concurrent spools account against one budget correctly (§11: the {@link Budget} config and every field is
 * {@code final}, mutation is limited to the atomics). A single {@link Budget} instance is immutable; a spool acquired
 * from {@link #acquire()} draws on this store's one budget, and its per-request scratch state is discarded on
 * {@link SpoolLease#close() close}.
 */
public final class SpoolStore implements ObservabilitySource {

    private static final String TEMP_PREFIX = "jenesis-spool-";
    private static final int COPY_BUFFER = 64 * 1024;

    /**
     * The resource budget a {@link SpoolStore} spends: the total bytes it may hold spooled to temp files across every
     * in-flight spool at once, and the number of spools that may stream a body at once. Immutable; a non-positive
     * value is refused at construction so a misconfiguration fails loud rather than disabling the ceiling silently.
     */
    public record Budget(long maxInFlightBytes, int maxConcurrentSpools) {

        /** A generous default: 4 GiB of in-flight spool across at most 16 concurrent spools. Both are runtime-tunable
         *  (see {@link SpoolStore#fromConfig}); the default is a safety ceiling, not a target. */
        public static final long DEFAULT_MAX_IN_FLIGHT_BYTES = 4L * 1024 * 1024 * 1024;
        public static final int DEFAULT_MAX_CONCURRENT_SPOOLS = 16;

        public Budget {
            if (maxInFlightBytes <= 0) {
                throw new IllegalArgumentException("Spool in-flight byte budget must be positive: " + maxInFlightBytes);
            }
            if (maxConcurrentSpools <= 0) {
                throw new IllegalArgumentException("Spool concurrency budget must be positive: " + maxConcurrentSpools);
            }
        }

        /** The default budget. */
        public static Budget standard() {
            return new Budget(DEFAULT_MAX_IN_FLIGHT_BYTES, DEFAULT_MAX_CONCURRENT_SPOOLS);
        }

        /** The budget read from {@code config} ({@code spool.max-bytes}, {@code spool.max-spools}), falling back to
         *  {@link #standard()} for each unset or unparseable key. Split out of {@link SpoolStore#fromConfig} so a
         *  caller that needs the <em>budget</em> without a store - the hardened leg's per-artifact ceiling, which is
         *  only reachable within it - reads the same two keys through the same parse rather than its own. */
        public static Budget fromConfig(UnaryOperator<String> config) {
            return new Budget(positiveLong(config.apply("spool.max-bytes"), DEFAULT_MAX_IN_FLIGHT_BYTES),
                    positiveInt(config.apply("spool.max-spools"), DEFAULT_MAX_CONCURRENT_SPOOLS));
        }
    }

    /** Raised when a spool is refused because a {@link Budget} is exhausted; the message names which budget. An
     *  {@link IOException} so it rides the store write path and the {@link RepositoryRouter} maps it to a {@code 503}. */
    public static final class BudgetExhausted extends IOException {

        private BudgetExhausted(String message) {
            super(message);
        }
    }

    private final Budget budget;
    private final Path directory;
    private final AtomicLong bytesInFlight = new AtomicLong();
    private final AtomicInteger activeSpools = new AtomicInteger();
    private final AtomicLong exhaustionEvents = new AtomicLong();

    /** A spool store with the given budget, spooling to the system temporary directory. */
    public SpoolStore(Budget budget) {
        this(budget, null);
    }

    /** A spool store with the given budget, spooling into {@code directory} ({@code null} = the system temporary
     *  directory). An explicit directory is the seam a test points at a {@code @TempDir} to assert the spool is empty
     *  after a request. */
    public SpoolStore(Budget budget, Path directory) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.directory = directory;
    }

    /** A spool store whose budget is read from {@code config} ({@code spool.max-bytes}, {@code spool.max-spools}),
     *  falling back to {@link Budget#standard()} for each unset or unparseable key - the runtime-tunable knobs a
     *  deployment sizes to its disk (§3: a new tunable is a runtime setting, not a redeploy-only constant). */
    public static SpoolStore fromConfig(UnaryOperator<String> config) {
        return new SpoolStore(Budget.fromConfig(config));
    }

    private static long positiveLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }

    private static int positiveInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }

    /** The budget this store spends against. */
    public Budget budget() {
        return budget;
    }

    /**
     * A fresh per-request spool: an {@link ArtifactStore} that streams each blob to a bounded, owner-only temp file
     * drawing on this store's budget, and an {@link AutoCloseable} the router closes once the request is served -
     * reclaiming every temp file and releasing the request's budget. This is the seam {@link RepositoryRouter}'s
     * {@code nocache} leg (and the hardening screen builds on it) fetches an untrusted body through.
     */
    public ArtifactStore acquire() {
        return new SpoolLease().root();
    }

    /** Bytes currently spooled to temp files across every in-flight spool - the live {@code jenreg.gateway.spool.bytes}
     *  gauge value. */
    public long bytesInFlight() {
        return bytesInFlight.get();
    }

    /** Spools currently streaming a body - the live {@code jenreg.gateway.spool.count} gauge value. */
    public int activeSpools() {
        return activeSpools.get();
    }

    /** Budget-exhaustion events (each answered as a 503) since this store was created. */
    public long exhaustionEvents() {
        return exhaustionEvents.get();
    }

    @Override
    public List<Metric> metrics() {
        return List.of(
                Metric.bounded("jenreg.gateway.spool.bytes",
                        "Untrusted-upstream bytes currently spooled to temp files while being fully screened, against "
                                + "the in-flight spool size budget past which a further spool is refused with 503 - a "
                                + "used-vs-available signal for how close the hardening proxy's pre-verdict staging is "
                                + "to its disk ceiling.",
                        bytesInFlight.get(), budget.maxInFlightBytes(), "bytes"),
                Metric.bounded("jenreg.gateway.spool.count",
                        "Spools in flight right now - untrusted bodies being staged and screened concurrently - "
                                + "against the concurrency budget past which a further spool is refused with 503.",
                        activeSpools.get(), budget.maxConcurrentSpools(), ""),
                Metric.counter("jenreg.gateway.spool.exhausted",
                        "Times a spool was refused because a budget - in-flight bytes or concurrency - was exhausted, "
                                + "each answered to the client as a 503: resource pressure made visible rather than an "
                                + "unbounded spool or a screening bypass.",
                        exhaustionEvents.get(), ""));
    }

    @Override
    public List<HealthCheck> healthChecks() {
        return List.of(HealthCheck.up("jenreg.gateway.spool",
                "The budgeted pre-verdict spool store is installed, staging untrusted upstream bodies to bounded, "
                        + "owner-only temp files for full screening before any byte is served."));
    }

    /** The owner-only spool file ({@link OwnerOnly}), in the configured directory or the default temporary one: a
     *  fetched body is the plaintext artifact, and it must not sit world-readable in a shared directory while it is
     *  screened. */
    private Path createTemp() throws IOException {
        return directory == null
                ? OwnerOnly.createTempFile(TEMP_PREFIX, null)
                : OwnerOnly.createTempFile(directory, TEMP_PREFIX, null);
    }

    /** A spooled blob: the temp file it landed in and its byte length (equal to the budget it holds). */
    private record Spooled(Path file, long bytes) {
    }

    /**
     * The shared per-request scratch a {@link #acquire()} hands out: the in-memory pointer objects, the temp-file blob
     * index, this request's contribution to the budget, and its single concurrency slot - all shared across every
     * {@link Spool#scope(String) scoped view} of the request so closing the root reclaims everything. The store's blob
     * bytes stream into secure temp files (never heap); the small versioned pointer objects stay in memory.
     */
    private final class SpoolLease {

        private final Map<String, byte[]> pointers = new ConcurrentHashMap<>();
        private final Map<String, Path> blobs = new ConcurrentHashMap<>();
        // This request's own contribution to bytesInFlight - the sum of the sizes of the temp files it holds - so
        // close() subtracts exactly what it added, and a mid-request refusal never leaves the global gauge drifted.
        private final AtomicLong ownBytes = new AtomicLong();
        // The one concurrency slot this request holds, acquired lazily on its first blob spool (a request that only
        // 404-misses never spends a slot) and released once on close.
        private final AtomicBoolean slotHeld = new AtomicBoolean();

        private Spool root() {
            return new Spool(this, "");
        }

        /** Acquire this request's concurrency slot on its first blob spool; a no-op on later spools of the same
         *  request. Refuses with {@link BudgetExhausted} when the concurrency budget is full. */
        private void acquireSlot() throws BudgetExhausted {
            if (slotHeld.compareAndSet(false, true)) {
                if (activeSpools.incrementAndGet() > budget.maxConcurrentSpools()) {
                    activeSpools.decrementAndGet();
                    slotHeld.set(false);
                    exhaustionEvents.incrementAndGet();
                    throw new BudgetExhausted("Spool concurrency budget of " + budget.maxConcurrentSpools()
                            + " concurrent spools exhausted");
                }
            }
        }

        /**
         * Copy {@code in} to a fresh secure temp file - digesting through {@code digest} when one is given - enforcing
         * the size budget a chunk at a time so an oversize body is refused mid-stream, and always cleaning the file up
         * on a refusal or error. The one place a blob's bytes touch storage, and they stream through, never buffered
         * whole (§1). On success the reserved bytes stay counted (subtracted at {@link #close()} or when the file is
         * discarded); on any failure they are released and the partial file deleted.
         */
        private Spooled spool(InputStream in, MessageDigest digest) throws IOException {
            acquireSlot();
            Path file = createTemp();
            long reserved = 0;
            boolean committed = false;
            try (OutputStream out = digest == null
                    ? Files.newOutputStream(file)
                    : new DigestOutputStream(Files.newOutputStream(file), digest)) {
                byte[] buffer = new byte[COPY_BUFFER];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    reserved += read;
                    if (bytesInFlight.addAndGet(read) > budget.maxInFlightBytes()) {
                        exhaustionEvents.incrementAndGet();
                        throw new BudgetExhausted("Spool in-flight size budget of " + budget.maxInFlightBytes()
                                + " bytes exhausted");
                    }
                    out.write(buffer, 0, read);
                }
                committed = true;
            } finally {
                if (!committed) {
                    // A refusal or error: release every byte this failed blob reserved and delete its partial file, so
                    // the global gauge never drifts and no orphan spool survives (cleanup on refusal and on error).
                    bytesInFlight.addAndGet(-reserved);
                    Files.deleteIfExists(file);
                }
            }
            ownBytes.addAndGet(reserved);
            return new Spooled(file, reserved);
        }

        /** Drop a spooled file this request no longer holds (an overwrite, a dedup, a delete), releasing its budget
         *  from both the global gauge and this request's own tally and deleting it. */
        private void discard(Path file) throws IOException {
            long length = Files.exists(file) ? Files.size(file) : 0;
            bytesInFlight.addAndGet(-length);
            ownBytes.addAndGet(-length);
            Files.deleteIfExists(file);
        }

        /** Reclaim this request's spool: delete every temp file it still holds, release its whole budget contribution
         *  and its concurrency slot. Called once by the router when the request is served; a cleanup failure never
         *  masks the served result. */
        private void close() {
            for (Path file : blobs.values()) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // best-effort scratch reclamation
                }
            }
            blobs.clear();
            bytesInFlight.addAndGet(-ownBytes.getAndSet(0));
            if (slotHeld.compareAndSet(true, false)) {
                activeSpools.decrementAndGet();
            }
        }
    }

    /**
     * One view of a {@link SpoolLease}'s scratch at a tenant/repository prefix - the {@link ArtifactStore} a format writes
     * an untrusted body into and reads it back from. Every scoped view shares the one lease, so closing the root the
     * router acquired reclaims the whole request's spool.
     */
    private static final class Spool implements ArtifactStore, AutoCloseable {

        private final SpoolLease lease;
        private final String prefix;

        private Spool(SpoolLease lease, String prefix) {
            this.lease = lease;
            this.prefix = prefix;
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return new Spool(lease, prefix + tenant + "/");
        }

        @Override
        public Object identity() {
            return "spool:" + System.identityHashCode(lease) + "/" + prefix;
        }

        @Override
        public boolean exists(String key) {
            return lease.blobs.containsKey(prefix + key) || lease.pointers.containsKey(prefix + key);
        }

        @Override
        public long size(String key) throws IOException {
            Path spool = lease.blobs.get(prefix + key);
            if (spool != null) {
                return Files.size(spool);
            }
            byte[] pointer = lease.pointers.get(prefix + key);
            return pointer == null ? -1L : pointer.length;
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            Path spool = lease.blobs.get(prefix + key);
            if (spool != null) {
                try (InputStream in = Files.newInputStream(spool)) {
                    in.transferTo(out);
                }
                return;
            }
            byte[] pointer = lease.pointers.get(prefix + key);
            if (pointer != null) {
                out.write(pointer);
            }
        }

        @Override
        public InputStream open(String key) throws IOException {
            Path spool = lease.blobs.get(prefix + key);
            if (spool != null) {
                return Files.newInputStream(spool);
            }
            byte[] pointer = lease.pointers.get(prefix + key);
            if (pointer == null) {
                throw new IOException("No object at " + key);
            }
            return new ByteArrayInputStream(pointer);
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            Spooled spooled = lease.spool(in, null);
            Path previous = lease.blobs.put(prefix + key, spooled.file());
            if (previous != null) {
                lease.discard(previous);
            }
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            Spooled spooled = lease.spool(in, digest);
            String hash = HexFormat.of().formatHex(digest.digest());
            Path existing = lease.blobs.putIfAbsent(prefix + "blobs/" + hash, spooled.file());
            if (existing != null) {
                lease.discard(spooled.file());
            }
            return hash;
        }

        @Override
        public void delete(String key) throws IOException {
            lease.pointers.remove(prefix + key);
            Path spool = lease.blobs.remove(prefix + key);
            if (spool != null) {
                lease.discard(spool);
            }
        }

        @Override
        public List<String> list(String key) {
            String base = prefix + (key.endsWith("/") ? key : key + "/");
            SequencedSet<String> children = new LinkedHashSet<>();
            for (String stored : lease.pointers.keySet()) {
                addChild(children, base, stored);
            }
            for (String stored : lease.blobs.keySet()) {
                addChild(children, base, stored);
            }
            return new ArrayList<>(children);
        }

        private static void addChild(SequencedSet<String> children, String base, String stored) {
            if (stored.startsWith(base)) {
                String rest = stored.substring(base.length());
                int slash = rest.indexOf('/');
                children.add(slash < 0 ? rest : rest.substring(0, slash));
            }
        }

        @Override
        public Optional<Versioned> readVersioned(String key) {
            byte[] content = lease.pointers.get(prefix + key);
            return content == null ? Optional.empty() : Optional.of(new Versioned(content, content));
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) {
            lease.pointers.put(prefix + key, content);
            return true;
        }

        /**
         * Held rather than streamed, and that is what this store <em>is</em>: a spool keeps the pointers of a
         * publish in flight in memory until the lease commits, so there is nowhere to stream to.
         *
         * <p>Stated explicitly rather than inherited, because the inherited body does the same thing for a
         * completely different reason. Three sibling decorators inherited it by accident and silently turned a
         * streaming backend into a buffering one; a reader cannot tell the deliberate case from the accident
         * unless the deliberate one says so.
         */
        @Override
        public boolean writeVersioned(String key, InputStream content, long length, Object expected)
                throws IOException {
            return writeVersioned(key, content.readNBytes(Math.toIntExact(length)), expected);
        }

        @Override
        public void close() {
            lease.close();
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}
}
