package build.jenesis.repository.server.spi;

import module java.base;

/**
 * A core extension point for the deployment-wide {@code /api/capabilities} surface, discovered at runtime with
 * {@link ServiceLoader} - so a richer distribution advertises its extra capabilities (a downstream edition's supported
 * formats, import sources, module flags) on the <em>one</em> free-served {@code /api/capabilities} endpoint without a
 * bean override and without a client change. This is exactly the intent the free {@code RepositoryController#capabilities}
 * javadoc has always stated: "a distribution with more capabilities extends the map without a client change".
 *
 * <p>The free {@code RepositoryController} builds its base map ({@code readOnly}, {@code auth}, {@code anonymousRights}),
 * then {@link #merge merges} every discovered contributor into it. With no contributor installed - the free product -
 * the served map is exactly the base map, byte-for-byte unchanged. A distribution adds capabilities simply by shipping
 * a module that {@code provides build.jenesis.repository.server.spi.CapabilityContributor with ...}; the server already
 * {@code uses} it, so no core change is needed. It replaces the former {@code WebMvcRegistrations} mapping-suppression
 * stopgap that dropped the free mapping so a downstream controller could own the same path.
 *
 * <h2>Merge / precedence rule</h2>
 * Contributors <b>extend</b> the base map; they never shadow it. On a key conflict the <b>base key always wins</b>, and
 * among contributors the <b>first in class-name order wins</b> (see {@link #merge}). This guarantees the free
 * product's own flags - the read-only gate, the auth flag, the anonymous grant - can never be overwritten by a
 * contributor, so the base semantics of {@code /api/capabilities} are preserved whatever a distribution adds. New
 * (non-conflicting) keys are appended after the base keys, in contributor class-name order.
 *
 * <h2>A losing contribution is reported, never dropped in silence</h2>
 * The precedence rule decides <em>which value is served</em>. It does not license saying nothing about the value that
 * lost. A key a contributor meant to publish and the endpoint does not serve is a deployment defect - a misspelled key,
 * a concept the free base has since claimed, two distributions colliding - and an operator who cannot see it debugs a
 * console that renders the wrong thing with nothing anywhere to explain why. So {@link #merge} keeps the base-wins
 * outcome and additionally <b>names every contribution it refused</b>, in the returned {@link Merged} report and in the
 * served body itself, under {@value #CONFLICTS_KEY}.
 *
 * <p><b>Why it reports rather than throws.</b> Throwing on a collision was the other candidate, and on the face of it
 * the more fail-fast one (&sect;9). It loses on where this code runs. {@code merge} is not a resolution or startup step
 * that could refuse a bad deployment before it serves anything; it runs <em>inside the request</em>, on every GET of
 * {@code /api/capabilities}. An exception there is not a boot failure that an operator fixes once - it is a permanent
 * 500 on the one endpoint whose entire job is to tell a client what this deployment can do, and the first casualties
 * are the free base flags the precedence rule exists to protect: a console could no longer learn that the deployment is
 * read-only or that auth is on, because an optional plugin misspelled a key. That is precisely &sect;3's line - an
 * optional module must never be able to take a core surface down, its presence no more than its absence - and a
 * contributor's key-naming mistake is not worth the free product's own capability advertisement.
 *
 * <p>Reporting satisfies &sect;9 on its own terms: the silent-drop &sect;9 forbids is a failure whose loss changes what
 * is served with nothing logged, countered or surfaced, and this is logged, returned as data, and surfaced in the body.
 * It is also the shape the plan's bound-visibility gate already prescribes for exactly this class of outcome - an
 * explicit result the caller cannot miss, rather than an exception - and it leaves fail-fast available where fail-fast
 * is safe: a contributor that knows a priori which keys it may not claim can still refuse at the point it builds its
 * contribution, long before the merge sees it.
 *
 * <p>The same reasoning covers a contributor that <em>throws</em> from {@link #capabilities}: the exception is
 * contained, the contributor is named under {@value #FAILURES_KEY}, and the rest of the map is served. Containing it is
 * not swallowing it - nothing about the failure is lost, it is simply delivered as data instead of as a dead endpoint.
 * The one failure that is deliberately <em>not</em> contained here is a contributor that will not instantiate at all:
 * that is a {@link ServiceLoader} resolution failure over a module path the operator assembled, it raises before this
 * method is reached, and per &sect;9 a selected-but-unusable module must fail loudly rather than quietly serve less.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> The server discovers and calls contributors on the request thread and may do so
 *       concurrently for concurrent requests; an implementation must be safe to call from several threads at once. It
 *       is handed no shared mutable state and must introduce none - {@link #capabilities} builds and returns a fresh
 *       map, and the map passed to {@link #merge} is never mutated by a contributor.</li>
 *   <li><b>Idempotency / replay.</b> {@link #capabilities} is called once per request, not once per boot. Two calls
 *       over an unchanged deployment must return equal maps; a call must have no side effect, so re-serving the
 *       endpoint any number of times changes nothing.</li>
 *   <li><b>Absence sentinel.</b> "Nothing to contribute" is an empty map (a {@code null} return is tolerated and means
 *       the same). It is never an exception and never a {@code null} value under a key. A contributor whose backing
 *       capability is not installed contributes nothing and the base map is served unchanged - the SPI's
 *       no-op-by-absence guarantee.</li>
 *   <li><b>Selection failure.</b> There is no selection: the policy is additive, every discovered contributor is
 *       merged, and there is no configuration key that names one. A contributor module the operator put on the path
 *       but that cannot be instantiated fails at {@link ServiceLoader} resolution and is not silently skipped
 *       (&sect;9); a contributor that instantiates but cannot compute is contained and reported (see above).
 *       <p>Unlike the named singleton SPIs beside it, this one does <em>not</em> resolve through the shared
 *       {@code Providers} primitives. Not because it could not be keyed: {@code Providers.all} takes the name as a
 *       function rather than requiring a {@code name()} method, and this merge already computes a per-contributor
 *       identity for its own reports, so keying it is available. What it must not adopt is
 *       {@code Providers.validated}'s refusal, which turns a duplicate provider class into a throw - and this
 *       merge runs inside the request, where throwing costs the endpoint (see above). The ordering those
 *       primitives give is adopted directly instead, by name-sorting here. Two consequences follow and are stated rather
 *       than assumed. First, the packaging guards {@code Providers} applies to every other family are absent here: a
 *       contributor registered twice, or two distributions shipping the same contribution, are not refused at
 *       resolution - they surface instead as this merge's own conflict report, which is the weaker but per-key
 *       signal. Second, the discovery site is this module's own {@link #resolve} static, like the sibling families'
 *       {@code resolve}/{@code installed} statics, so the {@code uses} clause lives in this module;
 *       a second load site for this SPI would be a second discovery pipeline and is forbidden.</li>
 *   <li><b>Error visibility.</b> No outcome of a merge is silent. A contribution that loses a key to the base or to an
 *       earlier contributor is reported in {@link Merged#conflicts()} and under {@value #CONFLICTS_KEY}; a contributor
 *       that throws is reported in {@link Merged#failures()} and under {@value #FAILURES_KEY}. Both are additionally
 *       logged by the serving controller. The blast radius of either is confined to the losing entry: the base map and
 *       every other contribution are served intact.</li>
 *   <li><b>Read purity.</b> {@link #capabilities} answers a GET, so it renders state the process already holds -
 *       installed modules, resolved settings, discovered providers. It performs no external fetch, no scan and no
 *       write, and the endpoint must still answer when an upstream a capability describes is down (&sect;10).</li>
 *   <li><b>Lifecycle / ownership.</b> Instances are created by {@link ServiceLoader} from a public no-arg constructor
 *       and are not cached across requests, so a contributor owns no threads, clients or connections and has nothing
 *       to close. A contributor needing live application state bridges it in through a static holder rather than
 *       holding it itself.</li>
 *   <li><b>Ordering / concurrency.</b> The base map's keys come first in their insertion order; contributed keys are
 *       appended in <b>contributor-class-name order</b>, then the diagnostic keys last. Among contributors the
 *       first in that order wins a contested key. The reports are deterministic: conflicts appear in contributor class-name order and, within one
 *       contribution, sorted by key, so an unordered contributed map cannot make the report shuffle between
 *       requests. The determinism now holds <em>across module paths</em> too, which it did not while the winner
 *       was whichever module the loader happened to see first: two distributions claiming one key still must not,
 *       but when they do they now disagree reproducibly rather than per deployment. The conflict report is emitted
 *       either way - it was never the alternative to a stable winner, only the thing that makes the collision
 *       visible once there is one.</li>
 *   <li><b>Bounded work / cancellation.</b> {@link #capabilities} is on the request path and must be cheap and
 *       bounded - a handful of already-known flags, not an enumeration of stored artifacts. It is given no
 *       cancellation signal, so it must not block.</li>
 *   <li><b>Durability / delivery.</b> Nothing here is durable. The merged map is derived afresh per request from the
 *       installed module set and the live settings; there is no commit point, no crash window and nothing to heal.
 *       {@value #CONFLICTS_KEY} and {@value #FAILURES_KEY} are likewise derived, so a fixed deployment stops reporting
 *       on its very next request with nothing to clear.</li>
 *   <li><b>Stable key and type.</b> A contributed key is part of the wire. The CLI and the console read
 *       {@code gc}, {@code walk}, {@code search}, {@code dependents}, {@code scan}, {@code provenance} and
 *       {@code audit} by name, so a flag key is a lowercase, dot-free identifier and is <b>never renamed</b>: a
 *       rename reads to every client as "the capability is gone", because a missing key is {@code false}. A
 *       capability that outgrows "present/enabled" becomes a differently-named entry rather than a re-typed flag,
 *       so a client that understands the old key keeps working.</li>
 *   <li><b>Live resolution.</b> A contributor resolves its answer from the {@code configuration} operator it is
 *       handed on each call rather than pinning it at construction, so a selection or a required setting that turns
 *       the capability off shows up on the next read without a restart. The operator is the serving surface's own
 *       effective-value chain; a contributor neither builds one nor reaches for a static holder to find one.</li>
 * </ol>
 * Clauses 5 (streaming), 6 (tenant scoping) and 9 (staleness) do not apply: nothing here streams, the surface is
 * deployment-global by definition rather than tenant-scoped (a contributor must therefore put no per-tenant data in
 * it), and every value is computed live rather than fetched and cached.
 */
