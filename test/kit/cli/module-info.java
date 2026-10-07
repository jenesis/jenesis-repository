/**
 * The request every {@code jenrepo} action sends, as its endpoint binds it ({@code CliRequests}): the table the command
 * line's suite runs and the full product's census reads the other way round.
 *
 * <p>No JUnit and no assertion library: the class is data, nothing here provides a service, and the module is inert on
 * a runtime graph.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.cli.testkit {
    exports build.jenesis.repository.cli.testkit;
}
