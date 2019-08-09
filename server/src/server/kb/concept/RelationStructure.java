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

package grakn.core.server.kb.concept;

import grakn.core.concept.ConceptId;
import grakn.core.concept.thing.Thing;
import grakn.core.concept.type.RelationType;
import grakn.core.concept.type.Role;
import grakn.core.concept.type.Rule;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Encapsulates The structure of a  Relation.
 * This wraps up the structure of a Relation as either a RelationReified or a
 * RelationEdge.
 * It contains methods which can be accessed regardless of the Relation being a represented by a
 * VertexElement or an EdgeElement
 */
interface RelationStructure {

    /**
     * @return The ConceptId of the Relation
     */
    ConceptId id();

    /**
     * @return The relation structure which has been reified
     */
    RelationReified reify();

    /**
     * @return true if the Relation has been reified meaning it can support n-ary relations
     */
    boolean isReified();

    /**
     * @return The RelationType of the Relation
     */
    RelationType type();

    /**
     * @return All the Roles and the Things which play them
     */
    Map<Role, Set<Thing>> allRolePlayers();

    /**
     * @param roles The Roles which are played in this relation
     * @return The Things which play those Roles
     */
    Stream<Thing> rolePlayers(Role... roles);

    /**
     * Deletes the VertexElement or EdgeElement used to represent this Relation
     */
    void delete();

    /**
     * Return whether the relation has been deleted.
     */
    boolean isDeleted();

    /**
     * Used to indicate if this Relation has been created as the result of a Rule inference.
     *
     * @return true if this Relation exists due to a rule
     * @see Rule
     */
    boolean isInferred();

}
