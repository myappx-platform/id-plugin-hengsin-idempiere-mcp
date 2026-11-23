/**********************************************************************
* This file is part of iDempiere ERP Open Source                      *
* http://www.idempiere.org                                            *
*                                                                     *
* Copyright (C) Contributors                                          *
*                                                                     *
* This program is free software; you can redistribute it and/or       *
* modify it under the terms of the GNU General Public License         *
* as published by the Free Software Foundation; either version 2      *
* of the License, or (at your option) any later version.              *
*                                                                     *
* This program is distributed in the hope that it will be useful,     *
* but WITHOUT ANY WARRANTY; without even the implied warranty of      *
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the        *
* GNU General Public License for more details.                        *
*                                                                     *
* You should have received a copy of the GNU General Public License   *
* along with this program; if not, write to the Free Software         *
* Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,          *
* MA 02110-1301, USA.                                                 *
**********************************************************************/
package org.idempiere.mcp.server.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.compiere.model.MColumn;
import org.compiere.model.MTable;
import org.compiere.model.Query;
import org.compiere.util.Env;

/**
 * When a REST filter names a column the queried table does not have, say which table owns it.
 * The REST exception text itself stays {@code Invalid column for filter}; this only extends the
 * tool result the model reads.
 */
public final class FilterColumnHint {

	private static final Pattern COLUMN = Pattern.compile(
			"Invalid column for filter:\\s*([A-Za-z_][A-Za-z0-9_]*)");

	private FilterColumnHint() {
	}

	/** Append an ownership hint when {@code message} is an invalid-filter error. */
	public static String enrich(String message, String queriedTable) {
		if (message == null || !message.contains("Invalid column for filter")) {
			return message;
		}
		String column = columnName(message);
		if (column == null) {
			return message;
		}
		return compose(message, queriedTable, lookup(column));
	}

	/**
	 * Pure assembly. {@code owners} is the active tables that have {@code column}, base tables
	 * before views.
	 */
	public static String compose(String message, String queriedTable, List<String> owners) {
		String column = columnName(message);
		if (column == null) {
			return message;
		}
		String table = queriedTable != null && !queriedTable.isBlank() ? queriedTable.trim() : "this table";
		boolean onQueried = false;
		if (owners != null) {
			for (String owner : owners) {
				if (owner != null && owner.equalsIgnoreCase(table)) {
					onQueried = true;
					break;
				}
			}
		}
		StringBuilder hint = new StringBuilder();
		if (onQueried) {
			hint.append('`').append(column).append("` is a column of ").append(table)
					.append(", but this filter is not accepted (the column may be secure, encrypted, or not stored on the table). Do not filter it here.");
		} else if (owners == null || owners.isEmpty()) {
			hint.append('`').append(column).append("` is not a column of ").append(table)
					.append(", and it is not an active column on any table.");
		} else {
			hint.append('`').append(column).append("` is not a column of ").append(table)
					.append(". It belongs to: ");
			boolean first = true;
			for (String owner : owners) {
				if (owner == null || owner.isBlank()) {
					continue;
				}
				if (!first) {
					hint.append(", ");
				}
				first = false;
				hint.append(owner);
			}
			hint.append(". Filter that table. Do not filter a column that is not on this table.");
		}
		return message + " " + hint;
	}

	private static String columnName(String message) {
		if (message == null) {
			return null;
		}
		Matcher matcher = COLUMN.matcher(message);
		if (!matcher.find()) {
			return null;
		}
		return matcher.group(1);
	}

	private static List<String> lookup(String column) {
		List<String> tables = new ArrayList<String>();
		List<String> views = new ArrayList<String>();
		try {
			List<MColumn> columns = new Query(Env.getCtx(), MColumn.Table_Name,
					"UPPER(ColumnName)=UPPER(?)", null)
					.setParameters(column)
					.setOnlyActiveRecords(true)
					.list();
			for (MColumn columnRow : columns) {
				if (columnRow == null) {
					continue;
				}
				MTable table = MTable.get(Env.getCtx(), columnRow.getAD_Table_ID());
				if (table == null || !table.isActive()) {
					continue;
				}
				String name = table.getTableName();
				if (name == null || name.isBlank()) {
					continue;
				}
				if (table.isView()) {
					if (!views.contains(name)) {
						views.add(name);
					}
				} else if (!tables.contains(name)) {
					tables.add(name);
				}
			}
		} catch (RuntimeException e) {
			return tables;
		}
		tables.addAll(views);
		if (tables.size() > 8) {
			return new ArrayList<String>(tables.subList(0, 8));
		}
		return tables;
	}

	/** No database. Checks the sentence the model should see for {@code AD_User.IsSalesRep}. */
	public static void main(String[] args) {
		String message = "Search Records API Error: API Error 400: Invalid column for filter: IsSalesRep";
		List<String> owners = new ArrayList<String>();
		owners.add("C_BPartner");
		owners.add("RV_BPartner");
		owners.add("C_Order_LineTax_v");
		String hinted = compose(message, "AD_User", owners);
		if (!hinted.contains("not a column of AD_User") || !hinted.contains("C_BPartner")
				|| !hinted.contains("Invalid column for filter: IsSalesRep")) {
			System.err.println(hinted);
			System.exit(1);
		}
		List<String> onTable = new ArrayList<String>();
		onTable.add("C_BPartner");
		String secure = compose(message, "C_BPartner", onTable);
		if (!secure.contains("is a column of C_BPartner") || secure.contains("belongs to")) {
			System.err.println(secure);
			System.exit(1);
		}
		String untouched = compose("some other error", "AD_User", owners);
		if (!untouched.equals("some other error")) {
			System.err.println(untouched);
			System.exit(1);
		}
		System.out.println("filter column hint ok");
	}
}
