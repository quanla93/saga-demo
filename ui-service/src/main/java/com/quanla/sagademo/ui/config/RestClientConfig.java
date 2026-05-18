package com.quanla.sagademo.ui.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient orderRestClient(@Value("${saga.order-service-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public RestClient inventoryRestClient(@Value("${saga.inventory-service-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
