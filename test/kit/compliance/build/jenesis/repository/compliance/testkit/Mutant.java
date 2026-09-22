package build.jenesis.repository.compliance.testkit;

import module java.base;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.SignalSource;

/**
 * One deliberately broken substitution for the deployment object a {@link SignalContract.Property} is about, injected
 * at the four seams {@link SignalFixture} hands the kit one from - the falsification half of the kit (carrying
 * the earlier mechanism to this second kit).
 *
 * <p><b>Why the kit needs one at all.</b> Every check in {@link SignalContract} states what a feed must do; nothing
 * states that the check <em>could have said otherwise</em>. an earlier change found the sharpest instance in this very module:
 * the no-egress tripwire the read-purity leg measures with is only a complete record while the JDK's failed-lookup
 * cache is off, and with it on the leg answers "this query reached nothing" over a query that reached the vendor -
 * identically, whether or not the offence occurred. That was found by hand. {@link #A_QUERY_THAT_REACHES_A_HOST_ALREADY_REACHED}
 * is that instance made part of the kit.
 *
 * <p><b>A mutant is not a mock.</b> The provider still builds the real source from the real configuration through
 * {@code SignalSourceProvider.named}, the recording is still played back over a real socket, and the tripwire is
 * still the JDK's own resolver. A mutant wraps <em>one</em> of the four deployment objects a fixture hands over and
 * removes exactly one behaviour:
 *
 * <ul>
 *   <li><b>the source</b> - {@link SignalFixture#source} - wrapped in a proxy over the very interfaces the resolved
 *       object implements, so the mutated source is the real one minus one answer;</li>
 *   <li><b>the vendor</b> - the recorded {@code recorded()} / {@code rejected()} / {@code malformed()} /
 *       {@code clean()} / {@code endless()} scripts, which are what a deployment's vendor really answers with;</li>
 *   <li><b>the configuration</b> - {@link SignalFixture#unconfigured()}, the shapes a booting deployment feeds the
 *       provider;</li>
 *   <li><b>the question</b> - {@link SignalFixture#ask} and {@link SignalFixture#lookup}, which is what a gate asks
 *       and where an extra round trip or a second vendor host shows up.</li>
 * </ul>
 *
 * <p>{@link #NONE} is the identity: the unmutated leg every fixture already runs.
 */
public enum Mutant {

    /** Nothing is removed - the deployment's own feed, which is what the contract's ordinary leg drives. */
    NONE("nothing"),

    /**
     * The reach of the shapes a fixture calls unconfigured: every one of them now answers the feed's own
     * <em>production</em> configuration, so the provider is asked to decline a configuration that enables it. This is
     * the "switched off in a report but not in resolution" defect, and it is the only thing the self-skip leg is for.
     */
    AN_UNCONFIGURED_SHAPE_THAT_IS_REALLY_ENABLED("the reach of the unconfigured shapes - every one of them now "
            + "answers the feed's production configuration"),

    /**
     * The factory's freedom from effect: resolving the source also spends one request against the recorded endpoint.
     * A plain GET rather than a query through the source, because what clause 2 forbids is the round trip - a
     * rate-limit token spent, a staleness stamp moved - and routing it through the source would drag the snapshot
     * space into a check that deliberately runs with none bound.
     */
    A_CREATION_THAT_FETCHES("the factory's freedom from effect - resolving the source spends one request"),

    /**
     * The declaration's precision: the created source also answers a {@link SignalSource} contract the fixture never
     * declared. Nothing else changes - it answers everything it answered before - so only a check that really
     * compares {@code signals()} against the object can tell.
     */
    A_SOURCE_THAT_IS_ALSO_SOMETHING_ELSE("the declaration's precision - the source answers one contract more than "
            + "signals() names"),

    /**
     * The vendor's payload: the recording is replaced by the feed's own <em>clean</em> answer, the one it plays for a
     * coordinate the vendor carries nothing for. The feed still parses, still spends its request and still answers -
     * it just answers nothing, which is what a recording that drifted from the vendor's wire shape produces.
     */
    A_VENDOR_THAT_CARRIES_NOTHING("the vendor's payload - the recording now plays the feed's own \"nothing here\" "
            + "answer"),

    /** Determinism: the vendor answers its good payload once and its clean one to every request after, so a second
     *  query over the same recording reads differently from the first. */
    A_VENDOR_THAT_CHANGES_ITS_MIND("determinism - the vendor answers its good payload once and nothing afterwards"),

    /** The status branch: the vendor's rejection is replaced by its 200, so a feed that never looked at the status
     *  reads exactly as one that did. */
    A_REJECTION_THE_FEED_ACCEPTS("the status branch - the rejection now carries a 200"),

