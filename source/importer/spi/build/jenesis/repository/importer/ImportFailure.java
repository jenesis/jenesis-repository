package build.jenesis.repository.importer;

import module java.base;

/**
 * Why a migration walk stopped, classified rather than collapsed, so an import job deciding whether to retry or a
 * console deciding what to say keys on a kind rather than the message:
 * <ul>
 *   <li>{@link Kind#AUTH} - the incumbent refused the credential, or its absence; retrying is pointless until it
 *       changes.</li>
 *   <li>{@link Kind#MISSING} - the incumbent answered, but the repository or asset is not there; retrying is pointless
 *       until the request changes.</li>
 *   <li>{@link Kind#TRANSIENT} - the incumbent could not answer now: transport, throttle, overload, gateway. The one
 *       kind a retry helps.</li>
 *   <li>{@link Kind#PROTOCOL} - it answered something this connector cannot walk: no listing or index, a tree past its
 *       depth cap, a download aimed off-origin at a private host. A different source or setting is needed.</li>
 * </ul>
 * {@link #classify(int)} is the one mapping from an HTTP status to a kind, so connectors agree on what a {@code 429}
 * means. The message still names the failing URL and status.
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

    /** The kind an HTTP status means for a migration walk: {@code 401}/{@code 403}/{@code 407} the credential,
     *  {@code 404}/{@code 410} absence, {@code 408}, {@code 425}, {@code 429} and every {@code 5xx} "not now", anything
     *  else an unexpected protocol answer. A {@code 2xx} is {@link Kind#PROTOCOL}: a connector passing a success here
     *  has already decided the response was unusable. */
    public static Kind classify(int status) {
        return switch (status) {
            case 401, 403, 407 -> Kind.AUTH;
            case 404, 410 -> Kind.MISSING;
            case 408, 425, 429 -> Kind.TRANSIENT;
            default -> status >= 500 ? Kind.TRANSIENT : Kind.PROTOCOL;
        };
    }

    /** A failure carrying an upstream status, {@code "<what> failed (<status>) for <url>"}, classified by
     *  {@link #classify(int)}; {@code what} names the leg ("Nexus listing", "Download"). */
    public static ImportFailure status(int status, URI url, String what) {
        return new ImportFailure(classify(status), what + " failed (" + status + ") for " + url);
    }

    /** A transport failure: the fetcher answered nothing, so it is transient - an unresolvable host, a refused
     *  connection and a dropped socket are all "not now". */
    public static ImportFailure unreachable(URI url) {
        return new ImportFailure(Kind.TRANSIENT, "No response from " + url);
    }

    /** A failure in what the incumbent answered rather than whether: no listing or index, a tree past its depth cap, a
     *  download this connector refuses to follow. */
    public static ImportFailure protocol(String message) {
        return new ImportFailure(Kind.PROTOCOL, message);
    }
}
