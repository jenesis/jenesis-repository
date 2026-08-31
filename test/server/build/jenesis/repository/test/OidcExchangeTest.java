package build.jenesis.repository.test;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.oidc.OidcExchange;
import build.jenesis.repository.server.spi.TokenExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import module org.junit.jupiter.api;

import module java.base;
import java.security.Signature;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The OIDC exchange end to end against a real signature: a temporary RSA key signs a JWT, the public key is served as
 * a JWKS over a local HTTP server, and the exchange is driven for the success, tampered-signature, expired,
 * wrong-issuer and unmatched-subject cases - so the verification path (discovery, JWKS, RS256, claim matching) is
 * exercised, not stubbed.
 */
class OidcExchangeTest {

    /** Discovery attempts the fixture makes before giving up; a starved box can lose one to a read timeout. */
    private static final int WARM_ATTEMPTS = 6;

    /**
     * How long to wait after a failed warm-up before trying again, doubling from here.
     *
     * <p><b>Why there is a wait at all, and why it is this long.</b> The loop below used to retry three times with
     * no pause between them, which is close to not retrying: the failure it recovers from is a read timeout on a
     * saturated machine, and three attempts fired within microseconds all meet the same saturation. Measured
     * 2026-08-31 on a cold full lane - 153 suites, ~59 forked JVMs - this suite failed exactly that way while
     * passing on the next run of the same tree.
     *
     * <p>Deliberately not {@code Retries.backoff}, which caps at a hundred milliseconds: that one is tuned for a
     * lost compare-and-set, where the peer is expected to be gone almost immediately. This waits for a load spike
     * to pass, which is a different timescale - 250 ms doubling to four seconds, about eight seconds in total
     * across all attempts, paid only when the machine is genuinely too busy to answer a localhost GET.
     */
    private static final long WARM_BACKOFF_MILLIS = 250;

    @TempDir
    Path root;

    private Authorization authorization;
    private OidcExchange exchange;
    private WireMockServer server;
    private String issuer;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        authorization = Authorization.enforcing(store);
        exchange = new OidcExchange(authorization);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();

        server = new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
        server.start();
        issuer = "http://127.0.0.1:" + server.port();
        server.stubFor(any(urlPathEqualTo("/.well-known/openid-configuration"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{"
                        + "\"issuer\":\"" + issuer + "\","
                        + "\"authorization_endpoint\":\"" + issuer + "/authorize\","
                        + "\"token_endpoint\":\"" + issuer + "/token\","
                        + "\"jwks_uri\":\"" + issuer + "/jwks\","
                        + "\"response_types_supported\":[\"id_token\"],"
                        + "\"subject_types_supported\":[\"public\"],"
                        + "\"id_token_signing_alg_values_supported\":[\"RS256\"]}")));
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        server.stubFor(any(urlPathEqualTo("/jwks"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\",\"n\":\""
                                + unsigned(publicKey.getModulus()) + "\",\"e\":\""
                                + unsigned(publicKey.getPublicExponent()) + "\"}]}")));

        authorization.setTrust("acme", new Authorization.Trust("github", issuer, "jenesis",
                "repo:acme/app:*", "releases", "repository:read,repository:write", Duration.ofMinutes(15)));
        warmTheDecoder();
    }

