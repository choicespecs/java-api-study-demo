package com.example.apidemo.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.Set;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    /**
     * SECURITY: Always store passwords as a strong hash (BCrypt here).
     * Never store plain text. Never use MD5/SHA1 for passwords.
     */
    @Column(nullable = false)
    private String password;

    @Column(unique = true)
    private String email;

    /**
     * EAGER fetch so roles are loaded with the user — avoids LazyInit exceptions
     * in the security filter chain. For large role sets, evaluate carefully.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Set<Role> roles = Set.of(Role.ROLE_USER);

    @Builder.Default
    private boolean enabled = true;
}
