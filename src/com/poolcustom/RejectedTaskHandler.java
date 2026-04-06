package com.poolcustom;

import java.util.concurrent.RejectedExecutionException;

public class RejectedTaskHandler {
    // Логирует отклонение задачи и выбрасывает исключение вызывающему коду.
    public void reject(String taskDescription, String reason) {
        PoolLogger.log("Отклонение", "Задача " + taskDescription + " была отклонена: " + reason + ".");
        throw new RejectedExecutionException("Задача отклонена: " + taskDescription + ". Причина: " + reason);
    }
}
