package io.unilitix.sdk.core

internal class SnapshotBuffer(private val maxSize: Int) {
    private val snapshots = ArrayDeque<Snapshot>()

    @Synchronized
    fun add(snapshot: Snapshot) {
        if (snapshots.size >= maxSize) snapshots.removeFirst()
        snapshots.addLast(snapshot)
    }

    @Synchronized
    fun drain(): List<Snapshot> {
        val list = snapshots.toList()
        snapshots.clear()
        return list
    }

    @Synchronized
    fun size(): Int = snapshots.size
}
