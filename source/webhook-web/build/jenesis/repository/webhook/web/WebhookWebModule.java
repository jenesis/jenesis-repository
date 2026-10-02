package build.jenesis.repository.webhook.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/** Contributes {@link WebhookWebConfig}, with {@code /api/webhook} and {@code /api/webhook/retry}, to the server. */
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
