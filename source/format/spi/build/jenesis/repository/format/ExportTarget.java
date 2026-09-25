package build.jenesis.repository.format;

import module java.base;

/**
 * Where an export publishes to: the repository a format's own client would be pointed at - another deployment of this
 * product, or any repository manager reachable by URL and credential - reduced to the one thing an exporter needs,
 * sending a request to it.
 *
 * <p>A request's {@link Request#path path} is relative to the URL the client is configured with - {@code
 * .../repository/<tenant>/<repository>/maven/} for Maven, the registry for npm, the service index's directory for
 * NuGet - never absolute, so an exporter composes no host and cannot address anything but the target. The target
 * attaches the credential it was given to every request unless the exporter sets the header the format's client
 * sends it in itself ({@link #credential()} answers what that is), follows no redirect, and refuses a private or
 * plaintext address unless the deployment allows one.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> One export job drives one target from one thread; an implementation need not be
 *     thread-safe.</li>
 * <li><b>Absence sentinel.</b> {@link #credential()} is empty for an anonymous target, never {@code null}.</li>
 * <li><b>Streaming (&sect;1).</b> A request body is opened when it is sent and streamed; an implementation never
 *     buffers it whole. A response body is read up to a small cap - enough for the message a registry answers a
 *     refusal with, never an artifact - and {@link #sha256} hashes what it reads without holding it.</li>
 * <li><b>Error visibility (&sect;9).</b> {@link #send} throws for a request that could not be made (no route, a
 *     refused address, a timeout); a request the target answered - whatever its status - is a {@link Response}, and
 *     judging it is the exporter's.</li>
 * <li><b>Traversal refusal.</b> A path that would leave the configured URL - a {@code ..} segment, an absolute URL, a
 *     scheme - is refused with an {@link IllegalArgumentException} before anything is sent.</li>
 * </ol>
 */
public interface ExportTarget {

    /** Send one request and answer what the target said. */
    Response send(Request request) throws IOException;

    /** The SHA-256 of what the target serves at {@code path}, streamed rather than held, or empty when it serves
     *  nothing there - how an export tells a file already present from a conflicting one. */
    Optional<String> sha256(String path) throws IOException;

    /** The credential this target was given, for a format whose client sends it in a header of its own. */
    Optional<Credential> credential();

    /**
     * One request: the method, the path relative to the target's URL (with its query, when it has one), headers of
     * the exporter's own, and a body, or {@link Body#NONE}.
     */
    record Request(String method, String path, Map<String, String> headers, Body body) {

        public Request {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(path, "path");
            headers = Map.copyOf(headers);
            Objects.requireNonNull(body, "body");
        }

        public static Request get(String path) {
            return new Request("GET", path, Map.of(), Body.NONE);
        }

        public static Request head(String path) {
            return new Request("HEAD", path, Map.of(), Body.NONE);
        }

        public static Request put(String path, String contentType, Body body) {
            return new Request("PUT", path, Map.of("Content-Type", contentType), body);
        }

        public static Request post(String path, String contentType, Body body) {
            return new Request("POST", path, Map.of("Content-Type", contentType), body);
        }
    }

    /** A request body: its length when known ({@code -1} when not) and how to open it, which may happen once per send. */
    interface Body {

        Body NONE = of(new byte[0]);

        long length();

        InputStream open() throws IOException;

        static Body of(byte[] content) {
            byte[] copy = content.clone();
            return new Body() {
                @Override
                public long length() {
                    return copy.length;
                }

                @Override
                public InputStream open() {
                    return new ByteArrayInputStream(copy);
                }
            };
        }
    }

    /** What the target answered: its status and, up to a small cap, its body. */
    record Response(int status, String body) {

        /** A {@code 2xx}. */
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * The credential a target was given: a user name and a secret for {@code Basic}, or a secret alone for a token -
     * which the target sends as {@code Bearer} unless an exporter places it itself.
     */
    record Credential(Optional<String> username, String secret) {

        public Credential {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(secret, "secret");
        }
    }
}
