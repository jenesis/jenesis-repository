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
 *
 * <p><b>Why the root is held by the process rather than carried by the store.</b> Its readers include a pass's
 * completion hook, which is handed no store at all, and the root is the node's - a process is one node, whose driver
 * installs it at start and {@linkplain #retireRoot retires} it at close, so a context closed in a process leaves
 * nothing behind for the next. Where two contexts share a process at once, what crosses between them is advice and
 * never a decision: a request lands on the other deployment's root and a walk it would not otherwise have run is run
 * there - which is a cost, and is why a driver retires only the root it installed.
 */
public final class Requests {

    /** The one subject this module knows: a walk of the store, every consumer riding. */
    public static final String WALK = "walk";

    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9_-]+");
    private static final AtomicReference<ArtifactStore> ROOT = new AtomicReference<>();

    /** One standing request: for what, why, since when, and - for a retry rather than a need - not before when. */
    public record Request(String subject, String reason, Instant at, Instant notBefore) {

        /** Whether the request may be acted on at {@code now}. */
        public boolean due(Instant now) {
            return !notBefore.isAfter(now);
        }

        byte[] encoded() {
            return (at + "\n" + notBefore + "\n" + reason).getBytes(StandardCharsets.UTF_8);
        }

        static Request decode(String subject, byte[] body) {
            String[] lines = new String(body, StandardCharsets.UTF_8).split("\n", 3);
            Instant at = instant(lines, 0, Instant.EPOCH);
            return new Request(subject, lines.length > 2 ? lines[2] : "", at, instant(lines, 1, at));
        }

        private static Instant instant(String[] lines, int index, Instant fallback) {
            try {
                return lines.length > index ? Instant.parse(lines[index].trim()) : fallback;
            } catch (DateTimeParseException _) {
                return fallback;
            }
        }
    }

    private Requests() {
    }

    /** Ask for {@code subject}'s work on {@code root}, for {@code reason}; a standing request is overwritten. */
    public static void request(ArtifactStore root, String subject, String reason) throws IOException {
        request(root, subject, reason, Instant.now());
    }

    /** As {@link #request(ArtifactStore, String, String)}, to be acted on no earlier than {@code notBefore} - the
     *  shape of a retry: a pass that failed asks for itself again, an hour on, rather than at once and forever. */
    public static void request(ArtifactStore root, String subject, String reason, Instant notBefore)
            throws IOException {
        Instant now = Instant.now();
        root.write(key(subject), new ByteArrayInputStream(
                new Request(subject, reason == null ? "" : reason, now, notBefore == null ? now : notBefore)
                        .encoded()));
    }

    /** The standing request for {@code subject}, if any. A cleared one is an empty body ({@link #clear(ArtifactStore,
     *  Request)}), which stands for none. */
    public static Optional<Request> pending(ArtifactStore root, String subject) throws IOException {
        return root.readVersioned(key(subject)).filter(versioned -> versioned.content().length > 0)
                .map(versioned -> Request.decode(subject, versioned.content()));
    }

    /** The most standing requests one read answers: a request is one object per subject and a subject is a task or
     *  a walk, so the space holds a few dozen at most - the page is a bound on a misuse, never a limit anyone
     *  reaches. */
    private static final int PAGE = 1_000;

    /** Every standing request - one page of one small space, bounded by the subjects there are. A page rather than a
     *  whole-space listing, because the walks screen and its API read this on a request. */
    public static List<Request> pending(ArtifactStore root) throws IOException {
        List<String> subjects = new ArrayList<>();
        root.page(Scopes.space(Scopes.REQUESTS), "", PAGE, subjects::add);
        List<Request> requests = new ArrayList<>();
        for (String subject : subjects) {
            pending(root, subject).ifPresent(requests::add);
        }
        return requests;
    }

    /** The work was done: drop the standing request for {@code subject}, whatever it is. */
    public static void clear(ArtifactStore root, String subject) throws IOException {
        root.delete(key(subject));
    }

    /**
     * The work {@code acted} asked for was done: drop it, and only it. A request made while the work ran replaced it
     * and asks for the work again, so it stands. The store has no versioned delete, so the clear is a compare-and-set
     * of an empty body against the token the acted-on request still holds - a replacement moves the token and the
     * clear does not land.
     */
    public static void clear(ArtifactStore root, Request acted) throws IOException {
        String key = key(acted.subject());
        Optional<ArtifactStore.Versioned> current = root.readVersioned(key);
        if (current.isEmpty() || current.get().content().length == 0) {
            return;
        }
        if (!Arrays.equals(current.get().content(), acted.encoded())) {
            return;   // replaced while the work ran
        }
        var _ = root.writeVersioned(key, new byte[0], current.get().token());
    }

    /** Install the deployment's root store for {@link #requestOnRoot}; {@code null} uninstalls it. The node's
     *  driver installs it at boot - once per process, since a process is one node. */
    public static void installRoot(ArtifactStore root) {
        ROOT.set(root);
    }

    /** Retire {@code root} as the installed root, if it still is: a driver closing retires what it installed and
     *  nothing a later driver installed after it. */
    public static void retireRoot(ArtifactStore root) {
        ROOT.compareAndSet(root, null);
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
