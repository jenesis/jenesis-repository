package build.jenesis.repository.importer.testkit;

import module java.base;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.format.testkit.WitnessStore;
import build.jenesis.repository.importer.ImportFailure;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.ImportSourceProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;

/**
 * The executable {@link ImportSource} / {@link ImportSourceProvider} contract: one parameterized body of behavioural
 * checks that every connector runs through an {@link ImportFixture}, so a migration property is stated once and proven
 * N times instead of being re-interpreted in N hand-written connector suites.
 *
 * <p>{@code ImporterContractTest} had taken the census as far as discovery and coordinate derivation; what a migration
 * actually promises - that an interrupted run resumes rather than starts over, that a multi-gigabyte artifact is never
 * held in heap, that a refused credential is distinguishable from a throttle, that a connector without what it needs
 * declines instead of failing later - was prose. Each {@link Property} below is one of those promises.
 *
 * <p>Two checks are deliberately built on proofs rather than observations, because the observation a naive check makes
 * is also what a broken implementation produces:
 * <ul>
 *   <li>{@link Property#STREAMS_ASSET_CONTENT} feeds a {@link GeneratedBody} that never exists as an array and asks a
 *       {@link build.jenesis.repository.format.testkit.WitnessStore} how much of it the connector had already read when
 *       it handed the stream over. Zero is the streaming answer; a connector that buffered the download shows the whole
 *       length. The scripted incumbent additionally refuses to serve that body through the buffered {@code fetch}
 *       overload at all, so the other shape of materialising it fails by name. No timing, no heap sampling, and no
 *       small fixture blob - a 40-byte body makes a streaming and a buffering connector indistinguishable.</li>
 *   <li>{@link Property#RESUMES_WITHOUT_DUPLICATING} interrupts a real walk by throwing out of the checkpoint the
 *       connector itself reported, then resumes a fresh source from exactly that cursor and requires the two runs to
 *       concatenate into the complete walk - so "it resumes" is a statement about the assets delivered, not about a
 *       cursor string being non-null.</li>
 * </ul>
 *
 * <p>Assertion-library-free on purpose: a check throws {@link AssertionError} naming the connector, the property and the
 * expectation, so this module stays {@code java.base} + the importer SPI and a downstream distribution can require it
 * for its own connector fixtures exactly as it already requires the store testkit. The JUnit driver lives under
 * {@code test/**} and turns each check into one dynamic test.
 *
 * <h2>Clauses this kit discharges (T-304)</h2>
 *
 * {@code SELF_SKIPS_WITHOUT_CREDENTIALS} is {@code ImportSourceProvider}'s absence-sentinel and selection-failure
 * pair, which its own javadoc already cites. The kit's four other properties are about {@code ImportSource}, which
 * is not an inventoried surface (no {@code uses}/{@code provides} clause names it), so they are claimed nowhere.
 *
 * @jenesis.covers build.jenesis.repository.importer.ImportSourceProvider 3, 4
 */
public final class ImportContract {

    /** The artifact the streaming leg pulls: far past any plausible buffer, so a materialising connector is
     *  unmistakable, and cheap enough to stream through a temporary directory in a unit test. */
    private static final long STREAMED_BYTES = 5L << 20;

    /** The upstream statuses every connector is driven through, and what each must classify to. One table, so five
     *  connectors cannot arrive at five readings of the same refusal. */
    private static final Map<Integer, ImportFailure.Kind> CLASSIFIED = new LinkedHashMap<>(Map.of(
            401, ImportFailure.Kind.AUTH,
            404, ImportFailure.Kind.MISSING,
            503, ImportFailure.Kind.TRANSIENT));

    /** The credentials the self-skip leg authenticates with - distinctive, so a recorded header is unambiguous. */
    private static final String USERNAME = "t203-operator", PASSWORD = "t203-secret";

    /** A config key no deployment sets, used to prove the required-config self-disable really bites. */
    private static final String ABSENT_CONFIG = "JENESIS_T203_REQUIRED_KEY_THAT_IS_NEVER_SET";

