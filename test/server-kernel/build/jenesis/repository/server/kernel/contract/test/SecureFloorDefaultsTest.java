package build.jenesis.repository.server.kernel.contract.test;

import module org.junit.jupiter.api;
import build.jenesis.repository.server.RepositoryProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The secure-by-default floor a shipped server boots with, as far as
 * {@link RepositoryProperties} carries it: the request-rate ceiling and the immaturity hold. Both are field
 * defaults, so they floor every deployment - embedders and tests included - and both stay an operator override
 * away from off, which is what makes floor-on acceptable rather than a forced value.
 *
 * <p>The compliance feeds are not part of the floor. A public network feed is opt-in by convention
 * ({@code FeatureConventionTest} states why), and a default-properties map that only one launcher applied would be a
 * default no shipped image has: a default only one composition applies is not a default.
 */
class SecureFloorDefaultsTest {

    @Test
    void this_edition_carries_the_free_cores_request_rate_floor_rather_than_one_of_its_own() {
        // The value is the decision and is asserted there. What this checks is that no module ships its own
        // ceiling against the core's default, so the posture a deployment gets does not depend on which image it
        // runs. An edition adds capability - the per-tenant ceiling, the screen, the trail - it does not change what
        // the core decided.
        assertThat(new RepositoryProperties().getRateLimit())
                .as("the kernel must not re-flip the core's floor")
                .isEqualTo(build.jenesis.repository.server.RepositoryProperties.DEFAULT_RATE_LIMIT);
    }

    @Test
    void a_small_immaturity_hold_window_replaces_the_no_hold_default() {
        // 0 means no hold; the floor holds a freshly-published upstream pull-through (a typosquat/compromise vehicle)
        // for review over the highest-risk window. Small (2 days) and QUARANTINE, not REJECT, and fail-open (only bites
        // on an upstream Last-Modified date), so a legitimate fresh release is only briefly held and an operator can
        // release it on review, raise the window, or set 0 to disable. A field default (like the rate ceiling), since
        // the hold makes no outbound call and so floors embedders too, not just the packaged main() entrypoint.
        assertThat(new RepositoryProperties().getImmaturityHoldDays())
                .as("a fresh deployment holds brand-new pull-throughs briefly for review").isEqualTo(2);
    }
}
