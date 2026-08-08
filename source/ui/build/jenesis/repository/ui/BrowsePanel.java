package build.jenesis.repository.ui;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.ScreenedNames;

import module java.base;

/**
 * The bundled browse panel: the console's entry point into the generic artifact browse ({@link BrowseController} at
 * {@code /browse}), replacing the former flat placeholder dump. It links into the breadcrumbed lazy tree and previews
 * the repository's top-level published namespaces (the formats' request-path roots) as quick links, so the console is
 * usable out of the box and a plugged-in panel has a worked example to copy. It reads only the first level of the
 * {@code publish/} pointer tree through the {@link ArtifactStore} - never an artifact blob. Repository-derived names
 * are HTML-escaped before they are placed in the fragment (the shell drops the body in unescaped).
 */
public final class BrowsePanel implements Panel {

    /** How many top-level namespace quick links the panel renders (and examines) - a format's request-path root, so a
     *  realistic store has a handful; the cap is what keeps a panel render bounded regardless. */
    private static final int NAMESPACES = 1_000;

    @Override
    public String id() {
        return "browse";
    }

    @Override
    public String title() {
        return "Browse";
    }

    @Override
    public String render(ArtifactStore store) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<p>Browse the repository's published artifacts as a breadcrumbed, navigable tree.</p>");
        html.append("<p><a href=\"/browse\" role=\"button\" class=\"secondary\">Open the repository browser &rarr;</a></p>");
        // The top-level namespaces come from the shared screened enumeration, so this panel lists exactly what the
        // browse it links into would show. Every root child is a namespace directory (a container), so it forwards on
        // its own listing's screen rather than being screened as a leaf; the reserved publish/quarantine review subtree
        // is suppressed by the seam itself - it is not a browsable namespace (a GET withholds it and the /assets export
        // never walks it). Bounded: a quick-link row is a namespace, and a store with more than the cap has an
        // operator problem the panel must not turn into an unbounded read.
        List<String> namespaces = new ArrayList<>();
        ScreenedNames.paths(new ServableNames(store), ServableNames.Policy.HIDE_WITHHELD_AND_GONE)
                .containers(_ -> true)
                .scanning(BoundedChildren.bounded().entries(NAMESPACES).page(NAMESPACES))
                .scan(store, ServableNames.PUBLISHED, (name, _) -> namespaces.add(name));
        if (namespaces.isEmpty()) {
            html.append("<p>The repository is empty. Publish an artifact to see it here.</p>");
            return html.toString();
        }
        html.append("<p>Published namespaces:</p><ul>");
        for (String name : namespaces) {
            html.append("<li><a href=\"/browse?path=").append(urlEscape(name)).append("\">")
                    .append(escape(name)).append("</a></li>");
        }
        html.append("</ul>");
        return html.toString();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /** Percent-encode a namespace name for the {@code path} query parameter, so a name is safe in the href. */
    private static String urlEscape(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(Character.forDigit((c >> 4) & 0xF, 16))
                        .append(Character.forDigit(c & 0xF, 16));
            }
        }
        return encoded.toString();
    }
}
