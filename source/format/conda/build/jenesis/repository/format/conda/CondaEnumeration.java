package build.jenesis.repository.format.conda;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.format.ProxyFormat;

/**
 * Walks a conda channel rooted at an upstream - the same {@code repodata.json} {@link CondaFormat} generates to
 * serve clients, pointed at "list everything": the channel's subdirs come from its {@code channeldata.json} where
 * one is served (jenesis's own included), falling back to probing the standard platform subdirs; each subdir's
 * {@code repodata.json} contributes every {@code packages} and {@code packages.conda} filename. Each entry pairs
 * the {@code <subdir>/<filename>} path {@link CondaImporter} accepts with its download URL. Reached through
 * {@code CondaFormat}'s {@code ProxyFormat.enumerate}. The subdir discovery is eager (an unreachable channel fails up front); per-subdir repodata reads lazily, failures
 * surfacing as {@link UncheckedIOException} - except a {@code 404}, which is an empty subdir, not a failure.
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

    /** The channel's subdirs: its {@code channeldata.json} where one answers, else the standard platform set -
     *  probing costs one {@code 404} per absent subdir, which the walk treats as empty. */
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
