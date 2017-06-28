package io.mycat.route.parser.druid.impl;

import java.sql.SQLException;
import java.sql.SQLNonTransientException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAllExpr;
import com.alibaba.druid.sql.ast.expr.SQLAnyExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLExistsExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLInSubQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUpdateSetItem;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlUpdateStatement;

import io.mycat.MycatServer;
import io.mycat.config.ErrorCode;
import io.mycat.config.MycatPrivileges;
import io.mycat.config.MycatPrivileges.Checktype;
import io.mycat.config.model.ERTable;
import io.mycat.config.model.SchemaConfig;
import io.mycat.config.model.TableConfig;
import io.mycat.meta.protocol.MyCatMeta.TableMeta;
import io.mycat.route.RouteResultset;
import io.mycat.route.parser.druid.MycatSchemaStatVisitor;
import io.mycat.route.util.RouterUtil;
import io.mycat.server.ServerConnection;
import io.mycat.server.util.GlobalTableUtil;
import io.mycat.server.util.SchemaUtil;
import io.mycat.server.util.SchemaUtil.SchemaInfo;
import io.mycat.util.StringUtil;
/** 
 * see http://dev.mysql.com/doc/refman/5.7/en/update.html
 * @author huqing.yan
 *
 */
public class DruidUpdateParser extends DefaultDruidParser {
	public SchemaConfig visitorParse(SchemaConfig schema, RouteResultset rrs, SQLStatement stmt, MycatSchemaStatVisitor visitor, ServerConnection sc)
			throws SQLException {
        MySqlUpdateStatement update = (MySqlUpdateStatement) stmt;
        SQLTableSource tableSource = update.getTableSource();
        String schemaName = schema == null ? null : schema.getName();
        if (tableSource instanceof SQLJoinTableSource) {
        	SchemaInfo schemaInfo = SchemaUtil.isNoSharding(sc, schemaName, (SQLJoinTableSource) tableSource, stmt);
			if (schemaInfo == null) {
				String msg = "updating multiple tables is not supported, sql:" + stmt;
				throw new SQLNonTransientException(msg);
			} else {
				rrs.setStatement(RouterUtil.removeSchema(rrs.getStatement(), schemaInfo.schema));
				RouterUtil.routeToSingleNode(rrs, schemaInfo.schemaConfig.getDataNode());
				rrs.setFinishedRoute(true);
				return schema;
			}
		} else {
			SchemaInfo schemaInfo = SchemaUtil.getSchemaInfo(sc.getUser(), schemaName, (SQLExprTableSource) tableSource);
            //数据库校验
			if (schemaInfo == null) {
				String msg = "No database selected";
				throw new SQLException(msg,"3D000", ErrorCode.ER_NO_DB_ERROR);
			}
			//权限控制
			if(!MycatPrivileges.checkPrivilege(sc, schemaInfo.schema, schemaInfo.table, Checktype.UPDATE)){
				String msg = "The statement DML privilege check is not passed, sql:" + stmt;
				throw new SQLNonTransientException(msg);
			}
			schema = schemaInfo.schemaConfig;
			String tableName = schemaInfo.table;
			rrs.setStatement(RouterUtil.removeSchema(rrs.getStatement(), schemaInfo.schema));
			if (RouterUtil.isNoSharding(schema, tableName)) {//整个schema都不分库或者该表不拆分
				RouterUtil.routeToSingleNode(rrs, schema.getDataNode());
				rrs.setFinishedRoute(true);
				return schema;
	        }
			TableConfig tc = schema.getTables().get(tableName);
			super.visitorParse(schema, rrs, stmt, visitor, sc);
			if (tc!=null && tc.isGlobalTable()) {
				if (GlobalTableUtil.useGlobleTableCheck()) {
					String sql = convertUpdateSQL(schemaInfo, update, rrs.getStatement());
					rrs.setStatement(sql);
				}
				RouterUtil.routeToMultiNode(false, rrs, tc.getDataNodes(), tc.isGlobalTable());
				rrs.setFinishedRoute(true);
				return schema;
			}
	        String partitionColumn = tc.getPartitionColumn();
	        String joinKey = tc.getJoinKey();

            /*此方法控制分片字段不会被更新
             */
	        confirmShardColumnNotUpdated(update, schema, tableName, partitionColumn, joinKey, rrs);

            //这里新增检查方法确保子表的关联字段不会被更新
            confirmChildColumnNotUpdated(update,schema,tableName);

	        if (schema.getTables().get(tableName).isGlobalTable() && ctx.getRouteCalculateUnit().getTablesAndConditions().size() > 1) {
	            throw new SQLNonTransientException("global table is not supported in multi table related update " + tableName);
	        }
			if (ctx.getTables().size() == 0) {
				ctx.addTable(schemaInfo.table);
			}
		}
        return schema;
    }
    private String convertUpdateSQL(SchemaInfo schemaInfo, MySqlUpdateStatement update, String orginSQL){
		long opTimestamp = new Date().getTime();
		TableMeta orgTbMeta = MycatServer.getInstance().getTmManager().getSyncTableMeta(schemaInfo.schema,
				schemaInfo.table);
		if (orgTbMeta == null)
			return orginSQL;
		if (!GlobalTableUtil.isInnerColExist(schemaInfo, orgTbMeta))
			return orginSQL; // 没有内部列
		List<SQLUpdateSetItem> items = update.getItems();
		boolean flag = false;
		for (int i = 0; i < items.size(); i++) {
			SQLUpdateSetItem item = items.get(i);
			String col = item.getColumn().toString();

			if (StringUtil.removeBackQuote(col).equalsIgnoreCase(GlobalTableUtil.GLOBAL_TABLE_MYCAT_COLUMN)) {
				flag = true;
				SQLUpdateSetItem newItem = new SQLUpdateSetItem();
				newItem.setColumn(item.getColumn());
				newItem.setValue(new SQLIntegerExpr(opTimestamp));
				items.remove(item);
				items.add(i, newItem);
			}
		}
		if(!flag){
			SQLUpdateSetItem newItem = new SQLUpdateSetItem();
			newItem.setColumn(new SQLIdentifierExpr(GlobalTableUtil.GLOBAL_TABLE_MYCAT_COLUMN));
			newItem.setValue(new SQLIntegerExpr(opTimestamp));
			items.add(newItem);
		}
		return RouterUtil.removeSchema(update.toString(), schemaInfo.schema);
    }

