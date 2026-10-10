package build.jenesis.repository.gateway;

import module java.base;
import build.jenesis.repository.discovery.DiscoveryException;
import build.jenesis.repository.discovery.RepositoryDiscovery;
import build.jenesis.repository.discovery.RepositoryDiscovery.Located;
import build.jenesis.repository.format.ProxyFormat;

/**
 * The fetcher a discovered leg's format fetches through where a domain names a template: each request the format
 * composes under {@code base} is read back to the repository path it asks for and sent to where that file is - the
 * template filled in for it, or under the root a file names - and the Maven metadata a latest link names is answered
 * here rather than fetched. A file no domain names answers {@code 404}, so the format reads it as absent upstream.
 *
 * <p>A download {@link Located.Fetched#checked() checked} against the checksum beside it is: the strongest of
 * {@code .sha512}, {@code .sha256} and {@code .sha1} the location holds is fetched first, and the body is read through
 * a digest that fails the read at its end where they differ - so a mismatched body is never kept or served whole. A
 * location holding none is served unchecked, as an upstream's own sidecars are.
 */
final class DiscoveredFetcher implements ProxyFormat.Fetcher {

    /** The checksums a fetched file is checked against, strongest first, with the algorithm each names. */
    private static final List<Map.Entry<String, String>> CHECKSUMS = List.of(Map.entry(".sha512", "SHA-512"),
            Map.entry(".sha256", "SHA-256"), Map.entry(".sha1", "SHA-1"));

    private final ProxyFormat.Fetcher delegate;
    private final RepositoryDiscovery discovery;
    private final String base;
    private final String route;

    /** Over {@code delegate}, reading each request composed under {@code base} as a repository path beginning
     *  {@code route}: {@code /maven/} where the format composes below a Maven repository's root, {@code /} where it
     *  keeps the module service's route ({@link RepositoryDiscovery#route}). */
    DiscoveredFetcher(ProxyFormat.Fetcher delegate, RepositoryDiscovery discovery, URI base, String route) {
        this.delegate = delegate;
        this.discovery = discovery;
        this.base = base.toString();
        this.route = route;
    }

    @Override
    public ProxyFormat.Fetcher beside() {
        return new DiscoveredFetcher(delegate.beside(), discovery, URI.create(base), route);
    }

    @Override
    public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException {
        Optional<Located> located = locate(url);
        if (located.isEmpty()) {
            return Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
        }
        return switch (located.get()) {
            case Located.Answered answered -> Optional.of(new ProxyFormat.Fetched(200, answered.body(),
                    Map.of("Content-Type", answered.contentType())));
            case Located.Fetched fetched -> delegate.fetch(fetched.url(), requestHeaders);
            case Located.Relayed relayed -> delegate.fetch(relayed(relayed), requestHeaders);
        };
    }

    @Override
    public Optional<ProxyFormat.Head> head(URI url, Map<String, String> requestHeaders) throws IOException {
        Optional<Located> located = locate(url);
        if (located.isEmpty()) {
            return Optional.of(new ProxyFormat.Head(404, Map.of()));
        }
        return switch (located.get()) {
            case Located.Answered answered -> Optional.of(new ProxyFormat.Head(200,
                    Map.of("Content-Type", answered.contentType(),
                            "Content-Length", Integer.toString(answered.body().length))));
            case Located.Fetched fetched -> delegate.head(fetched.url(), requestHeaders);
            case Located.Relayed relayed -> delegate.head(relayed(relayed), requestHeaders);
        };
    }

    @Override
    public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
        Optional<Located> located = locate(url);
        if (located.isEmpty()) {
            return Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
        }
        return switch (located.get()) {
            case Located.Answered answered -> Optional.of(new ProxyFormat.Download(200,
                    new ByteArrayInputStream(answered.body()), Map.of("Content-Type", answered.contentType())));
            case Located.Relayed relayed -> delegate.download(relayed(relayed), requestHeaders);
            case Located.Fetched fetched -> fetched.checked() ? checked(fetched.url(), requestHeaders)
                    : delegate.download(fetched.url(), requestHeaders);
        };
    }

    // The download of a file checked against the strongest checksum beside it.
    private Optional<ProxyFormat.Download> checked(URI url, Map<String, String> requestHeaders) throws IOException {
        for (Map.Entry<String, String> checksum : CHECKSUMS) {
            Optional<ProxyFormat.Fetched> sidecar = delegate.beside().fetch(URI.create(url + checksum.getKey()),
                    Map.of());
            if (sidecar.isEmpty() || sidecar.get().status() != 200) {
                continue;
            }
            String expected = new String(sidecar.get().body(), StandardCharsets.US_ASCII).strip().split("\\s+")[0]
                    .toLowerCase(Locale.ROOT);
            Optional<ProxyFormat.Download> download = delegate.download(url, requestHeaders);
            if (download.isEmpty() || download.get().status() != 200) {
                return download;
            }
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance(checksum.getValue());
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException(unavailable);
            }
            return Optional.of(new ProxyFormat.Download(200, new Verified(download.get().body(), digest, expected,
                    url), download.get().headers()));
        }
        return delegate.download(url, requestHeaders);
    }

    private Optional<Located> locate(URI url) throws IOException {
        String composed = url.toString();
        if (!composed.startsWith(base)) {
            return Optional.empty();
        }
        String rest = composed.substring(base.length());
        try {
            return discovery.locate(route + rest);
        } catch (DiscoveryException refused) {
            throw new IOException(refused.getMessage(), refused);
        }
    }

    private static URI relayed(Located.Relayed relayed) {
        return RedirectHandlerProvider.compose(relayed.root(), relayed.path());
    }

    /** A body read through a digest that fails the read at its end where the digest is not the one expected. */
    private static final class Verified extends FilterInputStream {

        private final MessageDigest digest;
        private final String expected;
        private final URI url;
        private boolean ended;

        private Verified(InputStream body, MessageDigest digest, String expected, URI url) {
            super(body);
            this.digest = digest;
            this.expected = expected;
            this.url = url;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b < 0) {
                end();
            } else {
                digest.update((byte) b);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read < 0) {
                end();
            } else {
                digest.update(buffer, offset, read);
            }
            return read;
        }

        private void end() throws IOException {
            if (ended) {
                return;
            }
            ended = true;
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(expected)) {
                throw new IOException(url + " does not match the checksum beside it: " + expected + " named, "
                        + actual + " read");
            }
        }
    }
}