    /** The parse branch: the unreadable body is replaced by the good payload, so a feed that cannot tell a proxy
     *  error page from data reads exactly as one that can. */
    A_MALFORMED_BODY_THE_FEED_PARSES("the parse branch - the unreadable body now parses"),

    /** Gate 4's separation: the vendor's own "I carry nothing for this" answer is replaced by its outage, so
     *  "screened and found nothing" and "could not be reached" become one observation. */
    A_CLEAN_ANSWER_THAT_IS_AN_OUTAGE("gate 4's separation - the clean answer is now an outage"),

    /** The bound: the endless recording stops, so a cursor with no cap reads exactly as one that refuses by name. */
    AN_ENDLESS_FEED_THAT_ENDS("the bound - the endless recording now ends"),

    /** The cost of one lookup: the question spends one extra request against the recorded endpoint before it is
     *  asked, which is what a step that appeared in a chain, or a warm read that quietly re-fetches, looks like. */
    A_LOOKUP_THAT_SPENDS_AN_EXTRA_REQUEST("the cost of one lookup - the question spends one extra request"),

    /** The read behaviour: every answer the source has already given is served again from memory without asking the
     *  vendor, so a feed that declares every query a fetch reads as a warm one and a window never lapses. */
    A_QUERY_THAT_ANSWERS_FROM_MEMORY("the read behaviour - an answer already given is served again from memory"),

    /**
     * <b>The instance found, as a mutation.</b> The query also resolves one host this JVM has <em>already</em>
     * resolved milliseconds earlier - the mutant resolves it once when it is built - so the read-purity leg's
     * "reached exactly the vendor" comparison must see a second host.
     *
     * <p>It is the mutation and the instrument's own proof at once. The second lookup is only visible because
     * {@link NoEgressResolver} switches the JDK's ten-second failed-lookup cache off; with it on, the repeat is
     * answered from {@code InetAddress}'s cache, the installed resolver is never consulted, and the leg would report
     * "this query reached nothing" over a query that reached two hosts. The mutant therefore checks its own premise
     * and, when the record under-counts, fails as a <em>broken harness</em> naming the sampling instrument rather
     * than as a falsification - because a record that under-counts says nothing about whether the check measures its
     * property.
     */
    A_QUERY_THAT_REACHES_A_HOST_ALREADY_REACHED("the read path's exactness - the query also resolves a host this JVM "
            + "resolved a moment ago"),

    /** The vocabulary: every lookup is issued for the last ecosystem the fixture declares, whatever it was asked
     *  about - one map that answers the same spelling for everything, which is what a vocabulary keyed on another
     *  vendor's names degrades into. */
    AN_ECOSYSTEM_ALWAYS_QUERIED_THE_SAME_WAY("the vocabulary - every lookup is issued for one declared ecosystem"),

    /** The stamp's origin: a source that has fetched nothing reports a fetch instant anyway - a construction date
     *  rendering beside an empty panel exactly as a healthy feed does. */
    A_STAMP_FABRICATED_AT_CONSTRUCTION("the stamp's origin - a source that has fetched nothing reports an instant"),

    /** The stamp's meaning: it moves to the moment the answer was SERVED rather than the moment its data was drawn,
     *  so an aged answer renders as a fresh one and the consumer's only defence is gone. */
    A_STAMP_THAT_MOVES_WHEN_IT_SERVES("the stamp's meaning - it moves to the moment the answer was served"),

    /**
     * <b>The kit's general vacuity probe:</b> a source that does nothing at all. Every query is answered by the
     * SPI's <em>own</em> "no feed is active" sentinel - {@code AdvisorySource.NONE} and its three siblings, whose
     * neutral answers and {@link Freshness#NEVER} stamp are what an unconfigured deployment resolves to - so the
     * mutated object is not a stub the kit invented but the absence the SPI already declares. Nothing is fetched, and
     * the identity half ({@code name}, {@code label}, {@code order} on a report column) is left alone, because the
     * mutant removes the feed's <em>work</em> rather than its name.
     *
     * <p>It is the one mutant that names no single behaviour: it is how the census measures what the kit would still be
     * green over. A check this survives proves nothing about a real feed unless something else falsifies it, which is
     * what the signal contract requires of every survivor.
     */
    A_SOURCE_THAT_ANSWERS_NOTHING("the feed's work, everywhere - every query answers the SPI's own \"no feed is "
            + "active\" sentinel and nothing is fetched");

    private final String removes;

    Mutant(String removes) {
        this.removes = removes;
    }

