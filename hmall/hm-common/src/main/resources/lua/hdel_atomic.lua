-- 批量删除 Hash 字段（原子操作）
-- KEYS[1] = hash key
-- ARGV... = fields to delete

for i, field in ipairs(ARGV) do
    redis.call('HDEL', KEYS[1], field)
end
return 1
