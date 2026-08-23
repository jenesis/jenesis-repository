package build.jenesis.repository.importer.contract.test;

import module java.base;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.testkit.ImportContract;
import build.jenesis.repository.importer.testkit.ImportFixture;
import build.jenesis.repository.importer.testkit.ScriptedUpstream;

/**
 * The Artifactory connector's corpus, scripted over the <em>OSS</em> Folder Info crawl: the deep File List answers the
 * {@code 400} a free instance gives ("available only in Artifactory Pro") and the walk falls back to the per-folder
 * API, checkpointing after each completed top-level subtree.
 *
 * <p>That choice of leg is itself the finding this fixture pins. The Pro deep File List is <em>one response</em>, so it
 * has no mid-walk resume point at all and ignores the cursor: an interrupted Pro migration re-walks the entire
 * repository from the start. Only the OSS crawl can satisfy the resume property, so the shared leg drives that one and
 * the divergence is written down here rather than discovered again during a customer migration.
 */
final class ArtifactoryImportFixture implements ImportFixture {

    private static final String BASE = "http://artifactory.t203";
    private static final String REPOSITORY = "libs-release-local";
    private static final String DEEP_LIST = BASE + "/api/storage/" + REPOSITORY + "?list&deep=1&listFolders=0";
    private static final String ROOT_FOLDER = BASE + "/api/storage/" + REPOSITORY;
    private static final String PRO_ONLY = "{\"errors\":[{\"status\":400,"
            + "\"message\":\"The 'list' command is available only in Artifactory Pro\"}]}";

    @Override
    public String source() {
        return "artifactory";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.importer.artifactory.ArtifactorySourceProvider";
    }

    @Override
    public ImportRequest request() {
        // An Artifactory repository holds one package type, so the provider requires the ecosystem format up front.
        return new ImportRequest(URI.create(BASE), REPOSITORY).withFormat("maven");
    }

    @Override
    public Corpus corpus() {
        ScriptedUpstream upstream = ossCrawl()
                .answering(ROOT_FOLDER, 200, folder(child("alpha", true), child("beta", true)))
                .answering(ROOT_FOLDER + "/alpha", 200, folder(child("one.jar", false), child("two.jar", false)))
                .answering(ROOT_FOLDER + "/beta", 200, folder(child("three.jar", false), child("four.jar", false)));
        for (String path : List.of("alpha/one.jar", "alpha/two.jar", "beta/three.jar", "beta/four.jar")) {
            upstream.answering(download(path), 200, path);
        }
        return new Corpus(upstream, List.of("alpha/one.jar", "alpha/two.jar", "beta/three.jar", "beta/four.jar"));
    }

    @Override
    public Streamed streamed(GeneratedBody body) {
        String path = "alpha/artifact.bin";
        return new Streamed(ossCrawl()
                .answering(ROOT_FOLDER, 200, folder(child("alpha", true)))
                .answering(ROOT_FOLDER + "/alpha", 200, folder(child("artifact.bin", false)))
                .generating(download(path), body), path);
    }

    @Override
    public ScriptedUpstream failing(int status) {
        // A refusal that is not the Pro gate surfaces straight off the deep File List, before the OSS fallback.
        return ScriptedUpstream.incumbent().refusing(DEEP_LIST, status);
    }

    @Override
    public Optional<Corpus> hostile() {
        ScriptedUpstream upstream = ossCrawl()
                .answering(ROOT_FOLDER, 200, folder(
                        child("..", false), child(".", false), child("nested/" + NexusImportFixture.ESCAPE, false),
                        child("good.jar", false)))
                .answering(download("good.jar"), 200, "good");
        return Optional.of(new Corpus(upstream, List.of("good.jar")));
    }

    /** The deep File List refused exactly the way a free instance refuses it, so every leg walks the OSS crawl. */
    private static ScriptedUpstream ossCrawl() {
        return ScriptedUpstream.incumbent().answering(DEEP_LIST, 400, PRO_ONLY);
    }

    private static String download(String path) {
        return BASE + "/" + REPOSITORY + "/" + path;
    }

    private static String child(String name, boolean folder) {
        return "{\"uri\":\"/" + name + "\",\"folder\":" + folder + "}";
    }

    private static String folder(String... children) {
        return "{\"children\":[" + String.join(",", children) + "]}";
    }
    @Override
    public Map<ImportContract.Property, String> unsupported() {
        return Map.of(ImportContract.Property.CARRIES_EVERY_DERIVABLE_SIBLING,
                "both Artifactory listing surfaces - the Pro deep File List and the OSS Folder Info recursion - enumerate real "
                        + "files, so every path this walk reports was read from the incumbent rather than built "
                        + "from a coordinate. The maven leg proves the property where paths really are composed");
    }

}
