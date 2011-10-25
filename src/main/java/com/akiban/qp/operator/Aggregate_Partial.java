/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.qp.operator;

import com.akiban.qp.row.Row;
import com.akiban.qp.row.ValuesHolderRow;
import com.akiban.qp.rowtype.AggregatedRowType;
import com.akiban.qp.rowtype.RowType;
import com.akiban.server.aggregation.Aggregator;
import com.akiban.server.aggregation.AggregatorFactory;
import com.akiban.server.types.ValueSource;
import com.akiban.server.types.util.ValueHolder;
import com.akiban.util.ArgumentValidation;
import com.akiban.util.ShareHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

final class Aggregate_Partial extends Operator
{

    // Operator interface

    @Override
    protected Cursor cursor(StoreAdapter adapter) {
        List<Aggregator> aggregators = new ArrayList<Aggregator>();
        for (AggregatorFactory factory : aggregatorFactories) {
            aggregators.add(factory.get());
        }
        return new AggregateCursor(
                adapter,
                inputOperator.cursor(adapter),
                inputOperator.rowType(),
                aggregators,
                inputsIndex,
                outputType
        );
    }

    @Override
    public void findDerivedTypes(Set<RowType> derivedTypes) {
        inputOperator.findDerivedTypes(derivedTypes);
        derivedTypes.add(outputType);
    }

    @Override
    public List<Operator> getInputOperators() {
        return Collections.singletonList(inputOperator);
    }

    @Override
    public RowType rowType() {
        return outputType;
    }

    // AggregationOperator interface

    public Aggregate_Partial(Operator inputOperator, int inputsIndex, List<AggregatorFactory> aggregatorFactories) {
        this(
                inputOperator,
                inputsIndex,
                aggregatorFactories,
                inputOperator.rowType().schema().newAggregateType(inputOperator.rowType())
        );
    }

    // Object interface

    @Override
    public String toString() {
        if (inputsIndex == 0) {
            return String.format("Aggregation(without GROUP BY: %s)", aggregatorFactories);
        }
        if (inputsIndex == 1) {
            return String.format("Aggregation(GROUP BY 1 field, then: %s)", aggregatorFactories);
        }
        return String.format("Aggregation(GROUP BY %d fields, then: %s)", inputsIndex, aggregatorFactories);
    }


    // package-private (for testing)

    Aggregate_Partial(Operator inputOperator, int inputsIndex,
                      List<AggregatorFactory> aggregatorFactories, AggregatedRowType outputType) {
        this.inputOperator = inputOperator;
        this.inputsIndex = inputsIndex;
        this.aggregatorFactories = new ArrayList<AggregatorFactory>(aggregatorFactories);
        this.outputType = outputType;
        validate();
    }

    // private methods

    private void validate() {
        ArgumentValidation.isBetween("inputsIndex", 0, inputsIndex, inputOperator.rowType().nFields()+1);
        if (inputsIndex + aggregatorFactories.size() != inputOperator.rowType().nFields()) {
            throw new IllegalArgumentException(
                    String.format("inputsIndex(=%d) + aggregatorNames.size(=%d) != inputRowType.nFields(=%d)",
                            inputsIndex, aggregatorFactories.size(), inputOperator.rowType().nFields()
            ));
        }
        if (outputType == null)
            throw new NullPointerException();
    }

    // object state

    private final Operator inputOperator;
    private final AggregatedRowType outputType;
    private final int inputsIndex;
    private final List<AggregatorFactory> aggregatorFactories;

    // nested classes

    private static class AggregateCursor implements Cursor {

        // Cursor interface

        @Override
        public void open(Bindings bindings) {
            if (cursorState != CursorState.CLOSED)
                throw new IllegalStateException("can't open cursor: already open");
            inputCursor.open(bindings);
            cursorState = CursorState.OPENING;
        }

        @Override
        public Row next() {
            adapter.checkQueryCancelation();
            if (cursorState == CursorState.CLOSED)
                throw new IllegalStateException("cursor not open");
            if (cursorState == CursorState.CLOSING) {
                close();
                return null;
            }

            assert cursorState == CursorState.OPENING || cursorState == CursorState.RUNNING : cursorState;
            while (true) {
                Row input = nextInput();
                if (input == null) {
                    if (everSawInput) {
                        cursorState = CursorState.CLOSING;
                        return createOutput();
                    }
                    else if (noGroupBy()) {
                        cursorState = CursorState.CLOSING;
                        return createEmptyOutput();
                    }
                    else {
                        close();
                        return null;
                    }
                }
                if (!input.rowType().equals(inputRowType)) {
                    return input; // pass through
                }
                everSawInput = true;
                if (outputNeeded(input)) {
                    saveInput(input); // save this input for the next time this method is invoked
                    return createOutput();
                }
                aggregate(input);
            }
        }

