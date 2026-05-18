package com.quanla.sagademo.ui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductView(
        UUID id,
        String sku,
        String name,
        int stockAvailable,
        int stockReserved
) {}
