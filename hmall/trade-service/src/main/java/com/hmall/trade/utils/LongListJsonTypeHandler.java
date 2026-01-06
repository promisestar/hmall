package com.hmall.trade.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 兼容 List<Long> 和 Set<Long> 入参的 JSON 类型处理器
 * 序列化：支持 List/Set → JSON 字符串
 * 反序列化：JSON 字符串 → List<Long>（固定返回 List，保持统一）
 */
@MappedTypes({List.class, Set.class}) // 新增 Set.class 映射
@MappedJdbcTypes(JdbcType.VARCHAR)
public class LongListJsonTypeHandler extends BaseTypeHandler<Collection<Long>> { // 泛型改为 Collection<Long>

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final TypeReference<List<Long>> TYPE_REF = new TypeReference<List<Long>>() {};

    /**
     * 序列化：兼容 List/Set 类型入参，统一转为 JSON 字符串
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Collection<Long> parameter, JdbcType jdbcType) {
        try {
            // 无论传入 List 还是 Set，都转为 List 后序列化（避免 Set 序列化后顺序问题）
            List<Long> targetList = new ArrayList<>(parameter);
            String json = objectMapper.writeValueAsString(targetList);
            ps.setString(i, json);
        } catch (Exception e) {
            throw new RuntimeException("序列化 Collection<Long>（List/Set）到 JSON 失败", e);
        }
    }

    /**
     * 反序列化：从 ResultSet（列名）获取 JSON → List<Long>
     */
    @Override
    public List<Long> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        try {
            String json = rs.getString(columnName);
            return json == null ? null : objectMapper.readValue(json, TYPE_REF);
        } catch (Exception e) {
            throw new RuntimeException("反序列化 JSON 到 List<Long> 失败（列名：" + columnName + "）", e);
        }
    }

    /**
     * 反序列化：从 ResultSet（列索引）获取 JSON → List<Long>
     */
    @Override
    public List<Long> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        try {
            String json = rs.getString(columnIndex);
            return json == null ? null : objectMapper.readValue(json, TYPE_REF);
        } catch (Exception e) {
            throw new RuntimeException("反序列化 JSON 到 List<Long> 失败（列索引：" + columnIndex + "）", e);
        }
    }

    /**
     * 反序列化：从 CallableStatement 获取 JSON → List<Long>
     */
    @Override
    public List<Long> getNullableResult(CallableStatement cs, int parameterIndex) throws SQLException {
        try {
            String json = cs.getString(parameterIndex);
            return json == null ? null : objectMapper.readValue(json, TYPE_REF);
        } catch (Exception e) {
            throw new RuntimeException("反序列化 JSON 到 List<Long> 失败（参数索引：" + parameterIndex + "）", e);
        }
    }
}