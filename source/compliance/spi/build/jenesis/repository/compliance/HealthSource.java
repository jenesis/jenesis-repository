package build.jenesis.repository.compliance;

import module java.base;

/**
 * Supplies the maintainer-health of an ecosystem-neutral coordinate - a score derived from the coordinate's source
 * repository (its maintenance activity, whether its changes are code-reviewed, and whether its releases are signed /
 * built with verifiable provenance), the OpenSSF Scorecard-style signals a reviewer weighs beside a vulnerability's
 * severity. Where an advisory feed says what is wrong with a version, a health signal says how well the project
 * behind it is looked after, so a finding on an abandoned single-maintainer package can be prioritised above the same
 * finding on a well-run one, and an operator can gate on a health floor. The lookup is <em>per coordinate</em>
 * (version-independent - health is a property of the project, not a release), keyed like every purl feed, and degrades
 * gracefully: a coordinate with no resolvable source repository, or one the source scores nothing, is simply
 * {@link Optional#empty() absent} rather than an error. A network-backed source is a separate implementation of this
 * seam; the default reports nothing, for a deployment that consults no health signal. A source module contributes an
 * implementation through {@link SignalSourceProvider}, opting into this contract by implementing it; {@link #resolve}
 * merges every enabled source into one keeping the lowest (most conservative) score per coordinate.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> One instance is shared by the gate's health dimension, the scheduled re-analysis sweep and
 *     every console panel that ranks findings, and they call {@link #health} concurrently. An implementation that
 *     caches must do so under a memory model that lets a reader see a completed refresh whole.</li>
 * <li><b>Idempotency / replay.</b> {@link #health} is a question, not a command: asking twice for the same coordinate
 *     must yield an equal answer, because a finding is re-ranked on migration and on re-analysis and a drifting score
 *     would reorder a review queue without anything having changed. Two calls may legitimately cost one upstream
 *     lookup (a warm cache) or several; what may not differ is the answer.</li>
 * <li><b>Absence sentinel.</b> {@link Optional#empty()} means "no health is known for this coordinate", and
 *     {@code null} is never legal - neither as the {@link Optional} nor inside it. "No health source is active" is the
 *     identity-comparable {@link #NONE}, which {@link #resolve} folds an empty candidate set into, so a caller can
 *     tell an unranked deployment from an unrated coordinate by identity. Inside a {@link Health}, a component the
 *     source could not evaluate is {@link Health#NOT_EVALUATED} and never a zero: zero is a finding, {@code -1} is a
 *     silence, and rendering the two the same would report a well-run project as unreviewed.</li>
 * <li><b>The neutral element, and what it deliberately cannot say (&sect;9).</b> This is the clause a consumer must
 *     read before it gates on anything. Absence covers <em>four</em> different facts - the ecosystem is one the
 *     source does not cover, the coordinate has no resolvable source repository, the repository exists but was never
 *     scored, and the source could not be reached - and the {@link Optional} alone distinguishes none of them,
 *     because a health signal fails <b>soft</b>: it is a ranking aid, so an outage must let a review rank on severity
 *     and reachability rather than blocking a publish the way a broken {@link AdvisorySource} does. The consequence
 *     is explicit rather than implied: <b>an absent score is not evidence of anything</b>, so a health floor may
 *     reject only what it has a score for, and a policy that treated absence as failing would fail every private
 *     coordinate and every vendor outage alike.
 *     <p>The fourth fact - the source could not be reached - is the one a gate must be able to see, and
 *     {@link SignalSource#freshness()} is where it now lives: with the source not
 *     {@link Freshness#authoritative() authoritative}, <em>every</em> coordinate reads unrated, so a health floor
 *     that acted on absence would reject the whole world on one vendor outage. The rule is therefore the same one
 *     {@link ExploitProbabilitySource} states for an EPSS threshold - <b>a floor may only decide about a coordinate
 *     it has a score for, and a source that is not authoritative yields no scores to decide on</b> - so the health
 *     dimension stands down for the pass and the other dimensions decide, exactly as when no health source is
 *     installed. The first three facts remain deliberately indistinguishable from each other, because all three mean
 *     "nobody scored this project" and a review treats them alike.</p></li>
 * <li><b>Bounded work / cancellation (&sect;12).</b> A network-backed source bounds every lookup: a per-request
 *     timeout, a whole-lookup deadline and a response byte cap, plus the length of any resolution <em>chain</em> it
 *     walks (the deps.dev implementation resolves a coordinate's default version, that version's source repository
 *     and only then its Scorecard - three separate bounded queries, and a fourth would be a fourth metered call per
 *     ranked coordinate). Reaching a bound degrades to absent under clause 4 rather than answering with a
 *     partially-resolved score, because a score drawn from an incomplete resolution ranks a project on evidence
 *     nobody gathered.</li>
 * <li><b>Read purity (&sect;10).</b> The intent is that {@link #health} renders what a refresh already stored, so a
 *     ranking stands while the source is down. <b>No implementation meets this today</b>: the one health source in
 *     the inventory fetches from inside the query and holds its answer in a process-local TTL cache, so a restarted
 *     deployment re-walks the resolution chain for every coordinate it ranks. Recorded here rather than left implicit,
 *     because an undocumented divergence on a shared concern is a defect (&sect;13); clause 4 is what keeps it
 *     harmless, since the worst outcome of the divergence is an unranked finding rather than a wrong verdict.</li>
 * <li><b>Staleness.</b> {@link SignalSource#freshness()} carries it, so a console showing a coordinate without a
 *     score can say when this source last answered at all - an empty health panel beside a fetch instant is "nothing
 *     scored as of then", and one beside {@link Freshness#NEVER} is "this source has never answered" (&sect;10). A
 *     maintainer-health score also ages differently from an advisory: a project's maintenance signals move over
 *     months, so the instant is what tells a reviewer whether an 8.4 still describes the project or describes it as
 *     it was.
 *     <p>That difference decides what happens to an aged score whose refresh fails: it is <b>kept, with its original
 *     instant</b> ({@link FeedCache#failSoft}), because a Scorecard that is a day late still describes the
 *     project and the alternative - the neutral "unrated" - is no evidence at all, in the direction that loosens.
 *     {@link AdvisorySource} clause 4 rules the other way for an advisory list, and says why.
 *     <p>The reading is <b>one value for the source</b> even though a lookup is per coordinate, and deliberately so:
 *     a health floor is a policy over a pass rather than over a coordinate, and a floor applied to some coordinates
 *     of a pass and skipped for others yields a report that cannot say which coordinates were screened. So a lookup
 *     that resolved nothing at all stands the whole dimension down - clause 4's "a source that is not authoritative
 *     yields no scores to decide on" - until <em>that</em> coordinate resolves again or its retry window lapses. A
 *     success on a <em>different</em> coordinate does not clear it, because the source answering for one coordinate
 *     says nothing about the one it could not answer for.</li>
 * <li><b>Ordering / determinism.</b> {@link #combined} keeps the <em>lowest</em> overall score per coordinate, so it
 *     is order-insensitive by construction and consulting a second source can only lower a coordinate's health, never
 *     inflate it. Freshness folds conservatively beside it ({@link Freshness#merged}): authoritative only when every
 *     source is, and as old as the oldest. Which sources a deployment installs changes what is reported; the order
 *     they were discovered in never does.</li>
 * <li><b>Tenant scoping (&sect;6).</b> None, deliberately: how well a project is maintained is the same fact for
 *     every tenant. {@link SignalSourceProvider} carries no tenant and offers no way to supply one.</li>
 * </ol>
 */
