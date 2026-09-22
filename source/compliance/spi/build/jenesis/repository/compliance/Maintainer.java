package build.jenesis.repository.compliance;

import module java.base;

/**
 * Someone an artifact's own metadata names as responsible for it - a package.json's author, maintainers and
 * contributors, a POM's developers, the owner of the repository a scm or repository field points at - in the two
 * spellings a signing key can be looked up by: an e-mail address (the Web Key Directory) and a GitHub login (the
 * keys a user publishes there) - and, for the repository itself, the one spelling a build's keyless identity
 * names it by, {@code <host>/<owner>/<name>}, which is what provenance-bound trust compares a signing workflow's
 * repository against. It is what a discovered key is bound to: a key found through a maintainer is
 * trusted, once the operator accepts discovery, only for artifacts whose metadata names that maintainer, never
 * pooled across the repository the way a key looked up by its own id is.
 *
 * <p>Read by the ecosystem inspectors out of the metadata they already parse for a licence, and carried on the
 * {@link ComplianceGate.Subject} beside it; nothing here reaches any network, and nothing here is the
 * maintainer-<em>health</em> score the health dimension reads, which is a fact about a package rather than a
 * person. The {@linkplain #ids identities} are the normalised forms - {@code mailto:<address>} and
 * {@code github:<login>}, lower-cased - so the same person named two ways in two versions is one maintainer.
 */
public record Maintainer(String name, String email, String github, String repository) {

    public Maintainer {
        name = blank(name) ? null : name.trim();
        email = blank(email) || !email.contains("@") ? null : email.trim().toLowerCase(Locale.ROOT);
        github = blank(github) ? null : github.trim().toLowerCase(Locale.ROOT);
        repository = blank(repository) ? null : repository.trim().toLowerCase(Locale.ROOT);
    }

    /** A person: name, e-mail and GitHub login, any of them absent, naming no repository. */
    public Maintainer(String name, String email, String github) {
        this(name, email, github, null);
    }

    /** Whether anything a key could be looked up by is named. */
    public boolean addressable() {
        return email != null || github != null;
    }

    /** The identities this maintainer can be looked up and bound by, in the order they were named. */
    public Set<String> ids() {
        Set<String> ids = new LinkedHashSet<>();
        if (email != null) {
            ids.add("mailto:" + email);
        }
        if (github != null) {
            ids.add("github:" + github);
        }
        if (repository != null) {
            ids.add(Maintainers.REPOSITORY + repository);
        }
        return ids;
    }

    /** The identities of every maintainer in a list, deduplicated, in order. */
    public static Set<String> ids(Collection<Maintainer> maintainers) {
        Set<String> ids = new LinkedHashSet<>();
        for (Maintainer maintainer : maintainers) {
            ids.addAll(maintainer.ids());
        }
        return ids;
    }

    private static final Pattern PERSON = Pattern.compile("^\\s*([^<(]*?)\\s*(?:<([^>]*)>)?\\s*(?:\\(([^)]*)\\))?\\s*$");

    /** npm's person shorthand, {@code Name <email> (url)} with any part absent, where a GitHub profile url names
     *  the login; empty for a blank string. */
    public static Optional<Maintainer> person(String text) {
        if (blank(text)) {
            return Optional.empty();
        }
        Matcher person = PERSON.matcher(text);
        if (!person.matches()) {
            return Optional.of(new Maintainer(text, null, null));
        }
        return Optional.of(new Maintainer(person.group(1), person.group(2),
                githubLogin(person.group(3)).orElse(null)));
    }

