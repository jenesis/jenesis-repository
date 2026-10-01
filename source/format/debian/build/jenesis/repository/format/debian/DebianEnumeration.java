package build.jenesis.repository.format.debian;

import module java.base;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.format.ProxyFormat;

/**
 * Walks an apt repository rooted at an upstream. apt never lists suites, so they are discovered from the {@code dists/}
 * autoindex mirrors expose; each suite's {@code Release} names its {@code Packages} indexes ({@code .gz} preferred per
 * directory), which are streamed stanza by stanza for their {@code Filename} fields, since a distribution index runs to
 * tens of megabytes. Each entry pairs the pool path {@link DebianImporter} accepts with its download URL; a package
 * listed under several architectures or suites is emitted once. The {@code dists/} listing is read eagerly, so a source without one
 * fails up front; suites stream lazily, failures surfacing as {@link UncheckedIOException}.
 */
public final class DebianEnumeration {

    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");

    private DebianEnumeration() {
    }

    /**
     * @param allowInternal the deployment's {@link build.jenesis.repository.blobs.ProxyLeg#ALLOW_INTERNAL} dial, the
     *     one the proxy leg reads, screened by {@link build.jenesis.repository.blobs.OutboundTargets}: off, a
     *     cross-origin target must be {@code https} and public; a target on the upstream's own origin is trusted either
     *     way
     */
    public static Stream<Map.Entry<String, URI>> enumerate(ProxyFormat.Fetcher fetcher, URI upstream,
                                                           boolean allowInternal) throws IOException {
        String base = upstream.toString();
        URI root = URI.create(base.endsWith("/") ? base : base + "/");
        URI dists = root.resolve("dists/");
        List<String> suites = new ArrayList<>();
        Matcher matcher = HREF.matcher(fetch(fetcher, dists));
        while (matcher.find()) {
            String href = matcher.group(1);
            URI resolved = dists.resolve(href);
            String suite = resolved.toString();
            if (suite.startsWith(dists.toString()) && suite.endsWith("/") && suite.length() > dists.toString().length()) {
                suites.add(suite.substring(dists.toString().length(), suite.length() - 1));
            }
        }
        if (suites.isEmpty()) {
            throw new IOException("No suites listed at " + dists);
        }
        Set<String> emitted = new HashSet<>();
        return suites.stream()
                .filter(suite -> !suite.contains("/"))
                .flatMap(suite -> {
                    try {
                        return indexes(fetcher, root, suite).stream();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .flatMap(index -> filenames(fetcher, index))
                .filter(emitted::add)
                .map(filename -> Map.entry(filename, root.resolve(filename)))
                // Filename is untrusted upstream content, and an absolute value replaces the host on resolve, so a
                // hostile mirror could name an internal address as a download target; each URL is screened like every
                // other leg's.
                .filter(entry -> OutboundTargets.mayFollow(entry.getValue(), root, allowInternal));
    }


    /** The suite's binary package indexes, from its {@code Release} checksum lists: every {@code Packages} or
     *  {@code Packages.gz} entry, the compressed one preferred where a directory lists both. */
    private static List<URI> indexes(ProxyFormat.Fetcher fetcher, URI root, String suite) throws IOException {
        String release = fetch(fetcher, root.resolve("dists/" + suite + "/Release"));
        Map<String, String> byDirectory = new LinkedHashMap<>();
        for (String line : release.split("\n")) {
            if (!line.startsWith(" ")) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 3) {
                continue;
            }
            String path = parts[2];
            if (!path.endsWith("/Packages") && !path.endsWith("/Packages.gz")) {
                continue;
            }
            String directory = path.substring(0, path.lastIndexOf('/'));
            String known = byDirectory.get(directory);
            if (known == null || path.endsWith(".gz")) {
                byDirectory.put(directory, path);
            }
        }
        List<URI> indexes = new ArrayList<>();
        for (String path : byDirectory.values()) {
            indexes.add(root.resolve("dists/" + suite + "/" + path));
        }
        return indexes;
    }

    /** Stream one index's {@code Filename} fields, gunzipped by suffix; closed with the stream, so an early-terminated
     *  walk releases the download. */
    private static Stream<String> filenames(ProxyFormat.Fetcher fetcher, URI index) {
        try {
            ProxyFormat.Download download = fetcher.download(index, Map.of())
                    .orElseThrow(() -> ProxyFormat.Unavailable.noResponse(index));
            if (download.status() != 200) {
                download.close();
                throw ProxyFormat.Unavailable.status(index, download.status());
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    index.toString().endsWith(".gz") ? new GZIPInputStream(download.body()) : download.body(),
                    StandardCharsets.UTF_8));
            return reader.lines()
                    .filter(line -> line.startsWith("Filename:"))
                    .map(line -> line.substring("Filename:".length()).trim())
                    .onClose(() -> {
                        try {
                            reader.close();
                            download.close();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String fetch(ProxyFormat.Fetcher fetcher, URI url) throws IOException {
        return new String(ProxyFormat.Fetcher.required(fetcher, url).body(), StandardCharsets.UTF_8);
    }
}
