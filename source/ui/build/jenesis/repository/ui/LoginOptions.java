package build.jenesis.repository.ui;

import module java.base;
import build.jenesis.repository.icon.IconContributor;
import build.jenesis.repository.icon.IconResource;

/**
 * The sign-in choices a mechanism module offers the login page, each with a stable id, a button label and the path
 * into the mechanism's own URL space. Each mechanism contributes one bean; the page lists them all, and with none shows
 * the "no sign-in module" notice. The reading half of the login seam beside {@link LoginContributor}, so a mechanism
 * that is not an OAuth2 client (SAML, a certificate) is listed too.
 */
public interface LoginOptions {

    List<LoginOption> options();

    /**
     * One sign-in choice: its stable id, the label its button shows, the path the button links to, and optionally the
     * mechanism's own mark.
     *
     * @param icon the mechanism's own drawing ({@link build.jenesis.repository.icon.IconContributor#icon}, as a field
     *             since an option is a record), or empty for the figure computed from {@code id}. Some providers'
     *             branding guidelines require their mark on a sign-in button; an operator-named OIDC or SAML provider
     *             has none the console could carry.
     */
    record LoginOption(String id, String label, String href, Optional<IconResource> icon)
            implements IconContributor {

        public LoginOption {
            Objects.requireNonNull(icon, "icon - a mechanism with no mark passes Optional.empty(), never null");
        }

        /** The id, which the mark is computed from. */
        @Override
        public String name() {
            return id;
        }
    }
}
