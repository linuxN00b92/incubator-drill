/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.common.logical.data.visitors;

import org.apache.drill.common.logical.data.*;


public abstract class AbstractLogicalVisitor<T, X, E extends Throwable> implements LogicalVisitor<T, X, E> {

    public T visitOp(LogicalOperator op, X value) throws E{
        throw new UnsupportedOperationException(String.format(
                "The LogicalVisitor of type %s does not currently support visiting the PhysicalOperator type %s.", this
                .getClass().getCanonicalName(), op.getClass().getCanonicalName()));
    }

    @Override
    public T visitScan(Scan scan, X value) throws E {
        return visitOp(scan, value);
    }

    @Override
    public T visitStore(Store store, X value) throws E {
        return visitOp(store, value);
    }

    @Override
    public T visitFilter(Filter filter, X value) throws E {
        return visitOp(filter, value);
    }

    @Override
    public T visitFlatten(Flatten flatten, X value) throws E {
        return visitOp(flatten, value);
    }

    @Override
    public T visitProject(Project project, X value) throws E {
        return visitOp(project, value);
    }

    @Override
    public T visitOrder(Order order, X value) throws E {
        return visitOp(order, value);
    }

    @Override
    public T visitJoin(Join join, X value) throws E {
        return visitOp(join, value);
    }

    @Override
    public T visitLimit(Limit limit, X value) throws E {
        return visitOp(limit, value);
    }

    @Override
    public T visitRunningAggregate(RunningAggregate runningAggregate, X value) throws E {
        return visitOp(runningAggregate, value);
    }

    @Override
    public T visitSegment(Segment segment, X value) throws E {
        return visitOp(segment, value);
    }

    @Override
    public T visitSequence(Sequence sequence, X value) throws E {
        return visitOp(sequence, value);
    }

    @Override
    public T visitTransform(Transform transform, X value) throws E {
        return visitOp(transform, value);
    }

    @Override
    public T visitUnion(Union union, X value) throws E {
        return visitOp(union, value);
    }

    @Override
    public T visitCollapsingAggregate(CollapsingAggregate collapsingAggregate, X value) throws E {
        return visitOp(collapsingAggregate, value);
    }

    @Override
    public T visitWindowFrame(WindowFrame windowFrame, X value) throws E {
        return visitOp(windowFrame, value);
    }

    @Override
    public T visitConstant(Constant constant, X value) throws E {
       return visitOp(constant, value);
    }

    public T visitOptionSetter(OptionSetter optionSetter, X value) throws E {
      return visitOp(optionSetter, value);
    }
    
    
}
