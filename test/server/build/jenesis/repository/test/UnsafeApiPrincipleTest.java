package build.jenesis.repository.test;

import module java.base;

import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core structural ratchet against <b>unsafe API</b> (SPI_HARDENING_PLAN.md D-101): API whose <em>convenient</em>
 * form is the wrong one, so a caller reaches the unbounded path <em>by doing nothing wrong</em>. Five defects in that
 * plan's history were one shape:
 * <ul>
 *   <li><b>D-035</b> - {@code fetchBounded}'s default routed through the 8&nbsp;MiB-capped {@code fetch}: "the default
 *       <b>is</b> the defect";</li>
 *   <li><b>D-053</b> - {@code ArtifactStore.page}'s default sorted a whole {@code list()}, the exact opposite of what
 *       paging is for, and every shipped backend overrode it so nothing ever caught it;</li>
 *   <li><b>D-055</b> - {@code Fetcher.download}/{@code head} defaults materialised and opened bodies, breaking the
 *       streaming clause of the very interface that declared them;</li>
 *   <li><b>D-010</b> - {@code CacheStorage.allEntries()}, a whole-store scan with <b>no caller at all</b>;</li>
 *   <li><b>D-068</b> - five private {@code bounded(InputStream, long)} helpers, parallel by convention.</li>
 * </ul>
 * Every one was found by accident, while doing something else. This scan makes the shape fail the build instead. It is
 * a deterministic source scan in the {@link UnboundedListingPrincipleTest} / {@link ArchiveInflationPrincipleTest}
 * mould - it reads {@code source/} and boots nothing, strips comments, keeps a censused, reason-bearing, shrink-only
 * allowlist per leg, an allowlist-liveness leg, named (never counted) non-vacuity pins, and a <b>live negative
 * control</b> that plants an offender in a synthetic source tree and asserts each leg names it.
 *
 * <h2>Leg (a) - a {@code default} that reaches a less-bounded path than the sibling it stands in for</h2>
 * Every method is given a <b>cost class</b> read off its signature, and a {@code default} in an interface is an
 * offender when it calls a sibling (declared on the same interface or on one it extends, resolved by <em>arity</em> so
 * an overload cannot mask its own unbounded twin) whose class is weaker than its own:
 * <ul>
 *   <li><b>BOUNDED</b> - takes a {@code limit}/{@code max&hellip;} cap or a {@code Consumer}/{@code Visitor}/
 *       {@code Sink}, or answers a {@code Traversal.Result}: it promises the caller a bound;</li>
 *   <li><b>STREAMED</b> - hands back or takes a stream, or answers a carrier whose body is an {@link InputStream}:
 *       nothing is materialised;</li>
 *   <li><b>MATERIALISED</b> - answers a whole {@code List}/{@code Set}/{@code Map}/{@code byte[]}, or a carrier whose
 *       body is a {@code byte[]}: the whole answer lands in heap;</li>
 *   <li><b>METADATA</b> - answers a question ({@code boolean}, a number, {@code void}, an {@code Optional} of a
 *       bodiless carrier) and therefore needs no bound at all.</li>
 * </ul>
 * A <b>carrier</b> is classified by what it <em>carries</em>, not by what it is called: {@code Fetched(int, byte[]
 * body, Map)} is MATERIALISED, {@code Download(int, InputStream body, Map)} is STREAMED, {@code Head(int, Map)} is
 * METADATA. That is what lets the scan see D-055, whose three legs are distinguished by nothing else.
 *
 * <p>The forbidden transitions, each named by the defect it catches:
 * <ul>
 *   <li>BOUNDED &rarr; MATERIALISED - the paged default implemented over the whole-list sibling (D-053, D-035);</li>
 *   <li>STREAMED &rarr; MATERIALISED - the streaming leg derived from the buffered one (D-055's {@code download});</li>
 *   <li>METADATA &rarr; MATERIALISED - the emptiness probe that lists a namespace to ask whether it is empty;</li>
 *   <li>METADATA &rarr; STREAMED - opening a body to answer a metadata question (D-055's {@code head}).</li>
 * </ul>
 * Everything else is allowed on purpose. A MATERIALISED default over a MATERIALISED sibling promises nothing it does
 * not deliver, and a METADATA default over a BOUNDED sibling is the <em>fix</em> (the bounded one-child probe), not
 * the defect.
 *
 * <h2>Leg (b) - a whole-collection method standing beside a paged sibling</h2>
 * An <b>exported</b> method (its package is named in some {@code module-info.java} {@code exports} clause) that answers
 * a whole {@code List}/{@code Set}/{@code Map}/{@code Collection} with no bound of its own, while a <em>related</em>
 * sibling on the same type is BOUNDED, is an offender. Relatedness is the same name, or one of the pairs this codebase
 * writes: {@code list}/{@code page}, {@code x}/{@code pageX}, {@code x}/{@code walkX}, {@code x}/{@code streamX},
 * {@code allX}/{@code x}. No store-derivation heuristic is needed and none is used: <em>the existence of a cursor-and-
 * limit sibling is itself the evidence</em> that these rows scale with the deployment's data - nobody writes a paged
 * accessor for a fixed set. An {@code @Override} is skipped, because an implementation of an already-censused
 * declaration is not new API.
 *
 * <h2>Leg (c) - an exported enumerating method with no production caller</h2>
 * An exported {@code public} (or interface) method that either answers a whole collection or whose body reaches an
 * enumeration primitive ({@code .list(}, {@code .page(}, {@code .walk(}, {@code readAllBytes}, {@code toByteArray},
 * {@code Files.walk}), and whose name appears in <b>no call position anywhere under {@code source/}</b> - counting
 * bare calls, {@code this.}/{@code super.} calls, {@code ::} method references and calls written in the shipped
 * templates - is an offender. The owner's rule is that unsafe API should not exist to be called, so <b>the fix here is
 * deletion, not a cursor</b> (D-010's {@code allEntries} outcome). Framework-invoked methods (anything carrying an
 * annotation: {@code @Bean}, {@code @GetMapping}, {@code @Override}, &hellip;) and the {@code **}{@code /testkit/}
 * modules (test support whose callers live in {@code test/} by design) are outside the leg.
 *
 * <p><b>The core is a published library</b>, so "no caller in this repository" is not "no caller": the downstream
 * edition consumes these modules as pinned Central artifacts and cannot be seen from here. Every {@link #UNCALLED}
 * entry therefore has to say <em>where</em> the caller is, and an entry that cannot name one is a deletion the entry is
 * deferring, not a grant.
 *
 * <h2>Non-vacuity, the pins, and the negative control</h2>
 * {@link #the_scan_sees_the_shapes_it_is_built_to_judge} pins <b>named</b> classifications rather than a count - gate 6
 * of this plan is that counts are diagnostics and never acceptance criteria, so nothing here asserts "at least N".
 * The three {@code *_SIZE} pins are exact equalities on named allowlists, held <b>shrink-only</b>: an entry may be
 * removed (delete the offender, delete its entry, decrement the pin), never added to mask a new one.
 * {@link #the_allowlists_stay_live} fails when an entry stops matching a real offender, so a grant cannot rot into a
 * dead mask. {@link #negative_control_a_planted_offender_trips_every_leg} writes a synthetic source tree containing one
 * planted offender per leg and runs the real scan over it, so the ratchet is proven to bite on every build rather than
 * only in a commit message. {@link #the_scan_reads_every_source_file_including_the_ones_grep_calls_binary} closes the
 * other half of vacuity - a file the scan never reads is a file it reports clean forever - and pins the {@code NUL}-
 * bearing sources a {@code grep}-based census silently drops.
 *
 * <h2>Honest limitations</h2>
 * Like every token-scanning ratchet here (&sect;6 caveat) this is heuristic, and it is deliberately biased toward
 * false negatives so that a green build means something. It parses declarations with a regex over comment-stripped
 * source rather than with a compiler, so it sees the idioms this codebase writes and not the ones it does not: leg (a)
 * follows only <em>sibling</em> calls (a default that routes through a static helper in another class escapes, which
 * is exactly how the current {@code ArtifactStore.page} default legitimately passes - it delegates to
 * {@code pageByListing}, whose own bound throws past {@code MAX_INHERITED_CHILDREN}); leg (b) reads relatedness off
 * names; and leg (c) counts callers by bare method name, so a name shared with an unrelated method hides a genuinely
 * dead one. The value is that a <b>new</b> instance of a shape this plan has already paid for five times fails the
 * build the moment it is written.
 */
class UnsafeApiPrincipleTest {

    // --- leg (a): interface defaults that reach a less-bounded path -------------------------------------------------

    /** The pinned size of {@link #BOUND_DROPS}. Shrink-only. */
    private static final int BOUND_DROPS_SIZE = 3;

    /**
     * Interface {@code default}s that reach a weaker-bounded sibling, keyed {@code Owner#name/arity -> target/arity},
     * each with the reason the divergence is the right answer here. Seeded from the full census at this tip.
     */
    private static final List<Allow> BOUND_DROPS = List.of(
            new Allow("Buffered#download/2 -> fetch/2",
                    "D-055's derivation, preserved deliberately and behind a name a class has to WRITE DOWN rather "
                            + "than a default it receives for saying nothing. Fetcher's three legs are abstract; "
                            + "ProxyFormat.Fetcher.Buffered is the opt-in for a degenerate upstream whose whole answer "
                            + "is a small in-memory document (a scripted test double, a canned index), where the "
                            + "derivation costs nothing because there is no artifact and no network. A transport or a "
                            + "decorator that implements it collapses the deployment's streaming path onto the "
                            + "buffered one - which is what the type's own javadoc forbids and FetcherProvider's "
                            + "contract screens for"),
            new Allow("Buffered#head/2 -> download/2",
                    "the HEAD half of the same opt-in: it opens the derived download only to read its status and "
                            + "headers, over a body that is already a buffered in-memory document. Fetcher.head "
                            + "itself is abstract and Fetcher.NONE answers it without opening anything, so no "
                            + "transport inherits this"),
            new Allow("FormatExchange#respond/1 -> respond/2",
                    "the bodiless response: respond(status) opens the response stream with contentLength -1 and "
                            + "closes it immediately, writing no body and reading none. The STREAMED sibling it "
                            + "reaches transfers nothing, so there is no body opened to answer a metadata question - "
                            + "the D-055 shape this transition exists to catch"));

    // --- leg (b): whole-collection answers beside a paged sibling ---------------------------------------------------

    /** The pinned size of {@link #WHOLE_COLLECTIONS}. Shrink-only. */
    private static final int WHOLE_COLLECTIONS_SIZE = 2;

    /**
     * Exported whole-collection methods that stand beside a paged sibling, keyed {@code Owner#name/arity}, each with
     * the reason the unpaged form is the honest one. An entry is never a licence to enumerate: it asserts that this
     * particular whole answer is either bounded by something other than the deployment's data, or that its bound
     * fails visibly (gate 4) rather than truncating silently.
     */
    private static final List<Allow> WHOLE_COLLECTIONS = List.of(
            new Allow("ArtifactStore#list/1",
                    "the store's whole-namespace primitive itself. Its bound is not on the method, it is on each of "
                            + "its call sites: UnboundedListingPrincipleTest censuses every .list(prefix) in "
                            + "source/ and fails the build on a new one that carries no boundedness justification. "
                            + "Deleting the primitive is D-108's cross-repository migration onto page(...), not this "
                            + "ratchet's business - the real cost of the entry is that the census, not the signature, "
                            + "is what keeps a caller honest"),
            new Allow("Content#sibling/1",
                    "D-035's ruling, in force. The whole-document read has no caller-supplied bound BECAUSE a "
                            + "fraction of a POM or an attestation envelope is worthless, so it carries the seam's own "
                            + "LARGEST_SIBLING ceiling and THROWS past it - a bound that fails visibly rather than "
                            + "handing back a prefix the caller believes is whole. The bounded-fact sibling(path, "
                            + "limit) beside it answers a different question (give me at most this much and tell me "
                            + "there was more), so it is not a paged version of this one"));

    // --- leg (c): exported enumerating methods with no production caller --------------------------------------------

    /** The pinned size of {@link #UNCALLED}. Shrink-only. */
    private static final int UNCALLED_SIZE = 5;

    /**
     * Exported enumerating methods with no call site under this repository's {@code source/}, keyed
     * {@code Owner#name/arity}. Because the core is a published library, each entry must NAME the caller the
     * scan cannot see; an entry that names none is a deletion being deferred.
     */
    private static final List<Allow> UNCALLED = List.of(
            new Allow("RateLimitFilter#rejectedByTenant/0",
                    "called from the downstream edition, which consumes this module as a pinned Central artifact: "
                            + "GovernanceMetrics tags jenesis.ratelimit.rejected per tenant off this snapshot. The "
                            + "map is keyed by metering bucket - a provisioned tenant, or the single 'anonymous' "
                            + "bucket for a keyless request - so it is bounded by the operator's tenant set, not by "
                            + "traffic"),
            new Allow("QuotaArtifactStore#recompute/0",
                    "called from the downstream edition: its recovery pass drives the periodic reconcile that "
                            + "corrects usage-counter drift (RecoveryInvariants). The walk it does is the quota "
                            + "namespace's, and it answers one long rather than a collection"),
            new Allow("DirtyIndexFeed#pending/0",
                    "called from the downstream edition: SearchIndexTask reads the dirty set through it inside its "
                            + "IndexWriter transaction, applies each entry and clears only what committed. It became "
                            + "uncalled HERE when D-101 deleted applySince(Applier) - the composed read-apply-collect "
                            + "convenience that no production caller in either repository ever used, which is the "
                            + "D-010 outcome this leg exists to force. The walk is the dirty-marker namespace, "
                            + "bounded by pending changes rather than by the index's size"),
            new Allow("DirtyIndexFeed#compactThrough/1",
                    "called from the downstream edition: SearchIndexTask and DependentsIndex both call it as the "
                            + "reconcile backstop's feed garbage-collection, after rebuilding their index from "
                            + "durable truth. Its walk is the dirty-marker namespace, bounded by pending changes"),
            new Allow("PostureReport#forTenant/1",
                    "NO caller in either repository, and it is not a store enumeration - it filters an already-"
                            + "materialised in-memory advisory list, so the cost of keeping it is API surface rather "
                            + "than heap. It is left standing because deleting it means ruling on the whole "
                            + "tenant-scoped posture leg (SecurityAdvisory.tenant(...), Scope.TENANT and this "
                            + "accessor are all unreached: no SafetyAdvisor implementation in either repository ever "
                            + "raises a TENANT-scoped advisory), which is an owner's decision about a documented "
                            + "multi-tenant extension point, not a scan's"));

    // --- the legs ---------------------------------------------------------------------------------------------------

    @Test
    void no_interface_default_reaches_a_less_bounded_path_than_the_sibling_it_stands_in_for() throws IOException {
        List<String> offenders = render(boundDrops(scan(sourceRoot())), BOUND_DROPS);
        assertThat(offenders)
                .as("these interface defaults promise a bound they then drop: each calls a sibling of the SAME "
                        + "interface whose cost class is weaker than its own, so an implementation that says nothing "
                        + "inherits the unbounded path (D-035's fetchBounded over fetch, D-053's page over list, "
                        + "D-055's download over fetch and head over download). Either make the leg abstract so every "
                        + "implementation has to answer it, or give the default a bound that FAILS VISIBLY past a "
                        + "ceiling the way ArtifactStore.pageByListing does; if the divergence is genuinely right, "
                        + "add a BOUND_DROPS entry saying why and bump BOUND_DROPS_SIZE.%n%s",
                        String.join(System.lineSeparator(), offenders))
                .isEmpty();
    }

    @Test
    void no_exported_whole_collection_answer_stands_beside_a_paged_sibling() throws IOException {
        List<String> offenders = render(wholeCollections(scan(sourceRoot())), WHOLE_COLLECTIONS);
        assertThat(offenders)
                .as("these exported methods answer a WHOLE List/Set/Map while a paged sibling on the same type "
                        + "already exists - and the paged sibling is the proof that these rows scale with the "
                        + "deployment's own data, because nobody writes a cursor-and-limit accessor for a fixed set. "
                        + "Route the callers through the paged form and DELETE the whole-collection one; if it must "
                        + "stay, add a WHOLE_COLLECTIONS entry stating what bounds it (or how its bound fails "
                        + "visibly) and bump WHOLE_COLLECTIONS_SIZE.%n%s",
                        String.join(System.lineSeparator(), offenders))
                .isEmpty();
    }

    @Test
    void no_exported_enumerating_method_survives_without_a_production_caller() throws IOException {
        List<String> offenders = render(uncalled(scan(sourceRoot())), UNCALLED);
        assertThat(offenders)
                .as("these exported methods enumerate or materialise and NOTHING under source/ calls them - the "
                        + "D-010 shape, where a whole-store scan shipped on an SPI that no caller ever used. Unsafe "
                        + "API should not exist to be called, so the fix is DELETION, not a cursor. If the caller is "
                        + "in the downstream edition (which consumes this repository as a pinned Central artifact and "
                        + "cannot be seen from here), add an UNCALLED entry NAMING that caller and bump "
                        + "UNCALLED_SIZE.%n%s",
                        String.join(System.lineSeparator(), offenders))
                .isEmpty();
    }

    // --- the ratchet's own hygiene ----------------------------------------------------------------------------------

    @Test
    void the_allowlist_sizes_are_pinned_shrink_only() {
        assertThat(BOUND_DROPS).as("BOUND_DROPS is pinned shrink-only: an entry may be removed, never added to mask a "
                + "new bound-dropping default").hasSize(BOUND_DROPS_SIZE);
        assertThat(WHOLE_COLLECTIONS).as("WHOLE_COLLECTIONS is pinned shrink-only").hasSize(WHOLE_COLLECTIONS_SIZE);
        assertThat(UNCALLED).as("UNCALLED is pinned shrink-only").hasSize(UNCALLED_SIZE);
    }

    @Test
    void every_allowlist_entry_carries_a_reason() {
        List<String> bare = Stream.of(BOUND_DROPS, WHOLE_COLLECTIONS, UNCALLED)
                .flatMap(List::stream)
                .filter(allow -> allow.reason().length() < 40)
                .map(allow -> "  - " + allow.key())
                .toList();
        assertThat(bare)
                .as("an allowlist entry without a stated reason is an unexplained exemption, which is the shape this "
                        + "ratchet exists to remove.%n%s", String.join(System.lineSeparator(), bare))
                .isEmpty();
    }

    @Test
    void the_allowlists_stay_live() throws IOException {
        Scan scan = scan(sourceRoot());
        List<String> dead = new ArrayList<>();
        dead.addAll(stale(BOUND_DROPS, boundDrops(scan), "BOUND_DROPS"));
        dead.addAll(stale(WHOLE_COLLECTIONS, wholeCollections(scan), "WHOLE_COLLECTIONS"));
        dead.addAll(stale(UNCALLED, uncalled(scan), "UNCALLED"));
        Collections.sort(dead);
        assertThat(dead)
                .as("these allowlist entries no longer match a real offender (the method was deleted, made abstract, "
                        + "paged, or gained a caller), so the grant masks nothing - remove the entry and decrement its "
                        + "pin so the allowlist tracks the source and cannot rot into a dead mask.%n%s",
                        String.join(System.lineSeparator(), dead))
                .isEmpty();
    }

    /**
     * The coverage leg, and the reason this scan is a Java file walk rather than a {@code grep}: six source files in
     * these repositories carry a raw {@code NUL} byte inside a string literal, which makes {@code grep} classify the
     * whole file as <b>binary</b> and drop it from its output <em>silently</em>. A grep-based census therefore reports
     * such a file clean forever - which is precisely the failure mode D-101 exists to prevent, and is how a genuine
     * thirteenth proxy leg hid behind a twelve-leg census until D-069 found it.
     *
     * <p>So this asserts two things. First that the scan <b>read every {@code .java} file</b> under {@code source/},
     * reconciled against an independent walk taken here. Second, and the part that is not circular, that every file
     * carrying a {@code NUL} byte was not merely opened but <b>parsed</b> - it contributes declarations - so a reader
     * that choked on one, or a charset fallback that turned it into mojibake, fails here rather than reporting the
     * file offence-free. {@link #readTolerant} reads UTF-8 (in which {@code NUL} is a perfectly legal code point) and
     * falls back to ISO-8859-1, so neither happens today; this keeps it that way.
     */
    @Test
    void the_scan_reads_every_source_file_including_the_ones_grep_calls_binary() throws IOException {
        Path sourceRoot = sourceRoot();
        Set<String> onDisk = new TreeSet<>();
        Set<String> carryingNul = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".java") || name.equals("module-info.java")) {
                    continue;
                }
                String relative = sourceRoot.relativize(file).toString().replace(File.separatorChar, '/');
                onDisk.add(relative);
                for (byte b : Files.readAllBytes(file)) {
                    if (b == 0) {
                        carryingNul.add(relative);
                        break;
                    }
                }
            }
        }

        Scan scan = scan(sourceRoot);
        assertThat(scan.files())
                .as("the scan did not read every .java file under source/ - a file it never reads is a file it "
                        + "reports offence-free forever, which is the silent-skip failure this whole ratchet is "
                        + "against")
                .containsAll(onDisk);

        Set<String> parsed = scan.decls().stream().map(Decl::relativePath).collect(Collectors.toCollection(TreeSet::new));
        List<String> unparsed = carryingNul.stream().filter(file -> !parsed.contains(file)).sorted().toList();
        assertThat(unparsed)
                .as("these source files carry a raw NUL byte - the bytes that make grep call a file binary and drop "
                        + "it - and the scan read them without producing a single declaration, so it is seeing "
                        + "mojibake or nothing at all rather than source. The scan must parse them exactly as it "
                        + "parses any other file.%n%s", String.join(System.lineSeparator(), unparsed))
                .isEmpty();
    }

    @Test
    void the_scan_sees_the_shapes_it_is_built_to_judge() throws IOException {
        Scan scan = scan(sourceRoot());

        // Named pins, not counts: the classifier must read these exactly, or every leg passes vacuously.
        assertThat(costOf(scan, "ArtifactStore#page/4"))
                .as("ArtifactStore.page(prefix, startAfter, limit, consumer) is the BOUNDED shape the whole scan is "
                        + "calibrated against")
                .isEqualTo(Cost.BOUNDED);
        assertThat(costOf(scan, "ArtifactStore#list/1"))
                .as("ArtifactStore.list(prefix) answers a whole namespace, so it is MATERIALISED")
                .isEqualTo(Cost.MATERIALISED);
        assertThat(costOf(scan, "ArtifactStore#exists/1"))
                .as("ArtifactStore.exists(key) answers a question and needs no bound, so it is METADATA")
                .isEqualTo(Cost.METADATA);
        assertThat(costOf(scan, "ArtifactStore#open/1"))
                .as("ArtifactStore.open(key) hands back a stream, so it is STREAMED")
                .isEqualTo(Cost.STREAMED);

        // The carrier rule - what an answer CARRIES, which is the only thing that separates D-055's three legs.
        assertThat(scan.carrierOf("Fetched"))
                .as("Fetched(int status, byte[] body, Map headers) carries a materialised body")
                .isEqualTo(Cost.MATERIALISED);
        assertThat(scan.carrierOf("Download"))
                .as("Download(int status, InputStream body, Map headers) carries a streamed body")
                .isEqualTo(Cost.STREAMED);
        assertThat(scan.carrierOf("Head"))
                .as("Head(int status, Map headers) carries no body at all")
                .isNotEqualTo(Cost.MATERIALISED);

        // The declaration walk must reach the real tree, including a nested interface inside an SPI file.
        assertThat(scan.decls().stream().map(Decl::key))
                .as("the declaration walk must find the nested Fetcher.Buffered interface's legs")
                .contains("Buffered#download/2", "Buffered#head/2");
    }

    /**
     * The live falsification: a synthetic {@code source/} tree carrying exactly one planted offender per leg, run
     * through the very same {@link #scan} the three legs above use. A ratchet nobody has watched fail is a ratchet
     * that might assert nothing, so this runs on every build rather than living in a commit message.
     */
    @Test
    void negative_control_a_planted_offender_trips_every_leg() throws IOException {
        Path root = Files.createTempDirectory("d101-negative-control");
        try {
            Path pkg = Files.createDirectories(root.resolve("probe/build/probe/api"));
            Files.writeString(root.resolve("probe/module-info.java"), """
                    module probe {
                        exports probe.api;
                    }
                    """);
            Files.writeString(pkg.resolve("Probe.java"), """
                    package probe.api;

                    import module java.base;

                    public interface Probe {

                        /** Leg (b): a whole-collection answer standing beside the paged sibling below. */
                        List<String> rows(String prefix);

                        /** The paged sibling that proves the rows scale with the deployment's data. */
                        void rows(String prefix, String after, int limit, Consumer<String> sink);

                        /** Leg (a): a BOUNDED default that drops its bound onto the MATERIALISED sibling. */
                        default List<String> firstRows(String prefix, int limit) {
                            return rows(prefix).subList(0, limit);
                        }
                    }
                    """);
            Files.writeString(pkg.resolve("ProbeScan.java"), """
                    package probe.api;

                    import module java.base;

                    public final class ProbeScan {

                        private final Probe probe;

                        public ProbeScan(Probe probe) {
                            this.probe = probe;
                        }

                        /** Leg (c): exported, enumerates, and nothing under source/ calls it. */
                        public List<String> everything() {
                            return probe.rows("");
                        }
                    }
                    """);

            Scan planted = scan(root);

            assertThat(boundDrops(planted).stream().map(Offender::key))
                    .as("a BOUNDED default implemented over its MATERIALISED sibling must trip leg (a)")
                    .containsExactly("Probe#firstRows/2 -> rows/1");
            assertThat(wholeCollections(planted).stream().map(Offender::key))
                    .as("a whole-collection answer beside a paged sibling must trip leg (b)")
                    .containsExactly("Probe#rows/1");
            assertThat(uncalled(planted).stream().map(Offender::key))
                    .as("an exported enumerating method with no caller must trip leg (c)")
                    .containsExactly("ProbeScan#everything/0");

            // ... and each is cleared by a justified allowlist entry, so the grant mechanism is proven too.
            List<Allow> grants = List.of(
                    new Allow("Probe#firstRows/2 -> rows/1", "synthetic - negative control only, never a real grant"),
                    new Allow("Probe#rows/1", "synthetic - negative control only, never a real grant"),
                    new Allow("ProbeScan#everything/0", "synthetic - negative control only, never a real grant"));
            assertThat(render(boundDrops(planted), grants)).isEmpty();
            assertThat(render(wholeCollections(planted), grants)).isEmpty();
            assertThat(render(uncalled(planted), grants)).isEmpty();
        } finally {
            delete(root);
        }
    }

    // --- the three legs, over a parsed scan ---------------------------------------------------------------------------

    /** Leg (a): interface {@code default}s calling an arity-resolved sibling of a weaker cost class. */
    private static List<Offender> boundDrops(Scan scan) {
        List<Offender> offenders = new ArrayList<>();
        for (Decl decl : scan.decls()) {
            if (!decl.kind().equals("interface") || !decl.modifiers().contains("default") || !decl.hasBody()) {
                continue;
            }
            Cost mine = scan.cost(decl);
            for (Call call : calls(decl.body())) {
                if (call.name().equals(decl.name()) && call.arity() == decl.parameters().size()) {
                    continue;
                }
                Optional<Decl> target = scan.sibling(decl.owner(), call.name(), call.arity());
                if (target.isEmpty()) {
                    continue;
                }
                Cost theirs = scan.cost(target.get());
                if (!forbidden(mine, theirs)) {
                    continue;
                }
                offenders.add(new Offender(
                        decl.owner() + "#" + decl.name() + "/" + decl.parameters().size()
                                + " -> " + call.name() + "/" + call.arity(),
                        decl.relativePath(),
                        "a " + mine + " default reaches its " + theirs + " sibling " + call.name()
                                + "/" + call.arity()));
            }
        }
        return distinct(offenders);
    }

    /** Leg (b): exported whole-collection answers with a related BOUNDED sibling on the same type. */
    private static List<Offender> wholeCollections(Scan scan) {
        List<Offender> offenders = new ArrayList<>();
        for (Decl decl : scan.decls()) {
            if (!scan.exported(decl) || decl.modifiers().contains("@Override") || !wholeCollection(decl)) {
                continue;
            }
            if (!decl.kind().equals("interface") && !decl.modifiers().contains("public")) {
                continue;                       // package-private: not exported API, whatever its package is
            }
            for (Decl sibling : scan.membersOf(decl.owner())) {
                if (sibling == decl || scan.cost(sibling) != Cost.BOUNDED
                        || !related(decl.name(), sibling.name())) {
                    continue;
                }
                offenders.add(new Offender(
                        decl.owner() + "#" + decl.name() + "/" + decl.parameters().size(),
                        decl.relativePath(),
                        "answers a whole " + decl.returns() + " while the paged sibling " + sibling.name()
                                + "/" + sibling.parameters().size() + " already exists"));
                break;
            }
        }
        return distinct(offenders);
    }

    /** Leg (c): exported enumerating methods no call site under {@code source/} reaches. */
    private static List<Offender> uncalled(Scan scan) {
        List<Offender> offenders = new ArrayList<>();
        for (Decl decl : scan.decls()) {
            if (!scan.exported(decl) || decl.relativePath().contains("/testkit/")) {
                continue;
            }
            if (decl.modifiers().contains("@")) {
                continue;                       // annotated: invoked by a framework, not from a source call site
            }
            if (!decl.kind().equals("interface") && !decl.modifiers().contains("public")) {
                continue;
            }
            boolean enumerates = wholeCollection(decl)
                    || decl.hasBody() && ENUMERATION_PRIMITIVES.stream().anyMatch(decl.body()::contains);
            if (!enumerates || scan.callSites(decl.name()) > 0) {
                continue;
            }
            offenders.add(new Offender(
                    decl.owner() + "#" + decl.name() + "/" + decl.parameters().size(),
                    decl.relativePath(),
                    "enumerates or answers a whole " + decl.returns() + " and no source/ call site reaches it"));
        }
        return distinct(offenders);
    }

    /** The store enumerations and whole-body reads that make a body "enumerating or materialising" for leg (c). */
    private static final List<String> ENUMERATION_PRIMITIVES = List.of(
            ".list(", ".page(", ".walk(", ".readAllBytes(", ".toByteArray(", "Files.walk(", ".readAllLines(");

    /** The forbidden cost transitions, each named by the defect it catches - see the class javadoc. */
    private static boolean forbidden(Cost from, Cost to) {
        if (to == Cost.MATERIALISED) {
            return from == Cost.BOUNDED || from == Cost.STREAMED || from == Cost.METADATA;
        }
        return to == Cost.STREAMED && from == Cost.METADATA;
    }

    /** A whole-collection answer: the return type IS a collection and no parameter bounds the call. */
    private static boolean wholeCollection(Decl decl) {
        if (decl.parameters().stream().anyMatch(UnsafeApiPrincipleTest::bounding)) {
            return false;
        }
        String raw = raw(decl.returns());
        return COLLECTIONS.contains(raw)
                || raw.equals("Optional") && COLLECTIONS.contains(raw(typeArgument(decl.returns())));
    }

    /** The name pairs this codebase writes for "the whole set" and "a page of it". */
    private static boolean related(String whole, String paged) {
        if (whole.equals(paged)) {
            return true;
        }
        String w = whole.toLowerCase(Locale.ROOT);
        String p = paged.toLowerCase(Locale.ROOT);
        return p.equals("page" + w) || p.equals(w + "paged") || p.equals("walk" + w) || p.equals("stream" + w)
                || w.equals("list") && p.equals("page")
                || w.startsWith("all") && w.length() > 3 && p.equals(w.substring(3));
    }

    // --- offenders, allowlists and rendering --------------------------------------------------------------------------

    /** One offending declaration: its {@code Owner#name/arity} key, the source-relative file, and why it is unsafe. */
    private record Offender(String key, String relativePath, String why) {
    }

    /** One allowlist grant: the offender key it clears and the reason that clearing it is the right answer. */
    private record Allow(String key, String reason) {
    }

    private static List<Offender> distinct(List<Offender> offenders) {
        Map<String, Offender> byKey = new LinkedHashMap<>();
        offenders.forEach(offender -> byKey.putIfAbsent(offender.key(), offender));
        return List.copyOf(byKey.values());
    }

    /** The offenders no allowlist entry clears, rendered so the failure names the method and says why. */
    private static List<String> render(List<Offender> offenders, List<Allow> allowlist) {
        Set<String> granted = allowlist.stream().map(Allow::key).collect(Collectors.toSet());
        return offenders.stream()
                .filter(offender -> !granted.contains(offender.key()))
                .map(offender -> "  - " + offender.key() + "  (" + offender.relativePath() + "): " + offender.why())
                .sorted()
                .toList();
    }

    /** Allowlist entries that no longer name a real offender. */
    private static List<String> stale(List<Allow> allowlist, List<Offender> offenders, String which) {
        Set<String> live = offenders.stream().map(Offender::key).collect(Collectors.toSet());
        return allowlist.stream()
                .map(Allow::key)
                .filter(key -> !live.contains(key))
                .map(key -> "  - " + which + ": " + key)
                .toList();
    }

    // --- the cost model -----------------------------------------------------------------------------------------------

    /** What a signature promises about the work it does - see the class javadoc. */
    private enum Cost {
        /** Answers a whole {@code List}/{@code Set}/{@code Map}/{@code byte[]}, or a carrier holding one. */
        MATERIALISED,
        /** Hands back or takes a stream, or answers a carrier whose body is an {@link InputStream}. */
        STREAMED,
        /** Carries a {@code limit}/sink/cursor, or answers a {@code Traversal.Result}. */
        BOUNDED,
        /** Answers a question and therefore needs no bound at all. */
        METADATA,
        /** A shape this model has nothing to say about; never an offender and never an offence. */
        OTHER
    }

    private static final Set<String> COLLECTIONS = Set.of("List", "Set", "Map", "Collection", "SortedMap", "SortedSet",
            "NavigableMap", "NavigableSet", "Iterable", "Properties", "byte[]", "String[]");

    private static final Set<String> BODY_COMPONENTS = Set.of("body", "content", "bytes", "payload", "data");

    /** A parameter that bounds the call: a numeric cap, or a sink the answer is pushed to one row at a time. */
    private static boolean bounding(Parameter parameter) {
        return numericBound(parameter) || sink(parameter);
    }

    private static boolean numericBound(Parameter parameter) {
        String type = raw(parameter.type());
        if (!type.equals("int") && !type.equals("long") && !type.equals("Integer") && !type.equals("Long")) {
            return false;
        }
        String name = parameter.name().toLowerCase(Locale.ROOT);
        return name.equals("limit") || name.equals("k") || name.equals("n") || name.equals("top")
                || name.equals("count") || name.equals("size") || name.equals("depth") || name.startsWith("max")
                || name.contains("budget") || name.contains("bound") || name.contains("ceiling")
                || name.contains("cap");
    }

    private static boolean sink(Parameter parameter) {
        String type = raw(parameter.type());
        return type.equals("Consumer") || type.equals("BiConsumer") || type.endsWith("Sink")
                || type.endsWith("Visitor");
    }

    /** The erased simple name of a type: {@code java.util.List<String>} becomes {@code List}, {@code byte[]} stays. */
    private static String raw(String type) {
        if (type == null) {
            return "";
        }
        String trimmed = type.trim();
        int angle = trimmed.indexOf('<');
        if (angle >= 0) {
            trimmed = trimmed.substring(0, angle);
        }
        boolean array = trimmed.endsWith("[]");
        if (array) {
            trimmed = trimmed.substring(0, trimmed.length() - 2).trim();
        }
        int dot = trimmed.lastIndexOf('.');
        String simple = dot >= 0 ? trimmed.substring(dot + 1) : trimmed;
        return array ? simple + "[]" : simple;
    }

    /** The first type argument of a generic type, or the empty string. */
    private static String typeArgument(String type) {
        int open = type.indexOf('<');
        int close = type.lastIndexOf('>');
        return open >= 0 && close > open ? type.substring(open + 1, close) : "";
    }

    // --- the parsed model ---------------------------------------------------------------------------------------------

    /** One declared method parameter. */
    private record Parameter(String type, String name) {
    }

    /** One declared method: where it lives, what declares it, its signature and (when it has one) its body. */
    private record Decl(String relativePath, String packageName, String owner, String kind, String modifiers,
                        String returns, String name, List<Parameter> parameters, String body, boolean hasBody) {

        String key() {
            return owner + "#" + name + "/" + parameters.size();
        }
    }

    /** One sibling invocation found in a body: the method name and how many arguments were passed. */
    private record Call(String name, int arity) {
    }

    /**
     * A parsed {@code source/} tree: every method declaration, the exported package set, the record carriers, the
     * supertype graph, and the call census used by leg (c).
     */
    private record Scan(List<Decl> decls, Set<String> files, Set<String> exportedPackages,
                        Map<String, List<Parameter>> carriers, Map<String, Set<String>> supertypes,
                        Map<String, Integer> callCensus, Map<String, Integer> declarationCensus) {

        boolean exported(Decl decl) {
            return exportedPackages.contains(decl.packageName());
        }

        List<Decl> membersOf(String owner) {
            return decls.stream().filter(decl -> decl.owner().equals(owner)).toList();
        }

        /** How many times {@code name(} appears in a position that is not one of its own declarations - every
         *  declaration contributes exactly one {@code name(} to the raw census, so the declarations are subtracted
         *  out. Zero means nothing under {@code source/} calls it. */
        int callSites(String name) {
            return callCensus.getOrDefault(name, 0) - declarationCensus.getOrDefault(name, 0);
        }

        /** A method of {@code owner} or of a type it extends, resolved by name AND arity so an overload cannot mask
         *  its own unbounded twin - which is exactly how {@code AuditTrail.query/6 -> query/4} hides behind
         *  {@code query}. */
        Optional<Decl> sibling(String owner, String name, int arity) {
            Set<String> owners = new LinkedHashSet<>();
            owners.add(owner);
            Deque<String> queue = new ArrayDeque<>(supertypes.getOrDefault(owner, Set.of()));
            while (!queue.isEmpty()) {
                String next = queue.poll();
                if (owners.add(next)) {
                    queue.addAll(supertypes.getOrDefault(next, Set.of()));
                }
            }
            return decls.stream()
                    .filter(decl -> owners.contains(decl.owner()))
                    .filter(decl -> decl.name().equals(name) && decl.parameters().size() == arity)
                    .findFirst();
        }

        /** What a carrier type carries: the rule that separates {@code Fetched} from {@code Download} from
         *  {@code Head}, which nothing else in a signature does. */
        Cost carrierOf(String simpleName) {
            List<Parameter> components = carriers.get(simpleName);
            if (components == null) {
                return Cost.OTHER;
            }
            for (Parameter component : components) {
                if (!BODY_COMPONENTS.contains(component.name().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String type = raw(component.type());
                if (type.equals("byte[]") || type.equals("String") || COLLECTIONS.contains(type)) {
                    return Cost.MATERIALISED;
                }
                if (type.equals("InputStream") || type.equals("OutputStream") || type.equals("Reader")) {
                    return Cost.STREAMED;
                }
            }
            return Cost.OTHER;
        }

        Cost cost(Decl decl) {
            if (decl.parameters().stream().anyMatch(UnsafeApiPrincipleTest::bounding)) {
                return Cost.BOUNDED;
            }
            String returns = decl.returns();
            String raw = raw(returns);
            if (raw.equals("Result") || returns.startsWith("Traversal.Result")) {
                return Cost.BOUNDED;
            }
            if (COLLECTIONS.contains(raw)) {
                return Cost.MATERIALISED;
            }
            if (raw.equals("Optional")) {
                String inner = raw(typeArgument(returns));
                if (COLLECTIONS.contains(inner)) {
                    return Cost.MATERIALISED;
                }
                Cost carried = carrierOf(inner);
                if (carried != Cost.OTHER) {
                    return carried;
                }
            }
            Cost carried = carrierOf(raw);
            if (carried != Cost.OTHER) {
                return carried;
            }
            if (raw.equals("InputStream") || raw.equals("OutputStream") || raw.equals("Reader")
                    || raw.equals("Writer") || raw.equals("Stream")) {
                return Cost.STREAMED;
            }
            if (decl.parameters().stream().anyMatch(parameter -> {
                String type = raw(parameter.type());
                return type.equals("InputStream") || type.equals("OutputStream");
            })) {
                return Cost.STREAMED;
            }
            if (raw.equals("boolean") || raw.equals("long") || raw.equals("int") || raw.equals("void")
                    || raw.equals("Instant") || raw.equals("Duration") || raw.equals("Object")
                    || raw.equals("Optional")) {
                return Cost.METADATA;
            }
            return Cost.OTHER;
        }
    }

    private static Cost costOf(Scan scan, String key) {
        return scan.decls().stream()
                .filter(decl -> decl.key().equals(key))
                .findFirst()
                .map(scan::cost)
                .orElseThrow(() -> new AssertionError("the declaration walk did not find " + key
                        + " - the parser is broken and every leg of this scan would pass vacuously"));
    }

    // --- the scan -----------------------------------------------------------------------------------------------------

    private static final Pattern TYPE_DECL = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed)[ \\t]+)*"
                    + "(?<kind>interface|class|record|enum)[ \\t]+(?<name>\\w+)(?<rest>[^{]*)");

    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^[ \\t]*(?<mods>(?:(?:public|protected|private|static|final|default|abstract|synchronized|native"
                    + "|strictfp)[ \\t]+)*)"
                    + "(?<ret>[\\w.$]+(?:<[^<>;{}]*(?:<[^<>;{}]*>[^<>;{}]*)*>)?(?:\\[\\s*\\])*)[ \\t]+"
                    + "(?<name>\\w+)[ \\t]*\\((?<params>[^;{)]*(?:\\([^)]*\\)[^;{)]*)*)\\)[ \\t\\r\\n]*"
                    + "(?:throws[^;{]*)?(?<end>[{;])");

    /** Return-type positions that are really a statement keyword, so {@code return foo(x);} is not a declaration. */
    private static final Set<String> NOT_A_TYPE = Set.of("return", "new", "case", "throw", "else", "record", "class",
            "interface", "enum", "if", "while", "for", "catch", "switch", "assert", "yield", "do", "default");

    /** The keywords a sibling call may legally follow; anything else before it is a type, so it is a declaration. */
    private static final Set<String> STATEMENT_KEYWORDS = Set.of("return", "throw", "yield", "else", "case", "assert",
            "do", "while", "if", "for", "switch", "instanceof");

    private static Scan scan(Path sourceRoot) throws IOException {
        List<Decl> decls = new ArrayList<>();
        Map<String, List<Parameter>> carriers = new HashMap<>();
        Map<String, Set<String>> supertypes = new HashMap<>();
        Map<String, Integer> callCensus = new HashMap<>();
        Set<String> exported = new TreeSet<>();
        Set<String> read = new TreeSet<>();

        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String name = file.getFileName().toString();
                String relative = sourceRoot.relativize(file).toString().replace(File.separatorChar, '/');
                if (name.equals("module-info.java")) {
                    Matcher exports = Pattern.compile("(?m)^\\s*exports\\s+([\\w.]+)")
                            .matcher(stripComments(readTolerant(file)));
                    while (exports.find()) {
                        exported.add(exports.group(1));
                    }
                    continue;
                }
                if (name.endsWith(".java")) {
                    String code = stripComments(readTolerant(file));
                    decls.addAll(parse(relative, code, carriers, supertypes));
                    census(code, callCensus, true);
                    read.add(relative);
                } else if (RESOURCE.matcher(name).find()) {
                    census(readTolerant(file), callCensus, false);
                }
            }
        }
        Map<String, Integer> declarationCensus = new HashMap<>();
        decls.forEach(decl -> declarationCensus.merge(decl.name(), 1, Integer::sum));
        return new Scan(List.copyOf(decls), Set.copyOf(read), Set.copyOf(exported), Map.copyOf(carriers),
                Map.copyOf(supertypes), Map.copyOf(callCensus), Map.copyOf(declarationCensus));
    }

    /** Shipped text resources a template engine can invoke a method from - templates call getters and accessors, so a
     *  method reached only from one is NOT uncalled. */
    private static final Pattern RESOURCE =
            Pattern.compile("\\.(?:html|jte|js|xml|properties|json|ya?ml|txt)$");

    /** Counts every {@code name(} occurrence, plus {@code ::name} method references in Java sources. */
    private static void census(String text, Map<String, Integer> census, boolean java) {
        Matcher invocations = Pattern.compile("(\\w+)\\s*\\(").matcher(text);
        while (invocations.find()) {
            census.merge(invocations.group(1), 1, Integer::sum);
        }
        if (java) {
            Matcher references = Pattern.compile("::\\s*(\\w+)").matcher(text);
            while (references.find()) {
                census.merge(references.group(1), 1, Integer::sum);
            }
        }
    }

    private static List<Decl> parse(String relative, String code, Map<String, List<Parameter>> carriers,
                                    Map<String, Set<String>> supertypes) {
        String packageName = "";
        Matcher declaration = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(code);
        if (declaration.find()) {
            packageName = declaration.group(1);
        }

        record Span(String name, String kind, int start, int end) {
        }
        List<Span> types = new ArrayList<>();
        Matcher typeDecl = TYPE_DECL.matcher(code);
        while (typeDecl.find()) {
            int open = code.indexOf('{', typeDecl.end());
            if (open < 0) {
                continue;
            }
            int close = matchBrace(code, open);
            if (close < 0) {
                continue;
            }
            String name = typeDecl.group("name");
            String rest = typeDecl.group("rest");
            types.add(new Span(name, typeDecl.group("kind"), open, close));

            Set<String> supers = new LinkedHashSet<>();
            Matcher extend = Pattern.compile("(?:extends|implements)\\s+([^{]*)").matcher(rest);
            while (extend.find()) {
                for (String piece : extend.group(1).split(",")) {
                    String simple = raw(piece.trim());
                    if (!simple.isEmpty()) {
                        supers.add(simple);
                    }
                }
            }
            supertypes.computeIfAbsent(name, key -> new LinkedHashSet<>()).addAll(supers);

            if (typeDecl.group("kind").equals("record")) {
                int lp = rest.indexOf('(');
                int rp = rest.lastIndexOf(')');
                if (lp >= 0 && rp > lp) {
                    carriers.put(name, parameters(rest.substring(lp + 1, rp)));
                }
            }
        }

        List<Decl> decls = new ArrayList<>();
        Matcher method = METHOD_DECL.matcher(code);
        while (method.find()) {
            String returns = method.group("ret").trim();
            if (NOT_A_TYPE.contains(returns)) {
                continue;
            }
            Span owner = null;
            for (Span span : types) {
                if (span.start() < method.start() && method.start() < span.end()
                        && (owner == null || span.start() > owner.start())) {
                    owner = span;
                }
            }
            String name = method.group("name");
            if (owner == null || name.equals(owner.name())) {
                continue;                                       // a top-level statement, or a constructor
            }
            boolean hasBody = method.group("end").equals("{");
            String body = "";
            if (hasBody) {
                int open = method.end() - 1;
                int close = matchBrace(code, open);
                if (close > 0) {
                    body = code.substring(open + 1, close);
                }
            }
            decls.add(new Decl(relative, packageName, owner.name(), owner.kind(),
                    method.group("mods") + annotations(code, method.start()), returns, name,
                    parameters(method.group("params")), body, hasBody));
        }
        return decls;
    }

    /** The annotations sitting immediately above a declaration, joined - {@code "@Override @Bean "}. */
    private static String annotations(String code, int declarationStart) {
        StringBuilder out = new StringBuilder();
        int cursor = declarationStart;
        while (cursor > 0) {
            int lineEnd = cursor - 1;
            int lineStart = code.lastIndexOf('\n', lineEnd - 1) + 1;
            if (lineStart >= lineEnd) {
                break;
            }
            String line = code.substring(lineStart, lineEnd).trim();
            if (!line.startsWith("@")) {
                break;
            }
            out.insert(0, line + " ");
            cursor = lineStart;
        }
        return out.toString();
    }

    private static List<Parameter> parameters(String text) {
        List<Parameter> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index <= text.length(); index++) {
            char c = index < text.length() ? text.charAt(index) : ',';
            if (c == '<' || c == '(') {
                depth++;
            } else if (c == '>' || c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                String piece = text.substring(start, index).trim();
                start = index + 1;
                if (piece.isEmpty()) {
                    continue;
                }
                piece = piece.replaceAll("^(?:final\\s+|@\\w+(?:\\([^)]*\\))?\\s+)+", "").trim();
                int space = lastTopLevelSpace(piece);
                if (space > 0) {
                    out.add(new Parameter(piece.substring(0, space).trim(), piece.substring(space + 1).trim()));
                }
            }
        }
        return out;
    }

    private static int lastTopLevelSpace(String text) {
        int depth = 0;
        for (int index = text.length() - 1; index >= 0; index--) {
            char c = text.charAt(index);
            if (c == '>' || c == ')') {
                depth++;
            } else if (c == '<' || c == '(') {
                depth--;
            } else if (Character.isWhitespace(c) && depth == 0) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Every invocation in a body that is <em>not</em> a call on another object - a bare {@code name(&hellip;)}, a
     * {@code this.}/{@code super.} call - keyed by name and argument count. A call on a receiver is somebody else's
     * bound; only a sibling call is the interface promising one thing and doing another.
     */
    private static Set<Call> calls(String body) {
        Set<Call> out = new LinkedHashSet<>();
        Matcher invocation = Pattern.compile("(\\w+)\\s*\\(").matcher(body);
        while (invocation.find()) {
            int before = invocation.start() - 1;
            while (before >= 0 && Character.isWhitespace(body.charAt(before))) {
                before--;
            }
            if (before >= 0 && body.charAt(before) == '.') {
                if (!receiverIsSelf(body, before)) {
                    continue;
                }
            } else if (before >= 1 && body.charAt(before) == ':' && body.charAt(before - 1) == ':') {
                continue;                                       // a method reference, not an invocation here
            } else if (before >= 0 && Character.isJavaIdentifierPart(body.charAt(before))
                    && !STATEMENT_KEYWORDS.contains(identifierEndingAt(body, before))) {
                continue;                                       // preceded by a type: a declaration, or a `new`
            }
            int arity = arity(body, body.indexOf('(', invocation.start()));
            if (arity >= 0) {
                out.add(new Call(invocation.group(1), arity));
            }
        }
        return out;
    }

    private static boolean receiverIsSelf(String body, int dot) {
        String receiver = identifierEndingAt(body, dot - 1);
        return receiver.equals("this") || receiver.equals("super");
    }

    private static String identifierEndingAt(String body, int end) {
        int start = end + 1;
        while (start > 0 && Character.isJavaIdentifierPart(body.charAt(start - 1))) {
            start--;
        }
        return body.substring(start, end + 1);
    }

    /** The number of top-level arguments of the call whose {@code (} is at {@code open}, or -1 when unbalanced. */
    private static int arity(String body, int open) {
        if (open < 0) {
            return -1;
        }
        int depth = 0;
        int separators = 0;
        boolean any = false;
        for (int index = open; index < body.length(); index++) {
            char c = body.charAt(index);
            if (c == '"' || c == '\'') {
                char quote = c;
                index++;
                while (index < body.length() && body.charAt(index) != quote) {
                    if (body.charAt(index) == '\\') {
                        index++;
                    }
                    index++;
                }
                any = true;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    return any || separators > 0 ? separators + 1 : 0;
                }
                continue;
            }
            if (depth == 1 && c == ',') {
                separators++;
            } else if (depth >= 1 && !Character.isWhitespace(c)) {
                any = true;
            }
        }
        return -1;
    }

    private static int matchBrace(String code, int open) {
        int depth = 0;
        for (int index = open; index < code.length(); index++) {
            char c = code.charAt(index);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static void delete(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Read a source file charset-tolerantly, exactly as {@link UnboundedListingPrincipleTest} does, so a file
     *  carrying non-UTF-8 bytes never makes the scan throw. */
    private static String readTolerant(Path file) throws IOException {
        try {
            return Files.readString(file);
        } catch (CharacterCodingException malformed) {
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        }
    }

    /** Blanks out {@code //} and block comments (preserving newlines and string/char literals), so a signature named
     *  in prose is never parsed as a declaration. The sibling structural guards' routine. */
    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int n = text.length();
        int state = 0; // 0 code, 1 string, 2 char, 3 line-comment, 4 block-comment
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            char next = i + 1 < n ? text.charAt(i + 1) : '\0';
            switch (state) {
                case 0 -> {
                    if (c == '"') { out.append(c); state = 1; }
                    else if (c == '\'') { out.append(c); state = 2; }
                    else if (c == '/' && next == '/') { out.append("  "); i++; state = 3; }
                    else if (c == '/' && next == '*') { out.append("  "); i++; state = 4; }
                    else { out.append(c); }
                }
                case 1 -> {
                    out.append(c);
                    if (c == '\\' && i + 1 < n) { out.append(text.charAt(++i)); }
                    else if (c == '"') { state = 0; }
                }
                case 2 -> {
                    out.append(c);
                    if (c == '\\' && i + 1 < n) { out.append(text.charAt(++i)); }
                    else if (c == '\'') { state = 0; }
                }
                case 3 -> {
                    if (c == '\n') { out.append('\n'); state = 0; } else { out.append(' '); }
                }
                case 4 -> {
                    if (c == '*' && next == '/') { out.append("  "); i++; state = 0; }
                    else { out.append(c == '\n' ? '\n' : ' '); }
                }
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** The module sources directory, located exactly as the sibling structural scans do. */
    private static Path sourceRoot() {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("source")) && Files.isDirectory(dir.resolve("build/jenesis"))) {
                return dir.resolve("source");
            }
        }
        throw new AssertionError("could not locate the core repo root (an ancestor holding source/ beside "
                + "build/jenesis) from working directory " + start + " - this structural check must run from the "
                + "repository tree so it can read the module sources");
    }
}
