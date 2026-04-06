package com.poolcustom;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class PoolLogger {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // Закрытый конструктор запрещает создание экземпляров утилитного класса.
    private PoolLogger() {
    }

    // Печатает одно лог-сообщение в едином формате с временем и компонентом.
    public static synchronized void log(String component, String message) {
        System.out.println("[" + LocalTime.now().format(FORMATTER) + "] "
                + "[" + component + "] "
                + message);
    }
}
