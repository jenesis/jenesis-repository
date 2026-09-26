package build.jenesis.repository.format.terraform;

import module java.base;

/**
 * A module source Terraform would clone with git, read instead as the archive of one ref that a git host serves over
 * HTTP - which is what lets a proxied module whose registry names a git repository download through this repository.
 *
 * <p>Most public modules are git sources: registry.terraform.io answers a module's download with
 * {@code git::https://github.com/<owner>/<repo>?ref=<tag>}, and a client given that clones it, reaching the git host
 * directly and around this repository. The three hosts that carry nearly all of them each serve any ref as a
 * {@code .tar.gz} with one top-level directory, so a ref is fetched, cached and served as that archive, and the
 * client is told to take the directory inside it, followed by the source's own {@code //subdir}. The directory is
 * named as the archive's first entry names it, since the host chooses it and Terraform does not expand a glob in a
 * registry module's subdirectory:
 *
 * <ul>
 *   <li>{@code github}: {@code <host>/<owner>/<repo>/archive/<ref>.tar.gz}, which GitHub Enterprise Server shares;</li>
 *   <li>{@code gitlab}: {@code <host>/<group>/.../<project>/-/archive/<ref>/<project>-<ref>.tar.gz};</li>
 *   <li>{@code bitbucket}: {@code <host>/<owner>/<repo>/get/<ref>.tar.gz}.</li>
 * </ul>
 *
 * <p><b>Only hosts the operator lists</b> ({@value #HOSTS}): a source on any other host - and every source this
 * cannot read as one ref of one repository, an SSH URL, a {@code git@} address, a source with no {@code ref} or with
 * options beyond {@code depth} - is relayed as the upstream wrote it, or refused when {@value #REFUSE} is on. The list
 * ships empty, since fetching from a git host is reaching a third party. An entry is a host as the source writes it,
 * port included, and names its kind after an {@code =} unless the host is {@code github.com}, {@code gitlab.com} or
 * {@code bitbucket.org}, whose kinds are known.
 *
 * <p>A ref is a tag, a branch or a commit, so the same source can name different bytes over time and no upstream
 * declares a checksum for any of them. {@link #identity} is what the digest recorded on the first fetch is keyed on.
 *
 * @param archive      where the ref's archive is fetched
 * @param subdirectory the source's {@code //subdir}, or empty
 * @param identity     the host, repository and ref, which is what one recorded digest answers for
 */
record TerraformGitSource(URI archive, String subdirectory, String identity) {

    /** The git hosts a proxied module's source may be fetched from, as {@code host} or {@code host=kind} entries. */
    static final String HOSTS = "terraform.git-hosts";

    /** Whether a git source that cannot be fetched through this repository is refused rather than relayed. */
    static final String REFUSE = "terraform.git-refuse-unlisted";

    private static final Map<String, String> KNOWN = Map.of("github.com", "github", "gitlab.com", "gitlab",
            "bitbucket.org", "bitbucket");

    private static final Set<String> KINDS = Set.of("github", "gitlab", "bitbucket");

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");

    /** Whether {@code source} is one Terraform would fetch with git: a {@code git::} source, or the GitHub and
     *  Bitbucket shorthands Terraform detects as git. */
    static boolean git(String source) {
        String stripped = source.strip();
        return stripped.startsWith("git::") || stripped.startsWith("github.com/")
                || stripped.startsWith("bitbucket.org/");
    }

    /**
     * {@code source} read as one ref's archive on a host {@code hosts} lists, or empty when it is not a git source,
     * names no listed host, or is not a shape one archive can stand for.
     */
    static Optional<TerraformGitSource> of(String source, String hosts) {
        String url = source.strip();
        if (url.startsWith("git::")) {
            url = url.substring("git::".length());
        } else if (git(url)) {
            url = "https://" + url;
        } else {
            return Optional.empty();
        }
        int separator = url.indexOf("://");
        if (separator < 0) {
            return Optional.empty();
        }
        String scheme = url.substring(0, separator).toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return Optional.empty();
        }
        String query = "";
        int question = url.indexOf('?');
        if (question >= 0) {
            query = url.substring(question + 1);
            url = url.substring(0, question);
        }
        String rest = url.substring(separator + "://".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return Optional.empty();
        }
        String authority = rest.substring(0, slash);
        String path = rest.substring(slash + 1);
        String subdirectory = "";
        int split = path.indexOf("//");
        if (split >= 0) {
            subdirectory = path.substring(split + 2).replaceAll("/+$", "");
            path = path.substring(0, split);
        }
        String ref = null;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            if (name.equals("ref")) {
                ref = URLDecoder.decode(equals < 0 ? "" : pair.substring(equals + 1), StandardCharsets.UTF_8);
            } else if (!name.equals("depth")) {
                return Optional.empty();
            }
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - ".git".length());
        }
        List<String> repository = List.of(path.split("/"));
        if (ref == null || !named(List.of(ref)) || repository.size() < 2 || !named(repository)
                || !subdirectory.isEmpty() && !named(List.of(subdirectory.split("/")))) {
            return Optional.empty();
        }
        String kind = kind(authority, hosts);
        String base = scheme + "://" + authority + "/" + path;
        String archive = switch (kind == null ? "" : kind) {
            case "github" -> repository.size() == 2 ? base + "/archive/" + ref + ".tar.gz" : null;
            case "bitbucket" -> repository.size() == 2 ? base + "/get/" + ref + ".tar.gz" : null;
            case "gitlab" -> base + "/-/archive/" + ref + "/" + repository.getLast() + "-" + ref + ".tar.gz";
            default -> null;
        };
        return archive == null ? Optional.empty() : Optional.of(new TerraformGitSource(URI.create(archive),
                subdirectory, authority.toLowerCase(Locale.ROOT) + "/" + path + "@" + ref));
    }

    /** The kind of git host {@code authority} is, as {@code hosts} lists it, or {@code null} when it is not listed. */
    private static String kind(String authority, String hosts) {
        if (hosts == null) {
            return null;
        }
        for (String entry : hosts.split("[,\\s]+")) {
            int equals = entry.indexOf('=');
            String host = (equals < 0 ? entry : entry.substring(0, equals)).strip();
            if (host.isEmpty() || !host.equalsIgnoreCase(authority)) {
                continue;
            }
            String kind = equals < 0 ? KNOWN.get(host.toLowerCase(Locale.ROOT))
                    : entry.substring(equals + 1).strip().toLowerCase(Locale.ROOT);
            return kind != null && KINDS.contains(kind) ? kind : null;
        }
        return null;
    }

    private static boolean named(List<String> segments) {
        return segments.stream().allMatch(segment -> NAME.matcher(segment).matches()
                && !segment.equals(".") && !segment.equals(".."));
    }
}
