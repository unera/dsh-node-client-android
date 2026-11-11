package ru.uvw.dshnc.logic

import java.util.concurrent.ConcurrentHashMap

/**
 * Live processes by command number.
 *
 * `#INTERNAL.CANCEL` reaches the node with a number, not with a word, so the process has
 * to be found by that number. The same registry serves `exec` and both pipes: a cancel
 * does not care which method started the process.
 *
 * Android has no `setsid` and the app cannot start a process group of its own: a
 * `destroy()` stops the `sh` wrapper, while a pipeline's children may outlive it.
 */
class Running {
    private val processes = ConcurrentHashMap<Long, Process>()

    fun put (seq: Long, process: Process) {
        processes[seq] = process
    }

    /** Take the process out: only one cancel may win it. */
    fun take (seq: Long): Process? = processes.remove(seq)

    /** The number is free again: a late cancel must find nothing, not a dead process. */
    fun forget (seq: Long) {
        processes.remove(seq)
    }

    fun count (): Int = processes.size

    fun isEmpty (): Boolean = processes.isEmpty()
}
