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

import ai.grakn.GraknTx;
import ai.grakn.concept.Entity;
import ai.grakn.graql.admin.Answer;
import ai.grakn.graql.admin.Conjunction;
import ai.grakn.graql.admin.Unifier;
import ai.grakn.graql.admin.VarPatternAdmin;
import ai.grakn.graql.internal.pattern.Patterns;
import ai.grakn.graql.internal.query.QueryAnswer;
import ai.grakn.graql.internal.reasoner.cache.LazyQueryCacheImpl;
import ai.grakn.graql.internal.reasoner.cache.QueryCacheImpl;
import ai.grakn.graql.internal.reasoner.iterator.LazyAnswerIterator;
import ai.grakn.graql.internal.reasoner.query.QueryAnswers;
import ai.grakn.graql.internal.reasoner.query.ReasonerAtomicQuery;
import ai.grakn.graql.internal.reasoner.query.ReasonerQueries;
import ai.grakn.kb.internal.EmbeddedGraknTx;
import ai.grakn.test.rule.SampleKBContext;
import ai.grakn.util.GraknTestUtil;
import com.google.common.collect.ImmutableMap;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ai.grakn.graql.Graql.var;
import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class QueryCacheTest {

    @ClassRule
    public static final SampleKBContext testContext = SampleKBContext.load("ruleApplicabilityTest.gql");

    private static EmbeddedGraknTx<?> graph;
    private static ReasonerAtomicQuery recordQuery;
    private static ReasonerAtomicQuery retrieveQuery;
    private static Answer singleAnswer;
    private static Unifier retrieveToRecordUnifier;
    private static Unifier recordToRetrieveUnifier;

    @Before
    public void onStartup(){
        assumeTrue(GraknTestUtil.usingTinker());
        graph = testContext.tx();
        String recordPatternString = "{(role1: $x, role2: $y) isa reifiable-relation;}";
        String retrievePatternString = "{(role1: $p1, role2: $p2) isa reifiable-relation;}";
        Conjunction<VarPatternAdmin> recordPattern = conjunction(recordPatternString, graph);
        Conjunction<VarPatternAdmin> retrievePattern = conjunction(retrievePatternString, graph);
        recordQuery = ReasonerQueries.atomic(recordPattern, graph);
        retrieveQuery = ReasonerQueries.atomic(retrievePattern, graph);
        retrieveToRecordUnifier = retrieveQuery.getMultiUnifier(recordQuery).getUnifier();
        recordToRetrieveUnifier = retrieveToRecordUnifier.inverse();

        Entity entity = graph.getEntityType("anotherNoRoleEntity").instances().findFirst().orElse(null);
        singleAnswer = new QueryAnswer(
                ImmutableMap.of(
                        var("x"), entity,
                        var("y"), entity
                ));
    }

    @Test
    public void recordRetrieveAnswers(){
        QueryCacheImpl<ReasonerAtomicQuery> cache = new QueryCacheImpl<>();
        QueryAnswers record = cache.record(recordQuery, new QueryAnswers(recordQuery.getQuery().execute()));
        assertEquals(record, cache.getAnswers(retrieveQuery).unify(retrieveToRecordUnifier));
        assertEquals(record, cache.getAnswers(recordQuery));
    }

    @Test
    public void recordUpdateRetrieveAnswers(){
        QueryCacheImpl<ReasonerAtomicQuery> cache = new QueryCacheImpl<>();
        cache.record(recordQuery, new QueryAnswers(recordQuery.getQuery().execute()));
        cache.recordAnswer(recordQuery, singleAnswer);
        assertTrue(cache.getAnswers(recordQuery).contains(singleAnswer));
        assertTrue(cache.getAnswers(retrieveQuery).contains(singleAnswer.unify(recordToRetrieveUnifier)));
    }

    @Test
    public void recordRetrieveAnswerStream(){
        QueryCacheImpl<ReasonerAtomicQuery> cache = new QueryCacheImpl<>();
        Set<Answer> record = cache.record(recordQuery, recordQuery.getQuery().stream()).collect(Collectors.toSet());
        assertEquals(record, cache.getAnswerStream(retrieveQuery).map(ans -> ans.unify(retrieveToRecordUnifier)).collect(Collectors.toSet()));
        assertEquals(record, cache.record(recordQuery, recordQuery.getQuery().stream()).collect(Collectors.toSet()));
    }

    @Test
    public void recordUpdateRetrieveAnswerStream(){
        QueryCacheImpl<ReasonerAtomicQuery> cache = new QueryCacheImpl<>();
        cache.record(recordQuery, recordQuery.getQuery().stream());
        cache.recordAnswer(recordQuery, singleAnswer);

        assertTrue(cache.getAnswerStream(recordQuery).anyMatch(ans -> ans.equals(singleAnswer)));
        assertTrue(cache.getAnswerStream(retrieveQuery).anyMatch(ans -> ans.equals(singleAnswer.unify(recordToRetrieveUnifier))));
    }

    @Test
    public void getRetrieveAnswerStream() {
        QueryCacheImpl<ReasonerAtomicQuery> cache = new QueryCacheImpl<>();
        Answer answer = recordQuery.getQuery().stream().findFirst().orElse(null);
        Answer retrieveAnswer = answer.unify(recordToRetrieveUnifier);

        Stream<Answer> recordStream = cache.getAnswerStream(recordQuery);
        Stream<Answer> retrieveStream = cache.getAnswerStream(retrieveQuery);

        QueryAnswers recordAnswers = new QueryAnswers(recordStream.collect(Collectors.toSet()));
        QueryAnswers retrieveAnswers = new QueryAnswers(retrieveStream.collect(Collectors.toSet()));

        assertTrue(recordAnswers.contains(answer));
        assertTrue(retrieveAnswers.contains(retrieveAnswer));
    }

    @Test
    public void getUpdateRetrieveAnswerStream() {
        QueryCacheImpl<ReasonerAtomicQuery> cache = new QueryCacheImpl<>();
        Answer answer = recordQuery.getQuery().stream().findFirst().orElse(null);
        Answer retrieveAnswer = answer.unify(recordToRetrieveUnifier);
        Answer retrieveSingleAnswer = singleAnswer.unify(recordToRetrieveUnifier);
        Stream<Answer> recordStream = cache.getAnswerStream(recordQuery);
        Stream<Answer> retrieveStream = cache.getAnswerStream(retrieveQuery);

        cache.recordAnswer(recordQuery, singleAnswer);

        QueryAnswers recordAnswers = new QueryAnswers(recordStream.collect(Collectors.toSet()));
        QueryAnswers retrieveAnswers = new QueryAnswers(retrieveStream.collect(Collectors.toSet()));

        //NB: not expecting the update in the stream
        assertTrue(recordAnswers.contains(answer));
        assertTrue(retrieveAnswers.contains(retrieveAnswer));
        assertFalse(recordAnswers.contains(singleAnswer));
        assertFalse(retrieveAnswers.contains(retrieveSingleAnswer));

        assertTrue(cache.getAnswers(recordQuery).contains(singleAnswer));
        assertTrue(cache.getAnswers(retrieveQuery).contains(retrieveSingleAnswer));
    }

    @Test
    public void recordRetrieveSingleAnswer(){
        QueryCacheImpl<ReasonerAtomicQuery> cache = new QueryCacheImpl<>();
        Answer answer = recordQuery.getQuery().stream().findFirst().orElse(null);
        Answer retrieveAnswer = answer.unify(recordToRetrieveUnifier);
        cache.recordAnswer(recordQuery, answer);

        assertEquals(cache.getAnswer(recordQuery, new QueryAnswer()), new QueryAnswer());
        assertEquals(cache.getAnswer(recordQuery, answer), answer);
        assertEquals(cache.getAnswer(recordQuery, retrieveAnswer), answer);

        assertEquals(cache.getAnswer(retrieveQuery, new QueryAnswer()), new QueryAnswer());
        assertEquals(cache.getAnswer(retrieveQuery, retrieveAnswer), retrieveAnswer);
        assertEquals(cache.getAnswer(retrieveQuery, answer), retrieveAnswer);
    }

    /**
     * ##################################
     *
     *      Lazy query cache tests
     *
     * ##################################
     */

    @Test
    public void lazilyRecordRetrieveAnswers(){
        LazyQueryCacheImpl<ReasonerAtomicQuery> cache = new LazyQueryCacheImpl<>();
        cache.record(recordQuery, recordQuery.getQuery().stream());

        Set<Answer> retrieve = cache.getAnswerStream(retrieveQuery).map(ans -> ans.unify(retrieveToRecordUnifier)).collect(toSet());
        Set<Answer> record = cache.getAnswerStream(recordQuery).collect(toSet());

        assertTrue(!retrieve.isEmpty());
        assertEquals(record, retrieve);
    }

    @Test
    public void lazilyRecordUpdateRetrieveAnswers(){
        LazyQueryCacheImpl<ReasonerAtomicQuery> cache = new LazyQueryCacheImpl<>();
        cache.record(recordQuery, recordQuery.getQuery().stream());
        cache.record(recordQuery, Stream.of(singleAnswer));

        Set<Answer> retrieve = cache.getAnswerStream(retrieveQuery).map(ans -> ans.unify(retrieveToRecordUnifier)).collect(toSet());
        Set<Answer> record = cache.getAnswerStream(recordQuery).collect(toSet());

        assertTrue(!retrieve.isEmpty());
        assertTrue(retrieve.contains(singleAnswer));
        assertEquals(record, retrieve);
    }

    @Test
    public void lazilyGetRetrieveAnswers() {
        LazyQueryCacheImpl<ReasonerAtomicQuery> cache = new LazyQueryCacheImpl<>();
        cache.record(recordQuery, recordQuery.getQuery().stream());
        LazyAnswerIterator retrieveIterator = cache.getAnswers(retrieveQuery);
        LazyAnswerIterator recordIterator = cache.getAnswers(recordQuery);

        Set<Answer> record = recordIterator.stream().collect(toSet());
        Set<Answer> retrieve = retrieveIterator.stream().map(ans -> ans.unify(retrieveToRecordUnifier)).collect(toSet());

        assertTrue(!retrieve.isEmpty());
        assertEquals(record, retrieve);
    }

    @Test
    public void lazilyGetUpdateRetrieveAnswers(){
        LazyQueryCacheImpl<ReasonerAtomicQuery> cache = new LazyQueryCacheImpl<>();
        Answer retrieveSingleAnswer = singleAnswer.unify(recordToRetrieveUnifier);
        cache.record(recordQuery, recordQuery.getQuery().stream());
        LazyAnswerIterator retrieveIterator = cache.getAnswers(retrieveQuery);
        LazyAnswerIterator recordIterator = cache.getAnswers(recordQuery);

        cache.record(recordQuery, Stream.of(singleAnswer));

        Set<Answer> record = recordIterator.stream().collect(toSet());
        Set<Answer> retrieve = retrieveIterator.stream().map(ans -> ans.unify(retrieveToRecordUnifier)).collect(toSet());

        assertTrue(!retrieve.isEmpty());
        assertTrue(!retrieve.contains(singleAnswer));
        assertEquals(record, retrieve);

        assertTrue(cache.getAnswers(recordQuery).stream().anyMatch(ans -> ans.equals(singleAnswer)));
        assertTrue(cache.getAnswers(retrieveQuery).stream().anyMatch(ans -> ans.equals(retrieveSingleAnswer)));
    }


    private Conjunction<VarPatternAdmin> conjunction(String patternString, GraknTx graph){
        Set<VarPatternAdmin> vars = graph.graql().parser().parsePattern(patternString).admin()
                .getDisjunctiveNormalForm().getPatterns()
                .stream().flatMap(p -> p.getPatterns().stream()).collect(toSet());
        return Patterns.conjunction(vars);
    }
}
