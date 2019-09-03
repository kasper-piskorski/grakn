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

package grakn.core.graql.reasoner.unifier;

import grakn.core.concept.type.Role;
import grakn.core.concept.type.SchemaConcept;
import grakn.core.concept.type.Type;
import grakn.core.graql.reasoner.atom.Atomic;
import grakn.core.graql.reasoner.query.ReasonerQuery;
import grakn.core.server.kb.concept.ConceptUtils;
import graql.lang.statement.Variable;

import java.util.Set;
import java.util.function.BiFunction;

/**
 * Interface for defining unifier comparisons.
 */
public interface UnifierComparison {

    /**
     * @return true if the unifier permits a multi-valued mapping (parent vars can have multiple corresponding child vars)
     */
    default boolean allowsNonInjectiveMappings(){ return true;}

    /**
     * @return true if types should be inferred when computing unifier
     */
    boolean inferTypes();

    /**
     * @return true if values should be inferred from id predicates when computing unifier
     */
    boolean inferValues();

    /**
     * @param parent parent type Atomic
     * @param child child type Atomic
     * @return true if both types are compatible in terms of type directness
     */
    boolean typeDirectednessCompatibility(Atomic parent, Atomic child);

    /**
     * Does compatibility comparison between a parent-child role pair.
     * NB: in contrast to typeCompatibility, roles in rules have INSERT semantics - have strict direct types.
     *
     * @param parent role
     * @param child role
     * @return true if Types are compatible
     */
    boolean roleCompatibility(Role parent, Role child);

    /**
     * Does compatibility comparison between a parent-child type set pair.
     * NB: Because types are defined in the when part, types have MATCH semantics - their types include relevant hierarchy parts.
     *
     * @param parent SchemaConcept of parent expression
     * @param child  SchemaConcept of child expression
     * @return true if Types are compatible
     */
    default boolean typeCompatibility(Set<? extends SchemaConcept> parent, Set<? extends SchemaConcept> child){
        //checks intra compatibility
        return !ConceptUtils.areDisjointTypeSets(parent, parent, true)
                && !ConceptUtils.areDisjointTypeSets(child, child, true);
    }

    /**
     * @param parent Atomic of parent expression
     * @param child  Atomic of child expression
     * @return true if id predicates are compatible
     */
    boolean idCompatibility(Atomic parent, Atomic child);

    /**
     * @param parent Atomic of parent expression
     * @param child  Atomic of child expression
     * @return true if value predicates are compatible
     */
    boolean valueCompatibility(Atomic parent, Atomic child);

    /**
     * @param parent Atomics of parent expression
     * @param child  Atomics of child expression
     * @return true if id predicate sets are compatible
     */
    default boolean idCompatibility(Set<Atomic> parent, Set<Atomic> child){
        return predicateCompatibility(parent, child, this::idCompatibility);
    }

    /**
     * @param parent multipredicate of parent
     * @param child  multipredicate of child
     * @return true if multipredicates are compatible (no contradictions between value predicates)
     */
    default boolean valueCompatibility(Set<Atomic> parent, Set<Atomic> child) {
        return predicateCompatibility(parent, child, this::valueCompatibility);
    }

    default boolean predicateCompatibility(Set<Atomic> parent, Set<Atomic> child, BiFunction<Atomic, Atomic, Boolean> comparison){
        //checks intra compatibility
        return (child.stream().allMatch(cp -> child.stream().allMatch(cp::isCompatibleWith)))
                && (parent.stream().allMatch(cp -> parent.stream().allMatch(cp::isCompatibleWith)));
    }

    /**
     * @param query to be checked
     * @param var   variable of interest
     * @param type  which playability is toto be checked
     * @return true if typing the typeVar with type is compatible with role configuration of the provided query
     */
    boolean typePlayability(ReasonerQuery query, Variable var, Type type);

    /**
     * @param parent    Atomic query
     * @param child     Atomic query
     * @param parentVar variable of interest in the parent query
     * @param childVar  variable of interest in the child query
     * @return true if attributes attached to child var are compatible with attributes attached to parent var
     */
    default boolean attributeCompatibility(ReasonerQuery parent, ReasonerQuery child, Variable parentVar, Variable childVar) { return true;}
}
