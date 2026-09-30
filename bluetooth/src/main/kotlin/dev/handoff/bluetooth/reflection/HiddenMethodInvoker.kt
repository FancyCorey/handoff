/*
 * Calling hidden profile methods through reflection on the profile proxy is adapted from
 * PodSwitch by Felip6499 (https://github.com/Felip6499/PodSwitch), MIT License,
 * Copyright (c) 2026 Felip6499. See THIRD_PARTY_NOTICES.md for the full license text.
 */
package dev.handoff.bluetooth.reflection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * The single place in Handoff that performs reflective calls. Every failure mode of
 * reflection is converted into a value; nothing thrown here can escape to callers.
 *
 * Kept free of Android types so its failure handling is unit-tested on the JVM.
 */
internal class HiddenMethodInvoker(
    private val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    /** Calls that exceed the timeout keep running here and are abandoned, never awaited. */
    private val callScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    sealed interface Resolution {
        data class Found(val method: Method) : Resolution

        /** Absent on this build, or blocked by the non-SDK interface policy (indistinguishable). */
        data class Missing(val detail: String) : Resolution
    }

    sealed interface Invocation {
        data class Returned(val value: Boolean) : Invocation
        data class MethodMissing(val detail: String) : Invocation
        data class Denied(val detail: String) : Invocation
        data class Threw(val detail: String) : Invocation
        data object TimedOut : Invocation
    }

    private val cache = ConcurrentHashMap<String, Resolution>()

    fun resolve(target: Class<*>, name: String, parameterType: Class<*>): Resolution =
        cache.getOrPut("${target.name}#$name(${parameterType.name})") {
            try {
                val method = target.getMethod(name, parameterType)
                if (method.returnType != java.lang.Boolean.TYPE && method.returnType != Boolean::class.javaObjectType) {
                    Resolution.Missing("$name() has unexpected return type ${method.returnType.simpleName}")
                } else {
                    Resolution.Found(method)
                }
            } catch (e: NoSuchMethodException) {
                Resolution.Missing("$name() not found or blocked by the non-SDK interface policy")
            } catch (e: SecurityException) {
                Resolution.Missing("$name() access denied: ${e.javaClass.simpleName}")
            } catch (e: LinkageError) {
                Resolution.Missing("$name() could not be linked: ${e.javaClass.simpleName}")
            } catch (e: RuntimeException) {
                Resolution.Missing("$name() resolution failed: ${e.javaClass.simpleName}")
            }
        }

    suspend fun invokeBoolean(target: Any, name: String, argument: Any, parameterType: Class<*>): Invocation {
        val method = when (val r = resolve(target.javaClass, name, parameterType)) {
            is Resolution.Found -> r.method
            is Resolution.Missing -> return Invocation.MethodMissing(r.detail)
        }
        val call = callScope.async { invokeBlocking(method, target, argument) }
        return withTimeoutOrNull(callTimeoutMs) { call.await() } ?: Invocation.TimedOut
    }

    /** Methods found to be absent, blocked or unusable; never looked up or called again. */
    private val unusableIntMethods = ConcurrentHashMap.newKeySet<String>()

    /**
     * Reflectively call a no-argument method returning `int`; null for any failure. A method
     * that is missing, blocked or refuses with SecurityException is remembered and skipped from
     * then on, so a blocked optional read (such as battery level) costs nothing afterwards.
     */
    suspend fun invokeIntOrNull(target: Any, name: String): Int? {
        val key = "${target.javaClass.name}#$name()"
        if (key in unusableIntMethods) return null
        val method = try {
            target.javaClass.getMethod(name).takeIf { it.returnType == Integer.TYPE }
        } catch (_: NoSuchMethodException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: LinkageError) {
            null
        } ?: run {
            unusableIntMethods += key
            return null
        }
        val call = callScope.async {
            try {
                method.invoke(target) as? Int
            } catch (e: InvocationTargetException) {
                if (e.targetException is SecurityException || e.targetException is LinkageError) unusableIntMethods += key
                null
            } catch (_: IllegalAccessException) {
                unusableIntMethods += key
                null
            } catch (_: RuntimeException) {
                null
            } catch (_: LinkageError) {
                unusableIntMethods += key
                null
            }
        }
        return withTimeoutOrNull(callTimeoutMs) { call.await() }
    }

    private fun invokeBlocking(method: Method, target: Any, argument: Any): Invocation = try {
        when (val value = method.invoke(target, argument)) {
            is Boolean -> Invocation.Returned(value)
            else -> Invocation.Threw("unexpected return value ${value?.javaClass?.simpleName}")
        }
    } catch (e: InvocationTargetException) {
        when (val cause = e.targetException) {
            is SecurityException -> Invocation.Denied(cause.message ?: "SecurityException")
            else -> Invocation.Threw(cause?.javaClass?.simpleName ?: "InvocationTargetException")
        }
    } catch (e: IllegalAccessException) {
        Invocation.MethodMissing("${method.name}() not accessible: ${e.message}")
    } catch (e: SecurityException) {
        Invocation.Denied(e.message ?: "SecurityException")
    } catch (e: IllegalArgumentException) {
        Invocation.Threw("IllegalArgumentException")
    } catch (e: RuntimeException) {
        Invocation.Threw(e.javaClass.simpleName)
    } catch (e: LinkageError) {
        Invocation.Threw(e.javaClass.simpleName)
    }

    companion object {
        /** The framework's own synchronous timeout is a few seconds; stay just above it. */
        const val DEFAULT_CALL_TIMEOUT_MS = 6_000L
    }
}
