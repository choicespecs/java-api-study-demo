package com.example.apidemo.service;

import com.example.apidemo.model.ApiKey;
import com.example.apidemo.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;

    /**
     * Validates an API key by:
     *   1. Checking it exists and is active (DB lookup)
     *   2. Checking it has not expired
     *
     * SECURITY NOTE: In production, store a SHA-256 hash of the key in the DB
     * (similar to password hashing). The key should only be shown once.
     */
    public Optional<ApiKey> validateApiKey(String keyValue) {
        return apiKeyRepository.findByKeyValueAndActiveTrue(keyValue)
                .filter(key -> key.getExpiresAt() == null
                               || key.getExpiresAt().isAfter(Instant.now()));
    }
}
