package build.jenesis.repository.format.maven;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.StoredListing;

import module java.base;

/**
 * Keeps a coordinate's computed {@code maven-metadata.xml} ({@link MavenMetadataListing}) in step with the
 * transitions that happen off the upload path - a hold on a version and its release, a yank and its reversal, a
 * removal - by re-deciding the one version's membership. Only a coordinate whose listing exists is touched: the
 * computation is opt-in, and a listing exists exactly when the setting is on and the coordinate was read or written.
 */
public final class MavenMetadataObserver implements PublicationObserver, StoredListing.Rebuilder {

    public MavenMetadataObserver() {
    }

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
        // The upload writes its own entry; nothing to do after the fact.
    }

    @Override
    public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        transition(artifact, store);
    }

    @Override
    public void onWithheld(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        transition(subject, store);
    }

    @Override
    public void onWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        transition(subject, store);
    }

    @Override
    public void onMarked(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        transition(subject, store);
    }

    private void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        String coordinatePath = null;
        String version = subject.version();
        if (subject.path() != null && subject.path().startsWith("/maven/")) {
            // /maven/<group/path>/<artifact>/<version>/<file>
            String body = subject.path().substring("/maven/".length());
            int file = body.lastIndexOf('/');
            int at = file < 0 ? -1 : body.lastIndexOf('/', file - 1);
            if (at > 0) {
                coordinatePath = body.substring(0, at);
                version = body.substring(at + 1, file);
            }
        } else if (subject.coordinate() != null && subject.coordinate().indexOf(':') > 0) {
            String coordinate = subject.coordinate();
            int colon = coordinate.indexOf(':');
            coordinatePath = coordinate.substring(0, colon).replace('.', '/') + "/" + coordinate.substring(colon + 1);
        }
        if (coordinatePath == null || version == null
                || !StoredListing.present(store, MavenMetadataListing.listing(coordinatePath))) {
            return;
        }
        new MavenMetadataListing(store).refresh(coordinatePath, version);
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new MavenMetadataListing(store).rebuild(listing);
    }
}
