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
import grakn.core.graql.gremlin.spanningtree.graph.InstanceNode;
import grakn.core.graql.gremlin.spanningtree.graph.Node;
import grakn.core.graql.gremlin.spanningtree.graph.NodeId;
import grakn.core.graql.gremlin.spanningtree.graph.SchemaNode;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.statement.Variable;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

import static grakn.core.server.kb.Schema.BaseType.RELATION_TYPE;
import static grakn.core.server.kb.Schema.EdgeLabel.ATTRIBUTE;
import static grakn.core.server.kb.Schema.EdgeLabel.ISA;
import static grakn.core.server.kb.Schema.EdgeLabel.PLAYS;
import static grakn.core.server.kb.Schema.EdgeLabel.RELATES;
import static grakn.core.server.kb.Schema.EdgeLabel.SHARD;
import static grakn.core.server.kb.Schema.EdgeProperty.RELATION_TYPE_LABEL_ID;
import static grakn.core.server.kb.Schema.VertexProperty.IS_IMPLICIT;
import static grakn.core.server.kb.Schema.VertexProperty.LABEL_ID;

/**
 * A fragment representing traversing an isa edge from type to instance.
 *
 */

@AutoValue
public abstract class InIsaFragment extends EdgeFragment {

    @Override
    public abstract Variable end();

    abstract boolean mayHaveEdgeInstances();

    @Override
    public GraphTraversal<Vertex, ? extends Element> applyTraversalInner(
            GraphTraversal<Vertex, ? extends Element> traversal, TransactionOLTP tx, Collection<Variable> vars) {

        GraphTraversal<Vertex, Vertex> vertexTraversal = Fragments.isVertex(traversal);

        if (mayHaveEdgeInstances()) {
            GraphTraversal<Vertex, Vertex> isImplicitRelationType =
                    __.<Vertex>hasLabel(RELATION_TYPE.name()).has(IS_IMPLICIT.name(), true);

            GraphTraversal<Vertex, Element> toVertexAndEdgeInstances = Fragments.union(ImmutableSet.of(
                    toVertexInstances(__.identity()),
                    toEdgeInstances()
            ));

            return choose(vertexTraversal, isImplicitRelationType,
                    toVertexAndEdgeInstances,
                    toVertexInstances(__.identity())
            );
        } else {
            return toVertexInstances(vertexTraversal);
        }
    }

    /**
     * A type-safe way to do `a.choose(pred, whenTrue, whenFalse)`, as `choose(a, pred, whenTrue, whenFalse)`.
     * This is because the default signature is too restrictive
     */
    private <S, E1, E2> GraphTraversal<S, E2> choose(
            GraphTraversal<S, E1> traversal, GraphTraversal<E1, ?> traversalPredicate,
            GraphTraversal<E1, ? extends E2> trueChoice, GraphTraversal<E1, ? extends E2> falseChoice) {

        // This is safe. The generics for `GraphTraversal#choose` are more restrictive than necessary
        //noinspection unchecked
        return traversal.choose(
                traversalPredicate, (GraphTraversal<S, E2>) trueChoice, (GraphTraversal<S, E2>) falseChoice);
    }

    private <S> GraphTraversal<S, Vertex> toVertexInstances(GraphTraversal<S, Vertex> traversal) {
        return traversal.in(SHARD.getLabel()).in(ISA.getLabel());
    }

    private GraphTraversal<Vertex, Edge> toEdgeInstances() {
        Variable type = new Variable();
        Variable labelId = new Variable();

        // There is no fast way to retrieve all edge instances, because edges cannot be globally indexed.
        // This is a best-effort, that uses the schema to limit the search space...

        // First retrieve the type ID
        GraphTraversal<Vertex, Vertex> traversal =
                __.<Vertex>as(type.symbol()).values(LABEL_ID.name()).as(labelId.symbol()).select(type.symbol());

        // Next, navigate the schema to all possible types whose instances can be in this relation
        traversal = Fragments.inSubs(traversal.out(RELATES.getLabel()).in(PLAYS.getLabel()));

        // Navigate to all (vertex) instances of those types
        // (we do not need to navigate to edge instances, because edge instances cannot be role-players)
        traversal = toVertexInstances(traversal);

        // Finally, navigate to all relation edges with the correct type attached to these instances
        return traversal.outE(ATTRIBUTE.getLabel())
                .has(RELATION_TYPE_LABEL_ID.name(), __.where(P.eq(labelId.symbol())));
    }

    @Override
    public String name() {
        return String.format("<-[isa:%s]-", mayHaveEdgeInstances() ? "with-edges" : "");
    }

    @Override
    public double internalFragmentCost() {
        return COST_INSTANCES_PER_TYPE;
    }

    @Override
    protected Node startNode() {
        return new SchemaNode(NodeId.of(NodeId.Type.VAR, start()));
    }

    @Override
    protected Node endNode() {
        return new InstanceNode(NodeId.of(NodeId.Type.VAR, end()));
    }

    @Override
    protected NodeId getMiddleNodeId() {
        return NodeId.of(NodeId.Type.ISA, new HashSet<>(Arrays.asList(start(), end())));
    }
}