    /*
    * 判断字段是否在SQL AST的节点中，比如 col 在 col = 'A' 中，这里要注意，一些子句中可能会在字段前加上表的别名，
    * 比如 t.col = 'A'，这两种情况， 操作符(=)左边被druid解析器解析成不同的对象SQLIdentifierExpr(无表别名)和
    * SQLPropertyExpr(有表别名)
     */
    private static boolean columnInExpr(SQLExpr sqlExpr, String colName) throws SQLNonTransientException {
        String column;
        if (sqlExpr instanceof SQLIdentifierExpr) {
            column = StringUtil.removeBackQuote(((SQLIdentifierExpr) sqlExpr).getName()).toUpperCase();
        } else if (sqlExpr instanceof SQLPropertyExpr) {
            column = StringUtil.removeBackQuote(((SQLPropertyExpr) sqlExpr).getName()).toUpperCase();
        } else {
            throw new SQLNonTransientException("Unhandled SQL AST node type encountered: " + sqlExpr.getClass());
        }

        return column.equals(colName.toUpperCase());
    }

    /*
    * 当前节点是不是一个子查询
    * IN (select...), ANY, EXISTS, ALL等关键字, IN (1,2,3...) 这种对应的是SQLInListExpr
     */
    private static boolean isSubQueryClause(SQLExpr sqlExpr) throws SQLNonTransientException {
        return (sqlExpr instanceof SQLInSubQueryExpr || sqlExpr instanceof SQLAnyExpr || sqlExpr instanceof SQLAllExpr
                || sqlExpr instanceof SQLQueryExpr || sqlExpr instanceof SQLExistsExpr);
    }

