package build;

import module java.base;

import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Environment;
import build.jenesis.Make;
import build.jenesis.Project;
import build.jenesis.SequencedProperties;
import build.jenesis.project.InternalModule;
import build.jenesis.project.MultiProjectAssembler;
import build.jenesis.project.ProjectModuleDescriptor;
import build.jenesis.step.Bind;

/**
 * The project launcher: the stock build, plus the opt-in {@code images} goal that builds this repository's image
 * and chart and publishes them.
 *
 * <p><strong>The contract this file keeps.</strong> It builds the project exactly as
 * {@code java build/jenesis/Make.java} does - the stock layout and the stock assembler - and adds one module,
 * {@code images}, which is never a default target and reads only what {@code stage} wrote and the {@code deploy}
 * tree. Nothing about what this project <em>builds</em> may ever move in here.
 *
 * <p><strong>Why a launcher at all.</strong> A plugin of the stock build runs in one of its slots, and the last of
 * them, {@code postprocess}, runs inside {@code build} - before {@code stage} has written the Docker context an image
 * is built from. The images module hangs off {@code stage} instead, which only a layout wired in code can do. It lives
 * in {@code plugins/images}, compiled from source by the tool's own {@code InternalModule}, and it is configured by
 * {@code deploy/images.properties}: which staged module becomes which image, and which chart is packaged for it.
 *
 * <pre>
 * java build/Build.java images                                    # build the image and package the chart
 * java -Djenesis.images.push=hub build/Build.java images          # ... and publish both to Docker Hub
 * </pre>
 *
 * <p>Another repository that builds on this one uses the same plugin through this tree - its launcher points an
 * {@code InternalModule} at {@code plugins/images} here and hands it its own configuration.
 */
public class Build {

    /** Opt-in: {@code java build/Build.java images}. Never a default target, because an ordinary build must not
     *  need a container tool on the PATH. */
    private static final String IMAGES = "images";

    /** The deployment tree: the Helm chart, and the configuration of what this repository builds and publishes. */
    private static final Path DEPLOY = Path.of("deploy");

    public static void main(String... selectors) {
        try {
            Path root = Path.of(System.getProperty("jenesis.make.root", "")).toAbsolutePath().normalize();
            Make.Settings settings = Make.settings(root);
            Environment environment = new Environment(settings.keys());
            Project.ofEnvironment(environment, root)
                    .profiles(settings.profiles().toArray(Path[]::new))
                    .layout(Build::imaged)
                    .build(selectors);
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            t.printStackTrace();
            System.err.println();
            System.err.println("The build failed with the error above. Pass `help` as the only argument to look up"
                    + " how to invoke Jenesis, or `skill` for an agent-oriented briefing.");
            System.exit(1);
        }
    }

    /** The stock layout, unchanged, plus {@code images}. */
    private static Function<String, String> imaged(BuildExecutor executor,
                                                   Project project,
                                                   MultiProjectAssembler<? super ProjectModuleDescriptor> assembler)
            throws IOException {
        Function<String, String> selectors = Project.Layout.MODULAR_TO_MAVEN.apply(executor, project, assembler);
        executor.addModule(IMAGES, (images, _) -> {
            // The deploy tree is a declared input for the same reason the staged context is: a chart is derived from
            // files, so re-packaging is the executor's decision. The configuration names paths in it as deploy/...
            images.addSource("deploy", Bind.asSources(), project.root().resolve(DEPLOY));
            SequencedSet<String> inputs = new LinkedHashSet<>(List.of("deploy",
                    BuildExecutorModule.PREVIOUS + Project.STAGE + "/docker"));
            images.addModule("docker", new InternalModule("module", "tool",
                            project.root().resolve(Path.of("plugins", "images")))
                            .properties(configuration(project.root().resolve(DEPLOY).resolve("images.properties"))),
                    inputs);
        }, Project.STAGE);
        return selectors;
    }

    /** The images configuration, in the order the file states it. */
    static SequencedMap<String, String> configuration(Path file) throws IOException {
        SequencedMap<String, String> values = new LinkedHashMap<>();
        SequencedProperties.ofFiles(file).forEachProperty(values::put);
        return values;
    }
}
