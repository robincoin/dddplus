package ddd.plus.showcase.wms.domain.order.spec;

import ddd.plus.showcase.wms.domain.order.Order;
import io.github.dddplus.buddy.specification.AbstractSpecification;
import io.github.dddplus.buddy.specification.Notification;

import java.math.BigDecimal;

public class OrderNotFullyCartonized extends AbstractSpecification<Order> {
    @Override
    public boolean isSatisfiedBy(Order order, Notification notification) {
        BigDecimal expectedQty = order.totalExpectedQty();
        BigDecimal cartonizedQty = BigDecimal.valueOf(order.totalCartonizedQty());
        return expectedQty.compareTo(cartonizedQty) > 0;
    }
}
