package com.example.dq.repository

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** 元数据缓存统一写队列:全局串行(同一时刻只有一个任务执行)、FIFO、异常按原类型回抛 */
class MetaWriteQueueTest {

    @Test
    fun `多线程提交 任意时刻只有一个任务在执行`() {
        val queue = MetaWriteQueue()
        try {
            val running = AtomicInteger()
            val maxConcurrent = AtomicInteger()
            val done = AtomicInteger()
            val threads = (1..8).map {
                Thread {
                    repeat(50) {
                        queue.submit {
                            val now = running.incrementAndGet()
                            maxConcurrent.updateAndGet { m -> maxOf(m, now) }
                            Thread.sleep(0, 200)
                            done.incrementAndGet()
                            running.decrementAndGet()
                        }
                    }
                }
            }
            threads.forEach(Thread::start)
            threads.forEach(Thread::join)
            assertThat(done.get()).isEqualTo(400)
            assertThat(maxConcurrent.get()).isEqualTo(1)
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun `提交顺序即执行顺序 先进先出`() {
        val queue = MetaWriteQueue()
        try {
            val order = ConcurrentLinkedQueue<Int>()
            repeat(20) { i -> queue.submit { order.add(i) } }
            assertThat(order.toList()).containsExactlyElementsOf((0 until 20).toList())
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun `任务异常按原类型回抛 队列不中断可继续用`() {
        val queue = MetaWriteQueue()
        try {
            assertThatThrownBy { queue.submit<Unit> { throw IllegalArgumentException("boom") } }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("boom")
            assertThat(queue.submit { 42 }).isEqualTo(42)
        } finally {
            queue.shutdown()
        }
    }
}
