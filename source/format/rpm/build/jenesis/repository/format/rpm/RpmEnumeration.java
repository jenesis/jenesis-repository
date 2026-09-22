package build.jenesis.repository.format.rpm;

import module java.base;
import module java.xml;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.format.ProxyFormat;

/**
 * Walks a yum/dnf repository rooted at an upstream - the same {@code repodata} {@link RpmFormat} generates to serve
 * clients, pointed at "list everything": {@code repodata/repomd.xml} names the {@code primary} index, whose
 * {@code <location href>} entries are streamed one {@code <package>} at a time (the primary of a large distribution
 * mirror runs to hundreds of megabytes, so it is never buffered whole; gzip is unwrapped by suffix). Each entry
 * pairs the repo-root-relative location - the path {@link RpmImporter} accepts - with its download URL. Covers a
 * real yum mirror, a Nexus or Artifactory yum repository, and jenesis's own generated repodata alike. Not wired into
 * {@code ProxyFormat.enumerate}; callers drive it directly. The {@code repomd.xml} is read eagerly (a repo without one
 * fails up front); the primary streams lazily, failures surfacing as {@link UncheckedIOException}. XML is parsed with
 * DTDs and external entities disabled.
 */
public final class RpmEnumeration {

    private RpmEnumeration() {
    }

    /**
     * @param allowInternal the deployment's {@link build.jenesis.repository.blobs.ProxyLeg#ALLOW_INTERNAL} dial - the
     *                      same one the proxy leg reads, so a walk and a pull-through of the same advertised URL cannot
     *                      answer differently, and since the earlier work/the one screen they both call is {@link
     *                      build.jenesis.repository.blobs.OutboundTargets}. Off means a cross-origin target must be
     *                      {@code https} and public; one on the submitted upstream's own ORIGIN (scheme and authority)
     *                      is operator-trusted either way, because it reaches no host, port or scheme the walk is not
     *                      already reaching.
     */
    public static Stream<Map.Entry<String, URI>> enumerate(ProxyFormat.Fetcher fetcher, URI upstream,
                                                           boolean allowInternal) throws IOException {
        String base = upstream.toString();
        URI root = URI.create(base.endsWith("/") ? base : base + "/");
        URI repomd = root.resolve("repodata/repomd.xml");
        Optional<ProxyFormat.Fetched> fetched = fetcher.fetch(repomd, Map.of());
        if (fetched.isEmpty()) {
            throw ProxyFormat.Unavailable.noResponse(repomd);
        }
        if (fetched.get().status() != 200) {
            throw ProxyFormat.Unavailable.status(repomd, fetched.get().status());
        }
        String primary = primaryHref(fetched.get().body());
        if (primary == null) {
            throw new IOException("No primary index named by " + repomd);
        }
        URI location = root.resolve(primary);
        // The primary <location href> comes from untrusted upstream repomd.xml; an absolute value replaces the host
        // (java.net.URI.resolve returns an absolute argument as-is), and this URL is fetched server-side right here, so
        // a hostile repomd pointing primary at a loopback / 169.254.169.254 control plane is an immediate SSRF. Refuse
        // it through the one shared OutboundTargets call every peer leg and walk now makes.
        if (!OutboundTargets.mayFollow(location, root, allowInternal)) {
            throw new IOException("Refusing a primary index at a private host: " + location);
        }
        ProxyFormat.Download download = fetcher.download(location, Map.of())
                .orElseThrow(() -> ProxyFormat.Unavailable.noResponse(location));
        if (download.status() != 200) {
            download.close();
            throw ProxyFormat.Unavailable.status(location, download.status());
        }
        try {
            InputStream body = primary.endsWith(".gz") ? new GZIPInputStream(download.body()) : download.body();
            XMLStreamReader reader = factory().createXMLStreamReader(body);
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(
                            new LocationIterator(reader), Spliterator.ORDERED), false)
                    .map(href -> Map.entry(href, root.resolve(href)))
                    // Each <package><location href> comes from untrusted upstream primary.xml; an absolute value
                    // replaces the host, so a hostile primary pointing a package at a private control-plane host would
                    // become an importer download target. Screen each emitted URL through the one shared
                    // OutboundTargets call every peer leg and walk now makes.
                    .filter(entry -> OutboundTargets.mayFollow(entry.getValue(), root, allowInternal))
                    .onClose(() -> {
                        try {
                            download.close();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException | XMLStreamException e) {
            download.close();
            throw e instanceof IOException failure ? failure : new IOException(e);
        }
    }


    /** The {@code <location href>} of the {@code <data type="primary">} entry, or {@code null} when absent. */
    private static String primaryHref(byte[] repomd) throws IOException {
        try {
            XMLStreamReader reader = factory().createXMLStreamReader(new ByteArrayInputStream(repomd));
            boolean primary = false;
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                    if (reader.getLocalName().equals("data")) {
                        primary = "primary".equals(reader.getAttributeValue(null, "type"));
                    } else if (primary && reader.getLocalName().equals("location")) {
                        return reader.getAttributeValue(null, "href");
                    }
                }
            }
            return null;
        } catch (XMLStreamException e) {
            throw new IOException(e);
        }
    }

    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    /** Pulls one {@code <package><location href>} per advance from the streaming reader. */
    private static final class LocationIterator implements Iterator<String> {

        private final XMLStreamReader reader;
        private String next;

        private LocationIterator(XMLStreamReader reader) {
            this.reader = reader;
        }

        @Override
        public boolean hasNext() {
            if (next != null) {
                return true;
            }
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT
                            && reader.getLocalName().equals("location")) {
                        String href = reader.getAttributeValue(null, "href");
                        if (href != null) {
                            next = href;
                            return true;
                        }
                    }
                }
                return false;
            } catch (XMLStreamException e) {
                throw new UncheckedIOException(new IOException(e));
            }
        }

        @Override
        public String next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            String href = next;
            next = null;
            return href;
        }
    }
}
