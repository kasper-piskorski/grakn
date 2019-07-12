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

import grakn.core.concept.thing.Entity;
import grakn.core.concept.thing.Thing;
import grakn.core.concept.type.EntityType;
import grakn.core.server.kb.structure.VertexElement;
import java.util.stream.Stream;

/**
 * An instance of Entity Type EntityType
 * This represents an entity in the graph.
 * Entities are objects which are defined by their Attribute and their links to
 * other entities via Relation
 */
public class EntityImpl extends ThingImpl<Entity, EntityType> implements Entity {
    private EntityImpl(VertexElement vertexElement) {
        super(vertexElement);
    }

    private EntityImpl(VertexElement vertexElement, EntityType type) {
        super(vertexElement, type);
    }

    public static EntityImpl get(VertexElement vertexElement) {
        return new EntityImpl(vertexElement);
    }

    public static EntityImpl create(VertexElement vertexElement, EntityType type) {
        return new EntityImpl(vertexElement, type);
    }

    public static EntityImpl from(Entity entity) {
        return (EntityImpl) entity;
    }

    @Override
    public Stream<Thing> getDependentConcepts() { return Stream.of(this); }
}