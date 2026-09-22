package build.jenesis.repository.settings;

import module java.base;

/**
 * The one classifier of which settings keys hold a secret - a {@link Setting.Kind#SECRET} value - so a stored secret
 * is never read back on any surface. A SECRET-kind setting can legitimately live in the {@code config/settings} store
 * (the keyless signer's OIDC identity token is one), so "secrets are environment-only and never stored" is not a safe
 * assumption; this is the by-construction guard the {@code /api/settings} view and the settings export bundle both
 * consult. Discovered through {@link SettingsContributor#all()} - the SPI home that {@code uses} the service - so a
 * plugin module's SECRET key is classified without a maintained table, exactly as {@link SettingsScopes} classifies a
 * key's scope. {@code java.base} only, like every settings contract, so any surface can consult it without a heavier
 * dependency.
 */
public final class SettingsSecrets {

    private SettingsSecrets() {
    }

    /** The keys of every SECRET-kind setting in the installed catalogue - the write-only values a read-back surface
     *  must omit. Discovered from the {@link SettingsContributor} contributors, so a module's SECRET key is included
     *  exactly when the module is installed. */
    public static Set<String> keys() {
        Set<String> secrets = new HashSet<>();
        for (Setting setting : SettingsContributor.all()) {
            if (setting.kind() == Setting.Kind.SECRET) {
                secrets.add(setting.key());
            }
        }
        return secrets;
    }

    /** Whether a key holds a secret value that must never be read back - {@code true} for a SECRET-kind catalogued
     *  setting. */
    public static boolean secret(String key) {
        return key != null && keys().contains(key);
    }

    /** A copy of an export bundle (module name - or {@code tenant:<tenant>:<module>} - to that document's stored
     *  values) with every SECRET-kind key removed, and an emptied document dropped - so a stored secret never travels
     *  in a backup. The export is credential-free by construction, not by the false "secrets are environment-only"
     *  assumption. Sorted like the on-store shape so a re-export of unchanged state stays byte-identical. */
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
