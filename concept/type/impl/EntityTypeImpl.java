/*
 * Copyright (C) 2020 Grakn Labs
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
 *
 */

package grakn.core.concept.type.impl;

import grakn.core.common.exception.GraknException;
import grakn.core.concept.thing.Entity;
import grakn.core.concept.thing.impl.EntityImpl;
import grakn.core.concept.type.AttributeType;
import grakn.core.concept.type.EntityType;
import grakn.core.concept.type.RoleType;
import grakn.core.graph.TypeGraph;
import grakn.core.graph.util.Encoding;
import grakn.core.graph.vertex.ThingVertex;
import grakn.core.graph.vertex.TypeVertex;

import javax.annotation.Nullable;
import java.util.List;
import java.util.stream.Stream;

import static grakn.core.common.exception.ErrorMessage.TypeRead.TYPE_ROOT_MISMATCH;
import static grakn.core.common.exception.ErrorMessage.TypeWrite.ROOT_TYPE_MUTATION;

public class EntityTypeImpl extends ThingTypeImpl implements EntityType {

    private EntityTypeImpl(TypeVertex vertex) {
        super(vertex);
        if (vertex.encoding() != Encoding.Vertex.Type.ENTITY_TYPE) {
            throw exception(TYPE_ROOT_MISMATCH.message(
                    vertex.label(),
                    Encoding.Vertex.Type.ENTITY_TYPE.root().label(),
                    vertex.encoding().root().label()
            ));
        }
    }

    private EntityTypeImpl(TypeGraph graph, String label) {
        super(graph, label, Encoding.Vertex.Type.ENTITY_TYPE);
        assert !label.equals(Encoding.Vertex.Type.Root.ENTITY.label());
    }

    public static EntityTypeImpl of(TypeVertex vertex) {
        if (vertex.label().equals(Encoding.Vertex.Type.Root.ENTITY.label())) return new EntityTypeImpl.Root(vertex);
        else return new EntityTypeImpl(vertex);
    }

    public static EntityTypeImpl of(TypeGraph graph, String label) {
        return new EntityTypeImpl(graph, label);
    }

    @Override
    public void setSupertype(EntityType superType) {
        super.superTypeVertex(((EntityTypeImpl) superType).vertex);
    }

    @Nullable
    @Override
    public EntityTypeImpl getSupertype() {
        return super.getSupertype(EntityTypeImpl::of);
    }

    @Override
    public Stream<EntityTypeImpl> getSupertypes() {
        return super.getSupertypes(EntityTypeImpl::of);
    }

    @Override
    public Stream<EntityTypeImpl> getSubtypes() {
        return super.getSubtypes(EntityTypeImpl::of);
    }

    @Override
    public Stream<EntityImpl> getInstances() {
        return super.instances(EntityImpl::of);
    }

    @Override
    public List<GraknException> validate() {
        return super.validate();
    }

    @Override
    public EntityImpl create() {
        return create(false);
    }

    @Override
    public EntityImpl create(boolean isInferred) {
        validateIsCommitedAndNotAbstract(Entity.class);
        ThingVertex instance = vertex.graph().thing().create(vertex.iid(), isInferred);
        return EntityImpl.of(instance);
    }

    private static class Root extends EntityTypeImpl {

        private Root(TypeVertex vertex) {
            super(vertex);
            assert vertex.label().equals(Encoding.Vertex.Type.Root.ENTITY.label());
        }

        @Override
        public boolean isRoot() { return true; }

        @Override
        public void setLabel(String label) {
            throw exception(ROOT_TYPE_MUTATION.message());
        }

        @Override
        public void unsetAbstract() {
            throw exception(ROOT_TYPE_MUTATION.message());
        }

        @Override
        public void setSupertype(EntityType superType) {
            throw exception(ROOT_TYPE_MUTATION.message());
        }

        @Override
        public void setOwns(AttributeType attributeType, boolean isKey) {
            throw exception(ROOT_TYPE_MUTATION.message());
        }

        @Override
        public void setOwns(AttributeType attributeType, AttributeType overriddenType, boolean isKey) {
            throw exception(ROOT_TYPE_MUTATION.message());
        }

        @Override
        public void setPlays(RoleType roleType) {
            throw exception(ROOT_TYPE_MUTATION.message());
        }

        @Override
        public void setPlays(RoleType roleType, RoleType overriddenType) {
            throw exception(ROOT_TYPE_MUTATION.message());
        }

        @Override
        public void unsetPlays(RoleType roleType) {
            throw exception(ROOT_TYPE_MUTATION.message());
        }
    }
}
