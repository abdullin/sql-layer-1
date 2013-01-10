/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.sql.optimizer.rule.cost;

import com.akiban.server.geophile.BoxLatLon;
import com.akiban.sql.optimizer.rule.cost.CostEstimator.IndexIntersectionCoster;
import com.akiban.sql.optimizer.rule.cost.CostEstimator.SelectivityConditions;
import com.akiban.sql.optimizer.rule.range.RangeSegment;
import static com.akiban.sql.optimizer.rule.OperatorAssembler.INSERTION_SORT_MAX_LIMIT;
import static com.akiban.sql.optimizer.rule.cost.CostEstimator.simpleRound;

import com.akiban.sql.optimizer.plan.*;

import com.akiban.ais.model.Group;
import com.akiban.ais.model.UserTable;
import com.akiban.server.error.AkibanInternalException;
import com.akiban.server.geophile.SpaceLatLon;
import com.akiban.server.types.AkType;
import com.akiban.server.types3.common.BigDecimalWrapper;

import java.math.BigDecimal;
import java.util.*;

public class PlanCostEstimator
{
    protected CostEstimator costEstimator;
    private PlanEstimator planEstimator;

    public PlanCostEstimator(CostEstimator costEstimator) {
        this.costEstimator = costEstimator;
    }

    public CostEstimate getCostEstimate() {
        return planEstimator.getCostEstimate();
    }

    public static final long NO_LIMIT = -1;

    public void setLimit(long limit) {
        planEstimator.setLimit(limit);
    }

    public void indexScan(IndexScan index) {
        planEstimator = new IndexScanEstimator(index);
    }

    public void spatialIndex(SingleIndexScan index) {
        planEstimator = new SpatialIndexEstimator(index);
    }

    public void flatten(TableGroupJoinTree tableGroup,
                        TableSource indexTable,
                        Set<TableSource> requiredTables) {
        planEstimator = new FlattenEstimator(planEstimator, 
                                             tableGroup, indexTable, requiredTables);
    }

    public void groupScan(GroupScan scan,
                          TableGroupJoinTree tableGroup,
                          Set<TableSource> requiredTables) {
        planEstimator = new GroupScanEstimator(scan, tableGroup, requiredTables);
    }

    public void groupLoop(GroupLoopScan scan,
                          TableGroupJoinTree tableGroup,
                          Set<TableSource> requiredTables) {
        planEstimator = new GroupLoopEstimator(scan, tableGroup, requiredTables);
    }

    public void select(Collection<ConditionExpression> conditions,
                       SelectivityConditions selectivityConditions) {
        planEstimator = new SelectEstimator(planEstimator,
                                            conditions, selectivityConditions);
    }

    public void sort(int nfields) {
        planEstimator = new SortEstimator(planEstimator, nfields);
    }

    protected abstract class PlanEstimator {
        protected PlanEstimator input;
        protected CostEstimate costEstimate = null;
        protected long limit = NO_LIMIT;

        protected PlanEstimator(PlanEstimator input) {
            this.input = input;
        }

        protected CostEstimate getCostEstimate() {
            if (costEstimate == null) {
                estimateCost();
            }
            return costEstimate;
        }

        protected abstract void estimateCost();

        protected void setLimit(long limit) {
            this.limit = limit;
            costEstimate = null;    // Invalidate any previously computed cost.
            // NB: not passed to input; only set on end, which must then
            // propagate some limit back accordingly.
        }

        protected boolean hasLimit() {
            return (limit > 0);
        }

        protected CostEstimate inputCostEstimate() {
            return input.getCostEstimate();
        }

    }

    protected class IndexScanEstimator extends PlanEstimator {
        IndexScan index;

        protected IndexScanEstimator(IndexScan index) {
            super(null);
            this.index = index;
        }

        @Override
        protected void estimateCost() {
            costEstimate = getScanOnlyCost(index);
            long totalCount = costEstimate.getRowCount();
            if (hasLimit() && (limit < totalCount)) {
                if (index instanceof SingleIndexScan) {
                    SingleIndexScan single = (SingleIndexScan)index;
                    if (single.getConditionRange() == null) {
                        costEstimate = costEstimator.costIndexScan(single.getIndex(), 
                                                                   limit);
                        return;
                    }
                }
                // Multiple scans are involved; assume proportional.
                double setupCost = getScanSetupCost(index);
                double scanCost = costEstimate.getCost() - setupCost;
                costEstimate = new CostEstimate(limit,
                                                setupCost + scanCost * limit / totalCount);
            }
        }
    }

