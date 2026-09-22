package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.icon.IconContributor;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

/**
 * The ONE named factory for a compliance {@link SignalSource}, discovered at runtime with {@link ServiceLoader} - so
 * a security-signal contribution (a vulnerability feed, a known-exploited catalogue, an exploit-probability model, a
 * maintainer-health source, a report column) is a drop-in module that {@code provides} this interface, and the
 * composition names no vendor. Which of the specialised contracts a provider's source answers is expressed by the
 * source implementing the {@link SignalSource} sub-interfaces - {@link #signals()} declares the same set statically,
 * the capability signal a console or API gates its surface on without creating the source - so a module contributing
 * several signals from one client and one config read (a KEV catalogue plus its report column) writes ONE provider
 * creating ONE object rather than one provider SPI per signal kind. Each provider builds from ONE
 * {@link SignalContext} - its configuration lookup, the deployment-global space its snapshots live in and the clock
 * its staleness stamps come from - staying free of any framework dependency, and yields empty when its signal is not
 * enabled; discovery applies the uniform
 * {@link Features#active} gate (enabled unless {@code jenreg.<name>=false}, self-disabled when
 * {@link #requiredConfig()} is unset). Consumers resolve per contract through the contract's own statics
 * ({@link AdvisorySource#resolve}, {@link KnownExploitedSource#resolve}, {@link ExploitProbabilitySource#resolve},
 * {@link HealthSource#resolve}, {@link AdvisorySignal#resolve}), each keeping its own conservative merge and its
 * identity-comparable neutral element, exactly as when the five contracts carried five provider SPIs.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()}, {@link #signals()} and {@link #requiredConfig()} are pure declarations
 *     callable from any thread; {@link #create} runs on the resolving thread. The {@link SignalSource} it returns is
 *     shared by the gate, the sweeps and every console panel, so <em>that</em> object must be thread-safe.</li>
 * <li><b>Idempotency / replay.</b> {@link #create} builds a client, never a fetch: resolving twice must not refresh
 *     a feed, spend a rate-limit token or move a staleness stamp - and it must not read, write or probe the
 *     {@link SignalContext#snapshots() snapshot space} either. Refreshing is an explicit, idempotent write-role
 *     action (&sect;10).</li>
 * <li><b>Absence sentinel.</b> {@link #create} declares "I am not configured" with an empty {@link Optional}, and
 *     each contract's own {@code resolve} folds an empty candidate set into its identity-comparable neutral element
 *     - never {@code null}, and never a value that reads as "clean". {@code null} is never a legal return from
 *     {@link #name()}, {@link #signals()} or {@link #requiredConfig()} either.</li>
 * <li><b>Selection failure (&sect;9).</b> This is an additive SPI: every enabled provider contributes and there is
 *     no selection to miss. The uniform gate is enablement, not selection - {@code jenreg.<name>=false}
 *     switches one off and an unset {@link #requiredConfig()} key self-disables one with a single log line, so a
 *     licensed feed turns on by supplying its credential. What is <em>not</em> tolerated is a packaging error: two
 *     providers answering to one name, or one provider registered twice, throw rather than letting discovery order
 *     silently drop a feed's signals - a vulnerability nobody reported is indistinguishable from a clean
 *     artifact.</li>
 * <li><b>Tenant scoping (&sect;6).</b> There is none, deliberately: a signal source is a <em>deployment</em>
 *     singleton. The CISA catalogue, the EPSS model and the OSV database are the same public data for every tenant
 *     and are refreshed once, so {@link SignalContext} carries no tenant and offers no way to supply one - compare
 *     {@link VexProvider#over VexProvider.over(tenant, store, config)}, whose first parameter <em>is</em> the tenant
 *     because an ingested VEX statement really does belong to one. The consequence is structural rather than
 *     advisory: a provider never receives an {@link build.jenesis.repository.store.ArtifactStore} from its caller,
 *     because a store does not say at the type level whether it is the deployment root or a tenant's view of it, and
 *     most resolution sites hold only the latter. {@link SignalContext#snapshots()} is the deployment root the
 *     composition bound, narrowed to {@link SignalContext#SNAPSHOT_ROOT} and then to this provider's own
 *     {@link #name()} - so one signal can reach neither a tenant's data nor another signal's snapshots. The
 *     {@code config} lookup behind {@link SignalContext#setting} is likewise the deployment-wide effective view; a
 *     provider must read every dial through it rather than an environment variable of its own.</li>
 * <li><b>Error visibility (&sect;9).</b> {@link #signals()} must match what {@link #create} returns; resolution
 *     still filters the created object by {@code instanceof}, so a drifting declaration can only under- or
 *     over-advertise, never mis-route a signal into another contract's merge.</li>
 * <li><b>Read purity (&sect;10).</b> A query against a created source renders its persisted snapshot; the fetch is
 *     the refresh path's business, so a gate decision stands when the vendor is down.</li>
 * <li><b>Staleness.</b> A created source surfaces when it last refreshed <em>and</em> whether it is answering from a
 *     real fetch at all, through the one {@link SignalSource#freshness()} accessor every contract in the family
 *     inherits - so an empty answer is never ambiguous between "nothing found", "never fetched" and "the vendor is
 *     down". A source that mirrors a catalogue takes that instant from the stamp it committed beside the snapshot,
 *     never from the process it happens to be running in, so the answer survives a restart; one that queries per
 *     coordinate takes it from {@link SignalContext#clock()} at the fetch it answered from. The accessor is
 *     abstract, so a provider cannot ship a source that has not answered the question - and it is a pure read, so a
 *     console panel renders it beside an empty result without refreshing anything.</li>
 * <li><b>Lifecycle / ownership.</b> A provider is a cheap, stateless factory that {@link ServiceLoader} creates,
 *     consults and discards. The created source may own an HTTP client, cache or thread and closes them through its
 *     own lifecycle; the caller resolves per contract and owns the result. A provider that declines is never asked
 *     for anything else. The {@link SignalContext} is handed <em>per creation</em> and is the caller's, not the
 *     provider's: a provider may keep what it reads out of it (a dial, the store view, the clock) but must not
 *     retain the context expecting later calls to see a newer configuration - a settings change re-resolves the
 *     family and creates fresh sources. Nothing in the context is closed by the provider; the deployment owns the
 *     root store and the {@link SignalContext#deployment binding} that exposes it, and retires that binding when
 *     its context closes.</li>
 * <li><b>Ordering / determinism.</b> Discovery order never shows through: providers are name-sorted before anything
 *     is created, so {@link #installed} and {@link #named} answer the same set and the same attributed order on
 *     every module path, and every contract's merge is order-insensitive by construction.</li>
 * <li><b>Durability / delivery (&sect;13).</b> Creating a source commits nothing. What a source persists it persists
 *     into {@link SignalContext#snapshots()} only - never a file, never a second store, never the artifact publish
 *     path (a mirrored catalogue is derived external data, not a served artifact, so no publication interceptor or
 *     observer fires for it). The durable source of truth is that space's own compare-and-set pointer: the commit
 *     point is the pointer move, an incomplete refresh commits nothing and leaves the prior-good catalogue serving
 *     with its true age, and a crash between writing a snapshot body and moving the pointer leaves unreferenced
 *     bytes rather than a pointer naming data that is not there. A deployment that has bound no root store is a
 *     wiring error, and {@link SignalContext#snapshots()} throws naming the signal rather than letting a mirror
 *     write nowhere and then read as a clean, empty catalogue.</li>
 * <li><b>Attribution and marks.</b> {@link #name()} is not only a toggle key: it is the string a
 *     {@code Finding} records as its {@code source}, half of the {@code (source, id)} identity a durable finding is
 *     merged under, and therefore the name a console resolves <em>back</em> to a plug-in long after the fact. That
 *     is why this interface, and not {@link SignalSource}, extends {@link IconContributor}: the provider is the only
 *     object in this family that has ever had a name, it is reachable without creating anything (so a console
 *     resolves a mark on a render path with no client, no fetch and no snapshot read), and extending it here adds no
 *     abstract method to any implementation - {@code name()} is already declared and {@code icon()} defaults to
 *     empty. The consequences follow {@link IconContributor}'s own contract: renaming a provider does not rename its
 *     history, it <em>orphans</em> it, and every finding that provider ever recorded renders from then on as a name
 *     nothing answers to. Rename deliberately or not at all.</li>
 * <li><b>Vendor field mapping.</b> Every provider in this family exists to translate <em>one external vendor's
 *     document</em> - an OSV entry, a GitHub Security Advisory node, a CISA KEV row, an EPSS score line, a Snyk,
 *     Mend, VulnDB, VulnCheck, Socket or OpenSSF payload - into the shared signal records. That translation is
 *     correct only against the vendor's own published API, which lives outside both repositories, so <b>no contract
 *     kit in this product can hold the reference</b>: a kit can drive a recorded payload and assert the answer is
 *     well-shaped, and that is exactly what {@code SignalContract} does, but it cannot tell a faithful mapping from a
 *     plausible one. This clause is therefore verified per provider by the principle checkup, against these
 *     invariants, which hold whatever the vendor calls its fields:
 *     <ul>
 *       <li><b>An absent field maps to absent</b>, never to a default that reads as a fact. A vendor that omits a
 *           severity must not surface as "severity none"; an unscored CVE is absent, never zero
 *           ({@code ExploitProbabilitySource}'s own sentinel clause states the same rule one level down).</li>
 *       <li><b>Scales are normalised as documented, not as they arrive.</b> A CVSS vector, a 0-10 base score, a
 *           0-1 probability and a vendor's own {@code low}/{@code high} words are four different scales; the mapping
 *           states which it read and what it produced.</li>
 *       <li><b>Identifiers are carried in the ecosystem's canonical spelling</b> - the PackageURL ecosystem and the
 *           CVE/GHSA identity the rest of the product merges on - so two vendors reporting the same vulnerability of
 *           the same artifact merge rather than double-count.</li>
 *       <li><b>The vendor's own identifier is preserved</b> beside the canonical one, because it is what an operator
 *           types into the vendor's console to check the finding.</li>
 *       <li><b>A field the vendor renamed or restructured must be visible as an outage, not as absence.</b> This is
 *           the dangerous one and the reason the clause is stated: a mapping that reads a field that no longer exists
 *           yields "no advisories", which is byte-for-byte the shape of a clean artifact, and the gate then admits
 *           what it was installed to hold. The error-visibility clause above says a source must never report clean
 *           when it means unknown; this clause says the same thing about a mapping that silently stopped
 *           matching.</li>
 *     </ul></li>
 * </ol>
*
 * <p><b>The switch is read one way, and the default is stated rather than implied.</b> A source asks
 * {@link SignalContext#enabled} and passes its own default.
 *
 * <p>Both halves of that used to be divergent. Six sources read the switch through {@code Features} and six through
 * {@code Boolean.parseBoolean}, which disagree on every value that is neither "true" nor "false" -
 * {@code jenreg.snyk=yes} enabled Snyk while {@code jenreg.osv=yes} disabled OSV, though the settings surface
 * documents {@code jenreg.<name>=false} as the one thing that switches a source off. That was an accident of which
 * idiom a provider copied, and asking the context is what removes it. It could not simply be fixed in place: half
 * the source modules do not require the store SPI, which is why they had reimplemented the read rather than calling
 * {@code Features} - so the shared answer has to live on the context they already hold.
 *
 * <p>The <em>defaults</em> differ on purpose, and are now written at the call site rather than implied by the
 * idiom. A public no-credential feed defaults OFF, because an unconfigured deployment must consult no advisory API
 * - a contract with a test of its own - and the shipped boot module raises those five as part of its secure floor.
 * A licensed feed defaults on and disables itself when its credential is absent, reaching the same place by a
 * different route. Reading a default off whichever idiom a provider happened to copy is how two deliberate postures
 * came to look like one inconsistent one.
 */
