"""ProfileStore — 用户画像 Redis 存储，支持增量更新和读取降级。

Layer 1: 行为流（Redis List，最近 50 条，TTL 7 天）
    加购/下单/确认收货事件实时追加，用于回溯分析。
Layer 2: 聚合画像（Redis Hash，增量更新，TTL 30 天）
    category_scores / brand_scores / price_stats，HINCRBY 原子增量聚合。

设计原则：
- Phase 1 代码完整保留，ProfileStore 作为缓存加速层叠加
- record_event 失败 catch 异常不抛，不影响业务主流程
- HINCRBY 原子增量更新，Agent 侧与后端侧共享同一份画像
- get_profile miss 时返回空 dict，调用方降级到 Phase 1 实时计算
"""

import json
import logging
import time

import redis.asyncio as aioredis

from src.core.config import get_settings

logger = logging.getLogger(__name__)
_settings = get_settings()

# Redis Key 前缀，与其他 key 空间隔离
_PREFIX = "profile"

# 行为权重（与 Phase 1 _accumulate_preference 的 weight=5/3 保持一致）
WEIGHTS = {"purchase": 5, "cart": 3, "view": 1}

# TTL 常量
_EVENT_TTL = 7 * 24 * 3600        # 行为流 7 天
_PROFILE_TTL = 30 * 24 * 3600     # 聚合画像 30 天

# 行为流最大保留条数
_EVENT_MAX_LEN = 50
# 价格记录最大保留条数
_PRICE_MAX_LEN = 20


