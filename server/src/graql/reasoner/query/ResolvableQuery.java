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

package grakn.core.graql.reasoner.query;

import grakn.core.concept.answer.ConceptMap;
import grakn.core.graql.gremlin.TraversalPlanner;
import grakn.core.graql.reasoner.ResolutionIterator;
import grakn.core.graql.reasoner.atom.Atom;
import grakn.core.graql.reasoner.state.AnswerPropagatorState;
import grakn.core.graql.reasoner.state.ResolutionState;
import grakn.core.graql.reasoner.unifier.Unifier;
import graql.lang.Graql;
import graql.lang.query.GraqlGet;

import java.util.HashSet;
import java.util.Iterator;
import javax.annotation.CheckReturnValue;
import java.util.Set;
import java.util.stream.Stream;

/**
 *
 * Interface for resolvable reasoner queries.
 *
 */
public interface ResolvableQuery extends ReasonerQuery {

    @CheckReturnValue
    ResolvableQuery copy();

    @CheckReturnValue
    Stream<Atom> selectAtoms();

    /**
     * @return this query in the composite form
     */
    @CheckReturnValue
    CompositeQuery asComposite();

    /**
     * @param sub substitution to be inserted into the query
     * @return corresponding query with additional substitution
     */
    @CheckReturnValue
    ResolvableQuery withSubstitution(ConceptMap sub);

    /**
     * @return corresponding query with variable predicates removed
     */
    @CheckReturnValue
    ResolvableQuery constantValuePredicateQuery();

    /**
     * @return corresponding reasoner query with inferred types
     */
    @CheckReturnValue
    ResolvableQuery inferTypes();

    /**
     * @param q query to be compared with
     * @return true if two queries are alpha-equivalent
     */
    @CheckReturnValue
    boolean isEquivalent(ResolvableQuery q);

    /**
     * @return true if this query requires atom decomposition
     */
    @CheckReturnValue
    boolean requiresDecomposition();

    /**
     * reiteration might be required if rule graph contains loops with negative flux
     * or there exists a rule which head satisfies body
     * @return true if because of the rule graph form, the resolution of this query may require reiteration
     */
    @CheckReturnValue
    boolean requiresReiteration();

    /**
     * @return corresponding Get query
     */
    @CheckReturnValue
    default GraqlGet getQuery() {
        return Graql.match(getPattern()).get();
    }

    /**
     * @return rewritten (decomposed) version of the query
     */
    @CheckReturnValue
    ResolvableQuery rewrite();

    /**
     * resolves the query
     * @return stream of answers
     */
    @CheckReturnValue
    default Stream<ConceptMap> resolve(){
        return resolve(new HashSet<>());
    }

    /**
     *
     * @param subGoals already visited subgoals
     * @return stream of resolved answers
     */
    @CheckReturnValue
    default Stream<ConceptMap> resolve(Set<ReasonerAtomicQuery> subGoals){
        boolean doNotResolve = getAtoms().isEmpty() || (isPositive() && !isRuleResolvable());
        return doNotResolve?
                tx().executor().traverse(getPattern(), TraversalPlanner.createTraversal(getPattern(), tx())) :
                new ResolutionIterator(this, subGoals).hasStream();
    }

    /**
     * @param sub partial substitution
     * @param u unifier with parent state
     * @param parent parent state
     * @param subGoals set of visited sub goals
     * @return resolution state formed from this query
     */
    @CheckReturnValue
    ResolutionState resolutionState(ConceptMap sub, Unifier u, AnswerPropagatorState parent, Set<ReasonerAtomicQuery> subGoals);

    /**
     * @param parent parent state
     * @param subGoals set of visited sub goals
     * @return inner query state iterator (db iter + unifier + state iter) for this query
     */
    @CheckReturnValue
    Iterator<ResolutionState> innerStateIterator(AnswerPropagatorState parent, Set<ReasonerAtomicQuery> subGoals);
}
