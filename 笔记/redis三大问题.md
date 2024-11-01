## 一个简单的查询数据库以及redis的方法
~~~ java
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Object queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从Redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //4.不存在，根据id查询数据库
        Shop shop = getById(id);
        //5.不存在，返回错误
        if(shop==null){
            return Result.fail("店铺不存在！");
        }
        //6.存在，写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
        return Result.ok(shop);
    }
}
~~~

## redis与mysql数据的一致性
执行的策略是先更新后删除reids的数据，以及对redis的数据设置ttl设置

~~~ java
@Override
public Object update(Shop shop) {
    Long id = shop.getId();
    if(id == null){
        return Result.fail("商铺id不存在");
    }
    updateById(shop);
    stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
    return Result.ok();
}
~~~
## 缓存穿透解决思路

当查不到的时候，mysql向redis传一个空值
~~~ java
@Override
public Object queryById(Long id) {
    String key = CACHE_SHOP_KEY + id;
    //1.从Redis查询缓存
    String shopJson = stringRedisTemplate.opsForValue().get(key);
    //2.判断是否存在
    if(StrUtil.isNotBlank(shopJson)){
        //3.存在，直接返回
        Shop shop = JSONUtil.toBean(shopJson, Shop.class);
        return Result.ok(shop);
    }
    //上面是有值的情况，下面是无值的2种情况：A：空字符串。B：null。
    if(shopJson != null){
        return Result.fail("店铺信息不存在！");
    }
    //4.不存在，根据id查询数据库
    Shop shop = getById(id);
    //5.不存在，返回错误
    if(shop==null){
        stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
        return Result.fail("店铺不存在！");
    }
    //6.存在，写入Redis
    stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
    return Result.ok(shop);
}
~~~
### 缓存击穿
缓存击穿问题：也叫热点key问题，就是一个被高并发访问并且缓存重建业务较复杂的key突然消失了，无数的请求访问在瞬间给数据库带来巨大的冲击。

### 通过加锁的形式
~~~ java 
public Shop queryWithMutex(Long id){
    String key = CACHE_SHOP_KEY + id;
    //1.从Redis查询缓存
    String shopJson = stringRedisTemplate.opsForValue().get(key);
    //2.判断是否存在
    if(StrUtil.isNotBlank(shopJson)){
        //3.存在，直接返回
        Shop shop = JSONUtil.toBean(shopJson, Shop.class);
        return shop;
    }
    //上面是有值的情况，下面是无值的2种情况：A：空字符串。B：null。
    if(shopJson != null){
        return null;
    }
    //4.实现缓存重建
    //4.1 获取互斥锁
    String lockKey = LOCK_SHOP_KEY+id;
    Shop shop = null;
    try {
        boolean isLock = tryLock(lockKey);
        //4.2 判断是否获取成功
        if(!isLock){
            //4.3 失败，则休眠并重试
            Thread.sleep(50);
            return queryWithMutex(id);
        }
 
        //4.4 获取互斥锁成功，根据id查询数据库
        shop = getById(id);
        //模拟重建的延时
        Thread.sleep(200);
        //5.数据库查询失败，返回错误
        if(shop==null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
        throw new RuntimeException(e);
    }finally {
        //7.释放互斥锁
        unLock(lockKey);
    }
    //8.返回
    return shop;
}
~~~~
### 逻辑过期
~~~ java
private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
public Shop queryWithLogicalExpire(Long id){
    String key = CACHE_SHOP_KEY + id;
    //1.从Redis查询缓存
    String shopJson = stringRedisTemplate.opsForValue().get(key);
    //2.判断是否存在
    if(StrUtil.isBlank(shopJson)){
        //3.不存在，返回空
        return null;
    }
    //4.命中，需要先把json反序列化为对象
    RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
    JSONObject data = (JSONObject) redisData.getData();
    Shop shop = JSONUtil.toBean(data, Shop.class);
    //5.判断是否过期
    //5.1 未过期直接返回店铺信息
    LocalDateTime expireTime = redisData.getExpireTime();
    if(expireTime.isAfter(LocalDateTime.now())){
        return shop;
    }
    //5.2 已过期重建缓存
    //6.缓存重建
    //6.1.获取互斥锁
    String lockKey = LOCK_SHOP_KEY + id;
    boolean isLock = tryLock(lockKey);
    //6.2.判断是否获取互斥锁成功
    if(isLock){
        //6.3.成功，开启独立线程，实现缓存重建
        CACHE_REBUILD_EXECUTOR.submit(()->{
            try {
                saveShop2Redis(id,20L); //实际中应该设置为30分钟
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unLock(lockKey);
            }
        });
 
    }
    //6.4.失败，返回过期的商铺信息
    return shop;
}
~~~

~~~ java
public void saveShop2Redis(Long id,Long expireSeconds){
    //1.查询店铺数据
    Shop shop = getById(id);
    //2.封装逻辑过期时间
    RedisData redisData = new RedisData();
    redisData.setData(shop);
    redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
    //3.写入Redis
    stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
}
~~~~