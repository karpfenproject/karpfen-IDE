package io.karpfen.ide.runtime.host

/**
 * Phase-0 stub of the `runtime-host` module: RuntimeHost interface with the IDE-owned Docker runtime (default),
 * attach and process hosts.
 * The module map of the architecture proposal (`docs/architecture-proposal.md`) shows where it sits.
 */
public object RuntimeHostModule {
    /** The module's name as the module map and the build use it. */
    public const val ID: String = "runtime-host"
}
