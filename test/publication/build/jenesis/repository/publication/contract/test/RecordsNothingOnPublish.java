package build.jenesis.repository.publication.contract.test;

/**
 * A fixture whose hook records nothing for an accepted publish, and says why - so the census can accept that the
 * kit's recording clauses have nothing to falsify for it, on the fixture's own stated reason rather than a list the
 * census keeps of hooks it has heard of.
 */
interface RecordsNothingOnPublish {

    /** Why the recording clauses cannot bite, and where the hook's real effect is proven. */
    String whyNothingOnPublish();
}
