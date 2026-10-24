package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.AppSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link SyncRunner}: один проход на процесс (spec sync-run, «Один проход на
 * приложение», «Ожидание с ограничением», «Последний успешный синк»). Тело
 * прохода подменено — сеть и БД тут не нужны, проверяется только кто и
 * сколько раз его запускает.
 */
@RunWith(RobolectricTestRunner.class)
public class SyncRunnerTest {

    private Context ctx;
    private SyncRunner.Pass realPass;
    private final AtomicInteger passes = new AtomicInteger();
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private SyncRunner.Status passStatus = SyncRunner.Status.OK;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        realPass = SyncRunner.pass;
        SyncRunner.pass = c -> {
            passes.incrementAndGet();
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new SyncRunner.Outcome(passStatus, new SyncLog(), null);
        };
    }

    @After
    public void tearDown() {
        release.countDown();
        // Дождаться прохода этого теста, чтобы следующий не присоединился к нему.
        SyncRunner.runOnce(ctx);
        SyncRunner.pass = realPass;
    }

    /** Кнопка во время фонового синка: второй проход не начинается, исход общий. */
    @Test
    public void concurrentCallers_shareOnePass() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<SyncRunner.Outcome> first = pool.submit(() -> SyncRunner.runOnce(ctx));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        Future<SyncRunner.Outcome> second = pool.submit(() -> SyncRunner.runOnce(ctx));

        release.countDown();
        SyncRunner.Outcome a = first.get(2, TimeUnit.SECONDS);
        SyncRunner.Outcome b = second.get(2, TimeUnit.SECONDS);

        assertSame(a, b);
        assertEquals(1, passes.get());
        pool.shutdown();
    }

    /** Ожидание истекло: «ещё идёт», проход не прерван, следующий вызов к нему присоединяется. */
    @Test
    public void sharedTimeout_returnsStillRunning_andPassContinues() throws Exception {
        SyncRunner.Outcome waited = SyncRunner.runOnceShared(ctx, 100);
        assertEquals(SyncRunner.Status.STILL_RUNNING, waited.status);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<SyncRunner.Outcome> next = pool.submit(() -> SyncRunner.runOnce(ctx));
        release.countDown();

        assertEquals(SyncRunner.Status.OK, next.get(2, TimeUnit.SECONDS).status);
        assertEquals(1, passes.get());
        pool.shutdown();
    }

    /** После завершённого прохода следующий вызов запускает новый. */
    @Test
    public void afterPassDone_nextCallStartsNewPass() {
        release.countDown();
        SyncRunner.runOnce(ctx);
        SyncRunner.runOnce(ctx);
        assertEquals(2, passes.get());
    }

    @Test
    public void lastSuccess_recordedOnOk() {
        AppSettings.setLastSyncSuccessAt(ctx, 0L);
        release.countDown();
        long before = System.currentTimeMillis();
        SyncRunner.runOnce(ctx);
        assertTrue(AppSettings.getLastSyncSuccessAt(ctx) >= before);
    }

    @Test
    public void lastSuccess_untouchedOnNetworkError() {
        AppSettings.setLastSyncSuccessAt(ctx, 42L);
        passStatus = SyncRunner.Status.NETWORK_ERROR;
        release.countDown();
        SyncRunner.runOnce(ctx);
        assertEquals(42L, AppSettings.getLastSyncSuccessAt(ctx));
    }
}
