/*
 * The approach of toggling a headset's Bluetooth audio services with BluetoothSetServiceState
 * (disable, then enable) to make Windows connect it is adapted from PodSwitch by Felip6499
 * (https://github.com/Felip6499/PodSwitch, windows/PodSwitch.App/Platform/WindowsBluetoothConnector.cs),
 * MIT License, Copyright (c) 2026 Felip6499. See THIRD_PARTY_NOTICES.md.
 */
package dev.handoff.desktop.bluetooth

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Minimal JNA binding to the public Win32 Bluetooth API (bluetoothapis.h, exported by
 * bthprops.cpl). Only documented functions are used.
 */
@Suppress("FunctionName", "PropertyName")
internal interface BthProps : StdCallLibrary {

    @Structure.FieldOrder(
        "dwSize", "fReturnAuthenticated", "fReturnRemembered", "fReturnUnknown",
        "fReturnConnected", "fIssueInquiry", "cTimeoutMultiplier", "hRadio",
    )
    class SearchParams : Structure() {
        @JvmField var dwSize: Int = 0
        @JvmField var fReturnAuthenticated: Int = 1
        @JvmField var fReturnRemembered: Int = 1
        @JvmField var fReturnUnknown: Int = 0
        @JvmField var fReturnConnected: Int = 1
        @JvmField var fIssueInquiry: Int = 0
        @JvmField var cTimeoutMultiplier: Byte = 0
        @JvmField var hRadio: WinNT.HANDLE? = null

        init {
            dwSize = size()
        }
    }

    @Structure.FieldOrder(
        "dwSize", "Address", "ulClassofDevice", "fConnected", "fRemembered", "fAuthenticated",
        "stLastSeen", "stLastUsed", "szName",
    )
    class DeviceInfo : Structure() {
        @JvmField var dwSize: Int = 0
        @JvmField var Address: Long = 0
        @JvmField var ulClassofDevice: Int = 0
        @JvmField var fConnected: Int = 0
        @JvmField var fRemembered: Int = 0
        @JvmField var fAuthenticated: Int = 0
        @JvmField var stLastSeen: WinBase.SYSTEMTIME = WinBase.SYSTEMTIME()
        @JvmField var stLastUsed: WinBase.SYSTEMTIME = WinBase.SYSTEMTIME()
        @JvmField var szName: CharArray = CharArray(BLUETOOTH_MAX_NAME_SIZE)

        init {
            dwSize = size()
        }

        val name: String get() = Native.toString(szName)
    }

    @Structure.FieldOrder("dwSize")
    class FindRadioParams : Structure() {
        @JvmField var dwSize: Int = 0

        init {
            dwSize = size()
        }
    }

    fun BluetoothFindFirstRadio(params: FindRadioParams, radio: WinNT.HANDLEByReference): WinNT.HANDLE?
    fun BluetoothFindRadioClose(find: WinNT.HANDLE): Boolean
    fun BluetoothFindFirstDevice(params: SearchParams, info: DeviceInfo): WinNT.HANDLE?
    fun BluetoothFindNextDevice(find: WinNT.HANDLE, info: DeviceInfo): Boolean
    fun BluetoothFindDeviceClose(find: WinNT.HANDLE): Boolean
    fun BluetoothGetDeviceInfo(radio: WinNT.HANDLE?, info: DeviceInfo): Int
    fun BluetoothSetServiceState(radio: WinNT.HANDLE?, info: DeviceInfo, service: Guid.GUID, flags: Int): Int
    fun BluetoothEnumerateInstalledServices(radio: WinNT.HANDLE?, info: DeviceInfo, count: com.sun.jna.ptr.IntByReference, services: Array<Guid.GUID>?): Int

    companion object {
        const val BLUETOOTH_MAX_NAME_SIZE = 248
        const val SERVICE_DISABLE = 0x00
        const val SERVICE_ENABLE = 0x01

        /** Null when the library can't be loaded (not Windows, or no Bluetooth stack). */
        @Volatile var loadError: String? = null
            private set

        val INSTANCE: BthProps? by lazy {
            // BluetoothApis.dll (Windows 8+) and the legacy bthprops.cpl export the same functions.
            for (library in listOf("BluetoothApis", "bthprops.cpl")) {
                try {
                    return@lazy Native.load(library, BthProps::class.java, W32APIOptions.UNICODE_OPTIONS)
                } catch (e: Throwable) {
                    loadError = "$library: ${e.message}"
                }
            }
            null
        }

        val A2DP_SINK: Guid.GUID = Guid.GUID.fromString("{0000110B-0000-1000-8000-00805F9B34FB}")
        val HANDS_FREE: Guid.GUID = Guid.GUID.fromString("{0000111E-0000-1000-8000-00805F9B34FB}")
        val HEADSET: Guid.GUID = Guid.GUID.fromString("{00001108-0000-1000-8000-00805F9B34FB}")
        val AVRCP_TARGET: Guid.GUID = Guid.GUID.fromString("{0000110C-0000-1000-8000-00805F9B34FB}")
        val AVRCP: Guid.GUID = Guid.GUID.fromString("{0000110E-0000-1000-8000-00805F9B34FB}")
    }
}

