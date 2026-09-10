package build.jenesis.repository.test;

import module org.junit.jupiter.api;

import build.jenesis.repository.server.RepositoryProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The secure default of the repository server's credential model: per-credential authorization is enforced out of the
 * box, and anonymous/open mode is honoured only as an explicit opt-out ({@code jenreg.auth=false}, env
 * {@code JENREG_AUTH=false}). Locks the field default so a fresh deployment never boots open silently.
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
     * The request-rate floor is this core's decision, and it is asserted here because this is where it is made.
     *
     * <p>It used to be made twice. This core shipped {@code 0} - unlimited, on the reasoning that a ceiling is an
     * operator's decision - and the downstream edition's own properties shipped {@code 6000}, on the reasoning
     * that a fresh deployment should cap a runaway client. Both javadocs argued their side sincerely, which is how
     * a difference like that survives; the effect was that the posture a deployment got depended on which image it
     * ran. An edition adds capability, it does not change what this core decided - so the floor moved here and the
     * edition now references it.
     *
     * <p>Which leaves one way for the split to come back: this core drifting to {@code 0} while the edition keeps
     * referencing a constant that has changed under it. The downstream census would still pass, because it checks
     * that the edition does not re-flip the value rather than what the value is. This is the half that says the
     * floor is still a floor.
     */
    @Test
    void a_fresh_deployment_carries_the_request_rate_floor() {
        assertThat(RepositoryProperties.DEFAULT_RATE_LIMIT)
                .as("0 would be unlimited - the floor is what caps a runaway or abusive client on a fresh deploy")
                .isEqualTo(6000L);
        assertThat(new RepositoryProperties().getRateLimit())
                .as("and the property carries it, so a deployment that configures nothing is not unlimited")
                .isEqualTo(RepositoryProperties.DEFAULT_RATE_LIMIT);
    }

    @Test
    void per_credential_authorization_is_on_by_default_and_anonymous_is_an_explicit_opt_out() {
        assertThat(new RepositoryProperties().isAuth())
                .as("the secure default: a fresh deployment enforces per-credential authorization").isTrue();

        RepositoryProperties open = new RepositoryProperties();
        open.setAuth(false);
        assertThat(open.isAuth())
                .as("anonymous/open is honoured only as an explicit opt-out (jenreg.auth=false)").isFalse();
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
