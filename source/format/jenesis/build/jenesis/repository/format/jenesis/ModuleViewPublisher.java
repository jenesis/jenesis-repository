package build.jenesis.repository.format.jenesis;

import module java.base;
import build.jenesis.repository.format.java.JavaLayout;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServedAliases;
import build.jenesis.repository.format.java.bridge.ModuleView;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Jenesis format's side of cross-publishing: when the Maven format publishes a modular jar it hands the module here
 * for a {@code /module/} view - the jar linked by name and version, and by name alone for the latest - over the same
 * blob. Discovered by the Maven format through {@link ServiceLoader} over {@link ModuleView}, exported to just these
 * two modules.
 *
 * <p>A publish writes both pointers; a {@link ModuleView#rebuild rebuild} only the version-addressed one, the only
 * function of stored state. Every write is the {@link Publication#link} compare-and-set a direct publish makes, so a
 * cross-view and a direct publish are the same object and a repeat is free.
 */
public final class ModuleViewPublisher implements ModuleView {

    @Override
    public void publish(String moduleName, String version, String classifier, String hash, ArtifactStore store,
                        String origin) throws IOException {
        rebuild(moduleName, version, classifier, hash, store, origin);
        if (!classifier.isEmpty()) {
            return;   // a classified jar is one of a version's files, never the module the latest view names
        }
        // The latest pointers are why publish and rebuild differ: a publish moves them to its version when it is the
        // highest yet (LatestView), while a rebuild re-linking them would move them to whatever the walk reached last.
        if (!LatestView.takes(store, JavaLayout.latestModule(moduleName), version)) {
            return;
        }
        Publication publication = new Publication(store);
        for (String latest : List.of(JavaLayout.latestModule(moduleName),
                JavaLayout.latestArtifact(moduleName, "jar"))) {
            publication.link(latest, hash);
            // Reassigned, not appended: the view leaves the version that held it, or a release of 1.0 would later lift
            // a view that is 2.0's - and 2.0 may be held on its own account.
            ServedAliases.reassign(store, origin, latest);
        }
    }

    @Override
    public void rebuild(String moduleName, String version, String classifier, String hash, ArtifactStore store,
                        String origin) throws IOException {
        Publication publication = new Publication(store);
        for (String view : List.of(JavaLayout.versionedModule(moduleName, version, classifier),
                JavaLayout.versionedArtifact(moduleName, version, classifier, "jar"))) {
            publication.link(view, hash);
            ServedAliases.record(store, origin, view);
        }
    }

    @Override
    public void describe(String moduleName, String version, String hash, boolean latest, ArtifactStore store,
                         String origin) throws IOException {
        Publication publication = new Publication(store);
        String versioned = JavaLayout.versionedArtifact(moduleName, version, "", "pom");
        publication.link(versioned, hash);
        ServedAliases.record(store, origin, versioned);
        if (latest) {
            String pom = JavaLayout.latestArtifact(moduleName, "pom");
            publication.link(pom, hash);
            ServedAliases.reassign(store, origin, pom);
        }
    }
}
