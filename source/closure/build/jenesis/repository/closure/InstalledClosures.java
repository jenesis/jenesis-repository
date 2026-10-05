package build.jenesis.repository.closure;

import module java.base;

/** The discovered {@link EcosystemClosure}s by ecosystem, resolved once: the installed set is fixed for the JVM's
 *  life. */
final class InstalledClosures {

    static final Map<String, EcosystemClosure> CLOSURES = discover();

    private InstalledClosures() {
    }

    private static Map<String, EcosystemClosure> discover() {
        Map<String, EcosystemClosure> closures = new HashMap<>();
        for (EcosystemClosure closure : ServiceLoader.load(EcosystemClosure.class)) {
            EcosystemClosure other = closures.putIfAbsent(closure.ecosystem(), closure);
            if (other != null) {
                throw new IllegalStateException("Two closures resolve " + closure.ecosystem() + ": "
                        + other.getClass().getName() + " and " + closure.getClass().getName());
            }
        }
        return Map.copyOf(closures);
    }
}
