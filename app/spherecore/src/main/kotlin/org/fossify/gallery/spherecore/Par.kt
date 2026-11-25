package org.fossify.gallery.spherecore

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Jednoduchý paralelizmus: pevný fond vlákien (jadrá − 1, aspoň 1). Nevolať vnorene. */
internal object Par {
    val threads: Int get() = maxOf(1, Runtime.getRuntime().availableProcessors() - 1)

    private val pool: ExecutorService by lazy {
        Executors.newFixedThreadPool(threads) { r -> Thread(r, "sfera-par").apply { isDaemon = true } }
    }

    /** Zavolá body(i) pre i v 0 until n; prvá výnimka sa prehodí volajúcemu. */
    fun forRange(n: Int, body: (Int) -> Unit) {
        if (n <= 0) return
        val t = minOf(threads, n)
        if (t <= 1) {
            for (i in 0 until n) body(i)
            return
        }
        val next = AtomicInteger(0)
        val tasks = (0 until t).map {
            Callable {
                while (true) {
                    val i = next.getAndIncrement()
                    if (i >= n) break
                    body(i)
                }
            }
        }
        val fs = pool.invokeAll(tasks)
        for (f in fs) {
            try {
                f.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }
        }
    }
}
