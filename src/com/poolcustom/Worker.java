package com.poolcustom;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Worker implements Runnable {
    // Воркер обслуживает одну закреплённую за ним очередь задач.
    private final int queueId;
    private final BlockingQueue<CustomThreadPool.TrackedTask<?>> queue;
    private final CustomThreadPool pool;
    private final long keepAliveTime;
    private final TimeUnit timeUnit;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public Worker(int queueId, BlockingQueue<CustomThreadPool.TrackedTask<?>> queue,
                  CustomThreadPool pool, long keepAliveTime, TimeUnit timeUnit) {
        // Сохраняет всё, что нужно worker для ожидания и выполнения задач.
        this.queueId = queueId;
        this.queue = queue;
        this.pool = pool;
        this.keepAliveTime = keepAliveTime;
        this.timeUnit = timeUnit;
    }

    public void stopNow() {
        // Выставляет флаг немедленной остановки для основного цикла worker.
        running.set(false);
    }

    @Override
    public void run() {
        String threadName = Thread.currentThread().getName();
        try {
            // Основной цикл работы потока - ждём задачу или сигнал на остановку.
            while (running.get()) {
                CustomThreadPool.TrackedTask<?> task = queue.poll(keepAliveTime, timeUnit);

                if (task == null) {
                    // Если задача не пришла за keepAliveTime, решение о завершении
                    // принимает пул: core-воркеры можно оставить, а временные убираем.
                    if (pool.shouldTerminateAfterIdle(this)) {
                        PoolLogger.log("Воркер", threadName + " долгое простаивание поэтому останавливаем. queueId=" + queueId);
                        return;
                    }
                    continue;
                }

                // Логируем старт выполнения чтобы было видно, какой поток взял задачу.
                PoolLogger.log("Воркер", threadName + " выполняет " + task.getDescription());
                try {
                    task.run();
                } catch (RuntimeException e) {
                    PoolLogger.log("Воркер", threadName + " завершился с ошибкой при выполнении "
                            + task.getDescription() + ": " + e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Восстанавливаем флаг прерывания и логируем причину остановки.
            if (pool.isShutdownNow()) {
                PoolLogger.log("Воркер", threadName + " был прерван из-за вызова shutdownNow.");
            } else {
                PoolLogger.log("Воркер", threadName + " был прерван.");
            }
        } finally { 
            // В любом случае сообщаем пулу, что worker закончил работу.
            pool.onWorkerTerminated(this);
            PoolLogger.log("Воркер", threadName + " завершил работу.");
        }
    }
}
