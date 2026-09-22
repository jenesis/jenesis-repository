package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.gateway.testkit.FormatDrive.Call;
import build.jenesis.repository.gateway.testkit.FormatDrive.MemStore;
import build.jenesis.repository.gateway.testkit.FormatDrive;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code GET /debian/dists/} suite autoindex must HTML-escape each suite name. A suite is the first path segment of
 * a publish ({@code debian/<suite>/pool/...}), gated only by {@code Keys.unsafe}, which blocks {@code /} and control
 * chars but permits {@code < > " &}. Concatenated raw into this {@code text/html} page it would be a stored XSS - a
 * suite like {@code a"><img src=x onerror=...>} would execute in the gateway origin for anyone who opens
 * {@code /debian/dists/}. Container-free: the suite's index entry is seeded directly and the page is driven through the
 * format seam.
 */
class DebianSuiteIndexEscapingTest {

    @Test
    void the_dists_autoindex_html_escapes_the_suite_name() throws IOException {
        RepositoryFormat debian = FormatDrive.format("debian");
        MemStore store = new MemStore();
        // An XSS-carrying suite (no '/', which Keys.unsafe would block anyway) with an index entry so the autoindex
        // lists it. The payload is a self-firing <img onerror>, escaping the href attribute via a stray double-quote.
        String suite = "bad\"><img src=x onerror=alert(1)>";
        store.objects.put("debian/" + suite + "/index/main/binary-amd64/Packages", new byte[]{'x'});

        Call call = new Call("GET", "/debian/dists/");
        debian.handle(call, store);

        assertThat(call.status).isEqualTo(200);
        String page = new String(call.body(), StandardCharsets.UTF_8);
        assertThat(page).as("the raw payload must never appear unescaped in the served HTML")
                .doesNotContain("\"><img src=x onerror=alert(1)>");
        assertThat(page).as("the suite name is HTML-escaped in both the href and the link text")
                .contains("bad&quot;&gt;&lt;img src=x onerror=alert(1)&gt;");
    }
}