    protected class SpatialIndexEstimator extends PlanEstimator {
        SingleIndexScan index;

        protected SpatialIndexEstimator(SingleIndexScan index) {
            super(null);
            this.index = index;
        }

        @Override
        protected void estimateCost() {
            int nscans = 1;
            FunctionExpression func = (FunctionExpression)index.getLowComparand();
            List<ExpressionNode> operands = func.getOperands();
            SpaceLatLon space = (SpaceLatLon)index.getIndex().space();
            if ("_center".equals(func.getFunction())) {
                nscans = 2;     // One in each direction.
                costEstimate = costEstimator.costIndexScan(index.getIndex(),
                                                           index.getEqualityComparands(),
                                                           null, true,
                                                           null, true);
            } else if ("_center_radius".equals(func.getFunction())) {
                BigDecimal lat = decimalConstant(operands.get(0));
                BigDecimal lon = decimalConstant(operands.get(1));
                BigDecimal r = decimalConstant(operands.get(2));
                if ((lat != null) && (lon != null) && (r != null)) {
                    BoxLatLon box = BoxLatLon.newBox(lat.subtract(r), lat.add(r), lon.subtract(r), lon.add(r));
                    long[] zValues = new long[SpaceLatLon.MAX_DECOMPOSITION_Z_VALUES];
                    space.decompose(box, zValues);
                    for (int i = 0; i < SpaceLatLon.MAX_DECOMPOSITION_Z_VALUES; i++) {
                        long z = zValues[i];
                        if (z != -1L) {
                            ExpressionNode lo = new ConstantExpression(space.zLo(z), AkType.LONG);
                            ExpressionNode hi = new ConstantExpression(space.zHi(z), AkType.LONG);
                            CostEstimate zScanCost =
                                costEstimator.costIndexScan(index.getIndex(), index.getEqualityComparands(),
                                                            lo, true,
                                                            hi, true);
                            costEstimate =
                                costEstimate == null
                                ? zScanCost
                                : costEstimate.union(zScanCost);
                        }
                    }
                } else {
                    throw new AkibanInternalException("Operands for spatial index must all be constant numbers: " + func);
                }
            } else {
                throw new AkibanInternalException("Unexpected function for spatial index: " + func);
            }
            index.setScanCostEstimate(costEstimate);
            long totalRows = costEstimate.getRowCount();
            long nrows = totalRows;
            if (hasLimit() && (limit < totalRows)) {
                nrows = limit;
            }
            if (nscans == 1) {
                if (nrows != totalRows)
                    costEstimate = costEstimator.costIndexScan(index.getIndex(), nrows);
                return;
            }
            double setupCost = costEstimator.costIndexScan(index.getIndex(), 0).getCost();
            double scanCost = costEstimate.getCost() - setupCost;
            costEstimate = new CostEstimate(limit,
                                            setupCost * nscans +
                                            scanCost * nrows / totalRows);
        }
    }

    protected static BigDecimal decimalConstant(ExpressionNode expr) {
        // Because the distance_lat_lon function returns a double, the radius
        // may be one for comparison.
        // Also numbers may accidentally be given as integers due to formatting.
        while (expr instanceof CastExpression) {
            expr = ((CastExpression)expr).getOperand();
        }
        if (!(expr instanceof ConstantExpression)) return null;
        Object obj = ((ConstantExpression)expr).getValue();
        if (obj instanceof BigDecimalWrapper)
            obj = ((BigDecimalWrapper)obj).asBigDecimal();
        if (obj instanceof BigDecimal)
            return (BigDecimal)obj;
        else if (obj instanceof Number)
            return BigDecimal.valueOf((long)(((Number)obj).doubleValue() * 1.0e6), 6);
        else
            return null;
    }

    protected class FlattenEstimator extends PlanEstimator {
        private TableGroupJoinTree tableGroup;
        private TableSource indexTable;
        private Set<TableSource> requiredTables;

        protected FlattenEstimator(PlanEstimator input,
                                   TableGroupJoinTree tableGroup,
                                   TableSource indexTable,
                                   Set<TableSource> requiredTables) {
            super(input);
            this.tableGroup = tableGroup;
            this.indexTable = indexTable;
            this.requiredTables = requiredTables;
        }

