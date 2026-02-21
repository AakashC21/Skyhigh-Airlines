package com.skyhigh.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaitlistService {

    private final StringRedisTemplate redisTemplate;

    /**
     * FIX (HIGH): Atomic check-and-add using Redis ZADD NX flag.
     * Replaces the previous two-step (ZRANK → ZADD) pattern which had a
     * TOCTOU race — two concurrent requests could both pass ZRANK and both
     * add the same user, creating a duplicate waitlist entry.
     *
     * ZADD NX is a single atomic Redis command: it adds the member ONLY if
     * it does not already exist in the sorted set. Returns true if added.
     */
    public boolean joinWaitlistIfAbsent(Long flightId, String userId) {
        String key = "waitlist:" + flightId;
        double score = System.currentTimeMillis();

        // addIfAbsent maps to ZADD NX — atomic, no race condition
        Boolean added = redisTemplate.opsForZSet().addIfAbsent(key, userId, score);
        if (Boolean.TRUE.equals(added)) {
            log.info("User {} joined waitlist for flight {} at position {}",
                    userId, flightId, getWaitlistPosition(flightId, userId));
            return true;
        }
        log.debug("User {} is already on waitlist for flight {}", userId, flightId);
        return false;
    }

    /**
     * Legacy join (non-atomic) — kept for internal scheduler use only.
     * External callers should use joinWaitlistIfAbsent().
     */
    public void joinWaitlist(Long flightId, String userId) {
        String key = "waitlist:" + flightId;
        redisTemplate.opsForZSet().add(key, userId, System.currentTimeMillis());
        log.info("User {} joined waitlist for flight {}", userId, flightId);
    }

    /**
     * Pops the next user from the waitlist (lowest score = earliest joiner).
     * Called by CleanupScheduler when a seat is released.
     */
    public String popNextUser(Long flightId) {
        String key = "waitlist:" + flightId;
        Set<ZSetOperations.TypedTuple<String>> popped = redisTemplate.opsForZSet().popMin(key, 1);
        if (popped == null || popped.isEmpty()) {
            return null;
        }
        return popped.iterator().next().getValue();
    }

    /**
     * Returns the 0-based rank of a user in the waitlist, or null if not present.
     */
    public Long getWaitlistPosition(Long flightId, String userId) {
        String key = "waitlist:" + flightId;
        return redisTemplate.opsForZSet().rank(key, userId);
    }
}
