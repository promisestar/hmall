package com.hmall.search.Listener;

import com.hmall.search.service.ISearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * ClassName: RemoveItemListener
 * Package: com.hmall.search.Listener
 * Description: 商品删除ES索引同步监听器
 *
 * @Author Raiden
 * @Create 2026/1/12 09:34
 * @Version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoveItemListener {
    private final ISearchService searchService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "search.remove.queue", durable = "true"),
            exchange = @Exchange(name = "search.topic", type = "topic"),
            key = "search.remove"
    ))
    public void listenRemoveItem(Long id) {
        try {
            searchService.removeDocumentById(id);
        } catch (Exception e) {
            log.error("同步ES删除文档失败，itemId={}", id, e);
        }
    }
}
