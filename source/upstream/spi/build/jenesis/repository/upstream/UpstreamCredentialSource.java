package build.jenesis.repository.upstream;

import module java.base;

/**
 * Where the credentials the pull-through proxies send upstream come from, so a deployment can proxy a private registry
 * (an internal Nexus or Artifactory, an authenticated mirror). {@link #headers} is read on every proxied fetch, so it
 * serves from memory. Where credentials live - a store object, an external secret manager - is a discovered
 * {@link UpstreamCredentialSourceProvider}'s part; with none, {@link #NONE} attaches nothing and the management surface
 * says the feature is not installed.
 */
public interface UpstreamCredentialSource {

    /** The headers to send to {@code url}'s host - one {@code name -> value} entry, usually {@code Authorization} - or
     *  empty. */
    Map<String, String> headers(URI url);

    /** The hosts that carry a credential (never the credentials themselves), for the management surface. */
    SortedSet<String> hosts() throws IOException;

    /** Store the {@code name: value} header to send to one upstream host. */
    void set(String host, String name, String value) throws IOException;

    /** Clear the credential for one upstream host. */
    void remove(String host) throws IOException;

    /** Store {@code credential} for one host: a {@link UpstreamCredential.Header} as {@link #set} stores it, or an
     *  {@link UpstreamCredential.Issued} token, which a source that cannot mint refuses. */
    default void set(String host, UpstreamCredential credential) throws IOException {
        switch (credential) {
            case UpstreamCredential.Header header -> set(host, header.name(), header.value());
            case UpstreamCredential.Issued issued -> throw new IllegalStateException("This upstream-credential "
                    + "source cannot mint " + issued.issuer() + " tokens for '" + host + "'.");
        }
    }

    /** The source standing in when no module is installed: nothing is attached and a write is refused. A singleton, so
     *  "not installed" is told by identity. */
    UpstreamCredentialSource NONE = new UpstreamCredentialSource() {

        @Override
        public Map<String, String> headers(URI url) {
            return Map.of();
        }

        @Override
        public SortedSet<String> hosts() {
            return Collections.emptySortedSet();
        }

        @Override
        public void set(String host, String name, String value) {
            throw new IllegalStateException("No upstream-credential module is installed on this deployment.");
        }

        @Override
        public void remove(String host) {
            throw new IllegalStateException("No upstream-credential module is installed on this deployment.");
        }
    };
}
