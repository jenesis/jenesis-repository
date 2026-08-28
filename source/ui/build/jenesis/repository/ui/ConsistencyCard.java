package build.jenesis.repository.ui;

import build.jenesis.repository.store.ArtifactStore;

/**
 * The bundled multi-node consistency card: the console's window onto the server's
 * {@code GET /api/consistency} read - the per-node fingerprints every node publishes to the shared store (per-node
 * numbers) and any divergence the check found between them. Consistent with the downstream console's consistency page
 * and its {@code /api/admin/consistency} admin API: a per-node table (id, live/stale, heartbeat age, index cursor,
 * config generation, quota) and a divergence list (each a stuck-cursor / config / pointer split with a value-free
 * reason), calling the key-auth'd JSON API with the {@code Jenesis-Repository-Key} header exactly as the
 * {@link LogCard} does (the free console authenticates the human by session, but the server's
 * {@code /api/consistency} read is key-gated like every other {@code /api} surface).
 *
 * <p>It reads nothing from the {@link ArtifactStore} - the fleet view is a live API read, not store state - and
 * <strong>degrades cleanly to single-node</strong>: a deployment with one live node shows that node and an explicit
 * "single node - no divergence to check" state, never a false positive, and before a key is entered it shows an empty
 * state rather than an error. It detects and reports; it never blocks.
 *
 * <p>The fleet read is the browser's (contract clause 7), so this card prepares no value: the fragment carries the
 * key box, the controls and the empty containers, and {@code /js/cards.js} fills them from the API through the DOM,
 * which escapes a node id or a divergence reason by construction.
 */
public final class ConsistencyCard implements ConsoleCard {

    @Override
    public String id() {
        return "consistency";
    }

    @Override
    public String title() {
        return "Consistency";
    }

    @Override
    public String fragment() {
        return "console/cards :: consistency";
    }

    @Override
    public Object model(ArtifactStore store) {
        return null;
    }
}