    /*
    * 遍历where子句的AST，寻找是否有与update子句中更新分片字段相同的条件，
    * o 如果发现有or或者xor，然后分片字段的条件在or或者xor中的，这种情况update也无法执行，比如
    *   update mytab set ptn_col = val, col1 = val1 where col1 = val11 or ptn_col = val；
    *   但是下面的这种update是可以执行的
    *   update mytab set ptn_col = val, col1 = val1 where ptn_col = val and (col1 = val11 or col2 = val2);
    * o 如果没有发现与update子句中更新分片字段相同的条件，则update也无法执行，比如
    *   update mytab set ptn_col = val， col1 = val1 where col1 = val11 and col2 = val22;
    * o 如果条件之间都是and，且有与更新分片字段相同的条件，这种情况是允许执行的。比如
    *   update mytab set ptn_col = val, col1 = val1 where ptn_col = val and col1 = val11 and col2 = val2;
    * o 对于一些特殊的运算符，比如between，not，或者子查询，遇到这些子句现在不会去检查分片字段是否在此类子句中，
    *  即使分片字段在此类子句中，现在也认为对应的update语句无法执行。
    *
    * @param whereClauseExpr   where子句的语法树AST
    * @param column   分片字段的名字
    * @param value    分片字段要被更新成的值
    * @hasOR          遍历到whereClauseExpr这个节点的时候，其上层路径中是否有OR/XOR关系运算
    *
    * @return         true，表示update不能执行，false表示可以执行
    */
    private boolean shardColCanBeUpdated(SQLExpr whereClauseExpr, String column, SQLExpr value, boolean hasOR)
            throws SQLNonTransientException {
        boolean canUpdate = false;
        boolean parentHasOR = false;

        if (whereClauseExpr == null)
            return false;

        if (whereClauseExpr instanceof SQLBinaryOpExpr) {
            SQLBinaryOpExpr nodeOpExpr = (SQLBinaryOpExpr) whereClauseExpr;
            /*
            * 条件中有or或者xor的，如果分片字段出现在or/xor的一个子句中，则此update
            * 语句无法执行
             */
            if ((nodeOpExpr.getOperator() == SQLBinaryOperator.BooleanOr) ||
                    (nodeOpExpr.getOperator() == SQLBinaryOperator.BooleanXor)) {
                parentHasOR = true;
            }
            // 发现类似 col = value 的子句
            if (nodeOpExpr.getOperator() == SQLBinaryOperator.Equality) {
                boolean foundCol;
                SQLExpr leftExpr = nodeOpExpr.getLeft();
                SQLExpr rightExpr = nodeOpExpr.getRight();

                foundCol = columnInExpr(leftExpr, column);

                // 发现col = value子句，col刚好是分片字段，比较value与update要更新的值是否一样，并且是否在or/xor子句中
                if (foundCol) {
                    if (rightExpr.getClass() != value.getClass()) {
                        throw new SQLNonTransientException("SQL AST nodes type mismatch!");
                    }

                    canUpdate = rightExpr.toString().equals(value.toString()) && (!hasOR) && (!parentHasOR);
                }
            } else if (nodeOpExpr.getOperator().isLogical()) {
                if (nodeOpExpr.getLeft() != null) {
                    if (nodeOpExpr.getLeft() instanceof SQLBinaryOpExpr) {
                        canUpdate = shardColCanBeUpdated(nodeOpExpr.getLeft(), column, value, parentHasOR);
                    }
                    // else
                    // 此子语句不是 =,>,<等关系运算符(对应的类是SQLBinaryOpExpr)。比如between X and Y
                    // 或者 NOT，或者单独的子查询，这些情况，我们不做处理
                }
                if ((!canUpdate) && nodeOpExpr.getRight() != null) {
                    if (nodeOpExpr.getRight() instanceof SQLBinaryOpExpr) {
                        canUpdate = shardColCanBeUpdated(nodeOpExpr.getRight(), column, value, parentHasOR);
                    }
                    // else
                    // 此子语句不是 =,>,<等关系运算符(对应的类是SQLBinaryOpExpr)。比如between X and Y
                    // 或者 NOT，或者单独的子查询，这些情况，我们不做处理
                }
            } else if (isSubQueryClause(nodeOpExpr)){
                // 对于子查询的检查有点复杂，这里暂时不支持
                return false;
            }
            // else
            // 其他类型的子句，忽略, 如果分片字段在这类子句中，此类情况目前不做处理，将返回false
        }
        // else
        //此处说明update的where只有一个条件，并且不是 =,>,<等关系运算符(对应的类是SQLBinaryOpExpr)。比如between X and Y
        // 或者 NOT，或者单独的子查询，这些情况，我们都不做处理

        return canUpdate;
    }

