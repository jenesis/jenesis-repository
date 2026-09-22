package build.jenesis.repository.format.ivy;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ListingObserver;

/**
 * Keeps a module's {@linkplain IvyListings revision listing} in step with the transitions that happen off the
 * publish path - a hold on a published revision and its release, a lifecycle mark and its reversal, a removal - by
 * re-deciding that one revision.
 *
 * <p>It matters more here than for most formats, because of what the listing is <em>for</em>. A resolver asked for
 * {@code 1.+} picks from this document: a revision left in it after its bytes are withheld is a revision the
 * resolver <b>selects</b> and then fails to download, so the build breaks at a download rather than being told the
 * version is unavailable. Re-deciding the entry is what turns a hold into "that version is not offered" instead of
 * "that version is offered and broken".
 */
public final class IvyListingObserver implements ListingObserver {

    private static final System.Logger LOGGER = System.getLogger(IvyListingObserver.class.getName());

    public IvyListingObserver() {
    }

    @Override
    public boolean rebuild(String listing, ArtifactStore store) throws IOException {
        return new IvyListings(store).rebuild(listing);
    }

    /**
     * A withheld revision leaves the listing, and it is <b>removed rather than re-derived</b>.
     *
     * <p>That distinction was measured rather than chosen. A hold notifies its observers with the store the hold
     * was written through, which is not the serving store the interceptor chain wraps - so asking that store
     * whether the pointer still serves answers <em>yes</em> while the request path already answers 404. Re-deriving
     * here would therefore leave the revision listed and a resolver would go on selecting a version it cannot
     * download, which is the exact failure this document exists to prevent.
     *
     * <p>The transition already says what happened. Asking the store to confirm it is not more careful, it is a
     * second answer that can differ from the first - so these three act on the notification and only the repair,
     * which runs against the serving store, re-derives.
     */
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

    /** Resolve the module and revision a subject names - by served path or by coordinate - and apply {@code
     *  decision}. A subject naming neither is not this format's to act on. */
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
        // A transition that names a coordinate rather than a path - a lifecycle mark, a retroactive hold. The
        // coordinate is organisation:module, which is this layout's whole addressing, so the module is exact and
        // no walk is needed to find it.
        if (subject.coordinate() != null && subject.version() != null) {
            int colon = subject.coordinate().indexOf(':');
            if (colon <= 0) {
                return;
            }
            try {
                listings.refresh(subject.coordinate().substring(0, colon),
                        subject.coordinate().substring(colon + 1), subject.version());
            } catch (IllegalArgumentException notAddressable) {
                // A coordinate that maps to no module names no listing to re-decide. Logged rather than raised:
                // a transition is not the place to refuse a name some other format owns.
                LOGGER.log(System.Logger.Level.DEBUG, "not an ivy coordinate: " + subject.coordinate(),
                        notAddressable);
            }
        }
    }
}
