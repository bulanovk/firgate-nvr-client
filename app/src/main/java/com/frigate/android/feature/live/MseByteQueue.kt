package com.frigate.android.feature.live

import java.io.IOException
import java.util.ArrayDeque

class MseByteQueue(private val capacityBytes: Int = 2 * 1024 * 1024) {
    private val frames = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var queuedBytes = 0
    private var closed = false
    private var closeCause: IOException? = null

    init {
        require(capacityBytes > 0) { "capacityBytes must be positive" }
    }

    @Synchronized
    fun offer(bytes: ByteArray): Boolean {
        if (closed || bytes.size > capacityBytes - queuedBytes) return false
        if (bytes.isNotEmpty()) {
            frames.addLast(bytes.copyOf())
            queuedBytes += bytes.size
            (this as java.lang.Object).notifyAll()
        }
        return true
    }

    @Synchronized
    @Throws(IOException::class)
    fun read(target: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= target.size - length) {
            "Invalid read range"
        }
        if (length == 0) return 0
        while (queuedBytes == 0 && !closed) {
            try {
                (this as java.lang.Object).wait()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for MSE data", interrupted)
            }
        }
        if (queuedBytes == 0) {
            closeCause?.let { throw it }
            return -1
        }

        var copied = 0
        while (copied < length && frames.isNotEmpty()) {
            val frame = frames.peekFirst() ?: break
            val count = minOf(length - copied, frame.size - headOffset)
            frame.copyInto(target, offset + copied, headOffset, headOffset + count)
            copied += count
            headOffset += count
            queuedBytes -= count
            if (headOffset == frame.size) {
                frames.removeFirst()
                headOffset = 0
            }
        }
        return copied
    }

    @Synchronized
    fun close(cause: IOException? = null) {
        if (closed) return
        closed = true
        closeCause = cause
        (this as java.lang.Object).notifyAll()
    }
}