    /**
     * One documented contract clause. The enum is the kit's vocabulary: a fixture excludes a property by name and
     * reason, and the census fails on a property no fixture anywhere exercises, so the list can never grow a clause
     * that is asserted nowhere.
     */
    public enum Property {
        /** An interrupted walk resumes from the cursor it checkpointed: the resumed run delivers the rest of the
         *  corpus, re-delivers nothing the interrupted run had fully consumed, and ends on the terminal {@code null}
         *  ({@code ImportSource} clauses 2 and 7). */
        RESUMES_WITHOUT_DUPLICATING,
        /** An asset is downloaded only when the consumer opens it, and its bytes go from the incumbent to storage
         *  unread - zero bytes produced at the moment the store is handed the stream ({@code ImportSource} clause 4,
         *  &sect;1). */
        STREAMS_ASSET_CONTENT,
        /** A refused credential, an absent repository and an unavailable instance surface as three distinguishable
         *  {@link ImportFailure.Kind}s rather than one {@code IOException} a caller would have to string-match
         *  ({@code ImportSource} clause 5). */
        CLASSIFIES_FAILURES,
        /** A connector without what it needs steps aside rather than half-building: it declines a request missing a
         *  declared requirement, self-disables when its required config is unset, and walks anonymously - sending no
         *  credential header at all - when the request carries no credentials ({@code ImportSourceProvider} clauses 3
         *  and 4). */
        SELF_SKIPS_WITHOUT_CREDENTIALS,
        /** Every path the walk reports is one a store write may address: it passes both the source-side
         *  {@link ImportSource#safePath} screen and the importer-side {@link RepositoryImporter#importable} screen, and
         *  a traversal-laced listing entry is skipped rather than reported ({@code ImportSource} clause 6). */
        REPORTS_ONLY_IMPORTABLE_PATHS
    }

    /** One named, independently runnable contract check. */
    public record Check(Property property, String name, Body body) {

        public Check {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(body, "body");
        }
    }

    /** The body of a {@link Check}, run against a fixture and a fresh, empty store. */
    @FunctionalInterface
    public interface Body {
        void run(ImportFixture fixture, ArtifactStore store) throws Exception;
    }

    private ImportContract() {
    }

    /**
     * Every contract check, in declaration order, independent of any fixture. The list is the contract: a connector
     * runs all of it or names - with a reason - the properties its protocol does not have.
     */
    public static List<Check> checks() {
        return List.of(
                new Check(Property.RESUMES_WITHOUT_DUPLICATING,
                        "an interrupted walk resumes from its own cursor without losing or repeating an asset",
                        ImportContract::resumesWithoutDuplicating),
                new Check(Property.STREAMS_ASSET_CONTENT,
                        "an asset is fetched only when opened and reaches storage unread",
                        ImportContract::streamsAssetContent),
                new Check(Property.CLASSIFIES_FAILURES,
                        "a refusal, an absence and an outage are three distinguishable failures",
                        ImportContract::classifiesFailures),
                new Check(Property.SELF_SKIPS_WITHOUT_CREDENTIALS,
                        "the connector declines what it cannot run and walks anonymously without credentials",
                        ImportContract::selfSkipsWithoutCredentials),
                new Check(Property.REPORTS_ONLY_IMPORTABLE_PATHS,
                        "every reported path is one a store write may address, and a laced one is skipped",
                        ImportContract::reportsOnlyImportablePaths));
    }

    /**
     * The checks {@code fixture} runs: every check whose property the fixture does not exclude. Excluding a property
     * without a reason fails here rather than silently shrinking the suite.
     */
    public static List<Check> checks(ImportFixture fixture) {
        Objects.requireNonNull(fixture, "fixture");
        fixture.unsupported().forEach((property, reason) -> {
            Objects.requireNonNull(property, "unsupported property");
            if (reason == null || reason.isBlank()) {
                throw new AssertionError("The '" + fixture.source() + "' fixture excludes " + property
                        + " without a reason; an exclusion must say which part of the connector's protocol does not "
                        + "have the property, and where the property is proven instead.");
            }
        });
        return checks().stream().filter(check -> !fixture.unsupported().containsKey(check.property())).toList();
    }

    // --- the contract ------------------------------------------------------------------------------------------

