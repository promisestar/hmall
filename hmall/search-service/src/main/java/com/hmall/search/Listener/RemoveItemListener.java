package com.hmall.search.Listener;

import com.hmall.search.service.ISearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * ClassName: RemoveItemListener
 * Package: com.hmall.search.Listener
 * Description:
 *
 * @Author Raiden
 * @Create 2026/1/12 09:34
 * @Version 1.0
 */
@Component
@RequiredArgsConstructor
public class RemoveItemListener {
    private final ISearchService searchService;

    @RabbitListener(bindings = @QueueBinding(
            value=@Queue(name = "search.remove.queue", durable = "true"),
            exchange = @Exchange(name = "search.topic", type = "topic"),
            key = "search.remove"
    ))
    public void listenRemoveItem(Long id) throws IOException {
        searchService.removeDocumentById(id);
    }
}
