package build.jenesis.repository.compliance.osv;

import module java.base;
import module tools.jackson.databind;
import module org.slf4j;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.FeedChanges;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.feed.FeedClient;
import build.jenesis.repository.feed.FeedException;
import build.jenesis.repository.feed.FeedPolicy;
import build.jenesis.repository.feed.FeedTransport;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Checksums;

/**
 * OSV answered from a local copy of its export rather than asked over its API: an {@link AdvisorySource.Mirror} keeping
 * the ecosystems the repositories selecting it hold ({@link OsvMirror}), so a deployment with no egress at screening time
 * screens its cached copies all the same, and a scan costs no request per version.
 *
 * <p>A lookup reads the ecosystem's copy - its {@code current} document and the package's, two point reads, the first
 * held for {@link OsvMirror#STATE_TTL} - and decides each record against the version as {@link OsvRanges} does; each record
 * makes the {@link OsvRecords advisory} the API feed makes of it. An ecosystem the copy does not hold yet raises, so the
 * screen decides it as an outage, never as clean.
 *
 * <p>A {@link #refresh} builds each wanted ecosystem with no copy, and rebuilds one whose copy is older than the
 * rebuild interval or whose change list ran past its position; {@link #drawChanges} brings every copy up to date from
 * its change list and logs the packages whose records changed, which a scan asks about again. Both run under the
 * refresh pass's lease, and a build is the long one: it streams the ecosystem's whole archive.
 */
public final class OsvMirrorSource implements AdvisorySource.Mirror, AdvisorySource.Changes {

    private static final Logger LOGGER = LoggerFactory.getLogger(OsvMirrorSource.class);

    /** The feed's name - the attribution key its provider answers to and the client names in every failure. */
    static final String FEED = "osv-mirror";

    /** How often a copy is drawn whole again when nothing else asks for it: the backstop for an update a draw could not
     *  apply. */
    static final Duration DEFAULT_REBUILD = Duration.ofDays(7);

    /** The most records one {@link #drawChanges} fetches across every ecosystem. */
    static final int RECORDS = 500;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** A whole ecosystem's archive is hundreds of megabytes and takes minutes, which the per-lookup bounds would
     *  refuse: four gibibytes, ten minutes a request and an hour in all, two attempts. Fail-closed, so a draw that did
     *  not land says so. */
    static final FeedPolicy POLICY = FeedPolicy.closed()
            .maxResponseBytes(4L << 30)
            .deadline(Duration.ofHours(1))
            .requestTimeout(Duration.ofMinutes(10))
            .maxAttempts(2);

    private final OsvMirror mirror;
    private final Supplier<ArtifactStore> space;
    private final Clock clock;
    private final Supplier<Duration> rebuild;

    private OsvMirrorSource(FeedClient client, URI export, Supplier<ArtifactStore> space, Clock clock,
                            Supplier<Duration> rebuild) {
        this.mirror = new OsvMirror(space, client, export, clock);
        this.space = space;
        this.clock = clock;
        this.rebuild = rebuild;
    }

    /** The production form: OSV's export at {@code export}, the copy in {@code space}, on {@code clock}, each copy
     *  rebuilt after {@code rebuild} - read as each refresh begins, {@link Duration#ZERO} for never on a schedule. */
    public static OsvMirrorSource over(URI export, Supplier<ArtifactStore> space, Clock clock,
                                       Supplier<Duration> rebuild) {
        return new OsvMirrorSource(FeedClient.of(FEED, FeedTransport.jdk(CONNECT_TIMEOUT), POLICY), export, space, clock,
                rebuild);
    }

    /** A source sending every request through {@code transport}, keeping its copy in {@code space} on {@code clock}. */
    public static OsvMirrorSource responding(FeedTransport transport, Supplier<ArtifactStore> space, Clock clock,
                                             Duration rebuild) {
        return new OsvMirrorSource(FeedClient.of(FEED, transport, POLICY),
                OsvAdvisorySource.DEFAULT_EXPORT, space, clock, () -> rebuild);
    }

