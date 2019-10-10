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

import grakn.core.concept.LabelId;
import grakn.core.concept.type.RelationType;
import grakn.core.concept.type.Role;
import grakn.core.concept.type.Type;
import grakn.core.server.kb.Schema;
import grakn.core.server.kb.concept.ConceptImpl;
import grakn.core.server.kb.concept.ElementFactory;
import grakn.core.server.kb.concept.ElementUtils;
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

    public VertexElement(ElementFactory elementFactory, Vertex element) {
        super(elementFactory, element);
    }

    /**
     * @param direction The direction of the edges to retrieve
     * @param label     The type of the edges to retrieve
     * @return A collection of edges from this concept in a particular direction of a specific type
     */
    public Stream<EdgeElement> getEdgesOfType(Direction direction, Schema.EdgeLabel label) {
        Iterable<Edge> iterable = () -> element().edges(direction, label.getLabel());
        return StreamSupport.stream(iterable.spliterator(), false)
                .filter(edge -> ElementUtils.isValidElement(edge)) // filter out deleted but cached available edges
                .map(edge -> elementFactory.buildEdgeElement(edge));
    }

    /**
     * @param to   the target VertexElement
     * @param type the type of the edge to create
     * @return The edge created
     */
    public EdgeElement addEdge(VertexElement to, Schema.EdgeLabel type) {
        Edge newEdge = element().addEdge(type.getLabel(), to.element());
        return elementFactory.buildEdgeElement(newEdge);
    }

    /**
     * @param to   the target VertexElement
     * @param type the type of the edge to create
     */
    public EdgeElement putEdge(VertexElement to, Schema.EdgeLabel type) {
        EdgeElement existingEdge = elementFactory.edgeBetweenVertices(id().toString(), to.id().toString(), type);

        if (existingEdge == null) {
            return addEdge(to, type);
        } else {
            return existingEdge;
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

    public Shard asShard() {
        return elementFactory.getShard(this);
    }

    public Shard currentShard() {
        Object currentShardId = property(Schema.VertexProperty.CURRENT_SHARD);
        Vertex shardVertex = elementFactory.getVertexWithId(currentShardId.toString());
        return elementFactory.getShard(shardVertex);
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Vertex [").append(id()).append("] /n");
        element().properties().forEachRemaining(
                p -> stringBuilder.append("Property [").append(p.key()).append("] value [").append(p.value()).append("] /n"));
        return stringBuilder.toString();
    }

    /**
     * Create a new vertex that is a shard and connect it to the owning Type vertex
     * @param owningConcept
     * @return
     */
    public Shard shard(ConceptImpl owningConcept) {
        VertexElement shardVertex = elementFactory.addVertexElement(Schema.BaseType.SHARD);
        Shard shard = elementFactory.createShard(owningConcept, shardVertex);
        return shard;
    }

    public Stream<Shard> shards() {
        return getEdgesOfType(Direction.IN, Schema.EdgeLabel.SHARD)
                .map(EdgeElement::source)
                .map(vertexElement -> elementFactory.getShard(vertexElement));
    }

    public Stream<EdgeElement> roleCastingsEdges(Type type, Set<Integer> allowedRoleTypeIds) {
        return elementFactory.rolePlayerEdges(id().toString(), type, allowedRoleTypeIds);

    }

    public boolean rolePlayerEdgeExists(String startVertexId, RelationType type, Role role, String endVertexId) {
        return elementFactory.rolePlayerEdgeExists(startVertexId, type, role, endVertexId);
    }

    public Stream<VertexElement> getShortcutNeighbors(Set<Integer> ownerRoleIds, Set<Integer> valueRoleIds,
                                                      boolean ownerToValueOrdering) {
        return elementFactory.shortcutNeighbors(id().toString(), ownerRoleIds, valueRoleIds, ownerToValueOrdering);
    }

    public Stream<EdgeElement> edgeRelationsConnectedToInstancesOfType(LabelId edgeInstanceLabelId) {
        return elementFactory.edgeRelationsConnectedToInstancesOfType(id().toString(), edgeInstanceLabelId);
    }
}
