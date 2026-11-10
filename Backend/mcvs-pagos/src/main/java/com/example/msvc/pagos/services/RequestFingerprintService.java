package com.example.msvc.pagos.services;

import com.example.msvc.pagos.enums.PaymentMethod;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class RequestFingerprintService {

    private final ObjectMapper objectMapper;

    public RequestFingerprintService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String paymentFingerprint(UUID purchaseId, BigDecimal amount, PaymentMethod paymentMethod) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("purchaseId", purchaseId);
        payload.put("amount", amount.setScale(2).toPlainString());
        payload.put("paymentMethod", paymentMethod.name());
        return fingerprint(payload);
    }

    public String refundFingerprint(UUID paymentId, UUID refundRequestId, BigDecimal amount, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", paymentId);
        payload.put("refundRequestId", refundRequestId);
        payload.put("amount", amount.setScale(2).toPlainString());
        payload.put("reason", reason);
        return fingerprint(payload);
    }

    private String fingerprint(Map<String, Object> payload) {
        try {
            byte[] serialized = objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(serialized);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("No se pudo calcular la huella de la solicitud", exception);
        }
    }
}