        @Override
        protected void estimateCost() {
            CostEstimate flattenCost = costEstimator.costFlatten(tableGroup, indexTable, requiredTables);
            long flattenScale = flattenCost.getRowCount();
            input.setLimit(hasLimit() ? 
                           // Ceiling number of inputs needed to get limit flattened.
                           ((limit + flattenScale - 1) / flattenScale) : 
                           NO_LIMIT);
            costEstimate = inputCostEstimate().nest(flattenCost);
        }
    }

    protected class GroupScanEstimator extends PlanEstimator {
        private GroupScan scan;
        private TableGroupJoinTree tableGroup;
        private Set<TableSource> requiredTables;

        protected GroupScanEstimator(GroupScan scan,
                                     TableGroupJoinTree tableGroup,
                                     Set<TableSource> requiredTables) {
            super(null);
            this.scan = scan;
            this.tableGroup = tableGroup;
            this.requiredTables = requiredTables;
        }

        @Override
        protected void estimateCost() {
            if (hasLimit()) {
                Map<UserTable,Long> tableCounts = groupScanTableCountsToLimit(requiredTables, limit);
                if (tableCounts != null) {
                    costEstimate = costEstimator.costPartialGroupScanAndFlatten(tableGroup, requiredTables, tableCounts);
                    return;
                }
            }
            CostEstimate scanCost = costEstimator.costGroupScan(scan.getGroup().getGroup());
            CostEstimate flattenCost = costEstimator.costFlattenGroup(tableGroup, requiredTables);
            costEstimate = scanCost.sequence(flattenCost);
        }
    }

    protected class GroupLoopEstimator extends PlanEstimator {
        private GroupLoopScan scan;
        private TableGroupJoinTree tableGroup;
        private Set<TableSource> requiredTables;

        protected GroupLoopEstimator(GroupLoopScan scan,
                                     TableGroupJoinTree tableGroup,
                                     Set<TableSource> requiredTables) {
            super(null);
            this.scan = scan;
            this.tableGroup = tableGroup;
            this.requiredTables = requiredTables;
        }

        @Override
        protected void estimateCost() {
            costEstimate = costEstimator.costFlattenNested(tableGroup, scan.getOutsideTable(), scan.getInsideTable(), scan.isInsideParent(), requiredTables);
        }
    }

    protected class SelectEstimator extends PlanEstimator {
        private Collection<ConditionExpression> conditions;
        private SelectivityConditions selectivityConditions;

        protected SelectEstimator(PlanEstimator input,
                                  Collection<ConditionExpression> conditions,
                                  SelectivityConditions selectivityConditions) {
            super(input);
            this.conditions = conditions;
            this.selectivityConditions = selectivityConditions;
        }

        @Override
        protected void estimateCost() {
            double selectivity = costEstimator.conditionsSelectivity(selectivityConditions);
            // Need enough input rows before selection.
            input.setLimit(hasLimit() ? 
                           Math.round(limit / selectivity) : 
                           NO_LIMIT);
            CostEstimate inputCost = inputCostEstimate();
            CostEstimate selectCost = costEstimator.costSelect(conditions,
                                                               selectivity, 
                                                               inputCost.getRowCount());
            costEstimate = inputCost.sequence(selectCost);
        }
    }

    protected class SortEstimator extends PlanEstimator {
        private int nfields;

        protected SortEstimator(PlanEstimator input, int nfields) {
            super(input);
            this.nfields = nfields;
        }

        @Override
        protected void estimateCost() {
            input.setLimit(NO_LIMIT);
            CostEstimate inputCost = inputCostEstimate();
            CostEstimate sortCost;
            if (hasLimit() && 
                (limit <= INSERTION_SORT_MAX_LIMIT)) {
                sortCost = costEstimator.costSortWithLimit(inputCost.getRowCount(),
                                                           Math.min(limit, inputCost.getRowCount()),
                                                           nfields);
            }
            else {
                sortCost = costEstimator.costSort(inputCost.getRowCount());
            }
            costEstimate = inputCost.sequence(sortCost);
        }
    }

