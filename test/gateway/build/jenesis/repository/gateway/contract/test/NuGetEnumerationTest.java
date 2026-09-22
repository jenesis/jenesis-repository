package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.nuget.NuGetEnumeration;
import build.jenesis.repository.gateway.testkit.CannedFetcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The NuGet V3 walk over canned documents - no server, no network: the service index names the resources; ids come
 * from the catalog where one is advertised (repeated publish events deduped, a since-deleted package's {@code 404}
 * flat-container read contributing nothing) or from the search service otherwise; each id's flat-container
 * {@code index.json} supplies the versions, lowercased per the flat-container convention; and a service index
 * advertising neither discovery resource fails eagerly with the honest constraint.
 */
class NuGetEnumerationTest {

    private static final String BASE = "http://source.local/nuget";

    @Test
    void the_catalog_discovers_ids_and_the_flat_container_supplies_the_truth() throws IOException {
        CannedFetcher fetcher = new CannedFetcher()
                .on(BASE + "/v3/index.json", 200, """
                        {"version":"3.0.0","resources":[
                          {"@id":"%s/v3-flatcontainer/","@type":"PackageBaseAddress/3.0.0"},
                          {"@id":"%s/v3/catalog0/index.json","@type":"Catalog/3.0.0"}]}""".formatted(BASE, BASE))
                .on(BASE + "/v3/catalog0/index.json", 200, """
                        {"items":[{"@id":"%s/v3/catalog0/page0.json"}]}""".formatted(BASE))
                .on(BASE + "/v3/catalog0/page0.json", 200, """
                        {"items":[
                          {"nuget:id":"Acme.Lib","nuget:version":"1.0.0","@type":"nuget:PackageDetails"},
                          {"nuget:id":"Acme.Lib","nuget:version":"2.0.0-Beta","@type":"nuget:PackageDetails"},
                          {"nuget:id":"Gone.Pkg","nuget:version":"1.0.0","@type":"nuget:PackageDetails"}]}""")
                .on(BASE + "/v3-flatcontainer/acme.lib/index.json", 200,
                        "{\"versions\":[\"1.0.0\",\"2.0.0-Beta\"]}")
                .on(BASE + "/v3-flatcontainer/gone.pkg/index.json", 404, "");

        List<Map.Entry<String, URI>> entries = NuGetEnumeration
                .enumerate(fetcher, URI.create(BASE), false).toList();

        assertThat(entries).containsExactly(
                Map.entry("acme.lib/1.0.0/acme.lib.1.0.0.nupkg",
                        URI.create(BASE + "/v3-flatcontainer/acme.lib/1.0.0/acme.lib.1.0.0.nupkg")),
                Map.entry("acme.lib/2.0.0-beta/acme.lib.2.0.0-beta.nupkg",
                        URI.create(BASE + "/v3-flatcontainer/acme.lib/2.0.0-beta/acme.lib.2.0.0-beta.nupkg")));
        assertThat(fetcher.urls.stream().filter((BASE + "/v3-flatcontainer/acme.lib/index.json")::equals))
                .as("a repeatedly published id is read once")
                .hasSize(1);
    }

    @Test
    void the_search_service_stands_in_when_no_catalog_is_advertised() throws IOException {
        CannedFetcher fetcher = new CannedFetcher()
                .on(BASE + "/v3/index.json", 200, """
                        {"version":"3.0.0","resources":[
                          {"@id":"%s/v3-flatcontainer/","@type":"PackageBaseAddress/3.0.0"},
                          {"@id":"%s/v3/search","@type":"SearchQueryService"}]}""".formatted(BASE, BASE))
                .on(BASE + "/v3/search?q=&skip=0&take=200", 200,
                        "{\"totalHits\":1,\"data\":[{\"id\":\"acme.lib\",\"version\":\"1.0.0\"}]}")
                .on(BASE + "/v3-flatcontainer/acme.lib/index.json", 200, "{\"versions\":[\"1.0.0\"]}");

        List<Map.Entry<String, URI>> entries = NuGetEnumeration
                .enumerate(fetcher, URI.create(BASE), false).toList();

        assertThat(entries).extracting(Map.Entry::getKey).containsExactly("acme.lib/1.0.0/acme.lib.1.0.0.nupkg");
    }

    @Test
    void the_search_service_walk_follows_the_pagination_continuation_across_pages() throws IOException {
        // The search walk pages skip += 200 until skip >= totalHits; a totalHits past one page must drive a second
        // fetch at skip=200, or every id beyond the first page is invisible to the migration. Existing coverage cans
        // a single page (totalHits=1), so the continuation itself was never exercised.
        CannedFetcher fetcher = new CannedFetcher()
                .on(BASE + "/v3/index.json", 200, """
                        {"version":"3.0.0","resources":[
                          {"@id":"%s/v3-flatcontainer/","@type":"PackageBaseAddress/3.0.0"},
                          {"@id":"%s/v3/search","@type":"SearchQueryService"}]}""".formatted(BASE, BASE))
                .on(BASE + "/v3/search?q=&skip=0&take=200", 200,
                        "{\"totalHits\":201,\"data\":[{\"id\":\"acme.one\"}]}")
                .on(BASE + "/v3/search?q=&skip=200&take=200", 200,
                        "{\"totalHits\":201,\"data\":[{\"id\":\"acme.two\"}]}")
                .on(BASE + "/v3-flatcontainer/acme.one/index.json", 200, "{\"versions\":[\"1.0.0\"]}")
                .on(BASE + "/v3-flatcontainer/acme.two/index.json", 200, "{\"versions\":[\"1.0.0\"]}");

        List<Map.Entry<String, URI>> entries = NuGetEnumeration
                .enumerate(fetcher, URI.create(BASE), false).toList();

        assertThat(entries).extracting(Map.Entry::getKey)
                .as("ids from both search pages are enumerated, so a page-two id is not invisible to the walk")
                .containsExactlyInAnyOrder(
                        "acme.one/1.0.0/acme.one.1.0.0.nupkg", "acme.two/1.0.0/acme.two.1.0.0.nupkg");
        assertThat(fetcher.urls)
                .as("the walk actually followed the continuation to the second page")
                .contains(BASE + "/v3/search?q=&skip=200&take=200");
    }

