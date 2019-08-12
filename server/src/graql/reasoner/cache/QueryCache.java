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

package grakn.core.graql.reasoner.cache;

import grakn.core.concept.answer.ConceptMap;
import grakn.core.graql.reasoner.query.ReasonerQueryImpl;
import grakn.core.graql.reasoner.unifier.MultiUnifier;
import grakn.core.graql.reasoner.utils.Pair;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Stream;


/**
 * Generic interface query caches.
 * <p>
 * Defines two basic operations:
 * - GET(Query)
 * - RECORD(Query, Answer).
 *
 * @param <Q>  the type of query that is being cached
 * @param <S>  the type of answer being cached
 * @param <SE> the type of answer being cached
 */
public interface QueryCache<
        Q extends ReasonerQueryImpl,
        S extends Iterable<ConceptMap>,
        SE extends Collection<ConceptMap>> {

    /**
     * record single answer to a specific query
     *
     * @param query  of interest
     * @param answer to this query
     * @return updated entry
     */
    CacheEntry<Q, SE> record(Q query, ConceptMap answer);

    CacheEntry<Q, SE> record(Q query, ConceptMap answer, @Nullable CacheEntry<Q, SE> entry, @Nullable MultiUnifier unifier);

    /**
     * retrieve (possibly) cached answers for provided query
     *
     * @param query for which to retrieve answers
     * @return unified cached answers
     */
    S getAnswers(Q query);

    Stream<ConceptMap> getAnswerStream(Q query);

    Pair<S, MultiUnifier> getAnswersWithUnifier(Q query);

    Pair<Stream<ConceptMap>, MultiUnifier> getAnswerStreamWithUnifier(Q query);

    /**
     * Query cache containment check
     *
     * @param query to be checked for containment
     * @return true if cache contains the query
     */
    boolean contains(Q query);


    Set<Q> queries();

    /**
     * Clear the cache
     */
    void clear();
}
