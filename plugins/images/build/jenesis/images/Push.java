package build.jenesis.images;

import module java.base;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;

/**
 * Tag and push the images a build made and the charts it packaged, so a marketplace offer, a customer's deployment
 * template or a plain {@code docker pull} can reference them by URI. What goes is what the build's
 * {@link Configuration} names: every image in the build's manifest except one it marks {@code publish.<repository>=false},
 * and every chart it packaged. Which images a repository publishes is therefore that repository's decision, made in
 * its configuration, and a publish run from it can push nothing else.
 *
 * <h2>An outward action, expressed so that it really happens</h2>
 *
 * <p>{@link #shouldRun} is what carries that: it ignores whether anything changed, because a push is not derived
 * from its inputs - it is derived from the state of somebody else's registry, which this build cannot see. So
 * naming a target runs the push, every time, however cold or warm the tree is. It runs <em>only</em> when a target
 * is named ({@code -Djenesis.images.push=hub|aws|azure|gcp|scaleway|all}, or several comma-separated, or
 * {@code PUSH_TARGET}), so building the images does not publish them.
 *
 * <p>{@link #shouldCacheRemotely} declines the <em>shared</em> build cache while leaving the local one: a push's
 * effect is in a registry, and a shared cache answering this step would report a push that never happened.
 *
 * <h2>One naming rule</h2>
 *
 * <p>Every image is published to a repository named for it, tagged with the version and with {@code latest}; a chart
 * goes to the same registry as an OCI artifact under its own name, versioned with the release and pointing at the
 * image published with it ({@link #released}). Two images never share a repository with the image as the tag, since
 * a registry shows every tag of a repository to anyone who can see it.
 */
record Push(long version, Configuration configuration) implements BuildStep {

    /** A push's whole value is its effect on a registry, and that effect does not travel in a step output - so a
     *  shared cache answering this step would report a push that never happened. The local cache is left alone:
     *  it only ever replays a run this machine really made. */
    @Override
    public boolean shouldCacheRemotely() {
        return false;
    }


    /** Which registries to publish to. */
    private static final String TARGET = "jenesis.images.push";

    /** The marketplace product version, independent of the Jenesis tool version. */
    private static final String VERSION = "jenesis.images.version";

