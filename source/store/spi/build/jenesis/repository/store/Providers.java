package build.jenesis.repository.store;

import module java.base;

/**
 * The ONE provider-resolution mechanism behind every {@link ServiceLoader}-discovered SPI, defined once here in the
 * base SPI module beside {@link Features} - the two halves of the same convention. {@link Features} answers "is this
 * implementation switched on, and which one did the operator select?"; {@code Providers} answers "given the
 * discovered implementations and that policy, which instance does the caller get, and what happens when the answer
 * is ambiguous, missing or duplicated?". Both are pure {@code java.base}, so an SPI contract module keeps its
 * java.base-light shape (&sect;2) while sharing the resolution logic that was previously hand-rolled once per SPI.
 *
 * <p><strong>Why this exists.</strong> Roughly fifteen SPIs each re-implemented the same
 * "iterate, filter, create, take the first, otherwise fall back" loop. Every copy silently degraded an
 * <em>explicitly selected</em> implementation that turned out to be absent, switched off or misconfigured into the
 * unselected default - the silent-fallback class &sect;9 forbids, whose exemplar is {@code store=s3} booting against
 * the local disk. The copies also disagreed on ordering: "the first enabled implementation in discovery order" is a
 * different implementation on a different module path. These primitives fix both once.
 *
 * <p><strong>These are primitives, not one algorithm.</strong> The SPIs do <em>not</em> share a resolution policy, so
 * this class exposes one primitive per policy rather than one {@code resolve} with flags. The policy names match the
 * SPI inventory's {@code selection policy} metadata:
 * <ul>
 * <li>{@code ALL} - {@link #all}: additive SPIs where every enabled implementation contributes (formats, observers,
 *     settings contributors, panels, maintenance tasks, signal sources).</li>
 * <li>{@code OPTIONAL_UNIQUE} - {@link #optionalUnique}: a singleton capability that may be absent; absence yields
 *     an empty {@link Optional} the SPI maps onto <em>its own</em> declared sentinel ({@code NONE}, {@code none()},
 *     the fixed tenant directory, ...).</li>
 * <li>{@code NAMED_UNIQUE} - {@link #namedUnique}: the selection is mandatory; there is no unselected outcome.</li>
 * <li>{@code EXCLUSIVE_WITH_DEFAULT} - {@link #exclusiveWithDefault}: exactly one implementation always resolves;
 *     an unselected deployment gets the named default, and the chosen implementation's configuration is validated
 *     by caller-supplied policy before it is built (the {@code ArtifactStoreProvider} shape).</li>
 * </ul>
 * plus {@link #installedNames}, the shared enumeration every SPI's {@code installed()} static is built from (which
 * of them a surface actually reads is per-SPI, and is censused rather than assumed - D-164).
 *
 * <p><strong>Discovery stays with the SPI.</strong> No method here calls {@link ServiceLoader#load}: the {@code uses}
 * clause belongs in the module that owns the service interface, so the SPI's own {@code resolve}/{@code installed}
 * statics pass their {@code ServiceLoader.load(X.class)} in as the {@code discovered} argument. This class never
 * becomes a second discovery pipeline or provider registry - it is the shared body of the statics that already exist.
 *
 * <p><strong>Policy stays with the caller.</strong> Enablement, selection, construction and configuration validation
 * arrive as functions, so the free {@link Features} (a globally installed lookup) and a distribution's per-call
 * {@code config}-threading equivalent both drive the identical primitives, and a helper never invents semantics an
 * SPI did not declare.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@code Providers} is stateless: every method is a pure function of its arguments,
 *     holds no static mutable state, caches nothing, and may be called concurrently from any thread. It is only as
 *     thread-safe as the arguments handed in - a {@link ServiceLoader} instance is <em>not</em> thread-safe, so a
 *     caller must hand in a loader it does not share, which {@code ServiceLoader.load(X.class)} per call satisfies.</li>
 * <li><b>Idempotency / replay.</b> Calling a primitive twice over equal inputs produces an equal outcome - the same
 *     provider chosen, the same exception thrown. It performs no I/O and mutates nothing; repeating a resolve is
 *     always safe. Whether the <em>products</em> are equal is the {@code create} function's business.</li>
 * <li><b>Absence sentinel.</b> {@code null} is never returned and never accepted: a {@code create} function that
 *     returns {@code null} (rather than an empty {@link Optional}) fails loudly naming the offending provider, as
 *     does a provider that declares a {@code null} or blank name. Absence is expressed as an empty {@link Optional}
 *     ({@link #optionalUnique}) or an empty {@link List}/{@link SortedSet} ({@link #all}, {@link #installedNames});
 *     mapping that onto the SPI's own declared sentinel is deliberately left to the SPI, so this class cannot
 *     silently choose semantics an SPI never specified. {@link #namedUnique} and {@link #exclusiveWithDefault} have
 *     no absence outcome at all - they throw.</li>
 * <li><b>Selection failure (&sect;9).</b> An <em>explicitly selected</em> implementation that cannot be honoured
 *     throws {@link IllegalStateException} at resolution, naming the selection, distinguishing "no provider answers
 *     to that name" (module absent or name misspelled) from "the provider answered but yielded nothing" (switched
 *     off, or required configuration unset), and listing the installed provider names. There is no silent fallback
 *     to the unselected default on any path. Only an <em>unselected</em> {@link #optionalUnique} degrades, and only
 *     to the empty {@link Optional} its SPI turns into a sentinel. An explicit selection deliberately outranks the
 *     enablement predicate: naming an implementation that is also switched off is contradictory configuration, and
 *     the selection wins rather than silently resolving to something else.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing is swallowed. Duplicate provider names and duplicate provider
 *     classes are packaging errors and throw - for <em>every</em> primitive, including the additive one, and
 *     including duplicates among implementations that are switched off - because a duplicate resolved by discovery
 *     order is a silently chosen winner. Exceptions from a caller-supplied {@code enabled}, {@code create} or
 *     {@code validate} function propagate unchanged.</li>
 * <li><b>Lifecycle / ownership.</b> This class creates nothing and owns nothing: it calls the caller's
 *     {@code create} function and hands the result straight back, never retaining a reference to a provider or a
 *     product after it returns. Nothing is cached - each call re-iterates the {@code discovered} argument, so a
 *     fresh {@code ServiceLoader.load(X.class)} per call means providers are re-instantiated per call, while a
 *     retained loader reuses its own cached instances. Closing whatever the products own is the caller's business.
 *     The unique primitives never build a throw-away instance: ambiguity is detected from the enablement predicate
 *     <em>before</em> anything is constructed, so exactly zero or one product is ever created.</li>
 * <li><b>Ordering / determinism.</b> Results are deterministic across discovery order. Providers are sorted by
 *     name (case-insensitively, and names are unique by clause 5) before anything is filtered, created or reported,
 *     so {@link #all} returns the same list, {@link #installedNames} the same set, and every diagnostic the same
 *     provider list on every module path. "The first enabled implementation in discovery order" is therefore
 *     <em>not</em> a policy offered here: for a unique capability, more than one enabled implementation is ambiguous
 *     and throws, naming the candidates and the setting that disambiguates them. An SPI needing a different order
 *     (by a declared {@code order()}, say) sorts the returned list itself.</li>
 * <li><b>Bounded work / cancellation.</b> Work is bounded by the number of discovered providers: the
 *     {@code discovered} argument is iterated exactly once per call, {@code enabled} at most once per provider and
 *     {@code create} at most once per selected/enabled provider. No thread is started, nothing blocks, and no
 *     timeout or interruption policy applies.</li>
 * </ol>
 */
