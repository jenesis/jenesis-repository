package build.jenesis.repository.importer.contract.test;

import module java.base;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.testkit.ImportContract;
import build.jenesis.repository.importer.testkit.ImportFixture;
import build.jenesis.repository.importer.testkit.ScriptedUpstream;

/**
 * The format-index connector's corpus: the walk is a format's own mirror-style enumeration
 * ({@link ContractIndexedFormat} stands in for it here), and the connector supplies the paging cursor, the laziness and
 * the download screening on top.
 *
 * <p>The corpus is deliberately larger than its peers'. This connector checkpoints every 64 assets, so a four-asset
 * corpus would reach its first checkpoint only at the end of the walk and the resume leg would resume into an empty
 * remainder - passing while proving nothing. Seventy assets put a real remainder past the first checkpoint, which is
 * what the shared leg's "the resumed walk delivered nothing" guard insists on.
 *
 * <p>It is also the one connector whose enumeration failure it does not own: an index the format cannot read surfaces
 * as that format's own {@code IOException}, so the classification leg drives the leg this connector <em>does</em> own -
 * the asset download.
 */
final class IndexImportFixture implements ImportFixture {

    private static final String BASE = "http://index.t203";
    private static final String REPOSITORY = "repo";
    private static final String ROOT = BASE + "/" + REPOSITORY + "/";
    private static final String INDEX = ROOT + "index";

    /** Past the connector's 64-asset checkpoint interval, so the first checkpoint leaves a remainder to resume into. */
    private static final int ASSETS = 70;

    @Override
    public String source() {
        return "index";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.importer.index.IndexSourceProvider";
    }

    @Override
    public ImportRequest request() {
        // The ecosystem format names whose index to walk, so the provider requires it up front.
        return new ImportRequest(URI.create(BASE), REPOSITORY).withFormat(ContractIndexedFormat.NAME);
    }

    @Override
    public Corpus corpus() {
        List<String> paths = IntStream.range(0, ASSETS)
                .mapToObj("alpha/asset-%02d.bin"::formatted)
                .toList();
        ScriptedUpstream upstream = reachable().answering(INDEX, 200, index(paths));
        paths.forEach(path -> upstream.answering(ROOT + path, 200, path));
        return new Corpus(upstream, paths);
    }

    @Override
    public Streamed streamed(GeneratedBody body) {
        String path = "alpha/artifact.bin";
        return new Streamed(reachable()
                .answering(INDEX, 200, index(List.of(path)))
                .generating(ROOT + path, body), path);
    }

    @Override
    public ScriptedUpstream failing(int status) {
        String path = "alpha/one.bin";
        return reachable().answering(INDEX, 200, index(List.of(path))).refusing(ROOT + path, status);
    }

    @Override
    public Optional<Corpus> hostile() {
        List<String> laced = List.of("../" + NexusImportFixture.ESCAPE, "alpha/./" + NexusImportFixture.ESCAPE,
                "alpha/../../" + NexusImportFixture.ESCAPE);
        List<String> good = List.of("alpha/good.bin");
        List<String> all = Stream.concat(laced.stream(), good.stream()).toList();
        ScriptedUpstream upstream = reachable().answering(INDEX, 200, index(all));
        good.forEach(path -> upstream.answering(ROOT + path, 200, path));
        return Optional.of(new Corpus(upstream, good));
    }

    /** The provider probes the walk's root before it builds a source at all, so every scenario must answer it. */
    private static ScriptedUpstream reachable() {
        return ScriptedUpstream.incumbent().answering(ROOT, 200, "");
    }

    /** The stand-in format's index document: one {@code <path> <url>} pair per line, all same-origin with the root so
     *  the connector's credential wrapper authenticates them. */
    private static String index(List<String> paths) {
        StringJoiner document = new StringJoiner("\n");
        paths.forEach(path -> document.add(path + " " + ROOT + path));
        return document.toString();
    }
    @Override
    public Map<ImportContract.Property, String> unsupported() {
        return Map.of(ImportContract.Property.CARRIES_EVERY_DERIVABLE_SIBLING,
                "this source is a pass-through over ProxyFormat.enumerate: the format reads its ecosystem's own published "
                        + "index and this walk reports exactly the coordinates it yields, adding none. Whether an "
                        + "index names a coordinate's siblings is the upstream index's business, not a "
                        + "derivation this connector could get wrong. The maven leg proves the property where "
                        + "paths really are composed");
    }

}
