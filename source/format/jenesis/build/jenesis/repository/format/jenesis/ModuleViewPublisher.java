package build.jenesis.repository.format.jenesis;

import module java.base;
import build.jenesis.repository.format.java.JavaLayout;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServedAliases;
import build.jenesis.repository.format.java.bridge.ModuleView;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Jenesis format's contribution to cross-publishing: when the Maven format publishes a modular jar, it hands the
 * module here to also give it a {@code /module/} view - the jar linked by module name and version (and by module name
 * alone, the latest) over the same content-addressed blob. Discovered by the Maven format through {@link ServiceLoader}
 * over the {@link ModuleView} contract the shared Java-layout module exports to just these two modules.
 *
 * <p>Both pointers are written by a publish; only the version-addressed one is written by a
 * {@link ModuleView#rebuild rebuild}, because only it is a function of stored state. Every write is the same
 * compare-and-set {@link Publication#link} a first-hand Jenesis publish makes, so a cross-view and a direct
 * {@code /module/} publish are the same object and a repeat of either is free.
 */
public final class ModuleViewPublisher implements ModuleView {

    @Override
    public void publish(String moduleName, String version, String classifier, String hash, ArtifactStore store,
                        String origin) throws IOException {
        rebuild(moduleName, version, classifier, hash, store, origin);
        if (!classifier.isEmpty()) {
            return;   // a classified jar is one of a version's files, never the module the latest view names
        }
        // The "latest" pointers, and the reason publish and rebuild are two methods: they say "the most recently
        // published version of this module", which is an ordering fact about publications rather than a fact about
        // stored state. Only a publish knows it. A rebuild pass re-linking them would move them to whichever version
        // the walk reached last, so the pass leaves them exactly as the last publish left them.
        Publication publication = new Publication(store);
        for (String latest : List.of(JavaLayout.latestModule(moduleName),
                JavaLayout.latestArtifact(moduleName, "jar"))) {
            publication.link(latest, hash);
            // REASSIGNED, not recorded: the latest view names whichever version published last, so this publish takes
            // it off the version that held it. An append would leave a release of 1.0 lifting a view that has been
            // 2.0's since 2.0 landed - and 2.0 may be held on its own account.
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
