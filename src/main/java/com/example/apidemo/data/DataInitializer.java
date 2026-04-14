package com.example.apidemo.data;

import com.example.apidemo.model.ApiKey;
import com.example.apidemo.model.Product;
import com.example.apidemo.model.Role;
import com.example.apidemo.model.User;
import com.example.apidemo.repository.ApiKeyRepository;
import com.example.apidemo.repository.ProductRepository;
import com.example.apidemo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Seeds the database with demo data on application startup.
 *
 * DEMO CREDENTIALS SUMMARY
 * ─────────────────────────────────────────────────────────────────
 * USERS (Basic Auth / JWT login):
 *   admin   / password  → ROLE_ADMIN + ROLE_USER
 *   user    / password  → ROLE_USER
 *   viewer  / password  → ROLE_VIEWER
 *
 * API KEYS (X-API-Key header):
 *   demo-api-key-admin-12345  → ROLE_ADMIN
 *   demo-api-key-user-12345   → ROLE_USER
 *
 * OAUTH2 CLIENTS (POST /oauth2/token):
 *   machine-client / machine-secret  → client_credentials
 *   web-client     / web-secret      → authorization_code
 * ─────────────────────────────────────────────────────────────────
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final ProductRepository productRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        seedUsers();
        seedApiKeys();
        seedProducts();
        log.info("✓ Demo data initialized. See DataInitializer for credentials.");
    }

    private void seedUsers() {
        createUser("admin", "password", "admin@example.com",
                Set.of(Role.ROLE_ADMIN, Role.ROLE_USER));
        createUser("user", "password", "user@example.com",
                Set.of(Role.ROLE_USER));
        createUser("viewer", "password", "viewer@example.com",
                Set.of(Role.ROLE_VIEWER));
        log.info("✓ Users seeded: admin, user, viewer (all passwords: 'password')");
    }

    private void createUser(String username, String rawPassword, String email, Set<Role> roles) {
        if (!userRepository.existsByUsername(username)) {
            userRepository.save(User.builder()
                    .username(username)
                    .password(passwordEncoder.encode(rawPassword)) // BCrypt hash
                    .email(email)
                    .roles(roles)
                    .enabled(true)
                    .build());
        }
    }

    private void seedApiKeys() {
        createApiKey(
                "demo-api-key-admin-12345",
                "Demo Admin Key",
                "admin-service",
                Role.ROLE_ADMIN,
                null  // No expiry
        );
        createApiKey(
                "demo-api-key-user-12345",
                "Demo User Key",
                "user-service",
                Role.ROLE_USER,
                null
        );
        createApiKey(
                "demo-api-key-expired-12345",
                "Expired Key (to test expiry)",
                "old-service",
                Role.ROLE_USER,
                Instant.now().minus(1, ChronoUnit.DAYS) // Already expired
        );
        log.info("✓ API Keys seeded: demo-api-key-admin-12345, demo-api-key-user-12345, demo-api-key-expired-12345");
    }

    private void createApiKey(String key, String description, String owner,
                              Role role, Instant expiresAt) {
        ApiKey apiKey = ApiKey.builder()
                .keyValue(key)
                .description(description)
                .owner(owner)
                .role(role)
                .active(true)
                .expiresAt(expiresAt)
                .build();
        apiKeyRepository.save(apiKey);
    }

    private void seedProducts() {
        String[][] products = {
                {"iPhone 15", "Latest Apple smartphone", "999.99", "Electronics", "50"},
                {"Samsung Galaxy S24", "Android flagship", "849.99", "Electronics", "75"},
                {"Sony WH-1000XM5", "Noise-cancelling headphones", "349.99", "Electronics", "120"},
                {"MacBook Pro 14", "Apple silicon laptop", "1999.99", "Electronics", "30"},
                {"Kindle Paperwhite", "E-reader with glare-free display", "139.99", "Electronics", "200"},
                {"The Pragmatic Programmer", "Classic software engineering book", "45.99", "Books", "500"},
                {"Clean Code", "Robert C. Martin's must-read", "39.99", "Books", "300"},
                {"Designing Data-Intensive Applications", "DDIA by Martin Kleppmann", "49.99", "Books", "250"},
                {"System Design Interview", "Alex Xu's interview guide", "29.99", "Books", "400"},
                {"Nike Air Max 90", "Classic sneakers", "120.00", "Clothing", "150"},
                {"Levi's 501 Jeans", "Classic denim jeans", "59.99", "Clothing", "200"},
                {"Under Armour T-Shirt", "Performance athletic shirt", "34.99", "Clothing", "300"},
                {"Yoga Mat", "Non-slip exercise mat", "29.99", "Sports", "100"},
                {"Dumbbell Set", "Adjustable 5-50lb set", "299.99", "Sports", "40"},
                {"Jump Rope", "Speed rope for cardio", "19.99", "Sports", "150"},
                {"French Press", "8-cup coffee maker", "39.99", "Kitchen", "80"},
                {"Cast Iron Skillet", "Pre-seasoned 12-inch", "44.99", "Kitchen", "60"},
                {"Instant Pot", "7-in-1 pressure cooker", "99.99", "Kitchen", "90"},
                {"LEGO Technic", "Complex engineering set", "149.99", "Toys", "45"},
                {"Rubik's Cube", "Classic 3x3 puzzle", "9.99", "Toys", "500"},
        };

        for (String[] p : products) {
            productRepository.save(Product.builder()
                    .name(p[0])
                    .description(p[1])
                    .price(new BigDecimal(p[2]))
                    .category(p[3])
                    .stock(Integer.parseInt(p[4]))
                    .build());
        }
        log.info("✓ {} products seeded across 5 categories", products.length);
    }
}
