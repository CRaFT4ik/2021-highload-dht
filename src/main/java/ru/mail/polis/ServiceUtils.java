package ru.mail.polis;

import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public final class ServiceUtils {

    private ServiceUtils() {
        // Don't instantiate
    }

    public static void shutdownAndAwaitExecutor(ExecutorService executorService, Logger log) {
        try {
            executorService.shutdown();
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    throw new InterruptedException();
                }
            }
        } catch (InterruptedException e) {
            log.error("error: executor can't shutdown on its own", e);
            Thread.currentThread().interrupt();
        }
    }
}
