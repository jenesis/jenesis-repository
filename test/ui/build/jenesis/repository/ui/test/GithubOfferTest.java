package build.jenesis.repository.ui.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.ui.GithubCredentials;
import build.jenesis.repository.ui.GithubOffer;
import build.jenesis.repository.ui.SetupOffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The first-run guide's GitHub offer follows from where the app stands: a configured app is signed in with and stores
 * nothing, an unconfigured one is registered and pasted, a secret that could not be sealed asks for a master key
 * first, and a console that cannot store an app offers nothing.
 */
class GithubOfferTest {

    private static final SetupOffer.Viewer VIEWER = new SetupOffer.Viewer(Optional.empty());

    @Test
    void a_configured_app_is_signed_in_with_and_nothing_is_stored() throws IOException {
        App app = new App("Iv1.configured", true);
        GithubOffer offer = new GithubOffer(app, () -> "https://repo.example.com");

        assertThat(offer.state()).containsInstanceOf(GithubOffer.State.Configured.class);
        SetupOffer.Action action = offer.offer(VIEWER).orElseThrow().action().orElseThrow();
        assertThat(action.fields()).as("nothing to paste").isEmpty();
        assertThat(action.confirmation()).as("GitHub's own page asks for consent").isEmpty();
        assertThat(offer.accept("", "")).isEmpty();
        assertThat(app.saved).isEmpty();
    }

    @Test
    void an_unconfigured_app_is_registered_with_the_callback_shown_and_stored_when_pasted() throws IOException {
        App app = new App("", true);
        GithubOffer offer = new GithubOffer(app, () -> "https://repo.example.com");

        assertThat(offer.state()).containsInstanceOf(GithubOffer.State.Registrable.class);
        SetupOffer.Offer made = offer.offer(VIEWER).orElseThrow();
        assertThat(made.values()).extracting(SetupOffer.Value::text)
                .containsExactly("https://repo.example.com" + GithubOffer.CALLBACK);
        assertThat(made.link()).hasValueSatisfying(link -> assertThat(link.external()).isTrue());
        assertThat(made.action().orElseThrow().fields()).extracting(SetupOffer.Field::name, SetupOffer.Field::secret)
                .containsExactly(tuple("clientId", false), tuple("clientSecret", true));

        assertThat(offer.accept("Iv1.abc", "")).as("half an app").isPresent();
        assertThat(app.saved).isEmpty();
        assertThat(offer.accept(" Iv1.abc ", " s3cr3t ")).isEmpty();
        assertThat(app.saved).containsExactly("Iv1.abc", "s3cr3t");
    }

    @Test
    void without_a_master_key_the_offer_gives_one_to_provision_and_accepts_nothing() throws IOException {
        App app = new App("", false);
        GithubOffer offer = new GithubOffer(app, () -> "https://repo.example.com");

        assertThat(offer.state()).containsInstanceOf(GithubOffer.State.Unsealable.class);
        SetupOffer.Offer made = offer.offer(VIEWER).orElseThrow();
        assertThat(made.action()).isEmpty();
        assertThat(made.values()).extracting(SetupOffer.Value::label, SetupOffer.Value::text)
                .containsExactly(tuple("KEY", "k1:fresh"));
        assertThat(offer.accept("Iv1.abc", "s3cr3t")).hasValueSatisfying(reason -> assertThat(reason)
                .contains("KEY"));
        assertThat(app.saved).isEmpty();
    }

    @Test
    void a_console_that_cannot_store_an_app_offers_nothing_and_refuses_one() throws IOException {
        GithubCredentials fixed = new GithubCredentials() {
            @Override
            public String clientId() {
                return "";
            }

            @Override
            public String clientSecret() {
                return "";
            }
        };
        GithubOffer offer = new GithubOffer(fixed, () -> "https://repo.example.com");

        assertThat(offer.state()).isEmpty();
        assertThat(offer.offer(VIEWER)).isEmpty();
        assertThat(offer.accept("Iv1.abc", "s3cr3t")).isPresent();
    }

    /** An app stored as the console would, refusing a secret while no master key seals it. */
    private static final class App implements GithubCredentials, GithubCredentials.Registration {

        private final String clientId;
        private final boolean sealable;
        private final List<String> saved = new ArrayList<>();

        App(String clientId, boolean sealable) {
            this.clientId = clientId;
            this.sealable = sealable;
        }

        @Override
        public String clientId() {
            return clientId;
        }

        @Override
        public String clientSecret() {
            return "";
        }

        @Override
        public Optional<Registration> registration() {
            return Optional.of(this);
        }

        @Override
        public boolean sealable() {
            return sealable;
        }

        @Override
        public MasterKey newKey() {
            return new MasterKey("KEY", "k1:fresh");
        }

        @Override
        public void save(String clientId, String clientSecret) {
            if (!sealable) {
                throw new IllegalStateException("a secret needs KEY");
            }
            saved.add(clientId);
            saved.add(clientSecret);
        }
    }
}