public interface CapabilityContributor {

    /** The served key under which {@link #merge} names the contributions it refused - the entries a base key or an
     *  earlier contributor already owned, each with the key, the contributor that lost it and who holds the served
     *  value. Absent from the body when there is nothing to report, so the free product's zero-contributor map is
     *  byte-for-byte unchanged and a healthy deployment serves no diagnostic noise. The merge owns this key: a
     *  contributor claiming it is itself reported as a conflict rather than being allowed to forge the report. */
    String CONFLICTS_KEY = "capabilityConflicts";

    /** The served key under which {@link #merge} names contributors that threw while building their contribution, each
     *  with the failure. Absent when there is nothing to report, on the same terms as {@value #CONFLICTS_KEY}, and
     *  reserved from contributors on the same terms. */
    String FAILURES_KEY = "capabilityFailures";

    /**
     * The capabilities this contributor adds to {@code /api/capabilities}. Values must be JSON-serialisable (a boolean,
     * a string, a number, a list or a nested map), the same shape the base map uses. Returning an empty map (or
     * {@code null}) contributes nothing. Never mutate the map passed to {@link #merge}; return a fresh map of the extra
     * entries.
     *
     * <p>Keys are a shared namespace: one a base key or an earlier contributor already owns will not be served, and the
     * loss is reported rather than silently absorbed (see the merge rule above), so name a key for the concept it
     * carries and prefix it where a collision with another distribution is plausible.
     *
     * @param configuration the serving surface's effective-value chain for a setting key, resolved live per call
     *                      (clause 12). A contributor that reports nothing configuration-dependent ignores it.
     */
    Map<String, Object> capabilities(UnaryOperator<String> configuration);

