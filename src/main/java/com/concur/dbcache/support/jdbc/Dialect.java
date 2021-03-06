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


import com.concur.dbcache.support.jdbc.dialect.MysqlDialect;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;


/**
 * Dialect.
 */
public abstract class Dialect {

	public abstract String forTableInfoBuilderDoBuildTableInfo(TableInfo tInfo, String tableName);
	public abstract void forModelSave(TableInfo tableInfo, StringBuilder sql);
	public abstract String forModelDeleteById(TableInfo tInfo);
	public abstract void forModelUpdate(TableInfo tableInfo, StringBuilder sql);
	public abstract void forDbUpdate(TableInfo tableInfo, Collection<String> modifyColumns, StringBuilder sql);
	public abstract String forModelFindById(TableInfo tInfo);
	public abstract String forModelFindByColumn(TableInfo tInfo, String columnName);
	public abstract String forModelFindIdByColumn(TableInfo tInfo, String columnName);
	public abstract void forPaginate(StringBuilder sql, int pageNumber, int pageSize, String select, String sqlExceptSelect);
	public abstract String forModelSelectMax(TableInfo tInfo, String columnName);
	
	public boolean isOracle() {
		return false;
	}
	

	public void fillStatement(PreparedStatement pst, List<Object> paras) throws SQLException {
		if (paras != null) {
			for (int i = 0, size = paras.size(); i < size; i++) {
				pst.setObject(i + 1, paras.get(i));
			}
		}
	}
	
	public void fillStatement(PreparedStatement pst, Object... paras) throws SQLException {
		if (paras != null) {
			for (int i = 0; i < paras.length; i++) {
				pst.setObject(i + 1, paras[i]);
			}
		}
	}
	
	public String getDefaultPrimaryKey() {
		return "id";
	}

	public static Dialect getDefaultDialect() {
		return new MysqlDialect();
	}

}







