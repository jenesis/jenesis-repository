package build.jenesis.repository.hooks.testkit;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;

/**
 * The durable state that makes a shipped hook <em>live at all</em>, which the store kit's archetypes never needed.
 *
 * <p>An archetype acts on the first publish it is handed. Most shipped observers do not: they are deliberately inert
 * until the surface they feed exists, because the pass that builds that surface is a full rebuild from truth and a
 * marker written before it would be swept away unread. That gate is the bootstrap rule the two-route derived-metadata
 * contract rests on, so a fixture that bypassed it would be testing a code path no deployment reaches on a virgin
 * store.
 *
 * <p>The kit hands every check a fresh, empty store and calls no fixture leg before the drive, so the seed has to come
 * from the driver: {@link HookStores#deployed} calls this once per check, on the root store <em>and</em> on the one
 * {@code acme/main} scope the kit's tenant-scoping check publishes into, because a per-repository marker is per
 * repository. It is deliberately <b>not</b> allowed to write anything the fixture's own projection reads: the seed is
 * the deployment, never the surface, and the checks that assert an empty surface would silently pass if it were.
 */
public interface Deployment {

    /** Seed the durable state this hook is gated on, into {@code store}. Called before every check, on each scope the
     *  kit publishes into. Must write nothing the fixture's {@code projection} or {@code enqueued} would read back. */
    void deploy(ArtifactStore store) throws IOException;
}
