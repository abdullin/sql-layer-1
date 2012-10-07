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

package com.akiban.sql.server;

import com.akiban.sql.parser.ConstantNode;
import com.akiban.sql.parser.JavaValueNode;
import com.akiban.sql.parser.ParameterNode;
import com.akiban.sql.parser.SQLToJavaValueNode;
import com.akiban.sql.parser.StaticMethodCallNode;
import com.akiban.sql.parser.ValueNode;

import com.akiban.ais.model.Routine;
import com.akiban.ais.model.TableName;
import com.akiban.server.error.UnsupportedSQLException;
import com.akiban.server.types.FromObjectValueSource;
import com.akiban.server.types3.pvalue.PValueSources;

import java.util.Arrays;

public class ServerRoutineInvocation
{
    private final Routine routine;
    private final Object[] constantArgs;
    private final int[] parameterArgs;

    private ServerRoutineInvocation(Routine routine,
                                    Object[] constantArgs,
                                    int[] parameterArgs) {
        this.routine = routine;
        this.constantArgs = constantArgs;
        this.parameterArgs = parameterArgs;
    }

    public static ServerRoutineInvocation of(ServerSession server,
                                             StaticMethodCallNode methodCall) {
        String schemaName, routineName;
        if (methodCall.getProcedureName() == null) {
            schemaName = null;
            routineName = methodCall.getMethodName();
        }
        else {
            schemaName = methodCall.getProcedureName().getSchemaName();
            routineName = methodCall.getProcedureName().getTableName();
        }
        if (schemaName == null) {
            schemaName = server.getDefaultSchemaName();
        }
        Routine routine = server.getAIS().getRoutine(schemaName, routineName);
        if (routine == null) return null;
        Object[] constantArgs = null;
        int[] parameterArgs = null;
        JavaValueNode[] margs = methodCall.getMethodParameters();
        if (margs != null) {
            constantArgs = new Object[margs.length];
            parameterArgs = new int[margs.length];
            Arrays.fill(parameterArgs, -1);
            for (int i = 0; i < margs.length; i++) {
                JavaValueNode marg = margs[i];
                if (marg instanceof SQLToJavaValueNode) {
                    ValueNode sqlArg = ((SQLToJavaValueNode)marg).getSQLValueNode();
                    if (sqlArg instanceof ConstantNode) {
                        constantArgs[i] = ((ConstantNode)sqlArg).getValue();
                        continue;
                    }
                    else if (sqlArg instanceof ParameterNode) {
                        parameterArgs[i] = ((ParameterNode)sqlArg).getParameterNumber();
                        continue;
                    }
                }
                throw new UnsupportedSQLException("CALL parameter must be constant",
                                                  marg);
            }
        }
        return new ServerRoutineInvocation(routine, constantArgs, parameterArgs);
    }

    public Routine getRoutine() {
        return routine;
    }

    public Routine.CallingConvention getCallingConvention() {
        return routine.getCallingConvention();
    }

    public TableName getRoutineName() {
        return routine.getName();
    }

    public boolean hasParameters() {
        for (int index : parameterArgs) {
            if (index != -1) {
                return true;
            }
        }
        return false;
    }

    public boolean parametersInOrder() {
        for (int i = 0; i < parameterArgs.length; i++) {
            if (i != parameterArgs[i]) {
                return false;
            }
        }
        return true;
    }

    public void copyParameters(ServerQueryContext source, ServerQueryContext target,
                               boolean usePVals) {
        if (usePVals) {
            for (int i = 0; i < parameterArgs.length; i++) {
                if (parameterArgs[i] < 0) {
                    target.setPValue(i, PValueSources.fromObject(constantArgs[i], null).value());
                }
                else {
                    target.setPValue(i, source.getPValue(parameterArgs[i]));
                }
            }
        }
        else {
            FromObjectValueSource value = new FromObjectValueSource();
            for (int i = 0; i < parameterArgs.length; i++) {
                if (parameterArgs[i] < 0) {
                    value.setReflectively(constantArgs[i]);
                    target.setValue(i, value);
                }
                else {
                    target.setValue(i, source.getValue(parameterArgs[i]));
                }
            }
        }
    }

}
