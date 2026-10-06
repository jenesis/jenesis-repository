package build.jenesis.repository.closure.spi;

import module java.base;

/** The discovered {@link RequirementGrammar}s by ecosystem, resolved once: the installed set is fixed for the JVM's
 *  life, and the closure pass asks per dependency. */
final class InstalledGrammars {

    static final Map<String, RequirementGrammar> GRAMMARS = discover();

    private InstalledGrammars() {
    }

    private static Map<String, RequirementGrammar> discover() {
        Map<String, RequirementGrammar> grammars = new HashMap<>();
        for (RequirementGrammar grammar : ServiceLoader.load(RequirementGrammar.class)) {
            RequirementGrammar other = grammars.putIfAbsent(grammar.ecosystem(), grammar);
            if (other != null) {
                throw new IllegalStateException("Two requirement grammars name " + grammar.ecosystem() + ": "
                        + other.getClass().getName() + " and " + grammar.getClass().getName());
            }
        }
        return Map.copyOf(grammars);
    }
}