    /** What this mutant takes away, in the words a failure message uses. */
    public String removes() {
        return removes;
    }

    /**
     * {@code fixture} with exactly this mutant's behaviour removed, or {@code fixture} itself for {@link #NONE}.
     * Building the decorator is where {@link #A_QUERY_THAT_REACHES_A_HOST_ALREADY_REACHED} takes its first lookup, so
     * that the repeat inside the check is genuinely a repeat and lands well inside the JDK's ten-second window.
     */
    public static SignalFixture decorate(SignalFixture fixture, Mutant mutant) {
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(mutant, "mutant");
        return mutant == NONE ? fixture : new Mutated(fixture, mutant);
    }

    /** The fixture with one deployment object substituted. Everything the mutant does not name is forwarded, so the
     *  provider, the recording, the deployment binding and the tripwire are the kit's own on a mutated leg exactly as
     *  they are on an unmutated one. */
    private static final class Mutated implements SignalFixture {

        private final SignalFixture delegate;
        private final Mutant mutant;
        private final AtomicReference<URI> endpoint = new AtomicReference<>();
        private final AtomicInteger stamps = new AtomicInteger();
        private final String canary;

        private Mutated(SignalFixture delegate, Mutant mutant) {
            this.delegate = delegate;
            this.mutant = mutant;
            this.canary = "already-reached." + delegate.signal() + ".jenreg.test";
            if (mutant == A_QUERY_THAT_REACHES_A_HOST_ALREADY_REACHED) {
                resolve(canary);                     // the first lookup, so the one inside the check is a REPEAT
            }
        }

        // --- the declarations, unchanged -------------------------------------------------------------------------

        @Override
        public String providerClass() {
            return delegate.providerClass();
        }

        @Override
        public String signal() {
            return delegate.signal();
        }

        @Override
        public Set<Class<? extends SignalSource>> signals() {
            return delegate.signals();
        }

        @Override
        public String vendorHost() {
            return delegate.vendorHost();
        }

        @Override
        public FailMode failMode() {
            return delegate.failMode();
        }

        @Override
        public Reads reads() {
            return delegate.reads();
        }

        @Override
        public Paging paging() {
            return delegate.paging();
        }

        @Override
        public Degradation degradation() {
            return delegate.degradation();
        }

        @Override
        public Object answer() {
            return delegate.answer();
        }

        @Override
        public Object neutral() {
            return delegate.neutral();
        }

        @Override
        public Object cleanAnswer() {
            return delegate.cleanAnswer();
        }

        @Override
        public String pageCapMarker() {
            return delegate.pageCapMarker();
        }

        @Override
        public int chainedFetches() {
            return delegate.chainedFetches();
        }

        @Override
        public Duration warmWindow() {
            return delegate.warmWindow();
        }

        @Override
        public List<Family> families() {
            return delegate.families();
        }

        @Override
        public List<String> uncovered() {
            return delegate.uncovered();
        }

        // --- the configuration seam ------------------------------------------------------------------------------

        @Override
        public UnaryOperator<String> enabled(URI recorded) {
            endpoint.set(recorded);                  // remembered, so a mutation can spend a request of its own here
            return delegate.enabled(recorded);
        }

        @Override
        public UnaryOperator<String> production() {
            return delegate.production();
        }

        @Override
        public List<Unconfigured> unconfigured() {
            if (mutant != AN_UNCONFIGURED_SHAPE_THAT_IS_REALLY_ENABLED) {
                return delegate.unconfigured();
            }
            return delegate.unconfigured().stream()
                    .map(shape -> new Unconfigured(shape.why(), delegate.production()))
                    .toList();
        }

        // --- the vendor seam -------------------------------------------------------------------------------------

        @Override
        public RecordedFeed.Responder recorded() {
            if (mutant == A_VENDOR_THAT_CARRIES_NOTHING) {
                return delegate.clean();
            }
            if (mutant == A_VENDOR_THAT_CHANGES_ITS_MIND) {
                AtomicInteger answered = new AtomicInteger();
                return request -> answered.getAndIncrement() == 0
                        ? delegate.recorded().respond(request)
                        : delegate.clean().respond(request);
            }
            return delegate.recorded();
        }

        @Override
        public RecordedFeed.Responder rejected() {
            return mutant == A_REJECTION_THE_FEED_ACCEPTS ? delegate.recorded() : delegate.rejected();
        }

        @Override
        public RecordedFeed.Responder malformed() {
            return mutant == A_MALFORMED_BODY_THE_FEED_PARSES ? delegate.recorded() : delegate.malformed();
        }

