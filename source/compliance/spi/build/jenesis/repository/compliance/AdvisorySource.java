package build.jenesis.repository.compliance;

import module java.base;

/**
 * Supplies the known vulnerabilities affecting a package, looked up by its ecosystem and its ecosystem-neutral
 * coordinate (the canonical package name - Maven {@code group:artifact}, an npm/PyPI name, a Go module path). The
 * gate is blind to where they come from - an OSV-backed source over the network, a mirrored advisory feed, or a
 * fixed map in a test - so the policy and the advisory client evolve independently. The OSV/CVSS network source is a
 * separate implementation of this seam. A feed module contributes an implementation through
 * {@link SignalSourceProvider}, opting into this contract by implementing it; {@link #resolve} merges every enabled
 * feed into one de-duplicated source, mirroring {@code ArtifactStoreProvider} for the storage backends.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> One instance is shared by the publish gate, the scheduled re-analysis sweep and every
 *     console panel, and they call {@link #advisories} concurrently. An implementation that caches must do so under
 *     a memory model that lets a reader see a completed refresh whole - never a half-filled map.</li>
 * <li><b>Idempotency / replay.</b> {@link #advisories} is a question, not a command: asking twice for the same
 *     coordinate must yield equal advisories, because a publish is re-screened on migration and on re-analysis and a
 *     drifting answer would change a stored verdict without the artifact having changed. Two calls may legitimately
 *     cost one upstream query (a warm cache) or two; what may not differ is the answer.</li>
 * <li><b>Absence sentinel.</b> An empty list means "this feed screened the coordinate and found nothing", and
 *     {@code null} is never legal. "No feed is active" is the identity-comparable {@link #NONE}, which
 *     {@link #resolve} folds an empty candidate set into - a caller can therefore tell an unscreened deployment from
 *     a clean package by identity, which no list value could express.</li>
 * <li><b>Error visibility (&sect;9) - fail closed.</b> This is the clause the whole family turns on. A feed that
 *     cannot answer must <em>raise</em>, never return the empty list: an outage that reads as "no advisories" is
 *     indistinguishable from a clean package, and a vulnerability nobody reported would publish. That covers a
 *     rejected status (an exhausted rate limit, a refused credential), a body the parser cannot read, and a body
 *     that is well formed but arrived under a bad status - the last of which is why an implementation checks the
 *     status <em>before</em> it parses. Contrast {@link ExploitProbabilitySource}, whose absent score is safe and
 *     which therefore fails soft.
 *     <p><b>A warm cache is not an exemption, and this is the half that used to be left unsaid</b>. An
 *     implementation may hold an answer for a declared window and serve it without asking again - that is what a
 *     window <em>is</em>, and the gate is entitled to an answer up to that old. What it may not do is serve an answer
 *     from <em>past</em> that window because the refresh that should have replaced it failed. Nobody screened the
 *     coordinate since the window lapsed, so the emptiness the gate reads is not a screen result; a newly published
 *     advisory is invisible for exactly as long as the vendor is down, which is the case this clause exists for. So
 *     the raise covers the warm cache exactly as it covers the cold one, and the bound is the implementation's own
 *     window: <b>an answer this contract yields is never older than the window the implementation declares, and past
 *     it the failure is raised.</b>
 *     <p>The aged list is not thrown away so much as <em>subsumed</em>. Raising blocks the publish, which is a
 *     superset of everything the aged list would have blocked, so nothing it could still have told the gate is lost -
 *     the raise strictly dominates serving it. That is why this clause collapses "the data has aged" and "there is no
 *     data" although they are different evidence: for a screen read in the direction of blocking, they license the
 *     same action. The fail-<em>soft</em> contracts reach the opposite conclusion from the same distinction and keep
 *     their aged value ({@link KnownExploitedSource} clause 6, {@link HealthSource} clause 7), because there the
 *     neutral answer is the loosest one and discarding aged evidence for it would loosen rather than tighten.
 *     {@link FeedCache#failClosed} is where an implementation takes this position rather than re-deriving it.</li>
 * <li><b>Bounded work / cancellation (&sect;12).</b> A paginated feed draws every page before it answers - an
 *     advisory that only appears on page two of a widely-affected package must not be invisible to the gate - and
 *     bounds that draw with a page cap, a per-request timeout, a whole-fetch deadline and a response byte cap.
 *     Reaching any of them is a <em>named failure</em>, never a shorter list: a plausible-but-incomplete answer is
 *     worse than an outage, because the gate cannot tell it from a clean package.</li>
 * <li><b>Read purity (&sect;10).</b> The intent is that {@link #advisories} renders what a refresh already stored, so
 *     a gate decision stands while the vendor is down. <b>No implementation meets this today</b>: every
 *     installed feed fetches on the query path - two of the eight feeds with no cache at all, the rest behind
 *     a process-local TTL cache that dies with the JVM - so an advisory answer currently depends on the vendor being
 *     reachable, and clause 4 is what keeps that honest rather than dangerous. Recorded here rather than left
 *     implicit, because an undocumented divergence on a shared concern is a defect (&sect;13); /d migrate the
 *     feeds onto the feed client's snapshot path, which is where this clause becomes true.</li>
 * <li><b>Staleness.</b> {@link SignalSource#freshness()} carries it, for this contract as for the whole family: a
 *     consumer reads the instant the advisories behind an answer were fetched, so an empty list beside a fetch
 *     instant is "screened, nothing found" and an empty list beside {@link Freshness#NEVER} is "this feed has never
 *     answered" (&sect;10). It does not replace clause 4 and must not be read as softening it - an unreachable feed
 *     still <em>raises</em> rather than answering emptily, so the gate never sees the ambiguous pair at all. What
 *     freshness adds here is the display half: a console rendering "no advisories" can say when that was last true,
 *     and the identity-comparable {@link #NONE} reports {@link Freshness#NEVER} so an unscreened deployment reads as
 *     unscreened rather than as clean.
 *     <p><b>Both halves are derived per coordinate, not from whichever lookup finished last</b>. A
 *     coordinate this feed could not screen holds {@link Freshness#authoritative()} down until that coordinate
 *     screens again or its retry window lapses, and never clears because a <em>different</em> coordinate answered -
 *     the vendor having answered for B says nothing about the A it could not answer for. Recording successes only is
 *     the shape this forbids: it leaves a feed that has failed every lookup for three days rendering as authoritative
 *     beside a three-day-old instant, which is exactly the ambiguity &sect;10 exists to remove.
 *     {@link FreshnessTracker} is where an implementation takes this rather than re-deriving it.</p></li>
 * <li><b>Ordering / determinism.</b> {@link #combined} merges feeds by advisory id and CVE alias, keeping the richer
 *     record when two feeds report the same vulnerability, and is order-insensitive by construction - so which feeds
 *     a deployment installs changes what is reported, but the order they were discovered in never does.</li>
 * <li><b>Tenant scoping (&sect;6).</b> None, deliberately: an advisory about a package is the same fact for every
 *     tenant. {@link SignalSourceProvider} carries no tenant and offers no way to supply one.</li>
 * </ol>
 */
