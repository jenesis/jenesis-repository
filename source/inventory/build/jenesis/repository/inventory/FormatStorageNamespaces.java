package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.blobs.BlobRoots;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.maintenance.StorageNamespace;
import build.jenesis.repository.store.StoredListing;

/**
 * The storage-manifest registrar for the discovered formats: one {@link Declared} entry per installed format,
 * attributed to the format's own JPMS module, naming the artifact spaces that format's data lives in - its
 * {@code publish/<name>} pointer mirror and the {@code publish/quarantine/<name>} review mirror beside it, its
 * stored listings, and (for a blobs-namespace format) each declared blob root with its listings. A format module
 * carried none of this itself, so a format dropped from an image - or toggled off with {@code jenreg.<name>=false},
 * which the reclaiming passes treat identically - held data no manifest entry described: invisible to the orphaned-
 * data diagnostic, unreachable for the explicit purge, and the repository it lived in stayed refused by the
 * collector forever. With the entry persisted while the format is installed, its absence later reads exactly like
 * any other module's: the modules console names the leftovers, and {@code POST /api/admin/purge} reaps them after
 * the mandatory dry run.
 *
 * <p>The entries deliberately name no slice of the inventory's own record spaces ({@code published/<ecosystem>},
 * {@code meta/<ecosystem>}, ...): a manifest prefix has one owning module, and those spaces are the inventory's and
 * the metadata store's. An absent format's <em>records</em> are retired through the explicit forget-ecosystem verb
 * instead, which is also what lifts the collector's refusal - after which the format's now-unreferenced content
 * blobs are ordinary garbage the collector reclaims itself, and this entry's purge reaps the stray pointers and
 * listings that remain.
 *
 * <p>The registrar consults {@link RepositoryFormat#installed()}, so a toggled-off format re-registers nothing and
 * its persisted entry becomes an orphan candidate on the next boot - the same reading every reclaiming pass has of
 * the toggle. Its own self-attributed entry is empty: the registrar persists nothing of its own.
 */
public final class FormatStorageNamespaces implements StorageNamespace {

    @Override
    public List<Declared> declarations() {
        Map<String, Set<String>> byModule = new TreeMap<>();
        for (RepositoryFormat format : RepositoryFormat.installed()) {
            Set<String> prefixes = byModule.computeIfAbsent(
                    format.getClass().getModule().getName(), module -> new TreeSet<>());
            prefixes.add("publish/" + format.name());
            prefixes.add("publish/quarantine/" + format.name());
            prefixes.add(StoredListing.ROOT + format.name());
            if (format instanceof BlobRoots roots) {
                for (String root : roots.blobRoots()) {
                    prefixes.add(root);
                    prefixes.add(StoredListing.ROOT + root);
                }
            }
        }
        List<Declared> declared = new ArrayList<>();
        byModule.forEach((module, prefixes) ->
                declared.add(new Declared(module, prefixes, Set.of(), Set.of())));
        return declared;
    }
}
