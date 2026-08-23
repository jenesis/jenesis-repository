package build.jenesis.repository.importer.contract.test;

import module java.base;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.testkit.ImportContract;
import build.jenesis.repository.importer.testkit.ImportFixture;
import build.jenesis.repository.importer.testkit.ScriptedUpstream;

/**
 * The Nexus 3 connector's corpus: the components REST API paged by continuation token, each component carrying its own
 * ecosystem format and each asset a {@code downloadUrl} the listing chooses. Every scripted download URL is
 * same-origin with the base, which is what lets the credential leg see an {@code Authorization} header on a download at
 * all - the connector deliberately drops the operator's credential on a cross-origin URL, and that scoping stays
 * pinned by {@code NexusSourceTest} as the connector-particular behaviour it is.
 */
final class NexusImportFixture implements ImportFixture {

    private static final String BASE = "http://nexus.t203";
    private static final String REPOSITORY = "releases";
    private static final String PAGE_ONE = BASE + "/service/rest/v1/components?repository=" + REPOSITORY;
    private static final String PAGE_TWO = PAGE_ONE + "&continuationToken=page-two";

    @Override
    public String source() {
        return "nexus";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.importer.nexus.NexusSourceProvider";
    }

    @Override
    public ImportRequest request() {
        return new ImportRequest(URI.create(BASE), REPOSITORY);
    }

    @Override
    public Corpus corpus() {
        ScriptedUpstream upstream = ScriptedUpstream.incumbent()
                .answering(PAGE_ONE, 200, page("page-two", "alpha/one.jar", "alpha/two.jar"))
                .answering(PAGE_TWO, 200, page(null, "beta/three.jar", "beta/four.jar"));
        for (String path : List.of("alpha/one.jar", "alpha/two.jar", "beta/three.jar", "beta/four.jar")) {
            upstream.answering(download(path), 200, path);
        }
        return new Corpus(upstream, List.of("alpha/one.jar", "alpha/two.jar", "beta/three.jar", "beta/four.jar"));
    }

    @Override
    public Streamed streamed(GeneratedBody body) {
        String path = "alpha/artifact.bin";
        return new Streamed(ScriptedUpstream.incumbent()
                .answering(PAGE_ONE, 200, page(null, path))
                .generating(download(path), body), path);
    }

    @Override
    public ScriptedUpstream failing(int status) {
        // The listing is where Nexus surfaces a refusal: the components API answers the status and the walk stops
        // before it has an asset to download.
        return ScriptedUpstream.incumbent().refusing(PAGE_ONE, status);
    }

    @Override
    public Optional<Corpus> hostile() {
        ScriptedUpstream upstream = ScriptedUpstream.incumbent()
                .answering(PAGE_ONE, 200, page(null,
                        "../" + ESCAPE, "alpha/../../" + ESCAPE, "alpha/./" + ESCAPE, "alpha\\" + ESCAPE,
                        "alpha/good.jar"))
                .answering(download("alpha/good.jar"), 200, "alpha/good.jar");
        return Optional.of(new Corpus(upstream, List.of("alpha/good.jar")));
    }

    /** The distinctive leaf every laced entry aims at, so a reported path naming it is unmistakably an escape. */
    static final String ESCAPE = "t203-escaped-here.bin";

    private static String download(String path) {
        return BASE + "/repository/" + REPOSITORY + "/" + path;
    }

    /** One components page: {@code items[].assets[]} carrying a path and a download URL, plus the continuation token
     *  that makes the next page (and the resume cursor) exist. */
    private static String page(String continuation, String... paths) {
        StringJoiner items = new StringJoiner(",", "[", "]");
        for (String path : paths) {
            items.add("""
                    {"format":"maven2","assets":[{"path":"%s","downloadUrl":"%s"}]}"""
                    .formatted(path.replace("\\", "\\\\"), download(path).replace("\\", "\\\\")));
        }
        return "{\"items\":" + items + ",\"continuationToken\":"
                + (continuation == null ? "null" : "\"" + continuation + "\"") + "}";
    }
    @Override
    public Map<ImportContract.Property, String> unsupported() {
        return Map.of(ImportContract.Property.CARRIES_EVERY_DERIVABLE_SIBLING,
                "the Nexus components API carries every asset of a component explicitly, each with its own downloadUrl, so this "
                        + "walk reports what the incumbent listed and composes no path at all. There is nothing to "
                        + "derive and so nothing to derive incompletely: a file Nexus does not list is a file "
                        + "Nexus does not have. The maven leg proves the property where paths really are composed");
    }

}