        @Override
        public RecordedFeed.Responder clean() {
            return mutant == A_CLEAN_ANSWER_THAT_IS_AN_OUTAGE ? delegate.rejected() : delegate.clean();
        }

        @Override
        public RecordedFeed.Responder endless() {
            return mutant == AN_ENDLESS_FEED_THAT_ENDS ? delegate.recorded() : delegate.endless();
        }

        // --- the source seam -------------------------------------------------------------------------------------

        @Override
        public SignalSource source(UnaryOperator<String> config) {
            SignalSource resolved = delegate.source(config);
            if (mutant == A_CREATION_THAT_FETCHES) {
                spendOneRequest();
            }
            return broken(resolved);
        }

        /** The resolved source with one answer removed - a proxy over the very interfaces it implements, so the
         *  object a check receives answers every contract the real one answers and one behaviour less. */
        private SignalSource broken(SignalSource resolved) {
            Set<Class<?>> interfaces = new LinkedHashSet<>(implemented(resolved.getClass()));
            if (mutant == A_SOURCE_THAT_IS_ALSO_SOMETHING_ELSE) {
                interfaces.add(SignalContract.CONTRACTS.stream()
                        .filter(contract -> !delegate.signals().contains(contract))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(delegate.signal() + " declares every signal "
                                + "contract there is, so there is none left to over-advertise")));
            }
            Map<List<Object>, Object> answered = new ConcurrentHashMap<>();
            return (SignalSource) java.lang.reflect.Proxy.newProxyInstance(SignalSource.class.getClassLoader(),
                    interfaces.toArray(new Class<?>[0]),
                    (_, method, args) -> answer(resolved, method, args, answered));
        }

        private Object answer(SignalSource resolved, Method method, Object[] args, Map<List<Object>, Object> answered)
                throws Throwable {
            if (method.getName().equals("freshness") && method.getParameterCount() == 0) {
                return freshness(resolved);
            }
            if (mutant == A_SOURCE_THAT_ANSWERS_NOTHING) {
                return nothing(resolved, method, args);
            }
            if (mutant == A_QUERY_THAT_ANSWERS_FROM_MEMORY && !method.getName().equals("refresh")) {
                List<Object> key = new ArrayList<>();
                key.add(method.getName());
                key.addAll(args == null ? List.of() : Arrays.asList(args));
                Object remembered = answered.get(key);
                if (remembered != null) {
                    return remembered;
                }
                Object drawn = call(resolved, method, args);
                if (drawn != null) {
                    answered.put(key, drawn);
                }
                return drawn;
            }
            return call(resolved, method, args);
        }

