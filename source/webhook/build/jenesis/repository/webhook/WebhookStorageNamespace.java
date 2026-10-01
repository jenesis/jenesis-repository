package build.jenesis.repository.webhook;

import module java.base;
import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The webhook module's storage manifest: the per-repository {@code webhook} space - the {@link WebhookOutbox} queue
 * ({@code webhook/outbox}) and the parked backlog ({@code webhook/parked}) - so the orphan diagnostic and the operator
 * purge know the key-space. Per-tenant, never shared.
 */
public final class WebhookStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of(WebhookKeys.ROOT);
    }
}
