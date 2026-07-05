package com.btrace.viewer.data

import com.btrace.viewer.model.BinderEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedEventBufferTest {

    private fun mockEvent(id: Long): BinderEvent =
        BinderEvent.createMock(
            interfaceName = "test.IFoo",
            methodName = "m",
            callerPackage = "test",
            uid = 100
        ).copy(id = id)

    @Test
    fun `add up to capacity keeps all`() {
        val buf = BoundedEventBuffer(capacity = 3)
        buf.add(mockEvent(1))
        buf.add(mockEvent(2))
        buf.add(mockEvent(3))
        assertEquals(listOf(1L, 2L, 3L), buf.snapshot().map { it.id })
        assertEquals(3, buf.size())
    }

    @Test
    fun `add beyond capacity evicts oldest fifo`() {
        val buf = BoundedEventBuffer(capacity = 3)
        listOf(1L, 2L, 3L, 4L, 5L).forEach { buf.add(mockEvent(it)) }
        assertEquals(listOf(3L, 4L, 5L), buf.snapshot().map { it.id })
        assertEquals(3, buf.size())
    }

    @Test
    fun `setCapacity smaller trims oldest`() {
        val buf = BoundedEventBuffer(capacity = 5)
        listOf(1L, 2L, 3L, 4L, 5L).forEach { buf.add(mockEvent(it)) }

        buf.setCapacity(3)

        assertEquals(listOf(3L, 4L, 5L), buf.snapshot().map { it.id })
        assertEquals(3, buf.size())
    }

    @Test
    fun `setCapacity larger keeps existing and allows growth`() {
        val buf = BoundedEventBuffer(capacity = 3)
        listOf(1L, 2L, 3L).forEach { buf.add(mockEvent(it)) }

        buf.setCapacity(5)
        listOf(4L, 5L, 6L).forEach { buf.add(mockEvent(it)) }

        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), buf.snapshot().map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setCapacity rejects non-positive`() {
        BoundedEventBuffer(capacity = 5).setCapacity(0)
    }

    @Test
    fun `findById returns event when present and null when evicted`() {
        val buf = BoundedEventBuffer(capacity = 2)
        buf.add(mockEvent(10))
        buf.add(mockEvent(20))
        buf.add(mockEvent(30))   // evicts id=10
        assertEquals(20L, buf.findById(20)?.id)
        assertEquals(30L, buf.findById(30)?.id)
        assertEquals(null, buf.findById(10))
    }

    @Test
    fun `unlimited capacity never fifo-evicts`() {
        val buf = BoundedEventBuffer(capacity = Int.MAX_VALUE)
        (1L..1000L).forEach { buf.add(mockEvent(it)) }
        assertEquals(1000, buf.size())
        assertEquals(1L, buf.snapshot().first().id)   // 最老的没被淘汰
    }

    @Test
    fun `trimToSize drops oldest down to target and fires onEvicted`() {
        val buf = BoundedEventBuffer(capacity = Int.MAX_VALUE)
        val evicted = mutableListOf<Long>()
        buf.onEvicted = { evicted.add(it.id) }
        (1L..10L).forEach { buf.add(mockEvent(it)) }

        buf.trimToSize(4)

        assertEquals(listOf(7L, 8L, 9L, 10L), buf.snapshot().map { it.id })
        assertEquals(4, buf.size())
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), evicted)   // 淘汰的是最老那批
        assertEquals(Int.MAX_VALUE, buf.capacity())            // capacity 不变
    }

    @Test
    fun `trimToSize no-op when already within target`() {
        val buf = BoundedEventBuffer(capacity = Int.MAX_VALUE)
        (1L..3L).forEach { buf.add(mockEvent(it)) }
        buf.trimToSize(10)
        assertEquals(3, buf.size())
    }

    @Test
    fun `clear empties everything`() {
        val buf = BoundedEventBuffer(capacity = 3)
        buf.add(mockEvent(1))
        buf.add(mockEvent(2))
        buf.clear()
        assertEquals(0, buf.size())
        assertEquals(emptyList<BinderEvent>(), buf.snapshot())
        assertEquals(null, buf.findById(1))
    }
}
