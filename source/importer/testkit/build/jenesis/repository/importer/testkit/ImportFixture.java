package build.jenesis.repository.importer.testkit;

import module java.base;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.ImportSourceProvider;

/**
 * How one import connector registers with the shared {@link ImportContract}: the fixture scripts the incumbent - a
 * multi-page corpus, one artifact-sized asset, a refusing instance, a listing carrying a hostile path - and the kit
 * then drives <em>every</em> behavioural property over it.
 *
 * <p>A connector is covered by writing a fixture, never by adding assertions to its own suite. The five connectors
 * arrived at five different answers for the same questions - Artifactory's Pro listing ignores a resume cursor
 * altogether where its OSS crawl honours one, Maven turned a refused credential into "enable directory listing" - and
 * a per-connector suite is structurally unable to notice that, because each is green over the behaviour its own author
 * thought of.
 *
 * <p>Three declarations carry the fixture's honesty:
 * <ul>
 *   <li>{@link #providerClass()} keys the fixture to a statically declared {@code provides ... with ...} class, so the
 *       census can prove no declared or runtime-discovered connector is unfixtured (and no fixture names a dead one);</li>
 *   <li>{@link #provider()} is discovered through {@code ServiceLoader} by {@link ImportSourceProvider#handles name},
 *       never constructed, so every leg exercises the provider the server would actually reach - including its
 *       decline-rather-than-half-build path;</li>
 *   <li>{@link #unsupported()} names the properties this connector's <em>protocol</em> genuinely does not have, each
 *       with a mandatory reason saying where the property is proven instead. A property no fixture anywhere exercises
 *       fails the census, so an exclusion can shrink one connector's coverage but never the contract's.</li>
 * </ul>
 *
 * <p>Every accessor builds a <em>fresh</em> {@link ScriptedUpstream}: the checks compare what one walk requested
 * against what another did, so a shared recorder would blur the two.
 */
public interface ImportFixture {

    /** The {@link ImportSourceProvider#name() source name} this fixture drives ({@code nexus}, {@code artifactory},
     *  {@code maven}, {@code index}, {@code jenesis}) - the string an operator submits. */
    String source();

    /** The fully qualified {@code ImportSourceProvider} implementation class this fixture covers, as the census parses
     *  it out of the connector module's {@code provides ... with ...} clause. */
    String providerClass();

    /** The installed provider, discovered through the SPI exactly as the server discovers it - so a fixture works
     *  through {@code ServiceLoader} alone and never against a locally constructed provider. */
    default ImportSourceProvider provider() {
        return ServiceLoader.load(ImportSourceProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(candidate -> candidate.handles(source()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no discovered ImportSourceProvider handles '" + source()
                        + "', so its contract cannot run - is its module missing from the census graph?"));
    }

    /** The request every scenario is built from: the incumbent's URL and repository, plus the ecosystem format when
     *  this provider {@link ImportSourceProvider#requiresFormat() requires} one. Never carries credentials or a
     *  cursor - the checks add those themselves. */
    ImportRequest request();

    /** The multi-asset, multi-batch corpus the resume and path legs walk. Its {@link Corpus#paths()} are every asset
     *  the walk must report, in walk order, and the corpus must span at least two checkpointed batches or the resume
     *  leg has nothing to resume from. */
    Corpus corpus();

    /** A scripted incumbent and the asset paths a complete walk over it reports, in order. */
    record Corpus(ScriptedUpstream upstream, List<String> paths) {

        public Corpus {
            Objects.requireNonNull(upstream, "upstream");
            paths = List.copyOf(paths);
        }
    }

    /**
     * The corpus for this connector's <em>derived</em> walk, when it has one: a leg that composes an asset's path out
     * of a coordinate instead of reading it from the incumbent's own listing. Empty by default, because most
     * connectors only ever report what an incumbent listed them.
     *
     * <p>The distinction is the whole point of the property. A listing walk carries whatever the incumbent shows it,
     * so a file it omits is the incumbent's omission. A derived walk carries only what its author thought to compose,
     * so a file it omits is <em>this repository's</em> omission - and an omission on a migration-in is not a missing
     * artifact an operator notices, it is a coordinate that resolves differently afterwards. The corpus must therefore
     * be a coordinate the incumbent serves <b>whole</b>, with every sibling a consumer of the ecosystem resolves
     * against present upstream, and {@link Corpus#paths()} naming every one the walk must carry across.
     */
    default Optional<Corpus> derived() throws IOException {
        return Optional.empty();
    }

    /** The one-asset scenario whose content is {@code body}: an incumbent serving an artifact-sized, generated body,
     *  and the path the walk reports it at. The body must be reachable only through the streaming download. */
    Streamed streamed(GeneratedBody body);

    /** A scripted incumbent holding one artifact-sized asset, and the path the walk reports it at. */
    record Streamed(ScriptedUpstream upstream, String path) {

        public Streamed {
            Objects.requireNonNull(upstream, "upstream");
            Objects.requireNonNull(path, "path");
        }
    }

    /**
     * An incumbent that refuses with {@code status} on whichever leg this connector surfaces an upstream status from -
     * the listing for a connector that owns its enumeration, the asset download for one that borrows a format's. The
     * kit drives the same three statuses through every connector and reads the {@code ImportFailure.Kind} back, so
     * "auth", "missing" and "transient" mean one thing across all five.
     */
    ScriptedUpstream failing(int status);

    /**
     * A listing carrying traversal-laced paths beside legitimate ones, with {@link Corpus#paths()} naming only the
     * legitimate ones - what the walk must report. Empty when this connector's listing format cannot express a hostile
     * path at all (its parser rejects one before the source sees it), which is itself a statement worth reviewing.
     */
    default Optional<Corpus> hostile() {
        return Optional.empty();
    }

    /** The contract properties this connector's protocol does not have, each mapped to the reason and to where the
     *  property is proven instead. Empty by default: an exclusion is a deliberate, reviewable statement. */
    default Map<ImportContract.Property, String> unsupported() {
        return Map.of();
    }

    /** Build a source over {@code upstream} from {@code request} through the discovered provider, failing with a
     *  fixture-named message when the provider declines - the checks all start here, so every leg exercises the
     *  provider rather than a hand-built source. Built the way an import edge builds one
     *  ({@link ImportSourceProvider#open}), so every check in the kit runs against a connector walking a screened
     *  fetcher, for every connector, rather than against a transport no deployment ever hands one. */
    default ImportSource build(ScriptedUpstream upstream, ImportRequest request) {
        ImportSource source = ImportSourceProvider.open(provider(), request, upstream);
        if (source == null) {
            throw new AssertionError(source() + ": the provider declined to build a source from the fixture's own "
                    + "request. A fixture's request must be one this provider accepts, or every check below tests "
                    + "nothing.");
        }
        return source;
    }
}
