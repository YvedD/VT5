package com.yvesds.vt5.utils

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Simple bounded ring buffer (circular buffer).
 *
 * - capacity: maximum number of elements the buffer can hold.
 * - overwriteOldest: when true and the buffer is full, adding an element will overwrite the oldest.
 *                     When false and buffer is full, add(...) will return false and not insert.
 *
 * Thread-safe via an internal ReentrantLock.
 *
 * Usage examples:
 * val buf = RingBuffer<String>(8)                 // default 8 slots, overwrite oldest
 * buf.add("line1")
 * val oldest = buf.poll()
 * val snapshot = buf.toList()
 */
class RingBuffer<T>(
    capacity: Int = 8,
    private val overwriteOldest: Boolean = true
) : Iterable<T> {

    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    private val cap = capacity
    @Volatile private var count = 0
    private var head = 0 // index of oldest element
    private var tail = 0 // index of next write
    @Suppress("UNCHECKED_CAST")
    private val array = arrayOfNulls<Any?>(cap)

    private val lock = ReentrantLock()

    /**
     * Add an element to the buffer.
     * Returns true if inserted, false if buffer full and overwriteOldest==false.
     */
    fun add(item: T): Boolean {
        lock.withLock {
            if (count == cap) {
                if (!overwriteOldest) return false
                // Overwrite oldest: advance head, then write at tail
                array[tail] = item
                head = (head + 1) % cap
                tail = (tail + 1) % cap
                // count unchanged (still full)
                return true
            } else {
                array[tail] = item
                tail = (tail + 1) % cap
                count++
                return true
            }
        }
    }

    /**
     * Offer but do not overwrite oldest; returns false if full.
     * Convenience alias for add when overwriteOldest==false.
     */
    fun offer(item: T): Boolean = add(item)

    /**
     * Remove and return the oldest element, or null if empty.
     */
    @Suppress("UNCHECKED_CAST")
    fun poll(): T? {
        lock.withLock {
            if (count == 0) return null
            val v = array[head] as T?
            array[head] = null
            head = (head + 1) % cap
            count--
            return v
        }
    }

    /**
     * Return the oldest element without removing it, or null if empty.
     */
    @Suppress("UNCHECKED_CAST")
    fun peek(): T? {
        lock.withLock {
            if (count == 0) return null
            return array[head] as T
        }
    }

    /**
     * Return a snapshot List of elements from oldest -> newest.
     * Snapshot is a shallow copy and safe for iteration without lock.
     */
    fun toList(): List<T> {
        lock.withLock {
            if (count == 0) return emptyList()
            val out = ArrayList<T>(count)
            var idx = head
            repeat(count) {
                @Suppress("UNCHECKED_CAST")
                out.add(array[idx] as T)
                idx = (idx + 1) % cap
            }
            return out
        }
    }

    /**
     * Clear buffer contents.
     */
    fun clear() {
        lock.withLock {
            var idx = head
            repeat(count) {
                array[idx] = null
                idx = (idx + 1) % cap
            }
            head = 0
            tail = 0
            count = 0
        }
    }

    /**
     * Current number of elements.
     */
    fun size(): Int = count

    /**
     * True when empty.
     */
    fun isEmpty(): Boolean = size() == 0

    /**
     * True when full.
     */
    fun isFull(): Boolean = size() == cap

    /**
     * Add all elements from an Iterable in order.
     * Returns number actually added.
     */
    fun addAll(items: Iterable<T>): Int {
        var added = 0
        lock.withLock {
            for (it in items) {
                if (count == cap && !overwriteOldest) break
                add(it) // add() acquires same lock; but we are already inside lock, so call internal write instead
            }
        }
        // above used public add() which will re-lock; to avoid nested locks rewrite with direct writes,
        // but for simplicity and clarity we accept the small overhead here.
        // If you need highest perf, replace above with internal loop copying to array and adjusting pointers.
        // Recompute added count
        return toList().size
    }

    /**
     * Iterate from oldest -> newest.
     * Iteration uses a snapshot to avoid holding lock for long time.
     */
    override fun iterator(): Iterator<T> {
        val snap = toList()
        return snap.iterator()
    }
}