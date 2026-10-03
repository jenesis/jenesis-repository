package build.jenesis.repository.test;

import module org.junit.jupiter.api;

import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.settings.CoreDefaults;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The secure default of the repository server's credential model: per-credential authorization is enforced out of the
 * box, and anonymous/open mode is honoured only as an explicit opt-out ({@code jenrepo.auth=false}, env
 * {@code JENREPO_AUTH=false}). Locks the field default so a fresh deployment never boots open silently.
 * Also pins the hand-rolled storage-quota parser {@link RepositoryProperties#quotaBytes()}: a decimal count with an
 * optional 1024-based {@code K}/{@code M}/{@code G}/{@code T} suffix (each also spelled {@code *B} and {@code *IB}),
 * an unset/blank value meaning uncapped, and an unrecognised unit rejected.
 */
class RepositoryPropertiesTest {

    private static final long KIB = 1024L;
    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024L * 1024 * 1024;
    private static final long TIB = 1024L * 1024 * 1024 * 1024;

    private static long quotaBytes(String quota) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setQuota(quota);
        return properties.quotaBytes();
    }

    /**
     * The request-rate floor is this core's decision, and it is asserted here because this is where it is made: a
     * fresh deployment caps a client by its address, and leaves a tenant's ceiling to an operator who shares capacity
     * between tenants. Made once, so a posture cannot depend on which image a deployment runs - an edition adds
     * capability, it does not change what this core decides. The downstream census checks only that the edition does
     * not re-flip the value; this is the half that says what the value is.
     */
    @Test
    void a_fresh_deployment_caps_a_client_by_its_address_and_leaves_a_tenant_unbounded() {
        assertThat(RepositoryProperties.DEFAULT_RATE_LIMIT)
                .as("a tenant's ceiling is shared by all its credentials, so a floor there stops parallel CI")
                .isZero();
        assertThat(new RepositoryProperties().getRateLimit()).isEqualTo(RepositoryProperties.DEFAULT_RATE_LIMIT);
        assertThat(Long.parseLong(CoreDefaults.RATE_LIMIT_ADDRESS))
                .as("the floor is the address's - a runaway or abusive client is capped on a fresh deploy")
                .isEqualTo(60_000L);
    }

    @Test
    void per_credential_authorization_is_on_by_default_and_anonymous_is_an_explicit_opt_out() {
        assertThat(new RepositoryProperties().isAuth())
                .as("the secure default: a fresh deployment enforces per-credential authorization").isTrue();

        RepositoryProperties open = new RepositoryProperties();
        open.setAuth(false);
        assertThat(open.isAuth())
                .as("anonymous/open is honoured only as an explicit opt-out (jenrepo.auth=false)").isFalse();
    }

    @Test
    void an_unset_or_blank_quota_is_uncapped() {
        assertThat(new RepositoryProperties().quotaBytes()).as("unset (null) is uncapped").isZero();
        assertThat(quotaBytes("")).as("empty is uncapped").isZero();
        assertThat(quotaBytes("   ")).as("blank is uncapped").isZero();
    }

    @Test
    void a_plain_count_is_taken_as_bytes() {
        assertThat(quotaBytes("1024")).isEqualTo(1024L);
        assertThat(quotaBytes("512B")).as("an explicit B suffix is bytes").isEqualTo(512L);
    }

    @Test
    void each_1024_based_suffix_and_its_b_and_ib_spellings_scale() {
        assertThat(quotaBytes("1K")).isEqualTo(KIB);
        assertThat(quotaBytes("1KB")).isEqualTo(KIB);
        assertThat(quotaBytes("1KIB")).isEqualTo(KIB);
        assertThat(quotaBytes("1M")).isEqualTo(MIB);
        assertThat(quotaBytes("1MB")).isEqualTo(MIB);
        assertThat(quotaBytes("1MIB")).isEqualTo(MIB);
        assertThat(quotaBytes("1G")).isEqualTo(GIB);
        assertThat(quotaBytes("1GB")).isEqualTo(GIB);
        assertThat(quotaBytes("1GIB")).isEqualTo(GIB);
        assertThat(quotaBytes("1T")).isEqualTo(TIB);
        assertThat(quotaBytes("1TB")).isEqualTo(TIB);
        assertThat(quotaBytes("1TIB")).isEqualTo(TIB);
    }

    @Test
    void a_lowercase_suffix_is_accepted() {
        assertThat(quotaBytes("2gb")).isEqualTo(2 * GIB);
    }

    @Test
    void a_decimal_value_scales_by_its_suffix() {
        assertThat(quotaBytes("1.5G")).isEqualTo((long) (1.5 * GIB));
    }

    @Test
    void an_unrecognised_unit_is_rejected() {
        assertThatThrownBy(() -> quotaBytes("5X"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storage quota unit");
    }
}
