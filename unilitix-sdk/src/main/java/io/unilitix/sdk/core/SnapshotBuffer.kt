package io.unilitix.sdk.core

internal class SnapshotBuffer(private val maxSize: Int) {
    private val snapshots = ArrayDeque<Snapshot>()

    @Synchronized
    fun add(snapshot: Snapshot) {
        if (snapshots.size >= maxSize) snapshots.removeFirst()
        snapshots.addLast(snapshot)
    }

    /**
     * Puts snapshots that could not be uploaded back in front of anything
     * captured since, keeping capture order; the oldest are dropped past the cap.
     */
    @Synchronized
    fun requeue(unsent: List<Snapshot>) {
        for (s in unsent.asReversed()) snapshots.addFirst(s)
        while (snapshots.size > maxSize) snapshots.removeFirst()
    }

    @Synchronized
    fun drain(): List<Snapshot> {
        val list = snapshots.toList()
        snapshots.clear()
        return list
    }

    @Synchronized
    fun size(): Int = snapshots.size

    companion object {
        /** Most snapshots held in memory between uploads (5 minutes at 1/s). */
        const val MAX_HELD = 300
    }
}
