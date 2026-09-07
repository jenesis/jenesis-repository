package build.jenesis.repository.store.gcs.test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.gcs.GcsArtifactStoreProvider;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code presign} direct-fetch seam, both branches, with no network: a service-account key is generated here
 * and written as the JSON key file the provider reads, so a scoped {@code presign(key, ttl)} mints a V4
 * {@code GOOG4-RSA-SHA256} GET whose path carries the tenant scope prefix down to the signed object, whose
 * credential names the account and the day's scope, and whose signature the key's public half verifies over the
 * string to sign recomputed from the URL itself - so a signer wired to the wrong bytes fails here, not at the
 * bucket. A store whose credential cannot sign degrades to {@link java.util.Optional#empty} so the caller streams.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GcsPresignTest {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String ACCOUNT = "signer@project.iam.gserviceaccount.com";

    @TempDir
    private Path dir;

    private WireMockServer server;

    /** The provider probes its endpoint at boot, so even a test that only signs URLs boots against the stub - which
     *  honours the probe's precondition and issues the bearer token a signing credential asks for first. */
    @BeforeAll
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("localhost").dynamicPort()
                .extensions(new JsonGcs()));
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse()));
    }

    @AfterAll
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    private Map<String, String> settings(String credentials) {
        Map<String, String> settings = new LinkedHashMap<>(JsonGcs.settings(server.port(), "repo"));
        settings.put("jenreg.gcs.credentials", credentials);
        return settings;
    }

    @Test
    void presign_mints_a_v4_signed_get_the_accounts_key_verifies() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Path key = dir.resolve("service-account.json");
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("type", "service_account");
        document.put("project_id", "project");
        document.put("private_key_id", "key-1");
        document.put("private_key", "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        document.put("client_email", ACCOUNT);
        document.put("client_id", "1");
        document.put("token_uri", "http://localhost:" + server.port() + "/token");
        Files.writeString(key, Json.write(document));
        ArtifactStore store = ArtifactStoreProvider.resolve("gcs", settings(key.toString())::get).scope("acme");

        URI url = store.presign("blobs/x", TTL).orElseThrow();

        assertThat(url.getScheme() + "://" + url.getHost() + ":" + url.getPort())
                .as("signed for the endpoint the store was booted against").isEqualTo("http://localhost:" + server.port());
        assertThat(url.getRawPath()).as("the bucket, then the scope-prefixed key").isEqualTo("/repo/acme/blobs/x");
        Map<String, String> query = query(url.getRawQuery());
        assertThat(query).containsEntry("X-Goog-Algorithm", "GOOG4-RSA-SHA256")
                .containsEntry("X-Goog-Expires", "300")
                .containsEntry("X-Goog-SignedHeaders", "host")
                .containsKeys("X-Goog-Credential", "X-Goog-Date", "X-Goog-Signature");
        String credential = URLDecoder.decode(query.get("X-Goog-Credential"), StandardCharsets.UTF_8);
        assertThat(credential).startsWith(ACCOUNT + "/").endsWith("/auto/storage/goog4_request");

        // The string to sign, recomputed from nothing but the URL - so what is verified is what a bucket would see.
        String canonicalQuery = url.getRawQuery().substring(0, url.getRawQuery().indexOf("&X-Goog-Signature="));
        // The host header carries the port when the endpoint has one, exactly as the signer spells it.
        String host = url.getHost() + (url.getPort() > 0 ? ":" + url.getPort() : "");
        String canonicalRequest = "GET\n" + url.getRawPath() + "\n" + canonicalQuery + "\nhost:" + host
                + "\n\nhost\nUNSIGNED-PAYLOAD";
        String scope = credential.substring(ACCOUNT.length() + 1);
        String stringToSign = "GOOG4-RSA-SHA256\n" + query.get("X-Goog-Date") + "\n" + scope + "\n"
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update(stringToSign.getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(HexFormat.of().parseHex(query.get("X-Goog-Signature"))))
                .as("the account's key signed exactly the request the URL presents").isTrue();
    }

    @Test
    void a_credential_that_cannot_sign_degrades_to_empty() {
        ArtifactStore store = ArtifactStoreProvider.resolve("gcs", settings(GcsArtifactStoreProvider.ANONYMOUS)::get).scope("acme");
        assertThat(store.presign("blobs/x", TTL))
                .as("no signer -> stream as today, never a signed URL").isEmpty();
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            query.put(pair.substring(0, equals), pair.substring(equals + 1));
        }
        return query;
    }
}
