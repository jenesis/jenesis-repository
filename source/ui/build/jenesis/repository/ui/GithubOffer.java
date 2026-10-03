package build.jenesis.repository.ui;

import module java.base;

/**
 * Signing in with GitHub and becoming administrator, as the first-run guide offers it: the quicker way to an
 * administrator than a login key. What is offered follows from where the GitHub app stands ({@link State}): an app
 * already configured is signed in with; with none, an operator registers one on GitHub with the callback shown and
 * pastes its client id and secret, which the console stores; and where the secret could not be stored, the guide
 * says which master key to provision first and gives a freshly generated one.
 *
 * <p>Accepting posts to {@link #ROUTE}, under the guide's {@code /ui/setup} and so a super-admin's. It carries no
 * typed confirmation: it stores only the app its fields name, and the sign-in it starts asks for consent on GitHub's
 * own page.
 *
 * <p>Reads only the two settings the app is stored as, through {@link GithubCredentials}.
 */
public final class GithubOffer implements SetupOffer {

    /** Where accepting posts. */
    public static final String ROUTE = "/ui/setup/github";

    /** Where the console signs in with GitHub once the app is known. */
    public static final String SIGN_IN = "/oauth2/authorization/github";

    /** Where GitHub sends a sign-in back, beneath the console's address. */
    public static final String CALLBACK = "/login/oauth2/code/github";

    /** Where GitHub registers a new OAuth app. */
    static final String NEW_APP = "https://github.com/settings/applications/new";

    private static final String TITLE = "Sign in with GitHub and become administrator";

    private final GithubCredentials credentials;
    private final Supplier<String> address;

    /** The offer over {@code credentials}, the callback given beneath {@code address}, the console's own address as
     *  the request being served reaches it. */
    public GithubOffer(GithubCredentials credentials, Supplier<String> address) {
        this.credentials = credentials;
        this.address = address;
    }

    /** Where the GitHub app stands, as far as the guide can do something about it. */
    public sealed interface State {

        /** An app is configured: the operator signs in with it. */
        record Configured() implements State {
        }

        /** No app is, and the console can store one: the operator registers it on GitHub and pastes it here. */
        record Registrable() implements State {
        }

        /** No app is, and its secret could not be stored for want of a master key: {@code key} is one to provision. */
        record Unsealable(GithubCredentials.Registration.MasterKey key) implements State {
        }
    }

    /** Where the GitHub app stands, or empty when none is configured and the console cannot store one. */
    public Optional<State> state() {
        if (!credentials.clientId().isBlank()) {
            return Optional.of(new State.Configured());
        }
        return credentials.registration().map(registration -> registration.sealable()
                ? new State.Registrable() : new State.Unsealable(registration.newKey()));
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public Optional<Offer> offer(Viewer viewer) {
        return state().map(state -> switch (state) {
            case State.Configured _ -> new Offer(TITLE, List.of("This deployment signs in with a GitHub OAuth app. "
                    + "Sign in with it now, and the GitHub account you sign in as administers the deployment."), "",
                    List.of(), Optional.empty(), List.of(), Optional.of(new Action(ROUTE,
                    "Sign in with GitHub and make me administrator", List.of(), Optional.empty())));
            case State.Registrable _ -> new Offer(TITLE, List.of("Register a new OAuth app on GitHub with the "
                    + "authorization callback URL below, then paste its client id and a client secret it generates. "
                    + "The app is saved as the console's GitHub sign-in, its secret sealed with the settings master "
                    + "key, and you sign in with it at once: the GitHub account you sign in as administers the "
                    + "deployment."), "", List.of(), Optional.of(new Link("Register a new OAuth app on GitHub",
                    NEW_APP)), List.of(new Value("Authorization callback URL", address.get() + CALLBACK)),
                    Optional.of(new Action(ROUTE, "Save and sign in with GitHub", List.of(
                            new Field("clientId", "Client id", false),
                            new Field("clientSecret", "Client secret", true)), Optional.empty())));
            case State.Unsealable unsealable -> new Offer(TITLE, List.of("A GitHub app's client secret is stored "
                    + "sealed with the settings master key, and this deployment has none. Provision it as the "
                    + "environment variable " + unsealable.key().variable() + " (the Helm chart generates one) and "
                    + "restart; this one is freshly generated for it."), "", List.of(), Optional.empty(),
                    List.of(new Value(unsealable.key().variable(), unsealable.key().value())), Optional.empty());
        });
    }

    /**
     * Accept the offer with the app's {@code clientId} and {@code clientSecret}, which are stored unless an app is
     * configured already: empty when the sign-in may start, or why it may not, in which case nothing was stored.
     */
    public Optional<String> accept(String clientId, String clientSecret) throws IOException {
        if (!credentials.clientId().isBlank()) {
            return Optional.empty();
        }
        Optional<GithubCredentials.Registration> registration = credentials.registration();
        if (registration.isEmpty()) {
            return Optional.of("No GitHub app is configured, and this console cannot store one: name it in the "
                    + "deployment's configuration.");
        }
        if (clientId.isBlank() || clientSecret.isBlank()) {
            return Optional.of("Paste both the client id and the client secret of the app.");
        }
        try {
            registration.get().save(clientId.trim(), clientSecret.trim());
        } catch (IllegalArgumentException | IllegalStateException refused) {
            return Optional.of(refused.getMessage());
        }
        return Optional.empty();
    }
}