public interface SignalSourceProvider extends IconContributor {

    /** The signal name this provider answers to, e.g. {@code osv}, {@code kev}, {@code epss} - the
     *  {@code jenreg.<name>} toggle, the attribution key of everything it creates, and the string a
     *  finding it produced carries as its {@code source} for as long as that finding is stored. */
    @Override
    String name();

    /** The {@link SignalSource} contracts the created source answers - the static capability declaration a console
     *  or API reads through {@link #installed(Class)} without creating (or enabling) the source. It must match what
     *  {@link #create} actually returns; resolution still filters the created object by {@code instanceof}, so a
     *  drifting declaration can only under- or over-advertise, never mis-route a signal. */
    Set<Class<? extends SignalSource>> signals();

    /** Build the source if the configuration enables it, reading its dials, its durable snapshot space and its clock
     *  through the one {@link SignalContext}; empty when off. */
    Optional<SignalSource> create(SignalContext context);

    /** The config keys this signal cannot run without (a licensed token, an organization id); empty (the default)
     *  for a public source. A provider whose required keys are unset {@link Features#active self-disables} at
     *  discovery with one log line, so a licensed feed turns on by supplying its credential rather than a second
     *  switch. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /**
     * Every installed provider, name-sorted, as the {@link IconContributor} the findings it produced are attributed
     * to - the lookup a console joins a recorded {@code Finding.source} against to answer "which plug-in said this,
     * and is it still here?".
     *
     * <p>Regardless of <em>enablement</em>, and deliberately so. A finding is a statement a plug-in made in the past;
     * whether that plug-in is switched on today does not change who made it, and an operator who turns a feed off
     * for a week must not watch a week of history turn into orphans and back. What orphans a row is the module
     * leaving the deployment - the one condition under which nothing can answer for the name any more. (This
     * diverges from {@code RepositoryFormat.installed()}, which does apply the toggle, because that answers a
     * live-serving question - which formats handle a request now - rather than an attribution one.)
     *
     * <p>Nothing is created: this reads the providers' declarations only, so it performs no I/O and is safe on the
     * render path it exists for.
     */
    static List<SignalSourceProvider> contributors() {
        return Providers.all("signal-source",
                ServiceLoader.load(SignalSourceProvider.class),
                SignalSourceProvider::name,
                _ -> true,
                Optional::of);
    }

