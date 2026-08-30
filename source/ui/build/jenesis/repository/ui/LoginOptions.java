package build.jenesis.repository.ui;

import module java.base;
import build.jenesis.repository.icon.IconContributor;
import build.jenesis.repository.icon.IconResource;

/**
 * The sign-in choices a mechanism module offers the login page: each with a stable id, the label its button shows,
 * and the path the button links to (the mechanism owns its own URL space). A mechanism contributes one bean; the
 * login page flattens all of them, and with none it shows the "no sign-in module" notice.
 *
 * <p>It is the reading half of the login seam, beside {@link LoginContributor}, which is the installing half - and it
 * lives here for the same reason that one does. The free console used to build its own list by reaching into Spring
 * Security's {@code ClientRegistrationRepository} directly, which meant it could only ever show OAuth2 and OIDC: a
 * mechanism that is not an OAuth2 client - SAML, a certificate, anything a module might add - contributed a button to
 * one console and nothing at all to the other, on a page whose entire job is to list what a user may sign in with.
 */
public interface LoginOptions {

    List<LoginOption> options();

    /**
     * One sign-in choice: its stable id, the label its button shows, the path the button links to, and optionally the
     * mechanism's own mark.
     *
     * @param icon the mechanism's own drawing, or empty to be rendered as the figure computed from {@code id}. The
     *             same seam {@link build.jenesis.repository.icon.IconContributor#icon} gives every other family, and
     *             it is a field here rather than an overridable method because a login option is a record a
     *             mechanism produces, not a type it implements.
     *             <p>The login page is the one surface where the computed figure can be the <em>wrong</em> answer
     *             rather than merely a plainer one: Google's branding guidelines prescribe their mark on a sign-in
     *             button as a condition of app verification, and Microsoft's recommend theirs. That is why the seam
     *             exists before any asset fills it - a mechanism that may ship a mark has somewhere to put it.
     *             <p>It stays optional because most deployments cannot use it anyway: only {@code github} is a
     *             compiled-in provider, and for OIDC and SAML the identity provider is named by an operator at
     *             deployment time, so the console learns it from configuration and can carry no drawing for it.
     */
    record LoginOption(String id, String label, String href, Optional<IconResource> icon)
            implements IconContributor {

        public LoginOption {
            Objects.requireNonNull(icon, "icon - a mechanism with no mark passes Optional.empty(), never null");
        }

        /** The id is the name the mark is computed from, so a mechanism has one identity rather than two. */
        @Override
        public String name() {
            return id;
        }
    }
}
