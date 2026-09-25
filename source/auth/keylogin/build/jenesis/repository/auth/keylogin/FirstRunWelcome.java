package build.jenesis.repository.auth.keylogin;

import module java.base;
import module org.slf4j;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.Environment;

/**
 * Greets a deployment nobody can sign in to yet, with the {@link FirstRunKey} that lets somebody in.
 *
 * <p>It speaks once the context has refreshed - the web server is listening by then - so the key is the last thing a
 * first start prints rather than a line lost among the beans that follow it. A start that finds somebody can sign in
 * prints nothing here; a store that refuses the write (a read-only deployment) is said so, since a key that was not
 * stored would be refused at the sign-in form.
 *
 * <p>At WARN rather than INFO, although nothing is wrong: the key is the only way into a fresh deployment, and a
 * deployment that raised its root level to WARN must still see it.
 */
public final class FirstRunWelcome implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger LOGGER = LoggerFactory.getLogger(FirstRunWelcome.class);

    private static final String RULE = "=".repeat(78);

    private final FirstRunKey firstRunKey;
    private final Environment environment;
    private final AtomicBoolean greeted = new AtomicBoolean();

    public FirstRunWelcome(FirstRunKey firstRunKey, Environment environment) {
        this.firstRunKey = firstRunKey;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (!greeted.compareAndSet(false, true)) {
            return;
        }
        Optional<FirstRunKey.Issued> issued;
        try {
            issued = firstRunKey.issueIfNeeded();
        } catch (IOException | RuntimeException refused) {
            LOGGER.warn("Nobody can sign in to this deployment yet, and no one-time key could be stored for it: {}. "
                    + "Set a console admin key (JENREG_UI_ADMIN_KEY) or an administrator (JENREG_UI_ADMINS) "
                    + "instead.", refused.getMessage());
            return;
        }
        issued.ifPresent(key -> LOGGER.warn(message(key, port())));
    }

    private String port() {
        return environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
    }

    /** The banner: what this is, the key, where to use it, and how long it lasts. */
    public static String message(FirstRunKey.Issued issued, String port) {
        String until = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT)
                .withZone(ZoneOffset.UTC).format(issued.expires());
        String n = System.lineSeparator();
        return n + RULE + n
                + n
                + "   WELCOME TO JENESIS REPOSITORY" + n
                + n
                + "   Nobody can sign in to this deployment yet, so this start made a one-time key:" + n
                + n
                + "       " + issued.key() + n
                + n
                + "   Open http://localhost:" + port + " (or this server's address), choose" + n
                + "   \"Sign in with a key\" and paste it. The setup guide opens after you sign in." + n
                + n
                + "   The key works until " + until + ", and only until an administrator is set up." + n
                + "   If it runs out first, restart the server and it prints a new one." + n
                + n
                + RULE;
    }
}
