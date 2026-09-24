package build.jenesis.repository.upstream;

import module java.base;

/**
 * Where the credentials the pull-through proxies send upstream come from, so a deployment can proxy a PRIVATE
 * registry (an internal Nexus/Artifactory, an authenticated mirror), not only public ones. {@link #headers} is
 * read on every proxied fetch, so an implementation must serve from memory, never the wire. Where the credentials
 * live - a store object, an external secret manager - is the implementation's part, supplied by an
 * {@link UpstreamCredentialSourceProvider} module discovered with {@link ServiceLoader}; with none installed
 * {@link #NONE} stands in: no credential is ever attached and the management surface says the feature is not
 * installed.
 */
public interface UpstreamCredentialSource {

    /** The request headers to send to {@code url}'s host - a single {@code name -> value} entry (usually
     *  {@code Authorization}, but any header for an API-key upstream), or empty when none is configured. */
    Map<String, String> headers(URI url);

    /** The hosts that carry a credential (never the credentials themselves), for the management surface. */
    SortedSet<String> hosts() throws IOException;

    /** Store the {@code name: value} header to send to one upstream host. */
    void set(String host, String name, String value) throws IOException;

    /** Clear the credential for one upstream host. */
    void remove(String host) throws IOException;

    /** Store {@code credential} for one upstream host: a {@link UpstreamCredential.Header} as {@link #set} stores
     *  it, or a {@link UpstreamCredential.Issued} token, which a source that cannot mint refuses. */
    default void set(String host, UpstreamCredential credential) throws IOException {
        switch (credential) {
            case UpstreamCredential.Header header -> set(host, header.name(), header.value());
            case UpstreamCredential.Issued issued -> throw new IllegalStateException("This upstream-credential "
                    + "source cannot mint " + issued.issuer() + " tokens for '" + host + "'.");
        }
    }

    /** The shared source standing in when no module is installed: no credential is ever attached, and a write is
     *  refused. A singleton, so a composition can tell "not installed" by identity. */
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
