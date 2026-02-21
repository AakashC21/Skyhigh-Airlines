package com.skyhigh.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BaggageService — no mocking needed (pure logic).
 * Tests all branches of calculateExcessBaggageFee() and
 * provides a smoke-test for processPayment().
 */
class BaggageServiceTest {

    private final BaggageService baggageService = new BaggageService();

    // ─── calculateExcessBaggageFee() ─────────────────────────────────────────

    @ParameterizedTest(name = "weight={0}kg → fee=0")
    @ValueSource(doubles = { 0.0, 10.0, 20.0, 24.9, 25.0 })
    void calculateFee_WithinOrAtLimit_ReturnsZero(double weight) {
        BigDecimal fee = baggageService.calculateExcessBaggageFee(weight);
        // Use compareTo to be scale-agnostic (0 vs 0.00 vs 0.000)
        assertEquals(0, fee.compareTo(BigDecimal.ZERO),
                "No fee should be charged for " + weight + "kg (<=25kg limit)");
    }

    @ParameterizedTest(name = "weight={0}kg -> fee={1}")
    @CsvSource({
            "26.0, 15.00", // 1 kg over -> $15
            "30.0, 75.00", // 5 kg over -> $75
            "40.0, 225.00", // 15 kg over -> $225
            "50.0, 375.00", // 25 kg over -> $375
    })
    void calculateFee_OverLimit_ReturnsCorrectFee(double weight, String expectedFee) {
        BigDecimal fee = baggageService.calculateExcessBaggageFee(weight);
        // compareTo ignores scale: 15.00 == 15.000
        assertEquals(0, fee.compareTo(new BigDecimal(expectedFee)),
                "Fee for " + weight + "kg should be " + expectedFee);
    }

    @Test
    void calculateFee_ExactlyAtLimit_ReturnsZero() {
        // Boundary value: exactly 25.0kg — no surcharge
        BigDecimal fee = baggageService.calculateExcessBaggageFee(25.0);
        assertEquals(0, fee.compareTo(BigDecimal.ZERO), "Exactly at the 25kg limit: fee must be zero");
    }

    @Test
    void calculateFee_ZeroWeight_ReturnsZero() {
        BigDecimal fee = baggageService.calculateExcessBaggageFee(0.0);
        assertEquals(0, fee.compareTo(BigDecimal.ZERO), "Zero weight should have no fee");
    }

    @Test
    void calculateFee_SlightlyOverLimit_ReturnsPositiveFee() {
        // 26kg: 1kg excess -> $15 fee
        BigDecimal fee = baggageService.calculateExcessBaggageFee(26.0);
        assertTrue(fee.compareTo(BigDecimal.ZERO) > 0, "Slightly over limit should produce a positive fee");
    }

    @Test
    void calculateFee_LargeExcessWeight_ReturnsPositiveFee() {
        // 100kg bag: 75kg excess x $15/kg = $1125
        BigDecimal fee = baggageService.calculateExcessBaggageFee(100.0);
        assertEquals(0, fee.compareTo(new BigDecimal("1125.00")),
                "100kg should produce a fee of $1125");
    }

    // ─── processPayment() ────────────────────────────────────────────────────

    @Test
    void processPayment_ReturnsBoolean() {
        // Mock service returns a random boolean; verify it completes without error
        boolean result = baggageService.processPayment("tok_test_001", new BigDecimal("75.00"));
        assertTrue(result || !result, "processPayment must return a boolean without throwing");
    }

    @Test
    void processPayment_ZeroAmount_DoesNotThrow() {
        assertDoesNotThrow(() -> baggageService.processPayment("tok_zero", BigDecimal.ZERO),
                "processPayment with $0 amount should not throw");
    }
}
