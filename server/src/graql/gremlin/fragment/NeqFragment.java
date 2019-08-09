/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2019 Grakn Labs Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package grakn.core.graql.gremlin.fragment;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.statement.Variable;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.util.Collection;

/**
 * A fragment representing a negation.
 * Used for concept comparison, not attribute values
 *
 */
@AutoValue
public abstract class NeqFragment extends Fragment {

    public abstract Variable other();

    @Override
    public GraphTraversal<Vertex, ? extends Element> applyTraversalInner(
            GraphTraversal<Vertex, ? extends Element> traversal, TransactionOLTP tx, Collection<Variable> vars) {
        return traversal.where(P.neq(other().symbol()));
    }

    @Override
    public String name() {
        return "[neq:" + other().symbol() + "]";
    }

    @Override
    public double internalFragmentCost() {
        // This is arbitrary - we imagine about half the results are filtered out
        return COST_NODE_NEQ;
    }

    @Override
    public Fragment getInverse() {
        return Fragments.neq(varProperty(), other(), start());
    }

    @Override
    public ImmutableSet<Variable> dependencies() {
        return ImmutableSet.of(other());
    }
}
