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

package grakn.core.graql.reasoner.state;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import grakn.core.concept.Concept;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.graql.reasoner.cache.SemanticDifference;
import grakn.core.graql.reasoner.explanation.LookupExplanation;
import grakn.core.graql.reasoner.query.ReasonerAtomicQuery;
import grakn.core.graql.reasoner.query.ReasonerQueries;
import grakn.core.graql.reasoner.rule.InferenceRule;
import grakn.core.graql.reasoner.unifier.Unifier;
import grakn.core.graql.reasoner.utils.Pair;
import grakn.core.graql.reasoner.utils.TarjanReachability;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.statement.Variable;
import java.util.Collections;
import java.util.Iterator;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 *
 */
public class TransitiveReachabilityState extends ResolutionState {

    private final ReasonerAtomicQuery query;
    private final Unifier unifier;
    private final Iterator<AnswerState> answerStateIterator;
    private final InferenceRule rule;
    private final SemanticDifference semDiff;

    public TransitiveReachabilityState(InferenceRule rule, ConceptMap sub, Unifier u, SemanticDifference diff, AnswerPropagatorState parent) {
        super(sub, parent);
        this.query = rule.getHead();
        this.rule = rule;
        this.unifier = u;
        this.semDiff = diff;
        this.answerStateIterator = generateAnswerIterator();
    }

    private Function<Concept, Stream<Concept>> neighbourFunction(ReasonerAtomicQuery baseQuery, Variable from, Variable to, TransactionOLTP tx){
        return (node) ->
                tx.queryCache().getAnswerStream(baseQuery.withSubstitution(new ConceptMap(ImmutableMap.of(from, node))))
                        .map(ans -> ans.get(to));
    }

    private Stream<ConceptMap> answerStream(ReasonerAtomicQuery baseQuery, Concept startNode, Concept endNode, Variable from, Variable to, TransactionOLTP tx){
        return new TarjanReachability<>(startNode, endNode, neighbourFunction(baseQuery, from, to, tx)).stream()
                .map(e -> new ConceptMap(
                        ImmutableMap.of(from, e.getKey(), to, e.getValue()),
                        new LookupExplanation(baseQuery.getPattern()))
                );
    }

    private Iterator<AnswerState> generateAnswerIterator(){
        TransactionOLTP tx = query.tx();
        ConceptMap sub = getSubstitution();
        ReasonerAtomicQuery baseQuery = ReasonerQueries.atomic(Collections.singleton(query.getAtom()), tx);
        Pair<Variable, Variable> directionality = Iterables.getOnlyElement(baseQuery.getAtom().toRelationAtom().varDirectionality());
        Variable from = directionality.getKey();
        Variable to = directionality.getValue();

        Concept startNode = sub.containsVar(from)? sub.get(from) : null;
        Concept endNode = sub.containsVar(to)? sub.get(to) : null;

        Stream<ConceptMap> answerStream = startNode != null?
                answerStream(baseQuery, startNode, endNode, from, to, tx) :
                answerStream(baseQuery, endNode, startNode, to, from, tx);

        return answerStream
                .map(semDiff::apply).filter(ans -> !ans.isEmpty())
                .map(ans -> new AnswerState(ans, unifier, getParentState(), rule))
                .iterator();
    }

    @Override
    public ResolutionState generateChildState() {
        return answerStateIterator.hasNext() ? answerStateIterator.next() : null;
    }
}
