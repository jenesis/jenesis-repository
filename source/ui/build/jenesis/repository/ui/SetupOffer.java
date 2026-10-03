package build.jenesis.repository.ui;

import module java.base;

/**
 * Something the first-run guide offers on its first page, beside its own steps: an action an operator setting up a
 * deployment may want before anything else, such as loading a demo into an empty one. A console module contributes
 * one as a bean of the {@link ConsoleModuleProvider#configuration() configuration} it is installed through, so the
 * guide shows what is installed and names no module itself. An offer is data - a title, what it says, what it will
 * change, the values an operator copies out of it and the action that does it, with the fields it asks - and the
 * guide renders it through its own template, so nothing contributed produces markup.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #offer} is called concurrently, once per render of the guide's first page; an
 *     offering bean holds no per-request state.</li>
 * <li><b>Idempotency / replay.</b> Asking never changes what a later ask answers: an offer is made or withdrawn by
 *     what the deployment holds, never by being shown.</li>
 * <li><b>Absence sentinel.</b> Nothing to offer this viewer is {@link Optional#empty()}; {@code null} is never
 *     legal.</li>
 * <li><b>Selection failure.</b> An {@code ALL} seam: every offering bean is asked, in {@link #order()} and then by
 *     class name, and none being present leaves the guide as it is.</li>
 * <li><b>Tenant scoping.</b> {@link Viewer#tenant()} is the tenant the console has selected, empty while none is; an
 *     offer that speaks of a tenant speaks of that one and offers nothing without it. The guide is a super-admin's, so
 *     an offer may propose deployment-wide changes, and says so.</li>
 * <li><b>Error visibility.</b> An exception is the offer's failure, not the guide's: the page renders without it and
 *     the failure is logged naming the offering bean.</li>
 * <li><b>Read purity.</b> A request costs a constant number of point reads and bounded pages, whatever the tenant
 *     holds; an offer never fetches or writes, and what it writes it writes from the route its action posts to. The work an action starts runs off the request path, and the screen it
 *     leads to shows its progress rather than waiting for it.</li>
 * <li><b>Lifecycle / ownership.</b> An offering bean is a Spring bean of its module's configuration; the guide owns
 *     nothing of it.</li>
 * <li><b>Ordering / determinism.</b> Offers render in {@link #order()}, then by the offering bean's class name.</li>
 * <li><b>Confirmation.</b> An {@link Action} that changes more than one thing without a further step is confirmed by
 *     typing its {@link Confirmation#phrase()}; the route it posts to refuses a request whose {@code confirm} field
 *     does not carry the phrase, so no request made outside the dialog acts. An action that changes only what its
 *     fields say, or leads to a page that asks for consent itself - a provider's sign-in - carries none.</li>
 * </ol>
 */
public interface SetupOffer {

    /** Where this offer sits on the guide's first page: lower first. */
    default int order() {
        return 100;
    }

    /** What is offered to {@code viewer}, or empty for nothing. */
    Optional<Offer> offer(Viewer viewer) throws IOException;

    /** Who the guide is drawn for: the tenant the console has selected, empty while none is. */
    record Viewer(Optional<String> tenant) {

        public Viewer {
            Objects.requireNonNull(tenant, "tenant");
        }
    }

    /**
     * One offer: its title, the paragraphs that say what it is, the warning a reader must not miss, each thing it
     * changes as a line of its own, where it leads, the values an operator copies out of it, and the action that
     * does it.
     *
     * @param title        the offer's heading.
     * @param paragraphs   what the offer is, in plain sentences.
     * @param warning      what accepting it risks, shown as a warning; empty for none.
     * @param consequences each thing accepting it changes, one line each.
     * @param link         where the offer leads, beside or instead of acting.
     * @param values       what the operator copies elsewhere, each named.
     * @param action       what accepting it posts, empty when the offer only says or links.
     */
    record Offer(String title, List<String> paragraphs, String warning, List<String> consequences, Optional<Link> link,
                 List<Value> values, Optional<Action> action) {

        public Offer {
            Objects.requireNonNull(title, "title");
            paragraphs = List.copyOf(paragraphs);
            warning = warning == null ? "" : warning;
            consequences = List.copyOf(consequences);
            Objects.requireNonNull(link, "link");
            values = List.copyOf(values);
            Objects.requireNonNull(action, "action");
        }

        /** An offer that only says what it says and leads where its link does. */
        public static Offer linking(String title, List<String> paragraphs, Link link) {
            return new Offer(title, paragraphs, "", List.of(), Optional.of(link), List.of(), Optional.empty());
        }
    }

    /**
     * What accepting an offer posts: the route, the button's label, the fields the operator fills in, and the
     * confirmation it asks for, if any. The route receives each field by its name, and a confirmation's phrase as
     * its {@code confirm} field.
     */
    record Action(String route, String label, List<Field> fields, Optional<Confirmation> confirmation) {

        public Action {
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(label, "label");
            fields = List.copyOf(fields);
            Objects.requireNonNull(confirmation, "confirmation");
        }
    }

    /** A value an action asks for: the name it is posted as, its label, and whether it is a secret, masked as it is
     *  typed. Every field is required. */
    record Field(String name, String label, boolean secret) {

        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(label, "label");
        }
    }

    /** How an action is confirmed: the phrase the operator types, the question the dialog asks and what it warns of. */
    record Confirmation(String phrase, String question, String warning) {

        public Confirmation {
            Objects.requireNonNull(phrase, "phrase");
            Objects.requireNonNull(question, "question");
            warning = warning == null ? "" : warning;
        }
    }

    /** A value an offer gives the operator to copy elsewhere, and what it is. */
    record Value(String label, String text) {

        public Value {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(text, "text");
        }
    }

    /** Where an offer leads: its label and the path or address it opens. An address outside the console opens in a
     *  new tab. */
    record Link(String label, String href) {

        public Link {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(href, "href");
        }

        /** Whether the link leaves the console. */
        public boolean external() {
            return href.startsWith("https://") || href.startsWith("http://");
        }
    }
}