/** A remembered Bluetooth device as reported by Windows. */
internal data class WinBtDevice(
    val address: String,
    val name: String,
    val classOfDevice: Int,
    val connected: Boolean,
) {
    /** Major device class 0x04 = Audio/Video. */
    val isAudio: Boolean get() = (classOfDevice shr 8) and 0x1F == 0x04
    val minorClass: Int get() = (classOfDevice shr 2) and 0x3F
}

internal object Win32Bluetooth {
    private val api get() = BthProps.INSTANCE

    fun available(): Boolean = api != null

    /** True when an enabled Bluetooth radio is present. */
    fun radioOn(): Boolean {
        val api = api ?: return false
        return try {
            val radio = WinNT.HANDLEByReference()
            val find = api.BluetoothFindFirstRadio(BthProps.FindRadioParams(), radio) ?: return false
            api.BluetoothFindRadioClose(find)
            radio.value?.let { com.sun.jna.platform.win32.Kernel32.INSTANCE.CloseHandle(it) }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun rememberedDevices(): List<WinBtDevice> {
        val api = api ?: return emptyList()
        val out = mutableListOf<WinBtDevice>()
        try {
            val info = BthProps.DeviceInfo()
            val find = api.BluetoothFindFirstDevice(BthProps.SearchParams(), info) ?: return emptyList()
            try {
                do {
                    out += info.toDevice()
                } while (api.BluetoothFindNextDevice(find, info))
            } finally {
                api.BluetoothFindDeviceClose(find)
            }
        } catch (_: Throwable) {
            return out
        }
        return out.distinctBy { it.address }
    }

    fun device(address: String): WinBtDevice? = rememberedDevices().firstOrNull { it.address.equals(address, ignoreCase = true) }

    /**
     * Enable or disable one Bluetooth service for a remembered device. Returns the Win32 error
     * code (0 = success).
     */
    fun setServiceState(address: String, service: com.sun.jna.platform.win32.Guid.GUID, enable: Boolean): Int {
        val api = api ?: return -1
        return try {
            val info = infoFor(address) ?: return ERROR_NOT_FOUND
            api.BluetoothSetServiceState(null, info, service, if (enable) BthProps.SERVICE_ENABLE else BthProps.SERVICE_DISABLE)
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * The Bluetooth services Windows currently has enabled for a remembered device. A service
     * Handoff turned off earlier is missing from this list. Null if the device isn't found.
     */
    fun enabledServices(address: String): Set<String>? {
        val api = api ?: return null
        return try {
            val info = infoFor(address) ?: return null
            val count = com.sun.jna.ptr.IntByReference(MAX_SERVICES)
            @Suppress("UNCHECKED_CAST")
            val guids = Guid.GUID().toArray(MAX_SERVICES) as Array<Guid.GUID>
            if (api.BluetoothEnumerateInstalledServices(null, info, count, guids) != 0) return null
            guids.take(count.value).map { it.toGuidString().uppercase() }.toSet()
        } catch (_: Throwable) {
            null
        }
    }

    private fun infoFor(address: String): BthProps.DeviceInfo? {
        val api = api ?: return null
        val info = BthProps.DeviceInfo()
        val find = api.BluetoothFindFirstDevice(BthProps.SearchParams(), info) ?: return null
        try {
            do {
                if (formatAddress(info.Address).equals(address, ignoreCase = true)) return info
            } while (api.BluetoothFindNextDevice(find, info))
        } finally {
            api.BluetoothFindDeviceClose(find)
        }
        return null
    }

    private fun BthProps.DeviceInfo.toDevice() = WinBtDevice(
        address = formatAddress(Address),
        name = name.ifBlank { formatAddress(Address) },
        classOfDevice = ulClassofDevice,
        connected = fConnected != 0,
    )

    fun formatAddress(address: Long): String =
        (5 downTo 0).joinToString(":") { i -> "%02X".format((address ushr (8 * i)) and 0xFF) }

    const val ERROR_NOT_FOUND = 1168
    private const val MAX_SERVICES = 32
}