    private static void resumesWithoutDuplicating(ImportFixture fixture, ArtifactStore store) throws Exception {
        ImportFixture.Corpus corpus = fixture.corpus();
        List<String> expected = corpus.paths();
        isTrue(expected.size() >= 2, fixture,
                "a resume leg needs a corpus of at least two assets spanning two checkpointed batches; this fixture "
                        + "scripts " + expected.size());

        // The complete walk first: the corpus really delivers what the fixture claims, in the order it claims - which
        // is what makes a cursor mean anything at all (clause 7). Without this the resume comparison below could pass
        // over a walk that never reported the assets the fixture named.
        Walk complete = walk(fixture, corpus.upstream(), fixture.request(), false);
        equal(complete.paths(), expected, fixture, "a complete walk reports the corpus in the fixture's declared order");
        isTrue(complete.terminated(), fixture,
                "a complete walk ends by checkpointing a null cursor - that null is how a job knows it is done, and a "
                        + "walk that never reports it looks exactly like one that was interrupted");

        // ... now interrupt it. The walk is stopped by throwing out of the FIRST resumable checkpoint the connector
        // itself reported, so the cursor under test is the connector's own idea of "everything up to here is safely
        // consumed", not a cursor this check invented.
        Walk interrupted = walk(fixture, fixture.corpus().upstream(), fixture.request(), true);
        notNull(interrupted.cursor(), fixture,
                "an interrupted walk must have reported a resumable cursor before its terminal null; without one an "
                        + "interrupted migration can only start over");
        isTrue(!interrupted.paths().isEmpty(), fixture,
                "the first checkpoint must come after assets were consumed, or it certifies no progress");

        Walk resumed = walk(fixture, fixture.corpus().upstream(),
                fixture.request().withCursor(interrupted.cursor()), false);
        isTrue(!resumed.paths().isEmpty(), fixture,
                "the resumed walk delivered nothing, so this corpus does not actually exercise resumption - the "
                        + "interrupted run had already consumed everything. Script more assets past the first "
                        + "checkpoint.");

        List<String> repeated = resumed.paths().stream().filter(interrupted.paths()::contains).toList();
        if (!repeated.isEmpty()) {
            throw failure(fixture, "the resumed walk re-delivered " + repeated.size() + " asset(s) the interrupted run "
                    + "had already fully consumed before it checkpointed '" + interrupted.cursor() + "': " + repeated
                    + ". A cursor promises those assets are behind us; re-importing them costs the whole transfer "
                    + "again on every resume.");
        }
        List<String> together = new ArrayList<>(interrupted.paths());
        together.addAll(resumed.paths());
        equal(together, expected, fixture,
                "the interrupted run and the resumed run must concatenate into the complete walk - an asset in neither "
                        + "is silently lost by the migration, which is the failure a resume exists to prevent");
        isTrue(resumed.terminated(), fixture, "the resumed walk runs to the terminal null cursor");
    }

    private static void streamsAssetContent(ImportFixture fixture, ArtifactStore store) throws Exception {
        GeneratedBody body = GeneratedBody.of(STREAMED_BYTES);

        // Laziness first: an asset the consumer never opens costs nothing. The orchestrator skips every asset whose
        // format no installed importer handles, so a walk that eagerly downloaded would pay the full bandwidth of a
        // foreign-format repository to throw it away.
        ImportFixture.Streamed listing = fixture.streamed(body);
        ImportSource enumerating = fixture.build(listing.upstream(), fixture.request());
        List<String> reported = new ArrayList<>();
        enumerating.forEach((_, path, _) -> reported.add(path), _ -> {
        });
        isTrue(reported.contains(listing.path()), fixture,
                "the streaming fixture's asset must be reported at '" + listing.path() + "'; the walk reported "
                        + reported);
        equal(body.produced(), 0L, fixture,
                "an asset the consumer never opened was downloaded anyway - " + body.produced() + " bytes of it. "
                        + "Content.open() is deferred precisely so a skipped asset costs no bandwidth");

        // ... and now the transfer itself. The witness records how much of the body the connector had already read at
        // the instant the store was handed the stream: zero is streaming, the whole length is a connector that
        // materialised a multi-gigabyte artifact to hand it on.
        body.rewind();
        ImportFixture.Streamed streamed = fixture.streamed(body);
        ImportSource source = fixture.build(streamed.upstream(), fixture.request());
        WitnessStore witness = WitnessStore.over(store).watch(body);
        source.forEach((_, path, content) -> {
            if (!path.equals(streamed.path())) {
                return;
            }
            try (InputStream in = content.open()) {
                witness.writeBlob(in);
            }
        }, _ -> {
        });

        isTrue(witness.blobWrites() > 0, fixture,
                "the asset never reached the content-addressed write, so the streaming tripwire never armed");
        long produced = witness.producedBeforeStore().orElseThrow(() -> failure(fixture,
                "no content-addressed write was witnessed, so the streaming tripwire never armed"));
        if (produced != 0L) {
            throw failure(fixture, "the connector had already read " + produced + " of the asset's " + body.length()
                    + " bytes when it handed the stream to the store. An asset copies from the incumbent straight to "
                    + "storage unread (§1) - anything else means it was materialised first, and a migration of a "
                    + "multi-gigabyte artifact would carry the whole thing in heap.");
        }
        isTrue(store.exists("blobs/" + body.sha256()), fixture,
                "the streamed asset lands at its own content address blobs/" + body.sha256());
        equal(store.size("blobs/" + body.sha256()), body.length(), fixture,
                "the whole asset landed, not a prefix - a streaming copy that stops early is worse than a buffered one");
    }

