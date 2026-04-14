package com.example.apidemo.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Simple resource entity used to demonstrate pagination, filtering, and versioning.
 */
@Entity
@Table(name = "products")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    private String category;

    @Builder.Default
    private Integer stock = 0;
}