    private static final Pattern GITHUB_URL = Pattern.compile(
            "^(?:git\\+)?(?:https?|ssh|git)://(?:[^@/]+@)?(?:www\\.)?github\\.com[/:]([A-Za-z0-9-]+)(?:[/?#].*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_SCP = Pattern.compile("^(?:git\\+)?git@github\\.com:([A-Za-z0-9-]+)(?:/.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_SHORTHAND = Pattern.compile("^github:([A-Za-z0-9-]+)(?:/.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SCM_PREFIX = Pattern.compile("^scm:git:", Pattern.CASE_INSENSITIVE);

    /**
     * The GitHub login a repository, scm or profile url names - {@code https://github.com/<login>/...},
     * {@code git+ssh://git@github.com/<login>/...}, {@code git@github.com:<login>/...}, Maven's
     * {@code scm:git:https://github.com/<login>/...}, npm's {@code github:<login>/<repo>} - or empty for any other
     * host. An organisation is a login too, and a lookup of one simply finds no keys.
     */
    public static Optional<String> githubLogin(String url) {
        if (blank(url)) {
            return Optional.empty();
        }
        String candidate = SCM_PREFIX.matcher(url.trim()).replaceFirst("");
        for (Pattern pattern : List.of(GITHUB_URL, GITHUB_SCP, GITHUB_SHORTHAND)) {
            Matcher matcher = pattern.matcher(candidate);
            if (matcher.matches()) {
                return Optional.of(matcher.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    private static final Pattern GITHUB_REPOSITORY_URL = Pattern.compile(
            "^(?:git\\+)?(?:https?|ssh|git)://(?:[^@/]+@)?(?:www\\.)?github\\.com[/:]([A-Za-z0-9-]+)/"
                    + "([A-Za-z0-9_.-]+?)(?:\\.git)?(?:[/?#].*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_REPOSITORY_SCP = Pattern.compile(
            "^(?:git\\+)?git@github\\.com:([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?:[/?#].*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_REPOSITORY_SHORTHAND = Pattern.compile(
            "^github:([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?:[/?#].*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITLAB_REPOSITORY = Pattern.compile(
            "^(?:git\\+)?(?:https?|ssh|git)://(?:[^@/]+@)?(?:www\\.)?gitlab\\.com/"
                    + "((?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.-]+?)(?:\\.git)?(?:[?#].*)?$", Pattern.CASE_INSENSITIVE);

    /**
     * The repository a url names, as {@code <host>/<owner>/<name>}: GitHub's in every spelling {@link #githubLogin}
     * reads, with the name after the owner ({@code .git} and anything past the name dropped), and GitLab's as the
     * whole group path before {@code /-/}. A url naming an owner alone, or any other host, names no repository.
     * Lower-cased, since both hosts are case-insensitive and a keyless identity spells it as the token did.
     */
    public static Optional<String> repository(String url) {
        if (blank(url)) {
            return Optional.empty();
        }
        String candidate = SCM_PREFIX.matcher(url.trim()).replaceFirst("");
        for (Pattern pattern : List.of(GITHUB_REPOSITORY_URL, GITHUB_REPOSITORY_SCP, GITHUB_REPOSITORY_SHORTHAND)) {
            Matcher matcher = pattern.matcher(candidate);
            if (matcher.matches()) {
                return Optional.of(("github.com/" + matcher.group(1) + "/" + matcher.group(2))
                        .toLowerCase(Locale.ROOT));
            }
        }
        // GitLab addresses a repository's pages under /-/ (tree, blob, tags); the repository is everything before it.
        int pages = candidate.indexOf("/-/");
        Matcher gitlab = GITLAB_REPOSITORY.matcher(pages < 0 ? candidate : candidate.substring(0, pages));
        if (gitlab.matches()) {
            return Optional.of(("gitlab.com/" + gitlab.group(1)).toLowerCase(Locale.ROOT));
        }
        return Optional.empty();
    }

    /** The owner and the repository a scm or repository url names - the GitHub login, the repository, either
     *  alone - or empty when the url names neither, which is any other host's owner. */
    public static Optional<Maintainer> ofRepository(String url) {
        String login = githubLogin(url).orElse(null);
        String repository = repository(url).orElse(null);
        return login == null && repository == null
                ? Optional.empty()
                : Optional.of(new Maintainer(null, null, login, repository));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
