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

package grakn.core.server.kb.concept;

import grakn.core.concept.Concept;
import grakn.core.concept.ConceptId;
import grakn.core.concept.type.AttributeType;
import grakn.core.concept.type.EntityType;
import grakn.core.concept.type.RelationType;
import grakn.core.concept.type.Role;
import grakn.core.concept.type.Rule;
import grakn.core.server.exception.TemporaryWriteException;
import grakn.core.server.exception.TransactionException;
import grakn.core.server.kb.Schema;
import grakn.core.server.kb.structure.AbstractElement;
import grakn.core.server.kb.structure.EdgeElement;
import grakn.core.server.kb.structure.Shard;
import grakn.core.server.kb.structure.VertexElement;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.pattern.Pattern;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.janusgraph.core.JanusGraph;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static grakn.core.server.kb.Schema.BaseType.RELATION_TYPE;

/**
 * Constructs Concepts And Edges
 * This class turns Tinkerpop Vertex and Edge
 * into Grakn Concept and EdgeElement.
 * Construction is only successful if the vertex and edge properties contain the needed information.
 * A concept must include a label which is a Schema.BaseType.
 * An edge must include a label which is a Schema.EdgeLabel.
 */
public final class ElementFactory {
    private final TransactionOLTP tx;
    private final JanusGraph graph;

    public ElementFactory(TransactionOLTP tx, JanusGraph graph) {
        this.tx = tx;
        this.graph = graph;
    }

    private <X extends Concept, E extends AbstractElement> X getOrBuildConcept(E element, ConceptId conceptId, Function<E, X> conceptBuilder) {
        if (!tx.cache().isConceptCached(conceptId)) {
            X newConcept = conceptBuilder.apply(element);
            tx.cache().cacheConcept(newConcept);
        }
        return tx.cache().getCachedConcept(conceptId);
    }

    private <X extends Concept> X getOrBuildConcept(VertexElement element, Function<VertexElement, X> conceptBuilder) {
        ConceptId conceptId = ConceptId.of(element.property(Schema.VertexProperty.ID));
        return getOrBuildConcept(element, conceptId, conceptBuilder);
    }

    private <X extends Concept> X getOrBuildConcept(EdgeElement element, Function<EdgeElement, X> conceptBuilder) {
        ConceptId conceptId = ConceptId.of(element.id().getValue());
        return getOrBuildConcept(element, conceptId, conceptBuilder);
    }

    // ---------------------------------------- Building Attribute Types  -----------------------------------------------
    public <V> AttributeTypeImpl<V> buildAttributeType(VertexElement vertex, AttributeType<V> type, AttributeType.DataType<V> dataType) {
        return getOrBuildConcept(vertex, (v) -> AttributeTypeImpl.create(v, type, dataType));
    }

    // ------------------------------------------ Building Attribute
    <V> AttributeImpl<V> buildAttribute(VertexElement vertex, AttributeType<V> type, V persistedValue) {
        return getOrBuildConcept(vertex, (v) -> AttributeImpl.create(v, type, persistedValue));
    }

    // ---------------------------------------- Building Relation Types  -----------------------------------------------
    public RelationTypeImpl buildRelationType(VertexElement vertex, RelationType type) {
        return getOrBuildConcept(vertex, (v) -> RelationTypeImpl.create(v, type));
    }

    // -------------------------------------------- Building Relations
    RelationImpl buildRelation(VertexElement vertex, RelationType type) {
        return getOrBuildConcept(vertex, (v) -> RelationImpl.create(buildRelationReified(v, type)));
    }

    public RelationImpl buildRelation(EdgeElement edge, RelationType type, Role owner, Role value) {
        return getOrBuildConcept(edge, (e) -> RelationImpl.create(RelationEdge.create(type, owner, value, edge)));
    }

    RelationImpl buildRelation(EdgeElement edge) {
        return getOrBuildConcept(edge, (e) -> RelationImpl.create(RelationEdge.get(edge)));
    }

    RelationReified buildRelationReified(VertexElement vertex, RelationType type) {
        return RelationReified.create(vertex, type);
    }

    // ----------------------------------------- Building Entity Types  ------------------------------------------------
    public EntityTypeImpl buildEntityType(VertexElement vertex, EntityType type) {
        return getOrBuildConcept(vertex, (v) -> EntityTypeImpl.create(v, type));
    }

