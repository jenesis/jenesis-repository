package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ForwardingExchange;
import build.jenesis.repository.store.Publication;

/**
 * A {@link FormatExchange} that wraps a real request/response exchange but restreams its request body from an
 * already-stored source instead of the socket, so an ingress edge can hand an accepted blob back to the claiming
 * {@link build.jenesis.repository.format.RepositoryFormat} for pure layout. Everything except the body - the method,
 * path, query, headers, settings, the audit trail, the reads made on the caller's behalf, and the whole response
 * side (status, headers, streamed body, range/conditional handling) - delegates to the wrapped exchange, so the
 * format writes its response straight to the original client exactly as it would on a direct dispatch; only
 * {@link #requestStream()} is redirected to the stored blob.
 *
 * <p>This is the core, edition-neutral restream exchange the edge screening choreography builds on: after
 * {@link build.jenesis.repository.store.Publication#screen} accepts a body, the edge hands the format a
 * {@link Publication.Stored} stream over the acceptance. The body is a restream, never a buffered copy - each
 * {@link #requestStream()} is a fresh stream that opens {@code blobs/<hash>} lazily on the first read - so a large
 * artifact goes from storage to the format's layout write without being materialised in memory, and a layout that
 * only stores what it was given stores nothing: {@link Publication#storeBlob} recognises the stream and answers the
 * hash - so a screened publish writes its blob once, not twice with a read in between.
 */
public final class RestreamExchange extends ForwardingExchange {

    private final Publication.Acceptance accepted;

    public RestreamExchange(FormatExchange delegate, Publication.Acceptance accepted) {
        super(delegate);
        this.accepted = accepted;
    }

    @Override
    public InputStream requestStream() throws IOException {
        return new Publication.Stored(accepted);
    }
}
