package com.quanla.sagademo.ui.service;

import com.quanla.sagademo.ui.dto.ProductView;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductGateway {

    private final RestClient inventoryRestClient;

    public List<ProductView> listProducts() {
        return inventoryRestClient.get()
                .uri("/api/products")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }
}
