package build.jenesis.repository.webhook;

/**
 * The one spelling of the {@code webhook/} root and the two sub-spaces beneath it.
 *
 * <p>The same shape as {@code ForwardingKeys}, one module over and smaller: a constant for both
 * {@code WebhookOutbox} and {@link WebhookStorageNamespace} to name, because the namespace's declaration of the root
 * is the one the reclamation census reads, and without a shared constant no compiler relates it to what the outbox
 * actually writes.
 */
public final class WebhookKeys {

    /** The storage root this module owns, and the prefix its reclamation namespace declares. */
    public static final String ROOT = "webhook";

    /** The outbox a delivery is queued in, and the parking space a poisoned delivery is moved to. */
    static final String OUTBOX = ROOT + "/outbox";
    static final String PARKED = ROOT + "/parked";

    private WebhookKeys() {
    }
}
