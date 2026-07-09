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
 * ClassName: SaveItemListener
 * Package: com.hmall.search.Listener
 * Description: 商品新增ES索引同步监听器
 *
 * @Author Raiden
 * @Create 2026/1/12 09:33
 * @Version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SaveItemListener {
    private final ISearchService searchService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "search.create.queue", durable = "true"),
            exchange = @Exchange(name = "search.topic", type = "topic"),
            key = "search.create"
    ))
    public void listenSaveItem(ItemDTO item) {
        try {
            searchService.createDocument(item);
        } catch (Exception e) {
            log.error("同步ES新增文档失败，itemId={}", item.getId(), e);
        }
    }
}

