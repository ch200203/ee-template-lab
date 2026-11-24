package com.example.order.infra;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PaymentClient {
    private final RestClient rest = RestClient.create("https://payments.internal");

    public void cancel(String orderId) {
        rest.post().uri("/payments/{id}/cancel", orderId).retrieve().toBodilessEntity();
    }
}
