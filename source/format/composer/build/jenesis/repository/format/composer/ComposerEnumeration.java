package build.jenesis.repository.format.composer;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.blobs.OutboundTargets;

/**
 * Walks a Composer-v2 repository rooted at an upstream: package names come from the root's {@code list} endpoint or,
 * for an upstream that inlines them, its {@code available-packages}; each name's {@code p2/<vendor>/<package>.json} and
 * {@code ~dev} companion contribute an entry per version naming its own zip dist (minification drops only fields equal
 * to the previous version's, and a dist differs per version, so a dist-less entry has none). Each entry pairs the
 * {@code <vendor>/<package>/<version>.zip} path {@link ComposerImporter} accepts with the version's {@code dist.url},
 * screened by {@link build.jenesis.repository.blobs.OutboundTargets} as the proxy leg screens it. Only zip dists are
 * emitted. The root is read eagerly, so a repository listing no packages fails up front; the metadata reads lazily,
 * failures surfacing as {@link UncheckedIOException}, and a {@code 404} p2 file, a stale listing, is skipped.
 */
public final class ComposerEnumeration {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ComposerEnumeration() {
    }

    /**
     * @param allowInternal the deployment's {@link build.jenesis.repository.blobs.ProxyLeg#ALLOW_INTERNAL} dial, the
     *     one the proxy leg reads, screened by {@link build.jenesis.repository.blobs.OutboundTargets}: off, a
     *     cross-origin dist must be {@code https} and public; a dist on the upstream's own origin is trusted either way
     */
    public static Stream<Map.Entry<String, URI>> enumerate(ProxyFormat.Fetcher fetcher, URI upstream,
                                                           boolean allowInternal) throws IOException {
        String base = upstream.toString();
        URI root = URI.create(base.endsWith("/") ? base : base + "/");
        JsonNode packages = MAPPER.readTree(fetch(fetcher, root.resolve("packages.json")));
        String template = packages.path("metadata-url").asString("/p2/%package%.json");
        List<String> names = names(fetcher, root, packages);
        return names.stream()
                .flatMap(name -> Stream.of(name, name + "~dev"))
                .flatMap(name -> {
                    try {
                        return versions(fetcher, root, template, name, allowInternal).stream();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    /** The names to walk: the root's inline {@code available-packages}, else the document behind its {@code list} URL
     *  ({@code {"packageNames": [...]}}); with neither the repository is not enumerable. */
    private static List<String> names(ProxyFormat.Fetcher fetcher, URI root, JsonNode packages) throws IOException {
        List<String> names = new ArrayList<>();
        for (JsonNode name : packages.path("available-packages")) {
            String text = name.asString(null);
            if (text != null) {
                names.add(text);
            }
        }
        if (!names.isEmpty()) {
            return names;
        }
        String list = packages.path("list").asString(null);
        if (list == null) {
            throw new IOException("Not enumerable: neither available-packages nor a list endpoint at "
                    + root.resolve("packages.json"));
        }
        for (JsonNode name : MAPPER.readTree(fetch(fetcher, root.resolve(list))).path("packageNames")) {
            String text = name.asString(null);
            if (text != null) {
                names.add(text);
            }
        }
        return names;
    }

    /** One package's versions from its {@code p2} file, minified entries expanded by carrying {@code dist} forward, a
     *  version emitted when it names a public zip dist. A {@code 404}, a stale listing or an absent {@code ~dev},
     *  contributes nothing. */
    private static List<Map.Entry<String, URI>> versions(ProxyFormat.Fetcher fetcher, URI root, String template,
                                                         String name, boolean allowInternal) throws IOException {
        Optional<ProxyFormat.Fetched> fetched = fetcher.fetch(
                root.resolve(template.replace("%package%", name)), Map.of());
        if (fetched.isEmpty()) {
            throw ProxyFormat.Unavailable.noResponse(root.resolve(template.replace("%package%", name)));
        }
        if (fetched.get().status() == 404) {
            return List.of();
        }
        if (fetched.get().status() != 200) {
            throw ProxyFormat.Unavailable.status(root.resolve(template.replace("%package%", name)), fetched.get().status());
        }
        String plain = name.endsWith("~dev") ? name.substring(0, name.length() - "~dev".length()) : name;
        List<Map.Entry<String, URI>> entries = new ArrayList<>();
        JsonNode versions = MAPPER.readTree(new String(fetched.get().body(), StandardCharsets.UTF_8))
                .path("packages").path(plain);
        for (JsonNode entry : versions) {
            // A version without its own dist has none to download.
            JsonNode dist = entry.path("dist");
            String version = entry.path("version").asString(null);
            String url = dist.path("url").asString(null);
            String type = dist.path("type").asString("zip");
            if (version == null || url == null || !type.equals("zip")) {
                continue;
            }
            URI target;
            try {
                target = URI.create(url);
            } catch (IllegalArgumentException invalid) {
                continue;
            }
            // The same screen ComposerFormat.distUrl applies to the same field.
            if (OutboundTargets.mayFollow(target, root, allowInternal)) {
                entries.add(Map.entry(plain + "/" + version + ".zip", target));
            }
        }
        return entries;
    }

    private static String fetch(ProxyFormat.Fetcher fetcher, URI url) throws IOException {
        return new String(ProxyFormat.Fetcher.required(fetcher, url).body(), StandardCharsets.UTF_8);
    }
}
