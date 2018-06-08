/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016-2018 Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/agpl.txt>.
 */

package ai.grakn.graql.internal.reasoner.cache;

import ai.grakn.graql.admin.Answer;
import ai.grakn.graql.admin.CacheEntry;
import ai.grakn.graql.admin.MultiUnifier;
import ai.grakn.graql.admin.QueryCache;
import ai.grakn.graql.internal.reasoner.query.ReasonerQueryImpl;
import ai.grakn.graql.internal.reasoner.utils.Pair;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 *
 * <p>
 * Generic container class for storing performed query resolutions.
 * A one-to-one mapping is ensured between queries and entries.
 * On retrieval, a relevant entry is identified by means of a query alpha-equivalence check.
 *
 * Defines two basic operations:
 * - GET(Query) - retrieve an entry corresponding to a provided query, if entry doesn't exist return db lookup result of the query.
 * - RECORD(Query) - if the query entry exists, update the entry, otherwise create a new entry. In each case return an up-to-date entry.
 *
 * </p>
 *
 * @param <Q> the type of query that is being cached
 * @param <T> the type of answer being cached
 *
 * @author Kasper Piskorski
 *
 */
public abstract class QueryCacheBase<Q extends ReasonerQueryImpl, T extends Iterable<Answer>> implements QueryCache<Q, T> {

    private final Map<Q, CacheEntry<Q, T>> cache = new HashMap<>();
    private final StructuralQueryCache<Q> sCache;

    QueryCacheBase(){
        this.sCache = new StructuralQueryCacheImpl<>();
    }

    /**
     * @return structural cache of this cache
     */
    StructuralQueryCache<Q> structuralCache(){ return sCache;}

    /**
     * @param query for which the entry is to be retrieved
     * @return corresponding cache entry if any or null
     */
    CacheEntry<Q, T> getEntry(Q query){ return cache.get(query);}

    /**
     * Associates the specified answers with the specified query in this cache adding an (query) -> (answers) entry
     * @param query of the association
     * @param answers of the association
     * @return previous value if any or null
     */
    CacheEntry<Q, T> putEntry(Q query, T answers){ return cache.put(query, new CacheEntryImpl<>(query, answers));}

    /**
     * Perform cache union
     * @param c2 union right operand
     */
    @Override
    public void add(QueryCache<Q, T> c2){
        c2.queries().forEach( q -> this.record(q, c2.getAnswers(q)));
    }

    /**
     * Query cache containment check
     * @param query to be checked for containment
     * @return true if cache contains the query
     */
    @Override
    public boolean contains(Q query){ return cache.containsKey(query);}

    /**
     * @return all queries constituting this cache
     */
    @Override
    public Set<Q> queries(){ return cache.keySet();}

    /**
     * @return all (query) -> (answers) mappings
     */
    @Override
    public Collection<CacheEntry<Q, T>> entries(){ return cache.values();}

    /**
     * Perform cache difference
     * @param c2 cache which mappings should be removed from this cache
     */
    @Override
    public void remove(QueryCache<Q, T> c2){ remove(c2, queries());}

    /**
     * Clear the cache
     */
    @Override
    public void clear(){ cache.clear();}

    public abstract Pair<T, MultiUnifier> getAnswersWithUnifier(Q query);

    public abstract Pair<Stream<Answer>, MultiUnifier> getAnswerStreamWithUnifier(Q query);

}
