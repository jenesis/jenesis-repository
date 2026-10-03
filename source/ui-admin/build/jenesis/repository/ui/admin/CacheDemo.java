package build.jenesis.repository.ui.admin;

import module java.base;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.demo.Demo;
import build.jenesis.repository.demo.DemoContributor;
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.store.CacheService;
import build.jenesis.repository.ui.store.SettingsAdmin;

/**
 * The build cache's demo content: a project for the Jenesis build tool and one for Gradle, where those cache protocols
 * are installed, each holding the outputs a few builds stored and counted, so the projects screen and a project's
 * screen show what a cache that has served builds for a while shows.
 *
 * <p>A project is created through {@link CacheService#createProject}, the creation the wizard, the API and the CLI
 * make, recorded on the audit trail as the operator who confirmed the demo. Its entries are stored into the tenant's
 * cache as the cache stores a build's upload once its protocol has read the address, and the project is then counted
 * by the pass its screen's button starts, on the run's thread.
 */
public final class CacheDemo implements DemoContributor {

    /** One project the demo creates: its name, the build tool, what it is for, and the steps a build stored. */
    private record Project(String name, String type, String description, List<Output> outputs) {
    }

    /** One stored output: the step a build ran and how many bytes it left. */
    private record Output(String step, int size) {
    }

    private static final List<Project> PROJECTS = List.of(
            new Project("jenesis_build", "jenesis", "Demo: the Jenesis build tool's cache - compiled classes, test "
                    + "results and packaged modules, keyed by each step's inputs.", List.of(
                    new Output("module-source+server/produce/compile", 412_000),
                    new Output("module-source+server/produce/javadoc", 188_000),
                    new Output("module-source+server/produce/assemble", 455_000),
                    new Output("module-source+store/produce/compile", 236_000),
                    new Output("module-source+store/produce/assemble", 251_000),
                    new Output("module-source+format+maven/produce/compile", 174_000),
                    new Output("module-source+format+npm/produce/compile", 97_000),
                    new Output("module-test+server/produce/compile", 128_000),
                    new Output("module-test+server/observed/test/executed", 46_000),
                    new Output("module-test+store/observed/test/executed", 31_000),
                    new Output("module-test+format+maven/observed/test/executed", 22_000),
                    new Output("resolve/dependencies", 9_000))),
            new Project("android_app", "gradle", "Demo: a Gradle build's cache - task outputs a CI build stores and "
                    + "every later build reuses.", List.of(
                    new Output(":app:compileReleaseKotlin", 362_000),
                    new Output(":app:compileReleaseJavaWithJavac", 141_000),
                    new Output(":app:mergeReleaseResources", 298_000),
                    new Output(":app:lintAnalyzeRelease", 77_000),
                    new Output(":core:compileKotlin", 158_000),
                    new Output(":core:test", 19_000))));

    private final CacheStorage root;
    private final ArtifactStore repositoryStore;
    private final SettingsEditor editor;
    private final AuditTrail audit;

    /**
     * @param root            the deployment's cache, whose tenant spaces hold the projects.
     * @param repositoryStore the deployment's store, where a project's settings live.
     * @param editor          the settings editor every surface changes a setting through.
     * @param audit           where a created project is recorded.
     */
    public CacheDemo(CacheStorage root, ArtifactStore repositoryStore, SettingsEditor editor, AuditTrail audit) {
        this.root = root;
        this.repositoryStore = repositoryStore;
        this.editor = editor;
        this.audit = audit;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public Plan plan() {
        List<Project> installed = installed();
        if (installed.isEmpty()) {
            return Plan.NONE;
        }
        return new Plan(List.of(), List.of("the build-cache projects " + String.join(", ", installed.stream()
                .map(project -> project.name() + " (" + project.type() + ")").toList())
                + ", each holding sample build outputs"), new LinkedHashMap<>(), List.of(), List.of());
    }

    @Override
    public void load(Demo demo) throws IOException {
        SettingsAdmin settings = new SettingsAdmin(repositoryStore, editor, List::of, audit, demo::tenant,
                demo::actor, _ -> null);
        CacheService projects = new CacheService(root, audit, demo::tenant, demo::actor, settings,
                CacheService.Passes.CALLING_THREAD);
        CacheStorage cache = root.scope(demo.tenant());
        for (Project project : installed()) {
            String what = "Create the build-cache project " + project.name() + " (" + project.type() + ")";
            try {
                projects.createProject(project.name(), project.type(), project.description());
            } catch (IOException | RuntimeException refused) {
                demo.made(what, Demo.Outcome.FAILED, "Not created: " + refused.getMessage());
                continue;
            }
            demo.made(what, Demo.Outcome.DONE, project.description());
            long bytes = 0;
            try {
                for (Output output : project.outputs()) {
                    cache.store(new CacheStorage.Entry(project.name(), digest(project.type() + ":" + output.step()),
                            digest(project.name() + ":" + output.step() + ":inputs")), body(output));
                    bytes += output.size();
                }
            } catch (IOException | RuntimeException failed) {
                demo.made("Store build outputs in " + project.name(), Demo.Outcome.FAILED, "Not stored: "
                        + failed.getMessage());
                continue;
            }
            demo.made("Store build outputs in " + project.name(), Demo.Outcome.DONE, project.outputs().size()
                    + " entries, " + bytes + " bytes, as builds store what a step produced.");
            projects.recount(project.name());
        }
    }

    /** The projects whose build tool a cache protocol here speaks. */
    private static List<Project> installed() {
        return PROJECTS.stream().filter(project -> CacheService.TYPES.contains(project.type())).toList();
    }

    /** A step's or its inputs' address, as hex as a build tool sends it. */
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /** An output's bytes, generated from its step so the same run stores the same bytes. */
    private static InputStream body(Output output) {
        byte[] bytes = new byte[output.size()];
        new Random(output.step().hashCode()).nextBytes(bytes);
        return new ByteArrayInputStream(bytes);
    }
}