public interface HealthSource extends SignalSource {

    /** The maintainer-health of a coordinate, looked up from its source repository; {@link Optional#empty() empty}
     *  when the coordinate has no resolvable source or the source scores it nothing. A source degrades gracefully:
     *  an unscored coordinate is absent, never an exception - which is also what an outage looks like, so a caller
     *  that gates reads {@link SignalSource#freshness()} to tell the two apart. */
    Optional<Health> health(String ecosystem, String coordinate);

    /**
     * One coordinate's maintainer-health on the OpenSSF Scorecard scale (0 worst .. 10 best): the {@code overall}
     * score, the three component signals a dependency-health review turns on - {@code maintenance} (is the project
     * still actively maintained), {@code review} (are its changes code-reviewed) and {@code provenance} (are its
     * releases signed / built verifiably) - and the {@code sourceRepository} the score was computed on. A component
     * the source could not evaluate is {@link #NOT_EVALUATED} ({@code < 0}), told apart from a genuine zero.
     */
    record Health(String sourceRepository, double overall, double maintenance, double review, double provenance) {

        /** A component the source could not evaluate (Scorecard's {@code -1}), distinct from a real zero. */
        public static final double NOT_EVALUATED = -1.0;

        /** The prioritisation weight of this health - higher is more urgent, so a finding on a poorly-maintained
         *  coordinate ranks above one on a healthy coordinate. The inverse of the overall score on the same 0..10
         *  scale, the single scalar the gate and a ranking surface share (mirroring {@code Reachability.rank()}). */
        public double rank() {
            return 10.0 - overall;
        }

