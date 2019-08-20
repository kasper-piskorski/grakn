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

import grakn.core.concept.thing.Thing;
import grakn.core.server.kb.Schema;
import grakn.core.server.kb.concept.ConceptImpl;
import org.apache.tinkerpop.gremlin.structure.Direction;

import java.util.stream.Stream;

/**
 * Represent a Shard of a concept
 * Wraps a VertexElement which is a shard of a Concept.
 * This is used to break supernodes apart. For example the instances of a Type are
 * spread across several shards.
 */
public class Shard {
    private final VertexElement vertexElement;

    public Shard(ConceptImpl owner, VertexElement vertexElement) {
        this(vertexElement);
        owner(owner);
    }

    public Shard(VertexElement vertexElement) {
        this.vertexElement = vertexElement;
    }

    public VertexElement vertex() {
        return vertexElement;
    }

    /**
     * @return The id of this shard. Strings are used because shards are looked up via the string index.
     */
    public Object id() {
        return vertex().id();
    }

    /**
     * @param owner Sets the owner of this shard
     */
    private void owner(ConceptImpl owner) {
        vertex().putEdge(owner.vertex(), Schema.EdgeLabel.SHARD);
    }

    /**
     * Links a new concept to this shard.
     *
     * @param concept The concept to link to this shard
     */
    public void link(ConceptImpl concept) {
        concept.vertex().addEdge(vertex(), Schema.EdgeLabel.ISA);
    }

    /**
     * @return All the concept linked to this shard
     */
    public <V extends Thing> Stream<V> links() {
        return vertex().getEdgesOfType(Direction.IN, Schema.EdgeLabel.ISA).
                map(EdgeElement::source).
                map(vertexElement -> vertex().tx().factory().buildConcept(vertexElement));
    }

    /**
     * @return The hash code of the underlying vertex
     */
    public int hashCode() {
        return id().hashCode();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (object == null || getClass() != object.getClass()) return false;

        Shard shard = (Shard) object;

        //based on id because vertex comparisons are equivalent
        return id().equals(shard.id());
    }
}
