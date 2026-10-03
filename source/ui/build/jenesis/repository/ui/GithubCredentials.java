package build.jenesis.repository.ui;

import module java.base;

/**
 * The GitHub OAuth app a console signs in with, asked each time a sign-in starts rather than fixed at boot, so an app
 * registered from the console is used at once. A console that stores it as settings provides a bean; without one the
 * boot configuration ({@link GithubProperties}) answers. A blank client id means GitHub sign-in is off.
 */
public interface GithubCredentials {

    String clientId();

    String clientSecret();

    /** How an app registered from the console is stored, or empty where the console cannot store one and only the
     *  boot configuration names the app. */
    default Optional<Registration> registration() {
        return Optional.empty();
    }

    /** Where the console stores an app an operator registers: its client secret sealed with the settings master key. */
    interface Registration {

        /** Whether a master key is configured, without which a client secret is never stored. */
        boolean sealable();

        /** A master key in the shape the deployment reads, freshly generated, for an operator to provision. */
        MasterKey newKey();

        /** Store the app's client id and secret, refusing with the reason as an {@link IllegalArgumentException} or
         *  {@link IllegalStateException} and storing neither. */
        void save(String clientId, String clientSecret) throws IOException;

        /** A master key to provision: the environment variable it is read from, and its value. */
        record MasterKey(String variable, String value) {

            public MasterKey {
                Objects.requireNonNull(variable, "variable");
                Objects.requireNonNull(value, "value");
            }
        }
    }
}
