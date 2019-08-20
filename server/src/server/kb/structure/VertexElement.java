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

package grakn.core.server.kb.structure;

import grakn.core.server.kb.Schema;
import grakn.core.server.session.TransactionOLTP;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Represent a Vertex in a TransactionOLTP
 * Wraps a tinkerpop Vertex constraining it to the Grakn Object Model.
 * This is used to wrap common functionality between exposed Concept and unexposed
 * internal vertices.
 */
public class VertexElement extends AbstractElement<Vertex, Schema.VertexProperty> {

    public VertexElement(TransactionOLTP graknTx, Vertex element) {
        super(graknTx, element);
    }

    /**
     * @param direction The direction of the edges to retrieve
     * @param label     The type of the edges to retrieve
     * @return A collection of edges from this concept in a particular direction of a specific type
     */
    public Stream<EdgeElement> getEdgesOfType(Direction direction, Schema.EdgeLabel label) {
        Iterable<Edge> iterable = () -> element().edges(direction, label.getLabel());
        return StreamSupport.stream(iterable.spliterator(), false)
                .filter(edge -> tx().isValidElement(edge)) // filter out deleted but cached available edges
                .map(edge -> tx().factory().buildEdgeElement(edge));
    }

    /**
     * @param to   the target VertexElement
     * @param type the type of the edge to create
     * @return The edge created
     */
    public EdgeElement addEdge(VertexElement to, Schema.EdgeLabel type) {
        return tx().factory().buildEdgeElement(element().addEdge(type.getLabel(), to.element()));
    }

    /**
     * @param to   the target VertexElement
     * @param type the type of the edge to create
     */
    public EdgeElement putEdge(VertexElement to, Schema.EdgeLabel type) {
        GraphTraversal<Vertex, Edge> traversal = tx().getTinkerTraversal().V().
                hasId(id()).
                outE(type.getLabel()).as("edge").otherV().
                hasId(to.id())
                .select("edge");

        if (!traversal.hasNext()) {
            return addEdge(to, type);
        } else {
            return tx().factory().buildEdgeElement(traversal.next());
        }
    }

    /**
     * Deletes all the edges of a specific Schema.EdgeLabel to or from a specific set of targets.
     * If no targets are provided then all the edges of the specified type are deleted
     *
     * @param direction The direction of the edges to delete
     * @param label     The edge label to delete
     * @param targets   An optional set of targets to delete edges from
     */
    public void deleteEdge(Direction direction, Schema.EdgeLabel label, VertexElement... targets) {
        Iterator<Edge> edges = element().edges(direction, label.getLabel());
        if (targets.length == 0) {
            edges.forEachRemaining(Edge::remove);
        } else {
            Set<Vertex> verticesToDelete = Arrays.stream(targets).map(AbstractElement::element).collect(Collectors.toSet());
            edges.forEachRemaining(edge -> {
                boolean delete = false;
                switch (direction) {
                    case BOTH:
                        delete = verticesToDelete.contains(edge.inVertex()) || verticesToDelete.contains(edge.outVertex());
                        break;
                    case IN:
                        delete = verticesToDelete.contains(edge.outVertex());
                        break;
                    case OUT:
                        delete = verticesToDelete.contains(edge.inVertex());
                        break;
                }

                if (delete) edge.remove();
            });
        }
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Vertex [").append(id()).append("] /n");
        element().properties().forEachRemaining(
                p -> stringBuilder.append("Property [").append(p.key()).append("] value [").append(p.value()).append("] /n"));
        return stringBuilder.toString();
    }

}
