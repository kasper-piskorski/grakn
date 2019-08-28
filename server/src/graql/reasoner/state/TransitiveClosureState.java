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
import grakn.core.graql.reasoner.cache.IndexedAnswerSet;
import grakn.core.graql.reasoner.explanation.LookupExplanation;
import grakn.core.graql.reasoner.query.ReasonerAtomicQuery;
import grakn.core.graql.reasoner.unifier.Unifier;
import grakn.core.graql.reasoner.utils.IterativeTarjanTC;
import grakn.core.graql.reasoner.utils.Pair;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.statement.Variable;
import java.util.Iterator;

public class TransitiveClosureState extends ResolutionState {

    private final ReasonerAtomicQuery query;
    private final Unifier unifier;
    private final Iterator<AnswerState> answerStateIterator;

    public TransitiveClosureState(ReasonerAtomicQuery q, ConceptMap sub, Unifier u, AnswerPropagatorState parent) {
        super(sub, parent);
        this.query = q;
        this.unifier = u;
        this.answerStateIterator = generateAnswerIterator();
    }

    private Iterator<AnswerState> generateAnswerIterator(){
        HashMultimap<Concept, Concept> conceptGraph = HashMultimap.create();
        TransactionOLTP tx = query.tx();

        RelationAtom relationAtom = query.getAtom().toRelationAtom();
        Pair<Variable, Variable> varPair = Iterables.getOnlyElement(relationAtom.varDirectionality());
        IndexedAnswerSet answers = tx.queryCache().getEntry(query).cachedElement();
        answers.forEach(ans -> {
            Concept from = ans.get(varPair.getKey());
            Concept to = ans.get(varPair.getValue());
            conceptGraph.put(from, to);
        });
        
        return new IterativeTarjanTC<>(conceptGraph).stream()
                .map(e -> new ConceptMap(
                        ImmutableMap.of(varPair.getKey(), e.getKey(), varPair.getValue(), e.getValue()),
                        new LookupExplanation(query.getPattern()))
                )
                .map(ans -> new AnswerState(ans, unifier, getParentState()))
                .iterator();

    }

    @Override
    public ResolutionState generateChildState() {
        return answerStateIterator.hasNext() ? answerStateIterator.next() : null;
    }
}
