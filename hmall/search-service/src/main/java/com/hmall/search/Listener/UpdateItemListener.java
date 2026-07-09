package com.hmall.search.Listener;

import com.hmall.search.domain.dto.ItemDTO;
import com.hmall.search.service.ISearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * ClassName: UpdateItemListener
 * Package: com.hmall.search.Listener
 * Description: 商品更新ES索引同步监听器
 *
 * @Author Raiden
 * @Create 2026/1/12 09:34
 * @Version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateItemListener {
    private final ISearchService searchService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "search.update.queue", durable = "true"),
            exchange = @Exchange(name = "search.topic", type = "topic"),
            key = "search.update"
    ))
    public void listenUpdateItem(ItemDTO item) {
        try {
            searchService.updateDocument(item);
        } catch (Exception e) {
            log.error("同步ES更新文档失败，itemId={}", item.getId(), e);
        }
    }
}