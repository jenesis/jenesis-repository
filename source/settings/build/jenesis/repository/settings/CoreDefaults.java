package build.jenesis.repository.settings;

/**
 * The shipped default for each dial the server itself binds, defined once.
 *
 * <h2>Why this exists</h2>
 *
 * Each of these values was written out twice or three times: in {@code RepositoryProperties}, which the server binds
 * and {@code LiveConfig} reads as the running gate's fallback; in the {@link CoreSettingsContributor} row the
 * settings screen and the generated reference render; and, for {@code malware-action}, in the no-argument
 * {@code MaliciousPackagePolicy} constructor as well. Nothing held them together, and they came apart twice in one
 * week - once when the malicious-package verdict moved and the policy kept a value no deployment ran, and once when
 * the unknown-licence verdict moved and the properties bean kept telling operators the product held what the gate
 * was serving.
 *
 * <p>Aligning copies is not the fix; a test that holds three values equal only fires after someone has changed one
 * of them, and cannot stop a fourth appearing. So there is one definition and the other sites reference it.
 *
 * <h2>Why these are compile-time constants, and why the opposite argument does not apply</h2>
 *
 * They were methods for a few hours, on the {@code AuditActions} reasoning: javac inlines a {@code static final
 * String} into every reader, so a "reference" compiles to the identical bytes as typing the literal, and a rule
 * reading class files cannot tell a reference from a copy. That argument is sound where a <b>bytecode rule</b> has to
 * make exactly that distinction, which is why the audit action names are routed through a method.
 *
 * <p>It does not apply here, and taking it cost something. Nothing checks these by reading class files - what holds
 * them to one value is a test that <em>asks</em> for the verdict - and this build compiles every module from source,
 * so an inlined constant cannot drift from its definition: change it here and every reader is recompiled against it.
 * Meanwhile the build's own settings-reference extractor requires a declared default to be a compile-time constant it
 * can read, so the method form published six compliance dials - what happens to a malicious package, to a
 * vulnerability at threshold, to an unknown licence - as {@code (computed)}: a row that looks complete and documents
 * nothing, on precisely the settings an operator most needs explained.
 *
 * <p>So each default is declared here as the <b>string the catalogue publishes</b>, and parsed by whoever applies it.
 * One definition, readable by the extractor, stated in the form it is published in.
 *
 * <h2>What belongs here, and what does not</h2>
 *
 * A dial the <em>server kernel</em> binds, because those have readers in three modules and no single owner among
 * them. A dial belonging to a discovered dimension does not: its default lives on the policy that applies it and its
 * own settings contributor references that, which is what the licence and signature dimensions do. The test is
 * whether {@code LiveConfig} reads a {@code RepositoryProperties} field for it - if it does not, the dial reaches
 * the gate through the settings lookup and this class is the wrong home.
 */
public final class CoreDefaults {

    /** A curated malicious-package record is a more certain signal than a severity score, so it does not refuse
     *  less than the vulnerability dimension does. */
    public static final String MALWARE_ACTION = "REJECT";

    /** The secure floor for an artifact whose advisories reach the threshold below. */
    public static final String VULNERABILITY_ACTION = "REJECT";

    /** The severity band at which the vulnerability dimension bites: a fresh deployment with a feed active gates
     *  the most severe CVEs rather than admitting them silently. */
    public static final String VULNERABILITY_THRESHOLD = "CRITICAL";

    /** The secure floor for a coordinate an operator has named on the deny list. */
    public static final String DENY_LIST_ACTION = "REJECT";

    /** Pull-through proxying is on: the image proxies by default, and a distribution built on it is a drop-in
     *  replacement for it. Declared as the catalogue's own {@code "true"}/{@code "false"} rather than a boolean,
     *  because the string is what the settings reference publishes and a {@code Boolean.toString(...)} of a constant
     *  is not itself one; the one reader that wants a boolean parses it. */
    public static final String PROXY_ENABLED = "true";

    private CoreDefaults() {
    }
}
