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

package com.akiban.sql.optimizer.rule;

import org.slf4j.Logger;

import com.akiban.server.service.EventTypes;

import com.akiban.sql.optimizer.plan.PlanContext;

public abstract class BaseRule
{
    private final String traceName;

    protected BaseRule() {
        traceName = EventTypes.OPTIMIZE + ": " + getName();
    }

    public String getTraceName() {
        return traceName;
    }

    public String getName() {
        return getClass().getSimpleName();
    }

    protected abstract Logger getLogger();

    public abstract void apply(PlanContext plan);
}
