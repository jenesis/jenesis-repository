package build.jenesis.repository.ui.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.icon.IconResource;
import build.jenesis.repository.ui.LoginController;
import build.jenesis.repository.ui.LoginOptions;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sign-in page controller: it lists what the installed mechanisms offer and redirects an already-authenticated
 * visitor to the console.
 *
 * <p>It used to enumerate Spring Security's {@code ClientRegistrationRepository} directly, and these tests were
 * written against that - which is why they could only ever describe OAuth2 registrations. The page reads
 * {@link LoginOptions} now, the same seam the admin console's page reads, so a mechanism that is not an OAuth2 client
 * is listed here too rather than being invisible on one of the two sign-in pages.
 */
class LoginControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void it_lists_every_installed_mechanisms_options_and_flags_that_sign_in_is_possible() {
        LoginController controller = new LoginController(List.of(
                mechanism(option("github", "GitHub", "/oauth2/authorization/github")),
                // A second mechanism, and deliberately not an OAuth2 one: the page flattens what the mechanisms
                // offer rather than asking one framework what it knows about.
                mechanism(option("saml", "Single sign-on", "/saml2/authenticate/idp"),
                        option("key", "An access key", "/login/key"))));

        Model model = new ConcurrentModel();
        String view = controller.login(anonymous(), model);

        assertThat(view).isEqualTo("console/login");
        assertThat(model.getAttribute("loginConfigured")).isEqualTo(true);
        List<LoginController.Choice> options =
                (List<LoginController.Choice>) model.getAttribute("loginOptions");
        assertThat(options).extracting(LoginController.Choice::label)
                .containsExactly("GitHub", "Single sign-on", "An access key");
        assertThat(options).extracting(LoginController.Choice::href)
                .as("a mechanism owns its own URL space, so the page links where it says rather than deriving it")
                .containsExactly("/oauth2/authorization/github", "/saml2/authenticate/idp", "/login/key");
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_mechanism_with_no_mark_of_its_own_is_rendered_with_the_computed_figure_and_a_tint() {
        LoginController controller = new LoginController(List.of(
                mechanism(option("saml", "Single sign-on", "/saml2/authenticate/idp"))));

        Model model = new ConcurrentModel();
        controller.login(anonymous(), model);

        List<LoginController.Choice> options =
                (List<LoginController.Choice>) model.getAttribute("loginOptions");
        assertThat(options).singleElement().satisfies(choice -> {
            assertThat(choice.markSvg()).as("a button is marked like every other console row, not left bare")
                    .contains("<svg");
            assertThat(choice.markTint())
                    .as("and the computed figure carries its tint class, which is the second axis of identity")
                    .matches("app-mark--t\\d\\d");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_mechanism_that_ships_its_own_mark_renders_it_untinted() {
        // The rule this pins is the one every brand guideline states: a contributor's own drawing is rendered as
        // given and never recoloured. It is the reason the seam carries an icon at all - on a sign-in button the
        // computed figure can be the non-compliant answer, because Google's guidelines prescribe their mark there.
        String own = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><title>x</title></svg>";
        LoginController controller = new LoginController(List.of(
                mechanism(new LoginOptions.LoginOption("vendor", "Vendor SSO", "/vendor",
                        Optional.of(IconResource.svg(own))))));

        Model model = new ConcurrentModel();
        controller.login(anonymous(), model);

        List<LoginController.Choice> options =
                (List<LoginController.Choice>) model.getAttribute("loginOptions");
        assertThat(options).singleElement().satisfies(choice -> {
            assertThat(choice.markSvg()).as("the mechanism's own drawing, rendered as given").isEqualTo(own);
            assertThat(choice.markTint())
                    .as("and no tint class - tinting somebody's logo is what the guidelines forbid").isEmpty();
        });
    }

    /** A mechanism's option with no mark of its own - the ordinary case, and the one the computed figure serves. */
    private static LoginOptions.LoginOption option(String id, String label, String href) {
        return new LoginOptions.LoginOption(id, label, href, Optional.empty());
    }

    @Test
    void with_no_mechanism_installed_the_page_says_so_rather_than_rendering_empty() {
        // Not an error and not an empty list: a deployment with no mechanism cannot be signed into at all, and the
        // flag is what lets the page say which of the two it is instead of showing a page of nothing.
        LoginController controller = new LoginController(List.of());

        Model model = new ConcurrentModel();
        String view = controller.login(anonymous(), model);

        assertThat(view).isEqualTo("console/login");
        assertThat(model.getAttribute("loginConfigured")).isEqualTo(false);
        assertThat((List<?>) model.getAttribute("loginOptions")).isEmpty();
    }

    @Test
    void a_mechanism_offering_nothing_does_not_make_sign_in_look_possible() {
        // An installed but unconfigured mechanism contributes no option, and a page that counted mechanisms rather
        // than options would render a sign-in page with no way to sign in.
        LoginController controller = new LoginController(List.of(mechanism()));

        Model model = new ConcurrentModel();
        controller.login(anonymous(), model);

        assertThat(model.getAttribute("loginConfigured")).isEqualTo(false);
    }

    @Test
    void an_already_authenticated_visitor_is_redirected_to_the_console() {
        LoginController controller = new LoginController(List.of());
        Authentication authenticated = new UsernamePasswordAuthenticationToken(
                "alice", "n/a", AuthorityUtils.createAuthorityList("ROLE_USER"));

        assertThat(controller.login(authenticated, new ConcurrentModel())).isEqualTo("redirect:/console");
    }

    private static Authentication anonymous() {
        return new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    }

    private static LoginOptions mechanism(LoginOptions.LoginOption... options) {
        return () -> List.of(options);
    }
}
