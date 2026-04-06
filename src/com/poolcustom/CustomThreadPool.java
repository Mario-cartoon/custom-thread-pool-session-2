package com.poolcustom;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class CustomThreadPool implements CustomExecutor {
    // Базовые параметры конфигурации пула.
    private final int corePoolSize;
    private final int maxPoolSize;
    private final long keepAliveTime;
    private final TimeUnit timeUnit;
    private final int queueSize;
    private final int minSpareThreads;

    // Для каждого worker храним его очередь, сам worker и поток.
    private final List<WorkerSlot> workerSlots = new ArrayList<>();
    private final CustomThreadFactory threadFactory;
    private final RejectedTaskHandler rejectedHandler = new RejectedTaskHandler();

    // acceptingTasks отвечает за мягкое завершение, shutdownNow — за немедленную остановку.
    private final AtomicBoolean acceptingTasks = new AtomicBoolean(true);
    private final AtomicBoolean shutdownNow = new AtomicBoolean(false);
    private final AtomicInteger roundRobin = new AtomicInteger(0);
    private final AtomicInteger taskSequence = new AtomicInteger(1);
    private final AtomicInteger queueSequence = new AtomicInteger(1);
    private final Object lock = new Object();

    public CustomThreadPool(int corePoolSize, int maxPoolSize,
                            long keepAliveTime, TimeUnit timeUnit,
                            int queueSize, int minSpareThreads) {
        // Проверяем параметры и поднимаем стартовые core-воркеры.
        validateArguments(corePoolSize, maxPoolSize, keepAliveTime, timeUnit, queueSize, minSpareThreads);

        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.timeUnit = timeUnit;
        this.queueSize = queueSize;
        this.minSpareThreads = minSpareThreads;
        this.threadFactory = new CustomThreadFactory("Pool-");

        // Core-воркеры поднимаются сразу при создании пула.
        for (int i = 0; i < corePoolSize; i++) {
            addWorkerUnderLock();
        }
    }

    @Override
    public void execute(Runnable command) {
        // Превращаем Runnable в внутреннюю tracked-задачу и отправляем в пул.
        if (command == null) {
            throw new NullPointerException("command");
        }
        enqueueTask(TrackedTask.forRunnable(nextTaskId(), command));
    }

    @Override
    public <T> Future<T> submit(Callable<T> callable) {
        // Создаём задачу с результатом и возвращаем Future вызывающему коду.
        if (callable == null) {
            throw new NullPointerException("callable");
        }
        TrackedTask<T> task = TrackedTask.forCallable(nextTaskId(), callable);
        enqueueTask(task);
        return task;
    }

    @Override
    public void shutdown() {
        // Запрещаем приём новых задач, но уже поставленные задачи не трогаем.
        if (!acceptingTasks.compareAndSet(true, false)) {
            return;
        }
        PoolLogger.log("Пул", "Запрошено корректное завершение. Новые задачи больше не принимаются.");
    }

    @Override
    public void shutdownNow() {
        // Немедленно останавливаем пул: очищаем очереди и прерываем потоки.
        boolean changed = acceptingTasks.getAndSet(false);
        shutdownNow.set(true);

        List<WorkerSlot> slotsSnapshot;
        int droppedTasks = 0;
        synchronized (lock) {
            // Снимаем snapshot, чтобы безопасно прервать потоки уже вне lock.
            slotsSnapshot = new ArrayList<>(workerSlots);
            for (WorkerSlot slot : workerSlots) {
                droppedTasks += drainQueue(slot.queue);
            }
        }

        for (WorkerSlot slot : slotsSnapshot) {
            slot.worker.stopNow();
            slot.thread.interrupt();
        }

        if (changed || droppedTasks > 0) {
            PoolLogger.log("Пул", "Запрошено немедленное завершение. Снято задач из очередей: " + droppedTasks);
        }
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        // Ждём завершения уже созданных потоков, но не дольше указанного таймаута.
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        List<Thread> threadsSnapshot;
        synchronized (lock) {
            threadsSnapshot = collectThreadsUnderLock();
        }

        for (Thread thread : threadsSnapshot) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
            int nanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis));
            thread.join(millis, nanos);
        }

        synchronized (lock) {
            return workerSlots.isEmpty();
        }
    }

    boolean isShutdownRequested() {
        // Возвращает true, если пул уже перестал принимать новые задачи.
        return !acceptingTasks.get();
    }

    boolean isShutdownNow() {
        // Возвращает true, если включён режим немедленной остановки.
        return shutdownNow.get();
    }

    boolean shouldTerminateAfterIdle(Worker worker) {
        // Решает, должен ли конкретный worker завершиться после периода простоя.
        synchronized (lock) {
            WorkerSlot slot = findSlot(worker);
            if (slot == null) {
                return true;
            }

            if (shutdownNow.get()) {
                removeWorkerSlotUnderLock(slot);
                return true;
            }

            if (!acceptingTasks.get()) {
                // При graceful shutdown воркер уходит только после опустошения своей очереди.
                if (slot.queue.isEmpty()) {
                    removeWorkerSlotUnderLock(slot);
                    return true;
                }
                return false;
            }

            // В обычном режиме удаляем только временных воркеров сверх corePoolSize.
            if (workerSlots.size() > corePoolSize) {
                removeWorkerSlotUnderLock(slot);
                return true;
            }

            return false;
        }
    }

    void onWorkerTerminated(Worker worker) {
        // Удаляет завершившийся worker из внутренних структур пула.
        synchronized (lock) {
            WorkerSlot slot = findSlot(worker);
            if (slot != null) {
                removeWorkerSlotUnderLock(slot);
            }
        }
    }

    private int nextTaskId() {
        // Отдельный счётчик помогает удобно различать задачи в логах.
        return taskSequence.getAndIncrement();
    }

    private void enqueueTask(TrackedTask<?> task) {
        // Основная точка входа для постановки задачи в одну из очередей пула.
        if (!acceptingTasks.get()) {
            rejectedHandler.reject(task.getDescription(), "пул завершает работу");
        }

        // Поддерживаем минимальный запас свободных очередей для новых задач.
        ensureMinSpareThreads();

        WorkerSlot targetSlot;
        synchronized (lock) {
            if (workerSlots.isEmpty()) {
                addWorkerUnderLock();
            }
            // Задачи распределяются по очередям циклически, чтобы не перегружать одну очередь.
            targetSlot = workerSlots.get(Math.floorMod(roundRobin.getAndIncrement(), workerSlots.size()));
        }

        if (offerToSlot(targetSlot, task)) {
            return;
        }

        synchronized (lock) {
            // Если  очередь занята, пробуем расширить пул до maxPoolSize.
            if (workerSlots.size() < maxPoolSize) {
                WorkerSlot newSlot = addWorkerUnderLock();
                if (offerToSlot(newSlot, task)) {
                    return;
                }
            }
        }

        rejectedHandler.reject(task.getDescription(), "все очереди переполнены");
    }

    private boolean offerToSlot(WorkerSlot slot, TrackedTask<?> task) {
        // Пытается положить задачу в очередь конкретного worker.
        boolean offered = slot.queue.offer(task);
        if (offered) {
            PoolLogger.log("Пул", "Задача принята в очередь #" + slot.queueId + ": " + task.getDescription());
        }
        return offered;
    }

    private void ensureMinSpareThreads() {
        // Держит минимальный запас незагруженных очередей для новых задач.
        synchronized (lock) {
            // При необходимости заранее поднимаем дополнительные очереди/воркеры,
            // чтобы новые задачи не ждали создания потока в последний момент.
            while (countIdleQueuesUnderLock() < minSpareThreads && workerSlots.size() < maxPoolSize) {
                addWorkerUnderLock();
            }
        }
    }

    private long countIdleQueuesUnderLock() {
        // Считает, сколько очередей сейчас пустые и готовы быстро принять задачу.
        long idleQueues = 0;
        for (WorkerSlot slot : workerSlots) {
            if (slot.queue.isEmpty()) {
                idleQueues++;
            }
        }
        return idleQueues;
    }

    private WorkerSlot addWorkerUnderLock() {
        // Новый worker получает собственную очередь фиксированного размера.
        int queueId = queueSequence.getAndIncrement();
        BlockingQueue<TrackedTask<?>> queue = new LinkedBlockingQueue<>(queueSize);
        Worker worker = new Worker(queueId, queue, this, keepAliveTime, timeUnit);
        Thread thread = threadFactory.newThread(worker);
        WorkerSlot slot = new WorkerSlot(queueId, queue, worker, thread);
        workerSlots.add(slot);
        thread.start();
        return slot;
    }

    private WorkerSlot findSlot(Worker worker) {
        // Ищет служебную запись, которая соответствует переданному worker.
        for (WorkerSlot slot : workerSlots) {
            if (slot.worker == worker) {
                return slot;
            }
        }
        return null;
    }

    private void removeWorkerSlotUnderLock(WorkerSlot slot) {
        // Удаляет worker из списка активных после его завершения.
        workerSlots.remove(slot);
    }

    private List<Thread> collectThreadsUnderLock() {
        // Собирает snapshot потоков, чтобы ждать их завершения уже вне lock.
        List<Thread> threads = new ArrayList<>();
        for (WorkerSlot slot : workerSlots) {
            threads.add(slot.thread);
        }
        return threads;
    }

    private int drainQueue(BlockingQueue<TrackedTask<?>> queue) {
        // При shutdownNow убираем из очереди всё, что ещё не успело начать выполняться.
        List<TrackedTask<?>> dropped = new ArrayList<>();
        queue.drainTo(dropped);
        for (TrackedTask<?> task : dropped) {
            task.cancel(false);
            PoolLogger.log("Отклонение", "Задача " + task.getDescription() + " была снята из очереди из-за shutdownNow.");
        }
        return dropped.size();
    }

    private void validateArguments(int corePoolSize, int maxPoolSize,
                                   long keepAliveTime, TimeUnit timeUnit,
                                   int queueSize, int minSpareThreads) {
        // Проверяет корректность входных параметров конструктора.
        if (corePoolSize <= 0) {
            throw new IllegalArgumentException("corePoolSize должен быть больше 0");
        }
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException("maxPoolSize должен быть не меньше corePoolSize");
        }
        if (keepAliveTime < 0) {
            throw new IllegalArgumentException("keepAliveTime должен быть не меньше 0");
        }
        if (timeUnit == null) {
            throw new NullPointerException("timeUnit");
        }
        if (queueSize <= 0) {
            throw new IllegalArgumentException("queueSize должен быть больше 0");
        }
        if (minSpareThreads < 0) {
            throw new IllegalArgumentException("minSpareThreads должен быть не меньше 0");
        }
    }

    private static final class WorkerSlot {
        // Объединяем в одну структуру всё, что относится к конкретному worker.
        private final int queueId;
        private final BlockingQueue<TrackedTask<?>> queue;
        private final Worker worker;
        private final Thread thread;

        private WorkerSlot(int queueId, BlockingQueue<TrackedTask<?>> queue, Worker worker, Thread thread) {
            // Связывает queueId, очередь и поток в одну служебную запись.
            this.queueId = queueId;
            this.queue = queue;
            this.worker = worker;
            this.thread = thread;
        }
    }

    static final class TrackedTask<T> extends FutureTask<T> {
        // Описание используется только для логов и удобной диагностики.
        private final String description;

        private TrackedTask(Callable<T> callable, String description) {
            // Создаёт tracked-обёртку над Callable.
            super(callable);
            this.description = description;
        }

        private TrackedTask(Runnable runnable, T result, String description) {
            // Создаёт tracked-обёртку над Runnable.
            super(runnable, result);
            this.description = description;
        }

        static TrackedTask<Void> forRunnable(int taskId, Runnable runnable) {
            // Фабричный метод для Runnable-задач без возвращаемого значения.
            return new TrackedTask<>(runnable, null, describe(taskId, runnable));
        }

        static <T> TrackedTask<T> forCallable(int taskId, Callable<T> callable) {
            // Фабричный метод для Callable-задач с результатом.
            return new TrackedTask<>(callable, describe(taskId, callable));
        }

        String getDescription() {
            // Возвращает текст, который используется в логах.
            return description;
        }

        private static String describe(int taskId, Object task) {
            // Если у задачи нет своего понятного toString, используем имя класса.
            String text = String.valueOf(task);
            if (text == null || text.startsWith(task.getClass().getName() + "@")) {
                text = task.getClass().getSimpleName();
            }
            return "задача-" + taskId + " [" + text + "]";
        }
    }
}
