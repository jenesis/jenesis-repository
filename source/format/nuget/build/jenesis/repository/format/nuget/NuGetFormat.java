package build.jenesis.repository.format.nuget;

import module java.base;
import module java.xml;
import module tools.jackson.databind;

import build.jenesis.repository.blobs.RequestBase;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.Listings;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.icon.IconResource;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.multipart.MultipartBody;
import build.jenesis.repository.multipart.MultipartForm;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.PublishedExport;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArchiveWalk;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.Withheld;
import build.jenesis.repository.format.Semver;
import build.jenesis.repository.xml.Xml;

/**
 * The NuGet v3 format: {@code dotnet nuget push} and {@code dotnet restore} over the same store, under
 * {@code /nuget/...}. The service index ({@code GET /nuget/v3/index.json}) advertises the flat container and the
 * publish endpoint with absolute URLs built from the request, so the {@code /<repo>/} segment survives routing. A push
 * ({@code PUT /nuget/v3/package}, a multipart {@code .nupkg} read through
 * {@link build.jenesis.repository.multipart.MultipartBody}) reads the id and version from the {@code .nuspec} inside
 * the archive and stores the package under {@code nuget/<id>/<version>/}; the flat container lists a package's versions
 * and serves each {@code .nupkg}.
 */
public final class NuGetFormat implements RepositoryFormat, ProxyLeg, BlobLayout, RepositoryImporter.Delegating,
        ArtifactSignatures, RepositoryExporter {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String name() {
        return "nuget";
    }

    /** A deprecation shows, and a yank as NuGet itself says it: an unlisted version is left out of search and
     *  resolution and still installable by its exact version. */
    @Override
    public Map<LifecycleMark, String> lifecycleMarks() {
        return Map.of(LifecycleMark.DEPRECATED, LifecycleMark.DEPRECATED.word(), LifecycleMark.YANKED, "unlisted");
    }

    /** A package's marks name its id in lower case, as every path of the feed does. */
    @Override
    public String lifecycleCoordinate(String coordinate, String path) {
        return coordinate.toLowerCase(Locale.ROOT);
    }

    @Override
    public String ecosystem() {
        return "NuGet";
    }

    /** The coordinate version a stored NuGet pointer serves, from which the inventory back-fill rebuilds a lost
     *  {@code published} record. The version is its own segment of {@code nuget/<id>/<version>/<id>.<version>.nupkg},
     *  so the pair is read off the key. The id is lower-cased, as {@link #describe} lower-cases it, so the rebuilt row
     *  is the one the publish wrote. */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        String marker = "nuget/";
        if (!key.startsWith(marker)) {
            return Optional.empty();
        }
        String[] parts = key.substring(marker.length()).split("/");
        if (parts.length != 3) {
            return Optional.empty();     // a package root, a version folder, or something deeper - not a pointer
        }
        String id = parts[0].toLowerCase(Locale.ROOT), version = parts[1];
        if (!BlobLayout.addressable(id, version) || !parts[2].endsWith(".nupkg")) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDescriptor(ecosystem(), id, version, key,
                "application/octet-stream", version.contains("-"), null, 0L));
    }

    @Override
    public List<String> blobRoots() {
        return List.of("nuget");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        // The .nupkg pointer and its dependency sidecar, so evicting a version reclaims both.
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();   // a traversal-shaped coordinate maps nowhere - these keys are what an eviction DELETES
        }
        String id = coordinate.toLowerCase(Locale.ROOT);
        List<String> keys = new ArrayList<>();
        String key = "nuget/" + id + "/" + version + "/" + id + "." + version + ".nupkg";
        if (store.readVersioned(key).isPresent()) {
            keys.add(key);
        }
        String dependencies = dependenciesKey(id, version);
        if (store.readVersioned(dependencies).isPresent()) {
            keys.add(dependencies);
        }
        return keys;
    }

    /** The request path this version's {@code .nupkg} serves at
     *  ({@code /nuget/v3-flatcontainer/<id>/<version>/<id>.<version>.nupkg}), where a retroactive hold links its
     *  {@code /quarantine} handle; the dependency sidecar is not a download. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();
        }
        String id = coordinate.toLowerCase(Locale.ROOT);
        String key = "nuget/" + id + "/" + version + "/" + id + "." + version + ".nupkg";
        // The path is the store-free derivation below; only the liveness probe is this leg's.
        return store.readVersioned(key).isPresent() ? servedPaths(coordinate, version) : List.of();
    }

    /** The path this package version serves at, derived from the coordinate alone. The push endpoint is the only
     *  descriptor the screen has before the {@code .nuspec} is read, so this lets the gate key a screen-time hold's
     *  audit row and held-subject record on the package, the handle {@link #held} re-keys to. */
    @Override
    public List<String> servedPaths(String coordinate, String version) {
        return BlobLayout.addressable(coordinate, version)
                ? List.of(flatContainerPath(coordinate.toLowerCase(Locale.ROOT), version))
                : List.of();
    }

    /** The coordinate a flat-container request path carries
     *  ({@code /nuget/v3-flatcontainer/<id>/<version>/<file>.nupkg}), the id lower-cased as the store keys it. The
     *  service index, search, registrations, the version list, the push endpoint and the dependency sidecar name no
     *  versioned artifact. A {@code -} in the version marks a prerelease, as {@link Semver#compare} ranks it. */
    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith("/nuget/v3-flatcontainer/") || !path.endsWith(".nupkg")) {
            return Optional.empty();
        }
        String after = path.substring("/nuget/v3-flatcontainer/".length());
        int slash = after.indexOf('/');
        int next = slash < 0 ? -1 : after.indexOf('/', slash + 1);
        if (next < 0 || slash == 0 || next == slash + 1) {
            return Optional.empty();
        }
        String id = after.substring(0, slash).toLowerCase(Locale.ROOT);
        String version = after.substring(slash + 1, next);
        return Optional.of(new ArtifactDescriptor("NuGet", id, version, path,
                "application/octet-stream", version.contains("-"), null, -1L));
    }

    // An original CC0 line glyph (a package hexagon with a core).
    private static final IconResource ICON = IconResource.svg("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 2.5 20 7v10l-8 4.5L4 17V7z"/><circle cx="12" cy="12" r="3"/>
            </svg>""");

    @Override
    public Optional<IconResource> icon() {
        return Optional.of(ICON);
    }

    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(URI.create("https://api.nuget.org/"));
    }

    /** The one resource a push is accepted at, relative to {@code /nuget/}: what {@link #index} advertises as
     *  {@code PackagePublish/2.0.0} and {@link #handle} routes a {@code PUT} to. */
    private static final String PUSH = "v3/package";

    /** The push endpoint as a request path, which is what a descriptor handed to the screen carries. */
    private static final String PUSH_ROUTE = "/nuget/" + PUSH;

    @Override
    public boolean handles(String path) {
        return path.startsWith("/nuget/");
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        Blobs blobs = new Blobs(store);
        String rest = exchange.path().substring("/nuget/".length());
        if (exchange.method().equals("DELETE")) {
            if (rest.startsWith(PUSH + "/")) {
                unlist(rest.substring(PUSH.length() + 1), blobs, store, exchange);
            } else {
                exchange.respond(405);
            }
            return;
        }
        if (exchange.method().equals("PUT")) {
            // A push goes to the one advertised PackagePublish/2.0.0 resource and nowhere else. The coordinate comes
            // from the .nuspec, so without this any PUT under /nuget/ would publish and answer 201, telling a
            // misconfigured client its push succeeded; an unadvertised address is this format's 404 (contract clause
            // 6).
            if (rest.equals(PUSH) || rest.equals(PUSH + "/")) {
                push(exchange, blobs, store);
            } else {
                exchange.respond(404);
            }
            return;
        }
        if (rest.equals("v3/index.json")) {
            index(exchange);
        } else if (rest.equals("v3/search")) {
            search(blobs, exchange);
        } else if (rest.startsWith("v3/registrations/") && rest.endsWith("/index.json")) {
            registration(rest.substring("v3/registrations/".length(), rest.length() - "/index.json".length()),
                    blobs, exchange);
        } else if (rest.startsWith("v3-flatcontainer/")) {
            String after = rest.substring("v3-flatcontainer/".length());
            if (after.endsWith("/index.json")) {
                versions(after.substring(0, after.length() - "/index.json".length()), blobs, exchange);
            } else {
                int slash = after.indexOf('/');
                int next = slash < 0 ? -1 : after.indexOf('/', slash + 1);
                if (next < 0) {
                    exchange.respond(404);
                } else {
                    serve(after.substring(0, slash), after.substring(slash + 1, next), after.substring(next + 1),
                            blobs, exchange);
                }
            }
        } else {
            exchange.respond(404);
        }
    }

    private void index(FormatExchange exchange) throws IOException {
        String uri = exchange.requestUri();
        String base = RequestBase.of(exchange) + uri.substring(0, uri.length() - "/v3/index.json".length());
        String json = JSON.writeValueAsString(Map.of(
                "version", "3.0.0",
                "resources", List.of(
                        Map.of("@id", base + "/v3-flatcontainer/", "@type", "PackageBaseAddress/3.0.0"),
                        Map.of("@id", base + "/v3/registrations/", "@type", "RegistrationsBaseUrl/3.6.0"),
                        // The official client selects a search service by exact type from SearchQueryService/Versioned,
                        // /3.4.0 and /3.0.0-beta, and finds none if only the bare name or /3.0.0-rc is advertised.
                        Map.of("@id", base + "/v3/search", "@type", "SearchQueryService"),
                        Map.of("@id", base + "/v3/search", "@type", "SearchQueryService/3.0.0-beta"),
                        Map.of("@id", base + "/v3/search", "@type", "SearchQueryService/3.0.0-rc"),
                        Map.of("@id", base + "/v3/search", "@type", "SearchQueryService/3.4.0"),
                        Map.of("@id", base + "/" + PUSH, "@type", "PackagePublish/2.0.0"))));
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(200, json.getBytes(StandardCharsets.UTF_8));
    }

    /** The republish policy for the hosted publish: {@code OVERWRITE}, since the coordinate is known only once the
     *  layout has parsed the {@code .nuspec}. A version already pushed is refused at the link ({@link Blobs#linkOnce}),
     *  inside the pointer's compare-and-set, with nuget.org's {@code 409}, which {@code --skip-duplicate} recognises;
     *  an identical re-push converges. */
    private static final Publication.Republish REPUBLISH = Publication.Republish.overwrite();

    /** A push wraps its package in a multipart form, so this format is not edge-screened: the shared edge would assess
     *  the envelope while the bytes that serve are the {@code .nupkg} inside it, a hash no interceptor saw
     *  ({@code RepositoryFormat} clause 14). It screens at its own choke point instead: {@link #push} peels the file
     *  part off the stream and {@link #publish} drives {@code Publication.commit} with the discovered interceptors and
     *  observers over the package's own bytes. A bare {@code .nupkg} body takes the same path, so neither shape reaches
     *  {@link #handle} unscreened. */
    @Override
    public boolean screened() {
        return false;
    }

    private void push(FormatExchange exchange, Blobs blobs, ArtifactStore store) throws IOException {
        // A .nupkg is streamed into the content-addressed store and only the stored blob is reopened for its .nuspec,
        // so a package of any size publishes.
        String contentType = exchange.requestHeader("Content-Type");
        if (contentType != null && contentType.contains("multipart/form-data")) {
            Optional<String> boundary = MultipartBody.boundary(contentType);
            if (boundary.isEmpty()) {
                exchange.respond(400);   // no boundary
                return;
            }
            // The first part carrying a filename is the .nupkg, bounded to the next boundary by the shared streaming
            // reader. It is what reaches the screen, stored once under its own hash; the stream stays open across the
            // commit since the layout reopens the stored blob.
            Optional<MultipartBody.Part> file =
                    MultipartBody.over(exchange.requestStream(), boundary.get()).nextFile();
            if (file.isEmpty()) {
                exchange.respond(400);   // a multipart body with no file part
                return;
            }
            try (InputStream part = file.get().stream()) {
                publish(part, exchange, blobs, store);
            }
        } else {
            // A bare .nupkg body.
            publish(exchange.requestStream(), exchange, blobs, store);
        }
    }

    /**
     * The hosted publish, through the shared {@code Publication.commit}: the package is stored content-addressed, the
     * layout parses the {@code .nuspec}, and only then is the serving pointer linked. <b>The commit point is the
     * {@code .nupkg} pointer link</b>; every parse result lands before it, and the sidecar, the hosted marker and the
     * listings follow it, so nothing keyed by the version is written for a push the link refuses.
     *
     * <p><b>This is the format's screening choke point</b> ({@link #screened()}): the operation carries the discovered
     * interceptor chain and observers, so the screen runs over the {@code .nupkg}'s bytes and the after-commit
     * notification fires here.
     *
     * <p>The screen's descriptor carries the push path and no coordinate, which lives inside the package; the NuGet
     * inspector reads the stored body itself, so the coordinate and licence assessed come from the artifact. Once
     * parsed, the coordinate and download path are attached with {@link Publication.Visibility#describing}, so the
     * observers key on the artifact.
     */
    private void publish(InputStream nupkg, FormatExchange exchange, Blobs blobs, ArtifactStore store)
            throws IOException {
        try {
            commitPush(nupkg, exchange, blobs, store);
        } catch (Publication.RepublishConflict taken) {
            exchange.respond(409, ("Conflict: this package version already exists and cannot be modified ("
                    + taken.pointer() + ")").getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The push {@link #publish} answers, a version already pushed aside. */
    private void commitPush(InputStream nupkg, FormatExchange exchange, Blobs blobs, ArtifactStore store)
            throws IOException {
        Publication.Commit commit = new Publication(store).commit(
                ArtifactDescriptor.at("NuGet", exchange.path()), nupkg, REPUBLISH,
                accepted -> {
                    Parsed parsed = parsed(store, accepted.hash());
                    if (parsed == null) {
                        // No parseable .nuspec, or an id/version that would forge a pointer key: nothing is declared or
                        // linked.
                        return Publication.Visibility.declined();
                    }
                    String id = parsed.id();
                    String version = parsed.version();
                    String key = nupkgKey(id, version);
                    return Publication.Visibility
                            // The serving pointer, in this format's namespace rather than publish/, so a Serving step.
                            // A version already pushed refuses here, before anything keyed by the version is written.
                            .through((hash, size, _) -> blobs.linkRelease(key, hash, size))
                            .andThrough((_, _, _) -> parsed.write(blobs))
                            // The hosted marker switches on the local version index; a pull-through proxy never writes
                            // it, so its index falls through to the upstream's list. Written after the pointer, never
                            // ahead of the bytes it would list.
                            .andThrough((_, _, target) -> markHosted(target, HOSTED_KEY))
                            // The version list, registration index and search record are maintained on the push.
                            .andThrough((_, _, _) -> new NuGetListings(blobs).refresh(id, version))
                            // The observers are notified with the package's own coordinate and download path.
                            .describing(new ArtifactDescriptor("NuGet", id, version, flatContainerPath(id, version),
                                    "application/octet-stream", version.contains("-"), null, -1L));
                });
        switch (commit.disposition()) {
            case ACCEPT -> exchange.respond(commit.visible() ? 201 : 400);
            // Held: the layout is written behind the withhold marker (see held), so a review release is the marker
            // clear rather than a replay of a push whose envelope is gone.
            case QUARANTINE -> {
                held(blobs, store, exchange.path(), commit.hash());
                explain(exchange, 202, commit.explanation());
            }
            // Refused: nothing is linked or marked, and the stored blob is an unreferenced object the collector
            // reclaims.
            case REJECT -> explain(exchange, 422, commit.explanation());
        }
    }

    /**
     * The parse half of the layout, shared by the accepted and held legs so both derive the same coordinate and
     * sidecar. Streams the {@code .nuspec} out of the stored blob, refuses an id or version that would forge a pointer
     * key, and precomputes the dependency groups so a registration read concatenates sidecars rather than unzipping
     * every version. Nothing is written: the sidecar is written by {@link Parsed#write} only after the pointer is
     * linked, so a refused push never replaces a released version's dependencies.
     *
     * @return the lower-cased coordinate and its dependency groups, or {@code null} when nothing servable can be
     *     derived
     */
    private static Parsed parsed(ArtifactStore store, String hash) throws IOException {
        String[] coordinate;
        try (InputStream stored = store.open("blobs/" + hash)) {
            coordinate = coordinate(stored);
        }
        if (coordinate == null) {
            return null;
        }
        String id = coordinate[0].toLowerCase(Locale.ROOT);
        String version = coordinate[1];
        if (Keys.unsafe(id) || Keys.unsafe(version)) {
            return null;
        }
        List<Map<String, Object>> groups;
        try (InputStream stored = store.open("blobs/" + hash)) {
            groups = dependencyGroups(stored);
        }
        return new Parsed(id, version, JSON.writeValueAsBytes(groups));
    }

    /** A pushed package's coordinate and the dependency-groups sidecar a registration read concatenates. */
    private record Parsed(String id, String version, byte[] dependencies) {

        /** Store the sidecar through {@link Blobs}. */
        void write(Blobs blobs) throws IOException {
            blobs.write(dependenciesKey(id, version), dependencies);
        }
    }

    /**
     * Lay a held package out behind its withhold marker, so its review release is the same marker clear a retroactive
     * hold's release is. The shared commit lays out only on {@code ACCEPT}, so without this a release would materialise
     * nothing.
     *
     * <p><b>The review pointer is re-keyed onto the package.</b> The screen's descriptor carries the push endpoint, so
     * {@code Publication.screen} links the review pointer at {@code /quarantine/nuget/v3/package}, shared by every
     * push. The withhold marker is content-addressed and {@code withheld-reconcile} lifts a marker no live
     * {@code /quarantine} pointer aliases, so a second held push overwriting that pointer would release the first
     * package unreviewed. Each held package gets its own handle at its download path, and the endpoint pointer is
     * removed, since the release's cross-alias guard treats any other pointer carrying the hash as a standing hold. The
     * {@code QuarantineLog} row stays under the endpoint path.
     *
     * <p>Order: the dependency sidecar first, then {@link Withheld#mark}, and only then the {@code .nupkg} pointer and
     * the hosted marker, so the held package is never downloadable or listed. The stored listings leave a marked
     * version out.
     */
    private static void held(Blobs blobs, ArtifactStore store, String endpoint, String hash) throws IOException {
        Parsed parsed = parsed(store, hash);
        if (parsed == null) {
            return;   // nothing servable to hold open; the hold stays reviewable by its stored blob alone
        }
        String id = parsed.id();
        String version = parsed.version();
        Publication publication = new Publication(store, List.of(), List.of());
        try {
            // A hold never replaces a released package: refused before the mark.
            blobs.refuseReplacement(nupkgKey(id, version), hash);
        } catch (Publication.RepublishConflict taken) {
            publication.unpublish("/quarantine" + endpoint);
            throw taken;
        }
        Withheld.mark(store, hash, new ArtifactDescriptor("NuGet", id, version, flatContainerPath(id, version),
                "application/octet-stream", version.contains("-"), null, -1L));
        blobs.linkRelease(nupkgKey(id, version), hash, -1L);
        parsed.write(blobs);
        markHosted(store, HOSTED_KEY);
        new NuGetListings(blobs).refresh(id, version);   // held: the stored documents keep it out
        publication.link("/quarantine" + flatContainerPath(id, version), hash);
        publication.unpublish("/quarantine" + endpoint);
    }

    /** The package's served download path: what the flat container serves, what {@link #describe} parses, and where a
     *  held package's review handle is re-keyed. */
    private static String flatContainerPath(String id, String version) {
        return "/nuget/v3-flatcontainer/" + id + "/" + version + "/" + id + "." + version + ".nupkg";
    }

    /** The deployment-wide hosted-publish marker; a NuGet id cannot start with {@code .}, and {@link #search} skips
     *  it. */
    private static final String HOSTED_KEY = "nuget/.hosted";
    private static final byte[] HOSTED = "1".getBytes(StandardCharsets.UTF_8);

    /** Whether this repository has taken a hosted push. The version-index gate keys on it, so a proxy repository's
     *  index misses locally and the upstream's full version list is relayed. */
    private static boolean hosted(Blobs blobs) throws IOException {
        return blobs.exists(HOSTED_KEY);
    }

    /** Stamp the hosted marker once, by compare-and-set against absence; a lost race means a peer set it. */
    private static void markHosted(ArtifactStore store, String key) throws IOException {
        if (store.readVersioned(key).isEmpty()) {
            store.writeVersioned(key, HOSTED, null);
        }
    }

    private static String dependenciesKey(String id, String version) {
        return "nuget/" + id + "/" + version + "/dependencies.json";
    }

    private void versions(String id, Blobs blobs, FormatExchange exchange) throws IOException {
        String lower = id.toLowerCase(Locale.ROOT);
        if (Keys.unsafe(lower) || !hosted(blobs) || (!StoredListing.present(blobs.store(), NuGetListings.versions(lower))
                && blobs.isEmpty("nuget/" + lower))) {
            exchange.respond(404);
            return;
        }
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(),
                new NuGetListings(blobs).versionsSpec(lower));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            Listings.serve(exchange, document, "application/json", NuGetListings.BASE, null);
        }
    }

    /** {@code dotnet nuget delete <id> <version>}: {@code DELETE} on the publish resource followed by
     *  {@code /<id>/<version>}, which unlists the version as nuget.org does, through the product's yanked mark
     *  ({@link Lifecycle#mark(FormatExchange, ArtifactStore, String, String, Lifecycle.Flag)}), so the registration
     *  leaf renders {@code listed: false} whichever surface set it. {@code 204} for a version this feed holds,
     *  {@code 404} otherwise. */
    private static void unlist(String rest, Blobs blobs, ArtifactStore store, FormatExchange exchange)
            throws IOException {
        int slash = rest.indexOf('/');
        String id = slash < 0 ? "" : rest.substring(0, slash).toLowerCase(Locale.ROOT);
        String version = slash < 0 ? "" : rest.substring(slash + 1).toLowerCase(Locale.ROOT);
        if (!BlobLayout.addressable(id, version) || version.contains("/")
                || blobs.hash(nupkgKey(id, version)).isEmpty()) {
            exchange.respond(404);
            return;
        }
        if (Lifecycle.read(store, id, version).filter(flag -> flag.state() == LifecycleMark.YANKED).isEmpty()) {
            Lifecycle.mark(exchange, store, id, version, new Lifecycle.Flag(LifecycleMark.YANKED, ""));
        }
        exchange.respond(204);
    }

    /** The {@code .nupkg} pointer key a version's bytes live at, by which every version-enumerating surface judges a
     *  version, so the stored listings and the download cannot drift apart. */
    static String nupkgKey(String id, String version) {
        return "nuget/" + id + "/" + version + "/" + id + "." + version + ".nupkg";
    }

    private void search(Blobs blobs, FormatExchange exchange) throws IOException {
        String query = exchange.queryParameter("q");
        int skip;
        int take;
        try {
            String skipped = exchange.queryParameter("skip");
            String taken = exchange.queryParameter("take");
            skip = skipped == null ? 0 : Math.max(0, Integer.parseInt(skipped));
            take = taken == null ? 20 : Math.min(Integer.parseInt(taken), 1000);
        } catch (NumberFormatException invalid) {
            exchange.respond(400);
            return;
        }
        // The search document holds a record per package id with its servable versions. A search is one streamed pass
        // over it keeping only the requested page and a count, since the document is the size of the feed.
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(),
                new NuGetListings(blobs).searchSpec());
        // A repository that never had a package answers 404, while a query matching nothing answers 200 with an empty
        // array: the test is whether the pass saw any record at all, before the needle is applied.
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        long from = skip;
        long to = (long) skip + Math.max(take, 0);
        long totalHits = 0;
        long examined = 0;
        List<byte[]> data = new ArrayList<>();
        try (StoredListing.Served document = served.get();
             StoredListing.Codec.Reader reader = NuGetListings.RECORDS.read(document.body(),
                     document.header().size())) {
            for (Optional<Map.Entry<String, byte[]>> record = reader.next(); record.isPresent();
                 record = reader.next()) {
                examined++;
                if (!needle.isEmpty() && !record.get().getKey().contains(needle)) {
                    continue;
                }
                if (totalHits >= from && totalHits < to) {
                    data.add(record.get().getValue());   // only the requested page's records are retained
                }
                totalHits++;
            }
        }
        if (examined == 0) {
            exchange.respond(404);
            return;
        }
        StringBuilder json = new StringBuilder("{\"totalHits\":").append(totalHits).append(",\"data\":[");
        for (int i = 0; i < data.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(new String(data.get(i), StandardCharsets.UTF_8));
        }
        json.append("]}");
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(200, json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void registration(String id, Blobs blobs, FormatExchange exchange) throws IOException {
        String lower = id.toLowerCase(Locale.ROOT);
        if (Keys.unsafe(lower) || (!StoredListing.present(blobs.store(), NuGetListings.registration(lower))
                && blobs.isEmpty("nuget/" + lower))) {
            exchange.respond(404);
            return;
        }
        String uri = exchange.requestUri();
        String nuget = RequestBase.of(exchange) + uri.substring(0, uri.indexOf("/v3/registrations/"));
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(),
                new NuGetListings(blobs).registrationSpec(lower));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            Listings.serve(exchange, document, "application/json", NuGetListings.BASE, nuget);
        }
    }

    static JsonNode dependencyGroupsFor(String lower, String version, Blobs blobs) throws IOException {
        ByteArrayOutputStream sidecar = new ByteArrayOutputStream();
        if (blobs.read(dependenciesKey(lower, version), sidecar)) {
            return JSON.readTree(sidecar.toByteArray());
        }
        List<Map<String, Object>> groups = List.of();
        String nupkgKey = "nuget/" + lower + "/" + version + "/" + lower + "." + version + ".nupkg";
        Optional<String> blobHash = blobs.hash(nupkgKey);
        if (blobHash.isPresent()) {
            try (InputStream nupkg = blobs.open(blobHash.get())) {
                groups = dependencyGroups(nupkg);
            } catch (IOException _) {
                groups = List.of();   // the blob is gone or unreadable - no dependency groups
            }
        }
        blobs.write(dependenciesKey(lower, version), JSON.writeValueAsBytes(groups));
        return JSON.valueToTree(groups);
    }

    private static List<Map<String, Object>> dependencyGroups(InputStream nupkg) {
        try {
            Document document = nuspec(nupkg);
            return document == null ? List.of() : groups(document);
        } catch (Exception _) {
            return List.of();
        }
    }

    /** A {@code .nupkg} may carry an author or repository signature as {@code .signature.p7s}, a PKCS#7 entry whose
     *  signed content names the package's hash: optional, and over the package as it was before the entry was appended
     *  ({@link NuGetSignedArchive}). */
    @Override
    public List<ArtifactSignatures.Expectation> expects(String path) {
        return signable(path)
                ? List.of(ArtifactSignatures.Expectation.optional(ArtifactSignatures.Scheme.PKCS7))
                : List.of();
    }

    /** No signature material here has a request path of its own, so none covers another path. */
    @Override
    public Optional<String> covers(String path) {
        return Optional.empty();
    }

    /** The two paths a package's bytes are in hand at: the file it serves from, and the push endpoint. The push
     *  descriptor carries only {@value #PUSH_ROUTE}, the same for every package, so keyed on the served name alone the
     *  signature entry would go unread on every publish. */
    private static boolean signable(String path) {
        return !path.contains("..")
                && (path.endsWith(".nupkg") || path.equals(PUSH_ROUTE) || path.equals(PUSH_ROUTE + "/"));
    }

    /** Yes: the signature is an entry inside the package, so declaring it is what makes a screen claim the push
     *  ({@link ArtifactSignatures#embedsEvidence}). */
    @Override
    public boolean embedsEvidence(String path) {
        return signable(path);
    }

    @Override
    public List<ArtifactSignatures.Evidence> evidence(String path, ArtifactSignatures.Material material)
            throws IOException {
        if (expects(path).isEmpty()) {
            return List.of();
        }
        Optional<ArtifactSignatures.Signed> body = material.body();
        if (body.isEmpty()) {
            return List.of();
        }
        Optional<byte[]> signature;
        try (InputStream nupkg = body.get().open()) {
            signature = NuGetSignedArchive.signature(nupkg);
        }
        return signature
                .map(p7s -> List.of(new ArtifactSignatures.Evidence(ArtifactSignatures.Scheme.PKCS7, p7s,
                        NuGetSignedArchive.unsigned(body.get()), path + "!" + NuGetSignedArchive.SIGNATURE_ENTRY)))
                .orElse(List.of());
    }

    /**
     * The {@code .nuspec} of a {@code .nupkg}, parsed as a bounded XML document with DTDs and external entities
     * disabled, or {@code null} when there is none. The walk runs under
     * {@link build.jenesis.repository.store.ArchiveWalk#largestWalk()} and at most
     * {@link build.jenesis.repository.store.ArchiveInflation#largestEntry()} bytes reach the parser, so the publish and
     * the unauthenticated registration read ({@link #dependencyGroupsFor}) stay bounded; an entry past the ceiling
     * raises rather than returning null or a prefix.
     */
    private static Document nuspec(InputStream nupkg) throws IOException {
        return ArchiveWalk.walk(nupkg, NuGetFormat::declaredNuspec).orNull();
    }

    /** The parsed {@code .nuspec} inside an already-bounded {@code .nupkg} stream, or {@code null} when it carries
     *  none. */
    private static Document declaredNuspec(InputStream nupkg) throws IOException {
        try (ZipInputStream zip = ArchiveWalk.zip(nupkg)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.getName().endsWith(".nuspec")) {
                    // The .nuspec carries the coordinate, so a read the ceiling stopped raises rather than returning
                    // the null a package without one yields: the publish leg turns it into a 400, the registration read
                    // into no dependency groups.
                    byte[] xml = ArchiveInflation.entry(zip).required("NuGet package", ".nuspec");
                    try {
                        return Xml.parse(xml);
                    } catch (SAXException unreadable) {
                        // An unparsable .nuspec is reported as an unreadable member, distinct from a bound.
                        throw new IOException("The NuGet package's .nuspec is not a readable XML document",
                                unreadable);
                    }
                }
            }
        }
        return null;
    }

    // Dependencies are grouped by target framework or flat under <dependencies>; a flat list is one group with no
    // targetFramework.
    private static List<Map<String, Object>> groups(Document document) {
        NodeList blocks = document.getElementsByTagName("dependencies");
        if (blocks.getLength() == 0) {
            return List.of();
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        List<Map<String, String>> flat = new ArrayList<>();
        NodeList children = blocks.item(0).getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element element && element.getTagName().equals("group")) {
                Map<String, Object> group = new LinkedHashMap<>();
                String framework = element.getAttribute("targetFramework");
                if (!framework.isBlank()) {
                    group.put("targetFramework", framework);
                }
                group.put("dependencies", dependencies(element.getElementsByTagName("dependency")));
                groups.add(group);
            } else if (children.item(index) instanceof Element element && element.getTagName().equals("dependency")) {
                flat.add(dependency(element));
            }
        }
        if (!flat.isEmpty()) {
            groups.add(Map.of("dependencies", flat));
        }
        return groups;
    }

    private static List<Map<String, String>> dependencies(NodeList nodes) {
        List<Map<String, String>> dependencies = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element element) {
                dependencies.add(dependency(element));
            }
        }
        return dependencies;
    }

    private static Map<String, String> dependency(Element element) {
        return Map.of("id", element.getAttribute("id"), "range", element.getAttribute("version"));
    }

    private void serve(String id, String version, String file, Blobs blobs, FormatExchange exchange)
            throws IOException {
        String key = "nuget/" + id.toLowerCase(Locale.ROOT) + "/" + version + "/" + file;
        blobs.answer(key, exchange, "application/octet-stream");
    }

    /** Proxy a NuGet flat-container miss to the upstream registry. The service index stays local, advertising this
     *  registry's flat container. A version index is a mutable list of version strings, streamed through; a
     *  {@code .nupkg} is immutable, so it is fetched, cached and served. */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String path = exchange.path();
        if (!path.startsWith("/nuget/v3-flatcontainer/")) {
            // ProxyLeg has screened the path; only the flat-container subtree proxies.
            return false;
        }
        String after = path.substring("/nuget/v3-flatcontainer/".length());
        String root = upstream.toString();
        if (!root.endsWith("/")) {
            root += "/";
        }
        if (after.endsWith("/index.json")) {
            // The version index is streamed through with conditional-request validators forwarded both ways. It is an
            // enumeration restore resolves floating versions against, so only an upstream that answered 404/410 reaches
            // the client as a 404.
            ProxyRelay.Answer answer = ProxyRelay.fetchRemembered(fetcher, URI.create(root + "v3-flatcontainer/" + after),
                    ProxyRelay.conditionalHeaders(exchange), exchange, ProxyRelay.Document.ENUMERATION, store);
            if (!answer.answered()) {
                return answer.served();
            }
            ProxyRelay.relayValidators(answer.document(), exchange);
            exchange.setResponseHeader("Content-Type", "application/json");
            exchange.respond(200, answer.document().body());
            return true;
        }
        int slash = after.indexOf('/');
        int next = slash < 0 ? -1 : after.indexOf('/', slash + 1);
        if (next < 0) {
            return false;
        }
        String id = after.substring(0, slash);
        String version = after.substring(slash + 1, next);
        String key = "nuget/" + id.toLowerCase(Locale.ROOT) + "/" + version + "/" + after.substring(next + 1);
        // NuGet publishes the .nupkg's SHA-512 through its registration and catalog, so the streamed package is
        // verified against it. A hop that could not be read, or that the outbound screen refuses, must not downgrade
        // the fill.
        URI target = URI.create(root + "v3-flatcontainer/" + after);
        ProxyRelay.Declared expected =
                nupkgChecksum(root, id, version, fetcher, ProxyLeg.allowInternalTargets(exchange));
        if (!expected.readable()) {
            return ProxyRelay.unverifiable(target, expected);
        }
        // Streamed from the network into the content-addressed store, since a .nupkg is unbounded.
        try (ProxyFormat.Download download = fetcher.download(target, Map.of()).orElse(null)) {
            if (download == null || download.status() != 200) {
                return false;
            }
            if (!ProxyRelay.fill(new Blobs(store), key, target, download.body(), expected)) {
                return false;
            }
        }
        handle(exchange, store);
        return true;
    }

    /**
     * The SHA-512 NuGet publishes as a package's {@code packageHash}, resolved through the v3 chain: the service index
     * names the {@code RegistrationsBaseUrl}, whose leaf ({@code <id>/<version>.json}) carries the hash or points at a
     * catalog leaf that does. Each hop is a small bounded read, once per package miss.
     *
     * <p>{@link ProxyRelay.Declared#NONE}, plain caching, when a hop answered and the chain declares nothing: no
     * {@code RegistrationsBaseUrl}, a {@code 404}/{@code 410} leaf, or no SHA-512 {@code packageHash}.
     * {@linkplain ProxyRelay.Declared#unreadable Unreadable} when a hop could not be read, or the outbound screen
     * refuses an advertised {@code @id}, since a refused hop read as "no hash" would fail open.
     */
    private static ProxyRelay.Declared nupkgChecksum(String root, String id, String version,
            ProxyFormat.Fetcher fetcher, boolean allowInternal) throws IOException {
        Resolved index = fetchJson(root + "v3/index.json", fetcher);
        if (!index.readable()) {
            return index.declared();
        }
        String registrations = index.answered() ? resource(index.document(), "RegistrationsBaseUrl") : null;
        if (registrations == null) {
            return ProxyRelay.Declared.NONE;
        }
        if (!registrations.endsWith("/")) {
            registrations += "/";
        }
        URI upstream = URI.create(root);
        String leaf = registrations + id.toLowerCase(Locale.ROOT) + "/" + version.toLowerCase(Locale.ROOT) + ".json";
        if (unsafeUpstreamUrl(leaf, upstream, allowInternal)) {
            return ProxyRelay.Declared.unreadable("the registration leaf " + leaf + " the upstream service index "
                    + "advertises is refused by the outbound screen (set " + ProxyLeg.ALLOW_INTERNAL
                    + " to permit an internal or http target)");
        }
        Resolved registration = fetchJson(leaf, fetcher);
        if (!registration.readable()) {
            return registration.declared();
        }
        if (!registration.answered()) {
            return ProxyRelay.Declared.NONE;
        }
        byte[] direct = packageHash(registration.document());
        if (direct != null) {
            return ProxyRelay.Declared.of("SHA-512", direct);
        }
        // The hash lives in the catalog leaf the registration's catalogEntry points at.
        JsonNode catalogEntry = registration.document().get("catalogEntry");
        if (catalogEntry == null || !catalogEntry.isString()) {
            return ProxyRelay.Declared.NONE;
        }
        if (unsafeUpstreamUrl(catalogEntry.asString(), upstream, allowInternal)) {
            return ProxyRelay.Declared.unreadable("the catalog leaf " + catalogEntry.asString() + " the upstream "
                    + "registration advertises is refused by the outbound screen (set " + ProxyLeg.ALLOW_INTERNAL
                    + " to permit an internal or http target)");
        }
        Resolved catalog = fetchJson(catalogEntry.asString(), fetcher);
        if (!catalog.readable()) {
            return catalog.declared();
        }
        byte[] hash = catalog.answered() ? packageHash(catalog.document()) : null;
        return hash == null ? ProxyRelay.Declared.NONE : ProxyRelay.Declared.of("SHA-512", hash);
    }

    /** Whether an upstream-advertised URL (the registrations {@code @id}, a {@code catalogEntry}) is unsafe to fetch,
     *  by the shared outbound screen ({@link OutboundTargets#advertisedRefusal}). A malformed URL or one naming no host
     *  is unsafe. The exemption is the upstream's own origin, not its host name, so a compromised upstream cannot pivot
     *  onto another port of its own host. */
    private static boolean unsafeUpstreamUrl(String url, URI upstream, boolean allowInternal) {
        URI target;
        try {
            target = URI.create(url);
        } catch (IllegalArgumentException malformed) {
            return true;
        }
        return !OutboundTargets.mayFollow(target, upstream, allowInternal);
    }

    /** The resource of a type advertised by a v3 service index, or {@code null}: the bare type preferred, else any
     *  versioned variant ({@code RegistrationsBaseUrl/3.6.0}, ...), all of which resolve the same leaf. */
    private static String resource(JsonNode index, String type) {
        if (!(index.get("resources") instanceof ArrayNode resources)) {
            return null;
        }
        String fallback = null;
        for (JsonNode entry : resources) {
            JsonNode atType = entry.get("@type");
            JsonNode atId = entry.get("@id");
            if (atType == null || atId == null || !atId.isString()) {
                continue;
            }
            String kind = atType.asString();
            if (kind.equals(type)) {
                return atId.asString();
            }
            if (kind.startsWith(type + "/") && fallback == null) {
                fallback = atId.asString();
            }
        }
        return fallback;
    }

    /** The raw SHA-512 of a node's {@code packageHash} when {@code packageHashAlgorithm} is {@code SHA512} and the hash
     *  is base64 of 64 bytes, else {@code null}. */
    private static byte[] packageHash(JsonNode node) {
        JsonNode hash = node.get("packageHash");
        JsonNode algorithm = node.get("packageHashAlgorithm");
        if (hash == null || !hash.isString() || algorithm == null
                || !algorithm.asString().equalsIgnoreCase("SHA512")) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(hash.asString());
            return raw.length == 64 ? raw : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * One hop of the v3 chain: the document when it answered {@code 200}, {@code null} on a miss (the chain declares
     * nothing), or an {@linkplain ProxyRelay.Declared#unreadable unreadable} verdict when it could not be read. Kept
     * apart so a {@code 429} never reads as "no package hash".
     *
     * @param document the hop's parsed body, or {@code null} when it answered a miss or could not be read
     * @param declared the verdict to return when {@link #readable()} is {@code false}
     */
    private record Resolved(JsonNode document, ProxyRelay.Declared declared) {

        /** Whether the hop could be read at all; {@code false} means the caller returns {@link #declared()}. */
        boolean readable() {
            return declared == null;
        }

        /** Whether the hop answered with a document, as against an upstream miss the chain is entitled to. */
        boolean answered() {
            return document != null;
        }
    }

    /** Fetch one bounded JSON hop of the v3 chain. */
    private static Resolved fetchJson(String url, ProxyFormat.Fetcher fetcher) throws IOException {
        URI target = URI.create(url);
        ProxyRelay.Sidecar sidecar = ProxyRelay.declaring(fetcher, target, Map.of("Accept", "application/json"));
        if (!sidecar.answered()) {
            return sidecar.verdict().readable()
                    ? new Resolved(null, null)                       // the upstream answered: it carries no such hop
                    : new Resolved(null, sidecar.verdict());
        }
        JsonNode document = JSON.readTree(sidecar.document().body());
        return document.isObject()
                ? new Resolved(document, null)
                : new Resolved(null, ProxyRelay.Declared.unreadable("the v3 metadata document at " + target
                        + " answered 200 with a body that is not a JSON object"));
    }

    static String[] coordinate(InputStream nupkg) {
        try {
            Document document = nuspec(nupkg);   // raises past the shared inflation ceiling, so a deflate-bomb push fails closed (400)
            if (document == null) {
                return null;
            }
            String id = text(document, "id");
            String version = text(document, "version");
            return id == null || version == null ? null : new String[]{id, version};
        } catch (Exception _) {
            return null;
        }
    }

    private static String text(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    /** The migration-import capability, delegated to {@link NuGetImporter}. */
    private final NuGetImporter importer = new NuGetImporter();

    @Override
    public RepositoryImporter importer() {
        return importer;
    }

    /** The version's {@code .nupkg} is pushed as {@code dotnet nuget push} does - a multipart {@code PUT} to the
     *  {@code PackagePublish} resource with the credential in {@code X-NuGet-ApiKey} - unless its flat-container path
     *  already answers. A signature travels inside the package. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return Exported.WITHHELD;
        }
        String id = coordinate.toLowerCase(Locale.ROOT);
        String file = id + "." + version + ".nupkg";
        Blobs blobs = new Blobs(repository);
        Optional<Blobs.Located> located = blobs.locate("nuget/" + id + "/" + version + "/" + file);
        if (located.isEmpty()) {
            return Exported.WITHHELD;
        }
        String hash = located.get().hash();
        MultipartForm form = MultipartForm.create()
                .file("package", file, "application/octet-stream", located.get().size(), () -> blobs.open(hash));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", form.contentType());
        target.credential().ifPresent(credential -> headers.put("X-NuGet-ApiKey", credential.secret()));
        return PublishedExport.send(List.of(new PublishedExport.File(
                new ExportTarget.Request("PUT", PUSH, headers, ExportTarget.Body.of(form.length(), form::open)),
                Optional.of("v3-flatcontainer/" + id + "/" + version + "/" + file), hash)), target);
    }
}