    private static void classifiesFailures(ImportFixture fixture, ArtifactStore store) throws Exception {
        Map<ImportFailure.Kind, Integer> byKind = new LinkedHashMap<>();
        for (Map.Entry<Integer, ImportFailure.Kind> expected : CLASSIFIED.entrySet()) {
            int status = expected.getKey();
            ImportSource source = fixture.build(fixture.failing(status), fixture.request());
            ImportFailure failure = null;
            try {
                source.forEach((_, _, content) -> {
                    try (InputStream in = content.open()) {
                        in.transferTo(OutputStream.nullOutputStream());
                    }
                }, _ -> {
                });
            } catch (ImportFailure thrown) {
                failure = thrown;
            } catch (IOException other) {
                throw failure(fixture, "an incumbent answering " + status + " surfaced as a plain "
                        + other.getClass().getName() + " ('" + other.getMessage() + "'). A caller then has nothing to "
                        + "key a retry, a credential prompt or a bad-request answer on but the message text - which is "
                        + "exactly the collapse ImportFailure.Kind exists to undo.");
            }
            notNull(failure, fixture, "an incumbent answering " + status + " must fail the walk, not be absorbed - a "
                    + "migration that reported success over a refused listing has silently imported nothing");
            equal(failure.kind(), expected.getValue(), fixture,
                    "an incumbent answering " + status + " is classified (message was: '" + failure.getMessage() + "')");
            byKind.put(failure.kind(), status);
        }
        equal(byKind.size(), CLASSIFIED.size(), fixture,
                "the three refusals must land on three different kinds; collapsing any two of them back together is "
                        + "the defect this property exists to hold open (got " + byKind + ")");
    }

    private static void selfSkipsWithoutCredentials(ImportFixture fixture, ArtifactStore store) throws Exception {
        ImportSourceProvider provider = fixture.provider();

        // A walk with no credentials is ANONYMOUS, not one carrying an empty or fabricated credential: an incumbent
        // that allows anonymous reads must see no Authorization at all, or it answers 401 to a migration that would
        // have worked.
        ImportFixture.Corpus anonymous = fixture.corpus();
        drain(fixture.build(anonymous.upstream(), fixture.request()));
        List<ScriptedUpstream.Request> credentialled = anonymous.upstream().requests().stream()
                .filter(ScriptedUpstream.Request::authenticated).toList();
        if (!credentialled.isEmpty()) {
            throw failure(fixture, "a walk over a request carrying no credentials still sent a credential header on "
                    + credentialled.size() + " request(s), first at " + credentialled.getFirst().url()
                    + ". Without credentials a connector reads anonymously.");
        }

        // ... and when they are supplied they are actually used, or the anonymous leg above proves nothing.
        ImportFixture.Corpus authenticated = fixture.corpus();
        drain(fixture.build(authenticated.upstream(), fixture.request().withCredentials(USERNAME, PASSWORD)));
        isTrue(authenticated.upstream().requests().stream().anyMatch(ScriptedUpstream.Request::authenticated), fixture,
                "a request carrying credentials must authenticate the walk (one of " + ScriptedUpstream.CREDENTIAL_HEADERS
                        + "), or the anonymous leg above passes for a connector that simply never authenticates");

        // A request missing what the provider declared it needs is declined - null, the documented sentinel - rather
        // than half-built into a source that fails asynchronously somewhere in the operator's migration.
        ImportRequest bare = new ImportRequest(fixture.request().url(), fixture.request().repository());
        ImportSource fromBare;
        try {
            fromBare = provider.create(bare, fixture.corpus().upstream());
        } catch (RuntimeException thrown) {
            throw failure(fixture, "the provider threw " + thrown + " for a request missing its declared requirement. "
                    + "The documented answer is null, which the caller reports as a bad request.");
        }
        if (provider.requiresFormat()) {
            isTrue(fromBare == null, fixture,
                    "the provider declares requiresFormat(), so a request without an ecosystem format must build no "
                            + "source - it cannot know which layout the assets take");
        } else {
            notNull(fromBare, fixture,
                    "the provider declares no format requirement, so a bare url+repository request must build a "
                            + "source; declining one would make requiresFormat() a lie the console renders");
        }

        // The other half of self-skipping is discovery-time: a connector whose required config is unset is never
        // offered a migration at all. Proven both ways, so "active" is not vacuously true for every input.
        isTrue(Features.active(provider.name(), provider.requiredConfig()), fixture,
                "the connector must be active in a deployment that configured nothing, or it can never be selected");
        isTrue(!Features.active(provider.name(), Set.of(ABSENT_CONFIG)), fixture,
                "a connector whose required config key is unset must self-disable at discovery (Features.active), so "
                        + "an operator sees one boot line naming the missing key instead of a failure per migration");
    }

