package build.jenesis.repository.settings;

import module java.base;

/**
 * The classifier of which settings keys hold a {@link Setting.Kind#SECRET} value, so a stored secret is never read back
 * on any surface: the {@code /api/settings} view and the export bundle both consult it. Secrets can be stored (the
 * keyless signer's OIDC identity token is one), and the classification comes from {@link SettingsContributor#all()},
 * so a module's secret key is covered exactly while it is installed.
 */
public final class SettingsSecrets {

    private SettingsSecrets() {
    }

    /** The keys of every SECRET-kind setting in the installed catalogue: write-only values a read-back omits. */
    public static Set<String> keys() {
        Set<String> secrets = new HashSet<>();
        for (Setting setting : SettingsContributor.all()) {
            if (setting.kind() == Setting.Kind.SECRET) {
                secrets.add(setting.key());
            }
        }
        return secrets;
    }

    /** Whether a key holds a secret value that must never be read back. */
    public static boolean secret(String key) {
        return key != null && keys().contains(key);
    }

    /** A copy of an export bundle (document key to stored values) with every secret key removed and emptied documents
     *  dropped, so a stored secret never travels in a backup. Sorted, so a re-export of unchanged state is
     *  byte-identical. */
    public static SortedMap<String, SortedMap<String, String>> redact(
            Map<String, ? extends Map<String, String>> bundle) {
        Set<String> secrets = keys();
        SortedMap<String, SortedMap<String, String>> redacted = new TreeMap<>();
        bundle.forEach((document, values) -> {
            SortedMap<String, String> kept = new TreeMap<>();
            values.forEach((key, value) -> {
                if (!secrets.contains(key)) {
                    kept.put(key, value);
                }
            });
            if (!kept.isEmpty()) {
                redacted.put(document, kept);
            }
        });
        return redacted;
    }
}
