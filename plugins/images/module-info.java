/**
 * Building the runnable images and the Helm charts, and publishing them, as a build module rather than a shell
 * script - shared by every repository that builds this product, each of which says in its own configuration what it
 * makes and publishes ({@code build.jenesis.images.Configuration}).
 *
 * <p><strong>Why this is a build module.</strong> Building an image is deterministic and derived entirely from
 * declared inputs - the staged contexts the {@code stage} goal writes - which is the definition of something the
 * executor should decide about. Modelled as a script it was decided by a person remembering to run it, and its
 * inputs were whatever happened to be on disk.
 *
 * <p><strong>Why the push is a step too.</strong> {@link build.jenesis.BuildStep#shouldRun} exists precisely to
 * defeat the incremental skip for an outward action, as the tool's own {@code MavenRepositoryExport} does. So
 * {@code Push.shouldRun} ignores whether anything changed: naming a target publishes, every time, and naming none
 * publishes nothing. The building half is genuinely different: it <em>is</em> derived from declared inputs, and a
 * second run printing {@code [SKIPPED]} is what that difference looks like.
 *
 * <p><strong>Why the charts ride the same goal.</strong> A deployment needs a chart and the image that chart names,
 * and the two disagreeing is the failure worth designing against - a chart published from one release pointing at
 * an image from another. Publishing both from one command, on one credential, off one set of declared inputs, is
 * what makes that hard rather than merely unlikely.
 *
 * <p><strong>How a repository uses it.</strong> As a releaser of the whole project: a line
 * {@code images+release=<path to this module>} in its {@code jenesis.plugins.properties}, and its configuration in
 * {@code build.jenesis/plugin-images.properties}, the deploy tree bound there as an input. The tool compiles it from
 * source and hands it everything {@code stage} wrote, so {@code java build/jenesis/Make.java
 * release/custom/plugin.images} builds the images, and a profile naming {@code push}, released with
 * {@code -Djenesis.release.publish=true}, publishes them.
 *
 * @jenesis.release 25
 */
module build.jenesis.images {
    requires build.jenesis;
    provides build.jenesis.BuildModuleFactory with build.jenesis.images.ImagesFactory;
}
