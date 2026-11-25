package com.csust.eyediagnosis.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session 绑定 CSRF token（WP-01A）。 */
class CsrfTokenServiceTest {

    private final CsrfTokenService service = new CsrfTokenService();

    @Test
    void issueGeneratesUrlSafeTokenOfExpectedEntropy() {
        MockHttpSession session = new MockHttpSession();
        String token = service.issue(session);

        assertNotNull(token);
        // 32 字节 base64url 无填充 = 43 字符
        assertEquals(43, token.length());
        assertTrue(token.matches("[A-Za-z0-9_-]+"), "token 必须是 URL 安全字符: " + token);
    }

    @Test
    void issueIsStableWithinSession() {
        MockHttpSession session = new MockHttpSession();
        String first = service.issue(session);
        String second = service.issue(session);
        assertEquals(first, second);
    }

    @Test
    void differentSessionsGetDifferentTokens() {
        String a = service.issue(new MockHttpSession());
        String b = service.issue(new MockHttpSession());
        assertFalse(a.equals(b));
    }

    @Test
    void matchesAcceptsTokenFromSameSession() {
        MockHttpSession session = new MockHttpSession();
        String token = service.issue(session);
        assertTrue(service.matches(session, token));
        // 允许首尾空白（前端偶尔会带空格），但值本身必须一致
        assertTrue(service.matches(session, "  " + token + " "));
    }

    @Test
    void matchesRejectsTokenFromAnotherSession() {
        MockHttpSession session = new MockHttpSession();
        MockHttpSession other = new MockHttpSession();
        service.issue(session);
        String foreign = service.issue(other);
        assertFalse(service.matches(session, foreign));
    }

    @Test
    void matchesRejectsNullOrBlankAndUnissuedSession() {
        MockHttpSession session = new MockHttpSession();
        String token = service.issue(session);
        assertFalse(service.matches(session, null));
        assertFalse(service.matches(session, ""));
        assertFalse(service.matches(session, "   "));
        assertFalse(service.matches(session, token + "x"));
        assertFalse(service.matches(null, token));
        assertFalse(service.matches(new MockHttpSession(), token));
    }

    @Test
    void hasTokenTracksIssueAndClear() {
        MockHttpSession session = new MockHttpSession();
        assertFalse(service.hasToken(session));
        assertFalse(service.hasToken(null));
        service.issue(session);
        assertTrue(service.hasToken(session));
        service.clear(session);
        assertFalse(service.hasToken(session));
    }

    @Test
    void issueRequiresSession() {
        assertThrows(IllegalArgumentException.class, () -> service.issue(null));
    }
}
