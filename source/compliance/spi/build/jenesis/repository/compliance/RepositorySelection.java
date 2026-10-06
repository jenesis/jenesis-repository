package build.jenesis.repository.compliance;

import module java.base;

/**
 * How a repository's setting naming some of the deployment's providers - {@value AdvisorySource#SELECTION} for
 * advisory feeds, {@value ContentScanner#SETTING} for content scanners - is read, the one rule both settings share:
 * blank selects every provider the deployment has on, in their order; {@code none} selects none; anything else is a
 * comma-separated list of names, taken in the order the setting gives them, a name given twice counted once. A name the
 * deployment cannot honour fails the selection, naming the setting, the name and why - a repository is never screened
 * by fewer providers than it names without saying so.
 */
public final class RepositorySelection {

    /** The value selecting no provider at all. */
    public static final String NONE = "none";

    private RepositorySelection() {
    }

    /**
     * The providers of {@code installed} that {@code value}, the repository's {@code setting}, selects.
     *
     * @param kind        what a provider is called in a diagnostic - "advisory feed", "scanner"
     * @param name        a provider's name, as the setting spells it
     * @param unavailable why the deployment cannot honour a provider - switched off, a configuration it needs not
     *                    set - or empty where it can; a blank selection leaves out every provider it names a reason for
     * @throws IllegalStateException for a name no installed provider answers to, or one {@code unavailable} gives a
     *                               reason for
     */
    public static <T> List<T> select(String setting, String kind, String value, List<T> installed,
                                     Function<T, String> name, Function<T, Optional<String>> unavailable) {
        if (value == null || value.isBlank()) {
            return installed.stream().filter(provider -> unavailable.apply(provider).isEmpty()).toList();
        }
        if (value.strip().equalsIgnoreCase(NONE)) {
            return List.of();
        }
        Map<String, T> byName = new LinkedHashMap<>();
        for (T provider : installed) {
            byName.putIfAbsent(name.apply(provider), provider);
        }
        SequencedSet<String> named = new LinkedHashSet<>();
        for (String entry : value.split(",")) {
            if (!entry.isBlank()) {
                named.add(entry.strip());
            }
        }
        List<T> selected = new ArrayList<>(named.size());
        for (String entry : named) {
            T provider = byName.get(entry);
            if (provider == null) {
                throw new IllegalStateException(setting + " names the " + kind + " '" + entry + "', which is not "
                        + "installed; installed: " + List.copyOf(byName.keySet()));
            }
            Optional<String> reason = unavailable.apply(provider);
            if (reason.isPresent()) {
                throw new IllegalStateException(setting + " names the " + kind + " '" + entry + "', which is "
                        + reason.get());
            }
            selected.add(provider);
        }
        return List.copyOf(selected);
    }
}
