package build.jenesis.repository.search.service.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.search.LicenseFacet;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.search.SearchQueryProvider;
import build.jenesis.repository.search.service.RepositorySearch;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;
import build.jenesis.repository.settings.Wizard;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped default of {@code full-text-search}, asserted where a deployment meets it: once in what the catalogue
 * declares - the value the settings screens and the generated reference render, the level it is set at and that the
 * wizards ask it - and once in what a repository with nothing set actually does, which is the leg with content,
 * because it asks for the answer rather than reading the constant. Every other suite names the dial it depends on,
 * so without this one the default could move with the whole lane green.
 */
class FullTextSearchDefaultTest {

    @TempDir
    Path root;

    @Test
    void the_catalogue_declares_it_off_for_each_repository_and_every_wizard_asks_it() {
        Setting declared = SettingsContributor.all().stream()
                .filter(setting -> setting.key().equals(SearchMode.SETTING)).findFirst().orElseThrow();

        assertThat(declared.defaultValue()).as("off until a repository asks for it").isEqualTo("false");
        assertThat(declared.kind()).isEqualTo(Setting.Kind.BOOLEAN);
        assertThat(declared.scope()).as("a repository's decision, with a tenant and a deployment default")
                .isEqualTo(Setting.Scope.REPOSITORY);
        assertThat(declared.localOnly()).isFalse();
        assertThat(declared.tier()).isEqualTo(Setting.Tier.ESSENTIAL);
        assertThat(Wizard.REPOSITORY.asks(declared)).as("the new repository's wizard asks it").isTrue();
        assertThat(Wizard.SETUP.asks(declared)).as("and the first boot's asks the default every repository inherits")
                .isTrue();
    }

    @Test
    void a_repository_with_nothing_set_answers_by_name_and_never_asks_the_index() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("acme")
                .scope("releases");
        new StoreRepositoryInventory(store).record(SearchTestFormat.ECOSYSTEM, "org.acme.lib", "1.0",
                Instant.parse("2026-09-30T00:00:00Z"));
        AnsweringIndex index = new AnsweringIndex();

        RepositorySearch.Answer answer = new RepositorySearch(Optional.of(index))
                .search(store, "acme/releases", key -> null, "org.acme", null, 10);

        assertThat(answer.mode()).isEqualTo(SearchMode.NAME);
        assertThat(answer.indexed()).isFalse();
        assertThat(answer.hits()).extracting(SearchQuery.Hit::display).containsExactly("org.acme.lib:1.0");
        assertThat(index.asked).as("an installed index that would answer is not asked").isFalse();
    }

    /** An index that would answer anything, so a repository that asked it would be seen asking. */
    private static final class AnsweringIndex implements SearchQueryProvider, SearchQuery {

        private boolean asked;

        @Override
        public SearchQuery over(ArtifactStore store, String scope) {
            return this;
        }

        @Override
        public Optional<Hits> search(String query, String cursor, int limit) {
            asked = true;
            return Optional.of(Hits.last(List.of(Hit.coordinate("test", "from-the-index", "1.0"))));
        }

        @Override
        public Optional<List<LicenseFacet>> licenses() {
            asked = true;
            return Optional.of(List.of());
        }
    }
}