public interface AdvisorySource extends SignalSource {

    /**
     * A known vulnerability affecting a coordinate: its identifier, assessed severity, the version (or versions,
     * comma-separated) that fix it where the feed records one, and whether the feed classifies it as a malicious
     * package - a deliberately harmful publication rather than a flaw in a legitimate one. A malicious advisory
     * carries no meaningful CVSS score, so it is gated on the malicious flag rather than the severity threshold.
     * {@code fixed} is {@code null} when the feed names no fixed version (an unpatched advisory). {@code cves} are
     * the advisory's CVE aliases (empty when it has none), used to cross-reference the known-exploited catalogue.
     * {@code description} is the feed's human-readable summary (empty when it records none) - carried so the
     * findings ledger can persist what the advisory says without a display surface re-fetching the feed.
     */
    record Advisory(String id, Severity severity, boolean malicious, String fixed, List<String> cves,
                    String description) {

        /**
         * The bound on the long-form text a feed carries into the findings ledger. Every feed answers with both a
         * one-line summary and an unbounded long-form body (details, description, Markdown), and the ledger persists
         * one string per finding - so the long form is taken as a bounded prefix, or a single verbose advisory would
         * bloat every record that quotes it.
         */
        public static final int DESCRIPTION_LIMIT = 1000;

        public Advisory {
            description = description == null ? "" : description;
        }

        public Advisory(String id, Severity severity) {
            this(id, severity, false, null, List.of(), "");
        }

        public Advisory(String id, Severity severity, boolean malicious) {
            this(id, severity, malicious, null, List.of(), "");
        }

        public Advisory(String id, Severity severity, boolean malicious, String fixed) {
            this(id, severity, malicious, fixed, List.of(), "");
        }

        public Advisory(String id, Severity severity, boolean malicious, String fixed, List<String> cves) {
            this(id, severity, malicious, fixed, cves, "");
        }

        /**
         * The description one feed record carries into the ledger: the vendor's one-line summary where it has a
         * non-blank one, else a bounded {@value #DESCRIPTION_LIMIT}-character prefix of the long-form text, else
         * empty. Every feed makes exactly this choice under its own vendor's field names ({@code summary}/{@code
         * details}, {@code summary}/{@code description}, {@code title}/{@code description}), which is why the shape
         * lives here and only the field names stay with the feed.
         */
        public static String description(String summary, String longForm) {
            if (summary != null && !summary.isBlank()) {
                return summary;
            }
            if (longForm == null) {
                return "";
            }
            return longForm.length() > DESCRIPTION_LIMIT ? longForm.substring(0, DESCRIPTION_LIMIT) : longForm;
        }
    }

