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
 *
 */

package grakn.core.graql.reasoner.explanation;

import grakn.core.concept.answer.ConceptMap;
import grakn.core.concept.answer.Explanation;
import grakn.core.kb.concept.api.ConceptId;
import grakn.core.kb.graql.reasoner.unifier.Unifier;
import graql.lang.pattern.Pattern;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Explanation class for rule application.
 */
public class RuleExplanation extends Explanation {

    private final ConceptId ruleId;
    private final Unifier unifier;
    private final Pattern ruleHeadPattern;

    public RuleExplanation(ConceptId ruleId, Unifier u, Pattern headPattern){
        this.ruleId = ruleId;
        this.unifier = u;
        this.ruleHeadPattern = headPattern;
    }

    private RuleExplanation(List<ConceptMap> answers, ConceptId ruleId, Unifier u, Pattern headPattern){
        super(answers);
        this.ruleId = ruleId;
        this.unifier = u;
        this.ruleHeadPattern = headPattern;
    }

    @Override
    public RuleExplanation childOf(ConceptMap ans) {
        Explanation explanation = ans.explanation();
        List<ConceptMap> answerList = new ArrayList<>(this.getAnswers());
        answerList.addAll(
                explanation.isLookupExplanation()?
                        Collections.singletonList(ans) :
                        explanation.getAnswers()
        );
        return new RuleExplanation(answerList, getRuleId(), getUnifier(), ruleHeadPattern());
    }

    @Override
    public boolean isRuleExplanation(){ return true;}

    public ConceptId getRuleId(){ return ruleId;}
    public Unifier getUnifier(){ return unifier;}
    public Pattern ruleHeadPattern(){ return ruleHeadPattern;}
}
