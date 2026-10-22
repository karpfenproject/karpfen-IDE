package io.karpfen.ide.runtime.host

/**
 * Placeholder of the `runtime-host` module, kept from the phase-0 skeleton until a later increment builds it. The
 * module is to hold the hosts of the runtime: an IDE-owned Docker container by default, attaching to a running runtime,
 * and a local process. The README lists it among the modules of later increments.
 */
public object RuntimeHostModule {
    /** The module's name, which is also the name of its directory. */
    public const val ID: String = "runtime-host"
}
