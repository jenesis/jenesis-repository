package build.jenesis.repository.contract.testkit;

import module java.base;

/**
 * Completeness ratchet shared by parameterized SPI contract suites.
 *
 * <p>A valid census has three independent sources of truth: the providers modules DECLARE, the provider instances
 * the runtime graph DISCOVERS, and fixture or reason-bearing exemption registrations. {@link #of} compares all three
 * and throws {@link AssertionError} with every discovered mismatch, keeping this helper independent of JUnit and
 * assertion libraries.
 *
 * <p><b>Declared is read from the resolved module graph, not from source text.</b> It used to walk every
 * {@code module-info.java} under a source root and regex out the {@code provides ... with ...} clauses. That was a
 * parser that did not understand the language reimplementing a fact the compiler had already recorded: it needed the
 * service's fully-qualified name to appear literally, so an {@code import} in a module descriptor would have made it
 * silently match nothing and report a clean census over zero providers. {@link ModuleLayer#boot()} carries the same
 * clauses as compiled {@code ModuleDescriptor.Provides}, exactly and by construction.
 *
 * <p>What that changes about scope, and why it is the right change: the source walk asked "what does this TREE
 * declare", which is a question no deployment ever asks. The graph asks "what does THIS DEPLOYMENT declare", which
 * is the question the runtime and fixture halves were already asking - so all three sides now share one scope, and a
 * census can no longer disagree with itself about which providers exist. Whether a module belongs in that scope is a
 * separate question, answered where it can be: by the drive census, rooted on the assemblies this product ships.
 */
public final class ContractCensus {


    /**
     * One provider identified by its stable selection name and implementation class name.
     */
    public record Provider(String name, String implementation) {

        public Provider {
            name = required(name, "provider name");
            implementation = required(implementation, "provider implementation");
        }

        /**
         * Describes a runtime provider without retaining its mutable instance.
         */
        public static Provider runtime(String name, Object provider) {
            Objects.requireNonNull(provider, "provider");
            return new Provider(name, provider.getClass().getName());
        }
    }

    /**
     * A temporary fixture exemption. The reason is mandatory so a waiver is always reviewable.
     */
    public record Exemption(String implementation, String reason) {

        public Exemption {
            implementation = required(implementation, "exempt provider implementation");
            reason = required(reason, "exemption reason");
        }
    }

    private final Class<?> service;
    private final List<Provider> declaredProviders;
    private final List<Provider> runtimeProviders;
    private final List<String> fixtureProviders;
    private final List<Exemption> exemptions;

    private ContractCensus(Class<?> service,
                           Collection<Provider> declaredProviders,
                           Collection<Provider> runtimeProviders,
                           Collection<String> fixtureProviders,
                           Collection<Exemption> exemptions) {
        this.service = Objects.requireNonNull(service, "service");
        this.declaredProviders = List.copyOf(declaredProviders);
        this.runtimeProviders = List.copyOf(runtimeProviders);
        this.fixtureProviders = fixtureProviders.stream()
                .map(provider -> required(provider, "fixture provider implementation")).toList();
        this.exemptions = List.copyOf(exemptions);
    }

    /**
     * Verifies one service census and returns its immutable inputs when they agree.
     *
     * @throws AssertionError if declarations, runtime discovery, fixtures, or exemptions disagree
     */
    public static ContractCensus of(Class<?> service,
                                    Collection<Provider> declaredProviders,
                                    Collection<Provider> runtimeProviders,
                                    Collection<String> fixtureProviders,
                                    Collection<Exemption> exemptions) {
        ContractCensus census = new ContractCensus(service, declaredProviders, runtimeProviders, fixtureProviders,
                exemptions);
        census.verify();
        return census;
    }

    /**
     * Every provider class declared for {@code service} by a module in the resolved graph, read from the compiled
     * {@code provides} clauses. Provider names initially use the implementation class name; a suite with a domain
     * selection name may replace them before calling {@link #of}.
     */
    public static List<Provider> declaredProviders(Class<?> service) {
        return declaredProviders(service, module -> true);
    }