    /**
     * Discover every installed contributor and {@linkplain #merge merge} it onto {@code base}, resolving each
     * against {@code configuration}. This is the <b>one</b> discovery site for this SPI, which is why the
     * {@code uses} clause lives in this module beside the sibling families' {@code resolve} statics rather than in
     * whichever module happens to serve a surface: a second {@code ServiceLoader.load} elsewhere would be a second
     * discovery pipeline, and two pipelines over one contributor set is how two surfaces come to disagree about a
     * flag (clause 4). Every consumer - the serving controller and the console's own module-presence gate - calls
     * this, so a flag has exactly one definition and one answer.
     *
     * @param base          the caller's own entries, which win every conflict.
     * @param configuration the caller's effective-value chain, handed to each contributor.
     */
    static Merged resolve(Map<String, Object> base, UnaryOperator<String> configuration) {
        return merge(base, ServiceLoader.load(CapabilityContributor.class), configuration);
    }

    /**
     * Merge every {@code contributor}'s {@link #capabilities} into a copy of {@code base}, applying the documented
     * precedence rule: a base key always wins a conflict, and among contributors the first in class-name order wins.
     * The base keys keep their insertion order first; new keys are appended in contributor class-name order. When
     * {@code contributors} is empty the returned map equals {@code base} exactly (same keys, same order, same values) -
     * the free product's byte-for-byte-unchanged guarantee.
     *
     * <p>Every entry the rule refuses is <b>named</b> rather than dropped: the returned {@link Merged} carries the
     * conflicts and the contributor failures as data, and - when there are any - the served map carries them too under
     * {@value #CONFLICTS_KEY} and {@value #FAILURES_KEY}. This method therefore never throws on account of a
     * contributor: a contributor that throws from {@link #capabilities} is contained and reported, because a plugin's
     * mistake must not take down the endpoint that advertises the free product's own flags.
     *
     * @param configuration handed to every contributor, so each resolves its answer through the calling surface's
     *                      own effective-value chain rather than composing a second one.
     */
    static Merged merge(Map<String, Object> base, Iterable<CapabilityContributor> contributors,
                        UnaryOperator<String> configuration) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        // Who owns each key that is already spoken for, so a refused contribution can say what beat it rather than
        // only that it lost. The two diagnostic keys are reserved here, not written here: a contributor claiming one
        // is refused like any other collision, which is what stops a contribution forging its own clean bill of health.
        Map<String, String> owners = new LinkedHashMap<>();
        base.keySet().forEach(key -> owners.put(key, Conflict.BASE));
        owners.putIfAbsent(CONFLICTS_KEY, Conflict.RESERVED);
        owners.putIfAbsent(FAILURES_KEY, Conflict.RESERVED);

