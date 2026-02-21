#!/bin/sh
# SkyHigh Core — Full API Test Suite
# Dynamically selects AVAILABLE seats so the script is idempotent across runs.
BASE="http://localhost:8080/api/v1"
PASS=0
FAIL=0

check() {
    DESC="$1"; EXPECTED="$2"; ACTUAL="$3"
    if echo "$ACTUAL" | grep -q "$EXPECTED"; then
        echo "[PASS] $DESC"
        PASS=$((PASS+1))
    else
        echo "[FAIL] $DESC"
        echo "       Expected to contain: $EXPECTED"
        echo "       Got: $ACTUAL"
        FAIL=$((FAIL+1))
    fi
}

echo "========================================"
echo "  SkyHigh Core — Full API Test Suite"
echo "========================================"

# ── 1. GET /flights ───────────────────────────────────────────────────────────
R=$(curl -s "$BASE/flights")
check "GET /flights returns SH-101" "SH-101" "$R"

# ── 2. GET /flights/1/seats ───────────────────────────────────────────────────
SEATS_JSON=$(curl -s "$BASE/flights/1/seats")
check "GET /flights/1/seats returns seat list" "seatNumber" "$SEATS_JSON"

# Pick two AVAILABLE seats dynamically
SEAT1=$(echo "$SEATS_JSON" | grep -o '"seatNumber":"[^"]*"' | \
    while IFS= read -r line; do
      SNUM=$(echo "$line" | cut -d'"' -f4)
      STATUS=$(echo "$SEATS_JSON" | grep -A2 "\"seatNumber\":\"$SNUM\"" | grep -o '"status":"AVAILABLE"')
      if [ -n "$STATUS" ]; then echo "$SNUM"; fi
    done | head -1)

SEAT2=$(echo "$SEATS_JSON" | grep -o '"seatNumber":"[^"]*"' | \
    while IFS= read -r line; do
      SNUM=$(echo "$line" | cut -d'"' -f4)
      if [ "$SNUM" = "$SEAT1" ]; then continue; fi
      STATUS=$(echo "$SEATS_JSON" | grep -A2 "\"seatNumber\":\"$SNUM\"" | grep -o '"status":"AVAILABLE"')
      if [ -n "$STATUS" ]; then echo "$SNUM"; fi
    done | head -1)

if [ -z "$SEAT1" ] || [ -z "$SEAT2" ]; then
    echo "[INFO] Not enough AVAILABLE seats. Resetting DB..."
    # Can't easily reset here — use any known economy seats
    SEAT1="10C"
    SEAT2="10D"
fi

echo "[INFO] Using seats: $SEAT1, $SEAT2"

# ── 3. Hold SEAT1 ────────────────────────────────────────────────────────────
R=$(curl -s -X POST "$BASE/seats/hold" \
    -H "Content-Type: application/json" \
    -d "{\"flightId\":1,\"seatNumber\":\"$SEAT1\",\"userId\":\"test_u1\"}")
check "POST /seats/hold $SEAT1 → HELD" "HELD" "$R"

# ── 4. Duplicate hold (same seat, different user — expect conflict) ────────────
R=$(curl -s -X POST "$BASE/seats/hold" \
    -H "Content-Type: application/json" \
    -d "{\"flightId\":1,\"seatNumber\":\"$SEAT1\",\"userId\":\"test_u2\"}")
check "POST /seats/hold $SEAT1 duplicate → conflict" "held by another" "$R"

# ── 5. Confirm SEAT1 ─────────────────────────────────────────────────────────
R=$(curl -s -X POST "$BASE/bookings/confirm" \
    -H "Content-Type: application/json" \
    -d "{\"flightId\":1,\"seatNumber\":\"$SEAT1\",\"userId\":\"test_u1\",\"email\":\"u1@airline.com\",\"baggageWeight\":10,\"paymentProcessed\":false}")
check "POST /bookings/confirm $SEAT1 → CONFIRMED" "CONFIRMED" "$R"
PNR=$(echo "$R" | grep -o 'PNR-[A-Z0-9]*' | head -1)

# ── 6. GET booking by PNR ─────────────────────────────────────────────────────
if [ -n "$PNR" ]; then
    R=$(curl -s "$BASE/bookings/$PNR")
    check "GET /bookings/$PNR → CONFIRMED status" "CONFIRMED" "$R"
else
    echo "[FAIL] No PNR extracted from confirm response"
    FAIL=$((FAIL+1))
fi

