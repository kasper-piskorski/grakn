/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2018 Grakn Labs Ltd
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

import com.google.common.collect.Iterables;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.concept.answer.Explanation;
import grakn.core.graql.reasoner.explanation.JoinExplanation;
import grakn.core.graql.reasoner.query.ReasonerAtomicQuery;
import grakn.core.graql.reasoner.query.ReasonerQueryImpl;
import grakn.core.graql.reasoner.unifier.MultiUnifier;
import grakn.core.graql.reasoner.unifier.Unifier;
import grakn.core.server.kb.concept.ConceptUtils;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Query state corresponding to a an intermediate state obtained from decomposing a conjunctive query (ReasonerQueryImpl) in the resolution tree.
 */
public class CumulativeState extends AnswerPropagatorState<ReasonerQueryImpl> {

    private final LinkedList<ReasonerQueryImpl> subQueries;

    public CumulativeState(List<ReasonerQueryImpl> qs,
                           List<ConceptMap> subs,
                           MultiUnifier u,
                           AnswerPropagatorState parent,
                           Set<ReasonerAtomicQuery> subGoals) {
        super(Iterables.getFirst(qs, null), subs, u, parent, subGoals);
        this.subQueries = new LinkedList<>(qs);
        subQueries.removeFirst();
    }

    @Override
    protected Iterator<ResolutionState> generateChildStateIterator() {
        //NB: we need lazy resolutionState initialisation here, otherwise they are marked as visited before visit happens
        return getSubstitutions().stream()
                        .flatMap(sub -> getQuery().expandedStates(sub, getMultiUnifier(), this, getVisitedSubGoals()))
                        .iterator();
    }


    @Override
    public String toString(){
        return super.toString() +  "\n" +
                getSubstitutions() + "\n" +
                getQuery() + "\n" +
                subQueries.stream().map(ReasonerQueryImpl::toString).collect(Collectors.joining("\n")) + "\n";
    }

    @Override
    public ResolutionState propagateAnswer(AnswerState state) {
        List<ConceptMap> accumulatedAnswers = getSubstitutions();
        List<ConceptMap> toMerge = state.getSubstitutions();
        ConceptMap answer = accumulatedAnswers.stream()
                new ConceptMap(
                ConceptUtils.mergeAnswers(accumulatedAnswer, toMerge).map(),
                mergeExplanations(accumulatedAnswer, toMerge));

        if (answer.isEmpty()) return null;
        if (subQueries.isEmpty()) return new AnswerState(answer, getMultiUnifier(), getParentState());
        return new CumulativeState(subQueries, answer, getMultiUnifier(), getParentState(), getVisitedSubGoals());
    }

    @Override
    List<ConceptMap> consumeAnswers(AnswerState state) {
        return state.getSubstitutions();
    }

    private static Explanation mergeExplanations(ConceptMap base, ConceptMap toMerge) {
        if (toMerge.isEmpty()) return base.explanation();
        if (base.isEmpty()) return toMerge.explanation();

        List<ConceptMap> partialAnswers = new ArrayList<>();
        if (base.explanation().isJoinExplanation()) partialAnswers.addAll(base.explanation().getAnswers());
        else partialAnswers.add(base);
        if (toMerge.explanation().isJoinExplanation()) partialAnswers.addAll(toMerge.explanation().getAnswers());
        else partialAnswers.add(toMerge);
        return new JoinExplanation(partialAnswers);
    }

}
