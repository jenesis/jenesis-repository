package build.jenesis.repository.format.ivy;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;

/**
 * Keeps a module's {@linkplain IvyListings revision listing} in step with transitions off the publish path - a hold and
 * its release, a lifecycle mark and its reversal, a removal - by re-deciding that one revision. A resolver asked for
 * {@code 1.+} picks from this document, so a withheld revision left in it would be selected and then fail to download;
 * re-deciding the entry turns a hold into "not offered" rather than "offered and broken".
 */
public final class IvyListingObserver implements ListingObserver {

    private static final System.Logger LOGGER = System.getLogger(IvyListingObserver.class.getName());

    public IvyListingObserver() {
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new IvyListings(store).rebuild(listing);
    }

    /** A withheld revision is <b>removed rather than re-derived</b>. A hold notifies with the store it was written
     *  through, not the serving store the interceptor chain wraps, so asking that store whether the pointer serves
     *  answers yes while the request path already answers 404. The transition says what happened, so these three act on
     *  the notification; only the repair, which runs against the serving store, re-derives. */
    @Override
    public void onWithheld(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        at(subject, (listings, module, revision) -> listings.withdraw(module, revision), store);
    }

    @Override
    public void onWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        at(subject, (listings, module, revision) -> listings.restore(module, revision), store);
    }

    @Override
    public void onDeleted(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        at(subject, (listings, module, revision) -> listings.withdraw(module, revision), store);
    }

    /** What to do with the one revision a transition names. */
    @FunctionalInterface
    private interface Decision {
        void apply(IvyListings listings, String[] module, String revision) throws IOException;
    }

    /** Resolve the module and revision a subject names - by served path or by coordinate - and apply {@code decision}.
     *  A subject naming neither is not this format's. */
    private void at(ArtifactDescriptor subject, Decision decision, ArtifactStore store) throws IOException {
        if (subject.ecosystem() != null && !subject.ecosystem().equals(IvyFormat.ECOSYSTEM)) {
            return;
        }
        IvyListings listings = new IvyListings(store);
        if (subject.path() != null) {
            String[] segments = subject.path().split("/");
            if (segments.length == 6 && segments[1].equals("ivy")) {
                decision.apply(listings, new String[]{segments[2], segments[3]}, segments[4]);
            }
            return;
        }
        if (subject.coordinate() != null && subject.version() != null) {
            int colon = subject.coordinate().indexOf(':');
            if (colon > 0) {
                decision.apply(listings, new String[]{subject.coordinate().substring(0, colon),
                        subject.coordinate().substring(colon + 1)}, subject.version());
            }
        }
    }

    @Override
    public void transition(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (subject.ecosystem() != null && !subject.ecosystem().equals(IvyFormat.ECOSYSTEM)) {
            return;
        }
        IvyListings listings = new IvyListings(store);
        if (subject.path() != null) {
            // /ivy/<organisation>/<module>/<revision>/<file>
            String[] segments = subject.path().split("/");
            if (segments.length == 6 && segments[1].equals("ivy")) {
                listings.refresh(segments[2], segments[3], segments[4]);
            }
            return;
        }
        // A coordinate rather than a path - a lifecycle mark, a retroactive hold. organisation:module is this layout's
        // whole addressing, so the module is exact.
        if (subject.coordinate() != null && subject.version() != null) {
            int colon = subject.coordinate().indexOf(':');
            if (colon <= 0) {
                return;
            }
            try {
                listings.refresh(subject.coordinate().substring(0, colon),
                        subject.coordinate().substring(colon + 1), subject.version());
            } catch (IllegalArgumentException notAddressable) {
                // A coordinate mapping to no module names no listing; logged rather than raised, since another format
                // may own it.
                LOGGER.log(System.Logger.Level.DEBUG, "not an ivy coordinate: " + subject.coordinate(),
                        notAddressable);
            }
        }
    }
}