        List<Conflict> conflicts = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        // Name-sorted, not discovery-ordered. Whoever wins a contested key must win it on every module path, and
        // the sort key is the identity this merge already computes for its own reports - so a stable winner costs
        // nothing that was not being calculated anyway. Discovery order made two distributions claiming one key
        // disagree PER DEPLOYMENT, which is the hardest kind of disagreement to reproduce; the conflict report is
        // emitted either way, so a stable winner plus the report is strictly better than an unstable one plus the
        // report rather than an alternative to it.
        List<CapabilityContributor> ordered = new ArrayList<>();
        contributors.forEach(ordered::add);
        ordered.sort(Comparator.comparing(contributor -> contributor.getClass().getName()));
        for (CapabilityContributor contributor : ordered) {
            String name = contributor.getClass().getName();
            Map<String, Object> contribution;
            try {
                contribution = contributor.capabilities(configuration);
            } catch (RuntimeException exception) {
                // Contained, not swallowed: the contributor is named with its failure and the rest of the map is
                // served. An optional plugin that cannot build its view must not cost the deployment the endpoint.
                failures.add(new Failure(name, describe(exception)));
                continue;
            }
            if (contribution == null || contribution.isEmpty()) {
                continue;
            }
            List<Conflict> refused = new ArrayList<>();
            for (Map.Entry<String, Object> entry : contribution.entrySet()) {
                String owner = owners.get(entry.getKey());
                if (owner != null) {
                    refused.add(new Conflict(entry.getKey(), name, owner));
                } else {
                    merged.put(entry.getKey(), entry.getValue());
                    owners.put(entry.getKey(), name);
                }
            }
            // Sorted within the contribution so an unordered contributed map (a Map.of, typically) cannot make the
            // report shuffle between two requests over an unchanged deployment - clause 8's determinism.
            refused.sort(Comparator.comparing(Conflict::key));
            conflicts.addAll(refused);
        }

