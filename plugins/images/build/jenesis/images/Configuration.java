package build.jenesis.images;

import module java.base;

/**
 * What a build's images goal makes and publishes, read from the plugin's values - {@code images.<key>} in the
 * repository's {@code jenesis.plugins.arguments.properties}:
 *
 * <pre>
 * image.&lt;module path&gt;=&lt;repository&gt;      the image a module's staged context is built as
 * image.&lt;module path&gt;=                  a context this build stages but does not make an image of
 * publish.&lt;repository&gt;=false            an image that is built and never pushed
 * chart.&lt;name&gt;=&lt;input&gt;/&lt;path&gt;           a Helm chart packaged from the chart source at a bound input's path
 * chart.&lt;name&gt;.image=&lt;repository&gt;       the image the packaged chart runs by default
 * chart.&lt;name&gt;.values=&lt;input&gt;/&lt;path&gt;    a values file the chart is also rendered through before it is packaged
 * </pre>
 *
 * <p><b>Every staged context is named, one way or the other.</b> A module declaring {@code docker=<base>} in its
 * {@code packaging.properties} stages a context whether or not this build means to make an image of it, and one
 * missing from the configuration is an error naming it rather than a skip: the failure being avoided is an image
 * quietly dropping out of a release. Naming it with an empty value is how a build says the image is somebody else's.
 *
 * <p><b>An image's repository is its published name.</b> It is built locally as {@code <repository>:latest} and
 * pushed as {@code <registry>/<repository>} tagged with the version and {@code latest}, so what a registry shows is
 * what the configuration says, with nothing derived in between.
 *
 * <p>Serializable, because the steps carry it: a step is keyed by the digest of its serialized form, so changing
 * what a build publishes re-runs the steps it affects.
 */
record Configuration(TreeMap<String, String> images, TreeSet<String> unpublished, LinkedHashMap<String, Chart> charts)
        implements Serializable {

    /** A chart to package: its published name, the chart source and optional values overlay as
     *  {@code <input>/<path>}, and the repository its defaults run. */
    record Chart(String name, String source, String image, String values) implements Serializable {
    }

    private static final String IMAGE = "image.", PUBLISH = "publish.", CHART = "chart.";

    static Configuration of(SequencedMap<String, String> properties) {
        TreeMap<String, String> images = new TreeMap<>();
        TreeSet<String> unpublished = new TreeSet<>();
        LinkedHashMap<String, String[]> charts = new LinkedHashMap<>();
        properties.forEach((key, value) -> {
            String trimmed = value == null ? "" : value.strip();
            if (key.startsWith(IMAGE)) {
                images.put(key.substring(IMAGE.length()), trimmed);
            } else if (key.startsWith(PUBLISH)) {
                if (!trimmed.equals("true") && !trimmed.equals("false")) {
                    throw new IllegalArgumentException(key + " must be true or false, not '" + value + "'");
                }
                if (trimmed.equals("false")) {
                    unpublished.add(key.substring(PUBLISH.length()));
                }
            } else if (key.startsWith(CHART)) {
                String rest = key.substring(CHART.length());
                int dot = rest.lastIndexOf('.');
                String attribute = dot < 0 ? "" : rest.substring(dot + 1);
                boolean known = attribute.equals("image") || attribute.equals("values");
                String name = known ? rest.substring(0, dot) : rest;
                String[] chart = charts.computeIfAbsent(name, _ -> new String[3]);
                chart[known ? (attribute.equals("image") ? 1 : 2) : 0] = trimmed;
            } else {
                throw new IllegalArgumentException("Unknown images setting '" + key + "' - one of image.<module>, "
                        + "publish.<repository>, chart.<name>, chart.<name>.image, chart.<name>.values");
            }
        });
        LinkedHashMap<String, Chart> resolved = new LinkedHashMap<>();
        charts.forEach((name, chart) -> {
            if (chart[0] == null || chart[0].isEmpty() || chart[1] == null || chart[1].isEmpty()) {
                throw new IllegalArgumentException("The chart " + name + " needs both chart." + name
                        + "=<input>/<path> and chart." + name + ".image=<repository>");
            }
            resolved.put(name, new Chart(name, chart[0], chart[1], chart[2] == null || chart[2].isEmpty()
                    ? null : chart[2]));
        });
        return new Configuration(images, unpublished, resolved);
    }

    /**
     * The repository a module's staged context is built as, or empty when this build names it as not its image.
     *
     * @throws IllegalStateException for a module the configuration does not name at all - see the class javadoc.
     */
    Optional<String> image(String module) {
        String repository = images.get(module);
        if (repository == null) {
            throw new IllegalStateException("The staged context '" + module + "' is named by no image."
                    + module + " setting. A module declaring docker=<base> in its packaging.properties stages an "
                    + "image context, and this build's images configuration says what becomes of it: "
                    + "image." + module + "=<repository> to build it, or image." + module + "= if it is not an "
                    + "image this build makes. Named: " + images.keySet());
        }
        return repository.isEmpty() ? Optional.empty() : Optional.of(repository);
    }

    /** Whether a built image is pushed when a publish target is named. */
    boolean published(String repository) {
        return !unpublished.contains(repository);
    }
}
