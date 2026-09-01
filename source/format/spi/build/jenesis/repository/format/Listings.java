package build.jenesis.repository.format;

import module java.base;
import build.jenesis.repository.store.StoredListing;

/**
 * Helpers for the listing documents a format serves as HTML.
 *
 * <p>A listing page carries names that came from a publisher, so they are escaped before they are written into it.
 * The PyPI Simple index and the raw directory listing each escaped with their own copy of the same switch: a
 * character handled in one and overlooked in the other is an injection into whichever page was not updated.
 */
public final class Listings {

    private Listings() {
    }

    /**
     * Answer a request with a stored listing: its validator, a {@code 304} when the client already holds it, a
     * {@code HEAD} answered from the header alone, and otherwise the body streamed against its recorded length.
     *
     * <p>This was the same twelve lines in sixteen formats, and writing it once matters beyond tidiness. When the
     * listings stopped being materialised, two of those copies turned out to have no validator of their own: they
     * had been relying on the dispatcher to derive one from the bytes it was handed, which works only while the
     * answer is buffered. Streaming removed their revalidation silently, and a polling client would have
     * re-fetched a whole index forever. A format that answers through this helper cannot be missing a piece of it.
     *
     * <p>Nothing here materialises the document. The validator is the sha256 the listing's header already records
     * and the length is the size it records, so a listing the size of the repository costs a header and a copy
     * between two streams.
     *
     * @param contentType the media type to declare, or {@code null} when the caller has already set it.
     */
    public static void serve(FormatExchange exchange, StoredListing.Served document, String contentType)
            throws IOException {
        serve(exchange, document, contentType, document.header().size(), out -> document.body().transferTo(out));
    }

    /** Writes a response body that is rendered from a stored document rather than copied out of it. */
    @FunctionalInterface
    public interface Body {

        void writeTo(OutputStream out) throws IOException;
    }

    /**
     * The same revalidation, over a body the format renders from the document instead of streaming it verbatim.
     *
     * <p>An OCI {@code tags/list} is the case: the stored document is the names, and the answer wraps them in a
     * document of its own, so there are no stored bytes to copy - but the validator is still the stored document's
     * sha256, because that is what changes exactly when the answer does. Going through here is what stops such a
     * format from silently losing revalidation the moment it stops buffering, which is the defect recorded above
     * and has now happened three times.
     *
     * @param length the response's length, or {@code -1} when it is only known once the body has been written.
     */
    public static void serve(FormatExchange exchange, StoredListing.Served document, String contentType,
                             long length, Body body) throws IOException {
        String etag = '"' + document.header().sha256() + '"';
        exchange.setResponseHeader("ETag", etag);
        if (etag.equals(exchange.requestHeader("If-None-Match"))) {
            exchange.respond(304);
            return;
        }
        if (contentType != null) {
            exchange.setResponseHeader("Content-Type", contentType);
        }
        if (exchange.method().equals("HEAD")) {
            if (length >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(length));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        try (OutputStream out = exchange.respond(200, length)) {
            body.writeTo(out);
        }
    }

    public static String html(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
