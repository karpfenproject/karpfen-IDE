package io.karpfen.ide.runtime.host

/**
 * Phase-0 stub of the `runtime-host` module: RuntimeHost interface with the IDE-owned Docker runtime (default),
 * attach and process hosts.
 * See docs/architecture-proposal.md §4 for the module map.
 */
public object RuntimeHostModule {
    public const val ID: String = "runtime-host"
}