    private static void reportsOnlyImportablePaths(ImportFixture fixture, ArtifactStore store) throws Exception {
        ImportFixture.Corpus corpus = fixture.corpus();
        Walk walk = walk(fixture, corpus.upstream(), fixture.request(), false);
        isTrue(!walk.paths().isEmpty(), fixture, "the corpus must report at least one asset, or this asserts nothing");
        for (String path : walk.paths()) {
            isTrue(ImportSource.safePath(path), fixture,
                    "the walk reported '" + path + "', which fails the SPI's own safePath screen - that path becomes a "
                            + "store write on the import's write half");
            // The two screens must agree. The source screens what it REPORTS and the importer screens what it LAYS
            // OUT; a path either side accepts and the other refuses is a seam where an asset is either silently
            // dropped or composed into a key nobody intended - which is exactly the shape T-202a found one seam over.
            isTrue(RepositoryImporter.importable(path), fixture,
                    "the walk reported '" + path + "', which passes ImportSource.safePath but fails "
                            + "RepositoryImporter.importable - the read half and the write half disagree about what a "
                            + "legal asset path is, so this asset is refused by the importer after being enumerated");
        }

        Optional<ImportFixture.Corpus> hostile = fixture.hostile();
        if (hostile.isPresent()) {
            Walk laced = walk(fixture, hostile.get().upstream(), fixture.request(), false);
            equal(laced.paths(), hostile.get().paths(), fixture,
                    "a listing carrying traversal-laced entries must report exactly the legitimate ones - a laced "
                            + "entry is skipped, and the walk continues rather than failing (one bad row never aborts "
                            + "a migration)");
        }
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    /** What one walk did: the asset paths it reported in order, the last non-null cursor it checkpointed, and whether
     *  it reached the terminal null. */
    private record Walk(List<String> paths, String cursor, boolean terminated) {
    }

    /** Thrown out of a checkpoint to interrupt a walk exactly where the connector said it was safe to stop. */
    private static final class Interrupted extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        Interrupted() {
            super(null, null, false, false);
        }
    }

    private static Walk walk(ImportFixture fixture, ScriptedUpstream upstream, ImportRequest request,
                            boolean interrupt) throws IOException {
        ImportSource source = fixture.build(upstream, request);
        List<String> paths = new ArrayList<>();
        String[] cursor = new String[1];
        boolean[] terminated = new boolean[1];
        try {
            source.forEach((_, path, _) -> paths.add(path), reached -> {
                if (reached == null) {
                    terminated[0] = true;
                    return;
                }
                cursor[0] = reached;
                if (interrupt) {
                    throw new Interrupted();
                }
            });
        } catch (Interrupted _) {
            // the walk was stopped at the connector's own first resumable checkpoint
        }
        return new Walk(List.copyOf(paths), cursor[0], terminated[0]);
    }

    /** Walk every asset and read every body - the shape the credential legs need, since a connector may authenticate
     *  the listing, the download, or both. */
    private static void drain(ImportSource source) throws IOException {
        source.forEach((_, _, content) -> {
            try (InputStream in = content.open()) {
                in.transferTo(OutputStream.nullOutputStream());
            }
        }, _ -> {
        });
    }

    private static void equal(Object actual, Object expected, ImportFixture fixture, String what) {
        if (!Objects.deepEquals(actual, expected)) {
            throw failure(fixture, what + " - expected " + expected + " but was " + actual);
        }
    }

    private static void isTrue(boolean actual, ImportFixture fixture, String what) {
        if (!actual) {
            throw failure(fixture, what);
        }
    }

    private static void notNull(Object actual, ImportFixture fixture, String what) {
        if (actual == null) {
            throw failure(fixture, what + " - expected a value but was null");
        }
    }

    private static AssertionError failure(ImportFixture fixture, String message) {
        return new AssertionError(fixture.source() + ": " + message);
    }
}