class ProfileStore:
    """用户画像存储，支持增量更新和读取降级。

    使用 redis.asyncio 独立连接池，画像数据存 db=0（PROFILE_REDIS_DB），
    与后端 spring.redis.database=0 共享同一份画像数据。
    通过 profile: 前缀隔离 key 空间。
    """

    def __init__(self, redis_url: str = "", max_connections: int = 20):
        """初始化 Redis 连接池。

        Args:
            redis_url: Redis 连接 URL，默认使用全局配置
            max_connections: 连接池最大连接数
        """
        url = redis_url or _settings.redis_url
        self._pool = aioredis.ConnectionPool.from_url(
            url,
            max_connections=max_connections,
            decode_responses=True,
            db=_settings.PROFILE_REDIS_DB,
        )
        self._redis = aioredis.Redis(connection_pool=self._pool)

    def _key(self, user_id: str, suffix: str) -> str:
        """构建 Redis Key。"""
        return f"{_PREFIX}:{user_id}:{suffix}"

    async def record_event(
        self,
        user_id: str,
        event_type: str,
        item_id: int,
        category: str = "",
        brand: str = "",
        price: int = 0,
        num: int = 1,
    ) -> None:
        """记录行为事件并增量更新聚合画像。

        使用 pipeline 批量执行，HINCRBY 原子增量更新，并发安全。
        异常时静默忽略（画像写入失败不影响业务主流程）。

        Args:
            user_id: 用户 ID
            event_type: 事件类型（purchase / cart / view）
            item_id: 商品 ID
            category: 商品类目
            brand: 商品品牌
            price: 商品价格（分）
            num: 购买/加购数量
        """
        if not user_id:
            return

        weight = WEIGHTS.get(event_type, 1)
        score = weight * num

        events_key = self._key(user_id, "events")
        categories_key = self._key(user_id, "categories")
        brands_key = self._key(user_id, "brands")
        prices_key = self._key(user_id, "prices")
        stats_key = self._key(user_id, "stats")

        event = json.dumps({
            "type": event_type,
            "itemId": item_id,
            "category": category,
            "brand": brand,
            "price": price,
            "num": num,
            "ts": int(time.time()),
        })

        try:
            pipe = self._redis.pipeline()

            # Layer 1: 行为流（LPUSH + LTRIM 保留最近 50 条）
            pipe.lpush(events_key, event)
            pipe.ltrim(events_key, 0, _EVENT_MAX_LEN - 1)
            pipe.expire(events_key, _EVENT_TTL)

            # Layer 2: 增量聚合画像（HINCRBY 原子更新）
            if category:
                pipe.hincrby(categories_key, category, score)
                pipe.expire(categories_key, _PROFILE_TTL)
            if brand:
                pipe.hincrby(brands_key, brand, score)
                pipe.expire(brands_key, _PROFILE_TTL)
            if price:
                pipe.lpush(prices_key, price)
                pipe.ltrim(prices_key, 0, _PRICE_MAX_LEN - 1)
                pipe.expire(prices_key, _PROFILE_TTL)

            # 统计信息
            pipe.hincrby(stats_key, f"{event_type}_count", 1)
            pipe.hset(stats_key, "last_update", int(time.time()))
            pipe.expire(stats_key, _PROFILE_TTL)

            await pipe.execute()
        except Exception as e:
            logger.warning("画像写入失败, user_id=%s, event_type=%s: %s", user_id, event_type, e)

    async def get_profile(self, user_id: str) -> dict:
        """读取聚合画像。

        不存在时返回空 dict，调用方降级到 Phase 1 实时计算。

        Returns:
            包含 categories/brands/prices/stats 的字典，画像不存在时返回空 dict
        """
        if not user_id:
            return {}

        categories_key = self._key(user_id, "categories")
        brands_key = self._key(user_id, "brands")
        prices_key = self._key(user_id, "prices")
        stats_key = self._key(user_id, "stats")

        try:
            pipe = self._redis.pipeline()
            pipe.hgetall(categories_key)
            pipe.hgetall(brands_key)
            pipe.lrange(prices_key, 0, _PRICE_MAX_LEN - 1)
            pipe.hgetall(stats_key)
            categories_raw, brands_raw, prices_raw, stats_raw = await pipe.execute()
        except Exception as e:
            logger.warning("画像读取失败, user_id=%s: %s", user_id, e)
            return {}

        # 画像不存在时返回空 dict（触发降级）
        if not categories_raw and not brands_raw and not prices_raw:
            return {}

        # Redis 返回的 hash field/value 均为 str，需转换为正确类型
        categories = {}
        for k, v in categories_raw.items():
            try:
                categories[k] = int(v)
            except (TypeError, ValueError):
                categories[k] = v

        brands = {}
        for k, v in brands_raw.items():
            try:
                brands[k] = int(v)
            except (TypeError, ValueError):
                brands[k] = v

        prices = []
        for p in prices_raw:
            try:
                prices.append(int(p))
            except (TypeError, ValueError):
                pass

        stats = {}
        for k, v in stats_raw.items():
            try:
                stats[k] = int(v)
            except (TypeError, ValueError):
                stats[k] = v

        return {
            "categories": categories,
            "brands": brands,
            "prices": prices,
            "stats": stats,
        }

    async def top_categories(self, user_id: str, n: int = 3) -> list[str]:
        """Top N 偏好类目（后端推荐服务可共享调用）。"""
        profile = await self.get_profile(user_id)
        categories = profile.get("categories", {})
        sorted_cats = sorted(categories.items(), key=lambda x: x[1], reverse=True)
        return [cat for cat, _ in sorted_cats[:n]]

    async def top_brands(self, user_id: str, n: int = 3) -> list[str]:
        """Top N 偏好品牌。"""
        profile = await self.get_profile(user_id)
        brands = profile.get("brands", {})
        sorted_brands = sorted(brands.items(), key=lambda x: x[1], reverse=True)
        return [brand for brand, _ in sorted_brands[:n]]

    async def invalidate(self, user_id: str) -> None:
        """清除用户画像（用户请求或数据修正时调用）。"""
        if not user_id:
            return
        keys = [
            self._key(user_id, "events"),
            self._key(user_id, "categories"),
            self._key(user_id, "brands"),
            self._key(user_id, "prices"),
            self._key(user_id, "stats"),
        ]
        try:
            await self._redis.delete(*keys)
        except Exception as e:
            logger.warning("画像清除失败, user_id=%s: %s", user_id, e)

    async def backfill_profile(
        self,
        user_id: str,
        category_scores: dict,
        brand_scores: dict,
        price_points: list,
    ) -> None:
        """将 Phase 1 实时计算结果回写到 Redis 画像（冷启动后首次填充）。

        使用 HSET 覆盖写入（非增量），适用于画像 miss 后的全量回写场景。
        """
        if not user_id:
            return

        categories_key = self._key(user_id, "categories")
        brands_key = self._key(user_id, "brands")
        prices_key = self._key(user_id, "prices")
        stats_key = self._key(user_id, "stats")

        try:
            pipe = self._redis.pipeline()

            if category_scores:
                mapping = {k: str(v) for k, v in category_scores.items()}
                pipe.hset(categories_key, mapping=mapping)
                pipe.expire(categories_key, _PROFILE_TTL)
            if brand_scores:
                mapping = {k: str(v) for k, v in brand_scores.items()}
                pipe.hset(brands_key, mapping=mapping)
                pipe.expire(brands_key, _PROFILE_TTL)
            if price_points:
                pipe.rpush(prices_key, *[str(p) for p in price_points[:_PRICE_MAX_LEN]])
                pipe.expire(prices_key, _PROFILE_TTL)

            pipe.hset(stats_key, "last_update", int(time.time()))
            pipe.expire(stats_key, _PROFILE_TTL)

            await pipe.execute()
        except Exception as e:
            logger.warning("画像回写失败, user_id=%s: %s", user_id, e)


# 全局单例实例
profile_store = ProfileStore()
