package com.hmall.item.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 推荐相关 SQL 查询 Mapper
 * <p>
 * 跨表 JOIN order_detail + `order` + item，聚合用户购买偏好。
 * item-service 和 trade-service 共享同一 MySQL 数据库（shared-jdbc.yaml）。
 */
public interface RecommendMapper {

    /**
     * 查询用户已购类目偏好 Top3（按购买数量加权）
     */
    @Select("SELECT i.category, SUM(od.num) AS score " +
            "FROM order_detail od " +
            "JOIN `order` o ON od.order_id = o.id " +
            "JOIN item i ON od.item_id = i.id " +
            "WHERE o.user_id = #{userId} AND o.status IN (2,3,4,6) " +
            "AND i.category IS NOT NULL AND i.category != '' " +
            "GROUP BY i.category " +
            "ORDER BY score DESC " +
            "LIMIT 3")
    List<Map<String, Object>> queryUserCategoryPreferences(@Param("userId") Long userId);

    /**
     * 查询用户已购品牌偏好 Top3（按购买数量加权）
     */
    @Select("SELECT i.brand, SUM(od.num) AS score " +
            "FROM order_detail od " +
            "JOIN `order` o ON od.order_id = o.id " +
            "JOIN item i ON od.item_id = i.id " +
            "WHERE o.user_id = #{userId} AND o.status IN (2,3,4,6) " +
            "AND i.brand IS NOT NULL AND i.brand != '' " +
            "GROUP BY i.brand " +
            "ORDER BY score DESC " +
            "LIMIT 3")
    List<Map<String, Object>> queryUserBrandPreferences(@Param("userId") Long userId);

    /**
     * 查询用户已购商品 ID 列表（用于排除已购）
     */
    @Select("SELECT DISTINCT od.item_id " +
            "FROM order_detail od " +
            "JOIN `order` o ON od.order_id = o.id " +
            "WHERE o.user_id = #{userId} AND o.status IN (2,3,4,6)")
    List<Long> queryPurchasedItemIds(@Param("userId") Long userId);
}
