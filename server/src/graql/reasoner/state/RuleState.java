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
import grakn.core.graql.reasoner.rule.InferenceRule;
import grakn.core.graql.reasoner.unifier.Unifier;

import java.util.Iterator;
import java.util.Set;

/**
 *
 * <p>
 * Resolution state corresponding to a rule application.
 * </p>
 *
 *
 */
public class RuleState extends QueryStateBase{

    private final InferenceRule rule;
    private final Iterator<ResolutionState> bodyIterator;

    public RuleState(InferenceRule rule, ConceptMap sub, Unifier unifier, QueryStateBase parent, Set<ReasonerAtomicQuery> visitedSubGoals) {
        super(sub, unifier, parent, visitedSubGoals);
        //NB; sub gets propagated to the body here
        this.bodyIterator = Iterators.singletonIterator(rule.getBody().subGoal(sub, unifier, this, visitedSubGoals));
        this.rule = rule;
    }

    @Override
    public String toString(){
        return super.toString() + " to state @" + Integer.toHexString(getParentState().hashCode()) + "\n" +
                rule + "\n" +
                "Unifier: " + getUnifier();
    }

    @Override
    ResolutionState propagateAnswer(AnswerState state){
        ConceptMap answer = state.getAnswer();
        return !answer.isEmpty()? new AnswerState(answer, getUnifier(), getParentState(), rule) : null;
    }

    @Override
    public ResolutionState generateSubGoal() {
        return bodyIterator.hasNext() ? bodyIterator.next() : null;
    }

    @Override
    ConceptMap consumeAnswer(AnswerState state) {
        return state.getSubstitution();
    }
}
