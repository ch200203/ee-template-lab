package com.example.order.application;

import com.example.order.domain.Order;
import com.example.order.domain.OrderRepository;
import com.example.order.infra.PaymentClient;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private final OrderRepository orders;
    private final PaymentClient payments;

    public OrderService(OrderRepository orders, PaymentClient payments) {
        this.orders = orders;
        this.payments = payments;
    }

    @Transactional
    public void cancel(String id) {
        Order order = orders.findById(id).orElseThrow();
        if ("PAID".equals(order.status())) {
            payments.cancel(id);
        }
        orders.save(new Order(id, "CANCELLED"));
    }

    public List<Order> list() {
        return orders.findAll();
    }

    public void cancelAll(List<String> ids) {
        for (String id : ids) {
            Order order = orders.findById(id).orElseThrow();
            orders.save(new Order(order.id(), "CANCELLED"));
        }
    }
}
