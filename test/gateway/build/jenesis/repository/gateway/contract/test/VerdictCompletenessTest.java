package build.jenesis.repository.gateway.contract.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.gateway.VerdictSection;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A pinned verdict carries how completely the screen looked, and a raised ceiling invalidates one it cut short.
 *
 * <p>The verdict pinned <em>what</em> was decided about a digest and nothing about <em>how much of it</em> was read.
 * So an ALLOW reached while the full-body ceiling was 64 MiB was reused digest-exactly after an operator raised
 * that ceiling - and raising it is the single change that would have let the screen finish. The artifact whose tail
 * had never been inspected was exactly the one the raise was meant to inspect, and exactly the one guaranteed not
 * to be re-screened. This is the same shape as the ticket it came from: a fact about how completely we looked not
 * travelling with the answer.
 */
class VerdictCompletenessTest {

    private static final String DIGEST = "a".repeat(64);
    private static final long SMALL = 64L * 1024 * 1024;
    private static final long RAISED = 256L * 1024 * 1024;

    private static VerdictSection.Recorded allowedAt(long limit) {
        return new VerdictSection.Recorded("sha256:" + DIGEST, Verdict.ALLOW, null, Instant.EPOCH,
                "hardened/full-body", "http://upstream/x", List.of(), limit);
    }

    @Test
    void a_verdict_reached_at_the_current_ceiling_is_reused() {
        assertThat(allowedAt(SMALL).allows(DIGEST, SMALL))
                .as("nothing changed, so the screen that ran is the screen this deployment would run")
                .isTrue();
    }

    @Test
    void a_verdict_reached_under_a_lower_ceiling_is_not_reused_once_the_ceiling_is_raised() {
        assertThat(allowedAt(SMALL).allows(DIGEST, RAISED))
                .as("the raise is the change that would have let the screen finish, so it must invalidate the "
                        + "answer the old ceiling produced rather than be the one thing that cannot")
                .isFalse();
    }

    @Test
    void a_verdict_reached_under_a_higher_ceiling_is_still_reused_when_the_ceiling_is_lowered() {
        assertThat(allowedAt(RAISED).allows(DIGEST, SMALL))
                .as("looking further than this deployment now looks is not a reason to look again")
                .isTrue();
    }

    @Test
    void a_record_written_before_completeness_travelled_re_screens_once() {
        assertThat(allowedAt(0L).allows(DIGEST, SMALL))
                .as("an unknown ceiling is not a satisfied one - it re-screens, which is fail-closed and costs one "
                        + "inspection")
                .isFalse();
        assertThat(allowedAt(0L).allows(DIGEST, 0L))
                .as("and with no ceiling in force either, there is nothing to be incomplete against")
                .isTrue();
    }

    @Test
    void completeness_never_rescues_a_verdict_that_was_not_an_allow_over_these_bytes() {
        // The completeness test is an extra condition, not a replacement: it must not widen reuse.
        VerdictSection.Recorded refused = new VerdictSection.Recorded("sha256:" + DIGEST, Verdict.REJECT, "why",
                Instant.EPOCH, "hardened/full-body", "http://upstream/x", List.of(), RAISED);
        assertThat(refused.allows(DIGEST, SMALL)).as("a refusal is never reused, however completely it looked")
                .isFalse();
        assertThat(allowedAt(RAISED).allows("b".repeat(64), SMALL))
                .as("nor is an allow over different bytes").isFalse();
    }
}
