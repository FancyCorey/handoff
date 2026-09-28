package dev.handoff.desktop.bluetooth

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Guid
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Headset battery on Windows. Windows publishes the level reported through the hands-free
 * profile as the device property DEVPKEY_Bluetooth_Battery ({104EA319-6EE2-4701-BD47-8DDBF425BBE5}, 2)
 * on the headset's "Hands-Free AG" (BTHENUM\{0000111E-…}) device node. Read with the documented
 * Configuration Manager API; any failure simply means "no battery shown".
 */
internal object WinBattery {
    @Suppress("FunctionName")
    private interface CfgMgr32 : StdCallLibrary {
        @Structure.FieldOrder("fmtid", "pid")
        class DevPropKey : Structure(), Structure.ByReference {
            @JvmField var fmtid: Guid.GUID = Guid.GUID()
            @JvmField var pid: Int = 0
        }

        fun CM_Get_Device_ID_List_SizeW(size: IntByReference, filter: String?, flags: Int): Int
        fun CM_Get_Device_ID_ListW(filter: String?, buffer: CharArray, bufferLen: Int, flags: Int): Int
        fun CM_Locate_DevNodeW(devInst: IntByReference, deviceId: String, flags: Int): Int
        fun CM_Get_DevNode_PropertyW(
            devInst: Int,
            key: DevPropKey,
            type: IntByReference,
            buffer: Memory,
            size: IntByReference,
            flags: Int,
        ): Int
    }

    private val api: CfgMgr32? by lazy {
        try {
            Native.load("cfgmgr32", CfgMgr32::class.java, W32APIOptions.UNICODE_OPTIONS)
        } catch (_: Throwable) {
            null
        }
    }

    private const val CR_SUCCESS = 0
    private const val CM_GETIDLIST_FILTER_ENUMERATOR = 0x1
    private const val DEVPROP_TYPE_BYTE = 0x3
    private const val HANDS_FREE_AG = "BTHENUM\\{0000111E"

    /** Battery 0..100 for each requested address (upper-case "AA:BB:..."), where Windows knows it. */
    fun levels(addresses: Collection<String>): Map<String, Int> {
        val api = api ?: return emptyMap()
        if (addresses.isEmpty()) return emptyMap()
        return try {
            val ids = deviceIds(api).filter { it.startsWith(HANDS_FREE_AG, ignoreCase = true) }
            addresses.mapNotNull { address ->
                val hex = address.replace(":", "").uppercase()
                val id = ids.firstOrNull { it.uppercase().contains("&${hex}_") } ?: return@mapNotNull null
                battery(api, id)?.let { address.uppercase() to it }
            }.toMap()
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun deviceIds(api: CfgMgr32): List<String> {
        val size = IntByReference()
        if (api.CM_Get_Device_ID_List_SizeW(size, "BTHENUM", CM_GETIDLIST_FILTER_ENUMERATOR) != CR_SUCCESS) return emptyList()
        val buffer = CharArray(size.value.coerceAtLeast(1))
        if (api.CM_Get_Device_ID_ListW("BTHENUM", buffer, buffer.size, CM_GETIDLIST_FILTER_ENUMERATOR) != CR_SUCCESS) return emptyList()
        return String(buffer).split('\u0000').filter { it.isNotBlank() }
    }

    private fun battery(api: CfgMgr32, deviceId: String): Int? {
        val devInst = IntByReference()
        if (api.CM_Locate_DevNodeW(devInst, deviceId, 0) != CR_SUCCESS) return null
        val key = CfgMgr32.DevPropKey().apply {
            fmtid = Guid.GUID.fromString("{104EA319-6EE2-4701-BD47-8DDBF425BBE5}")
            pid = 2
        }
        val type = IntByReference()
        val buffer = Memory(8)
        val size = IntByReference(8)
        if (api.CM_Get_DevNode_PropertyW(devInst.value, key, type, buffer, size, 0) != CR_SUCCESS) return null
        if (type.value != DEVPROP_TYPE_BYTE) return null
        return (buffer.getByte(0).toInt() and 0xFF).takeIf { it in 0..100 }
    }
}
