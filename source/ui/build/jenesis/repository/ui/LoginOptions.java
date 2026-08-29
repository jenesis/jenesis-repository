package build.jenesis.repository.ui;

import module java.base;

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

    record LoginOption(String id, String label, String href) {
    }
}
