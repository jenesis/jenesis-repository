package build.jenesis.images;

import module java.base;

import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;

/**
 * Builds the images a build's {@link Configuration} names from the Docker contexts its {@code stage} goal wrote,
 * packages its Helm charts, and publishes both when a registry is named.
 *
 * <p>The contexts are the {@code stage} goal's own output, declared as this module's input by the launcher that wires
 * it - so the images rebuild when and only when what goes into them changed. An image's contents are its module's
 * {@code requires} closure, which the build tool writes into the context's {@code Dockerfile}; nothing here decides
 * what an image holds, only what it is called and where it goes.
 *
 * <p>The tags are written to {@code images.txt} beside the step output, and {@link Push} reads that rather than
 * deriving a second answer - so the set published is the set built, stated once. {@link Charts} does the same for
 * the charts, which ride the same goal and the same publish targets: a deployment needs the chart and the image it
 * names, and publishing them from one command with one credential is what keeps them from disagreeing.
 *
 * <p>The launcher hands the configuration over as the properties of the internal module that loads this one, which
 * is why this provider takes a {@code SequencedMap}; its no-argument constructor, which a service provider must
 * have, configures nothing and so builds nothing.
 */
public class Images implements BuildExecutorModule {

    /** Bumped when the step's behaviour changes: a step is keyed by the digest of its serialized form, not by its
     *  bytecode, so an edited body would otherwise reuse the cached verdict. */
    private static final long VERSION = 2L;

    /** The build's statement of what images exist, written by the build step and read by the push. */
    public static final String MANIFEST = "images.txt";

    /** Bumped when the chart step's behaviour changes, for the same reason as {@link #VERSION}. */
    private static final long CHARTING = 3L;

    /** Bumped when the push step's behaviour changes, for the same reason as {@link #VERSION}. */
    private static final long PUSHING = 6L;

    private final Configuration configuration;

    public Images() {
        this(new LinkedHashMap<>());
    }

    public Images(SequencedMap<String, String> properties) {
        configuration = Configuration.of(properties);
    }

    @Override
    public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) {
        executor.addStep("docker", new Build(VERSION, configuration), inherited.sequencedKeySet());
        // The charts are packaged from the bound deploy trees, which are declared inputs alongside the staged
        // contexts: a chart is derived from files the build can see, exactly as an image is from a staged context.
        executor.addStep("chart", new Charts(CHARTING, configuration), inherited.sequencedKeySet());
        // The push reads what the two builds just wrote, and runs only when a target is named - so building does
        // not publish. See Push for why it is a step at all and what makes it really happen.
        executor.addStep("push", new Push(PUSHING, configuration), "docker", "chart");
    }

    private record Build(long version, Configuration configuration) implements BuildStep {

        /** A staged context directory is named for the module's build identity, each path segment encoded. */
        private static final String PREFIX = "module-";

        /** An image build's effect is in the local Docker daemon, which no step output carries - so another
         *  machine's cached result would name images this one does not have. See {@link Push} for the same
         *  reasoning about a registry, and {@link Charts} for the step where it does not apply. */
        @Override
        public boolean shouldCacheRemotely() {
            return false;
        }

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            SequencedMap<String, Path> contexts = new LinkedHashMap<>();
            for (BuildStepArgument argument : arguments.values()) {
                if (argument.removed() || !Files.isDirectory(argument.folder())) {
                    continue;   // a removed or never-written input has no folder to walk
                }
                try (Stream<Path> staged = Files.list(argument.folder())) {
                    for (Path candidate : staged.sorted().toList()) {
                        if (!Files.isDirectory(candidate) || !Files.isRegularFile(candidate.resolve("Dockerfile"))) {
                            continue;
                        }
                        Optional<String> repository = configuration.image(module(candidate));
                        if (repository.isEmpty()) {
                            System.out.println("[images] " + module(candidate) + " is staged, and is not an image "
                                    + "this build makes");
                            continue;
                        }
                        String tag = repository.get() + ":latest";
                        Path clash = contexts.putIfAbsent(tag, candidate);
                        if (clash != null) {
                            // Refused rather than resolved: a put() would have built both contexts and named only
                            // the last, so whichever lost would be published under nobody's name.
                            throw new IllegalStateException("Two staged contexts claim the image '" + tag + "': "
                                    + clash.getFileName() + " and " + candidate.getFileName()
                                    + ". An image has one module.");
                        }
                    }
                }
            }
            if (contexts.isEmpty()) {
                throw new IllegalStateException("No image this build makes was staged. An image is a module "
                        + "declaring docker=<base> in its META-INF/build.jenesis/packaging.properties and named "
                        + "image.<module path>=<repository> in the images configuration.");
            }
            for (Map.Entry<String, Path> image : contexts.entrySet()) {
                build(image.getKey(), image.getValue());
            }
            Files.write(context.next().resolve(MANIFEST), contexts.sequencedKeySet());
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }

        /** The module path a staged context was written for: the build names a module's step folder by its path,
         *  each segment encoded on its own and the segments joined by {@code +}. */
        private static String module(Path context) {
            String name = context.getFileName().toString();
            return Arrays.stream((name.startsWith(PREFIX) ? name.substring(PREFIX.length()) : name)
                            .split("\\+", -1))
                    .map(segment -> URLDecoder.decode(segment, StandardCharsets.UTF_8))
                    .collect(Collectors.joining("/"));
        }

        /**
         * Run the container tool, streaming its output into this build's own: an image build is minutes of layer
         * work, and a step that goes silent for minutes and then prints everything at once is one people kill.
         */
        private static void build(String tag, Path context) throws IOException {
            List<String> command = List.of("docker", "build", "-t", tag, context.toString());
            Process process;
            try {
                process = new ProcessBuilder(command).inheritIO().start();
            } catch (IOException unavailable) {
                throw new IOException("Could not run '" + String.join(" ", command) + "'. Building images needs a "
                        + "container tool on the PATH; this goal is opt-in precisely so that an ordinary build does "
                        + "not.", unavailable);
            }
            int status;
            try {
                status = process.waitFor();
            } catch (InterruptedException interrupted) {
                process.destroy();
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while building " + tag, interrupted);
            }
            if (status != 0) {
                throw new IllegalStateException("docker build exited " + status + " for " + tag + " from " + context);
            }
        }
    }
}
