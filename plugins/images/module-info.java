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
 * <p><strong>How a repository uses it.</strong> As an exporter of the whole project: a line
 * {@code images+export=<path to this module>} in its {@code jenesis.plugins.properties}, and its configuration as the
 * plugin's values in {@code jenesis.plugins.arguments.properties}, the deploy tree bound there as an input. The tool
 * compiles it from source and hands it everything {@code stage} wrote, so {@code java build/jenesis/Make.java
 * export/custom/images} builds the images, and a profile naming {@code images.push} publishes them.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.pin tool/module/build.jenesis 0.15.0 SHA-256/ee1b36b01510fd52343e074335cf99564dd4b3198f31568d4dd9d286f63b89b2
 */
module build.jenesis.images {
    requires build.jenesis;
    provides build.jenesis.BuildExecutorModule with build.jenesis.images.Images;
}
