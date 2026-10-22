package io.karpfen.ide.runtime.session

/**
 * Phase-0 stub of the `runtime-session` module: deploy pipeline from the environment descriptor, live model mirror,
 * event injector and trace stream.
 * The module map of the architecture proposal (`docs/architecture-proposal.md`) shows where it sits.
 */
public object RuntimeSessionModule {
    /** The module's name as the module map and the build use it. */
    public const val ID: String = "runtime-session"
}
