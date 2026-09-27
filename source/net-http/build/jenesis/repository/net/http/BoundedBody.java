package build.jenesis.repository.net.http;

import module java.base;
import module java.net.http;

/**
 * The body handlers an outbound call reads a whole response with, each bounded: past {@code limit} bytes the read is
 * abandoned and the call fails with {@link TooLarge}, naming the address and the bound, instead of growing the heap
 * for as long as the peer keeps sending.
 *
 * <p>These stand in for {@code BodyHandlers.ofByteArray()} and {@code BodyHandlers.ofString()}, which buffer whatever
 * arrives. A document this product fetches whole - a key, an attestation, a trusted root, an identity provider's
 * discovery document - has a size its caller can state, and a peer answering past it is broken or hostile either
 * way, so every such read names its bound where it is made. A body declaring a {@code Content-Length} past the bound
 * is refused before any of it is read. A response too large to hold is not read whole at all: it streams through
 * {@code ofInputStream()} to wherever it is going.
 *
 * <p>The text form decodes as UTF-8, the charset of every document these calls fetch; a peer naming another in its
 * {@code Content-Type} is not believed, so what is decoded does not depend on the peer's say-so.
 */
public final class BoundedBody {

    private BoundedBody() {
    }

    /** The whole body as bytes, refused past {@code limit}; {@code uri} is what a refusal names. */
    public static HttpResponse.BodyHandler<byte[]> ofByteArray(URI uri, long limit) {
        Objects.requireNonNull(uri, "uri");
        if (limit <= 0 || limit > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("a body bound is positive and fits one array: " + limit);
        }
        return info -> {
            long declared = info.headers().firstValueAsLong("Content-Length").orElse(-1);
            return new Collector(uri, limit, declared > limit ? declared : -1);
        };
    }

    /** The whole body as UTF-8 text, refused past {@code limit} bytes; {@code uri} is what a refusal names. */
    public static HttpResponse.BodyHandler<String> ofString(URI uri, long limit) {
        HttpResponse.BodyHandler<byte[]> bytes = ofByteArray(uri, limit);
        return info -> HttpResponse.BodySubscribers.mapping(bytes.apply(info),
                body -> new String(body, StandardCharsets.UTF_8));
    }

    /** A response body refused for running past the bound its caller named. */
    public static final class TooLarge extends IOException {

        private final long limit;

        TooLarge(URI uri, long limit, long declared) {
            super("the answer from " + uri + (declared > 0 ? " declares " + declared + " bytes, past" : " runs past")
                    + " the " + limit + "-byte bound on what this call reads, so it was abandoned");
            this.limit = limit;
        }

        /** The bound the body ran past. */
        public long limit() {
            return limit;
        }
    }

    /** Gathers the body's buffers until it ends, or refuses it at the first byte past the bound. */
    private static final class Collector implements HttpResponse.BodySubscriber<byte[]> {

        private final URI uri;
        private final long limit;
        private final long declared;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final List<byte[]> chunks = new ArrayList<>();
        private long received;
        private Flow.Subscription subscription;

        Collector(URI uri, long limit, long declared) {
            this.uri = uri;
            this.limit = limit;
            this.declared = declared;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (declared > 0) {
                subscription.cancel();
                body.completeExceptionally(new TooLarge(uri, limit, declared));
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                received += buffer.remaining();
                if (received > limit) {
                    chunks.clear();
                    subscription.cancel();
                    body.completeExceptionally(new TooLarge(uri, limit, -1));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                chunks.add(chunk);
            }
        }

        @Override
        public void onError(Throwable failure) {
            chunks.clear();
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            if (body.isDone()) {
                return;
            }
            byte[] whole = new byte[(int) received];
            int at = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, whole, at, chunk.length);
                at += chunk.length;
            }
            chunks.clear();
            body.complete(whole);
        }
    }
}
