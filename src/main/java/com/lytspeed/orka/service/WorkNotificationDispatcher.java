package com.lytspeed.orka.service;

import com.lytspeed.orka.entity.enums.RequestStatus;
import com.lytspeed.orka.repository.FoodOrderRepository;
import com.lytspeed.orka.repository.RequestRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Load detached notification data briefly, then release the database connection before FCM. */
@Service
public class WorkNotificationDispatcher {
    private static final List<RequestStatus> SERVICE_STATES = List.of(RequestStatus.NEW, RequestStatus.ACCEPTED);
    private static final List<String> FOOD_STATES = List.of("PLACED", "PREPARING");
    private final RequestRepository requests;
    private final FoodOrderRepository foodOrders;
    private final FcmNotificationService notifications;
    private final TransactionTemplate reads;

    public WorkNotificationDispatcher(RequestRepository requests, FoodOrderRepository foodOrders,
                                      FcmNotificationService notifications, PlatformTransactionManager transactions) {
        this.requests = requests;
        this.foodOrders = foodOrders;
        this.notifications = notifications;
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
    }

    public List<WorkNotificationEvents.Created> unfinished() {
        return reads.execute(status -> {
            var work = new ArrayList<WorkNotificationEvents.Created>();
            requests.findReminderIds(SERVICE_STATES).forEach(id ->
                    work.add(new WorkNotificationEvents.Created(WorkNotificationEvents.Kind.SERVICE_REQUEST, id)));
            foodOrders.findReminderIds(FOOD_STATES).forEach(id ->
                    work.add(new WorkNotificationEvents.Created(WorkNotificationEvents.Kind.FOOD_ORDER, id)));
            return List.copyOf(work);
        });
    }

    public void dispatch(WorkNotificationEvents.Created work, boolean reminder, long intervalMinutes) {
        var alert = reads.execute(status -> {
            if (work.kind() == WorkNotificationEvents.Kind.SERVICE_REQUEST) {
                return requests.findById(work.id()).filter(request -> SERVICE_STATES.contains(request.getStatus()))
                        .map(request -> !reminder ? notifications.prepareNewRequest(request)
                                : request.getStatus() == RequestStatus.NEW
                                ? notifications.preparePendingRequestReminder(request, intervalMinutes)
                                : notifications.prepareAcceptedRequestReminder(request, intervalMinutes)).orElse(null);
            }
            return foodOrders.findById(work.id()).filter(order -> FOOD_STATES.contains(order.getStatus()))
                    .map(order -> reminder ? notifications.prepareFoodOrderReminder(order, intervalMinutes)
                            : notifications.prepareNewFoodOrder(order)).orElse(null);
        });
        if (alert != null) notifications.sendPrepared(alert);
    }
}
