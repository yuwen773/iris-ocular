package com.csust.eyediagnosis.newsingestion;

import java.util.Set;

/**
 * Authenticated machine identity for an internal Iris News Engine worker.
 */
public record NewsWorkerIdentity(String workerId, Set<String> allowedSources) {

    public NewsWorkerIdentity {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        allowedSources = allowedSources == null ? Set.of() : Set.copyOf(allowedSources);
    }

    public boolean allowsSource(String source) {
        return source != null && allowedSources.contains(source);
    }
}
