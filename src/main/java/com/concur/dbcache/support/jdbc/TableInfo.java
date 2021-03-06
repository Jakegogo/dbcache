/**
 * Copyright (c) 2011-2013, James Zhan 詹波 (jfinal@126.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.concur.dbcache.support.jdbc;


import com.concur.dbcache.conf.ShardStrategy;
import org.apache.commons.lang.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TableInfo save the table info like column name and column type.
 */
public class TableInfo {
	
	private String tableName;
	private String primaryKey;
	private String secondaryKey = null;
	
	private Class<?> modelClass;
	
	@SuppressWarnings("unchecked")
	private Map<String, Class<?>> columnTypeMap = new LinkedHashMap<String, Class<?>>();	//	new HashMap<String, Class<?>>();
	
	/** 分表策略类,null为不分表 */
	private ShardStrategy shardStrategy;
	
	public TableInfo(String tableName, Class<?> modelClass) {
		this(tableName, Dialect.getDefaultDialect().getDefaultPrimaryKey(), modelClass);
	}
	
	public TableInfo(String tableName, String primaryKey, Class<?> modelClass) {
		if (StringUtils.isBlank(tableName))
			throw new IllegalArgumentException("Table name can not be blank.");
		if (modelClass == null)
			throw new IllegalArgumentException("Model class can not be null.");
		
		this.tableName = tableName.trim();
		this.setPrimaryKey(primaryKey.trim());	// this.primaryKey = primaryKey.trim();
		this.modelClass = modelClass;
	}

	public String getTableName() {
		return tableName;
	}

	/**
	 * Model.save() need know what columns belongs to himself that he can saving to db.
	 * Think about auto saving the related table's column in the future.
	 */
	public boolean hasColumnLabel(String columnLabel) {
		return columnTypeMap.containsKey(columnLabel);
	}
	
	// 获取字段列表
	public List<String> getColumnNames() {
		return new ArrayList<String>(columnTypeMap.keySet());
	}
	
	/**
	 * update() and delete() need this method.
	 */
	public String getPrimaryKey() {
		return primaryKey;
	}
	
	public Map<String, Class<?>> getColumnTypeMap() {
		return columnTypeMap;
	}

	public void setPrimaryKey(String primaryKey) {
		if (StringUtils.isBlank(primaryKey))
			throw new IllegalArgumentException("Primary key can not be blank.");
		
		String[] keyArr = primaryKey.split(",");
		if (keyArr.length > 1) {
			if (StringUtils.isBlank(keyArr[0]) || StringUtils.isBlank(keyArr[1]))
				throw new IllegalArgumentException("The composite primary key can not be blank.");
			this.primaryKey = keyArr[0].trim();
			this.secondaryKey = keyArr[1].trim();
		}
		else {
			this.primaryKey = primaryKey;
		}
	}
	
	public String getSecondaryKey() {
		return secondaryKey;
	}
	
	public void setColumnTypeMap(Map<String, Class<?>> columnTypeMap) {
		this.columnTypeMap = columnTypeMap;
	}

	public void setShardStrategy(ShardStrategy shardStrategy) {
		this.shardStrategy = shardStrategy;
	}
	
}



