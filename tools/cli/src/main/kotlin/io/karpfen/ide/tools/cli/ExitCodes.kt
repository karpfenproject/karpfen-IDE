package io.karpfen.ide.tools.cli

/** Exit statuses of the karpfen command-line tool. */
public object ExitCodes {
    /** The usage was printed, or the validation found no ERROR. */
    public const val OK: Int = 0

    /** The validation found at least one ERROR. */
    public const val ERRORS_FOUND: Int = 1

    /** Invalid command line, or the directory to validate does not exist. */
    public const val USAGE: Int = 2

    /** The validation failed unexpectedly (a defect; the cause is logged). */
    public const val FAILURE: Int = 3
}
