package build.jenesis.repository.format;

import module java.base;

/**
 * A framework-neutral view of one HTTP request and its response, so a {@link RepositoryFormat} can speak its
 * protocol (read the method, path, query and headers; stream the body; set the status and response headers)
 * without binding to the JDK HTTP server of the skeleton or the servlet stack of the production build. Each
 * dispatcher adapts its own exchange to this interface. The {@code contentLength} of {@link #respond} follows the
 * JDK convention: a positive value is the exact body length, {@code 0} streams an unknown length (chunked), and a
 * negative value sends no body.
 */
public interface FormatExchange {

    String method();

    /** The request path, with any repository prefix already stripped (so a format sees {@code /maven/...}, {@code /v2/...}). */
    String path();

    /**
     * The path the client asked for. It is {@link #path()}, except where a pull-through keeps the upstream's answer
     * under another path than the one requested ({@code ProxyFormat.keptAs}) - a branch resolved to its commit - and
     * hands the leg an exchange whose {@link #path()} is the kept one; this is then still what the client sent, so the
     * leg fetches upstream what was asked for.
     */
    default String requestedPath() {
        return path();
    }

    /**
     * The full external request path, including any repository prefix the dispatcher stripped from {@link #path()}.
     * A format builds absolute self-referential URLs (an npm tarball, say) from this so they keep the {@code /<repo>/}
     * segment under multi-tenant routing; on the single-repository headless server it is the same as {@link #path()}.
     */
    default String requestUri() {
        return path();
    }

    /**
     * The request path a client reaches a format-facing path at - what a format writes into a document that points
     * back at itself, a download URL or an index base. It is {@link #requestUri()} with this request's own format
     * path swapped for {@code formatPath}, so it keeps whatever the routing put in front of the path a format sees; an
     * exchange whose routing also restored a mount a client never sends ({@code /cargo} in front of a repository's
     * own paths) takes that mount off again, since the client never sent it.
     *
     * @param formatPath a format-facing path, as {@link #path()} carries one.
     */
    default String external(String formatPath) {
        String uri = requestUri();
        String path = path();
        String prefix = uri.length() >= path.length() && uri.endsWith(path)
                ? uri.substring(0, uri.length() - path.length()) : "";
        return prefix + formatPath;
    }

    /**
     * The scheme the request arrived on - {@code https} when the server terminated TLS for it, {@code http}
     * otherwise - so a format that writes absolute self-referential URLs into a generated index (a packument, a
     * service index, a sparse-index config) tells the client to come back the way it came, and a credential the
     * client attaches to that URL never travels in cleartext from a deployment that serves TLS. A deployment behind a
     * TLS-terminating proxy sees {@code http} here unless the container is told to honour the proxy's forwarded
     * headers; the formats consult {@code X-Forwarded-Proto} first for that case. The {@code default} is
     * {@code http}, for the exchanges that have no connection at all (a headless embed, an internal push, a test
     * double).
     */
    default String scheme() {
        return "http";
    }

    /**
     * The address the request came from - the TCP peer, which is the reverse proxy in a proxied deployment - or
     * {@code null} for an exchange that has no connection. A format that trusts a forwarded header only when the
     * peer is one of the deployment's trusted proxies asks this; the header itself is not evidence of anything.
     */
    default String remoteAddress() {
        return null;
    }

    String queryParameter(String name);

    String requestHeader(String name);

    /**
     * A named server-configuration value the format reads to honour a runtime toggle, or {@code null} when unset -
     * the seam through which a format consults a deployment setting without binding to any settings layer. The key is
     * the bare setting name (e.g. {@code maven-metadata-compute}); the dispatcher that built the exchange resolves it
     * from the deployment's effective configuration. The {@code default} returns {@code null}, so a format sees the
     * shipped default on any exchange that carries no configuration (a headless embed, an internal push exchange, a
     * test double); the servlet dispatcher overrides it to answer from the Spring environment, into which an
     * operator's stored setting is layered.
     */
    default String setting(String key) {
        return null;
    }

    InputStream requestStream() throws IOException;

    void setResponseHeader(String name, String value);

    OutputStream respond(int status, long contentLength) throws IOException;

    /**
     * Where the body a {@code respond(200, contentLength)} will carry starts: {@code 0}, or - for a request asking for
     * a range of it - the first byte of that range, so a serve that opens its content can open it there
     * ({@code ArtifactStore.open(key, offset)}) and write only from it, rather than read the whole artifact for the
     * response to throw the prefix away. A serve that asks writes its content from this offset; one that never asks
     * writes it from the start, as before. Exchanges that slice no range answer {@code 0}.
     */
    default long from(long contentLength) {
        return 0L;
    }

    default void respond(int status, byte[] content) throws IOException {
        try (OutputStream out = respond(status, content.length == 0 ? -1 : content.length)) {
            if (content.length > 0) {
                out.write(content);
            }
        }
    }

    default void respond(int status) throws IOException {
        respond(status, -1L).close();
    }

    /** A {@code 200} carrying {@code body}; for a {@code HEAD}, its length and no body, which is what a client probing
     *  a document before fetching it reads. */
    default void answer(byte[] body) throws IOException {
        if (method().equals("HEAD")) {
            setResponseHeader("Content-Length", Integer.toString(body.length));
            respond(200, -1L).close();
        } else {
            respond(200, body);
        }
    }

    /**
     * Name the artifact a publish laid out, when the request's own path does not - a push to an endpoint such as
     * RubyGems' {@code api/v1/gems}, whose coordinate is only known once the stored bytes are parsed. The ingress edge
     * that screened the body notifies the after-commit observers with this rather than with the endpoint, so what
     * they record - an inventory row, a forward, an event - names the artifact that serves. A format that commits
     * its own publish refines it through {@link build.jenesis.repository.store.Publication.Visibility#describing}
     * instead; an exchange no edge wraps ignores it.
     */
    default void laidOut(build.jenesis.repository.store.ArtifactDescriptor artifact) {
    }

    /**
     * Record a privileged change this request made through a format's own protocol - a client's own yank or
     * deprecate - on the audit trail, as the caller that sent it, under an action name the audit trail owns. The edge
     * that dispatched the request knows who is acting and which tenant it acts in; a format knows only what it
     * changed. An exchange no edge wraps records nothing.
     */
    default void audit(String action, String target) {
    }

    /**
     * The store of another repository of this request's tenant, for a read this request makes there on the caller's
     * behalf - a registry's cross-repository blob mount - when the caller may read {@code path} and that repository
     * holds the format this request is served by. {@code path} is the full request path a client would send to read
     * it there, repository prefix included, so it is decided exactly as that read would be.
     *
     * <p>Empty when the caller may not read it, when the path names no repository of this tenant or one of another
     * format, and on an exchange no edge wraps - one answer for all of them, so asking discloses nothing about what
     * the caller may not read.
     */
    default Optional<build.jenesis.repository.store.ArtifactStore> readable(String path) {
        return Optional.empty();
    }

    /**
     * Whether the caller may read content this repository holds for review - the bytes a hold withholds from every
     * other reader, which a content scanner must read to produce the report the hold waits on. A format that serves
     * by content hash past a withhold marker serves the held bytes to such a caller and to no one else.
     *
     * <p>{@code false} on an exchange no edge wraps and for every caller whose credential does not carry the right, so
     * a format that asks discloses nothing it would not otherwise serve.
     */
    default boolean readsHeld() {
        return false;
    }
}