    @Test
    void a_catalog_service_at_a_private_host_is_refused_and_never_fetched() {
        // A hostile/compromised/MITM'd upstream v3/index.json advertises the Catalog @id as an ABSOLUTE URL at the
        // cloud metadata service (169.254.169.254, link-local). java.net.URI.resolve returns an absolute argument as-is,
        // and the catalog is fetched server-side by the walk, so once enumerate() is wired that GET is a live SSRF. It
        // must be refused up front by the same PrivateHosts guard the pypi/debian/rpm walks apply, and never issued.
        CannedFetcher fetcher = new CannedFetcher()
                .on(BASE + "/v3/index.json", 200, """
                        {"version":"3.0.0","resources":[
                          {"@id":"%s/v3-flatcontainer/","@type":"PackageBaseAddress/3.0.0"},
                          {"@id":"http://169.254.169.254/v3/catalog0/index.json","@type":"Catalog/3.0.0"}]}"""
                        .formatted(BASE));
        assertThatThrownBy(() -> NuGetEnumeration.enumerate(fetcher, URI.create(BASE), false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("private host");
        assertThat(fetcher.urls)
                .as("no server-side GET is ever issued to the link-local metadata endpoint")
                .noneMatch(url -> url.contains("169.254.169.254"));
    }

    @Test
    void a_flat_container_at_a_private_host_is_refused_and_never_fetched() {
        // The PackageBaseAddress (flat-container) @id anchors every version-list and download URL the walk fetches and
        // emits; an absolute value at a private host is the same SSRF, so it too is refused before any fetch.
        CannedFetcher fetcher = new CannedFetcher()
                .on(BASE + "/v3/index.json", 200, """
                        {"version":"3.0.0","resources":[
                          {"@id":"http://169.254.169.254/v3-flatcontainer/","@type":"PackageBaseAddress/3.0.0"},
                          {"@id":"%s/v3/catalog0/index.json","@type":"Catalog/3.0.0"}]}"""
                        .formatted(BASE));
        assertThatThrownBy(() -> NuGetEnumeration.enumerate(fetcher, URI.create(BASE), false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("private host");
        assertThat(fetcher.urls)
                .as("no server-side GET is ever issued to the link-local metadata endpoint")
                .noneMatch(url -> url.contains("169.254.169.254"));
    }

    @Test
    void a_catalog_page_at_a_private_host_is_skipped_while_the_public_page_proceeds() throws IOException {
        // A hostile catalog index lists two page @ids: one on the public upstream, one absolute at the metadata
        // service. The private page must be screened out (never fetched) while the public page's ids proceed to the
        // flat-container read - the per-page analogue of rpm's per-<package> skip, proving a normal public @id still
        // enumerates.
        CannedFetcher fetcher = new CannedFetcher()
                .on(BASE + "/v3/index.json", 200, """
                        {"version":"3.0.0","resources":[
                          {"@id":"%s/v3-flatcontainer/","@type":"PackageBaseAddress/3.0.0"},
                          {"@id":"%s/v3/catalog0/index.json","@type":"Catalog/3.0.0"}]}""".formatted(BASE, BASE))
                .on(BASE + "/v3/catalog0/index.json", 200, """
                        {"items":[
                          {"@id":"%s/v3/catalog0/page0.json"},
                          {"@id":"http://169.254.169.254/v3/catalog0/evil.json"}]}""".formatted(BASE))
                .on(BASE + "/v3/catalog0/page0.json", 200, """
                        {"items":[{"nuget:id":"Acme.Lib","nuget:version":"1.0.0","@type":"nuget:PackageDetails"}]}""")
                .on(BASE + "/v3-flatcontainer/acme.lib/index.json", 200, "{\"versions\":[\"1.0.0\"]}");

        List<Map.Entry<String, URI>> entries = NuGetEnumeration
                .enumerate(fetcher, URI.create(BASE), false).toList();

        assertThat(entries).extracting(Map.Entry::getKey)
                .as("the public catalog page's id enumerates; the private page contributes nothing")
                .containsExactly("acme.lib/1.0.0/acme.lib.1.0.0.nupkg");
        assertThat(fetcher.urls)
                .as("the catalog page at the metadata endpoint is never fetched, while the public page is")
                .noneMatch(url -> url.contains("169.254.169.254"));
        assertThat(fetcher.urls)
                .as("the public catalog page was followed")
                .contains(BASE + "/v3/catalog0/page0.json");
    }

    @Test
    void a_service_index_without_a_discovery_resource_fails_with_the_honest_constraint() {
        CannedFetcher fetcher = new CannedFetcher()
                .on(BASE + "/v3/index.json", 200, """
                        {"version":"3.0.0","resources":[
                          {"@id":"%s/v3-flatcontainer/","@type":"PackageBaseAddress/3.0.0"}]}""".formatted(BASE));
        assertThatThrownBy(() -> NuGetEnumeration.enumerate(fetcher, URI.create(BASE), false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Not enumerable");
    }
}
