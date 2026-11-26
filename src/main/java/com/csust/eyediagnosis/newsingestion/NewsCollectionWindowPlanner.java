package com.csust.eyediagnosis.newsingestion;

import java.time.LocalDate;

final class NewsCollectionWindowPlanner {

    enum Status {
        QUEUED, CAUGHT_UP, BACKFILL_LIMIT, INITIAL_CURSOR_REQUIRED,
        CONFIG_CURSOR_MISMATCH, WAITING_FOR_TASK, FAILED_WINDOW, IDENTITY_CONFLICT
    }

    record Plan(Status status, LocalDate startOn, LocalDate endBefore) {
    }

    private NewsCollectionWindowPlanner() {
    }

    static Plan plan(NewsCollectionScheduleProperties.SourcePolicy policy, LocalDate cursor,
                     LocalDate baseline, LocalDate utcToday) {
        LocalDate safeEnd = utcToday.minusDays(policy.lagDays());
        if (!cursor.isBefore(safeEnd)) {
            return new Plan(Status.CAUGHT_UP, null, null);
        }
        if (cursor.isBefore(safeEnd.minusDays(policy.maxBackfillDays()))) {
            return new Plan(Status.BACKFILL_LIMIT, null, null);
        }
        LocalDate start = cursor.minusDays(policy.lookbackDays());
        LocalDate end = cursor.plusDays(policy.windowDays());
        return new Plan(Status.QUEUED, start.isBefore(baseline) ? baseline : start,
                end.isAfter(safeEnd) ? safeEnd : end);
    }
}
