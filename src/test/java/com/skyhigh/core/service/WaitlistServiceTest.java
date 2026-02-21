package com.skyhigh.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class WaitlistServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @InjectMocks
    private WaitlistService waitlistService;

    private static final Long FLIGHT_ID = 1L;
    private static final String USER_ID = "user_001";
    private static final String WAITLIST_KEY = "waitlist:1";

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    // ─── joinWaitlistIfAbsent() ──────────────────────────────────────────────

    @Test
    void joinWaitlistIfAbsent_NewUser_ReturnsTrue() {
        when(zSetOperations.addIfAbsent(eq(WAITLIST_KEY), eq(USER_ID), anyDouble()))
                .thenReturn(true);
        // getWaitlistPosition called internally for logging
        when(zSetOperations.rank(eq(WAITLIST_KEY), eq(USER_ID)))
                .thenReturn(0L);

        boolean result = waitlistService.joinWaitlistIfAbsent(FLIGHT_ID, USER_ID);

        assertTrue(result, "New user should be successfully added to waitlist");
        verify(zSetOperations).addIfAbsent(eq(WAITLIST_KEY), eq(USER_ID), anyDouble());
    }

    @Test
    void joinWaitlistIfAbsent_ExistingUser_ReturnsFalse() {
        when(zSetOperations.addIfAbsent(eq(WAITLIST_KEY), eq(USER_ID), anyDouble()))
                .thenReturn(false);

        boolean result = waitlistService.joinWaitlistIfAbsent(FLIGHT_ID, USER_ID);

        assertFalse(result, "Duplicate user should return false — ZADD NX rejected");
        verify(zSetOperations).addIfAbsent(eq(WAITLIST_KEY), eq(USER_ID), anyDouble());
        // rank should NOT be called since user was not added
        verify(zSetOperations, never()).rank(any(), any());
    }

    @Test
    void joinWaitlistIfAbsent_NullFromRedis_ReturnsFalse() {
        // Redis may return null on network issues — treat as not added
        when(zSetOperations.addIfAbsent(eq(WAITLIST_KEY), eq(USER_ID), anyDouble()))
                .thenReturn(null);

        boolean result = waitlistService.joinWaitlistIfAbsent(FLIGHT_ID, USER_ID);

        assertFalse(result, "Null response from Redis should be treated as not added");
    }

    // ─── joinWaitlist() ──────────────────────────────────────────────────────

    @Test
    void joinWaitlist_CallsZAdd() {
        doReturn(true).when(zSetOperations).add(eq(WAITLIST_KEY), eq(USER_ID), anyDouble());

        waitlistService.joinWaitlist(FLIGHT_ID, USER_ID);

        verify(zSetOperations).add(eq(WAITLIST_KEY), eq(USER_ID), anyDouble());
    }

    // ─── popNextUser() ───────────────────────────────────────────────────────

    @Test
    void popNextUser_WhenWaitlistHasUser_ReturnsUserId() {
        ZSetOperations.TypedTuple<String> tuple = mock(ZSetOperations.TypedTuple.class);
        when(tuple.getValue()).thenReturn(USER_ID);
        Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
        tuples.add(tuple);
        when(zSetOperations.popMin(WAITLIST_KEY, 1)).thenReturn(tuples);

        String result = waitlistService.popNextUser(FLIGHT_ID);

        assertEquals(USER_ID, result, "Should return the userId of the first-in-queue user");
    }

    @Test
    void popNextUser_WhenWaitlistIsEmpty_ReturnsNull() {
        when(zSetOperations.popMin(WAITLIST_KEY, 1)).thenReturn(Collections.emptySet());

        String result = waitlistService.popNextUser(FLIGHT_ID);

        assertNull(result, "Empty waitlist should return null");
    }

    @Test
    void popNextUser_WhenRedisReturnsNull_ReturnsNull() {
        when(zSetOperations.popMin(WAITLIST_KEY, 1)).thenReturn(null);

        String result = waitlistService.popNextUser(FLIGHT_ID);

        assertNull(result, "Null from Redis should be handled gracefully");
    }

    // ─── getWaitlistPosition() ───────────────────────────────────────────────

    @Test
    void getWaitlistPosition_UserInList_ReturnsRank() {
        when(zSetOperations.rank(WAITLIST_KEY, USER_ID)).thenReturn(2L);

        Long rank = waitlistService.getWaitlistPosition(FLIGHT_ID, USER_ID);

        assertEquals(2L, rank, "Should return the 0-based rank from Redis");
    }

    @Test
    void getWaitlistPosition_UserNotInList_ReturnsNull() {
        when(zSetOperations.rank(WAITLIST_KEY, USER_ID)).thenReturn(null);

        Long rank = waitlistService.getWaitlistPosition(FLIGHT_ID, USER_ID);

        assertNull(rank, "User not in waitlist should return null rank");
    }

    @Test
    void getWaitlistPosition_FirstInQueue_ReturnsZero() {
        when(zSetOperations.rank(WAITLIST_KEY, USER_ID)).thenReturn(0L);

        Long rank = waitlistService.getWaitlistPosition(FLIGHT_ID, USER_ID);

        assertEquals(0L, rank, "First in queue should have rank 0");
    }
}
