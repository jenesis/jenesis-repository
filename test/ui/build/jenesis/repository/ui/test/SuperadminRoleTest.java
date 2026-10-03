package build.jenesis.repository.ui.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.ui.SuperadminRole;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The super-admin role is one authority, held by a sign-in that carries it and by nothing else - no other role, no
 * absent sign-in - and its role name is the authority's without the prefix a route's {@code hasRole} adds.
 */
class SuperadminRoleTest {

    @Test
    void a_sign_in_holds_the_role_only_by_carrying_its_authority() {
        assertThat(SuperadminRole.held(new TestingAuthenticationToken("root", "", SuperadminRole.AUTHORITY))).isTrue();
        assertThat(SuperadminRole.held(new TestingAuthenticationToken("ada", "", "ROLE_ADMIN", "ROLE_USER")))
                .isFalse();
        assertThat(SuperadminRole.held(null)).as("no sign-in holds nothing").isFalse();
    }

    @Test
    void the_role_is_the_authority_without_its_prefix() {
        assertThat("ROLE_" + SuperadminRole.ROLE).isEqualTo(SuperadminRole.AUTHORITY);
    }
}
