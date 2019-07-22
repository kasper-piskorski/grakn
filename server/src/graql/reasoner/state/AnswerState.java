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
import grakn.core.graql.reasoner.rule.InferenceRule;
import grakn.core.graql.reasoner.unifier.MultiUnifier;
import grakn.core.graql.reasoner.unifier.Unifier;
import java.util.List;

/**
 *
 * <p>
 * Resolution state holding an answer (ConceptMap) to the parent state.
 * </p>
 *
 *
 */
public class AnswerState extends ResolutionState {

    private final InferenceRule rule;
    private final MultiUnifier unifier;

    public AnswerState(List<ConceptMap> subs, MultiUnifier u, AnswerPropagatorState parent) {
        this(subs, u, parent, null);
    }

    AnswerState(List<ConceptMap> subs, MultiUnifier u, AnswerPropagatorState parent, InferenceRule rule) {
        super(subs, parent);
        this.unifier = u;
        this.rule = rule;
    }

    @Override
    public String toString(){
        return super.toString() + ": " + getSubstitutions() +
                (getParentState() != null? " to @" + Integer.toHexString(getParentState().hashCode()) : "") +
                (" with u: " + getMultiUnifier());
    }

    @Override
    public boolean isAnswerState(){ return true;}

    @Override
    public ResolutionState generateChildState() {
        return getParentState().propagateAnswer(this);
    }

    InferenceRule getRule(){ return rule;}

    MultiUnifier getMultiUnifier(){ return unifier;}
}
