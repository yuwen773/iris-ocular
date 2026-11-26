package com.csust.eyediagnosis.newsingestion;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Getter
@Component
@ConfigurationProperties(prefix = "eye-diagnosis.news-schedule")
public class NewsCollectionScheduleProperties {
    private static final int MAX_BACKFILL_DAYS = 36_600;

    @Setter
    private boolean enabled;
    private Duration pollInterval = Duration.ofMinutes(1);
    private List<SourcePolicy> sources = List.of();

    public void setPollInterval(Duration pollInterval) {
        if (pollInterval == null || pollInterval.compareTo(Duration.ofSeconds(5)) < 0
                || pollInterval.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("News scheduling poll interval must be between 5 seconds and 1 hour");
        }
        this.pollInterval = pollInterval;
    }

    public void setSources(List<SourcePolicy> sources) {
        Set<String> seen = new HashSet<>();
        for (SourcePolicy policy : sources) {
            if (!seen.add(policy.source())) {
                throw new IllegalArgumentException("Duplicate news schedule source: " + policy.source());
            }
        }
        this.sources = List.copyOf(sources);
    }

    public record SourcePolicy(String source, boolean enabled, long sourceConfigVersion, int intervalMinutes,
                               int windowDays, int lagDays, int lookbackDays, int maxBackfillDays) {
        public SourcePolicy {
            if (source == null || !NewsIngestionProperties.ALL_SOURCES.contains(source)
                    || sourceConfigVersion < 1 || sourceConfigVersion > NewsWireModels.MAX_SAFE_INT
                    || intervalMinutes < 1 || intervalMinutes > 1440
                    || windowDays < 1 || lagDays < 0 || lagDays > 14
                    || lookbackDays < 0 || lookbackDays > 14 || windowDays > 31 - lookbackDays
                    || maxBackfillDays < windowDays || maxBackfillDays > MAX_BACKFILL_DAYS) {
                throw new IllegalArgumentException("Invalid automatic news collection policy");
            }
        }
    }
}
