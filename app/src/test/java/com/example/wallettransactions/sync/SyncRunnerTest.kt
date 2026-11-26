package com.example.wallettransactions.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SyncRunner]: один проход на процесс (spec sync-run, «Один проход на
 * приложение», «Ожидание с ограничением», «Последний успешный синк»). Тело
 * прохода подменено — сеть и БД тут не нужны, проверяется только кто и
 * сколько раз его запускает.
 */
@RunWith(RobolectricTestRunner::class)
class SyncRunnerTest {

    private lateinit var ctx: Context
    private lateinit var realPass: SyncRunner.Pass
    private val passes = AtomicInteger()
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)

    @Volatile
    private var passStatus = SyncRunner.Status.OK

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        realPass = SyncRunner.pass
        SyncRunner.pass = SyncRunner.Pass {
            passes.incrementAndGet()
            started.countDown()
            try {
                release.await(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            SyncRunner.Outcome(passStatus, SyncLog(), null)
        }
    }

    @After
    fun tearDown() {
        release.countDown()
        // Дождаться прохода этого теста, чтобы следующий не присоединился к нему.
        SyncRunner.runOnce(ctx)
        SyncRunner.pass = realPass
    }

    /** Кнопка во время фонового синка: второй проход не начинается, исход общий. */
    @Test
    fun concurrentCallers_shareOnePass() {
        val pool = Executors.newFixedThreadPool(2)
        val first = pool.submit(Callable { SyncRunner.runOnce(ctx) })
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val second = pool.submit(Callable { SyncRunner.runOnce(ctx) })

        release.countDown()
        val a = first.get(2, TimeUnit.SECONDS)
        val b = second.get(2, TimeUnit.SECONDS)

        assertSame(a, b)
        assertEquals(1, passes.get())
        pool.shutdown()
    }

    /** Ожидание истекло: «ещё идёт», проход не прерван, следующий вызов к нему присоединяется. */
    @Test
    fun sharedTimeout_returnsStillRunning_andPassContinues() {
        val waited = SyncRunner.runOnceShared(ctx, 100)
        assertEquals(SyncRunner.Status.STILL_RUNNING, waited.status)

        val pool = Executors.newSingleThreadExecutor()
        val next = pool.submit(Callable { SyncRunner.runOnce(ctx) })
        release.countDown()

        assertEquals(SyncRunner.Status.OK, next.get(2, TimeUnit.SECONDS).status)
        assertEquals(1, passes.get())
        pool.shutdown()
    }

    /** После завершённого прохода следующий вызов запускает новый. */
    @Test
    fun afterPassDone_nextCallStartsNewPass() {
        release.countDown()
        SyncRunner.runOnce(ctx)
        SyncRunner.runOnce(ctx)
        assertEquals(2, passes.get())
    }

    @Test
    fun lastSuccess_recordedOnOk() {
        AppSettings.setLastSyncSuccessAt(ctx, 0L)
        release.countDown()
        val before = System.currentTimeMillis()
        SyncRunner.runOnce(ctx)
        assertTrue(AppSettings.getLastSyncSuccessAt(ctx) >= before)
    }

    @Test
    fun lastSuccess_untouchedOnNetworkError() {
        AppSettings.setLastSyncSuccessAt(ctx, 42L)
        passStatus = SyncRunner.Status.NETWORK_ERROR
        release.countDown()
        SyncRunner.runOnce(ctx)
        assertEquals(42L, AppSettings.getLastSyncSuccessAt(ctx))
    }

    // ── Чистка отправленных удалений (openspec purge-synced-tombstones) ──────

    private open class RecordingSteps : SyncRunner.Steps {
        val order = ArrayList<String>()
        var pullFailure: IOException? = null
        var purgeFailure: RuntimeException? = null

        override fun push(log: SyncLog) {
            order.add("push")
        }

        override fun pull(log: SyncLog) {
            order.add("pull")
            pullFailure?.let { throw it }
        }

        override fun purge() {
            order.add("purge")
            purgeFailure?.let { throw it }
        }
    }

    @Test
    fun steps_purgeRunsAfterSuccessfulPull() {
        release.countDown()
        val steps = RecordingSteps()
        val outcome = SyncRunner.runSteps(steps, SyncLog())
        assertEquals(listOf("push", "pull", "purge"), steps.order)
        assertEquals(SyncRunner.Status.OK, outcome.status)
    }

    @Test
    fun steps_pullFailed_noPurge() {
        release.countDown()
        val steps = RecordingSteps()
        steps.pullFailure = IOException("нет сети")
        val outcome = SyncRunner.runSteps(steps, SyncLog())
        assertEquals(listOf("push", "pull"), steps.order)
        assertEquals(SyncRunner.Status.NETWORK_ERROR, outcome.status)
    }

    @Test
    fun steps_purgeFailure_doesNotChangeOutcome() {
        release.countDown()
        val steps = RecordingSteps()
        steps.purgeFailure = IllegalStateException("база занята")
        assertEquals(SyncRunner.Status.OK, SyncRunner.runSteps(steps, SyncLog()).status)
    }

    // ── Подключение к кошельку (openspec new-user-default-set, design D5) ────

    /** Шаги подключения: загрузка подключения, шаг, отправка, отметка конца. */
    private class ConnectingSteps : RecordingSteps() {
        var step = LedgerConnection.Step.PUSH

        override fun connecting(): Boolean = true

        override fun connectionPull(log: SyncLog) {
            order.add("connectionPull")
            pullFailure?.let { throw it }
        }

        override fun connect(): LedgerConnection.Step {
            order.add("connect")
            return step
        }

        override fun connectionDone() {
            order.add("done")
        }
    }

    @Test
    fun connecting_pullBeforePush() {
        release.countDown()
        val steps = ConnectingSteps()
        val outcome = SyncRunner.runSteps(steps, SyncLog())
        assertEquals(listOf("connectionPull", "connect", "push", "done", "purge"), steps.order)
        assertEquals(SyncRunner.Status.OK, outcome.status)
    }

    @Test
    fun connecting_askedQuestion_noPush_awaitingDecision() {
        release.countDown()
        val steps = ConnectingSteps()
        steps.step = LedgerConnection.Step.ASK
        val outcome = SyncRunner.runSteps(steps, SyncLog())
        assertEquals(listOf("connectionPull", "connect"), steps.order)
        assertEquals(SyncRunner.Status.AWAITING_DECISION, outcome.status)
        assertEquals(SyncRunner.awaitingDecisionMessage(), outcome.errorMessage)
    }

    @Test
    fun connecting_pullFailed_nothingDroppedNorSent_connectionStays() {
        release.countDown()
        val steps = ConnectingSteps()
        steps.pullFailure = IOException("нет сети")
        val outcome = SyncRunner.runSteps(steps, SyncLog())
        assertEquals(listOf("connectionPull"), steps.order)
        assertEquals(SyncRunner.Status.NETWORK_ERROR, outcome.status)
    }
}
