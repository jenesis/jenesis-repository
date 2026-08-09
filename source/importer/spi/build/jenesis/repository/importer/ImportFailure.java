package build.jenesis.repository.importer;

import module java.base;

/**
 * Why a migration walk stopped, classified rather than collapsed.
 *
 * <p>Every connector used to answer the same {@link IOException} whichever way an incumbent refused it, so the only
 * difference between "your token expired", "that repository does not exist" and "the instance is restarting" was the
 * prose in the message - and a caller (an import job deciding whether to retry, a console deciding what to tell the
 * operator, an edition wiring a backoff) had nothing to key on but string matching. This exception carries the one
 * fact those callers need:
 *
 * <ul>
 *   <li>{@link Kind#AUTH} - the incumbent refused the credential the request carried (or the absence of one). Retrying
 *       is pointless until the operator changes the credential.</li>
 *   <li>{@link Kind#MISSING} - the incumbent answered, but the repository or asset named is not there. Retrying is
 *       pointless until the operator changes the request.</li>
 *   <li>{@link Kind#TRANSIENT} - the incumbent could not answer <em>now</em>: a transport failure, a throttle, an
 *       overload, a gateway. The same request may well succeed later, so this is the only kind a retry helps.</li>
 *   <li>{@link Kind#PROTOCOL} - the incumbent answered something this connector cannot walk: no listing and no index,
 *       a folder tree past its depth cap, a download aimed off-origin at a private host. The migration needs a
 *       different source or a different setting, not a retry.</li>
 * </ul>
 *
 * <p>{@link #classify(int)} is the single mapping from an HTTP status to a kind, so five connectors cannot arrive at
 * five different ideas of what a {@code 429} means. A connector that surfaces a status through {@link #status} inherits
 * it; a connector with a non-HTTP failure names its kind directly.
 *
 * <p>The messages are unchanged from the plain {@code IOException}s this replaces - a kind is added, nothing is taken
 * away - so an operator still reads which URL failed and with what status.
 */
public final class ImportFailure extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** What kind of failure stopped the walk. */
    public enum Kind {
        /** The credential was refused, or one was required and none was sent. */
        AUTH,
        /** The incumbent answered, but what was named is not there. */
        MISSING,
        /** The incumbent could not answer now; the same request may succeed later. */
        TRANSIENT,
        /** The incumbent answered something this connector cannot walk. */
        PROTOCOL
    }

    private final Kind kind;

    public ImportFailure(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public ImportFailure(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /** How this failure is classified - never {@code null}. */
    public Kind kind() {
        return kind;
    }

    /**
     * The kind an HTTP status means for a migration walk, stated once for every connector.
     *
     * <p>{@code 401}/{@code 403}/{@code 407} are the credential; {@code 404}/{@code 410} are absence; {@code 408},
     * {@code 425}, {@code 429} and every {@code 5xx} are "not now"; anything else is a protocol answer this connector
     * did not expect. A {@code 2xx} is not a failure at all and is deliberately classified {@link Kind#PROTOCOL}: a
     * connector that hands a success status to this method has already decided the response was unusable.
     */
    public static Kind classify(int status) {
        return switch (status) {
            case 401, 403, 407 -> Kind.AUTH;
            case 404, 410 -> Kind.MISSING;
            case 408, 425, 429 -> Kind.TRANSIENT;
            default -> status >= 500 ? Kind.TRANSIENT : Kind.PROTOCOL;
        };
    }

    /** A failure carrying an upstream status: {@code "<what> failed (<status>) for <url>"}, classified by
     *  {@link #classify(int)}. {@code what} names the leg ("Nexus listing", "Download"), so the message reads the way
     *  it always did. */
    public static ImportFailure status(int status, URI url, String what) {
        return new ImportFailure(classify(status), what + " failed (" + status + ") for " + url);
    }

    /** A transport failure - the fetcher answered nothing at all, so nothing about the incumbent is known. Transient
     *  by definition: an unresolvable host, a refused connection and a dropped socket are all "not now". */
    public static ImportFailure unreachable(URI url) {
        return new ImportFailure(Kind.TRANSIENT, "No response from " + url);
    }

    /** A failure in what the incumbent answered rather than in whether it answered - no listing and no index, a tree
     *  past its depth cap, a download aimed somewhere this connector refuses to follow. */
    public static ImportFailure protocol(String message) {
        return new ImportFailure(Kind.PROTOCOL, message);
    }
}
