package build.jenesis.repository.format.debian;

import module java.base;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.format.ProxyFormat;

/**
 * Walks an apt repository rooted at an upstream - the same {@code Release} and {@code Packages} indexes
 * {@link DebianFormat} generates to serve clients, pointed at "list everything": the apt protocol never lists
 * suites, so they are discovered from the {@code dists/} autoindex real mirrors (and jenesis itself) expose; each
 * suite's {@code Release} names its per-component {@code Packages} indexes ({@code .gz} preferred per directory),
 * which are streamed stanza by stanza for their {@code Filename} fields - never buffered whole, a distribution
 * mirror's index runs to tens of megabytes. Each entry pairs the pool path {@link DebianImporter} accepts (relative
 * to the repository root, as apt resolves it) with its download URL; a package listed for several architectures or
 * suites is emitted once. Reached through {@code DebianFormat}'s
 * {@code ProxyFormat.enumerate}. The {@code dists/} listing is read eagerly (a source without one fails up front with
 * the honest constraint); suites stream lazily, failures surfacing as {@link UncheckedIOException}.
 */
public final class DebianEnumeration {

    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");

    private DebianEnumeration() {
    }

    /**
     * @param allowInternal the deployment's {@link build.jenesis.repository.blobs.ProxyLeg#ALLOW_INTERNAL} dial - the
     *                      same one the proxy leg reads, so a walk and a pull-through of the same advertised URL cannot
     *                      answer differently, and the one screen they both call is {@link
     *                      build.jenesis.repository.blobs.OutboundTargets}. Off means a cross-origin target must be
     *                      {@code https} and public; one on the submitted upstream's own ORIGIN (scheme and authority)
     *                      is operator-trusted either way, because it reaches no host, port or scheme the walk is not
     *                      already reaching.
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
                // The Filename field comes from untrusted upstream Packages content; an absolute value replaces the
                // host (java.net.URI.resolve returns an absolute argument as-is), so a hostile mirror's
                // "Filename: http://169.254.169.254/..." would become an importer download target. Screen each emitted
                // URL through the one shared OutboundTargets call every peer leg and walk now makes.
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

    /** Stream one index's {@code Filename} fields, the body gunzipped by suffix and read line by line - closed with
     *  the stream, so an early-terminated walk releases the download. */
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