    private void confirmShardColumnNotUpdated(SQLUpdateStatement update,SchemaConfig schema,String tableName,String partitionColumn,String joinKey,RouteResultset rrs) throws SQLNonTransientException {
        List<SQLUpdateSetItem> updateSetItem = update.getItems();
        if (updateSetItem != null && updateSetItem.size() > 0) {
            boolean hasParent = (schema.getTables().get(tableName).getParentTC() != null);
            for (SQLUpdateSetItem item : updateSetItem) {
                String column = StringUtil.removeBackQuote(item.getColumn().toString().toUpperCase());
                //考虑别名，前面已经限制了update分片表的个数只能有一个，所以这里别名只能是分片表的
                if (column.contains(StringUtil.TABLE_COLUMN_SEPARATOR)) {
                    column = column.substring(column.indexOf(".") + 1).trim().toUpperCase();
                }
                if (partitionColumn != null && partitionColumn.equals(column)) {
                    boolean canUpdate;
                    canUpdate = ((update.getWhere() != null) && shardColCanBeUpdated(update.getWhere(),
                            partitionColumn, item.getValue(), false));

                    if (!canUpdate) {
                        String msg = "Sharding column can't be updated " + tableName + "->" + partitionColumn;
                        LOGGER.warn(msg);
                        throw new SQLNonTransientException(msg);
                    }
                }
                if (hasParent) {
                    if (column.equals(joinKey)) {
                        String msg = "Parent relevant column can't be updated " + tableName + "->" + joinKey;
                        LOGGER.warn(msg);
                        throw new SQLNonTransientException(msg);
                    }
                    rrs.setCacheAble(true);
                }
            }
        }
    }


    /**
     * 确保子表和父表关联的字段不会被更新
     * 注如果是更新子表和父表关联的字段这个限制由方法confirmShardColumnNotUpdated做出
     * @throws SQLNonTransientException
     */
    private void confirmChildColumnNotUpdated(SQLUpdateStatement update,SchemaConfig schema,String tableName) throws  SQLNonTransientException{
		if (schema.getFkErRelations() == null) {
			return;
		}
        List<SQLUpdateSetItem> updateSetItem = update.getItems();
        //遍历此次需要更新的字段
        if (updateSetItem != null && updateSetItem.size() > 0) {
            for (SQLUpdateSetItem item : updateSetItem) {
                String column = StringUtil.removeBackQuote(item.getColumn().toString().toUpperCase());
                if(isJoinColumn(column,schema,tableName)){
                    String msg = "child relevant column can't be updated " + tableName + "->" + column;
                    LOGGER.warn(msg);
                    throw new SQLNonTransientException(msg);
                }
            }
        }
    }


    /**
     * 判断字段是不是在作为某些表格的父表连接字段使用
     * 直接通过在schema配置加载的时候创建的父子关系进行判断
     * 如果存在的只有两种情况，一种作为子表字段，一种作为父表字段
     * confirmShardColumnNotUpdated方法已经检测了第一种情况
     * @param schema
     * @param tableName
     * @return
     */
    private boolean isJoinColumn(String column,SchemaConfig schema,String tableName){
        Map<ERTable, Set<ERTable>> map =  schema.getFkErRelations();
        ERTable key = new ERTable(schema.getName(),tableName,column);
        if(map.containsKey(key)){
            return true;
        }
        return false;
    }
}