public final class Providers {

    /** Namespace shared with {@link Features} - a diagnostic points at the exact key an operator must change. */
    private static final String NAMESPACE = "jenesis.repository.";

    private Providers() {
    }

    /**
     * The {@code ALL} policy: every enabled implementation contributes. Providers are validated (clause 5), sorted by
     * name, filtered through {@code enabled} and built with {@code create}; a provider that declines by yielding an
     * empty {@link Optional} - it is configured off in a way its enablement predicate does not see - is left out.
     * A disabled provider is never asked to create anything.
     *
     * @param spi        the SPI's selection key, the {@code <spi>} in {@code jenesis.repository.<spi>=<name>}, used
     *                   verbatim in every diagnostic.
     * @param discovered the discovered providers, normally the SPI home's own {@code ServiceLoader.load(X.class)}.
     * @param name       each provider's {@code name()} - its toggle key and attribution key.
     * @param enabled    the enablement policy, normally {@code p -> Features.active(p.name(), p.requiredConfig())}.
     * @param create     builds the implementation, empty when the provider declines.
     * @return every contributed implementation, ordered by provider name; never {@code null}, never modifiable.
     */
    public static <P, T> List<T> all(String spi,
                                     Iterable<? extends P> discovered,
                                     Function<? super P, String> name,
                                     Predicate<? super P> enabled,
                                     Function<? super P, Optional<T>> create) {
        Objects.requireNonNull(enabled, "enabled");
        Objects.requireNonNull(create, "create");
        List<Named<P>> providers = validated(spi, discovered, name);
        List<T> resolved = new ArrayList<>();
        for (Named<P> provider : providers) {
            if (!enabled.test(provider.provider())) {
                continue;
            }
            created(create.apply(provider.provider()), spi, provider).ifPresent(resolved::add);
        }
        return List.copyOf(resolved);
    }

