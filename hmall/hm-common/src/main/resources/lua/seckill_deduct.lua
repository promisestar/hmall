-- 秒杀原子预减 Lua 脚本（限购检查 + 库存预减合一）
-- 对应 redis-application-analysis.md 3.7.3 节，增强版：合并限购检查
--
-- KEYS[1] = seckill:stock:{relationId}     String  当前剩余库存
-- KEYS[2] = seckill:limit:{relationId}     Hash    {userId → 已购数量}
-- ARGV[1] = userId                          用户ID
-- ARGV[2] = quantity                        购买数量（通常为 1）
-- ARGV[3] = limitNum                        每人限购数量
--
-- 返回值：
--   1  = 扣减成功（库存已减 + 限购已增）
--   0  = 库存不足（售罄）
--  -1  = 库存未初始化（Redis Key 不存在，活动未预热）
--  -2  = 超过限购数量

-- 1. 读取当前库存
local stock = redis.call('GET', KEYS[1])
if stock == false then
    return -1
end
stock = tonumber(stock)

-- 2. 读取用户已购数量（HGET 返回 false 表示不存在，视为 0）
local purchased = redis.call('HGET', KEYS[2], ARGV[1])
if purchased == false then
    purchased = 0
else
    purchased = tonumber(purchased)
end

-- 3. 限购检查：已购 + 本次数量 > 限购数 → 拒绝
local quantity = tonumber(ARGV[2])
local limitNum = tonumber(ARGV[3])
if purchased + quantity > limitNum then
    return -2
end

-- 4. 库存检查：不足 → 售罄
if stock < quantity then
    return 0
end

-- 5. 原子扣减库存 + 递增限购计数
redis.call('DECRBY', KEYS[1], quantity)
redis.call('HINCRBY', KEYS[2], ARGV[1], quantity)

return 1
