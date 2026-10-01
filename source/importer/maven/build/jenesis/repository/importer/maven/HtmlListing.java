package build.jenesis.repository.importer.maven;

import module java.base;

/**
 * Extracts a directory listing from an autoindex page as any generator serves one - nginx, Apache httpd, Nexus,
 * Artifactory - without pinning a markup. Every {@code href} is resolved against the listing's URL and kept if it is a
 * <em>direct child</em> (one more segment, a trailing slash marking a subdirectory) or a <em>canonical file link</em> -
 * same scheme and authority, no query or fragment, a path ending with the walked directory's relative path plus one
 * file name, as Nexus's index rows link their files. Parent links, sort links, assets, other hosts and fragments are
 * ignored. Entries are deduplicated by name and sorted, so the walk and its resume cursor are deterministic.
 */
final class HtmlListing {

    private HtmlListing() {
    }

    /** One entry: the percent-decoded {@code name} and its still-encoded {@code raw} single segment (a decoded
     *  separator or traversal is rejected at parse), whether it is a subdirectory, and the URL to fetch it from. */
    record Entry(String name, String raw, boolean directory, URI target) {
    }

    /** Parse a directory's page into its entries, sorted by name; {@code rawPath} is the directory's encoded path
     *  relative to the root (empty at the root), the suffix a canonical file link must carry. Empty means no listing
     *  here. */
    static List<Entry> parse(URI directory, String rawPath, String page) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (String href : hrefs(page)) {
            Entry entry = resolve(directory, rawPath, href);
            if (entry != null) {
                entries.putIfAbsent(entry.name(), entry);
            }
        }
        List<Entry> sorted = new ArrayList<>(entries.values());
        sorted.sort(Comparator.comparing(Entry::name));
        return sorted;
    }

    /** The listing a non-listing root page advertises, or {@code null}. A Nexus repository root answers a landing page
     *  that links its HTML index - a same-authority, query-less link whose path ends with the root's last segment - so
     *  the walk follows that one hop, read off the page rather than assumed at a vendor path. */
    static URI listingPointer(URI root, String page) {
        String path = root.getRawPath();
        int slash = path.lastIndexOf('/', path.length() - 2);
        if (path.length() < 2 || slash < 0) {
            return null;   // a walk rooted at the host itself has no name to recognize a pointer by
        }
        String suffix = path.substring(slash);   // "/<last-segment>/"
        for (String href : hrefs(page)) {
            URI target;
            try {
                target = root.resolve(href).normalize();
            } catch (IllegalArgumentException invalid) {
                continue;
            }
            if (target.getRawQuery() == null && target.getRawFragment() == null
                    && sameAuthority(root, target)
                    && target.getRawPath() != null && target.getRawPath().endsWith(suffix)
                    && !target.getRawPath().equals(path)) {
                return target;
            }
        }
        return null;
    }

    /** Every href attribute value on the page, entity-unescaped and trimmed. */
    private static List<String> hrefs(String page) {
        List<String> values = new ArrayList<>();
        String lower = page.toLowerCase(Locale.ROOT);
        int at = 0;
        while ((at = lower.indexOf("href", at)) != -1) {
            at += 4;
            int index = at;
            while (index < page.length() && Character.isWhitespace(page.charAt(index))) {
                index++;
            }
            if (index >= page.length() || page.charAt(index) != '=') {
                continue;
            }
            index++;
            while (index < page.length() && Character.isWhitespace(page.charAt(index))) {
                index++;
            }
            if (index >= page.length()) {
                break;
            }
            char quote = page.charAt(index);
            String value;
            if (quote == '"' || quote == '\'') {
                int end = page.indexOf(quote, index + 1);
                if (end == -1) {
                    break;
                }
                value = page.substring(index + 1, end);
                at = end + 1;
            } else {
                int end = index;
                while (end < page.length() && !Character.isWhitespace(page.charAt(end)) && page.charAt(end) != '>') {
                    end++;
                }
                value = page.substring(index, end);
                at = end;
            }
            values.add(unescape(value.trim()));
        }
        return values;
    }

    /** Resolve one href against the listing's directory into an entry, or {@code null} for navigation chrome. */
    private static Entry resolve(URI directory, String rawPath, String href) {
        if (href.isEmpty() || href.startsWith("#")) {
            return null;
        }
        URI target;
        try {
            target = directory.resolve(href).normalize();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (target.getRawQuery() != null || target.getRawFragment() != null) {
            return null;
        }
        String base = directory.toString(), resolved = target.toString();
        if (resolved.startsWith(base)) {
            String rest = resolved.substring(base.length());
            boolean subdirectory = rest.endsWith("/");
            String segment = subdirectory ? rest.substring(0, rest.length() - 1) : rest;
            return segment.isEmpty() || segment.indexOf('/') >= 0 ? null : entry(segment, subdirectory, target);
        }
        // The canonical file link: same authority, the walked directory's relative path plus one name.
        if (!sameAuthority(directory, target) || target.getRawPath() == null || target.getRawPath().endsWith("/")) {
            return null;
        }
        String path = target.getRawPath();
        String segment = path.substring(path.lastIndexOf('/') + 1);
        return path.endsWith("/" + rawPath + segment) ? entry(segment, false, target) : null;
    }

    private static Entry entry(String segment, boolean subdirectory, URI target) {
        String name = decode(segment);
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            return null;
        }
        return new Entry(name, segment, subdirectory, target);
    }

    private static boolean sameAuthority(URI left, URI right) {
        return Objects.equals(left.getScheme(), right.getScheme())
                && Objects.equals(left.getRawAuthority(), right.getRawAuthority());
    }

    /** The few entities autoindex generators actually emit in an href. */
    private static String unescape(String href) {
        return href.indexOf('&') < 0 ? href : href
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    /** Percent-decode a path segment as UTF-8; a plus stays literal (path, not form, encoding) and a malformed escape
     *  stays. */
    private static String decode(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        byte[] raw = segment.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        for (int at = 0; at < raw.length; at++) {
            if (raw[at] == '%' && at + 2 < raw.length) {
                int high = Character.digit(raw[at + 1], 16), low = Character.digit(raw[at + 2], 16);
                if (high >= 0 && low >= 0) {
                    out.write(high << 4 | low);
                    at += 2;
                    continue;
                }
            }
            out.write(raw[at]);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
