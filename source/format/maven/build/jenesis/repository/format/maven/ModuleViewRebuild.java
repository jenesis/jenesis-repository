package build.jenesis.repository.format.maven;

import module java.base;
import build.jenesis.repository.format.java.JavaLayout;
import build.jenesis.repository.format.java.bridge.ModuleView;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.RebuildPass;
import build.jenesis.repository.walk.WalkConsumer;

/**
 * The repair half of the Maven cross-publish: re-derives the {@code /module/} view of every published modular jar from
 * the store, so a cross-publish that never completed is finished by a later pass.
 *
 * <p><b>What it repairs.</b> {@link MavenFormat#layout(ArtifactStore, String, String)} links the coordinate first and
 * derives the views after, so every crash window - a failed read between the two, a view provider that threw, a process
 * that died - is a published jar whose view is missing. It is also the back-fill when a {@code ModuleView} provider
 * joins the module path late.
 *
 * <p><b>What it does not repair.</b> Only the version-addressed view ({@link ModuleView#rebuild}): the "latest" pointer
 * records which version was published last, which is not a function of stored state. It never removes anything, since
 * the Jenesis format publishes into {@code /module/} first-hand and a view with no Maven jar behind it is no evidence
 * of failure.
 *
 * <p><b>Delivery.</b> Per-item durable: the view write completes inside {@link #onRetained} and is an idempotent
 * compare-and-set on a key derived from the pointer, so a re-delivered stride re-lands identical bytes. No state is
 * held between deliveries.
 *
 * <p>Driven by whatever runs the shared rebuild pass on its cadence; a republish of the same bytes re-runs the whole
 * layout and repairs too. It writes the view provider's own {@code publish/module/} keys through the bridge the publish
 * path uses, so both derive the same paths; those writes land under the root the pass enumerates, as a publish during a
 * walk does.
 */
public final class ModuleViewRebuild implements WalkConsumer {

    /** The bridge's discovered list, the instances {@link MavenFormat} publishes through, so a repaired view is
     *  identical to a published one. */
    private static final List<ModuleView> MODULE_VIEWS = ModuleView.installed();

    @Override
    public String name() {
        return "module-view";
    }

    @Override
    public String description() {
        return "Re-derives the /module/ view of every published modular Maven jar whose cross-publish never "
                + "completed; reads each pointer the walk hands it and the jar's descriptor when the view is missing.";
    }

    /** Re-derive the version-addressed {@code /module/} view of one delivered pointer that is a Maven jar declaring a
     *  module name. Anything else is skipped without a store round trip, including a pointer whose blob is gone, which
     *  {@link RebuildPass} delivers with size {@code -1}: there is no jar to read a module name from. */
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
        Optional<String> classifier = JavaLayout.mavenClassifier(path);
        String module = classifier.isEmpty() ? null : MavenFormat.moduleName(store, artifact.hash());
        if (module == null) {
            return;
        }
        for (ModuleView view : MODULE_VIEWS) {
            view.rebuild(module, coordinate[2], classifier.get(), artifact.hash(), store, path);
        }
        // The version's descriptor joins the view with its own jar; the "latest" descriptor is a publish's to move.
        if (classifier.get().isEmpty()) {
            String pomPath = JavaLayout.attachment(path, ".pom");
            Optional<String> pom = new Publication(store).blob(pomPath);
            if (pom.isPresent()) {
                for (ModuleView view : MODULE_VIEWS) {
                    view.describe(module, coordinate[2], pom.get(), false, store, pomPath);
                }
            }
        }
    }
}
