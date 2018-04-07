# 欢迎使用DbCache

## DBCache 的设计哲学

>DBCache沿袭CQRS设计思想, 将高频数据缓存到内存, 实现快速读写, 并提供简易的存取接口. 异步入库同时能够做到更加低延迟的性能. 
>
>轻量级ORM让你在写优雅代码的同时, 兼具高性能的接口服务.
>
>对了,还有你最关心的索引, 依然是DBCache透明的内存索引维护的最大亮点!
>

## 设计原则

* 不需要用锁的地方尽量不用到锁;横向扩展设计,减少并发争用资源 ↑
* 维护缓存原子性,数据入库采用类似异步事件驱动方式减少延迟 ↑
* 支持大批量写入数据,相同实体更新操作在入库任务堆积时候回将进行合并写入 ↑
* 积极解耦,模块/组件的方式,基于接口的设计,易于维护和迁移 ↑
* 用户友好性.不需要了解太多的内部原理,不需要太多配置 ↑
* 可监控,易于问题排查和自动恢复(后期将加入write ahead log) ↑
* 注重性能和内存占用控制以及回收效率(使用solr的高性能lrumap) ↑
* 整体结构简单为主,不过度封装 ↓
* 懒加载,预编译操作(动态按需加载数据到缓存,使用asm进行构建实体对象)  ↓
* 细粒度化单独组件,可通过spring配置动态地组装 ↑


## 简易类图
![图片 1](https://raw.githubusercontent.com/Jakegogo/dbcache/master/screenshots/pic2.png)


## 顺序入库+去重 – DbPersistService
-实现方式:
* 队列：采用concurrentLinkedQueue的设计模式。将队列的队头和队尾热点分离，使用CAS入队和出队
* 去重：每个Entity都有一个无固定长度的队列。 比如定义为SafeType，有一个next指针，当next指针不为空，则继续获取next，直到为null就执行入库操作
![图片 2](https://raw.githubusercontent.com/Jakegogo/dbcache/master/screenshots/pic1.png)



## 开始使用
### 1. 引入maven依赖
   
```
<dependency>
    <groupId>com.concur</groupId>
    <artifactId>dbcache</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 2. 配置spring的Bean扫描

```
<context:annotation-config/>
<context:component-scan base-package="com.concur.dbcache" />
	
```

### 3. 定义实体

```
// 1.添加实体注解, 让DbCache能够扫描到
@Entity
public class Mineral implements EntityInitializer, IEntity<Long> {

    // 2.指定主键
    @Id
    private long id;

    // 3.如果需要不同维度的索引查询, 可通过@Index来声明索引
    @Index(name = "ownerId")
    private long ownerId;
```


### 4. 在服务中使用

```
@Service
public class XXServiceImpl implements XXService {

    // 1.声明Entity的仓储服务(DBCache将为之自动注入)
    // 2.指定Entity类型, 在这里Mineral是我们的尸体, Long为主键类型
    @Autowired
    private DbCacheService<Mineral, Long> mineralRepository;
```

### 5. 接口案例

##### a.根据ID查询
```
Mineral mineral = mineralRepository.get(id);
```

##### b.根据索引查询
```
List<Mineral> mineral = mineralRepository.listByIndex("ownerId", 8860L);
```
##### c.执行写入
```
Mineral mineral = new Mineral();
    ......
// 将主键返还赋值到mineral的ID
mineral = mineralRepository.submitCreate(mineral);
```