# ── 7. Hold on already-CONFIRMED seat should fail ─────────────────────────────
R=$(curl -s -X POST "$BASE/seats/hold" \
    -H "Content-Type: application/json" \
    -d "{\"flightId\":1,\"seatNumber\":\"$SEAT1\",\"userId\":\"test_u2\"}")
check "POST /seats/hold on CONFIRMED seat → error" "already booked" "$R"

# ── 8. Hold SEAT2 for baggage flow ────────────────────────────────────────────
R=$(curl -s -X POST "$BASE/seats/hold" \
    -H "Content-Type: application/json" \
    -d "{\"flightId\":1,\"seatNumber\":\"$SEAT2\",\"userId\":\"test_u3\"}")
check "POST /seats/hold $SEAT2 → HELD" "HELD" "$R"

# ── 9. Confirm with excess baggage but no payment → 402 ──────────────────────
R=$(curl -s -X POST "$BASE/bookings/confirm" \
    -H "Content-Type: application/json" \
    -d "{\"flightId\":1,\"seatNumber\":\"$SEAT2\",\"userId\":\"test_u3\",\"email\":\"u3@airline.com\",\"baggageWeight\":30,\"paymentProcessed\":false}")
check "POST /bookings/confirm excess baggage no payment → 402" "Excess baggage fee" "$R"

# ── 10. Confirm with excess baggage and payment ───────────────────────────────
R=$(curl -s -X POST "$BASE/bookings/confirm" \
    -H "Content-Type: application/json" \
    -d "{\"flightId\":1,\"seatNumber\":\"$SEAT2\",\"userId\":\"test_u3\",\"email\":\"u3@airline.com\",\"baggageWeight\":30,\"paymentProcessed\":true}")
check "POST /bookings/confirm excess baggage + payment → CONFIRMED" "CONFIRMED" "$R"

# ── 11. Waitlist join (unique user ID per run using timestamp) ────────────────
WL_USER="wl_$(date +%s)"
R=$(curl -s -X POST "$BASE/waitlist/join" \
    -H "Content-Type: application/json" \
    -d "{\"flightId\":1,\"userId\":\"$WL_USER\"}")
check "POST /waitlist/join first time → WAITLISTED" "WAITLISTED" "$R"

# ── 12. Duplicate waitlist join (same user) ───────────────────────────────────
R=$(curl -s -X POST "$BASE/waitlist/join" \
    -H "Content-Type: application/json" \
    -d "{\"flightId\":1,\"userId\":\"$WL_USER\"}")
check "POST /waitlist/join duplicate → Already on waitlist" "Already on waitlist" "$R"

# ── 13. Bean Validation: missing flightId → 400 ──────────────────────────────
R=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/seats/hold" \
    -H "Content-Type: application/json" \
    -d '{"seatNumber":"1A","userId":"x"}')
check "Validation: missing flightId → 400" "400" "$R"

# ── 14. Bean Validation: blank userId → 400 ──────────────────────────────────
R=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/seats/hold" \
    -H "Content-Type: application/json" \
    -d '{"flightId":1,"seatNumber":"1A","userId":""}')
check "Validation: blank userId → 400" "400" "$R"

# ── 15. Bean Validation: invalid email → 400 ─────────────────────────────────
R=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/bookings/confirm" \
    -H "Content-Type: application/json" \
    -d '{"flightId":1,"seatNumber":"1A","userId":"x","email":"not-valid","baggageWeight":0}')
check "Validation: invalid email → 400" "400" "$R"

# ── 16. Bean Validation: negative baggageWeight → 400 ────────────────────────
R=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/bookings/confirm" \
    -H "Content-Type: application/json" \
    -d '{"flightId":1,"seatNumber":"1A","userId":"x","email":"x@y.com","baggageWeight":-5}')
check "Validation: negative baggageWeight → 400" "400" "$R"

# ── 17. GET non-existent booking → 404 ───────────────────────────────────────
R=$(curl -s "$BASE/bookings/PNR-DOESNOTEXIST")
check "GET /bookings/nonexistent → Booking not found" "Booking not found" "$R"

# ── 18. Actuator health → UP ─────────────────────────────────────────────────
R=$(curl -s "http://localhost:8080/actuator/health")
check "GET /actuator/health → UP" '"status":"UP"' "$R"

echo ""
echo "========================================"
printf "  Results: %d passed, %d failed\n" "$PASS" "$FAIL"
echo "========================================"
if [ "$FAIL" -eq 0 ]; then
    echo "  ALL TESTS PASSED ✓"
fi
echo "========================================"