    /**
     * The {@code OPTIONAL_UNIQUE} policy: a singleton capability that may legitimately be absent, whose absence the
     * SPI maps onto its own declared sentinel.
     *
     * <p>With a {@code selection} present the named provider is resolved and any failure throws (clause 4) - the
     * enablement predicate is not consulted, an explicit selection outranking the toggle. With no selection, the
     * enabled providers are the candidates: none yields empty, exactly one is built (and its own empty answer is
     * likewise the absent outcome), and <em>more than one</em> throws rather than picking a discovery-order winner.
     *
     * @param spi        the SPI's selection key (see {@link #all}).
     * @param discovered the discovered providers.
     * @param name       each provider's {@code name()}.
     * @param selection  the operator's explicit choice, normally {@code Features.selection(spi)}; an empty or blank
     *                   value means unselected.
     * @param enabled    the enablement policy, consulted only when unselected.
     * @param create     builds the implementation, empty when the provider declines.
     * @return the single resolved implementation, or empty when the capability is not installed or not switched on.
     */
    public static <P, T> Optional<T> optionalUnique(String spi,
                                                    Iterable<? extends P> discovered,
                                                    Function<? super P, String> name,
                                                    Optional<String> selection,
                                                    Predicate<? super P> enabled,
                                                    Function<? super P, Optional<T>> create) {
        Objects.requireNonNull(enabled, "enabled");
        Objects.requireNonNull(create, "create");
        List<Named<P>> providers = validated(spi, discovered, name);
        Optional<String> selected = selected(selection);
        if (selected.isPresent()) {
            return Optional.of(select(spi, providers, selected.get(), create));
        }
        List<Named<P>> candidates = new ArrayList<>();
        for (Named<P> provider : providers) {
            if (enabled.test(provider.provider())) {
                candidates.add(provider);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() > 1) {
            // Never a discovery-order winner: which module the loader saw first is not a configuration decision.
            throw new IllegalStateException("More than one " + spi + " implementation is enabled ("
                    + names(candidates) + ") but " + spi + " resolves to exactly one; select it with "
                    + NAMESPACE + spi + "=<name>, or switch the others off with " + NAMESPACE + "<name>=false.");
        }
        Named<P> only = candidates.getFirst();
        return created(create.apply(only.provider()), spi, only);
    }

    /**
     * The {@code NAMED_UNIQUE} policy: the selection is mandatory, so there is no unselected outcome and no sentinel.
     * A selection no provider answers to, or a provider that yields nothing, throws (clause 4).
     *
     * @param spi        the SPI's selection key (see {@link #all}).
     * @param discovered the discovered providers.
     * @param name       each provider's {@code name()}.
     * @param selection  the required implementation name; blank or {@code null} is a programming error.
     * @param create     builds the implementation; an empty answer is a configuration error, not an absence.
     * @return the selected implementation, never {@code null}.
     */
    public static <P, T> T namedUnique(String spi,
                                       Iterable<? extends P> discovered,
                                       Function<? super P, String> name,
                                       String selection,
                                       Function<? super P, Optional<T>> create) {
        Objects.requireNonNull(create, "create");
        if (selection == null || selection.isBlank()) {
            throw new IllegalArgumentException("A " + spi
                    + " implementation name is required; this SPI has no unselected outcome.");
        }
        return select(spi, validated(spi, discovered, name), selection.strip(), create);
    }

    /**
     * The {@code EXCLUSIVE_WITH_DEFAULT} policy: exactly one implementation always resolves - the store-backend
     * shape. An unselected deployment gets the provider answering to {@code fallback} (the most universally
     * applicable implementation); an explicit selection no provider answers to throws rather than falling back,
     * because falling back would serve and persist against the wrong backend. The chosen provider's configuration is
     * then checked through the caller's {@code validate} policy <em>before</em> it is built, so a selected backend
     * missing its bucket or connection string fails loudly with one message naming every missing key instead of
     * self-disabling the way a merely optional capability may.
     *
     * @param spi        the SPI's selection key (see {@link #all}).
     * @param discovered the discovered providers.
     * @param name       each provider's {@code name()}.
     * @param selection  the operator's explicit choice; an empty or blank value means unselected.
     * @param fallback   the name of the implementation an unselected deployment gets, e.g. {@code filesystem}.
     * @param validate   the configuration policy: the keys the given provider cannot run without and that are unset,
     *                   normally {@code p -> Features.missing(p.requiredConfig(), config)}; empty when satisfied.
     * @param create     builds the implementation; unlike the optional policies it cannot decline.
     * @return the resolved implementation, never {@code null}.
     */
    public static <P, T> T exclusiveWithDefault(String spi,
                                                Iterable<? extends P> discovered,
                                                Function<? super P, String> name,
                                                Optional<String> selection,
                                                String fallback,
                                                Function<? super P, List<String>> validate,
                                                Function<? super P, T> create) {
        Objects.requireNonNull(fallback, "fallback");
        Objects.requireNonNull(validate, "validate");
        Objects.requireNonNull(create, "create");
        List<Named<P>> providers = validated(spi, discovered, name);
        Optional<String> selected = selected(selection);
        String wanted = selected.orElse(fallback);
        Named<P> chosen = null;
        for (Named<P> provider : providers) {
            if (provider.name().equalsIgnoreCase(wanted)) {
                chosen = provider;
                break;
            }
        }
        if (chosen == null) {
            if (selected.isPresent() && !wanted.equalsIgnoreCase(fallback)) {
                // An explicitly configured backend whose module is absent (or whose name is misspelled). Falling back
                // silently persists against the wrong backend: publishes land in ephemeral storage while every
                // artifact in the intended bucket 404s.
                throw new IllegalStateException("The '" + wanted + "' " + spi + " backend is selected but no"
                        + " provider answers to it (its module is not on the module path, or the name is misspelled);"
                        + " refusing to fall back to the '" + fallback + "' default. Installed " + spi
                        + " providers: " + names(providers) + ".");
            }
            throw new IllegalStateException("No " + spi + " provider answers to the '" + fallback + "' default;"
                    + " installed " + spi + " providers: " + names(providers) + ".");
        }
        List<String> missing = validate.apply(chosen.provider());
        if (missing == null) {
            throw new IllegalStateException("The " + spi + " configuration policy returned null for provider "
                    + chosen.provider().getClass().getName() + "; null is never a legal result.");
        }
        if (!missing.isEmpty()) {
            // The exclusive SPI must not self-disable: fail loudly, naming every unset key at once.
            throw new IllegalStateException("The '" + chosen.name() + "' " + spi + " backend is selected but its"
                    + " required configuration is missing: " + String.join(", ", missing) + ".");
        }
        T resolved = create.apply(chosen.provider());
        if (resolved == null) {
            throw new IllegalStateException("The '" + chosen.name() + "' " + spi + " provider "
                    + chosen.provider().getClass().getName() + " returned null; null is never a legal SPI result.");
        }
        return resolved;
    }

    /**
     * The installed implementation names, as every SPI's {@code installed()} static reports them. The
     * {@code enabled} predicate decides what "installed" means for this SPI: {@code p -> true} reports every
     * implementation on the module path regardless of configuration, {@code p -> Features.enabled(p.name())} reports
     * the ones not switched off, and {@code p -> Features.active(p.name(), p.requiredConfig())} reports only the ones
     * a deployment can actually use. A boolean {@code installed()} is {@code !installedNames(...).isEmpty()}.
     *
     * <p><b>Which predicate a caller passes decides whether its answer is a capability signal at all</b>, and the
     * weaker two are not: only {@code Features.active} agrees with what {@code resolve} will do, so a surface gated on
     * an {@code enabled}-predicated {@code installed()} opens for an implementation that self-disabled on a missing
     * required key, and opens just before an ambiguous or unanswered selection throws. That divergence is why the
     * capability-signal census (D-164) asks per SPI who reads {@code installed()}, rather than treating the shape as
     * self-evidently a capability.
     *
     * @return the matching names in a stable sorted order; never {@code null}, never modifiable.
     */
    public static <P> SortedSet<String> installedNames(String spi,
                                                       Iterable<? extends P> discovered,
                                                       Function<? super P, String> name,
                                                       Predicate<? super P> enabled) {
        Objects.requireNonNull(enabled, "enabled");
        List<Named<P>> providers = validated(spi, discovered, name);
        SortedSet<String> names = new TreeSet<>();
        for (Named<P> provider : providers) {
            if (enabled.test(provider.provider())) {
                names.add(provider.name());
            }
        }
        return Collections.unmodifiableSortedSet(names);
    }

    /** A discovered provider paired with its declared, non-blank, stripped name. */
    private record Named<P>(P provider, String name) {
    }

    /** Resolve one explicitly selected implementation, or throw naming the selection and what is missing (&sect;9). */
    private static <P, T> T select(String spi,
                                   List<Named<P>> providers,
                                   String selection,
                                   Function<? super P, Optional<T>> create) {
        for (Named<P> provider : providers) {
            if (!provider.name().equalsIgnoreCase(selection)) {
                continue;
            }
            Optional<T> resolved = created(create.apply(provider.provider()), spi, provider);
            if (resolved.isPresent()) {
                return resolved.get();
            }
            throw new IllegalStateException("The '" + selection + "' " + spi + " implementation is selected ("
                    + NAMESPACE + spi + "=" + selection + ") but its provider "
                    + provider.provider().getClass().getName() + " yielded no instance: it is switched off ("
                    + NAMESPACE + provider.name() + "=false) or its required configuration is unset; refusing to"
                    + " degrade silently.");
        }
        throw new IllegalStateException("The '" + selection + "' " + spi + " implementation is selected ("
                + NAMESPACE + spi + "=" + selection + ") but no installed provider answers to it (its module is not"
                + " on the module path, or the name is misspelled); refusing to degrade silently. Installed " + spi
                + " providers: " + names(providers) + ".");
    }

    /**
     * Iterate the discovered providers exactly once, reject the packaging errors a discovery-order winner would
     * hide - a null provider, a blank name, two providers answering to one name, one provider registered twice -
     * and hand back the survivors in a deterministic, name-sorted order.
     */
    private static <P> List<Named<P>> validated(String spi,
                                                Iterable<? extends P> discovered,
                                                Function<? super P, String> name) {
        Objects.requireNonNull(spi, "spi");
        Objects.requireNonNull(discovered, "discovered");
        Objects.requireNonNull(name, "name");
        List<Named<P>> providers = new ArrayList<>();
        Map<String, String> byName = new LinkedHashMap<>();
        Map<Class<?>, String> byClass = new LinkedHashMap<>();
        for (P provider : discovered) {
            if (provider == null) {
                throw new IllegalStateException("A discovered " + spi + " provider is null.");
            }
            String declared = name.apply(provider);
            if (declared == null || declared.isBlank()) {
                throw new IllegalStateException("The " + spi + " provider " + provider.getClass().getName()
                        + " declares no name; every discovered provider answers to a non-blank name.");
            }
            declared = declared.strip();
            String clash = byName.putIfAbsent(declared.toLowerCase(Locale.ROOT), provider.getClass().getName());
            if (clash != null) {
                throw new IllegalStateException("Two " + spi + " providers answer to the name '" + declared + "': "
                        + clash + " and " + provider.getClass().getName() + "; a duplicate provider name is a"
                        + " packaging error, never a silently chosen winner.");
            }
            String duplicate = byClass.putIfAbsent(provider.getClass(), declared);
            if (duplicate != null) {
                throw new IllegalStateException("The " + spi + " provider " + provider.getClass().getName()
                        + " is registered more than once (as '" + duplicate + "' and '" + declared + "'); a"
                        + " duplicate provider is a packaging error, never a silently chosen winner.");
            }
            providers.add(new Named<>(provider, declared));
        }
        providers.sort(Comparator.comparing(provider -> provider.name().toLowerCase(Locale.ROOT)));
        return providers;
    }

    /** A {@code create} function answers with an {@link Optional}; {@code null} is never a legal SPI result. */
    private static <P, T> Optional<T> created(Optional<T> created, String spi, Named<P> provider) {
        if (created == null) {
            throw new IllegalStateException("The '" + provider.name() + "' " + spi + " provider "
                    + provider.provider().getClass().getName() + " returned null instead of an Optional;"
                    + " null is never a legal SPI result.");
        }
        return created;
    }

    /** The explicit selection, with an absent, blank or whitespace-only value normalised to unselected. */
    private static Optional<String> selected(Optional<String> selection) {
        Objects.requireNonNull(selection, "selection");
        return selection.map(String::strip).filter(value -> !value.isEmpty());
    }

    /** The provider names for a diagnostic, in the same deterministic order every message uses. */
    private static <P> List<String> names(List<Named<P>> providers) {
        List<String> names = new ArrayList<>();
        for (Named<P> provider : providers) {
            names.add(provider.name());
        }
        return names;
    }
}
