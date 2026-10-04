package build.jenesis.repository.format;

import module java.base;
import build.jenesis.repository.store.StoredListing;

/**
 * Helpers for the listing documents a format serves as HTML.
 *
 * <p>A listing page carries names that came from a publisher, so they are escaped before they are written into it,
 * by one escape every listing page shares: a character handled in one copy and overlooked in another would be an
 * injection into whichever page was not updated.
 */
public final class Listings {

    private Listings() {
    }

    /**
     * Answer a request with a stored listing: its validator, a {@code 304} when the client already holds it, a
     * {@code HEAD} answered from the header alone, and otherwise the body streamed against its recorded length.
     *
     * <p>A format relying on the dispatcher to derive a validator from the bytes it was handed has one only while the
     * answer is buffered; streaming removes its revalidation silently, and a polling client would re-fetch a whole
     * index forever. A format that answers through this helper cannot be missing a piece of it.
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
     * sha256, because that is what changes exactly when the answer does.
     *
     * @param length the response's length, or {@code -1} when it is only known once the body has been written.
     */
    public static void serve(FormatExchange exchange, StoredListing.Served document, String contentType,
                             long length, Body body) throws IOException {
        serve(exchange, '"' + document.header().sha256() + '"', contentType, length, body);
    }

    /**
     * The same answer for a listing that stores a placeholder for the base its links are served under, completed with
     * {@code base} on the way out - a package document whose archive URLs name the host a client asked through. The
     * validator folds the base into the stored digest, since one document answers differently under two bases, and no
     * length is declared, since completing the placeholder changes it. A {@code null} base serves the document as
     * stored.
     */
    public static void serve(FormatExchange exchange, StoredListing.Served document, String contentType,
                             String placeholder, String base) throws IOException {
        if (base == null) {
            serve(exchange, document, contentType);
            return;
        }
        serve(exchange, '"' + document.header().sha256() + "-" + Integer.toHexString(base.hashCode()) + '"',
                contentType, -1L, out -> document.copyTo(out, placeholder, base));
    }

    private static void serve(FormatExchange exchange, String etag, String contentType, long length, Body body)
            throws IOException {
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

    /** The entries of an HTML page that lists one {@code <a>} per line, each keyed by the anchor's text, unescaped -
     *  the shape a Simple page and a folder page share. */
    public static final StoredListing.Codec ANCHORS = StoredListing.Codec.delimited("\n", link -> {
        int start = link.indexOf('>') + 1;
        int end = link.indexOf("</a>");
        return start > 0 && end > start ? unhtml(link.substring(start, end)) : "";
    });

    /**
     * The text {@code escaped} stands for: {@link #html}'s inverse, and the character references an HTML generator
     * emits besides - {@code &#39;}, {@code &apos;} and any numeric one. One pass, so an escaped ampersand stays the
     * literal text it encodes ({@code &amp;lt;} reads {@code &lt;}, not {@code <}); a reference this does not know
     * stays as written.
     */
    public static String unhtml(String escaped) {
        if (escaped.indexOf('&') < 0) {
            return escaped;
        }
        StringBuilder text = new StringBuilder(escaped.length());
        for (int at = 0; at < escaped.length(); at++) {
            char c = escaped.charAt(at);
            int end = c == '&' ? escaped.indexOf(';', at) : -1;
            String decoded = end > at ? reference(escaped.substring(at + 1, end)) : null;
            if (decoded == null) {
                text.append(c);
            } else {
                text.append(decoded);
                at = end;
            }
        }
        return text.toString();
    }

    private static String reference(String name) {
        return switch (name) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            default -> {
                if (name.length() < 2 || name.charAt(0) != '#') {
                    yield null;
                }
                boolean hex = name.charAt(1) == 'x' || name.charAt(1) == 'X';
                try {
                    int code = Integer.parseInt(name.substring(hex ? 2 : 1), hex ? 16 : 10);
                    yield Character.isValidCodePoint(code) ? Character.toString(code) : null;
                } catch (NumberFormatException notANumber) {
                    yield null;
                }
            }
        };
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