    /**
     * Performs the OIDC discovery fetch here, in the fixture, so that no test's assertion depends on it.
     *
     * <p><b>Why this exists.</b> {@code OidcExchange} builds a decoder per issuer on first use, and building one
     * means Spring Security fetching {@code /.well-known/openid-configuration} and then the JWKS over HTTP. Every
     * test here gets a fresh exchange and a fresh stub, so every test used to pay that round-trip inside its own
     * assertion. Measured once on a whole-tree run: the GET to <em>127.0.0.1</em> exceeded Spring's read timeout
     * and the suite failed with {@code SocketTimeoutException: Read timed out} wrapped in the exchange's
     * fail-closed arm - which is the product behaving exactly as designed, reported against a test that was really
     * asserting something else (clock-skew tolerance, in that instance).
     *
     * <p>So the round-trip moves here, where it is what it actually is: fixture setup, and legitimately retryable
     * because a slow local stub is not a claim about the product. The retry <em>backs off</em>, which it did not
     * originally: three immediate tries meet the same saturation that caused the timeout, so they recovered about
     * as often as one did. Afterwards the decoder is cached for the issuer
     * and every assertion below runs against a warm one, so none of them can time out on discovery at all. This is
     * structural rather than a mitigation - it removes the dependency instead of making it less likely.
     *
     * <p>The warm-up token is genuinely signed by this issuer's key but carries an audience the trust does not
     * name, so the exchange decodes it, matches no trust, and mints nothing - the fixture is not left holding a
     * credential the tests would then have to account for.
     */
    private void warmTheDecoder() {
        String warm = jwt("not-the-trusts-audience", "repo:acme/app:ci", Instant.now().plusSeconds(300));
        IOException last = null;
        for (int attempt = 1; attempt <= WARM_ATTEMPTS; attempt++) {
            try {
                exchange.exchange("acme", warm);
                return;
            } catch (IOException slow) {
                last = slow;
                if (attempt < WARM_ATTEMPTS) {
                    try {
                        // Backed off, not bunched. Without this the retries all land inside the same load spike
                        // that caused the timeout, so three tries recover about as often as one.
                        Thread.sleep(WARM_BACKOFF_MILLIS << (attempt - 1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted while warming the decoder", interrupted);
                    }
                }
            }
        }
        throw new IllegalStateException("the issuer stub did not answer OIDC discovery in " + WARM_ATTEMPTS
                + " attempts, so no test below could have verified a token either", last);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void a_valid_token_is_exchanged_for_a_short_lived_key_carrying_the_trusts_grant() throws IOException {
        TokenExchange.Exchanged exchanged = exchange.exchange("acme",
                jwt("jenesis", "repo:acme/app:ref:refs/heads/main", Instant.now().plusSeconds(300)));
        assertThat(exchanged).isNotNull();
        assertThat(exchanged.trust()).isEqualTo("github");
        assertThat(exchanged.expires()).isAfter(Instant.now()).isBefore(Instant.now().plus(Duration.ofMinutes(16)));
        assertThat(authorization.authorize(exchanged.key(), "releases", Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
    }

    @Test
    void a_tampered_signature_is_rejected() throws IOException {
        String token = jwt("jenesis", "repo:acme/app:ref:refs/heads/main", Instant.now().plusSeconds(300));
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
        assertThat(exchange.exchange("acme", tampered)).as("a forged signature mints nothing").isNull();
    }

    @Test
    void an_expired_token_a_foreign_audience_and_a_wrong_subject_are_all_rejected() throws IOException {
        assertThat(exchange.exchange("acme",
                jwt("jenesis", "repo:acme/app:ref:refs/heads/main", Instant.now().minusSeconds(300))))
                .as("expired").isNull();
        assertThat(exchange.exchange("acme",
                jwt("someone-else", "repo:acme/app:ref:refs/heads/main", Instant.now().plusSeconds(300))))
                .as("wrong audience").isNull();
        assertThat(exchange.exchange("acme",
                jwt("jenesis", "repo:evil/app:ref:refs/heads/main", Instant.now().plusSeconds(300))))
                .as("subject outside the pattern").isNull();
    }

    @Test
    void a_token_matching_only_the_second_of_a_tenants_trusts_is_exchanged_against_that_trust() throws IOException {
        // The reason exchange() is a loop: a tenant can carry several trusts, and a token that does not match the
        // first (here by audience) must fall through to a later one that does. Provision a decoy trust ordered before
        // "github" (trusts iterate by name) whose audience the token does not carry; the token's audience matches only
        // "github", so it must be exchanged against "github" - carrying github's grant, not the decoy's.
        authorization.setTrust("acme", new Authorization.Trust("aaa-decoy", issuer, "other-audience",
                "repo:acme/app:*", "wrong-scope", "repository:read", Duration.ofMinutes(15)));

        TokenExchange.Exchanged exchanged = exchange.exchange("acme",
                jwt("jenesis", "repo:acme/app:ref:refs/heads/main", Instant.now().plusSeconds(300)));

        assertThat(exchanged).as("the token fell through the decoy and matched the second trust").isNotNull();
        assertThat(exchanged.trust()).as("exchanged against the trust it actually matched").isEqualTo("github");
        assertThat(authorization.authorize(exchanged.key(), "releases", Authorization.REPOSITORY_WRITE))
                .as("the minted key carries the matched trust's grant, not the decoy's").isEqualTo(Authorization.Decision.ALLOWED);

        // And a token matching only the decoy's audience is exchanged against the decoy - proving both trusts are live
        // and the loop selects by the token, not by iteration order alone.
        TokenExchange.Exchanged viaDecoy = exchange.exchange("acme",
                jwt("other-audience", "repo:acme/app:ci", Instant.now().plusSeconds(300)));
        assertThat(viaDecoy).isNotNull();
        assertThat(viaDecoy.trust()).isEqualTo("aaa-decoy");
    }

    @Test
    void a_tenant_with_no_trust_for_the_issuer_exchanges_nothing() throws IOException {
        assertThat(exchange.exchange("globex",
                jwt("jenesis", "repo:acme/app:ref:refs/heads/main", Instant.now().plusSeconds(300))))
                .as("no trust for this tenant").isNull();
    }

    @Test
    void an_unsigned_none_algorithm_token_is_rejected() throws IOException {
        String token = header("none", "k1") + "." + body("\"jenesis\"", Instant.now().plusSeconds(300))
                + "." + b64(new byte[]{1, 2, 3});
        assertThat(exchange.exchange("acme", token)).as("alg=none mints nothing").isNull();
    }

    @Test
    void an_hs256_token_signed_with_the_public_key_is_rejected() throws Exception {
        String header = header("HS256", "k1");
        String body = body("\"jenesis\"", Instant.now().plusSeconds(300));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(keyPair.getPublic().getEncoded(), "HmacSHA256"));
        String forged = header + "." + body + "."
                + b64(mac.doFinal((header + "." + body).getBytes(StandardCharsets.US_ASCII)));
        assertThat(exchange.exchange("acme", forged)).as("an algorithm-confusion token mints nothing").isNull();
    }

    @Test
    void a_token_signed_by_a_key_outside_the_jwks_is_rejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PrivateKey foreign = generator.generateKeyPair().getPrivate();
        assertThat(exchange.exchange("acme",
                rs256(header("RS256", "k1"), body("\"jenesis\"", Instant.now().plusSeconds(300)), foreign)))
                .as("a foreign signing key mints nothing").isNull();
        assertThat(exchange.exchange("acme",
                rs256(header("RS256", "rotated"), body("\"jenesis\"", Instant.now().plusSeconds(300)), keyPair.getPrivate())))
                .as("a kid absent from the JWKS mints nothing").isNull();
    }

    @Test
    void an_array_audience_is_matched_by_membership() throws IOException {
        assertThat(exchange.exchange("acme", rs256(header("RS256", "k1"),
                body("[\"x\",\"jenesis\"]", Instant.now().plusSeconds(300)), keyPair.getPrivate())))
                .as("the trust audience present in the array is accepted").isNotNull();
        assertThat(exchange.exchange("acme", rs256(header("RS256", "k1"),
                body("[\"x\",\"y\"]", Instant.now().plusSeconds(300)), keyPair.getPrivate())))
                .as("an array without the trust audience is rejected").isNull();
    }

    @Test
    void an_issuer_that_cannot_be_reached_is_not_reported_as_an_invalid_token() {
        // "I could not verify this" and "this token is not valid" were the same answer: the decode was wrapped in
        // catch (RuntimeException) { continue; }, and fromIssuerLocation performs OIDC discovery and fetches a JWKS
        // over the network, so a timeout, a 5xx or an unreachable issuer arrived here and left as null - which is
        // exactly what a forged token produces. An operator could not tell an outage from an attack, and a loaded
        // machine could turn a healthy token into a rejection.
        //
        // This is why the cell above was flaky rather than wrong: under a full build the discovery call is slow
        // enough to fail, and the failure was indistinguishable from the expiry it was asserting about.
        String valid = rs256(header("RS256", "k1"), body("\"jenesis\"", Instant.now().plusSeconds(300)),
                keyPair.getPrivate());
        server.stop();   // the issuer goes away AFTER the trust was registered, as an outage would

        // A COLD exchange, not the fixture's: building the decoder is precisely what must fail here, and the
        // fixture's has already built one against the issuer while it was still up. Warming it is what keeps every
        // other test in this class off the discovery round-trip (see warmTheDecoder) - this is the one test that
        // needs the round-trip to happen, and to fail.
        OidcExchange cold = new OidcExchange(authorization);
        assertThatThrownBy(() -> cold.exchange("acme", valid))
                .as("an unreachable issuer is an infrastructure failure, not a judgement about the token: it must "
                        + "reach the caller rather than degrade into the null a forged token produces")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("infrastructure failure");
    }

    @Test
    void the_fixture_warms_the_decoder_so_that_no_assertion_below_pays_for_discovery() throws IOException {
        // The falsifier for warmTheDecoder, and the reason it can be trusted without reproducing the flake it
        // exists for: after the fixture has warmed, an exchange must reach the issuer's discovery endpoint ZERO
        // further times. Remove the warm-up and this is 0 before and 1 after, so it fails - which is exactly the
        // per-assertion round-trip that timed out on a loaded machine.
        long before = discoveryRequests();
        assertThat(before).as("the fixture's warm-up really did perform discovery").isPositive();

        exchange.exchange("acme", jwt("jenesis", "repo:acme/app:ref:refs/heads/main",
                Instant.now().plusSeconds(300)));

        assertThat(discoveryRequests())
                .as("an exchange after the warm-up re-fetched the issuer's configuration; every assertion in this "
                        + "class then depends on a live HTTP round-trip completing inside Spring's read timeout, "
                        + "which is what made this suite flaky under a whole-tree build")
                .isEqualTo(before);
    }

    /** How many times the issuer stub has served its OIDC discovery document. */
    private long discoveryRequests() {
        return server.getAllServeEvents().stream()
                .filter(event -> event.getRequest().getUrl().contains("well-known"))
                .count();
    }

    @Test
    void expiry_tolerates_small_clock_skew_but_not_a_stale_token() throws IOException {
        assertThat(exchange.exchange("acme", rs256(header("RS256", "k1"),
                body("\"jenesis\"", Instant.now().minusSeconds(30)), keyPair.getPrivate())))
                .as("a token just past expiry is within the skew").isNotNull();
        assertThat(exchange.exchange("acme", rs256(header("RS256", "k1"),
                body("\"jenesis\"", Instant.now().minusSeconds(600)), keyPair.getPrivate())))
                .as("a long-expired token is rejected").isNull();
    }

    @Test
    void a_signed_token_with_no_audience_claim_is_rejected_cleanly_not_a_server_error() throws IOException {
        // A validly-signed token may omit aud entirely (many OPs mint tokens without it); an audience-pinned trust
        // must not match it, and must not throw either - Jwt.getAudience() is then null and the audience check runs
        // outside the decode try/catch, so an NPE would 500 the exchange and skip every later trust.
        String noAud = base64("{\"iss\":\"" + issuer + "\",\"sub\":\"repo:acme/app:ci\",\"exp\":"
                + Instant.now().plusSeconds(300).getEpochSecond() + "}");
        assertThat(exchange.exchange("acme", rs256(header("RS256", "k1"), noAud, keyPair.getPrivate())))
                .as("a token missing aud matches no audience-pinned trust and mints nothing, without a 500").isNull();
    }

    @Test
    void a_trust_created_with_a_blank_or_null_audience_is_rejected_at_creation_with_a_clear_message() {
        // Fail-fast at the single creation/parse chokepoint (the Trust canonical constructor): a trust must not exist
        // with a blank audience, because a blank audience would silently accept a token minted for any relying party.
        assertThatThrownBy(() -> new Authorization.Trust("github", issuer, "  ",
                "repo:acme/app:*", "releases", "repository:read", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .as("a blank audience names the offending trust and states the remedy")
                .hasMessageContaining("github")
                .hasMessageContaining("requires an explicit audience");
        assertThatThrownBy(() -> new Authorization.Trust("github", issuer, null,
                "repo:acme/app:*", "releases", "repository:read", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .as("a null audience is rejected the same way")
                .hasMessageContaining("requires an explicit audience");
    }

    @Test
    void a_trust_created_with_an_explicit_audience_is_accepted() {
        Authorization.Trust trust = new Authorization.Trust("github", issuer, "jenesis",
                "repo:acme/app:*", "releases", "repository:read", Duration.ofMinutes(15));
        assertThat(trust.audience()).isEqualTo("jenesis");
    }

    private String header(String algorithm, String kid) {
        return base64("{\"alg\":\"" + algorithm + "\",\"kid\":\"" + kid + "\",\"typ\":\"JWT\"}");
    }

    private String body(String audienceJson, Instant expiry) {
        return base64("{\"iss\":\"" + issuer + "\",\"aud\":" + audienceJson
                + ",\"sub\":\"repo:acme/app:ci\",\"exp\":" + expiry.getEpochSecond() + "}");
    }

    private String rs256(String header, String body, PrivateKey key) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update((header + "." + body).getBytes(StandardCharsets.US_ASCII));
            return header + "." + body + "." + b64(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String jwt(String audience, String subject, Instant expiry) {
        String header = base64("{\"alg\":\"RS256\",\"kid\":\"k1\",\"typ\":\"JWT\"}");
        String payload = base64("{\"iss\":\"" + issuer + "\",\"aud\":\"" + audience + "\",\"sub\":\"" + subject
                + "\",\"exp\":" + expiry.getEpochSecond() + "}");
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header + "." + payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
