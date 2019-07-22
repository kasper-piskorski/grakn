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

import com.google.common.collect.Iterators;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.graql.reasoner.query.ReasonerAtomicQuery;
import grakn.core.graql.reasoner.query.ReasonerQueries;
import grakn.core.graql.reasoner.query.ReasonerQueryImpl;
import grakn.core.graql.reasoner.unifier.MultiUnifier;
import grakn.core.graql.reasoner.unifier.Unifier;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Query state corresponding to a conjunctive query (ReasonerQueryImpl) in the resolution tree.
 */
public class ConjunctiveState extends AnswerPropagatorState<ReasonerQueryImpl> {

    public ConjunctiveState(ReasonerQueryImpl q,
                            List<ConceptMap> subs,
                            MultiUnifier u,
                            AnswerPropagatorState parent,
                            Set<ReasonerAtomicQuery> visitedSubGoals) {
        super(q, subs, u, parent, visitedSubGoals);
    }

    @Override
    Iterator<ResolutionState> generateChildStateIterator() {
        return Iterators.concat(
                getSubstitutions().stream()
                        .map(sub -> ReasonerQueries.create(getQuery(), sub))
                        .map(q -> q.innerStateIterator(this, getVisitedSubGoals()))
                        .iterator()
        );
    }

    @Override
    ResolutionState propagateAnswer(AnswerState state) {
        List<ConceptMap> answers = consumeAnswers(state);
        return !answers.isEmpty() ? new AnswerState(answers, getMultiUnifier(), getParentState()) : null;
    }

    @Override
    List<ConceptMap> consumeAnswers(AnswerState state) {
        return state.getSubstitutions();
    }
}