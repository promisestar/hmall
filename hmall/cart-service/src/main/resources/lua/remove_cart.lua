-- 原子删除购物车条目 Lua 脚本
-- KEYS[1] = cart:user:{userId}       商品元数据 Hash
-- KEYS[2] = cart:user:{userId}:num   数量 Hash
-- ARGV... = itemId 列表              要删除的商品 ID

for i, field in ipairs(ARGV) do
    redis.call('HDEL', KEYS[1], field)
    redis.call('HDEL', KEYS[2], field)
end
return 1
