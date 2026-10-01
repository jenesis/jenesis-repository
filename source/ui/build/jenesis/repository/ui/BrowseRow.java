package build.jenesis.repository.ui;

import module java.base;

/**
 * One row of the artifact browse as the shared tree fragment renders it, for the whole-store browse and the
 * repository-scoped one alike. The links are what differ between them, so a row carries them.
 *
 * @param name         the entry's own name, as it is shown.
 * @param folder       whether this is a folder, which is what makes the row expandable.
 * @param size         the size to show, already formatted - a folder and an unreadable leaf both show a dash.
 * @param depth        the indent level; the shared row multiplies it by the layout's step.
 * @param href         where the name links, or {@code null} for a row that is text rather than a link - which is
 *                     how a console with no artifact detail page renders a leaf.
 * @param childrenHref the URL the expand button fetches this folder's children from, or {@code null} for a leaf.
 *                     A folder with no children URL renders unexpandable rather than as a button that does nothing.
 */
public record BrowseRow(String name, boolean folder, String size, int depth, String href, String childrenHref) {

    /** Whether the name is a link, as opposed to plain text. */
    public boolean linked() {
        return href != null;
    }

    /** Whether the row offers the expand control. */
    public boolean expandable() {
        return folder && childrenHref != null;
    }
}
