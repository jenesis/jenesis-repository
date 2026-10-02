package build.jenesis.repository.ui;

/**
 * The GitHub OAuth app a console signs in with, asked each time a sign-in starts rather than fixed at boot, so an app
 * registered from the console is used at once. A console that stores it as settings provides a bean; without one the
 * boot configuration ({@link GithubProperties}) answers. A blank client id means GitHub sign-in is off.
 */
public interface GithubCredentials {

    String clientId();

    String clientSecret();
}
