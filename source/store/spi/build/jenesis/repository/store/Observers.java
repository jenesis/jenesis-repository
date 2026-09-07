package build.jenesis.repository.store;

import module java.base;

/** The one discovery of the publication hook class, at this holder's load: every {@link PublicationObserver} on the
 *  module path, and the two subsets consumers used to split off for themselves - the interceptors among them and the
 *  listing rebuilders. A holder rather than a field on the interface, so the load happens on first use of the list and
 *  not on the interface's own initialisation. Empty in the core (no provider on the module path). */
final class Observers {

    static final List<PublicationObserver> ALL = ServiceLoader.load(PublicationObserver.class)
            .stream().map(ServiceLoader.Provider::get).toList();

    static final List<PublishInterceptor> INTERCEPTORS = ALL.stream()
            .filter(observer -> observer instanceof PublishInterceptor)
            .map(observer -> (PublishInterceptor) observer)
            .toList();

    static final List<StoredListing.Rebuilder> REBUILDERS = ALL.stream()
            .filter(observer -> observer instanceof StoredListing.Rebuilder)
            .map(observer -> (StoredListing.Rebuilder) observer)
            .toList();

    private Observers() {
    }
}
