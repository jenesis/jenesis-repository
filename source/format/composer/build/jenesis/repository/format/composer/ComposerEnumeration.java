package build.jenesis.repository.format.composer;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.blobs.OutboundTargets;

/**
 * Walks a Composer-v2 repository rooted at an upstream - the same {@code packages.json} and {@code p2} files
 * {@link ComposerFormat} already reads to serve pull-through, pointed at "list everything": package names come from
 * the root's {@code list} endpoint (jenesis emits one, {@code list.json}; Packagist's is likewise) or, for an upstream
 * that inlines it instead, its {@code available-packages} array; each name's {@code p2/<vendor>/<package>.json} and
 * {@code ~dev} companion contribute one
 * entry per version that names its own zip dist (minification only drops fields equal to the previous version's,
 * and a dist differs per version, so a dist-less entry genuinely has none to download). Each entry
 * pairs the {@code <vendor>/<package>/<version>.zip} path {@link ComposerImporter} accepts with the version's
 * {@code dist.url} - which comes from untrusted metadata, so it passes the same
 * {@link build.jenesis.repository.blobs.OutboundTargets} screen the proxy leg applies to the very same field, a
 * private cross-origin target skipped rather than fetched. Only zip dists are emitted (the shape
 * the importer replays). Reached through {@code ComposerFormat}'s
 * {@code ProxyFormat.enumerate}. The root document is read eagerly - a repository advertising neither a package list
 * nor {@code available-packages} fails up front with the honest constraint - and the per-package metadata reads
 * lazily, failures surfacing as {@link UncheckedIOException} (a {@code 404} p2 file is a stale listing, skipped).
 */
public final class ComposerEnumeration {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ComposerEnumeration() {
    }

    /**
     * @param allowInternal the deployment's {@link build.jenesis.repository.blobs.ProxyLeg#ALLOW_INTERNAL} dial - the
     *                      same one the proxy leg reads, so a walk and a pull-through of the same {@code dist.url}
     *                      cannot answer differently, and since the earlier work/the one screen they both call is
     *                      {@link build.jenesis.repository.blobs.OutboundTargets}. Off means a cross-origin dist must
     *                      be {@code https} and public; one on the submitted upstream's own ORIGIN (scheme and
     *                      authority) is operator-trusted either way, because it reaches no host, port or scheme the
     *                      walk is not already reaching.
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

    /** The names to walk: the root's inline {@code available-packages}, else the document behind its {@code list}
     *  URL ({@code {"packageNames": [...]}}); neither is the honest constraint - the repository is not enumerable. */
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

    /** One package's versions from its {@code p2} file: minified entries expanded by carrying {@code dist} forward,
     *  a version emitted when it names a public zip dist. A {@code 404} is a stale listing (or an absent {@code ~dev}
     *  companion) and contributes nothing. */
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
            // Minified metadata drops only fields equal to the previous version's; a dist differs per version, so a
            // version without its own dist genuinely has none to download.
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
            // the earlier sharpest instance was here: this walk exempted a dist on the submitted upstream's own authority
            // while ComposerFormat.distUrl - eighty lines away, over the same dist.url field of the same document -
            // refused exactly that. Same format, same field, two answers, neither aware of the other. Both now ask
            // OutboundTargets, so there is one answer and it is the exempting one (see that class for why).
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
