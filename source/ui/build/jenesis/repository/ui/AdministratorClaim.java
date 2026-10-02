package build.jenesis.repository.ui;

import module java.base;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The first-run guide's claim on administration: a super-admin session on the starter key asks to sign in with a
 * provider and be made administrator. The guide marks the session ({@link #offer}); when the provider then signs that
 * session in, the identity it returns is granted administration ({@link #redeem}) before its rights are worked out, so
 * the sign-in already holds them. A claim is good once, for {@link #WINDOW}, and only in the session that made it - the
 * mark is a session attribute, which the browser's return from the provider carries and nothing else can.
 */
public final class AdministratorClaim {

    /** The session attribute holding the instant the claim lapses. */
    public static final String ATTRIBUTE = AdministratorClaim.class.getName();

    /** How long a claim stands: the time to approve the app on the provider's page and come back. */
    public static final Duration WINDOW = Duration.ofMinutes(10);

    private AdministratorClaim() {
    }

    /** Mark {@code session} as claiming administration for whoever signs it in next, until {@link #WINDOW} after
     *  {@code now}. Only a route a super-admin reaches may call this. */
    public static void offer(HttpSession session, Instant now) {
        session.setAttribute(ATTRIBUTE, now.plus(WINDOW));
    }

    /**
     * Grant {@code principal} administration if the session of the request being served holds a claim still inside its
     * window, answering whether it did. The claim is spent either way, so a lapsed or used one grants nothing later.
     */
    public static boolean redeem(ConsoleAdministrators administrators, String principal, Instant now) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return false;
        }
        HttpSession session = attributes.getRequest().getSession(false);
        if (session == null || !(session.getAttribute(ATTRIBUTE) instanceof Instant until)) {
            return false;
        }
        session.removeAttribute(ATTRIBUTE);
        if (now.isAfter(until)) {
            return false;
        }
        try {
            administrators.grant(principal);
        } catch (IOException failed) {
            throw new UncheckedIOException("Could not grant " + principal + " administration of this deployment",
                    failed);
        }
        return true;
    }
}
