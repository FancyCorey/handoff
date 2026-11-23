package dev.handoff.bluetooth

internal enum class AclTransport(val label: String) {
    CLASSIC("Classic"),
    LE("LE"),
    UNSPECIFIED("transport unknown"),
}

/** Last observations from Android in one adapter lifetime. */
internal class AclObservations {
    private val devices = mutableMapOf<String, MutableMap<AclTransport, Boolean>>()

    @Synchronized
    fun record(address: String, transport: AclTransport, open: Boolean) {
        val transports = devices.getOrPut(address.uppercase()) { mutableMapOf() }
        if (transport == AclTransport.UNSPECIFIED) transports.clear() else transports.remove(AclTransport.UNSPECIFIED)
        transports[transport] = open
    }

    @Synchronized
    fun isLinked(address: String): Boolean? = devices[address.uppercase()]?.values?.any { it }

    @Synchronized
    fun clear() = devices.clear()
}