    // ------------------------------------------- Building Entities
    EntityImpl buildEntity(VertexElement vertex, EntityType type) {
        return getOrBuildConcept(vertex, (v) -> EntityImpl.create(v, type));
    }

    // ----------------------------------------- Building Rules --------------------------------------------------
    public RuleImpl buildRule(VertexElement vertex, Rule type, Pattern when, Pattern then) {
        return getOrBuildConcept(vertex, (v) -> RuleImpl.create(v, type, when, then));
    }

    // ------------------------------------------ Building Roles  Types ------------------------------------------------
    public RoleImpl buildRole(VertexElement vertex, Role type) {
        return getOrBuildConcept(vertex, (v) -> RoleImpl.create(v, type));
    }

    /**
     * Constructors are called directly because this is only called when reading a known vertex or concept.
     * Thus tracking the concept can be skipped.
     *
     * @param vertex A vertex of an unknown type
     * @return A concept built to the correct type
     */
    public <X extends Concept> X buildConcept(Vertex vertex) {
        return buildConcept(buildVertexElement(vertex));
    }

    public <X extends Concept> X buildConcept(VertexElement vertexElement) {

        long start2 = System.currentTimeMillis();
        ConceptId conceptId = ConceptId.of(vertexElement.property(Schema.VertexProperty.ID));
        getConceptIdTime += System.currentTimeMillis() - start2;
        //X cachedConcept = null;
        start2 = System.currentTimeMillis();
        X cachedConcept = tx.cache().getCachedConcept(conceptId);
        getCachedConceptTime += System.currentTimeMillis() - start2;

        if (cachedConcept == null) {
            long start = System.currentTimeMillis();
            Schema.BaseType type;
            try {
                String label = vertexElement.label();
                getVertexLabel += System.currentTimeMillis() - start;
                type = Schema.BaseType.fromString(label);

            } catch (IllegalStateException e) {
                throw TemporaryWriteException.indexOverlap(vertexElement.element(), e);
            }

            baseTypeValueOfTime += System.currentTimeMillis() - start;
            start = System.currentTimeMillis();
            Concept concept;
            switch (type) {
                case RELATION:
                    concept = RelationImpl.create(RelationReified.get(vertexElement));
                    break;
                case TYPE:
                    concept = new TypeImpl(vertexElement);
                    break;
                case ROLE:
                    concept = RoleImpl.get(vertexElement);
                    break;
                case RELATION_TYPE:
                    concept = RelationTypeImpl.get(vertexElement);
                    break;
                case ENTITY:
                    concept = EntityImpl.get(vertexElement);
                    break;
                case ENTITY_TYPE:
                    concept = EntityTypeImpl.get(vertexElement);
                    break;
                case ATTRIBUTE_TYPE:
                    concept = AttributeTypeImpl.get(vertexElement);
                    break;
                case ATTRIBUTE:
                    concept = AttributeImpl.get(vertexElement);
                    break;
                case RULE:
                    concept = RuleImpl.get(vertexElement);
                    break;
                default:
                    throw TransactionException.unknownConcept(type.name());
            }
            conceptBuildTime += System.currentTimeMillis() - start;
            tx.cache().cacheConcept(concept);
            return (X) concept;
        }
        return cachedConcept;
    }

    public static long getVertexLabel = 0;
    public static long getConceptIdTime = 0;
    public static long getCachedConceptTime = 0;
    public static long baseTypeRetrieveTime = 0;
    public static long conceptBuildTime = 0;

    /**
     * Constructors are called directly because this is only called when reading a known Edge or Concept.
     * Thus tracking the concept can be skipped.
     *
     * @param edge A Edge of an unknown type
     * @return A concept built to the correct type
     */
    public <X extends Concept> X buildConcept(Edge edge) {
        return buildConcept(buildEdgeElement(edge));
    }

    public <X extends Concept> X buildConcept(EdgeElement edgeElement) {
        Schema.EdgeLabel label = Schema.EdgeLabel.valueOf(edgeElement.label().toUpperCase(Locale.getDefault()));

        ConceptId conceptId = ConceptId.of(edgeElement.id().getValue());
        if (!tx.cache().isConceptCached(conceptId)) {
            Concept concept;
            switch (label) {
                case ATTRIBUTE:
                    concept = RelationImpl.create(RelationEdge.get(edgeElement));
                    break;
                default:
                    throw TransactionException.unknownConcept(label.name());
            }
            tx.cache().cacheConcept(concept);
        }
        return tx.cache().getCachedConcept(conceptId);
    }

