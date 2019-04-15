package grakn.core.graql.gremlin.fragment;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import grakn.core.graql.gremlin.spanningtree.graph.DirectedEdge;
import grakn.core.graql.gremlin.spanningtree.graph.Node;
import grakn.core.graql.gremlin.spanningtree.graph.NodeId;
import grakn.core.graql.gremlin.spanningtree.util.Weighted;
import grakn.core.server.kb.Schema;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.statement.Variable;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Pop;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import static grakn.core.graql.gremlin.fragment.AbstractRolePlayerFragment.RELATION_DIRECTION;
import static grakn.core.graql.gremlin.fragment.AbstractRolePlayerFragment.RELATION_EDGE;
import static grakn.core.server.kb.Schema.EdgeLabel.ATTRIBUTE;
import static grakn.core.server.kb.Schema.EdgeProperty.RELATION_ROLE_OWNER_LABEL_ID;
import static grakn.core.server.kb.Schema.EdgeProperty.RELATION_ROLE_VALUE_LABEL_ID;
import static grakn.core.server.kb.Schema.EdgeProperty.RELATION_TYPE_LABEL_ID;

@AutoValue
public abstract class InAttributeFragment extends Fragment {

    @Override
    public abstract Variable end();

    @Override
    GraphTraversal<Vertex, ? extends Element> applyTraversalInner(GraphTraversal<Vertex, ? extends Element> traversal, TransactionOLTP tx, Collection<Variable> vars) {
        /*
        return Fragments.union(Fragments.isVertex(traversal), ImmutableSet.of(
                edgeRelationTraversal(tx, Direction.OUT, RELATION_ROLE_OWNER_LABEL_ID, vars),
                edgeRelationTraversal(tx, Direction.IN, RELATION_ROLE_VALUE_LABEL_ID, vars)
        ));
        */
        return null;
    }

    /*
    private GraphTraversal<Vertex, Edge> edgeRelationTraversal(
            TransactionOLTP tx, Direction direction, Schema.EdgeProperty roleProperty, Collection<Variable> vars) {

        GraphTraversal<Vertex, Edge> edgeTraversal = __.toE(direction, Schema.EdgeLabel.ATTRIBUTE.getLabel());

        // Identify the relation - role-player pair by combining the relation edge and direction into a map
        edgeTraversal.as(RELATION_EDGE.symbol()).constant(direction).as(RELATION_DIRECTION.symbol());
        edgeTraversal.select(Pop.last, RELATION_EDGE.symbol(), RELATION_DIRECTION.symbol()).as(edge().symbol()).select(RELATION_EDGE.symbol());

        // Filter by any provided type labels
        applyLabelsToTraversal(edgeTraversal, roleProperty, roleLabels(), tx);
        applyLabelsToTraversal(edgeTraversal, RELATION_TYPE_LABEL_ID, relationTypeLabels(), tx);

        return edgeTraversal;
    }
    */

    @Override
    public String name() { return "<-[attribute]-"; }

    @Override
    public double internalFragmentCost() {
        return COST_INSTANCES_PER_TYPE;
    }

    @Override
    public Set<Node> getNodes() {
        Node start = new Node(NodeId.of(NodeId.NodeType.VAR, start()));
        Node end = new Node(NodeId.of(NodeId.NodeType.VAR, end()));
        Node middle = new Node(NodeId.of(NodeId.NodeType.ATTRIBUTE, new HashSet<>(Arrays.asList(start(), end()))));
        middle.setInvalidStartingPoint();
        return Sets.newHashSet(start, end, middle);
    }

    @Override
    public Set<Weighted<DirectedEdge>> directedEdges(Map<NodeId, Node> nodes,
                                                     Map<Node, Map<Node, Fragment>> edges) {
        return directedEdges(NodeId.NodeType.ATTRIBUTE, nodes, edges);
    }
}
