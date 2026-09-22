package build.jenesis.repository.compliance;

import module java.base;

/**
 * The shared {@link GatePolicyProvider} plumbing, held as one immutable value for the length of a single
 * {@link GatePolicyProvider#create create} call. Around its own policy object every dimension - the licence policy,
 * the version floor, the known-exploited catalogue - used to hand-roll the same three things: read its dials out of
 * the {@code config} lookup, yield {@linkplain Optional#empty() nothing} when the deployment has not configured the
 * dimension, and throw when it configured it wrongly. The eight dimensions carried seven copies of the
 * verdict-with-a-default parse, five copies of the comma-separated-list parse and a null-or-blank guard on every
 * single read; those are stated once here, so a ninth dimension arrives at parity with its peers (PRINCIPLES
 * &sect;13) instead of re-deriving them.
 *
 * <p>This is composition, not a base class: a provider still implements {@link GatePolicyProvider} directly and
 * still owns every decision that is genuinely its own - which keys it reads, what "nothing to gate on" means for
 * its dimension, and how its policy object is assembled. What it stops owning is the plumbing. The value is
 * immutable and lives no longer than the {@code create} call that made it, so nothing here is shared state a
 * concurrent rebuild could see half-written.
 *
 * <h2>A misconfiguration names its key</h2>
 * A dial is re-read on every settings rebuild and on every scheduled re-read of the deployment's effective config,
 * and a value that does not parse must throw so the writer rolls back and keeps the last good gate. Thrown out of
 * a bare {@code Verdict.valueOf} that says only <em>"No enum constant Verdict.MAYBE"</em>, that failure told an
 * operator nothing about <em>which</em> of the deployment's dials to fix - and on the boot / scheduled-re-read path
 * there is no settings-write context to add it back. Every read here therefore fails with the offending key in the
 * message (PRINCIPLES &sect;9). Reaching for a default instead is never an option: silently gating on
 * {@code QUARANTINE} when the operator asked for {@code REJECT} loosens the gate without saying so.
 *
 * <h2>Nothing configured is an absent dimension, never an inert one</h2>
 * {@code enforcing} is the one place the "has this dimension anything to gate on" question turns into a result: a
 * dimension with no floors, no reserved names, no rules, no catalogue is {@link Optional#empty() absent}, so it never
 * reaches {@link ComplianceGate} at all. It does not become a policy object that answers "allow" to everything - an
 * inert dimension is indistinguishable from an active one in every surface that counts the gate's dimensions, and a
 * policy that always allows is precisely the shape a silent misconfiguration would take.
 *
 * <h2>"Anything to gate on" is never "what verdict would it return"</h2>
 * The question {@code enforcing} answers is whether the dimension is <em>configured</em> - not what it would decide
 * once it runs. A {@code <dim>-action} of {@link Verdict#ALLOW} is a decision, so it makes the dimension
 * <em>evaluate and permit</em>; it does not withdraw the dimension from the gate. The deployment already has two ways
 * to switch a dimension off - the {@code jenreg.<name>} toggle and the provider's own
 * {@link GatePolicyProvider#requiredConfig() enablement} configuration - and a third, spelled as a verdict, would be
 * the weaker one. A dimension that withdraws itself is indistinguishable from one this deployment never installed, so
 * an incident review cannot tell "nobody configured provenance admission here" from "somebody set it to permit"; a
 * dimension that permits is a decision the deployment made, and it stays in the gate to be read as one.
 *
 * <p>That criterion is a shape rather than a comment: {@code enforcing} takes the <em>evidence</em> - the configured
 * entries, or a resolved source against its own absence sentinel - and never a bare {@code boolean}, so
 * {@code enforcing(action != Verdict.ALLOW, ...)} is not a call that compiles. A {@link Verdict} is neither a
 * {@link Collection} nor a {@link SignalSource}, and there is no overload it fits. A dimension whose "am I
 * configured" test genuinely does not fit either shape widens this helper deliberately, in the open, rather than
 * reaching for a predicate that can say anything at all.
 *
 * <h2>The publish/proxy asymmetry is declared, not re-derived</h2>
 * A dimension that does not gate a whole flavour says so once through {@link GatePolicyProvider#symmetry()}, and
 * {@link #of} - like {@link GatePolicyProvider#resolve} - answers empty for the flavour the declaration excludes.
 * The declaration is therefore what produces the behaviour rather than a comment describing it, so the two cannot
 * drift apart.
 */
