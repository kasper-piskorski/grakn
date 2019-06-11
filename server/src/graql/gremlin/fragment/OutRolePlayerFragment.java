/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2018 Grakn Labs Ltd
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
import grakn.core.server.kb.Schema;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.statement.Variable;
import org.apache.tinkerpop.gremlin.process.traversal.Pop;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.util.Collection;

import static grakn.core.server.kb.Schema.EdgeLabel.ROLE_PLAYER;
import static grakn.core.server.kb.Schema.EdgeProperty.RELATION_ROLE_OWNER_LABEL_ID;
import static grakn.core.server.kb.Schema.EdgeProperty.RELATION_ROLE_VALUE_LABEL_ID;
import static grakn.core.server.kb.Schema.EdgeProperty.RELATION_TYPE_LABEL_ID;
import static grakn.core.server.kb.Schema.EdgeProperty.ROLE_LABEL_ID;

/**
 * A fragment representing traversing a {@link Schema.EdgeLabel#ROLE_PLAYER} edge from the relation to the
 * role-player.
 * <p>
 * Part of a EquivalentFragmentSet, along with InRolePlayerFragment.
 *
 */
@AutoValue
public abstract class OutRolePlayerFragment extends AbstractRolePlayerFragment {

    @Override
    public GraphTraversal<Vertex, ? extends Element> applyTraversalInner(
            GraphTraversal<Vertex, ? extends Element> traversal, TransactionOLTP tx, Collection<Variable> vars) {

        return Fragments.union(traversal, ImmutableSet.of(
                reifiedRelationTraversal(tx, vars),
                edgeRelationTraversal(tx, Direction.OUT, RELATION_ROLE_OWNER_LABEL_ID, vars),
                edgeRelationTraversal(tx, Direction.IN, RELATION_ROLE_VALUE_LABEL_ID, vars)
        ));
    }

    private GraphTraversal<Element, Vertex> reifiedRelationTraversal(TransactionOLTP tx, Collection<Variable> vars) {
        GraphTraversal<Element, Vertex> traversal = Fragments.isVertex(__.identity());

        GraphTraversal<Element, Edge> edgeTraversal = traversal.outE(ROLE_PLAYER.getLabel()).as(edge().symbol());

        // Filter by any provided type labels
        applyLabelsToTraversal(edgeTraversal, ROLE_LABEL_ID, roleLabels(), tx);
        applyLabelsToTraversal(edgeTraversal, RELATION_TYPE_LABEL_ID, relationTypeLabels(), tx);

        traverseToRole(edgeTraversal, role(), ROLE_LABEL_ID, vars);

        return edgeTraversal.inV();
    }

    private GraphTraversal<Element, Vertex> edgeRelationTraversal(
            TransactionOLTP tx, Direction direction, Schema.EdgeProperty roleProperty, Collection<Variable> vars) {
        GraphTraversal<Element, Edge> edgeTraversal = Fragments.isEdge(__.identity());

        // Filter by any provided type labels
        applyLabelsToTraversal(edgeTraversal, roleProperty, roleLabels(), tx);
        applyLabelsToTraversal(edgeTraversal, RELATION_TYPE_LABEL_ID, relationTypeLabels(), tx);

        traverseToRole(edgeTraversal, role(), roleProperty, vars);

        // Identify the relation - role-player pair by combining the relation edge and direction into a map
        edgeTraversal.as(RELATION_EDGE.symbol()).constant(direction).as(RELATION_DIRECTION.symbol());
        edgeTraversal.select(Pop.last, RELATION_EDGE.symbol(), RELATION_DIRECTION.symbol()).as(edge().symbol()).select(RELATION_EDGE.symbol());

        return edgeTraversal.toV(direction);
    }

    @Override
    public String name() {
        return "-" + innerName() + "->";
    }

    @Override
    public double internalFragmentCost() {
        return roleLabels() != null ? COST_ROLE_PLAYERS_PER_ROLE : COST_ROLE_PLAYERS_PER_RELATION;
    }

    @Override
    public boolean canOperateOnEdges() {
        return true;
    }
}