    /** The provider names installed on this deployment that declare the given contract, regardless of enablement -
     *  the per-contract capability signal a console or API gates its surface on. */
    static Set<String> installed(Class<? extends SignalSource> contract) {
        return Providers.installedNames("signal-source",
                ServiceLoader.load(SignalSourceProvider.class),
                SignalSourceProvider::name,
                provider -> provider.signals().stream().anyMatch(contract::isAssignableFrom));
    }

    /** Every enabled source answering the given contract, keyed by its provider name - the attributed view a caller
     *  (the findings ledger) uses when it must record <em>which</em> provider reported a signal rather than a merged
     *  union. A name-keyed projection over the shared {@link Providers#all} policy, so the map is name-sorted rather
     *  than discovery-ordered and a duplicate provider name throws instead of one feed silently overwriting
     *  another's signals. Empty when none is enabled. Only providers declaring the contract are created (a KEV-only
     *  resolve never constructs the advisory feeds), and the created object is still filtered by
     *  {@code instanceof}, the authoritative check. */
    static <T extends SignalSource> SequencedMap<String, T> named(Class<T> contract, UnaryOperator<String> config) {
        List<Map.Entry<String, T>> attributed = Providers.all("signal-source",
                ServiceLoader.load(SignalSourceProvider.class),
                SignalSourceProvider::name,
                // The uniform convention gate: declares the contract, enabled unless jenreg.<name>=false,
                // and a source missing its required config (a licensed credential) self-disables with one log line.
                provider -> provider.signals().stream().anyMatch(contract::isAssignableFrom)
                        && Features.active(config, provider.name(), provider.requiredConfig()),
                // One context per provider, named after it: the config lookup the caller supplied, plus the
                // deployment-global snapshot space narrowed to THIS signal and the deployment clock. The caller never
                // hands a store, so a resolution site holding only a tenant- or repository-scoped one (a maintenance
                // pass, a console view) cannot mis-scope a mirror - see the SPI's tenant-scoping clause.
                provider -> provider.create(SignalContext.of(provider.name(), config))
                        .filter(contract::isInstance)
                        .map(source -> Map.entry(provider.name(), contract.cast(source))));
        SequencedMap<String, T> sources = new LinkedHashMap<>();
        for (Map.Entry<String, T> source : attributed) {
            sources.put(source.getKey(), source.getValue());
        }
        return sources;
    }
}
