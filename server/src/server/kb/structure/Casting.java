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
import grakn.core.concept.thing.Relation;
import grakn.core.concept.thing.Thing;
import grakn.core.concept.type.RelationType;
import grakn.core.concept.type.Role;
import grakn.core.server.kb.Cache;
import grakn.core.server.kb.Schema;
import grakn.core.server.kb.concept.ConceptManager;

import javax.annotation.Nullable;

/**
 * Represents An Thing Playing a Role
 * Wraps the Schema.EdgeLabel#ROLE_PLAYER Edge which contains the information unifying an Thing,
 * Relation and Role.
 */
public class Casting {

    private final EdgeElement edgeElement;
    private ConceptManager conceptManager;
    private final Cache<Role> cachedRole = new Cache<>(() -> conceptManager().getSchemaConcept(LabelId.of(edge().property(Schema.EdgeProperty.ROLE_LABEL_ID))));
    private final Cache<Thing> cachedInstance = new Cache<>(() -> conceptManager().buildConcept(edge().target()));
    private final Cache<Relation> cachedRelation = new Cache<>(() -> conceptManager().buildConcept(edge().source()));

    private final Cache<RelationType> cachedRelationType = new Cache<>(() -> {
        if (cachedRelation.isCached()) {
            return cachedRelation.get().type();
        } else {
            return conceptManager().getSchemaConcept(LabelId.of(edge().property(Schema.EdgeProperty.RELATION_TYPE_LABEL_ID)));
        }
    });

    Casting(EdgeElement edgeElement, @Nullable Relation relation, @Nullable Role role, @Nullable Thing thing, ConceptManager conceptManager) {
        this.edgeElement = edgeElement;
        this.conceptManager = conceptManager;
        if (relation != null) this.cachedRelation.set(relation);
        if (role != null) this.cachedRole.set(role);
        if (thing != null) this.cachedInstance.set(thing);
    }

    public static Casting create(EdgeElement edgeElement, Relation relation, Role role, Thing thing, ConceptManager conceptManager) {
        return new Casting(edgeElement, relation, role, thing, conceptManager);
    }

    public static Casting withThing(EdgeElement edgeElement, Thing thing, ConceptManager conceptManager) {
        return new Casting(edgeElement, null, null, thing, conceptManager);
    }

    public static Casting withRelation(EdgeElement edgeElement, Relation relation, ConceptManager conceptManager) {
        return new Casting(edgeElement, relation, null, null, conceptManager);
    }

    private EdgeElement edge() {
        return edgeElement;
    }

    private ConceptManager conceptManager() {
        return conceptManager;
    }

    /**
     * @return The Role the Thing is playing
     */
    public Role getRole() {
        return cachedRole.get();
    }

    /**
     * @return The RelationType the Thing is taking part in
     */
    public RelationType getRelationType() {
        return cachedRelationType.get();
    }

    /**
     * @return The Relation which is linking the Role and the instance
     */
    public Relation getRelation() {
        return cachedRelation.get();
    }

    /**
     * @return The Thing playing the Role
     */
    public Thing getRolePlayer() {
        return cachedInstance.get();
    }

    /**
     * @return The hash code of the underlying vertex
     */
    public int hashCode() {
        return edge().id().hashCode();
    }

    /**
     * Deletes this Casting effectively removing a Thing from playing a Role in a Relation
     */
    public void delete() {
        edge().delete();
    }

    /**
     * @return true if the elements equal each other
     */
    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (object == null || getClass() != object.getClass()) return false;

        Casting casting = (Casting) object;

        return edge().id().equals(casting.edge().id());
    }
}
