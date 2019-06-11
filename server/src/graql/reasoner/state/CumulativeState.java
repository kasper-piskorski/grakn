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

import grakn.core.concept.answer.ConceptMap;
import grakn.core.concept.answer.Explanation;
import grakn.core.graql.reasoner.explanation.JoinExplanation;
import grakn.core.graql.reasoner.query.ReasonerAtomicQuery;
import grakn.core.graql.reasoner.query.ReasonerQueryImpl;
import grakn.core.graql.reasoner.unifier.Unifier;
import grakn.core.server.kb.concept.ConceptUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Query state corresponding to a an intermediate state obtained from decomposing a conjunctive query (ReasonerQueryImpl) in the resolution tree.
 */
public class CumulativeState extends QueryStateBase{

    private final LinkedList<ReasonerQueryImpl> subQueries;
    private final Iterator<ResolutionState> feederStateIterator;
    private final ReasonerQueryImpl query;

    public CumulativeState(List<ReasonerQueryImpl> qs,
                           ConceptMap sub,
                           Unifier u,
                           QueryStateBase parent,
                           Set<ReasonerAtomicQuery> subGoals) {
        super(sub, u, parent, subGoals);
        this.subQueries = new LinkedList<>(qs);

        this.query = subQueries.getFirst();
        //NB: we need lazy subGoal initialisation here, otherwise they are marked as visited before visit happens
        this.feederStateIterator = !subQueries.isEmpty()?
                subQueries.removeFirst().subGoals(sub, u, this, subGoals).iterator() :
                Collections.emptyIterator();
    }

    @Override
    public String toString(){
        return super.toString() +  "\n" +
                getSubstitution() + "\n" +
                query + "\n" +
                subQueries.stream().map(ReasonerQueryImpl::toString).collect(Collectors.joining("\n")) + "\n";
    }

    @Override
    public ResolutionState propagateAnswer(AnswerState state) {
        ConceptMap accumulatedAnswer = getSubstitution();
        ConceptMap toMerge = state.getSubstitution();
        ConceptMap answer = new ConceptMap(
                ConceptUtils.mergeAnswers(accumulatedAnswer, toMerge).map(),
                mergeExplanations(accumulatedAnswer, toMerge));

        if (answer.isEmpty()) return null;
        if (subQueries.isEmpty()) return new AnswerState(answer, getUnifier(), getParentState());
        return new CumulativeState(subQueries, answer, getUnifier(), getParentState(), getVisitedSubGoals());
    }

    @Override
    public ResolutionState generateSubGoal(){
        return feederStateIterator.hasNext()? feederStateIterator.next() : null;
    }

    @Override
    ConceptMap consumeAnswer(AnswerState state) {
        return state.getSubstitution();
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
