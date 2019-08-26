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
import grakn.core.graql.reasoner.explanation.LookupExplanation;
import grakn.core.graql.reasoner.query.ReasonerAtomicQuery;
import grakn.core.graql.reasoner.unifier.Unifier;
import grakn.core.graql.reasoner.utils.LazyTarjanTC;
import grakn.core.graql.reasoner.utils.Pair;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.statement.Variable;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class TransitiveReachabilityState extends ResolutionState {

    private final ReasonerAtomicQuery query;
    private final Unifier unifier;
    private final Iterator<AnswerState> answerStateIterator;

    public TransitiveReachabilityState(ReasonerAtomicQuery q, ConceptMap sub, Unifier u, AnswerPropagatorState parent) {
        super(sub, parent);
        this.query = q;
        this.unifier = u;
        this.answerStateIterator = generateAnswerIterator();
    }

    private Iterator<AnswerState> generateAnswerIterator(){
        TransactionOLTP tx = query.tx();
        Set<Concept> startingPoints = new HashSet<>(getSubstitution().concepts());
        Pair<Variable, Variable> directionality = Iterables.getOnlyElement(this.query.getAtom().toRelationAtom().varDirectionality());
        Variable from = directionality.getKey();
        Variable to = directionality.getValue();


        return new LazyTarjanTC<>(startingPoints, null, (node) -> {
            ConceptMap sub = new ConceptMap(ImmutableMap.of(from, node));
            return tx.queryCache().getAnswerStream(query.withSubstitution(sub))
                    .map(ans -> ans.get(to));
        })
                .stream()
                .map(e -> new ConceptMap(
                        ImmutableMap.of(from, e.getKey(), to, e.getValue()),
                        new LookupExplanation(query.getPattern()))
                )
                .map(ans -> new AnswerState(ans, unifier, getParentState()))
                .iterator();

    }

    @Override
    public ResolutionState generateChildState() {
        long start = System.currentTimeMillis();
        AnswerState answerState = answerStateIterator.hasNext() ? answerStateIterator.next() : null;
        query.tx().profiler().updateTime(getClass().getSimpleName() + "::generateSubGoal", System.currentTimeMillis() - start);
        return answerState;
    }
}
