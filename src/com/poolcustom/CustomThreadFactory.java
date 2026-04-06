package com.poolcustom;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class CustomThreadFactory implements ThreadFactory {
    private final AtomicInteger threadNumber = new AtomicInteger(1);
    private final String namePrefix;

    // Запоминает префикс, из которого потом формируются понятные имена потоков.
    public CustomThreadFactory(String poolName) {
        this.namePrefix = poolName + "-worker-";
    }

    // Создаёт новый поток, назначает ему имя и пишет это событие в лог.
    @Override
    public Thread newThread(Runnable runnable) {
        String threadName = namePrefix + threadNumber.getAndIncrement();
        PoolLogger.log("ФабрикаПотоков", "Создан новый поток: " + threadName);
        Thread thread = new Thread(runnable, threadName);
        thread.setDaemon(false);
        return thread;
    }
}
