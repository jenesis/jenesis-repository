package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.AccessDenialSettingsContributor;
import build.jenesis.repository.server.AuthFailures;
import build.jenesis.repository.server.RepositoryAuthorizationEntryPoint;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.spi.AccessDenial;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.store.Features;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One default for what a caller without access is answered, and it hides the name.
 *
 * <p>The suites about the refusal itself drive both values; this one reads the shipped default on purpose, twice:
 * what the catalogue declares - the value the settings screen and the generated reference show - and what the code
 * does with nothing set anywhere, which is the leg that asks for the answer rather than reading the constant, so a
 * default moved in one place and not the other fails here.
 */
class AccessDenialDefaultTest {

    @AfterEach
    void restore() {
        Features.reset();
    }

    @Test
    void the_catalogue_shows_the_value_the_code_applies() {
        Setting declared = new AccessDenialSettingsContributor().settings().stream()
                .filter(setting -> AccessDenial.KEY.equals(setting.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(AccessDenial.KEY + " is not in the catalogue"));
        assertThat(declared.defaultValue()).as("the default the settings screen and the reference show")
                .isEqualTo(AccessDenial.DEFAULT)
                .isEqualTo("not-found");
        assertThat(declared.scope()).as("one answer for the whole deployment").isEqualTo(Setting.Scope.GLOBAL);
        assertThat(declared.live()).as("read on every refusal, so it applies without a restart").isTrue();
        assertThat(declared.choices()).as("and the honest 403 stays one setting away")
                .containsExactly("not-found", "forbidden");
        assertThat(declared.choices()).allSatisfy(choice -> assertThat(declared.parses(choice)).isTrue());
    }

    @Test
    void with_nothing_set_a_refusal_answers_404_on_every_surface_that_asks() {
        Features.configure(_ -> null);
        assertThat(AccessDenial.configured()).isEqualTo(AccessDenial.NOT_FOUND);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/repository/acme/releases/a.pom");
        when(request.getAttribute("jenrepo.decision")).thenReturn(Authorization.Decision.FORBIDDEN);
        HttpServletResponse response = mock(HttpServletResponse.class);
        new RepositoryAuthorizationEntryPoint(new AuthFailures(), List::of, AccessDenial::configured)
                .handle(request, response, new AccessDeniedException("no right"));
        verify(response).setStatus(404);

        assertThat(RepositoryRouting.denied("another tenant's key").getStatusCode().value()).isEqualTo(404);
        assertThat(RepositoryRouting.denied("another tenant's key").getReason())
                .as("and says no more than an absent name would").isEqualTo("Not found");
    }

    @Test
    void the_honest_refusal_is_reachable_from_configuration() {
        Features.configure(Map.of("jenrepo." + AccessDenial.KEY, "forbidden")::get);
        assertThat(AccessDenial.configured()).isEqualTo(AccessDenial.FORBIDDEN);
        assertThat(RepositoryRouting.denied("another tenant's key").getStatusCode().value()).isEqualTo(403);
        assertThat(RepositoryRouting.denied("another tenant's key").getReason()).isEqualTo("another tenant's key");
    }

    @Test
    void a_value_that_names_neither_answer_is_refused_naming_it() {
        assertThatThrownBy(() -> AccessDenial.of("401")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jenrepo.access-denied-status=401").hasMessageContaining("forbidden");
        assertThat(AccessDenial.of(" ")).isEqualTo(AccessDenial.NOT_FOUND);
    }
}
