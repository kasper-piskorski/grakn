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

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import grakn.core.concept.Concept;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.graql.reasoner.atom.binary.RelationAtom;
import grakn.core.graql.reasoner.cache.SemanticDifference;
import grakn.core.graql.reasoner.explanation.LookupExplanation;
import grakn.core.graql.reasoner.query.ReasonerAtomicQuery;
import grakn.core.graql.reasoner.rule.InferenceRule;
import grakn.core.graql.reasoner.unifier.Unifier;
import grakn.core.graql.reasoner.utils.IterativeTarjanTC;
import grakn.core.graql.reasoner.utils.Pair;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.statement.Variable;
import java.util.Iterator;
import java.util.stream.Stream;

public class TransitiveClosureState extends ResolutionState {

    private final ReasonerAtomicQuery query;
    private final Unifier unifier;
    private final Iterator<AnswerState> answerStateIterator;
    private final InferenceRule rule;
    private final SemanticDifference semDiff;

    public TransitiveClosureState(InferenceRule rule, ConceptMap sub, Unifier u, SemanticDifference diff, AnswerPropagatorState parent) {
        super(sub, parent);
        this.query = rule.getHead();
        this.rule = rule;
        this.unifier = u;
        this.semDiff = diff;
        this.answerStateIterator = generateAnswerIterator();
    }

    private Stream<ConceptMap> queryAnswerStream(ReasonerAtomicQuery query, TransactionOLTP tx){
        return Stream.concat(
                tx.queryCache().getAnswerStream(query),
                query.equivalentAnswerStream()
        );
    }

    private Iterator<AnswerState> generateAnswerIterator(){
        HashMultimap<Concept, Concept> conceptGraph = HashMultimap.create();
        TransactionOLTP tx = query.tx();

        RelationAtom relationAtom = query.getAtom().toRelationAtom();
        Pair<Variable, Variable> varPair = Iterables.getOnlyElement(relationAtom.varDirectionality());
        queryAnswerStream(query, tx)
                .forEach(ans -> {
            Concept from = ans.get(varPair.getKey());
            Concept to = ans.get(varPair.getValue());
            conceptGraph.put(from, to);
        });

        return new IterativeTarjanTC<>(conceptGraph).stream().map(e -> new ConceptMap(
                        ImmutableMap.of(varPair.getKey(), e.getKey(), varPair.getValue(), e.getValue()),
                        new LookupExplanation(query.getPattern()))
                )
                .map(semDiff::apply).filter(ans -> !ans.isEmpty())
                .map(ans -> new AnswerState(ans, unifier, getParentState(), rule))
                .iterator();

    }

    @Override
    public ResolutionState generateChildState() {
        return answerStateIterator.hasNext() ? answerStateIterator.next() : null;
    }
}
