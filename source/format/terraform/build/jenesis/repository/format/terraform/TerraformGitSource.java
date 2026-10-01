package build.jenesis.repository.format.terraform;

import module java.base;

/**
 * A module source Terraform would clone with git, read instead as one ref's archive served over HTTP, so a proxied
 * module whose registry names a git repository downloads through this repository.
 *
 * <p>Most public modules are git sources ({@code git::https://github.com/<owner>/<repo>?ref=<tag>}), which a client
 * clones around this repository. The three hosts carrying nearly all of them serve any ref as a {@code .tar.gz} with
 * one top-level directory, so a ref is fetched, cached and served as that archive, and the client told to take the
 * directory inside, then the source's own {@code //subdir}; the directory is named from the archive's first entry:
 * <ul>
 *   <li>{@code github}: {@code <host>/<owner>/<repo>/archive/<ref>.tar.gz}, as GitHub Enterprise Server too;</li>
 *   <li>{@code gitlab}: {@code <host>/<group>/.../<project>/-/archive/<ref>/<project>-<ref>.tar.gz};</li>
 *   <li>{@code bitbucket}: {@code <host>/<owner>/<repo>/get/<ref>.tar.gz}.</li>
 * </ul>
 *
 * <p><b>Only hosts the operator lists</b> ({@value #HOSTS}), shipped empty since fetching from a git host reaches a
 * third party. Any other source - another host, SSH, a {@code git@} address, no {@code ref}, options beyond
 * {@code depth} - is relayed as written, or refused when {@value #REFUSE} is on. An entry is a host as the source
 * writes it, port included, followed by {@code =} and its kind unless it is {@code github.com}, {@code gitlab.com} or
 * {@code bitbucket.org}.
 *
 * <p>A ref may name different bytes over time and nothing declares its checksum; {@link #identity} keys the digest the
 * first fetch records.
 *
 * @param archive where the ref's archive is fetched
 * @param subdirectory the source's {@code //subdir}, or empty
 * @param identity the host, repository and ref one recorded digest answers for
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
