package com.concur.dbcache.conf;

import java.lang.annotation.*;

/**
 * 内部自动注入
 * @see DbConfigFactory#getDbCacheServiceBean(java.lang.Class<T>)
 * @author Jake
 * @date 2014年9月21日下午4:50:14
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface Inject {}