    private static final String DEFAULT_VERSION = "1.0.0";

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        // Never "did anything change" - a push is derived from a registry this build cannot see. Named a target,
        // do it; that is the whole condition.
        return !targets().isEmpty();
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        List<String> targets = targets();
        if (targets.isEmpty()) {
            // An initial run executes every step regardless of shouldRun, since there is no previous output to
            // reuse. Naming no target means naming no work, not an error.
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
        List<String> built = manifest(arguments, Images.MANIFEST);
        if (built.isEmpty()) {
            throw new IllegalStateException("The image manifest is empty, so there is nothing to publish. Run the "
                    + "images goal first: it writes " + Images.MANIFEST + " naming what it built.");
        }
        List<String> images = new ArrayList<>();
        for (String image : built) {
            if (configuration.published(repository(image))) {
                images.add(image);
            } else {
                System.out.println("[images] not pushing " + image + ": it is built, and never published");
            }
        }
        if (!Cli.available("docker")) {
            throw new IllegalStateException("docker is not on the PATH, so no image can be tagged or pushed.");
        }
        List<Path> charts = packaged(arguments);
        if (!charts.isEmpty() && !Cli.available("helm")) {
            throw new IllegalStateException("helm is not on the PATH, but " + charts.size() + " chart(s) were "
                    + "packaged. Publishing half of a release - the image without the chart that names it - is "
                    + "worse than publishing none, so this stops rather than skipping them.");
        }
        String release = setting(VERSION, "PUSH_VERSION", DEFAULT_VERSION);
        for (String target : targets) {
            publish(target, images, charts, release, context.next());
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    /** What the build said it produced, read from its own manifest rather than derived a second time. */
    private static List<String> manifest(SequencedMap<String, BuildStepArgument> arguments, String name)
            throws IOException {
        for (BuildStepArgument argument : arguments.values()) {
            if (argument.folder() == null) {
                continue;
            }
            Path manifest = argument.folder().resolve(name);
            if (Files.isRegularFile(manifest)) {
                return Files.readAllLines(manifest).stream().map(String::strip).filter(line -> !line.isEmpty())
                        .toList();
            }
        }
        return List.of();
    }

    /** The packaged chart archives, found beside the manifest that names the charts. */
    private static List<Path> packaged(SequencedMap<String, BuildStepArgument> arguments) throws IOException {
        for (BuildStepArgument argument : arguments.values()) {
            if (argument.folder() == null || !Files.isRegularFile(argument.folder().resolve(Charts.MANIFEST))) {
                continue;
            }
            Path charts = argument.folder().resolve("charts");
            if (!Files.isDirectory(charts)) {
                return List.of();
            }
            try (Stream<Path> archives = Files.list(charts)) {
                return archives.filter(path -> path.getFileName().toString().endsWith(".tgz")).sorted().toList();
            }
        }
        return List.of();
    }

    private void publish(String target, List<String> images, List<Path> charts, String release, Path work)
            throws IOException {
        String registry = switch (target) {
            case "hub" -> hub();
            case "aws" -> aws();
            case "azure" -> azure();
            case "gcp" -> gcp();
            case "scaleway" -> scaleway();
            default -> throw new IllegalStateException("Unknown publish target '" + target + "'. Set " + TARGET
                    + " to one or more of " + TARGETS + ", comma-separated, or to all.");
        };
        System.out.println("[images] publishing to " + target + " at " + registry + " (version " + release + ")");
        for (String image : images) {
            require(image);
            // Each image has a repository of its own, tagged with the version and with latest.
            if (target.equals("aws")) {
                ensureEcrRepository(registry, repository(image));
            }
            String remote = registry + "/" + repository(image);
            tagAndPush(image, remote + ":" + release);
            if (!"0".equals(environment("PUSH_LATEST", "1"))) {
                tagAndPush(image, remote + ":latest");
            }
        }
        if (target.equals("scaleway") && !charts.isEmpty()) {
            // Scaleway's template runs the image as a Serverless Container through Terraform and names no chart, and
            // Scaleway's registry documentation says nothing about accepting a Helm chart as an OCI artifact - so a
            // chart is not pushed where nothing reads it and where the push is not known to be accepted.
            System.out.println("[images]   no charts to scaleway: its template deploys the image alone");
            return;
        }
        for (Path chart : charts) {
            Path released = released(chart, release, images(target, registry),
                    work.resolve("charts-" + target).resolve(chart.getFileName().toString()));
            // helm derives the OCI repository from the chart's own name and the tag from its version, so the target
            // is the namespace alone - which is also why what tells two charts apart is their name, not their tag.
            Cli.run(List.of("helm", "push", released.toString(), "oci://" + oci(target, registry)));
            System.out.println("[images]   pushed " + released.getFileName() + " to oci://" + oci(target, registry));
        }
    }

    /**
     * The chart as it is published with a release: the packaged archive with its {@code version} and
     * {@code appVersion} set to the release, and its default {@code image.registry} set to where this target's images
     * are - so {@code helm install <name> oci://<registry>/<name> --version <release>} deploys the image this publish
     * pushed with that release, and nothing has to be set to make it find the image.
     *
     * <p>Stamped here rather than when the chart is packaged, because the release and the registry are named per
     * publish and the chart step is cached on its inputs. A published chart version is never reused for other
     * contents, which a fixed version in {@code Chart.yaml} would do on every publish. The stamped chart is rendered
     * before it goes, and the render must name the image at the registry and release it was stamped with.
     */
    private Path released(Path archive, String release, String imageRegistry, Path work) throws IOException {
        Path unpacked = Files.createDirectories(work.resolve("unpacked"));
        Cli.run(List.of("tar", "-xzf", archive.toString(), "-C", unpacked.toString()));
        Path chart;
        try (Stream<Path> roots = Files.list(unpacked)) {
            chart = roots.filter(path -> Files.isRegularFile(path.resolve("Chart.yaml"))).findFirst()
                    .orElseThrow(() -> new IllegalStateException(archive + " holds no chart"));
        }
        List<String> metadata = new ArrayList<>(Files.readAllLines(chart.resolve("Chart.yaml")));
        Charts.set(metadata, chart.resolve("Chart.yaml"), "version:", "version: " + release);
        Charts.set(metadata, chart.resolve("Chart.yaml"), "appVersion:", "appVersion: \"" + release + "\"");
        Files.write(chart.resolve("Chart.yaml"), metadata);
        List<String> values = new ArrayList<>(Files.readAllLines(chart.resolve("values.yaml")));
        Charts.set(values, chart.resolve("values.yaml"), "  registry: ", "  registry: \"" + imageRegistry + "\"");
        Files.write(chart.resolve("values.yaml"), values);
        Path out = Files.createDirectories(work.resolve("released"));
        Cli.run(List.of("helm", "package", chart.toString(), "--destination", out.toString()));
        Path released = out.resolve(chart.getFileName() + "-" + release + ".tgz");
        String name = chart.getFileName().toString();
        String image = configuration.charts().containsKey(name) ? configuration.charts().get(name).image() : null;
        String rendered = Cli.capture(List.of("helm", "template", "release", released.toString()));
        if (image != null && !rendered.contains("\"" + imageRegistry + "/" + image + ":" + release + "\"")) {
            throw new IllegalStateException("The released " + name + " renders no image " + imageRegistry + "/" + image
                    + ":" + release + ", so it would not deploy what this publish pushed. Rendered:\n" + rendered);
        }
        return released;
    }

    /**
     * Where a target's images are, as a chart names them in {@code image.registry}. A {@code docker push} to Docker
     * Hub takes the bare {@code <namespace>}, but a Kubernetes image reference without a host means
     * {@code docker.io/library}, so the chart gets the host as well.
     */
    private static String images(String target, String registry) {
        return "hub".equals(target) ? "docker.io/" + registry : registry;
    }

    /**
     * Where a chart goes on this target.
     *
     * <p>Only Docker Hub differs, and it differs because the image side hides it: {@code docker push} accepts a bare
     * {@code <namespace>/<repository>} and fills in the default registry itself, while an {@code oci://} URL has
     * nowhere to take a default from and must name the host.
     */
    private static String oci(String target, String registry) {
        return "hub".equals(target) ? "registry-1.docker.io/" + registry : registry;
    }

    private static void require(String image) throws IOException {
        try {
            Cli.capture(List.of("docker", "image", "inspect", "--format", "{{.Id}}", image));
        } catch (IllegalStateException | IOException absent) {
            throw new IllegalStateException("The local image '" + image + "' does not exist. Run the images goal "
                    + "first - it builds what its manifest then names.", absent);
        }
    }

    private static void tagAndPush(String image, String remote) throws IOException {
        Cli.run(List.of("docker", "tag", image, remote));
        Cli.run(List.of("docker", "push", remote));
        System.out.println("[images]   pushed " + remote);
    }

    // ---- the registries ----

    /**
     * Docker Hub. The namespace is the account or organisation; the repositories under it keep the images' own
     * names, so a pull reads {@code <namespace>/<repository>:latest}.
     */
    private static String hub() throws IOException {
        String namespace = required("DOCKER_HUB_NAMESPACE", "set DOCKER_HUB_NAMESPACE=<org-or-user>");
        if (!skipLogin()) {
            String user = required("DOCKER_HUB_USER",
                    "set DOCKER_HUB_USER + DOCKER_HUB_TOKEN (an access token, never an account password), "
                            + "or SKIP_LOGIN=1");
            String token = required("DOCKER_HUB_TOKEN",
                    "set DOCKER_HUB_USER + DOCKER_HUB_TOKEN (an access token, never an account password), "
                            + "or SKIP_LOGIN=1");
            Cli.feed(List.of("docker", "login", "--username", user, "--password-stdin"), token);
        }
        return namespace;
    }

    /** AWS ECR. The repository is created on first publish, because ECR has no create-on-push. */
    private static String aws() throws IOException {
        String registry = environment("AWS_ECR_REGISTRY", "");
        String region = environment("AWS_REGION", "");
        if (registry.isEmpty()) {
            String account = required("AWS_ACCOUNT_ID", "set AWS_ECR_REGISTRY, or AWS_ACCOUNT_ID + AWS_REGION");
            if (region.isEmpty()) {
                throw new IllegalStateException("set AWS_ECR_REGISTRY, or AWS_ACCOUNT_ID + AWS_REGION");
            }
            registry = account + ".dkr.ecr." + region + ".amazonaws.com";
        }
        if (region.isEmpty()) {
            Matcher matcher = Pattern.compile("\\.ecr\\.([^.]+)\\.amazonaws\\.com").matcher(registry);
            if (!matcher.find()) {
                throw new IllegalStateException("could not determine the ECR region from '" + registry
                        + "' (set AWS_REGION)");
            }
            region = matcher.group(1);
        }
        if (!Cli.available("aws")) {
            throw new IllegalStateException("the aws CLI is not on the PATH");
        }
        if (!skipLogin()) {
            Cli.feed(List.of("docker", "login", "--username", "AWS", "--password-stdin", registry),
                    Cli.capture(List.of("aws", "ecr", "get-login-password", "--region", region)));
        }
        return registry;
    }

    /** ECR refuses a push to a repository that does not exist, so each image's is created once, idempotently. */
    private static void ensureEcrRepository(String registry, String repository) throws IOException {
        Matcher matcher = Pattern.compile("\\.ecr\\.([^.]+)\\.amazonaws\\.com").matcher(registry);
        String region = matcher.find() ? matcher.group(1) : environment("AWS_REGION", "");
        List<String> describe = List.of("aws", "ecr", "describe-repositories", "--region", region,
                "--repository-names", repository);
        try {
            Cli.capture(describe);
        } catch (IllegalStateException | IOException absent) {
            Cli.run(List.of("aws", "ecr", "create-repository", "--region", region,
                    "--repository-name", repository));
        }
    }

    /** The repository a built image is published to: its local name without the tag. */
    private static String repository(String image) {
        int colon = image.indexOf(':');
        return colon < 0 ? image : image.substring(0, colon);
    }

    private static String azure() throws IOException {
        String acr = required("AZURE_ACR", "set AZURE_ACR=<registry-name>");
        String name = acr.endsWith(".azurecr.io") ? acr.substring(0, acr.length() - ".azurecr.io".length()) : acr;
        if (!Cli.available("az")) {
            throw new IllegalStateException("the az CLI is not on the PATH");
        }
        if (!skipLogin()) {
            Cli.run(List.of("az", "acr", "login", "--name", name));
        }
        return name + ".azurecr.io";
    }

    private static String gcp() throws IOException {
        String repository = required("GCP_AR_REPO",
                "set GCP_AR_REPO=<region>-docker.pkg.dev/<project>/<repo>");
        if (!Cli.available("gcloud")) {
            throw new IllegalStateException("the gcloud CLI is not on the PATH");
        }
        if (!skipLogin()) {
            String host = repository.contains("/") ? repository.substring(0, repository.indexOf('/')) : repository;
            Cli.run(List.of("gcloud", "auth", "configure-docker", host, "--quiet"));
        }
        return repository;
    }

    /**
     * Scaleway Container Registry. {@code SCW_REGISTRY} is the namespace's endpoint,
     * {@code rg.<region>.scw.cloud/<namespace>}, and the login is an IAM API key's secret under the fixed user name
     * {@code nologin}, which is how that registry takes a key. There is no CLI to sign in with first, unlike Azure's
     * and Google's, so the key reaches the push step itself.
     */
    private static String scaleway() throws IOException {
        String registry = required("SCW_REGISTRY", "set SCW_REGISTRY=rg.<region>.scw.cloud/<namespace>");
        if (!skipLogin()) {
            String key = required("SCW_SECRET_KEY", "set SCW_SECRET_KEY (the secret of an IAM API key allowed to "
                    + "push to the namespace), or SKIP_LOGIN=1");
            String host = registry.contains("/") ? registry.substring(0, registry.indexOf('/')) : registry;
            Cli.feed(List.of("docker", "login", host, "--username", "nologin", "--password-stdin"), key);
        }
        return registry;
    }

    // ---- configuration ----

    /** Every target, in the order a publish walks them; {@code all} names this list. */
    private static final List<String> TARGETS = List.of("hub", "aws", "azure", "gcp", "scaleway");

    /** The targets named, in a stable order; {@code all} is every one of them. */
    private static List<String> targets() {
        String named = setting(TARGET, "PUSH_TARGET", "");
        if (named.isEmpty()) {
            return List.of();
        }
        return named.equals("all") ? TARGETS
                : Stream.of(named.split(",")).map(String::strip).filter(target -> !target.isEmpty()).toList();
    }

    private static boolean skipLogin() {
        return "1".equals(environment("SKIP_LOGIN", "0"));
    }

    /** A system property, then the environment variable the workflow already sets, then a default. */
    private static String setting(String property, String variable, String fallback) {
        String value = System.getProperty(property, "");
        return value.isEmpty() ? environment(variable, fallback) : value;
    }

    private static String environment(String variable, String fallback) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String required(String variable, String message) {
        String value = environment(variable, "");
        if (value.isEmpty()) {
            throw new IllegalStateException(message);
        }
        return value;
    }
}
