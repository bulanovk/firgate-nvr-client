package com.frigate.android.feature.live

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MseByteQueueTest {
    @Test
    fun readsBinaryFramesInOrderAcrossPartialReads() {
        val queue = MseByteQueue(capacityBytes = 8)
        assertTrue(queue.offer(byteArrayOf(1, 2)))
        assertTrue(queue.offer(byteArrayOf(3, 4, 5)))

        val firstRead = ByteArray(3)
        assertEquals(3, queue.read(firstRead, 0, firstRead.size))
        assertArrayEquals(byteArrayOf(1, 2, 3), firstRead)

        val secondRead = ByteArray(2)
        assertEquals(2, queue.read(secondRead, 0, secondRead.size))
        assertArrayEquals(byteArrayOf(4, 5), secondRead)
    }

    @Test
    fun closeUnblocksWaitingReader() {
        val queue = MseByteQueue(capacityBytes = 8)
        val executor = Executors.newSingleThreadExecutor()
        val waiting = CountDownLatch(1)
        try {
            val result = executor.submit<Int> {
                waiting.countDown()
                queue.read(ByteArray(1), 0, 1)
            }
            assertTrue(waiting.await(1, TimeUnit.SECONDS))
            queue.close()
            assertEquals(-1, result.get(1, TimeUnit.SECONDS))
        } finally {
            queue.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun propagatesCloseCause() {
        val queue = MseByteQueue(capacityBytes = 8)
        val cause = IOException("socket failed")
        queue.close(cause)

        assertThrows(IOException::class.java) {
            queue.read(ByteArray(1), 0, 1)
        }
    }

    @Test
    fun acceptsExactlyCapacityBytes() {
        val queue = MseByteQueue(capacityBytes = 4)
        assertTrue(queue.offer(byteArrayOf(1, 2, 3, 4)))

        val target = ByteArray(4)
        assertEquals(4, queue.read(target, 0, target.size))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), target)
    }

    @Test
    fun rejectsOverflowWithoutDroppingQueuedBytes() {
        val queue = MseByteQueue(capacityBytes = 4)
        assertTrue(queue.offer(byteArrayOf(1, 2, 3, 4)))
        assertFalse(queue.offer(byteArrayOf(5)))

        val target = ByteArray(4)
        assertEquals(4, queue.read(target, 0, target.size))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), target)
    }
}