        /** A compact one-line rendering for a finding detail: the overall score, the evaluated components and the
         *  source repository the score was computed on. */
        public String display() {
            StringBuilder text = new StringBuilder("maintainer-health ").append(format(overall)).append("/10");
            List<String> parts = new ArrayList<>();
            if (maintenance >= 0) {
                parts.add("maintenance " + format(maintenance));
            }
            if (review >= 0) {
                parts.add("review " + format(review));
            }
            if (provenance >= 0) {
                parts.add("provenance " + format(provenance));
            }
            if (!parts.isEmpty()) {
                text.append(" (").append(String.join(", ", parts)).append(')');
            }
            if (sourceRepository != null && !sourceRepository.isBlank()) {
                text.append(" - ").append(sourceRepository.strip());
            }
            return text.toString();
        }

        private static String format(double score) {
            return String.format(Locale.ROOT, "%.1f", score);
        }
    }

    /** The shared source that scores nothing, leaving a review to rank on severity and reachability alone. It is a
     *  singleton so a caller can tell "no health source is active" by identity
     *  ({@code source == HealthSource.none()}). Its freshness is {@link Freshness#NEVER}: no source was consulted,
     *  so an unrated coordinate confirms nothing. */
    HealthSource NONE = new HealthSource() {

        @Override
        public Optional<Health> health(String ecosystem, String coordinate) {
            return Optional.empty();
        }

        @Override
        public Freshness freshness() {
            return Freshness.NEVER;
        }
    };

    static HealthSource none() {
        return NONE;
    }

    /** Merge several health sources, keeping per coordinate the lowest (most conservative) overall score - a
     *  membership-style, order-independent merge, so consulting a second source can only lower a coordinate's health,
     *  never inflate it. A coordinate no source scores stays absent, and freshness folds conservatively beside the
     *  score: authoritative only when every source is, and as old as the oldest. */
    static HealthSource combined(HealthSource... sources) {
        List<HealthSource> models = List.of(sources);
        return new HealthSource() {

            @Override
            public Optional<Health> health(String ecosystem, String coordinate) {
                Health lowest = null;
                for (HealthSource model : models) {
                    Health candidate = model.health(ecosystem, coordinate).orElse(null);
                    if (candidate != null && (lowest == null || candidate.overall() < lowest.overall())) {
                        lowest = candidate;
                    }
                }
                return Optional.ofNullable(lowest);
            }

            @Override
            public Freshness freshness() {
                return Freshness.merged(models.stream().map(SignalSource::freshness).toList());
            }
        };
    }

    /** A fixed table of coordinate to health, for tests and small mirrors; the ecosystem is not part of the key. It
     *  fetches nothing, so its freshness is {@link Freshness#FIXED} - authoritative, with no fetch instant. */
    static HealthSource of(Map<String, Health> byCoordinate) {
        Map<String, Health> copy = Map.copyOf(byCoordinate);
        return new HealthSource() {

            @Override
            public Optional<Health> health(String ecosystem, String coordinate) {
                return Optional.ofNullable(copy.get(coordinate));
            }

            @Override
            public Freshness freshness() {
                return Freshness.FIXED;
            }
        };
    }

    /** The source names installed on this deployment, regardless of enablement.
     *
     *  <p>The console lists the maintainer-health page only where one is installed: the page renders the durable
     *  ledger the sweep writes, which is what a read must render (&sect;10), and a ledger nothing fills would only
     *  ever say that nothing had scored it. */
    static Set<String> installed() {
        return SignalSourceProvider.installed(HealthSource.class);
    }

    /** Every enabled source discovered via {@link ServiceLoader}, merged (lowest score per coordinate) into one; the
     *  {@link HealthSource#none() none} source when none is enabled. */
    static HealthSource resolve(UnaryOperator<String> config) {
        Collection<HealthSource> sources = SignalSourceProvider.named(HealthSource.class, config).values();
        return switch (sources.size()) {
            case 0 -> HealthSource.none();
            case 1 -> sources.iterator().next();
            default -> HealthSource.combined(sources.toArray(HealthSource[]::new));
        };
    }
}