    /**
     * The same, restricted to the modules {@code scope} accepts - so a census that distinguishes what the product
     * SHIPS from what a test module contributes can still say so. That distinction used to be a directory ("source"
     * versus "test/ui"); here it is a property of the module itself, which is what it always was.
     */
    public static List<Provider> declaredProviders(Class<?> service, Predicate<Module> scope) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(scope, "scope");
        List<Provider> providers = new ArrayList<>();
        for (Module module : ModuleLayer.boot().modules()) {
            if (!scope.test(module)) {
                continue;
            }
            ModuleDescriptor descriptor = module.getDescriptor();
            if (descriptor == null) {
                continue;                       // an unnamed module declares nothing
            }
            for (ModuleDescriptor.Provides provides : descriptor.provides()) {
                if (provides.service().equals(service.getName())) {
                    provides.providers().forEach(implementation ->
                            providers.add(new Provider(implementation, implementation)));
                }
            }
        }
        providers.sort(Comparator.comparing(Provider::implementation));
        return List.copyOf(providers);
    }

    public Class<?> service() {
        return service;
    }

    public List<Provider> declaredProviders() {
        return declaredProviders;
    }

    public List<Provider> runtimeProviders() {
        return runtimeProviders;
    }

    public List<String> fixtureProviders() {
        return fixtureProviders;
    }

    public List<Exemption> exemptions() {
        return exemptions;
    }

    private void verify() {
        List<String> errors = new ArrayList<>();
        if (declaredProviders.isEmpty()) {
            errors.add("static graph declares no providers");
        }
        duplicates("static provider name", declaredProviders.stream().map(Provider::name).toList(), errors);
        duplicates("static provider class", declaredProviders.stream().map(Provider::implementation).toList(), errors);
        duplicates("runtime provider name", runtimeProviders.stream().map(Provider::name).toList(), errors);
        duplicates("runtime provider class", runtimeProviders.stream().map(Provider::implementation).toList(), errors);
        duplicates("fixture provider class", fixtureProviders, errors);
        duplicates("exempt provider class", exemptions.stream().map(Exemption::implementation).toList(), errors);

        Set<String> declared = declaredProviders.stream()
                .map(Provider::implementation).collect(Collectors.toCollection(TreeSet::new));
        Set<String> runtime = runtimeProviders.stream()
                .map(Provider::implementation).collect(Collectors.toCollection(TreeSet::new));
        Set<String> fixtures = new TreeSet<>(fixtureProviders);
        Map<String, String> exempt = exemptions.stream().collect(Collectors.toMap(
                Exemption::implementation, Exemption::reason, (first, _) -> first, TreeMap::new));

        difference(declared, runtime).forEach(provider ->
                errors.add("runtime graph does not discover statically declared provider " + provider));
        difference(runtime, declared).forEach(provider ->
                errors.add("static graph does not declare runtime provider " + provider));
        difference(declared, union(fixtures, exempt.keySet())).forEach(provider ->
                errors.add("declared provider has neither fixture nor exemption " + provider));
        difference(fixtures, declared).forEach(provider ->
                errors.add("fixture names no live statically declared provider " + provider));
        difference(exempt.keySet(), declared).forEach(provider ->
                errors.add("exemption names no live statically declared provider " + provider));
        intersection(fixtures, exempt.keySet()).forEach(provider ->
                errors.add("exemption is stale because a fixture exists for " + provider));

        if (!errors.isEmpty()) {
            throw new AssertionError("Contract census for " + service.getName() + " failed:\n - "
                    + String.join("\n - ", errors));
        }
    }

    private static void duplicates(String label, Collection<String> values, Collection<String> errors) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicate = new TreeSet<>();
        for (String value : values) {
            if (!seen.add(value)) {
                duplicate.add(value);
            }
        }
        duplicate.forEach(value -> errors.add("duplicate " + label + " " + value));
    }

    private static Set<String> difference(Collection<String> left, Collection<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(new HashSet<>(right));
        return result;
    }

    private static Set<String> intersection(Collection<String> left, Collection<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.retainAll(new HashSet<>(right));
        return result;
    }

    private static Set<String> union(Collection<String> left, Collection<String> right) {
        Set<String> result = new HashSet<>(left);
        result.addAll(right);
        return result;
    }

    private static String required(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
