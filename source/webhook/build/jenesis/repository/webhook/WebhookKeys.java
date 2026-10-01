package build.jenesis.repository.webhook;

/**
 * The spelling of the {@code webhook/} root and its two sub-spaces, shared by {@code WebhookOutbox} and
 * {@link WebhookStorageNamespace}, so the root the reclamation census reads is the one the outbox writes.
 */
public final class WebhookKeys {

    /** The storage root this module owns, and the prefix its reclamation namespace declares. */
    public static final String ROOT = "webhook";

    /** The outbox a delivery is queued in, and the space a poisoned delivery is parked in. */
    static final String OUTBOX = ROOT + "/outbox";
    static final String PARKED = ROOT + "/parked";

    private WebhookKeys() {
    }
}
