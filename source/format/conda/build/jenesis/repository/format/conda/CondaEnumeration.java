package build.jenesis.repository.format.conda;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.format.ProxyFormat;

/**
 * Walks a conda channel rooted at an upstream: the subdirs come from its {@code channeldata.json} where served, else by
 * probing the standard platform subdirs, and each subdir's {@code repodata.json} contributes every {@code packages} and
 * {@code packages.conda} filename. Each entry pairs the {@code <subdir>/<filename>} path {@link CondaImporter} accepts
 * with its download URL. Subdir discovery is eager, so an unreachable channel fails up front; repodata reads lazily,
 * failures surfacing as {@link UncheckedIOException}, a {@code 404} being an empty subdir.
 */
public final class CondaEnumeration {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The standard platform subdirs probed when a channel serves no {@code channeldata.json}. */
    private static final List<String> SUBDIRS = List.of("noarch", "linux-64", "linux-aarch64", "linux-ppc64le",
            "osx-64", "osx-arm64", "win-64", "win-arm64");

    private CondaEnumeration() {
    }

    public static Stream<Map.Entry<String, URI>> enumerate(ProxyFormat.Fetcher fetcher, URI upstream)
            throws IOException {
        String base = upstream.toString();
        URI root = URI.create(base.endsWith("/") ? base : base + "/");
        return subdirs(fetcher, root).stream().flatMap(subdir -> {
            try {
                Optional<ProxyFormat.Fetched> fetched = fetcher.fetch(root.resolve(subdir + "/repodata.json"), Map.of());
                if (fetched.isEmpty()) {
                    throw ProxyFormat.Unavailable.noResponse(root.resolve(subdir + "/repodata.json"));
                }
                if (fetched.get().status() == 404) {
                    return Stream.empty();
                }
                if (fetched.get().status() != 200) {
                    throw ProxyFormat.Unavailable.status(root.resolve(subdir + "/repodata.json"), fetched.get().status());
                }
                JsonNode repodata = MAPPER.readTree(new String(fetched.get().body(), StandardCharsets.UTF_8));
                List<Map.Entry<String, URI>> entries = new ArrayList<>();
                for (String section : new String[]{"packages", "packages.conda"}) {
                    for (Map.Entry<String, JsonNode> property : repodata.path(section).properties()) {
                        String filename = property.getKey();
                        entries.add(Map.entry(subdir + "/" + filename, root.resolve(subdir + "/" + filename)));
                    }
                }
                return entries.stream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** The channel's subdirs: its {@code channeldata.json} where one answers, else the standard set, an absent subdir
     *  costing one {@code 404} read as empty. */
    private static List<String> subdirs(ProxyFormat.Fetcher fetcher, URI root) throws IOException {
        Optional<ProxyFormat.Fetched> fetched = fetcher.fetch(root.resolve("channeldata.json"), Map.of());
        if (fetched.isEmpty()) {
            throw ProxyFormat.Unavailable.noResponse(root.resolve("channeldata.json"));
        }
        if (fetched.get().status() != 200) {
            return SUBDIRS;
        }
        List<String> subdirs = new ArrayList<>();
        for (JsonNode subdir : MAPPER.readTree(new String(fetched.get().body(), StandardCharsets.UTF_8)).path("subdirs")) {
            String name = subdir.asString(null);
            if (name != null && !name.contains("/") && !name.contains("..")) {
                subdirs.add(name);
            }
        }
        return subdirs.isEmpty() ? SUBDIRS : subdirs;
    }
}
