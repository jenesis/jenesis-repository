package build.jenesis.repository.format.nuget;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.format.ProxyFormat;

/**
 * Walks a NuGet V3 repository rooted at an upstream: the service index names the resources, package ids come from the
 * catalog where one is advertised or from the search service otherwise, and each id's flat-container {@code index.json}
 * names the versions that exist, so an id whose package was deleted or unlisted contributes nothing. Each entry pairs
 * the lowercase {@code <id>/<version>/<id>.<version>.nupkg} path {@link NuGetImporter} accepts with its download URL.
 * Callers drive it directly. The service index is read eagerly, so one advertising neither a catalog nor a search
 * service fails up front; pages and version lists read lazily, failures surfacing as {@link UncheckedIOException}.
 */
public final class NuGetEnumeration {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int PAGE = 200;

    private NuGetEnumeration() {
    }

    /**
     * @param allowInternal the deployment's {@link build.jenesis.repository.blobs.ProxyLeg#ALLOW_INTERNAL} dial, the
     *     one the proxy leg reads, screened by {@link build.jenesis.repository.blobs.OutboundTargets}: off, a
     *     cross-origin hop must be {@code https} and public; a hop on the upstream's own origin is trusted either way
     */
    public static Stream<Map.Entry<String, URI>> enumerate(ProxyFormat.Fetcher fetcher, URI upstream,
                                                           boolean allowInternal) throws IOException {
        String base = upstream.toString();
        URI root = URI.create(base.endsWith("/") ? base : base + "/");
        JsonNode index = MAPPER.readTree(fetch(fetcher, root.resolve("v3/index.json")));
        String flat = null;
        String catalog = null;
        String search = null;
        for (JsonNode resource : index.path("resources")) {
            String type = resource.path("@type").asString("");
            String id = resource.path("@id").asString(null);
            if (id == null) {
                continue;
            }
            if (type.equals("PackageBaseAddress/3.0.0") && flat == null) {
                flat = id.endsWith("/") ? id : id + "/";
            } else if (type.startsWith("Catalog/") && catalog == null) {
                catalog = id;
            } else if (type.startsWith("SearchQueryService") && search == null) {
                search = id;
            }
        }
        if (flat == null) {
            throw new IOException("No flat container (PackageBaseAddress) advertised by " + root.resolve("v3/index.json"));
        }
        // Every @id the service index advertises is untrusted upstream content fetched server-side, so each is screened
        // before any fetch; the flat container also anchors the version-list and download URLs emitted below.
        URI container = URI.create(flat);
        if (!OutboundTargets.mayFollow(container, root, allowInternal)) {
            throw new IOException("Refusing a flat container at a private host: " + container);
        }
        Stream<String> ids;
        if (catalog != null) {
            URI service = root.resolve(catalog);
            if (!OutboundTargets.mayFollow(service, root, allowInternal)) {
                throw new IOException("Refusing a catalog service at a private host: " + service);
            }
            ids = catalogIds(fetcher, service, root, allowInternal);
        } else if (search != null) {
            URI service = root.resolve(search);
            if (!OutboundTargets.mayFollow(service, root, allowInternal)) {
                throw new IOException("Refusing a search service at a private host: " + service);
            }
            ids = searchIds(fetcher, service);
        } else {
            throw new IOException("Not enumerable: neither a catalog nor a search service advertised by "
                    + root.resolve("v3/index.json"));
        }
        Set<String> seen = new HashSet<>();
        return ids.map(id -> id.toLowerCase(Locale.ROOT))
                .filter(seen::add)
                .flatMap(id -> {
                    try {
                        return versions(fetcher, container, id).stream();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    /** Every {@code nuget:id} the catalog's pages carry, page by page as the stream advances. Ids repeat across publish
     *  events; the flat-container read decides what survives. */
    private static Stream<String> catalogIds(ProxyFormat.Fetcher fetcher, URI catalog, URI upstream,
                                            boolean allowInternal) throws IOException {
        JsonNode top = MAPPER.readTree(fetch(fetcher, catalog));
        List<URI> pages = new ArrayList<>();
        for (JsonNode item : top.path("items")) {
            String id = item.path("@id").asString(null);
            if (id != null) {
                URI page = catalog.resolve(id);
                // A catalog page @id is untrusted upstream content; one the screen refuses is skipped, not fetched.
                if (OutboundTargets.mayFollow(page, upstream, allowInternal)) {
                    pages.add(page);
                }
            }
        }
        return pages.stream().flatMap(page -> {
            try {
                List<String> ids = new ArrayList<>();
                for (JsonNode item : MAPPER.readTree(fetch(fetcher, page)).path("items")) {
                    String id = item.path("nuget:id").asString(null);
                    if (id != null) {
                        ids.add(id);
                    }
                }
                return ids.stream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** Every id the search service reports for the empty query, paged by {@code skip}/{@code take} lazily. */
    private static Stream<String> searchIds(ProxyFormat.Fetcher fetcher, URI search) {
        Iterator<List<String>> pages = new Iterator<>() {
            private int skip;
            private boolean done;

            @Override
            public boolean hasNext() {
                return !done;
            }

            @Override
            public List<String> next() {
                if (done) {
                    throw new NoSuchElementException();
                }
                try {
                    String separator = search.toString().contains("?") ? "&" : "?";
                    JsonNode page = MAPPER.readTree(fetch(fetcher,
                            URI.create(search + separator + "q=&skip=" + skip + "&take=" + PAGE)));
                    List<String> ids = new ArrayList<>();
                    for (JsonNode hit : page.path("data")) {
                        String id = hit.path("id").asString(null);
                        if (id != null) {
                            ids.add(id);
                        }
                    }
                    skip += PAGE;
                    done = ids.isEmpty() || skip >= page.path("totalHits").asInt(0);
                    return ids;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(pages, Spliterator.ORDERED), false)
                .flatMap(List::stream);
    }

    /** One id's surviving versions from its flat-container {@code index.json}; a {@code 404}, a package deleted since
     *  the catalog recorded it, contributes nothing. */
    private static List<Map.Entry<String, URI>> versions(ProxyFormat.Fetcher fetcher, URI container, String id)
            throws IOException {
        URI url = URI.create(container + id + "/index.json");
        Optional<ProxyFormat.Fetched> fetched = fetcher.fetch(url, Map.of());
        if (fetched.isEmpty()) {
            throw ProxyFormat.Unavailable.noResponse(url);
        }
        if (fetched.get().status() == 404) {
            return List.of();
        }
        if (fetched.get().status() != 200) {
            throw ProxyFormat.Unavailable.status(url, fetched.get().status());
        }
        List<Map.Entry<String, URI>> entries = new ArrayList<>();
        for (JsonNode version : MAPPER.readTree(new String(fetched.get().body(), StandardCharsets.UTF_8))
                .path("versions")) {
            String text = version.asString(null);
            if (text == null) {
                continue;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            String file = id + "." + lower + ".nupkg";
            entries.add(Map.entry(id + "/" + lower + "/" + file,
                    URI.create(container + id + "/" + lower + "/" + file)));
        }
        return entries;
    }


    private static String fetch(ProxyFormat.Fetcher fetcher, URI url) throws IOException {
        return new String(ProxyFormat.Fetcher.required(fetcher, url).body(), StandardCharsets.UTF_8);
    }
}
