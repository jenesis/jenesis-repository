package build.jenesis.repository.webhook;

import module java.base;
import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The webhook module's storage manifest: it owns the per-repository {@code webhook} space - the active {@link
 * WebhookOutbox} queue ({@code webhook/outbox}) and the parked backlog ({@code webhook/parked}) a terminally-failed
 * entry is moved into, both under the one {@code webhook} prefix - so the orphan diagnostic and the explicit operator
 * purge know the key-space without a hardcoded table. Per-tenant like every other module's data - two tenants never
 * share a webhook queue.
 */
public final class WebhookStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of(WebhookKeys.ROOT);
    }
}
