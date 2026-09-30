package dev.handoff.bluetooth.reflection

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reflection must never crash the app. These stand-ins model every behaviour a framework
 * proxy class can show: working, refusing, missing, throwing SecurityException, throwing
 * anything else, hanging, or returning the wrong type.
 */
class HiddenMethodInvokerTest {
    class Device

    @Suppress("unused", "UNUSED_PARAMETER")
    class WorkingProxy {
        fun connect(device: Device): Boolean = true
        fun disconnect(device: Device): Boolean = false
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    class HostileProxy {
        fun connect(device: Device): Boolean = throw SecurityException("Need BLUETOOTH_PRIVILEGED")
        fun disconnect(device: Device): Boolean = throw IllegalStateException("stack crashed")
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    class SlowProxy {
        fun connect(device: Device): Boolean {
            Thread.sleep(2_000)
            return true
        }
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    class WrongTypeProxy {
        fun connect(device: Device): Int = 1
    }

    @Suppress("unused")
    class BatteryProxy {
        var calls = 0
        fun getBatteryLevel(): Int {
            calls++
            return 80
        }
        fun getBlockedLevel(): Int {
            calls++
            throw SecurityException("system API")
        }
        fun getBrokenLevel(): Int {
            calls++
            throw NoClassDefFoundError("missing framework class")
        }
    }

    private val invoker = HiddenMethodInvoker(callTimeoutMs = 300)

    private fun call(target: Any, name: String) = runBlocking {
        invoker.invokeBoolean(target, name, Device(), Device::class.java)
    }

    @Test
    fun `boolean results are returned`() {
        assertEquals(HiddenMethodInvoker.Invocation.Returned(true), call(WorkingProxy(), "connect"))
        assertEquals(HiddenMethodInvoker.Invocation.Returned(false), call(WorkingProxy(), "disconnect"))
    }

    @Test
    fun `missing method is reported, not thrown`() {
        val result = call(Any(), "connect")
        assertTrue(result is HiddenMethodInvoker.Invocation.MethodMissing)
    }

    @Test
    fun `security exception inside the call becomes Denied`() {
        val result = call(HostileProxy(), "connect")
        assertTrue("$result", result is HiddenMethodInvoker.Invocation.Denied)
    }

    @Test
    fun `other exceptions inside the call become Threw`() {
        val result = call(HostileProxy(), "disconnect")
        assertEquals(HiddenMethodInvoker.Invocation.Threw("IllegalStateException"), result)
    }

    @Test
    fun `hanging call times out without blocking the caller`() {
        val started = System.currentTimeMillis()
        assertEquals(HiddenMethodInvoker.Invocation.TimedOut, call(SlowProxy(), "connect"))
        assertTrue(System.currentTimeMillis() - started < 1_500)
    }

    @Test
    fun `unexpected return type is treated as missing`() {
        assertTrue(invoker.resolve(WrongTypeProxy::class.java, "connect", Device::class.java) is HiddenMethodInvoker.Resolution.Missing)
    }

    @Test
    fun `resolution is cached`() {
        val a = invoker.resolve(WorkingProxy::class.java, "connect", Device::class.java)
        val b = invoker.resolve(WorkingProxy::class.java, "connect", Device::class.java)
        assertTrue(a === b)
    }

    @Test
    fun `optional int reads return the value`() = runBlocking {
        assertEquals(80, invoker.invokeIntOrNull(BatteryProxy(), "getBatteryLevel"))
    }

    @Test
    fun `optional int reads never throw and stop calling a blocked method`() = runBlocking {
        val proxy = BatteryProxy()
        assertEquals(null, invoker.invokeIntOrNull(proxy, "getBlockedLevel"))
        assertEquals(null, invoker.invokeIntOrNull(proxy, "getBlockedLevel"))
        assertEquals(1, proxy.calls)
        assertEquals(null, invoker.invokeIntOrNull(proxy, "getBrokenLevel"))
        assertEquals(null, invoker.invokeIntOrNull(proxy, "getBrokenLevel"))
        assertEquals(2, proxy.calls)
        assertEquals(null, invoker.invokeIntOrNull(proxy, "noSuchMethod"))
    }
}