        /**
         * The query, answered by the SPI's own "no feed is active" sentinel for the contract that declared the method.
         * {@code refresh()} returns {@link Freshness#NEVER} without reaching anything, which is what a source with
         * nothing to refresh honestly reports; a report column's identity ({@code name}, {@code label}, {@code order})
         * is forwarded, because this mutant removes the feed's work rather than its name, and its {@code evaluate}
         * yields no values.
         */
        private Object nothing(SignalSource resolved, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("refresh") && method.getParameterCount() == 0) {
                return Freshness.NEVER;
            }
            SignalSource sentinel = SignalContract.nothingInstalled(method.getDeclaringClass());
            if (sentinel != null) {
                return call(sentinel, method, args);
            }
            if (method.getName().equals("evaluate")) {
                return List.of();
            }
            return call(resolved, method, args);
        }

        private Freshness freshness(SignalSource resolved) throws Throwable {
            if (mutant == A_SOURCE_THAT_ANSWERS_NOTHING) {
                return Freshness.NEVER;
            }
            Freshness real = (Freshness) call(resolved, SignalSource.class.getMethod("freshness"), null);
            if (mutant == A_STAMP_FABRICATED_AT_CONSTRUCTION && !real.fetched()) {
                return Freshness.at(Instant.now());
            }
            if (mutant == A_STAMP_THAT_MOVES_WHEN_IT_SERVES && real.fetched()) {
                // A distinct instant every time it is read, so the movement cannot be lost to a coarse clock.
                return Freshness.at(Instant.now().plusSeconds(stamps.incrementAndGet()));
            }
            return real;
        }

        private static Object call(SignalSource resolved, Method method, Object[] args) throws Throwable {
            if (!method.getDeclaringClass().isInstance(resolved)) {
                return absent(method.getReturnType());          // a contract the real source never implemented
            }
            try {
                return method.invoke(resolved, args);
            } catch (InvocationTargetException raised) {
                throw raised.getCause();
            }
        }

        /** What an over-advertised contract's method answers. Nothing in the kit calls one - the declaration leg only
         *  asks {@code instanceof} - so this exists to keep the proxy total rather than to mean anything. */
        private static Object absent(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            return type == void.class ? null : 0;
        }

        /** Every interface {@code type} implements, transitively - what the proxy has to answer for the object a
         *  check receives to be indistinguishable from the one the provider built. */
        private static Set<Class<?>> implemented(Class<?> type) {
            Set<Class<?>> found = new LinkedHashSet<>();
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                Deque<Class<?>> queue = new ArrayDeque<>(Arrays.asList(current.getInterfaces()));
                while (!queue.isEmpty()) {
                    Class<?> next = queue.poll();
                    if (found.add(next)) {
                        queue.addAll(Arrays.asList(next.getInterfaces()));
                    }
                }
            }
            return found;
        }

        // --- the question seam -----------------------------------------------------------------------------------

        @Override
        public Object ask(SignalSource source) {
            if (mutant == A_LOOKUP_THAT_SPENDS_AN_EXTRA_REQUEST) {
                spendOneRequest();
            }
            if (mutant == A_QUERY_THAT_REACHES_A_HOST_ALREADY_REACHED) {
                repeatTheCanaryLookup();
            }
            return delegate.ask(source);
        }

        @Override
        public void lookup(SignalSource source, String ecosystem, String coordinate, String version) {
            if (mutant == AN_ECOSYSTEM_ALWAYS_QUERIED_THE_SAME_WAY && !delegate.families().isEmpty()) {
                delegate.lookup(source, delegate.families().getLast().ecosystem(), coordinate, version);
                return;
            }
            delegate.lookup(source, ecosystem, coordinate, version);
        }

        // --- lifecycle: the suite owns it, so a mutated fixture never starts or stops anything -------------------

        @Override
        public void start() {
        }

        @Override
        public void close() {
        }

        /** One plain GET against the recorded endpoint - a request the vendor counts, with no source involved. */
        private void spendOneRequest() {
            URI recorded = endpoint.get();
            if (recorded == null) {
                return;                              // no recorded endpoint in play; nothing to spend a request on
            }
            try {
                URLConnection connection = recorded.toURL().openConnection();
                connection.setConnectTimeout(2_000);
                connection.setReadTimeout(2_000);
                try (InputStream _ = connection.getInputStream()) {
                    // Opening the response is what makes the request, and the request is the whole mutation - the
                    // body is the vendor's and is deliberately never read, so this stays a request the recording
                    // counted rather than a whole-blob read.
                }
            } catch (IOException _) {
                // the recording may answer a rejection; the request was still made, which is the whole mutation
            }
        }

        /**
         * The repeat lookup, with the tripwire's record checked either side of it. A record that did not grow means
         * the JDK answered from its own failed-lookup cache and never consulted the installed resolver - the 
         * instance - so the mutation cannot be observed at all. That is a broken harness rather than a falsification,
         * and it is raised as one so the falsification leg reports it as the machinery failing rather than banking a
         * green.
         */
        private void repeatTheCanaryLookup() {
            List<String> before = NoEgressResolver.attempted();
            resolve(canary);
            if (!NoEgressResolver.since(before).contains(canary)) {
                throw new SamplingRecord("the no-egress tripwire did not record a REPEATED refused lookup of "
                        + canary + ", so it is a sampling instrument rather than a record: the JDK's "
                        + "networkaddress.cache.negative.ttl answered the second lookup from InetAddress's own cache "
                        + "and the installed resolver was never consulted (it is "
                        + NoEgressResolver.negativeLookupCacheTtl() + ", and must be 0). Every read-purity "
                        + "measurement made on a host a sibling fixture already reached is then vacuous rather than "
                        + "false - which is exactly the defect this mutation exists to keep visible.");
            }
        }

        /**
         * What a record that under-counts raises. It is an {@link Error} rather than an exception on purpose: the
         * contract's own check bodies catch {@code RuntimeException} to record what a feed raised, so a premise
         * failure thrown as one would be filed as the feed's answer and lost exactly where it matters most. It is
         * deliberately not an {@link AssertionError} either - that is the falsification leg's vocabulary for "the
         * check said otherwise", and this is the opposite: the check could not be driven at all.
         */
        private static final class SamplingRecord extends Error {

            private SamplingRecord(String message) {
                super(message);
            }
        }

        private static void resolve(String host) {
            try {
                InetAddress.getByName(host);
            } catch (UnknownHostException _) {
                // refused and recorded, which is the point - the tripwire answers every non-local name this way
            }
        }
    }
}
