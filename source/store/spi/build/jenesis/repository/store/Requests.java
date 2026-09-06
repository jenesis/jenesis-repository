package build.jenesis.repository.store;

import module java.base;

import build.jenesis.repository.scope.Scopes;

/**
 * Standing requests for work, by subject, under {@code .system/requests/<subject>} - the reason a pass runs when
 * no clock says so. Whatever notices the need writes one: a node that booted over its own running marker (an unclean
 * shutdown), a publication whose after-commit observer failed and was contained, an operator on any surface. The
 * worker that does the work clears it when the work is done. A request is one small object, overwritten rather than
 * appended, so a thousand notices of the same need cost one write each and leave one request; and it names its
 * reason and its moment, because an operator asked "why did a walk run at 03:12" deserves the answer.
 *
 * <p>A request lives at the deployment's root, where the workers look. Code that holds only a repository-scoped
 * store - a {@link Publication} - reaches the root through the one {@linkplain #installRoot installed} at boot by
 * the node's driver, and requests nothing when none is installed (a unit test, an embedder without a driver): a
 * request is never a reason for the caller's own work to fail.
 */
public final class Requests {

    /** The one subject the free core knows: a walk of the store, every consumer riding. */
    public static final String WALK = "walk";

    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9_-]+");
    private static final AtomicReference<ArtifactStore> ROOT = new AtomicReference<>();

    /** One standing request: for what, why, and since when. */
    public record Request(String subject, String reason, Instant at) {

        byte[] encoded() {
            return (at + "\n" + reason).getBytes(StandardCharsets.UTF_8);
        }

        static Request decode(String subject, byte[] body) {
            String[] lines = new String(body, StandardCharsets.UTF_8).split("\n", 2);
            Instant at;
            try {
                at = Instant.parse(lines[0].trim());
            } catch (DateTimeParseException _) {
                at = Instant.EPOCH;
            }
            return new Request(subject, lines.length > 1 ? lines[1] : "", at);
        }
    }

    private Requests() {
    }

    /** Ask for {@code subject}'s work on {@code root}, for {@code reason}; a standing request is overwritten. */
    public static void request(ArtifactStore root, String subject, String reason) throws IOException {
        root.write(key(subject), new ByteArrayInputStream(
                new Request(subject, reason == null ? "" : reason, Instant.now()).encoded()));
    }

    /** The standing request for {@code subject}, if any. */
    public static Optional<Request> pending(ArtifactStore root, String subject) throws IOException {
        return root.readVersioned(key(subject)).map(versioned -> Request.decode(subject, versioned.content()));
    }

    /** Every standing request - a listing of one small space, bounded by the subjects there are. */
    public static List<Request> pending(ArtifactStore root) throws IOException {
        List<Request> requests = new ArrayList<>();
        for (String subject : root.list(Scopes.space(Scopes.REQUESTS))) {
            pending(root, subject).ifPresent(requests::add);
        }
        return requests;
    }

    /** The work was done: drop the standing request for {@code subject}. */
    public static void clear(ArtifactStore root, String subject) throws IOException {
        root.delete(key(subject));
    }

    /** Install the deployment's root store for {@link #requestOnRoot}; {@code null} uninstalls it. The node's
     *  driver installs it at boot - once per process, since a process is one node. */
    public static void installRoot(ArtifactStore root) {
        ROOT.set(root);
    }

    /** The installed root, if a driver installed one. */
    public static Optional<ArtifactStore> root() {
        return Optional.ofNullable(ROOT.get());
    }

    /** {@link #request} on the installed root, from code that holds no root of its own; {@code false} when no root
     *  is installed or the write failed - either way the caller's own work goes on, a request being advice. */
    public static boolean requestOnRoot(String subject, String reason) {
        ArtifactStore root = ROOT.get();
        if (root == null) {
            return false;
        }
        try {
            request(root, subject, reason);
            return true;
        } catch (IOException | RuntimeException unwritable) {
            return false;
        }
    }

    private static String key(String subject) {
        if (subject == null || !SUBJECT.matcher(subject).matches()) {
            throw new IllegalArgumentException("not a request subject: " + subject);
        }
        return Scopes.space(Scopes.REQUESTS) + "/" + subject;
    }
}
