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

package grakn.core.graql.reasoner;

import grakn.benchmark.lib.instrumentation.ServerTracing;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.graql.reasoner.query.ReasonerQueries;
import grakn.core.graql.reasoner.query.ResolvableQuery;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.Graql;
import graql.lang.pattern.Conjunction;
import graql.lang.pattern.Pattern;
import graql.lang.query.MatchClause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Iterator;

/**
 * Iterator to handle execution of disjunctions.
 * We compose the disjunction into DNF - a collection of conjunctions. Then the disjunction iterator works as follows:
 * - we define an answer iterator which corresponds to the iterator of the currently processed conjunction
 * - if we run out of answers in the answer iterator, we reinitialise it with the iterator of the next conjunction
 * - we do that until we run out of both answers in the answer iterator and conjunctions in the DNF
 * NB: currently we clear the cache at the beginning of processing to ensure it is reused only in the context of a single
 * disjunction
 */
public class DisjunctionIterator extends ReasonerQueryIterator {

    final private Iterator<Conjunction<Pattern>> conjIterator;
    private Iterator<ConceptMap> answerIterator;
    private final TransactionOLTP tx;

    private static final Logger LOG = LoggerFactory.getLogger(DisjunctionIterator.class);

    public DisjunctionIterator(MatchClause matchClause, TransactionOLTP tx) {
        this.tx = tx;
        int conjunctionIterSpanId = ServerTracing.startScopedChildSpan("DisjunctionIterator() create DNF, conjunction iterator");

        this.conjIterator = matchClause.getPatterns().getNegationDNF().getPatterns().stream().iterator();
        answerIterator = conjunctionIterator(conjIterator.next(), tx);

        ServerTracing.closeScopedChildSpan(conjunctionIterSpanId);
    }

    private Iterator<ConceptMap> conjunctionIterator(Conjunction<Pattern> conj, TransactionOLTP tx) {
        ResolvableQuery query = ReasonerQueries.resolvable(conj, tx).rewrite();

        boolean doNotResolve = query.getAtoms().isEmpty()
                || (query.isPositive() && !query.isRuleResolvable());

        LOG.trace("Resolving conjunctive query ({}): {}", doNotResolve, query);

        return doNotResolve ?
                tx.stream(query.getQuery(), false).iterator() :
                new ResolutionIterator(query, new HashSet<>());
    }

    @Override
    public ConceptMap next() {
        return answerIterator.next();
    }

    /**
     * check whether answers available, if answers not fully computed, compute more answers
     *
     * @return true if answers available
     */
    @Override
    public boolean hasNext() {
        if (answerIterator.hasNext()) return true;

        while (conjIterator.hasNext()) {
            answerIterator = conjunctionIterator(conjIterator.next(), tx);
            if (answerIterator.hasNext()) return true;
        }
        return false;
    }
}
