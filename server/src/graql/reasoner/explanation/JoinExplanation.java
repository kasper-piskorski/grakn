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

package grakn.core.graql.reasoner.explanation;

import grakn.core.concept.answer.ConceptMap;
import grakn.core.concept.answer.Explanation;
import grakn.core.graql.reasoner.utils.ReasonerUtils;
import graql.lang.pattern.Pattern;

import java.util.List;

/**
 * Explanation class for a join explanation - resulting from merging atoms in a conjunction.
 */
public class JoinExplanation extends Explanation {

    public JoinExplanation(List<ConceptMap> answers){ super(answers);}
    public JoinExplanation(Pattern queryPattern, List<ConceptMap> partialAnswers){
        super(queryPattern, partialAnswers);
    }

    @Override
    public JoinExplanation childOf(ConceptMap ans) {
        return new JoinExplanation(ReasonerUtils.listUnion(this.getAnswers(), ans.explanation().getAnswers()));
    }

    @Override
    public boolean isJoinExplanation(){ return true;}
}
