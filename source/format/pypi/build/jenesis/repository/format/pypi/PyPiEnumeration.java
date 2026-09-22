package build.jenesis.repository.format.pypi;

import module java.base;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.format.ProxyFormat;

/**
 * Walks a PEP 503 simple index rooted at an upstream - the same pages {@link PyPiFormat} already reads to serve
 * pull-through, pointed at "list everything" instead of "resolve one": the root {@code simple/} project list, then
 * each project's page, each file link resolved against the page it appears on (the file usually lives on another
 * host, pypi.org's files on files.pythonhosted.org) with its {@code #sha256} fragment dropped. Each entry pairs the
 * layout path {@link PyPiImporter} accepts ({@code <project>/<filename>}) with the file's download URL. Covers
 * pypi.org, a Nexus or Artifactory pypi repository, and jenesis's own generated index alike - anything speaking
 * PEP 503. Not wired into {@code ProxyFormat.enumerate}; callers drive it directly. The root list is read eagerly
 * (an unreachable or index-less source fails up front); project pages are read lazily as the stream advances,
 * failures surfacing as {@link UncheckedIOException}.
 */
public final class PyPiEnumeration {

    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");

    private PyPiEnumeration() {
    }

    /**
     * @param allowInternal the deployment's {@link build.jenesis.repository.blobs.ProxyLeg#ALLOW_INTERNAL} dial - the
     *                      same one the proxy leg reads, so a walk and a pull-through of the same file href cannot
     *                      answer differently, and since the earlier work/the one screen they both call is {@link
     *                      build.jenesis.repository.blobs.OutboundTargets}. Off means a cross-origin file link must be
     *                      {@code https} and public; one on the submitted upstream's own ORIGIN (scheme and authority)
     *                      is operator-trusted either way, because it reaches no host, port or scheme the walk is not
     *                      already reaching.
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
                    // The file href comes from untrusted upstream index content and is cross-host by design
                    // (pypi.org's files live on files.pythonhosted.org), so a hostile index linking a loopback /
                    // 169.254.169.254 / CGNAT control plane must not become an importer download target. Screen it
                    // through the one shared OutboundTargets call every peer leg and walk now makes.
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
