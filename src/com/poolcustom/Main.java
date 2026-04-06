package com.poolcustom;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class Main {
    // Демонстрирует обычную работу пула, перегрузку и корректное завершение.
    public static void main(String[] args) throws Exception {
        CustomThreadPool pool = new CustomThreadPool(
                2,
                4,
                5,
                TimeUnit.SECONDS,
                2,
                1
        );

        List<Future<String>> futures = new ArrayList<>();

        PoolLogger.log("Лог", "Отправляем задачи Callable");
        for (int i = 1; i <= 4; i++) {
            futures.add(pool.submit(new DemoCallableTask("вычисление-" + i, 800)));
        }

        PoolLogger.log("Лог", "Отправляем Runnable-задачи, чтобы показать перегрузку.");
        for (int i = 1; i <= 10; i++) {
            try {
                pool.execute(new DemoRunnableTask("задача-" + i, 1500));
            } catch (RejectedExecutionException e) {
                PoolLogger.log("Лог", "В main зафиксировано отклонение: " + e.getMessage());
            }
        }

       

        pool.shutdown();
        boolean terminated = pool.awaitTermination(15, TimeUnit.SECONDS);
        PoolLogger.log("Лог", "Статус завершения: " + terminated);
    }

    private static final class DemoRunnableTask implements Runnable {
        private final String name;
        private final long durationMs;

        // Запоминает имя задачи и длительность её имитации.
        private DemoRunnableTask(String name, long durationMs) {
            this.name = name;
            this.durationMs = durationMs;
        }

        @Override
        public void run() {
            // Имитирует полезную работу через sleep и пишет начало/конец в лог.
            PoolLogger.log("Задача", name + " началась.");
            try {
                Thread.sleep(durationMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                PoolLogger.log("Задача", name + " была прервана.");
                return;
            }
            PoolLogger.log("Задача", name + " завершилась.");
        }

        @Override
        public String toString() {
            // Возвращает короткое имя задачи для логов.
            return name;
        }
    }

    private static final class DemoCallableTask implements java.util.concurrent.Callable<String> {
        private final String name;
        private final long durationMs;

        // Запоминает имя callable-задачи и длительность её выполнения.
        private DemoCallableTask(String name, long durationMs) {
            this.name = name;
            this.durationMs = durationMs;
        }

        @Override
        public String call() throws Exception {
            // Имитирует вычисление и возвращает текстовый результат.
            PoolLogger.log("Задача", name + " началась.");
            Thread.sleep(durationMs);
            PoolLogger.log("Задача", name + " завершилась.");
            return name + " завершено";
        }

        @Override
        public String toString() {
            // Возвращает короткое имя задачи для логов.
            return name;
        }
    }
}
