package grakn.core.graql.gremlin.fragment;

import com.google.auto.value.AutoValue;
import grakn.core.graql.gremlin.spanningtree.graph.DirectedEdge;
import grakn.core.graql.gremlin.spanningtree.graph.Node;
import grakn.core.graql.gremlin.spanningtree.graph.NodeId;
import grakn.core.graql.gremlin.spanningtree.util.Weighted;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.statement.Variable;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import static grakn.core.server.kb.Schema.EdgeLabel.ATTRIBUTE;

@AutoValue
public abstract class OutAttributeFragment extends Fragment {

    @Override
    public abstract Variable end();

    @Override
    GraphTraversal<Vertex, ? extends Element> applyTraversalInner(GraphTraversal<Vertex, ? extends Element> traversal, TransactionOLTP tx, Collection<Variable> vars) {
        //return traversal.out(ATTRIBUTE.getLabel());
        return Fragments.isVertex(traversal).out(ATTRIBUTE.getLabel());
    }

    @Override
    public String name() { return "-[attribute]->"; }

    @Override
    public double internalFragmentCost() { return COST_SAME_AS_PREVIOUS; }

    @Override
    public Set<Node> getNodes() {
        Node start = new Node(NodeId.of(NodeId.NodeType.VAR, start()));
        Node end = new Node(NodeId.of(NodeId.NodeType.VAR, end()));
        Node middle = new Node(NodeId.of(NodeId.NodeType.ATTRIBUTE, new HashSet<>(Arrays.asList(start(), end()))));
        middle.setInvalidStartingPoint();
        return new HashSet<>(Arrays.asList(start, end, middle));
    }

    @Override
    public Set<Weighted<DirectedEdge>> directedEdges(Map<NodeId, Node> nodes,
                                                     Map<Node, Map<Node, Fragment>> edges) {
        return directedEdges(NodeId.NodeType.ATTRIBUTE, nodes, edges);
    }
}
