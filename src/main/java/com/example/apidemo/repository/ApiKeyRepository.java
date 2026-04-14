package com.example.apidemo.repository;

import com.example.apidemo.model.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /**
     * Only return active keys — expired/revoked keys should never authenticate.
     * Combine with expiresAt check in the service layer.
     */
    Optional<ApiKey> findByKeyValueAndActiveTrue(String keyValue);
}
