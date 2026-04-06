package com.poolcustom;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class BenchmarkComparison {
    private static final int TASK_COUNT = 12;
    private static final int TASK_DURATION_MS = 300;

    // Запускает простое сравнение кастомного пула и ThreadPoolExecutor на одинаковых задачах.
    public static void main(String[] args) throws Exception {
        long customMs = runCustomPoolScenario();
        long jdkMs = runThreadPoolExecutorScenario();

        System.out.println("CustomThreadPool: " + customMs + " мс");
        System.out.println("ThreadPoolExecutor: " + jdkMs + " мс");
    }

    private static long runCustomPoolScenario() throws Exception {
        CustomThreadPool pool = new CustomThreadPool(2, 4, 1, TimeUnit.SECONDS, 8, 1);
        long started = System.nanoTime();
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < TASK_COUNT; i++) {
            futures.add(pool.submit(new TimedTask(TASK_DURATION_MS)));
        }

        waitAll(futures);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        pool.shutdownNow();
        return elapsedMs;
    }

    private static long runThreadPoolExecutorScenario() throws Exception {
        ExecutorService executor = new ThreadPoolExecutor(
                2,
                4,
                1,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8)
        );
        long started = System.nanoTime();
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < TASK_COUNT; i++) {
            futures.add(executor.submit(new TimedTask(TASK_DURATION_MS)));
        }

        waitAll(futures);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        executor.shutdownNow();
        return elapsedMs;
    }

    private static void waitAll(List<Future<Integer>> futures) throws InterruptedException, ExecutionException {
        for (Future<Integer> future : futures) {
            future.get();
        }
    }

    private static final class TimedTask implements Callable<Integer> {
        private final int durationMs;

        private TimedTask(int durationMs) {
            this.durationMs = durationMs;
        }

        @Override
        public Integer call() throws Exception {
            Thread.sleep(durationMs);
            return durationMs;
        }
    }
}
