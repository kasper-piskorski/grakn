/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016-2018 Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/agpl.txt>.
 */

package ai.grakn.graql.internal.reasoner;

import ai.grakn.graql.admin.Answer;
import ai.grakn.graql.admin.QueryCache;
import ai.grakn.graql.admin.ReasonerQuery;
import ai.grakn.graql.internal.query.QueryAnswer;
import ai.grakn.graql.internal.reasoner.cache.QueryCacheImpl;
import ai.grakn.graql.internal.reasoner.iterator.ReasonerQueryIterator;
import ai.grakn.graql.internal.reasoner.query.ReasonerAtomicQuery;
import ai.grakn.graql.internal.reasoner.query.ReasonerQueryImpl;
import ai.grakn.graql.internal.reasoner.state.ResolutionState;
import ai.grakn.kb.internal.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Stack;

/**
 *
 * <p>
 * Iterator for query answers maintaining the iterative behaviour of the QSQ scheme.
 * </p>
 *
 * @author Kasper Piskorski
 *
 */
public class ResolutionIterator extends ReasonerQueryIterator {

    private int iter = 0;
    private long oldAns = 0;
    private final ReasonerQueryImpl query;
    private final Set<Answer> answers = new HashSet<>();

    //private final QueryCacheImpl<ReasonerAtomicQuery> cache;
    private final QueryCacheImpl<ReasonerAtomicQuery> cache = new QueryCacheImpl<>();

    private final Stack<ResolutionState> states = new Stack<>();

    private Answer nextAnswer = null;
    private final boolean reiterationRequired;

    private static final Logger LOG = LoggerFactory.getLogger(ReasonerQueryImpl.class);

    public ResolutionIterator(ReasonerQueryImpl q){
        this.query = q;
        this.reiterationRequired = q.requiresReiteration();

/*
        if (q.tx().getQueryCache() == null){
            QueryCacheImpl<ReasonerAtomicQuery> qc = new QueryCacheImpl<>();
            cache = qc;
            q.tx().initQueryCache(qc);
        } else {
            cache = (QueryCacheImpl<ReasonerAtomicQuery>) q.tx().getQueryCache();
        };
        */

        states.push(query.subGoal(new QueryAnswer(), new UnifierImpl(), null, new HashSet<>(), cache));
    }

    private Answer findNextAnswer(){
        while(!states.isEmpty()) {
            ResolutionState state = states.pop();

            if (state.isAnswerState() && state.isTopState()) {
                return state.getSubstitution();
            }

            ResolutionState newState = state.generateSubGoal();
            /*
            if (newState instanceof QueryState && ((QueryState) newState).getQuery().toString().contains("[grandchild: $x]")){
                System.out.println();
            }
            if (newState instanceof AnswerState && newState.getParentState() instanceof AtomicState){
                System.out.println();
            }
            if (newState instanceof AnswerState
                    && newState.getParentState() instanceof AtomicState
                    && ((QueryState) newState.getParentState()).getQuery().toString().contains("[granddaughter: $x]")){
                System.out.println();
            }
            */
            if (newState != null) {
                if (!state.isAnswerState()) states.push(state);
                states.push(newState);
            }
        }
        return null;
    }

    @Override
    public Answer next(){
        if (nextAnswer == null) throw new NoSuchElementException();
        answers.add(nextAnswer);
        return nextAnswer;
    }

    /**
     * check whether answers available, if answers not fully computed compute more answers
     * @return true if answers available
     */
    @Override
    public boolean hasNext() {
        nextAnswer = findNextAnswer();
        if (nextAnswer != null) return true;

        //iter finished
        if (reiterationRequired) {
            long dAns = answers.size() - oldAns;
            if (dAns != 0 || iter == 0) {
                LOG.debug("iter: " + iter + " answers: " + answers.size() + " dAns = " + dAns);
                iter++;
                states.push(query.subGoal(new QueryAnswer(), new UnifierImpl(), null, new HashSet<>(), cache));
                oldAns = answers.size();
                return hasNext();
            }
        }

        return false;
    }
}
