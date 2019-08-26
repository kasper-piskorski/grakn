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

import com.google.common.base.Equivalence;
import grakn.core.concept.ConceptId;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.graql.gremlin.GraqlTraversal;
import grakn.core.graql.gremlin.TraversalPlanner;
import grakn.core.graql.reasoner.explanation.LookupExplanation;
import grakn.core.graql.reasoner.query.ReasonerQueryEquivalence;
import grakn.core.graql.reasoner.query.ReasonerQueryImpl;
import grakn.core.graql.reasoner.unifier.MultiUnifier;
import grakn.core.graql.reasoner.unifier.Unifier;
import grakn.core.graql.reasoner.unifier.UnifierType;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.statement.Variable;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Container class allowing to store similar graql traversals with similarity measure based on structural query equivalence.
 * On cache hit a concept map between provided query and the one contained in the cache is constructed. Based on that mapping,
 * id predicates of the cached query are transformed.
 * The returned stream is a stream of the transformed cached query unified with the provided query.
 *
 * @param <Q> the type of query that is being cached
 */
public class StructuralCache<Q extends ReasonerQueryImpl>{

    private final ReasonerQueryEquivalence equivalence = ReasonerQueryEquivalence.StructuralEquivalence;
    private final Map<Equivalence.Wrapper<Q>, CacheEntry<Q, GraqlTraversal>> structCache;

    StructuralCache(){
        this.structCache = new HashMap<>();
    }

    /**
     * @param query to be retrieved
     * @return answer stream of provided query
     */
    public Stream<ConceptMap> get(Q query){
        Equivalence.Wrapper<Q> structQuery = equivalence.wrap(query);
        TransactionOLTP tx = query.tx();

        CacheEntry<Q, GraqlTraversal> match = structCache.get(structQuery);
        if (match != null){
            Q equivalentQuery = match.query();
            GraqlTraversal traversal = match.cachedElement();
            MultiUnifier multiUnifier = equivalentQuery.getMultiUnifier(query, UnifierType.STRUCTURAL);
            Unifier unifier = multiUnifier.getAny();
            Map<Variable, ConceptId> idTransform = equivalentQuery.idTransform(query, unifier);

            ReasonerQueryImpl transformedQuery = equivalentQuery.transformIds(idTransform);

            return tx.executor().traversal(transformedQuery.getPattern(), traversal.transform(idTransform))
                    .map(unifier::apply)
                    .map(a -> a.explain(new LookupExplanation(query.getPattern())));
        }

        GraqlTraversal traversal = TraversalPlanner.createTraversal(query.getPattern(), tx);
        structCache.put(structQuery, new CacheEntry<>(query, traversal));

        return tx.executor().traversal(query.getPattern(), traversal)
                .map(a -> a.explain(new LookupExplanation(query.getPattern())));
    }

    public void clear(){
        structCache.clear();
    }
}
