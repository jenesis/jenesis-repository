package build.jenesis.repository.ui;

import module java.base;

/**
 * What every page about one repository opens with: its name, the format it holds and the URL a client reaches it at.
 * The console resolves it from the request path, so a contributed repository page needs to resolve nothing.
 *
 * @param name   the repository
 * @param format the format it holds, or {@code null} when it holds none
 * @param url    the URL a client reaches it at, or {@code null} when it holds no format and so answers no URL
 */
public record RepositoryHeader(String name, String format, String url) {

    public RepositoryHeader {
        Objects.requireNonNull(name, "name");
    }

    /** Whether the repository holds a format, and so has a URL to show. */
    public boolean served() {
        return format != null;
    }
}
