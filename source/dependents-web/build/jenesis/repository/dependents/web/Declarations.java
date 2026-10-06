package build.jenesis.repository.dependents.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import build.jenesis.repository.closure.spi.RequirementGrammar;
import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ServableNames;

/**
 * The declared tier's rows as a surface shows them, on the API and the console alike.
 *
 * <p>A row names a version of this repository, so it is listed only while that version is still published and not
 * withheld - two point reads a row, over a page already bounded. The index catches up with a deletion on its next
 * full pass; until then this is what keeps a deleted or held version's name off both surfaces.
 *
 * <p>Asked about a version of the package, each row also says whether its requirement admits that version -
 * {@code admits}, {@code excludes} or {@code unknown} - as the declaring ecosystem's {@link RequirementGrammar} reads
 * it, the one a closure takes its versions by - a requirement stating no version admits every one. It is a marker on a
 * declaration, never a resolved dependency, and nothing counts it.
 */
public final class Declarations {

    /** The most rows one page of the declared tier holds. */
    static final int MAX_PAGE = 500;

    private Declarations() {
    }

    /** One declared row: the declaring version, the requirement it states, and - when a version was asked about -
     *  whether that requirement admits it; {@code null} when none was. */
    public record Row(String ecosystem, String coordinate, String version, String requirement, String admits) {
    }

    static List<Row> disclosable(StoreRepositoryInventory inventory, List<DependentsQuery.Declaration> page,
                                 String asked) throws IOException {
        List<Row> shown = new ArrayList<>();
        for (DependentsQuery.Declaration row : page) {
            if (inventory.publishedAt(row.ecosystem(), row.coordinate(), row.version()).isPresent()
                    && inventory.disclosable(row.ecosystem(), row.coordinate(), row.version(),
                    ServableNames.Policy.HIDE_WITHHELD)) {
                shown.add(new Row(row.ecosystem(), row.coordinate(), row.version(), row.requirement(),
                        asked == null || asked.isBlank() ? null : admits(row, asked)));
            }
        }
        return shown;
    }

    /** Whether {@code row}'s requirement admits {@code asked}, as the declaring ecosystem's grammar reads it. */
    private static String admits(DependentsQuery.Declaration row, String asked) {
        return RequirementGrammar.of(row.ecosystem()).admits(row.requirement(), asked).name().toLowerCase(Locale.ROOT);
    }
}
