package build.jenesis.repository.importer.contract.test;

import module java.base;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.testkit.ImportContract;
import build.jenesis.repository.importer.testkit.ImportFixture;
import build.jenesis.repository.importer.testkit.ScriptedUpstream;

/**
 * The jenesis-to-jenesis connector's corpus: the source instance's {@code /api/assets} enumeration paged by an opaque
 * cursor, each asset carrying its serving path and format, and the bytes streamed from {@code /repository<path>} -
 * the fixed-tenant edition's shape, so the connector's first download probes the repository-qualified shape, is
 * answered {@code 404} and falls back, exactly the dialogue a real fixed-tenant source holds. The credential is a
 * single opaque API key in the {@code Jenesis-Repository-Key} header rather than HTTP basic, which is exactly why
 * the shared credential leg asserts over both header names instead of one.
 */
final class JenesisImportFixture implements ImportFixture {

    private static final String BASE = "http://jenreg.t203";
    private static final String REPOSITORY = "main";
    private static final String PAGE_ONE = BASE + "/api/assets?repo=" + REPOSITORY;
    private static final String PAGE_TWO = PAGE_ONE + "&cursor=page-two";

    @Override
    public String source() {
        return "jenesis";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.importer.jenesis.JenesisSourceProvider";
    }

    @Override
    public ImportRequest request() {
        return new ImportRequest(URI.create(BASE), REPOSITORY);
    }

    @Override
    public Corpus corpus() {
        ScriptedUpstream upstream = ScriptedUpstream.incumbent()
                .answering(PAGE_ONE, 200, page("page-two", "/maven/g/a/1.0/a-1.0.jar", "/maven/g/a/1.0/a-1.0.pom"))
                .answering(PAGE_TWO, 200, page(null, "/maven/g/b/2.0/b-2.0.jar", "/maven/g/b/2.0/b-2.0.pom"));
        for (String served : List.of("/maven/g/a/1.0/a-1.0.jar", "/maven/g/a/1.0/a-1.0.pom",
                "/maven/g/b/2.0/b-2.0.jar", "/maven/g/b/2.0/b-2.0.pom")) {
            upstream.refusing(BASE + "/repository/" + REPOSITORY + served, 404);
            upstream.answering(BASE + "/repository" + served, 200, served);
        }
        return new Corpus(upstream, List.of("g/a/1.0/a-1.0.jar", "g/a/1.0/a-1.0.pom",
                "g/b/2.0/b-2.0.jar", "g/b/2.0/b-2.0.pom"));
    }

    @Override
    public Streamed streamed(GeneratedBody body) {
        String served = "/maven/g/a/1.0/a-1.0.jar";
        return new Streamed(ScriptedUpstream.incumbent()
                .answering(PAGE_ONE, 200, page(null, served))
                .refusing(BASE + "/repository/" + REPOSITORY + served, 404)
                .generating(BASE + "/repository" + served, body), "g/a/1.0/a-1.0.jar");
    }

    @Override
    public ScriptedUpstream failing(int status) {
        return ScriptedUpstream.incumbent().refusing(PAGE_ONE, status);
    }

    @Override
    public Optional<Corpus> hostile() {
        ScriptedUpstream upstream = ScriptedUpstream.incumbent()
                .answering(PAGE_ONE, 200, page(null,
                        "/maven/../" + NexusImportFixture.ESCAPE, "/maven/g/./" + NexusImportFixture.ESCAPE,
                        "/maven/g/a/1.0/a-1.0.jar"))
                .refusing(BASE + "/repository/" + REPOSITORY + "/maven/g/a/1.0/a-1.0.jar", 404)
                .answering(BASE + "/repository/maven/g/a/1.0/a-1.0.jar", 200, "ok");
        return Optional.of(new Corpus(upstream, List.of("g/a/1.0/a-1.0.jar")));
    }

    /** One {@code /api/assets} page: the serving paths and the cursor the next page (and a resume) is reached by. */
    private static String page(String cursor, String... served) {
        StringJoiner assets = new StringJoiner(",", "[", "]");
        for (String path : served) {
            assets.add("{\"path\":\"" + path + "\",\"format\":\"maven\"}");
        }
        return "{\"assets\":" + assets + ",\"cursor\":" + (cursor == null ? "null" : "\"" + cursor + "\"") + "}";
    }
    @Override
    public Map<ImportContract.Property, String> unsupported() {
        return Map.of(ImportContract.Property.CARRIES_EVERY_DERIVABLE_SIBLING,
                "GET /api/assets enumerates the source instance's published assets one by one with their serving paths, so "
                        + "the walk carries across exactly what the source holds - a jenesis-to-jenesis migration "
                        + "composes nothing. The maven leg proves the property where paths really are composed");
    }

}