    List<Advisory> advisories(String ecosystem, String coordinate, String version);

    /** The shared source that reports nothing, for deployments that gate on licenses only. It is a singleton so a
     *  caller can tell "no advisory feed is active" by identity ({@code source == AdvisorySource.none()}). Its
     *  freshness is {@link Freshness#NEVER}: no feed was consulted, so the empty list confirms nothing. */
    AdvisorySource NONE = new AdvisorySource() {

        @Override
        public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
            return List.of();
        }

        @Override
        public Freshness freshness() {
            return Freshness.NEVER;
        }
    };

    static AdvisorySource none() {
        return NONE;
    }

    /** A fixed source keyed by {@code coordinate}, version- and ecosystem-insensitive - for tests and small mirrors.
     *  It fetches nothing, so its freshness is {@link Freshness#FIXED}: authoritative, with no fetch instant. */
    static AdvisorySource of(Map<String, List<Advisory>> byCoordinate) {
        Map<String, List<Advisory>> copy = Map.copyOf(byCoordinate);
        return new AdvisorySource() {

            @Override
            public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
                return copy.getOrDefault(coordinate, List.of());
            }

            @Override
            public Freshness freshness() {
                return Freshness.FIXED;
            }
        };
    }

    /** The feed names installed on this deployment, regardless of enablement - the capability signal a console
     *  or API gates its surface on. */
    static Set<String> installed() {
        return SignalSourceProvider.installed(AdvisorySource.class);
    }

    /** Every enabled feed discovered via {@link ServiceLoader}, keyed by its provider name in a deterministic
     *  name-sorted order - the attributed view of the same feeds {@link #resolve} merges, for a caller (the findings
     *  ledger) that must record <em>which</em> feed reported an advisory rather than a de-duplicated union. Empty
     *  when none is enabled. */
    static SequencedMap<String, AdvisorySource> named(UnaryOperator<String> config) {
        return SignalSourceProvider.named(AdvisorySource.class, config);
    }

    /** Every enabled feed discovered via {@link ServiceLoader}, merged (de-duplicated) into one source; the
     *  {@link AdvisorySource#none() none} source when none is enabled. */
    static AdvisorySource resolve(UnaryOperator<String> config) {
        return resolve(named(config).values());
    }

    /** The de-duplicated union of an already-resolved set of feeds - the {@link #resolve(UnaryOperator)} merge over a
     *  caller's own {@link #named} map, so a deployment that must both share the feed instances (the gate assessing
     *  through this union while the publish screen re-queries the same instances at commit for a warm cache read) and
     *  keep their attributed view builds each feed once and derives both from it. The {@link AdvisorySource#none()
     *  none} source when the set is empty, the single feed unwrapped when there is one. */
    static AdvisorySource resolve(Collection<AdvisorySource> feeds) {
        return switch (feeds.size()) {
            case 0 -> AdvisorySource.none();
            case 1 -> feeds.iterator().next();
            default -> AdvisorySource.combined(feeds.toArray(AdvisorySource[]::new));
        };
    }

    /** Merge several feeds (OSV, the GitHub Advisory Database, a licensed feed, ...), reporting each vulnerability
     *  once even when more than one feed carries it - de-duplicated by advisory id and by any shared CVE alias, so
     *  the same flaw that OSV names by its CVE and GitHub by its GHSA counts a single time. A duplicate report is
     *  merged, never dropped: the advisory keeps the first feed's id and gains the richer fields of every later
     *  report - the higher severity, a fixed version where the first knew none, the fuller description, the union
     *  of CVE aliases, the malicious flag if either sets it - so a feed that knows more about the same
     *  vulnerability enriches the record instead of losing to whichever feed happened to answer first. */
    static AdvisorySource combined(AdvisorySource... sources) {
        List<AdvisorySource> feeds = List.of(sources);
        return new AdvisorySource() {

            @Override
            public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
                List<Advisory> merged = new ArrayList<>();
                Map<String, Integer> known = new HashMap<>();
                for (AdvisorySource feed : feeds) {
                    for (Advisory advisory : feed.advisories(ecosystem, coordinate, version)) {
                        Set<String> identifiers = new LinkedHashSet<>(advisory.cves());
                        identifiers.add(advisory.id());
                        Integer at = identifiers.stream()
                                .map(known::get)
                                .filter(Objects::nonNull)
                                .findFirst()
                                .orElse(null);
                        if (at == null) {
                            at = merged.size();
                            merged.add(advisory);
                        } else {
                            merged.set(at, enriched(merged.get(at), advisory));
                        }
                        for (String identifier : identifiers) {
                            known.putIfAbsent(identifier, at);
                        }
                    }
                }
                return merged;
            }

            /** The conservative fold: authoritative only when every feed is, and as old as the oldest of them. */
            @Override
            public Freshness freshness() {
                return Freshness.merged(feeds.stream().map(SignalSource::freshness).toList());
            }
        };
    }

    // The field-wise merge of two reports of one vulnerability: the id stays the first feed's (stable however the
    // later feeds answer), every other field prefers the richer value.
    private static Advisory enriched(Advisory first, Advisory addition) {
        List<String> cves = new ArrayList<>(first.cves());
        for (String cve : addition.cves()) {
            if (!cves.contains(cve)) {
                cves.add(cve);
            }
        }
        return new Advisory(first.id(),
                // strongest(), not compareTo: UNKNOWN sorts above CRITICAL so a policy floor fails
                // closed against it, but a merge must not let a source that could not score an
                // advisory erase one that did.
                Severity.strongest(first.severity(), addition.severity()),
                first.malicious() || addition.malicious(),
                first.fixed() == null ? addition.fixed() : first.fixed(),
                cves,
                addition.description().length() > first.description().length()
                        ? addition.description()
                        : first.description());
    }
}
