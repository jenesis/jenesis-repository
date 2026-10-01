package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the folder-listing dial beside the {@link FolderListing} that reads it: a repository setting, inheriting
 * its tenant's and the deployment's value, off by default and read per request, so it applies as soon as it is set.
 */
public final class FolderListingSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(new Setting(FolderListing.SETTING, "Serving", "Folder listings",
                "Answer a folder URL of a Maven repository - a path ending in / - with a page listing what it serves, "
                        + "a thousand names at a time, for the clients that list a folder where maven-metadata.xml "
                        + "is missing (Coursier, sbt) and for people browsing. Off by default: a page costs a store "
                        + "listing per name it shows, which a repository whose clients read the metadata never "
                        + "needs.",
                Setting.Kind.BOOLEAN, FolderListing.DEFAULT, true, Setting.Scope.REPOSITORY).advanced());
    }
}