        if (!conflicts.isEmpty()) {
            merged.put(CONFLICTS_KEY, conflicts.stream().map(Conflict::served).toList());
        }
        if (!failures.isEmpty()) {
            merged.put(FAILURES_KEY, failures.stream().map(Failure::served).toList());
        }
        return new Merged(merged, conflicts, failures);
    }

    /** An exception rendered for the report: the type and its message, never a stack trace - the report is served on an
     *  authenticated API surface and is meant to name the broken contributor, not to hand out internals. */
    private static String describe(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getName()
                : exception.getClass().getName() + ": " + message;
    }

    /**
     * The outcome of a {@link #merge}: the map to serve, plus everything the precedence rule refused along the way.
     * The two report lists are the reason this is a record rather than a bare map - a caller that only took the map
     * would be back to a silent drop, and the compiler now hands it the drops whether it looked for them or not.
     */
    record Merged(Map<String, Object> capabilities, List<Conflict> conflicts, List<Failure> failures) {

        public Merged {
            // Insertion order is part of the served contract (base keys first), so the defensive copy has to preserve
            // it - Map.copyOf would not.
            capabilities = Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
            conflicts = List.copyOf(conflicts);
            failures = List.copyOf(failures);
        }

        /** Whether every discovered contribution was merged intact - nothing refused, nothing failed. The free
         *  product with no contributor installed, and any correctly-configured distribution, is intact. */
        public boolean intact() {
            return conflicts.isEmpty() && failures.isEmpty();
        }

        /** The report as log-ready lines, one per refused or failed contribution, so the serving controller renders it
         *  the same way wherever it is logged and a second caller does not invent a second wording. Empty when
         *  {@link #intact()}. */
        public List<String> report() {
            return Stream.concat(conflicts.stream().map(Conflict::describe), failures.stream().map(Failure::describe))
                    .toList();
        }
    }

    /** A contributed entry the precedence rule refused: the {@code key} it claimed, the {@code contributor} class that
     *  claimed it, and the {@code owner} of the value actually served. */
    record Conflict(String key, String contributor, String owner) {

        /** The {@link #owner()} of a key the free base map holds - the read-only gate, the auth flag, the anonymous
         *  grant. A contributor may extend the base map, never shadow it. */
        public static final String BASE = "base";

        /** The {@link #owner()} of a key the merge itself reserves - {@value CapabilityContributor#CONFLICTS_KEY} and
         *  {@value CapabilityContributor#FAILURES_KEY}, which report on contributions and so cannot be written by
         *  one. */
        public static final String RESERVED = "reserved";

        /** This conflict as the JSON-shaped map the endpoint serves under
         *  {@value CapabilityContributor#CONFLICTS_KEY}. Insertion-ordered so the rendered body is byte-stable across
         *  requests over an unchanged deployment. */
        public Map<String, Object> served() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", key);
            entry.put("contributor", contributor);
            entry.put("owner", owner);
            return Collections.unmodifiableMap(entry);
        }

        /** This conflict as one log-ready line naming what was dropped, who lost it and who holds the served value. */
        public String describe() {
            return "capability key '" + key + "' contributed by " + contributor + " was not served: it is held by "
                    + (BASE.equals(owner) ? "the free base map"
                            : RESERVED.equals(owner) ? "the merge's own report" : "contributor " + owner)
                    + ". A contributor extends /api/capabilities and never shadows it - rename the contributed key, or "
                    + "move the concern into the base map.";
        }
    }

    /** A contributor that threw while building its contribution: the {@code contributor} class and the rendered
     *  {@code error}. Its entries are absent from the served map; every other contribution is served. */
    record Failure(String contributor, String error) {

        /** This failure as the JSON-shaped map the endpoint serves under
         *  {@value CapabilityContributor#FAILURES_KEY}. Insertion-ordered on the same terms as
         *  {@link Conflict#served()}. */
        public Map<String, Object> served() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("contributor", contributor);
            entry.put("error", error);
            return Collections.unmodifiableMap(entry);
        }

        /** This failure as one log-ready line naming the contributor that could not build its view. */
        public String describe() {
            return "capability contributor " + contributor + " failed to build its contribution and was skipped; the "
                    + "rest of /api/capabilities is served unaffected. Cause: " + error;
        }
    }
}