public final class GateDimension {

    private final UnaryOperator<String> config;

    private final GatePolicyProvider.Path path;

    private GateDimension(UnaryOperator<String> config, GatePolicyProvider.Path path) {
        this.config = config;
        this.path = path;
    }

    /**
     * The settings view {@code provider} builds its dimension from for this gate flavour, or {@link Optional#empty()}
     * when the provider's declared {@link GatePolicyProvider.Symmetry symmetry} does not carry {@code path} at all -
     * so a flavour-restricted dimension yields no policy without writing the guard itself, and cannot yield one that
     * disagrees with its own declaration.
     *
     * @param provider the provider being asked, read for its declared symmetry
     * @param config   the deployment's setting lookup, answering {@code null} for an unset key
     * @param path     the gate flavour {@code create} was called for
     */
    public static Optional<GateDimension> of(GatePolicyProvider provider,
                                             UnaryOperator<String> config,
                                             GatePolicyProvider.Path path) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(path, "path");
        return provider.symmetry().carries(path)
                ? Optional.of(new GateDimension(config, path))
                : Optional.empty();
    }

    /** Whether the dimension is being built for the {@link GatePolicyProvider.Path#PROXY proxy-fetch} leg - the one
     *  question a {@link GatePolicyProvider.Symmetry#SOFTENED_ON_PROXY softening} dimension asks of the flavour. */
    public boolean proxy() {
        return path == GatePolicyProvider.Path.PROXY;
    }

    /**
     * The policy {@code build} assembles when the operator configured this dimension something to gate on, or
     * {@link Optional#empty()} when they did not. {@code configured} is that evidence itself - the parsed floors, the
     * reserved names, the rules, the trust anchors - because what counts as configured is the one part of this that is
     * genuinely per-dimension; empty means the gate never carries the dimension at all, rather than carrying a policy
     * object that finds nothing.
     *
     * <p>Note what is <em>not</em> askable here: the dimension's {@code <dim>-action} verdict. Presence is a question
     * about configuration, never about the answer the dimension would give, so a {@link Verdict#ALLOW} action yields a
     * live dimension that evaluates and permits (see the class javadoc). Handing a bare {@code boolean} in is not
     * possible, which is what keeps that from being restated per dimension.
     */
    public Optional<GatePolicy> enforcing(Collection<?> configured, Supplier<GatePolicy> build) {
        Objects.requireNonNull(configured, "configured");
        return present(!configured.isEmpty(), build);
    }

    /**
     * The {@link #enforcing(Collection, Supplier)} counterpart for a dimension configured by a <em>resolved source</em>
     * rather than a list: the known-exploited catalogue and the maintainer-health source each declare a singleton
     * "nothing is enabled" sentinel, and the dimension is carried exactly while {@code source} is not it. Compared by
     * identity, which is what the sentinels are documented to support - an equal-but-distinct empty source is a real
     * source that happens to know nothing today, not an absent one.
     *
     * @param source the source the dimension resolved for this deployment
     * @param absent that source family's sentinel, e.g. {@code KnownExploitedSource.none()}
     */
    public <T extends SignalSource> Optional<GatePolicy> enforcing(T source, T absent, Supplier<GatePolicy> build) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(absent, "absent");
        return present(source != absent, build);
    }

    private Optional<GatePolicy> present(boolean configured, Supplier<GatePolicy> build) {
        return configured ? Optional.of(build.get()) : Optional.empty();
    }

    /** The stripped value of {@code key}, or empty when it is unset or blank - the null-or-blank guard every read
     *  used to write out, so "unset" and "set to spaces" cannot diverge between two dimensions. */
    public Optional<String> text(String key) {
        String value = config.apply(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }

    /** The comma-separated entries of {@code key}, stripped, with blanks between separators dropped; empty when the
     *  key is unset or blank. Deliberately not named {@code list}: a settings dial is not a store namespace, and the
     *  {@code .list(prefix)} idiom belongs to the enumeration the unbounded-listing ratchet polices. */
    public List<String> entries(String key) {
        return each(key, Function.identity());
    }

    /**
     * The comma-separated entries of {@code key}, each parsed by {@code parse}; empty when the key is unset or
     * blank. An entry {@code parse} refuses fails the whole read, naming the key and the entry - a settings value
     * whose third rule is malformed must roll back, not quietly enforce the first two.
     */
    public <T> List<T> each(String key, Function<String, T> parse) {
        return split(key, config.apply(key), ",", parse);
    }

    /**
     * The entries of {@code key} separated by a newline or a {@code ;}, each parsed by {@code parse}; empty when the
     * key is unset or blank. The line-shaped counterpart of {@link #each} for a multi-line dial whose entries may
     * themselves contain commas.
     */
    public <T> List<T> lines(String key, Function<String, T> parse) {
        return split(key, config.apply(key), "[\\n;]", parse);
    }

    /** The {@link Verdict} configured at {@code key}, case-insensitively, or {@code fallback} when unset or blank;
     *  a value that is not a verdict throws, naming {@code key}. */
    public Verdict verdict(String key, Verdict fallback) {
        return verdict(key, config.apply(key), fallback);
    }

    /** The decimal configured at {@code key}, or {@code fallback} when unset or blank; a non-numeric value throws,
     *  naming {@code key}, rather than silently disabling the threshold it configures. */
    public double number(String key, double fallback) {
        OptionalDouble configured = number(key);
        return configured.isPresent() ? configured.getAsDouble() : fallback;
    }

    /** The decimal configured at {@code key}, or empty when unset or blank - for a dial that is genuinely off until
     *  an operator sets one; a non-numeric value throws, naming {@code key}. */
    public OptionalDouble number(String key) {
        Optional<String> value = text(key);
        if (value.isEmpty()) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Double.parseDouble(value.get()));
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(
                    "setting '" + key + "' must be a decimal number, not '" + value.get() + "'", malformed);
        }
    }

    /**
     * The comma-separated entries of {@code value}, stripped, with blanks between separators dropped; empty for a
     * {@code null} or blank value. The free-standing form of {@link #entries(String)}, for the sweeps that read a
     * dimension's own keys outside a gate build - the retroactive licence enforcement reads exactly the keys the
     * licence dimension does, and must read them the same way.
     */
    public static List<String> csv(String value) {
        return tokens(value, ",");
    }

    /**
     * The {@link Verdict} in {@code value}, case-insensitively, or {@code fallback} when it is {@code null} or
     * blank; anything else throws an {@link IllegalArgumentException} naming {@code key}. The free-standing form of
     * {@link #verdict(String, Verdict)}, for a dimension whose policy object is also built outside a gate build.
     */
    public static Verdict verdict(String key, String value, Verdict fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String token = value.strip();
        try {
            return Verdict.valueOf(token.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("setting '" + key + "' must be one of "
                    + Stream.of(Verdict.values()).map(Enum::name).collect(Collectors.joining(", "))
                    + ", not '" + token + "'", unknown);
        }
    }

    private static <T> List<T> split(String key, String value, String separator, Function<String, T> parse) {
        List<T> parsed = new ArrayList<>();
        for (String entry : tokens(value, separator)) {
            try {
                parsed.add(parse.apply(entry));
            } catch (RuntimeException malformed) {
                throw new IllegalArgumentException("setting '" + key + "' has an entry that does not parse: '"
                        + entry + "' - " + malformed.getMessage(), malformed);
            }
        }
        return List.copyOf(parsed);
    }

    /** Split on {@code separator}, strip each entry and drop the blanks a doubled or trailing separator leaves - the
     *  one spelling of "a multi-valued setting" behind every list-shaped dial. */
    private static List<String> tokens(String value, String separator) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String entry : value.split(separator)) {
            String stripped = entry.strip();
            if (!stripped.isEmpty()) {
                tokens.add(stripped);
            }
        }
        return List.copyOf(tokens);
    }
}
