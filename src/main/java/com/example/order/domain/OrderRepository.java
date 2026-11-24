package com.example.order.domain;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Optional<Order> findById(String id);

    List<Order> findAll();

    void save(Order order);
}
