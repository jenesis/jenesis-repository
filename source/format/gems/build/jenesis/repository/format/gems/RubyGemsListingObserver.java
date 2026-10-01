package build.jenesis.repository.format.gems;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.BlobsListingObserver;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Keeps the RubyGems {@linkplain RubyGemsListings stored compact index} in step with transitions off the push path - a
 * hold and its release, a lifecycle mark and its reversal, a removal - by re-deciding the one version in its gem's info
 * document, which re-derives the gem's {@code /versions} line. A subject naming neither a gem path nor a coordinate
 * rebuilds every listing in place.
 */
public final class RubyGemsListingObserver extends BlobsListingObserver {

    public RubyGemsListingObserver() {
        super(new RubyGemsFormat(), "rubygems", "RubyGems listings");
    }

    @Override
    protected void refresh(Blobs blobs, ArtifactStore store, ArtifactDescriptor subject, ArtifactDescriptor named)
            throws IOException {
        RubyGemsListings listings = new RubyGemsListings(blobs);
        if (subject.ecosystem() == null
                && blobs.isEmpty("rubygems/" + named.coordinate() + "/versions")) {
            return;   // a mark on a coordinate this repository holds no gem of
        }
        listings.refresh(named.coordinate(), named.version());
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new RubyGemsListings(new Blobs(store)).rebuild(listing);
    }
}
