package io.karpfen.ide.runtime.protocol

/**
 * Phase-0 stub of the `runtime-protocol` module: typed HTTP/WebSocket client for the karpfen runtime and the
 * environment lifecycle state machine.
 * The module map of the architecture proposal (`docs/architecture-proposal.md`) shows where it sits.
 */
public object RuntimeProtocolModule {
    /** The module's name as the module map and the build use it. */
    public const val ID: String = "runtime-protocol"
}