        @Override
        public void close() {
            if (cursorState != CursorState.CLOSED) {
                holder.release();
                inputCursor.close();
                cursorState = CursorState.CLOSED;
            }
        }

        // for use in this class

        private void aggregate(Row input) {
            for (int i=0; i < aggregators.size(); ++i) {
                Aggregator aggregator = aggregators.get(i);
                int inputIndex = i + inputsIndex;
                aggregator.input(input.eval(inputIndex));
            }
        }

        private Row createOutput() {
            ValuesHolderRow outputRow = unsharedOutputRow();
            for(int i = 0; i < inputsIndex; ++i) {
                ValueHolder holder = outputRow.holderAt(i);
                ValueSource key = keyValues.get(i);
                holder.copyFrom(key);
            }
            for (int i = inputsIndex; i < inputRowType.nFields(); ++i) {
                ValueHolder holder = outputRow.holderAt(i);
                int aggregatorIndex = i - inputsIndex;
                Aggregator aggregator = aggregators.get(aggregatorIndex);
                holder.expectType(aggregator.outputType());
                aggregator.output(holder);
            }
            return outputRow;
        }

        private Row createEmptyOutput() {
            assert noGroupBy() : "shouldn't be creating null output row when I have a grouping";
            ValuesHolderRow outputRow = unsharedOutputRow();
            for (int i = 0; i < outputRow.rowType().nFields(); ++i) {
                outputRow.holderAt(i).copyFrom(aggregators.get(i).emptyValue());
            }
            return outputRow;
        }

        private boolean noGroupBy() {
            return inputsIndex == 0;
        }

        private boolean outputNeeded(Row givenInput) {
            if (noGroupBy())
                return false;   // no GROUP BYs, so aggregate until givenInput is null

            // check for any changes to keys
            // Coming into this code, we're either RUNNING (within a GROUP BY run) or OPENING (about to start
            // a new run).
            if (cursorState == CursorState.OPENING) {
                // Copy over this row's values; switch mode to RUNNING; return false
                for (int i = 0; i < keyValues.size(); ++i) {
                    keyValues.get(i).copyFrom(givenInput.eval(i));
                }
                cursorState = CursorState.RUNNING;
                return false;
            }
            else {
                assert cursorState == CursorState.RUNNING : cursorState;
                // If any keys are different, switch mode to OPENING and return true; else return false.
                for (int i = 0; i < keyValues.size(); ++i) {
                    ValueHolder key = keyValues.get(i);
                    scratchValueHolder.copyFrom(givenInput.eval(i));
                    if (!scratchValueHolder.equals(key)) {
                        cursorState = CursorState.OPENING;
                        return true;
                    }
                }
                return false;
            }
        }

        private Row nextInput() {
            final Row result;
            if (holder.isHolding()) {
                result = holder.get();
                holder.release();
            }
            else {
                result = inputCursor.next();
            }
            return result;
        }

        private void saveInput(Row input) {
            assert holder.isEmpty() : holder;
            assert cursorState == CursorState.OPENING : cursorState;
            holder.hold(input);
        }

        private ValuesHolderRow unsharedOutputRow() {
            return new ValuesHolderRow(outputRowType); // TODO row sharing, etc
        }

        // AggregateCursor interface

        private AggregateCursor(StoreAdapter adapter,
                                Cursor inputCursor,
                                RowType inputRowType,
                                List<Aggregator> aggregators,
                                int inputsIndex,
                                AggregatedRowType outputRowType) {
            this.adapter = adapter;
            this.inputCursor = inputCursor;
            this.inputRowType = inputRowType;
            this.aggregators = aggregators;
            this.inputsIndex = inputsIndex;
            this.outputRowType = outputRowType;
            keyValues = new ArrayList<ValueHolder>();
            for (int i = 0; i < inputsIndex; ++i) {
                keyValues.add(new ValueHolder());
            }
        }


        // object state

        private final StoreAdapter adapter;
        private final Cursor inputCursor;
        private final RowType inputRowType;
        private final List<Aggregator> aggregators;
        private final int inputsIndex;
        private final AggregatedRowType outputRowType;
        private final List<ValueHolder> keyValues;
        private final ValueHolder scratchValueHolder = new ValueHolder();
        private final ShareHolder<Row> holder = new ShareHolder<Row>();
        private CursorState cursorState = CursorState.CLOSED;
        private boolean everSawInput = false;
    }

    private enum CursorState {
        /**
         * Freshly opened, or about to start a new run of group-bys
         */
        OPENING,
        /**
         * Within a run of group-bys
         */
        RUNNING,
        /**
         * The last row we returned (or the row we're about to return) is the last row; the next row will be
         * null, and will set the state to closing.
         */
        CLOSING,
        /**
         * The cursor is closed.
         */
        CLOSED
    }

}
