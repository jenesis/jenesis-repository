package build.jenesis.repository.demo.web;

import module java.base;

import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * The settings catalogue's labels, as the demo names the settings it switches: the label an operator knows from the
 * settings screen. Held once, since the modules installed - and so the catalogue - are fixed for the life of the
 * process, and the guide's first page asks on every render.
 */
final class Labels {

    private static final Map<String, String> LABELS = SettingsContributor.all().stream()
            .collect(Collectors.toUnmodifiableMap(Setting::key, Setting::label, (first, _) -> first));

    private Labels() {
    }

    /** The label of the setting {@code key}, or the key itself for one the catalogue does not hold. */
    static String of(String key) {
        return LABELS.getOrDefault(key, key);
    }

    /** Whether an installed module declares the setting {@code key}, so a change to it can be made at all. */
    static boolean catalogued(String key) {
        return LABELS.containsKey(key);
    }
}
