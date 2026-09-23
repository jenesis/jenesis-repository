package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.format.testkit.FormatFixture;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Withheld;

/**
 * What an ecosystem format adds to the {@link FormatFixture} seam, and why it needs anything at all.
 *
 * <p><b>The coordinate seam is a different interface here.</b> The base kit's
 * {@code COORDINATE_TRAVERSAL_REFUSED} property is stated over {@code ArtifactLayout}, the {@code publish/}-namespace
 * coordinate mapping Maven and the Jenesis module layout carry. An ecosystem format serves out of the shared
 * {@code blobs/} namespace instead, so its coordinate-to-pointer mapping is {@link BlobLayout} - and that is the seam
 * with teeth, because {@link BlobLayout#blobKeys} is what a retention eviction <em>deletes</em>. {@link #layout()}
 * exposes it and {@link #seed} names one really published coordinate, so {@code BlobLayoutCoordinateSeamTest} can
 * prove the same property over the seam these formats actually have.
 *
 * <p><b>The hold is content-addressed.</b> A {@code publish/}-namespace format is retracted by a
 * {@code PublishInterceptor} answering {@code withheld}; a blobs-namespace one is retracted by the
 * {@code withheld/<hash>} marker convention. {@link #hold} places exactly the markers the retroactive enforcement
 * sweeps place - {@link BlobLayout#blobHashes} resolved through the format's own layout - so the withhold leg is
 * simultaneously a test that the layout resolves anything at all: a {@code blobKeys} that answered empty would make
 * the hold a silent no-op and the leg would fail rather than pass over a version nothing ever retracted.
 *
 * <p><b>Seven ecosystems parse their artifact at publish.</b> A NuGet push reads the {@code .nuspec}, a gem push the
 * gemspec, a Debian push the {@code control}, an RPM push the binary header, a conda push the {@code info/index.json}
 * inside the archive, a Composer push the {@code composer.json} and a CocoaPods push the {@code .podspec.json} - so
 * those formats cannot accept the
 * arbitrary byte body the kit's publish leg hands a fixture, and they exclude that leg for a protocol reason.
 * {@link #publishPackage} is where they publish a <em>real</em> package instead, and
 * {@code PackagedArtifactContract} runs the same two properties (exact bytes back, and a {@code HEAD} answered from
 * metadata over a sealed blob) over it, so nothing is lost - only the shape of the body changes.
 */
interface EcosystemFormatFixture extends FormatFixture {

    /**
     * Every registered fixture, once.
     *
     * <p>It lives here rather than in a suite because there were two of these lists and they had drifted. The
     * census held nineteen; {@code BlobLayoutCoordinateSeamTest} held its own thirteen, and the six missing from it
     * - apk, helm, winget, terraform, swift, homebrew - were skipped by the pointer census in silence for as long
     * as nobody compared the two. Silence rather than failure, because a layout that does not override
     * {@code describePointer} is invisible to that check rather than unverifiable by it, and the seam test's own
     * javadoc had grown a paragraph explaining that those six "want a fixture first" - a rationale for an exemption
     * that had stopped being true, which is how a gap becomes permanent.
     *
     * <p>So a new format joins here and every suite over the fixture set gets it: the contract legs, the coordinate
     * round trip, the hostile-coordinate screen and the pointer census. A format with no fixture yet joins
     * {@code FormatFixtureCensusTest}'s exemptions with its reason instead.
     */
    static List<EcosystemFormatFixture> all() {
        return List.of(
                new ApkFormatFixture(),
                new CargoFormatFixture(),
                new CocoaPodsFormatFixture(),
                new ComposerFormatFixture(),
                new ConanFormatFixture(),
                new CondaFormatFixture(),
                new DebianFormatFixture(),
                new GoFormatFixture(),
                new HelmFormatFixture(),
                new HomebrewFormatFixture(),
                new HuggingFaceFormatFixture(),
                new IvyFormatFixture(),
                new NpmFormatFixture(),
                new NuGetFormatFixture(),
                new PyPiFormatFixture(),
                new RpmFormatFixture(),
                new RubyGemsFormatFixture(),
                new SwiftFormatFixture(),
                new TerraformFormatFixture(),
                new WingetFormatFixture());
    }

