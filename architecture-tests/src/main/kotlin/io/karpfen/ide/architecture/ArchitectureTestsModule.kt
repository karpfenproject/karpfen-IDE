package io.karpfen.ide.architecture

/**
 * Production code of the `architecture-tests` module, whose Konsist rules live in the test source set. The rules keep
 * the layers, the adapters of foreign APIs and the package layout of every module in shape; this object gives the
 * module the production file that `PackageLayoutTest` expects of every module in the module table.
 */
public object ArchitectureTestsModule {
    /** The module's name, which is also the name of its directory. */
    public const val ID: String = "architecture-tests"
}
