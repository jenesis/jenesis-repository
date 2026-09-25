package build.jenesis.images;

import module java.base;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;

/**
 * Packages each Helm chart a build's {@link Configuration} names, from a chart source in one of the bound deploy
 * trees.
 *
 * <h2>One chart source, as many published charts as there are images to run</h2>
 *
 * <p>A chart deploys an image off one value, so several published charts may share one source: what differs
 * between them is which image they run, and each image is published to a repository of its own. So packaging a
 * chart sets its {@code name} and the default {@code image.repository} in its values, and nothing else; the tag is
 * the chart's {@code appVersion}. A repository that builds on another one's chart binds that repository's deploy
 * tree and names the chart source in it, rather than keeping a copy that would drift.
 *
 * <h2>What a values overlay is, and is not</h2>
 *
 * <p>A chart may name a values overlay ({@code chart.<name>.values}) - the file a person passes to
 * {@code helm install -f} - and this step renders the chart through it to prove it templates before the chart is
 * packaged. It is <em>not</em> merged into the packaged defaults: the packaged default is the repository packaging
 * writes, one value in one place, and a second mechanism setting the same thing is how the two drift.
 *
 * <h2>Verified rather than assumed</h2>
 *
 * <p>Rewriting a key in a YAML file by line is only safe if something checks the result, so every packaged chart is
 * linted and rendered, and the render is asserted to name the chart's image. A substitution that silently missed
 * would otherwise publish a chart deploying another chart's image.
 */
record Charts(long version, Configuration configuration) implements BuildStep {

    // The two steps beside this one decline the shared cache (see Push); this one deliberately does not. A chart
    // is a .tgz packaged out of files the build declares as inputs, so it is fully carried by this step's output
    // and another machine's copy of it is as good as this one's.

    /** The build's statement of what charts exist, written here and read by {@link Push}. */
    static final String MANIFEST = "charts.txt";

    /** The file that marks a chart directory. */
    private static final String CHART = "Chart.yaml";

    /** The chart's own values, whose default image packaging points at the chart's repository. */
    private static final String VALUES_FILE = "values.yaml";

    /** Where a tree bound as a source lands inside its argument's folder. */
    private static final String SOURCES = "sources";

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        Path out = Files.createDirectories(context.next().resolve("charts"));
        List<String> packaged = new ArrayList<>();
        if (!configuration.charts().isEmpty() && !Cli.available("helm")) {
            throw new IllegalStateException("helm is not on the PATH, so no chart can be packaged. This goal is "
                    + "opt-in precisely so that an ordinary build needs neither a container tool nor helm.");
        }
        for (Configuration.Chart chart : configuration.charts().values()) {
            Path source = resolve(arguments, chart.source());
            if (!Files.isRegularFile(source.resolve(CHART))) {
                throw new IllegalStateException("The chart " + chart.name() + " names " + chart.source()
                        + ", which holds no " + CHART + " (" + source + ")");
            }
            Path overlay = chart.values() == null ? null : resolve(arguments, chart.values());
            if (overlay != null && !Files.isRegularFile(overlay)) {
                throw new IllegalStateException("The chart " + chart.name() + " names the values file "
                        + chart.values() + ", which does not exist (" + overlay + ")");
            }
            packaged.add(packageChart(source, overlay, chart, out, context.next()));
        }
        Files.write(context.next().resolve(MANIFEST), packaged);
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    /**
     * A path named as {@code <input>/<path>}: the bound input whose name is the first segment, and the path within
     * what it binds. The launcher names the inputs, so the configuration refers to a deploy tree by the name it was
     * bound under rather than by where it lies in the project.
     */
    private static Path resolve(SequencedMap<String, BuildStepArgument> arguments, String named) {
        int slash = named.indexOf('/');
        String input = slash < 0 ? named : named.substring(0, slash);
        String path = slash < 0 ? "" : named.substring(slash + 1);
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            String key = entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1);
            BuildStepArgument argument = entry.getValue();
            if (!key.equals(input) || argument.removed() || argument.folder() == null) {
                continue;
            }
            Path root = Files.isDirectory(argument.folder().resolve(SOURCES))
                    ? argument.folder().resolve(SOURCES) : argument.folder();
            return path.isEmpty() ? root : root.resolve(path);
        }
        throw new IllegalStateException("No bound input is named '" + input + "' (for " + named + "); the inputs are "
                + arguments.sequencedKeySet());
    }

    private String packageChart(Path source, Path overlay, Configuration.Chart chart, Path out, Path work)
            throws IOException {
        Path staged = work.resolve("staged-" + chart.name());
        copy(source, staged);
        rewrite(staged.resolve(CHART), chart.name());
        repository(staged.resolve(VALUES_FILE), chart.image());
        Cli.run(List.of("helm", "lint", staged.toString()));
        rendered(staged, overlay, chart.name(), chart.image());
        Cli.run(List.of("helm", "package", staged.toString(), "--destination", out.toString()));
        System.out.println("[images] packaged " + chart.name());
        return chart.name();
    }

    /**
     * Render the chart and hold it to what packaging claimed - an image reference naming the chart's repository at
     * {@code latest}. Rendered twice where the chart has an overlay: once on the packaged defaults, which is what a
     * marketplace installs, and once through the overlay, which is what a person installs.
     */
    private static void rendered(Path staged, Path overlay, String name, String repository) throws IOException {
        String defaults = Cli.capture(List.of("helm", "template", "release", staged.toString()));
        if (!defaults.contains("/" + repository + ":latest\"") && !defaults.contains("\"" + repository + ":latest\"")) {
            throw new IllegalStateException("The packaged " + name + " renders no image " + repository
                    + ":latest, so its repository did not reach the deployment. Rendered:\n" + defaults);
        }
        if (overlay != null) {
            Cli.capture(List.of("helm", "template", "release", staged.toString(), "-f", overlay.toString()));
        }
    }

    /** Set the chart's name, and prove it was there to set. */
    private static void rewrite(Path chart, String name) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(chart));
        set(lines, chart, "name:", "name: " + name);
        Files.write(chart, lines);
    }

    /** Point the chart's default image at its published repository, and prove it was there to point. */
    private static void repository(Path values, String repository) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(values));
        set(lines, values, "  repository: ", "  repository: " + repository);
        Files.write(values, lines);
    }

    static void set(List<String> lines, Path chart, String key, String replacement) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith(key)) {
                lines.set(index, replacement);
                return;
            }
        }
        throw new IllegalStateException("No top-level '" + key + "' in " + chart + ", so the chart cannot be "
                + "stamped. A rewrite that silently did nothing would publish two charts as one.");
    }

    private static void copy(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            try (Stream<Path> walk = Files.walk(target)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
