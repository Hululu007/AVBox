package com.github.avbox.osc.data

import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertTrue
import org.junit.Test

class DataLayerSuspendContractTest {

    @Test
    fun historyRepository_exposesNoBlockingDbMethod() {
        assertSuspendContract(HistoryRepository::class.java, setOf("currentCid"))
    }

    @Test
    fun collectRepository_exposesNoBlockingDbMethod() {
        assertSuspendContract(CollectRepository::class.java, setOf("currentCid"))
    }

    @Test
    fun followRepository_exposesNoBlockingDbMethod() {
        assertSuspendContract(FollowRepository::class.java, setOf("currentCid"))
    }

    @Test
    fun cacheRepository_exposesNoBlockingDbMethod() {
        assertSuspendContract(CacheRepository::class.java, emptySet())
    }

    @Test
    fun vodRecordDao_exposesNoBlockingDbMethod() {
        assertSuspendContract(VodRecordDao::class.java, emptySet())
    }

    @Test
    fun vodCollectDao_exposesNoBlockingDbMethod() {
        assertSuspendContract(VodCollectDao::class.java, emptySet())
    }

    @Test
    fun vodFollowDao_exposesNoBlockingDbMethod() {
        assertSuspendContract(VodFollowDao::class.java, emptySet())
    }

    @Test
    fun cacheDao_exposesNoBlockingDbMethod() {
        assertSuspendContract(CacheDao::class.java, emptySet())
    }

    private fun assertSuspendContract(type: Class<*>, syncAllow: Set<String>) {
        val offenders = type.declaredMethods.filter { method ->
            !method.isSuspend() &&
                !Flow::class.java.isAssignableFrom(method.returnType) &&
                method.name !in syncAllow
        }.map { it.name + " -> " + it.returnType.simpleName }
        assertTrue(
            type.simpleName + " exposes non-suspend DB methods: " + offenders,
            offenders.isEmpty(),
        )
    }

    private fun java.lang.reflect.Method.isSuspend(): Boolean =
        parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation"
}
