package com.poolcustom;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

public interface CustomExecutor extends Executor {
    // Отправляет Runnable-задачу на выполнение без результата.
    @Override
    void execute(Runnable command);

    // Отправляет Callable-задачу и возвращает Future для получения результата.
    <T> Future<T> submit(Callable<T> callable);

    // Запускает мягкое завершение: новые задачи не принимаются, старые дорабатываются.
    void shutdown();

    // Запускает немедленную остановку: очереди очищаются, потоки прерываются.
    void shutdownNow();
}
