package build.jenesis.repository.format.pypi;

import module java.base;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.format.ProxyFormat;

/**
 * Walks a PEP 503 simple index rooted at an upstream: the root {@code simple/} project list, then each project's page,
 * each file link resolved against its page (files usually live on another host) with its {@code #sha256} fragment
 * dropped. Each entry pairs the {@code <project>/<filename>} path {@link PyPiImporter} accepts with the file's download
 * URL, for any index speaking PEP 503. The root list is read eagerly, so an index-less
 * source fails up front; project pages are read lazily, failures surfacing as {@link UncheckedIOException}.
 */
public final class PyPiEnumeration {

    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");

    private PyPiEnumeration() {
    }

    /**
     * @param allowInternal the deployment's {@link build.jenesis.repository.blobs.ProxyLeg#ALLOW_INTERNAL} dial, the
     *     one the proxy leg reads, screened by {@link build.jenesis.repository.blobs.OutboundTargets}: off, a
     *     cross-origin file link must be {@code https} and public; a link on the upstream's own origin is trusted
     *     either way
     */
    public static Stream<Map.Entry<String, URI>> enumerate(ProxyFormat.Fetcher fetcher, URI upstream,
                                                           boolean allowInternal) throws IOException {
        String root = upstream.toString();
        URI index = URI.create((root.endsWith("/") ? root : root + "/") + "simple/");
        List<URI> projects = new ArrayList<>();
        Matcher matcher = HREF.matcher(fetch(fetcher, index));
        while (matcher.find()) {
            URI page = index.resolve(strip(matcher.group(1)));
            if (!page.toString().endsWith("/")) {
                page = URI.create(page + "/");
            }
            projects.add(page);
        }
        return projects.stream().flatMap(page -> {
            try {
                List<Map.Entry<String, URI>> files = new ArrayList<>();
                String project = segment(page);
                Matcher link = HREF.matcher(fetch(fetcher, page));
                while (link.find()) {
                    URI file = page.resolve(strip(link.group(1)));
                    // The file href is untrusted upstream content and cross-host by design, so it is screened before it
                    // becomes a download target.
                    if (!OutboundTargets.mayFollow(file, index, allowInternal)) {
                        continue;
                    }
                    files.add(Map.entry(project + "/" + segment(file), file));
                }
                return files.stream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }


    /** The href with any {@code #sha256=...} fragment dropped, so it resolves to the plain file URL. */
    private static String strip(String href) {
        int fragment = href.indexOf('#');
        return fragment < 0 ? href : href.substring(0, fragment);
    }

    /** The last path segment of a URL, percent-decoded - a page's project name, a file link's filename. */
    private static String segment(URI url) {
        String path = url.getPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String fetch(ProxyFormat.Fetcher fetcher, URI url) throws IOException {
        return new String(ProxyFormat.Fetcher.required(fetcher, url).body(), StandardCharsets.UTF_8);
    }
}
