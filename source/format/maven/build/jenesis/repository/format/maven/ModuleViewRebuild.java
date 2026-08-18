package build.jenesis.repository.format.maven;

import module java.base;
import build.jenesis.repository.format.java.JavaLayout;
import build.jenesis.repository.format.java.bridge.ModuleView;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.RebuildPass;
import build.jenesis.repository.walk.WalkConsumer;

/**
 * The repair half of the Maven format's cross-publish: it re-derives the {@code /module/} view of every published
 * modular jar from the durable store, so a cross-publish that never completed is finished by a later pass instead of
 * being left as a documented partial state.
 *
 * <p><b>What it repairs.</b> {@link MavenFormat#layout(ArtifactStore, String, String)} links the {@code /maven/}
 * coordinate first and derives the module views from it afterwards, because that is the only order whose residue can be
 * repaired at all (the coordinate carries the blob the module name is read out of; a stray {@code /module/} view
 * carries no coordinate). Every crash window that ordering leaves - a store read that failed between the two, a view
 * provider that threw, a process that died after the coordinate landed - is a published Maven jar whose module view is
 * missing, and that is exactly what this consumer re-derives. It is also the back-fill for the capability being
 * switched on late: a repository that carries modular jars published before any {@code ModuleView} provider was on the
 * module path gains their views on the first pass (&sect;5), with no re-import.
 *
 * <p><b>What it deliberately does not repair.</b> Only the version-addressed view, through
 * {@link ModuleView#rebuild} - never the "latest" pointer, which records which version was published last and is
 * therefore not a function of stored state. A pass re-linking it would move {@code /module/<name>/<name>.jar} to
 * whichever version the walk happened to reach last, which is the walk inventing a fact rather than restoring one. And
 * it never removes anything: a {@code /module/} pointer with no Maven jar behind it is not evidence of a failed
 * cross-publish, because the Jenesis format publishes into that namespace first-hand, so a deletion here would be an
 * orphan purge over another format's artifacts. Both exclusions are the SPI's "where a surface genuinely cannot be
 * re-derived, name it and degrade" clause taken literally.
 *
 * <p><b>Delivery.</b> Per-item durable: the view write completes inside {@link #onRetained} before it returns, and it
 * is an idempotent compare-and-set on a key derived from the delivered pointer, so a re-delivered stride after a
 * crash-resume re-lands identical bytes and a second full pass leaves the store exactly as the first did. The consumer
 * holds no state between deliveries or passes.
 *
 * <p><b>What drives it, and what that costs the core.</b> It is discovered like any other consumer and driven
 * by whatever runs the shared pass on a cadence - today the downstream {@code RebuildTask}, or an embedder calling
 * {@code RebuildPass.run} itself. This repository ships no scheduler of its own, so in a free-only deployment the
 * repair runs only when something drives a pass; a republish of the same bytes still re-runs the whole layout
 * sequence, which is the other repair and needs no scheduler. That is a gap in the driver, not in the consumer, and
 * naming it here is the honest form: the residue is repairable, and whether it is repaired unattended depends on the
 * deployment.
 *
 * <p>It writes into the {@code publish/module/} keys the view provider owns rather than a key space of its own,
 * because the pointer it repairs is that provider's pointer - it goes through the same bridge the publish path uses,
 * so the two can never derive different paths. Those writes land under the {@code publish} root the pass is
 * enumerating; that is ordinary (a publish during a walk does the same) and delivers the repaired view to the pass's
 * other consumers exactly as a publish would.
 */
public final class ModuleViewRebuild implements WalkConsumer {

    /** The discovered views, loaded once like {@link MavenFormat}'s own list - the same providers, reached through the
     *  same bridge, so a repaired view is byte-identical to a published one. */
    private static final List<ModuleView> MODULE_VIEWS = ServiceLoader.load(ModuleView.class)
            .stream().map(ServiceLoader.Provider::get).toList();

    @Override
    public String name() {
        return "module-view";
    }

    /**
     * Re-derive the version-addressed {@code /module/} view of one delivered pointer, when it is a Maven jar that
     * declares a module name.
     *
     * <p>Everything else is skipped without a store round trip: a pointer outside {@code /maven/}, a path that is not a
     * jar or not a full coordinate, and - the one case worth naming - a pointer whose blob is gone, which
     * {@link RebuildPass} delivers with a size of {@code -1} rather than dropping it, so a reconcile consumer can see
     * the torn state. A torn pointer is not this consumer's to repair: there is no jar to read a module name out of,
     * and guessing one from the coordinate would link a view over content the store does not hold.
     */
    @Override
    public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        String path = artifact.path();
        if (MODULE_VIEWS.isEmpty() || path == null || !path.startsWith("/maven/") || !path.endsWith(".jar")) {
            return;
        }
        String[] coordinate = JavaLayout.mavenCoordinate(path);
        if (coordinate == null || artifact.hash() == null || artifact.size() < 0) {
            return;
        }
        String module = MavenFormat.moduleName(store, artifact.hash());
        if (module == null) {
            return;
        }
        for (ModuleView view : MODULE_VIEWS) {
            view.rebuild(module, coordinate[2], artifact.hash(), store);
        }
    }
}