    protected CostEstimate getScanOnlyCost(IndexScan index) {
        CostEstimate result = index.getScanCostEstimate();
        if (result == null) {
            if (index instanceof SingleIndexScan) {
                SingleIndexScan singleIndex = (SingleIndexScan) index;
                if (singleIndex.getConditionRange() == null) {
                    result = costEstimator.costIndexScan(singleIndex.getIndex(),
                            singleIndex.getEqualityComparands(),
                            singleIndex.getLowComparand(),
                            singleIndex.isLowInclusive(),
                            singleIndex.getHighComparand(),
                            singleIndex.isHighInclusive());
                }
                else {
                    CostEstimate cost = null;
                    for (RangeSegment segment : singleIndex.getConditionRange().getSegments()) {
                        CostEstimate acost = costEstimator.costIndexScan(singleIndex.getIndex(),
                                singleIndex.getEqualityComparands(),
                                segment.getStart().getValueExpression(),
                                segment.getStart().isInclusive(),
                                segment.getEnd().getValueExpression(),
                                segment.getEnd().isInclusive());
                        if (cost == null)
                            cost = acost;
                        else
                            cost = cost.union(acost);
                    }
                    result = cost;
                }
            }
            else if (index instanceof MultiIndexIntersectScan) {
                MultiIndexIntersectScan multiIndex = (MultiIndexIntersectScan) index;
                result = costEstimator.costIndexIntersection(multiIndex, new IndexIntersectionCoster() {
                    @Override
                    public CostEstimate singleIndexScanCost(SingleIndexScan scan, CostEstimator costEstimator) {
                        return getScanOnlyCost(scan);
                    }
                });
            }
            else {
                throw new AkibanInternalException("unknown index type: " + index + "(" + index.getClass() + ")");
            }
            index.setScanCostEstimate(result);
        }
        return result;
    }

    protected double getScanSetupCost(IndexScan index) {
        if (index instanceof SingleIndexScan) {
            SingleIndexScan singleIndex = (SingleIndexScan)index;
            if (singleIndex.getConditionRange() == null) {
                return costEstimator.costIndexScan(singleIndex.getIndex(), 0).getCost();
            }
            else {
                return costEstimator.costIndexScan(singleIndex.getIndex(), 0).getCost() *
                    singleIndex.getConditionRange().getSegments().size();
            }
        }
        else if (index instanceof MultiIndexIntersectScan) {
            MultiIndexIntersectScan multiIndex = (MultiIndexIntersectScan)index;
            return getScanSetupCost(multiIndex.getOutputIndexScan()) +
                   getScanSetupCost(multiIndex.getSelectorIndexScan());
        }
        else {
            return 0.0;
        }
    }

    protected Map<UserTable,Long> groupScanTableCountsToLimit(Set<TableSource> requiredTables, long limit) {
        // Find the required table with the highest ordinal; we'll need limit of those
        // rows and however many of the others come before it.
        // TODO: Not as good if multiple branches are being flattened;
        // fewer are needed to start, but repeats come in via branch
        // lookup.
        TableNode lastRequired = null;
        for (TableSource table : requiredTables) {
            if ((lastRequired == null) ||
                (lastRequired.getOrdinal() < table.getTable().getOrdinal())) {
                lastRequired = table.getTable();
            }
        }
        long childCount = costEstimator.getTableRowCount(lastRequired.getTable());
        if (childCount <= limit)
            // Turns out we need the whole group before reaching the limit.
            return null;
        Map<UserTable,Long> tableCounts = new HashMap<UserTable,Long>();
        tableCounts.put(lastRequired.getTable(), limit);
        UserTable ancestor = lastRequired.getTable();
        while (true) {
            ancestor = ancestor.parentTable();
            if (ancestor == null) break;
            long ancestorCount = costEstimator.getTableRowCount(ancestor);
            tableCounts.put(ancestor, 
                            // Ceiling number of ancestor needed to get limit of child.
                            (limit * ancestorCount + (childCount - 1)) / childCount);
        }
        Group group = lastRequired.getTable().getGroup();
        Map<UserTable,Long> moreCounts = new HashMap<UserTable,Long>();
        for (UserTable table : lastRequired.getTable().getAIS().getUserTables().values()) {
            if (table.getGroup() == group) {
                UserTable commonAncestor = table;
                while (!tableCounts.containsKey(commonAncestor)) {
                    commonAncestor = commonAncestor.parentTable();
                }
                if (commonAncestor == table) continue;
                long ancestorCount = tableCounts.get(commonAncestor);
                if (table.rowDef().getOrdinal() > lastRequired.getOrdinal())
                    // A table that isn't required; number skipped
                    // depends on relative position.
                    ancestorCount--;
                moreCounts.put(table,
                               simpleRound(costEstimator.getTableRowCount(table) * ancestorCount,
                                           costEstimator.getTableRowCount(commonAncestor)));
            }
        }
        tableCounts.putAll(moreCounts);
        return tableCounts;
    }

}
