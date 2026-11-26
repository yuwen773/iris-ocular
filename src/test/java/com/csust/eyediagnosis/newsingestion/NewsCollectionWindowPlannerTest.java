package com.csust.eyediagnosis.newsingestion;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NewsCollectionWindowPlannerTest {

    private final NewsCollectionScheduleProperties.SourcePolicy policy =
            new NewsCollectionScheduleProperties.SourcePolicy("europe-pmc", true, 1, 15, 3, 2, 2, 30);

    @Test
    void initialBackfillIsBoundedAndDoesNotLookBeforeTheCoverageBaseline() {
        LocalDate initial = LocalDate.parse("2026-11-18");
        var plan = NewsCollectionWindowPlanner.plan(policy, initial, initial, LocalDate.parse("2026-11-26"));
        assertEquals(initial, plan.startOn());
        assertEquals(LocalDate.parse("2026-11-21"), plan.endBefore());
    }

    @Test
    void overlapsForLateUpdatesWhileKeepingTheIncompleteDaysOut() {
        var plan = NewsCollectionWindowPlanner.plan(policy, LocalDate.parse("2026-11-22"),
                LocalDate.parse("2026-11-18"), LocalDate.parse("2026-11-26"));
        assertEquals(LocalDate.parse("2026-11-20"), plan.startOn());
        assertEquals(LocalDate.parse("2026-11-24"), plan.endBefore());
    }

    @Test
    void caughtUpDoesNotCreateAnOverlapOnlyTask() {
        var plan = NewsCollectionWindowPlanner.plan(policy, LocalDate.parse("2026-11-24"),
                LocalDate.parse("2026-11-18"), LocalDate.parse("2026-11-26"));
        assertEquals("CAUGHT_UP", plan.status().name());
        assertNull(plan.startOn());
    }

    @Test
    void backfillOutsideTheLimitPausesWithoutSkippingDates() {
        var plan = NewsCollectionWindowPlanner.plan(policy, LocalDate.parse("2026-10-23"),
                LocalDate.parse("2026-10-23"), LocalDate.parse("2026-11-26"));
        assertEquals("BACKFILL_LIMIT", plan.status().name());
        assertNull(plan.endBefore());
    }
}
