package com.csust.eyediagnosis.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户反馈状态机的纯单元测试：合法 / 非法迁移只看这张表。
 *
 * <p>这是整条链路里唯一「不看数据库也知道对不对」的部分，因此把它单独钉死：
 * 服务层的条件更新只是让这张表的判断在并发下也成立，判断本身在这里。
 */
class UserFeedbackStatusTest {

    @Test
    void allowedTransitionsMatchTheDocumentedMatrix() {
        // 待处理：受理、直接结案、忽略
        assertTrue(UserFeedbackStatus.canMove(UserFeedbackStatus.PENDING, UserFeedbackStatus.PROCESSING));
        assertTrue(UserFeedbackStatus.canMove(UserFeedbackStatus.PENDING, UserFeedbackStatus.RESOLVED));
        assertTrue(UserFeedbackStatus.canMove(UserFeedbackStatus.PENDING, UserFeedbackStatus.IGNORED));
        // 处理中：可以退回待处理、结案、忽略
        assertTrue(UserFeedbackStatus.canMove(UserFeedbackStatus.PROCESSING, UserFeedbackStatus.PENDING));
        assertTrue(UserFeedbackStatus.canMove(UserFeedbackStatus.PROCESSING, UserFeedbackStatus.RESOLVED));
        assertTrue(UserFeedbackStatus.canMove(UserFeedbackStatus.PROCESSING, UserFeedbackStatus.IGNORED));
        // 已解决：只能重新打开
        assertTrue(UserFeedbackStatus.canMove(UserFeedbackStatus.RESOLVED, UserFeedbackStatus.PROCESSING));
        // 已忽略：只能重新打开
        assertTrue(UserFeedbackStatus.canMove(UserFeedbackStatus.IGNORED, UserFeedbackStatus.PENDING));
    }

    @Test
    void forbiddenTransitionsAreRejected() {
        // 结案之间不能直接互跳：已解决 → 已忽略 会抹掉已经给出的结论
        assertFalse(UserFeedbackStatus.canMove(UserFeedbackStatus.RESOLVED, UserFeedbackStatus.IGNORED));
        assertFalse(UserFeedbackStatus.canMove(UserFeedbackStatus.RESOLVED, UserFeedbackStatus.PENDING));
        assertFalse(UserFeedbackStatus.canMove(UserFeedbackStatus.IGNORED, UserFeedbackStatus.RESOLVED));
        assertFalse(UserFeedbackStatus.canMove(UserFeedbackStatus.IGNORED, UserFeedbackStatus.PROCESSING));
        // 同状态自我迁移不是合法动作（服务层据此判「你慢了一步」）
        assertFalse(UserFeedbackStatus.canMove(UserFeedbackStatus.PENDING, UserFeedbackStatus.PENDING));
        assertFalse(UserFeedbackStatus.canMove(UserFeedbackStatus.RESOLVED, UserFeedbackStatus.RESOLVED));
    }

    @Test
    void unknownOrNullValuesNeverMove() {
        assertFalse(UserFeedbackStatus.canMove(null, UserFeedbackStatus.PENDING));
        assertFalse(UserFeedbackStatus.canMove(UserFeedbackStatus.PENDING, null));
        assertFalse(UserFeedbackStatus.canMove("BOGUS", UserFeedbackStatus.PENDING));
        assertFalse(UserFeedbackStatus.canMove(UserFeedbackStatus.PENDING, "BOGUS"));
        assertFalse(UserFeedbackStatus.isValid(null));
        assertFalse(UserFeedbackStatus.isValid("BOGUS"));
        assertEquals("UNKNOWN", UserFeedbackStatus.orUnknown(null));
    }

    @Test
    void everyDocumentedStatusIsValid() {
        assertTrue(UserFeedbackStatus.isValid(UserFeedbackStatus.PENDING));
        assertTrue(UserFeedbackStatus.isValid(UserFeedbackStatus.PROCESSING));
        assertTrue(UserFeedbackStatus.isValid(UserFeedbackStatus.RESOLVED));
        assertTrue(UserFeedbackStatus.isValid(UserFeedbackStatus.IGNORED));
    }

    @Test
    void categoriesAreClosedSet() {
        assertTrue(UserFeedbackCategory.isValid(UserFeedbackCategory.SUGGESTION));
        assertTrue(UserFeedbackCategory.isValid(UserFeedbackCategory.ISSUE));
        assertTrue(UserFeedbackCategory.isValid(UserFeedbackCategory.RESULT_DOUBT));
        assertTrue(UserFeedbackCategory.isValid(UserFeedbackCategory.OTHER));
        assertFalse(UserFeedbackCategory.isValid(null));
        assertFalse(UserFeedbackCategory.isValid("COMPLAINT"));
    }
}
