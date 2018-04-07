# 欢迎使用DbCache

## DBCache 的设计哲学

>DBCache沿袭CQRS设计思想, 将高频数据缓存到内存, 实现快速读写, 并提供简易的存取接口. 异步入库同时能够做到更加低延迟的性能. 
>
>轻量级ORM让你在写优雅代码的同时, 兼具高性能的接口服务.
>
>对了,还有你最关心的索引, 依然是DBCache透明的内存索引维护的最大亮点!
>

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