    /**
     * The one reason every fixture here currently gives for excluding
     * {@link FormatContract.Property#PROXY_REFUSAL_IS_NOT_AN_ABSENCE}, held here rather than restated thirteen times
     * so that it reads as what it is: a single provisional statement, not thirteen independent findings.
     *
     * <p>The property asks whether this format's pull-through leg serves a path whose <em>absence</em> the client
     * resolves around rather than reports - the shape Gradle Module Metadata has on the Maven leg, where a refusal
     * spelled as a {@code 404} silently becomes a different resolution instead of a failed build. Answering it for an
     * ecosystem means knowing what its real client does with a miss on each proxied path, which is a per-format audit
     * and not something the kit can decide. None has been done, so no fixture here may claim its format is clear
     * of the shape - and none of these exclusions should be read as claiming it.
     *
     * <p>Written as one shared constant deliberately: a fixture that has actually been audited replaces it, either
     * with {@link FormatFixture#elective} supplying the path or with a reason of its own naming what was checked.
     * While the constant is still here, the row is a burn-down list.
     *
     * <p><b>The audit ran on 2026-08-24 and this is now down to one fixture.</b> Eleven formats replaced it with a
     * reason of their own; PyPI replaced it with a real {@link FormatFixture#elective} leg, the PEP 658 sidecar,
     * which was found to HAVE the defect and was fixed (D-290, then D-289's 502 split). What the audit established,
     * and what a later fixture should reason with rather than rediscover: a path classified
     * {@code ProxyRelay.Document.ENUMERATION} already refuses with a {@code 502} and misses with a {@code 404}, so
     * the two are distinguishable by construction and the property holds without a fixture proving it. The shape can
     * only live where a path is {@code PINNED}, or on the unclassified fill path, AND the client resolves around its
     * absence.
     *
     * <p>RubyGems is the one that stayed. Its {@code /quick/Marshal.4.8/<gem>-<version>.gemspec.rz} is {@code PINNED}
     * on the argument that a client which has already fixed gem and version resolves nothing from its absence - which
     * is plausible and is not decidable from our own source. Writing a rationale without knowing what RubyGems does
     * on that miss would be the {@code declared-rows-are-claims} failure this constant exists to prevent, so it keeps
     * the constant until someone establishes the answer.
     */
    String ELECTIVE_PATH_NOT_AUDITED = "no per-format audit has been done of which proxied paths this ecosystem's "
            + "client resolves around rather than reports on a miss, so this fixture states that the question is "
            + "open, not that the answer is no. See EcosystemFormatFixture.ELECTIVE_PATH_NOT_AUDITED";

    /**
     * Whether this format's pointers live under {@code publish/} rather than in the shared {@code blobs}
     * namespace - which decides whether the blobs-namespace coordinate seam applies to it at all.
     *
     * <p>Read off the namespaces the fixture already declares, so it is one statement rather than two that can
     * disagree. Every format here served from {@code blobs} until Ivy, which is a {@code publish/} layout
     * like Maven and Jenesis: for those the coordinate maps to a request-path folder through
     * {@code ArtifactLayout.paths} and a hold retracts by unpublishing under it, so there is no pointer key for
     * {@code BlobLayout} to describe and demanding one would be demanding an empty answer.
     */
    default boolean publishesUnderPublish() {
        return namespaces().contains("publish");
    }

    /** The blobs-namespace coordinate mapping this format carries. Every format whose pointers live in
     *  {@code blobs} implements it; one that does not would fail the census's role check rather than silently
     *  skipping the seam. */
    default BlobLayout layout() {
        RepositoryFormat serving = serving();
        if (serving instanceof BlobLayout blobLayout) {
            return blobLayout;
        }
        throw new AssertionError(format() + ": this format implements no BlobLayout, so its blobs-namespace "
                + "coordinate seam cannot be proven. Every format that serves from blobs/ must map its "
                + "coordinates to their pointers - that is what makes a retroactive hold able to retract them.");
    }

    /**
     * Publish one real version through the format's own write path and name the coordinate its {@link BlobLayout}
     * maps it by. Used by the coordinate-seam checks for their non-vacuity half: the hostile-coordinate assertions
     * mean nothing unless the honest coordinate really does resolve to live pointer keys first.
     */
    Seeded seed(ArtifactStore store) throws IOException;

    /** One published version, as its own layout describes it: the coordinate and version {@link BlobLayout#blobKeys}
     *  resolves, and the request path {@link BlobLayout#servedPaths} maps back to. */
    record Seeded(String coordinate, String version, String servedPath) {

        public Seeded {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(servedPath, "servedPath");
        }
    }

    /**
     * A realistic package published through the format's own write path, for an ecosystem whose publish protocol
     * parses the artifact and therefore cannot take an arbitrary byte body. Empty (the default) for a format that
     * accepts opaque bytes and runs the kit's own publish leg instead.
     */
    default Optional<Packaged> publishPackage(ArtifactStore store) throws IOException {
        return Optional.empty();
    }

    /** A published package: the exact bytes the client uploaded, the request path that serves them back, and the
     *  content address of the {@code blobs/} object holding them (the key the {@code HEAD} leg seals). */
    record Packaged(byte[] artifact, String servedPath, String contentHash) {

        public Packaged {
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(servedPath, "servedPath");
            Objects.requireNonNull(contentHash, "contentHash");
        }
    }

    /**
     * The hold a blobs-namespace format is retracted by: the {@code withheld/<hash>} marker on every content hash this
     * format's own {@link BlobLayout} resolves for the coordinate version - byte for byte what the retroactive
     * KEV/license sweeps mark. Idempotent, as every converge pass is.
     */
    default void hold(ArtifactStore store, String coordinate, String version) throws IOException {
        List<String> hashes = layout().blobHashes(coordinate, version, store);
        if (hashes.isEmpty()) {
            throw new AssertionError(format() + ": blobHashes(" + coordinate + ", " + version + ") resolved nothing, "
                    + "so this hold would retract nothing and the withhold leg would pass vacuously. A "
                    + "blobs-namespace format whose coordinate maps to no content hash cannot honour a retroactive "
                    + "hold at all.");
        }
        for (String hash : hashes) {
            Withheld.mark(store, hash);
        }
    }

    /** Drive one exchange through the discovered format and require the status the write path promises, so a fixture
     *  never seeds silently. */
    default ContractExchange seed(ArtifactStore store, ContractExchange exchange, int expected) throws IOException {
        serving().handle(exchange, store);
        if (exchange.status() != expected) {
            throw new AssertionError(format() + ": seeding " + exchange.method() + " " + exchange.path()
                    + " answered " + exchange.status() + " rather than " + expected);
        }
        return exchange;
    }
}
