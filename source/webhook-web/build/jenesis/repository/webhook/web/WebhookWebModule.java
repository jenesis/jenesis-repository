package build.jenesis.repository.webhook.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the event-webhook recovery web adapter to the repository server's {@code ServerModuleProvider} discovery,
 * so the server imports {@link WebhookWebConfig} - and with it the {@code /api/webhook} status endpoint and the
 * {@code /api/webhook/retry} unpark mutation - without naming the webhook surface anywhere. With this module absent the
 * server carries none of the surface.
 */
public final class WebhookWebModule implements ServerModuleProvider {

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public Class<?> configuration() {
        return WebhookWebConfig.class;
    }
}
