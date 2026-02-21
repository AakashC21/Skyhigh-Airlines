package com.skyhigh.core.service;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.Random;

@Service
public class BaggageService {

    private static final double MAX_WEIGHT_KG = 25.0;
    private static final BigDecimal EXCESS_FEE_PER_KG = new BigDecimal("15.00");
    private final Random random = new Random();

    public BigDecimal calculateExcessBaggageFee(double weight) {
        if (weight <= MAX_WEIGHT_KG) {
            return BigDecimal.ZERO;
        }
        double excess = weight - MAX_WEIGHT_KG;
        return EXCESS_FEE_PER_KG.multiply(BigDecimal.valueOf(excess));
    }

    public boolean processPayment(String paymentToken, BigDecimal amount) {
        // Mock payment processing simulation
        // 80% success rate
        try {
            Thread.sleep(200); // Simulate network latency
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return random.nextInt(100) < 80;
    }
}
