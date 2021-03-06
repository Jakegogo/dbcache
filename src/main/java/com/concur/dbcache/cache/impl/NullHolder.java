package com.concur.dbcache.cache.impl;

import com.concur.dbcache.cache.ValueWrapper;

import java.io.Serializable;

/**
 * 空值占位
 */
@SuppressWarnings("serial") 
public class NullHolder implements ValueWrapper, Serializable {

	@Override
	public Object get() {
		return null;
	}


	@Override
	public boolean equals(Object o) {
		return o == null || o == this;
	}

	@Override
	public int hashCode() {
		return 0;
	}

}