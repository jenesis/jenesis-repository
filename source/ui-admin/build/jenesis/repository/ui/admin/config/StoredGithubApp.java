package build.jenesis.repository.ui.admin.config;

import module java.base;

import build.jenesis.repository.settings.SecretCipher;
import build.jenesis.repository.ui.GithubCredentials;
import build.jenesis.repository.ui.admin.ConsoleSettingsContributor;
import build.jenesis.repository.ui.store.SettingsAdmin;

/**
 * The GitHub OAuth app the console signs in with, kept as two of the console's settings: read each time a sign-in
 * starts - a pin from the environment first, as every setting resolves - so one saved from the console signs in at
 * once, and saved as one write, its secret sealed with the settings master key.
 */
final class StoredGithubApp implements GithubCredentials, GithubCredentials.Registration {

    private final SettingsAdmin settings;

    StoredGithubApp(SettingsAdmin settings) {
        this.settings = settings;
    }

    @Override
    public String clientId() {
        return settings.effective(ConsoleSettingsContributor.GITHUB_CLIENT_ID, "");
    }

    @Override
    public String clientSecret() {
        return settings.effective(ConsoleSettingsContributor.GITHUB_CLIENT_SECRET, "");
    }

    @Override
    public Optional<Registration> registration() {
        return Optional.of(this);
    }

    @Override
    public boolean sealable() {
        return settings.sealsSecrets();
    }

    @Override
    public MasterKey newKey() {
        return new MasterKey(SecretCipher.ENV, SecretCipher.newKey());
    }

    @Override
    public void save(String clientId, String clientSecret) throws IOException {
        settings.saveAll(Map.of(ConsoleSettingsContributor.GITHUB_CLIENT_ID, clientId,
                ConsoleSettingsContributor.GITHUB_CLIENT_SECRET, clientSecret));
    }
}
