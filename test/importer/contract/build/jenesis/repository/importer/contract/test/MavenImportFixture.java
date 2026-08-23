package build.jenesis.repository.importer.contract.test;

import module java.base;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.testkit.ImportFixture;
import build.jenesis.repository.importer.testkit.MavenIndexChunk;
import build.jenesis.repository.importer.testkit.ScriptedUpstream;

/**
 * The vendor-neutral Maven connector's corpus, scripted over the <em>directory-listing</em> walk: an autoindex page per
 * directory, depth-first in sorted order, checkpointing {@code tree:<directory>} after each completed subtree.
 *
 * <p>The listing-less index fallback is deliberately not the leg driven here: it needs a real gzipped Nexus repository
 * index to be honest, {@code MavenSourceTest} already drives it record by record, and the two legs share the same
 * checkpoint and download plumbing. What the shared leg adds is the cross-connector comparison, and the tree walk is
 * the one every peer connector has an analogue of.
 */
final class MavenImportFixture implements ImportFixture {

    private static final String BASE = "http://maven.t203";
    private static final String REPOSITORY = "repo";
    private static final String ROOT = BASE + "/" + REPOSITORY + "/";
    private static final String INDEX_PROBE = ROOT + ".index/nexus-maven-repository-index.properties";

    @Override
    public String source() {
        return "maven";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.importer.maven.MavenSourceProvider";
    }

    @Override
    public ImportRequest request() {
        return new ImportRequest(URI.create(BASE), REPOSITORY);
    }

    @Override
    public Corpus corpus() {
        ScriptedUpstream upstream = ScriptedUpstream.incumbent()
                .answering(ROOT, 200, listing("alpha/", "beta/"))
                .answering(ROOT + "alpha/", 200, listing("one.jar", "two.jar"))
                .answering(ROOT + "beta/", 200, listing("three.jar", "four.jar"));
        for (String path : List.of("alpha/one.jar", "alpha/two.jar", "beta/three.jar", "beta/four.jar")) {
            upstream.answering(ROOT + path, 200, path);
        }
        // Sorted per directory, depth-first: alpha's subtree completes (and checkpoints) before beta is entered.
        return new Corpus(upstream, List.of("alpha/one.jar", "alpha/two.jar", "beta/four.jar", "beta/three.jar"));
    }

    @Override
    public Optional<Corpus> derived() {
        // The listing-less leg, which is the only Maven walk that COMPOSES paths rather than reading them: the root
        // refuses, so the walk falls back to the repository index, and every version the index does not already carry
        // is derived from maven-metadata.xml. 1.0.0 rides an index record; 2.0.0 exists only in the metadata, so it
        // is reached through the derivation, which is where a sibling is either composed or silently lost.
        String artifact = "org/example/widget";
        String base = artifact + "/2.0.0/widget-2.0.0";
        ScriptedUpstream upstream = ScriptedUpstream.incumbent()
                .refusing(ROOT, 403)
                .answering(ROOT + ".index/nexus-maven-repository-index.properties", 200, "nexus.index.id=repo\n")
                .answering(ROOT + ".index/nexus-maven-repository-index.gz", 200,
                        MavenIndexChunk.of(List.of(MavenIndexChunk.record("org.example", "widget", "1.0.0", "jar"))))
                .answering(ROOT + artifact + "/maven-metadata.xml", 200,
                        "<metadata><versioning><versions><version>1.0.0</version><version>2.0.0</version>"
                                + "</versions></versioning></metadata>")
                .answering(ROOT + base + ".pom", 200, "<project><packaging>jar</packaging></project>")
                // The sibling the derivation must not drop. It is served here, so a walk that does not carry it is
                // choosing not to ask - the file is at a path this leg already knows how to build.
                .answering(ROOT + base + ".module", 200, "{\"formatVersion\":\"1.1\"}")
                .answering(ROOT + base + ".jar", 200, "jar");
        return Optional.of(new Corpus(upstream, List.of(
                artifact + "/1.0.0/widget-1.0.0.jar", artifact + "/1.0.0/widget-1.0.0.pom",
                base + ".pom", base + ".module", base + ".jar")));
    }

    @Override
    public Streamed streamed(GeneratedBody body) {
        String path = "artifact.bin";
        return new Streamed(ScriptedUpstream.incumbent()
                .answering(ROOT, 200, listing(path))
                .generating(ROOT + path, body), path);
    }

    @Override
    public ScriptedUpstream failing(int status) {
        // Both enumeration surfaces refuse: the root autoindex and the repository index this connector falls back to.
        // Until that pair collapsed into "the server exposes no directory listing" whatever the reason, so an
        // expired credential read as a misconfigured autoindex.
        return ScriptedUpstream.incumbent().refusing(ROOT, status).refusing(INDEX_PROBE, status);
    }

    @Override
    public Optional<Corpus> hostile() {
        // Stated rather than inherited: this connector's listing format cannot express a laced child at all.
        // HtmlListing rejects a decoded separator, '.' or '..' when it turns an href into an entry, so MavenSource is
        // never handed one and there is no hostile page to script. The corpus leg of
        // REPORTS_ONLY_IMPORTABLE_PATHS still runs (every reported path must pass both screens), and the belt behind
        // the parser - ImportSource.safePath in walkTree and emit - is proven by MavenSourceTest's index-walk legs.
        return Optional.empty();
    }

    /** An autoindex page in the shape every generator emits: a parent link to ignore and one href per child. */
    private static String listing(String... names) {
        StringBuilder page = new StringBuilder("<html><body><a href=\"../\">Parent Directory</a>");
        for (String name : names) {
            page.append("<a href=\"").append(name).append("\">").append(name).append("</a>");
        }
        return page.append("</body></html>").toString();
    }
}
