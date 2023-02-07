package org.ltejeda.orderservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ltejeda.orderservice.dto.InventoryResponse;
import org.ltejeda.orderservice.dto.OrderLineItemsDto;
import org.ltejeda.orderservice.dto.OrderRequest;
import org.ltejeda.orderservice.event.OrderPlacedEvent;
import org.ltejeda.orderservice.model.Order;
import org.ltejeda.orderservice.model.OrderLineItems;
import org.ltejeda.orderservice.repository.OrderRepository;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final WebClient.Builder webClientBuilder;
    private final Tracer tracer;
    private final KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate;

    public String placeOrder(OrderRequest orderRequest) {
        Order order = new Order();
        order.setOrderNumber(UUID.randomUUID().toString());

        List<OrderLineItems> orderLineItems = orderRequest.getOrderLineItemsDtoList()
                .stream()
                .map(this::mapToDto)
                .toList();
        order.setOrderLineItemsList(orderLineItems);

        List<String> skuCodes = order.getOrderLineItemsList().stream()
                .map(OrderLineItems::getSkuCode)
                .toList();
        log.info("Calling inventory service");

        Span inventoryServiceLookUp = tracer.nextSpan().name("InventoryServiceLookUp");

        try (Tracer.SpanInScope spanInScope = tracer.withSpan(inventoryServiceLookUp.start())) {
            //Call InventoryService and place order if product is in Stock
            InventoryResponse[] inventoryResponseArray = webClientBuilder.build().get()
                    .uri("http://inventory-service/api/inventory",
                            uriBuilder -> uriBuilder.queryParam("skuCode", skuCodes).build())
                    .retrieve()
                    .bodyToMono(InventoryResponse[].class)
                    .block();

            boolean allProductsInStock = Arrays.stream(inventoryResponseArray)
                    .allMatch(InventoryResponse::isInStock);

            if (allProductsInStock) {
                orderRepository.save(order);
                kafkaTemplate.send("notificationTopic",new OrderPlacedEvent(order.getOrderNumber()));
                return "Order placed successfully";
            } else {
                throw new IllegalArgumentException("Product is not in stock, please try again later");
            }
        } finally {
            inventoryServiceLookUp.end();
        }
    }

    private OrderLineItems mapToDto(OrderLineItemsDto orderLineItemsDto) {
        OrderLineItems orderLineItems = new OrderLineItems();
        orderLineItems.setPrice(orderLineItemsDto.getPrice());
        orderLineItems.setQuantity(orderLineItemsDto.getQuantity());
        orderLineItems.setSkuCode(orderLineItemsDto.getSkuCode());
        return orderLineItems;
    }
}
