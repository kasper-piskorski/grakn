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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import grakn.core.concept.Label;
import grakn.core.concept.type.Role;
import grakn.core.graql.gremlin.spanningtree.graph.DirectedEdge;
import grakn.core.graql.gremlin.spanningtree.graph.Node;
import grakn.core.graql.gremlin.spanningtree.graph.NodeId;
import grakn.core.graql.gremlin.spanningtree.util.Weighted;
import grakn.core.server.kb.Schema;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.statement.Variable;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Edge;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static grakn.core.graql.gremlin.fragment.Fragments.displayOptionalTypeLabels;
import static grakn.core.graql.gremlin.spanningtree.util.Weighted.weighted;
import static java.util.stream.Collectors.toSet;

/**
 * Abstract class for the fragments that traverse {@link Schema.EdgeLabel#ROLE_PLAYER} edges: {@link InRolePlayerFragment} and
 * {@link OutRolePlayerFragment}.
 *
 */
public abstract class AbstractRolePlayerFragment extends Fragment {

    static final Variable RELATION_EDGE = reservedVar("RELATION_EDGE");
    static final Variable RELATION_DIRECTION = reservedVar("RELATION_DIRECTION");

    private static Variable reservedVar(String value) {
        return new Variable(value, Variable.Type.Reserved);
    }

    @Override
    public abstract Variable end();

    abstract Variable edge();

    abstract @Nullable
    Variable role();

    abstract @Nullable ImmutableSet<Label> roleLabels();

    abstract @Nullable ImmutableSet<Label> relationTypeLabels();

    final String innerName() {
        Variable role = role();
        String roleString = role != null ? " role:" + role.symbol() : "";
        String rels = displayOptionalTypeLabels("rels", relationTypeLabels());
        String roles = displayOptionalTypeLabels("roles", roleLabels());
        return "[" + Schema.EdgeLabel.ROLE_PLAYER.getLabel() + ":" + edge().symbol() + roleString + rels + roles + "]";
    }

    @Override
    final ImmutableSet<Variable> otherVars() {
        ImmutableSet.Builder<Variable> builder = ImmutableSet.<Variable>builder().add(edge());
        Variable role = role();
        if (role != null) builder.add(role);
        return builder.build();
    }

    @Override
    public Set<Node> getNodes() {
        Node start = new Node(NodeId.of(NodeId.NodeType.VAR, start()));
        Node end = new Node(NodeId.of(NodeId.NodeType.VAR, end()));
        Node middle = new Node(NodeId.of(NodeId.NodeType.VAR, edge()));
        middle.setInvalidStartingPoint();
        return new HashSet<>(Arrays.asList(start, end, middle));
    }

    @Override
    public final Set<Weighted<DirectedEdge>> directedEdges(Map<NodeId, Node> nodes,
                                                           Map<Node, Map<Node, Fragment>> edges) {

        // this is a somewhat special case, where the middle node being converted to a vertex
        // may be addressed by a variable

        Node start = nodes.get(NodeId.of(NodeId.NodeType.VAR, start()));
        Node end = nodes.get(NodeId.of(NodeId.NodeType.VAR, end()));
        Node middle = nodes.get(NodeId.of(NodeId.NodeType.VAR, edge()));
        middle.setInvalidStartingPoint();

        if (!edges.containsKey(middle)) {
            edges.put(middle, new HashMap<>());
        }
        edges.get(middle).put(start, this);

        return Sets.newHashSet(
                weighted(DirectedEdge.from(start).to(middle), -fragmentCost()),
                weighted(DirectedEdge.from(middle).to(end), 0));
    }
}