    @Override
    public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
        if (!OsvQuery.covers(ecosystem)) {
            return List.of();
        }
        String osvName = base(ecosystem);
        try {
            Optional<OsvMirror.State> state = mirror.heldState(osvName);
            if (state.isEmpty()) {
                throw new UncheckedIOException(new IOException("The OSV mirror holds no copy of " + osvName
                        + " yet: a repository selecting it holds the ecosystem, and its first draw has not landed"));
            }
            String product = OsvQuery.base(ecosystem);
            List<Advisory> advisories = new ArrayList<>();
            for (JsonNode record : mirror.records(osvName, state.get(), OsvRanges.key(product, coordinate))) {
                if (OsvRanges.affects(record, ecosystem, coordinate, version)) {
                    OsvRecords.advisory(record, coordinate).ifPresent(advisories::add);
                }
            }
            return List.copyOf(advisories);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the OSV mirror's copy of " + osvName, e);
        }
    }

    @Override
    public Set<String> ecosystems() {
        return OsvQuery.covered();
    }

    @Override
    public void mirror(Set<String> ecosystems) throws IOException {
        Set<String> covered = new TreeSet<>();
        for (String ecosystem : ecosystems) {
            if (OsvQuery.covers(ecosystem)) {
                covered.add(OsvQuery.base(ecosystem));
            }
        }
        mirror.want(covered);
    }

    /**
     * Build every wanted ecosystem with no copy, one older than the rebuild interval, or one whose change list could
     * not be followed. An ecosystem whose export
     * could not be drawn keeps the copy it had and leaves the freshness this answers short of authoritative; the
     * others are still built.
     */
    @Override
    public Freshness refresh() throws IOException {
        Instant now = clock.instant();
        Duration every = rebuild.get();
        for (String product : mirror.wanted()) {
            String osvName = OsvQuery.osvName(product);
            Optional<OsvMirror.State> state = mirror.state(osvName);
            if (state.isPresent() && !state.get().built().equals(Instant.EPOCH)
                    && (every.isZero() || state.get().built().plus(every).isAfter(now))) {
                continue;
            }
            try {
                mirror.build(osvName, product);
                LOGGER.info("Drew OSV's {} export whole into the mirror", osvName);
            } catch (FeedException e) {
                LOGGER.warn("Could not draw OSV's {} export into the mirror ({}); {}", osvName, OsvQuery.reason(e),
                        state.isPresent() ? "the copy built at " + state.get().built() + " keeps serving"
                                : "it is screened as an outage until a draw lands", e);
            }
        }
        return freshness();
    }

    @Override
    public List<Copy> copies() throws IOException {
        List<Copy> copies = new ArrayList<>();
        for (String product : mirror.wanted()) {
            Optional<OsvMirror.State> state = mirror.state(OsvQuery.osvName(product));
            copies.add(new Copy(product, state.map(OsvMirror.State::built).orElse(null),
                    state.map(OsvMirror.State::drawn).orElse(null)));
        }
        return List.copyOf(copies);
    }

    /** A digest of the generations the wanted ecosystems serve: it moves when a build lands, which sends the passes
     *  judging held versions over every one of them again. */
    @Override
    public Optional<String> snapshot() throws IOException {
        StringBuilder serving = new StringBuilder();
        for (String product : mirror.wanted()) {
            String osvName = OsvQuery.osvName(product);
            serving.append(osvName).append(' ')
                    .append(mirror.state(osvName).map(OsvMirror.State::generation).orElse("-")).append('\n');
        }
        return Optional.of(Checksums.sha256(serving.toString()));
    }

    /**
     * Bring every wanted copy up to date from its change list, at most {@link #RECORDS} records in all, and log the
     * packages they changed. A copy whose list ran past its position is rebuilt by the next {@link #refresh}, and the
     * draw records a gap, since what it skipped is unknown.
     */
    @Override
    public int drawChanges() throws IOException {
        ArtifactStore store = space.get();
        FeedChanges.Opened log = FeedChanges.open(store);
        Map<String, String> positions = new TreeMap<>();
        Set<Package> named = new LinkedHashSet<>();
        boolean gap = false;
        int budget = RECORDS;
        for (String product : mirror.wanted()) {
            String osvName = OsvQuery.osvName(product);
            Optional<OsvMirror.State> state = mirror.state(osvName);
            if (state.isEmpty() || budget <= 0) {
                continue;
            }
            try {
                Optional<OsvMirror.Update> update = mirror.update(osvName, product, state.get(), budget);
                if (update.isEmpty()) {
                    mirror.markForRebuild(osvName, state.get());
                    gap = true;
                } else {
                    named.addAll(update.get().packages());
                    budget -= update.get().fetched();
                }
            } catch (FeedException e) {
                throw new IOException("Could not draw what OSV changed in " + osvName + " (" + OsvQuery.reason(e)
                        + ")", e);
            }
            mirror.state(osvName).ifPresent(updated -> positions.put(osvName, updated.position().toString()));
        }
        return FeedChanges.commit(store, log, new FeedChanges.Draw(positions, named, gap), clock.instant());
    }

    @Override
    public ChangeLog changes(long after) throws IOException {
        return FeedChanges.read(space.get(), after);
    }

    /** Nothing to forget: every lookup reads the stored copy. */
    @Override
    public void forget(Set<Package> packages) {
    }

    /** Authoritative while every wanted ecosystem has a copy, as of the oldest draw among them; {@link Freshness#NEVER}
     *  while it is asked to keep none, since nothing then is confirmed. */
    @Override
    public Freshness freshness() {
        try {
            List<Freshness> parts = new ArrayList<>();
            for (String product : mirror.wanted()) {
                parts.add(mirror.heldState(OsvQuery.osvName(product)).map(state -> Freshness.at(state.drawn()))
                        .orElse(Freshness.NEVER));
            }
            return Freshness.merged(parts);
        } catch (IOException e) {
            LOGGER.warn("Could not read the OSV mirror's state", e);
            return Freshness.NEVER;
        }
    }

    /** OSV's name of {@code ecosystem}'s copy: a distribution's whole, without a release. */
    private static String base(String ecosystem) {
        return OsvQuery.osvName(OsvQuery.base(ecosystem));
    }
}