    /**
     * This is a helper method to get the base type of a vertex.
     * It first tried to get the base type via the label.
     * If this is not possible it then tries to get the base type via the Shard Edge.
     *
     * @param vertex The vertex to build a concept from
     * @return The base type of the vertex, if it is a valid concept.
     */
    private Schema.BaseType getBaseType(VertexElement vertex) {
        try {
            long start = System.currentTimeMillis();
            Schema.BaseType baseType = Schema.BaseType.valueOf(vertex.label());
            //Schema.BaseType baseType = Schema.BaseType.fromString(vertex.label());
            //Schema.BaseType baseType = Schema.BaseType.get(vertex.label());
            baseTypeValueOfTime += System.currentTimeMillis() - start;
            baseTypeCalls++;
            return baseType;
        } catch (IllegalArgumentException e) {
            //Base type appears to be invalid. Let's try getting the type via the shard edge
            Optional<VertexElement> type = vertex.getEdgesOfType(Direction.OUT, Schema.EdgeLabel.SHARD).
                    map(EdgeElement::target).findAny();

            if (type.isPresent()) {
                String label = type.get().label();
                if (label.equals(Schema.BaseType.ENTITY_TYPE.name())) return Schema.BaseType.ENTITY;
                if (label.equals(RELATION_TYPE.name())) return Schema.BaseType.RELATION;
                if (label.equals(Schema.BaseType.ATTRIBUTE_TYPE.name())) return Schema.BaseType.ATTRIBUTE;
            }

        }
        throw new IllegalStateException("Could not determine the base type of vertex [" + vertex + "]");
    }

    public static long baseTypeCalls = 0;
    public static long baseTypeValueOfTime = 0;

    // ---------------------------------------- Non Concept Construction -----------------------------------------------
    public EdgeElement buildEdgeElement(Edge edge) {
        return new EdgeElement(tx, edge);
    }


    Shard buildShard(ConceptImpl shardOwner, VertexElement vertexElement) {
        return new Shard(shardOwner, vertexElement);
    }

    Shard buildShard(VertexElement vertexElement) {
        return new Shard(vertexElement);
    }

    Shard buildShard(Vertex vertex) {
        return new Shard(buildVertexElement(vertex));
    }

    /**
     * Builds a VertexElement from an already existing Vertex.
     * *
     * @throws TransactionException if vertex is not valid. A vertex is not valid if it is null or has been deleted
     * @param vertex A vertex which can possibly be turned into a VertexElement
     * @return A VertexElement of
     */
    public VertexElement buildVertexElement(Vertex vertex) {
        long start = System.currentTimeMillis();
        if (!tx.isValidElement(vertex)) {
            Objects.requireNonNull(vertex);
            throw TransactionException.invalidElement(vertex);
        }
        VertexElement vertexElement = new VertexElement(tx, vertex);
        buildVertexElementTime += System.currentTimeMillis() - start;
        return vertexElement;
    }

    public static long buildVertexElementTime = 0;

    /**
     * Creates a new Vertex in the graph and builds a VertexElement which wraps the newly created vertex
     * @param baseType   The Schema.BaseType
     * @return a new VertexElement
     */
    public VertexElement addVertexElement(Schema.BaseType baseType) {
        Vertex vertex = graph.addVertex(baseType.name());
        String newConceptId = Schema.PREFIX_VERTEX + vertex.id().toString();
        vertex.property(Schema.VertexProperty.ID.name(), newConceptId);
        tx.cache().writeOccurred();
        return new VertexElement(tx, vertex);
    }

    /**
     * Creates a new Vertex in the graph and builds a VertexElement which wraps the newly created vertex
     * @param baseType   The Schema.BaseType
     * @param conceptId the ConceptId to set as the new ConceptId
     * @return a new VertexElement
     */
    public VertexElement addVertexElement(Schema.BaseType baseType, ConceptId conceptId) {
        Objects.requireNonNull(conceptId);
        Vertex vertex = graph.addVertex(baseType.name());
        String newConceptId = conceptId.getValue();
        vertex.property(Schema.VertexProperty.ID.name(), newConceptId);
        tx.cache().writeOccurred();
        return new VertexElement(tx, vertex);
    }
}
