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

package grakn.core.graql.reasoner.cache;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.graql.reasoner.CacheCasting;
import grakn.core.graql.reasoner.atom.binary.RelationAtom;
import grakn.core.graql.reasoner.explanation.LookupExplanation;
import grakn.core.graql.reasoner.explanation.RuleExplanation;
import grakn.core.graql.reasoner.query.ReasonerAtomicQuery;
import grakn.core.graql.reasoner.query.ReasonerQueries;
import grakn.core.graql.reasoner.query.ReasonerQueryEquivalence;
import grakn.core.graql.reasoner.query.ReasonerQueryImpl;
import grakn.core.graql.reasoner.unifier.UnifierType;
import grakn.core.kb.concept.api.Concept;
import grakn.core.kb.concept.api.ConceptId;
import grakn.core.kb.concept.api.Entity;
import grakn.core.kb.concept.api.Relation;
import grakn.core.kb.graql.reasoner.cache.CacheEntry;
import grakn.core.kb.graql.reasoner.unifier.MultiUnifier;
import grakn.core.kb.server.Session;
import grakn.core.kb.server.Transaction;
import grakn.core.rule.GraknTestServer;
import graql.lang.Graql;
import graql.lang.pattern.Conjunction;
import graql.lang.query.GraqlGet;
import graql.lang.statement.Statement;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import static grakn.core.util.GraqlTestUtil.assertCollectionsNonTriviallyEqual;
import static grakn.core.util.GraqlTestUtil.loadFromFileAndCommit;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertNull;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertFalse;

@SuppressWarnings("CheckReturnValue")
public class QueryCacheIT {

    private static String resourcePath = "test-integration/graql/reasoner/resources/";

    @ClassRule
    public static final GraknTestServer server = new GraknTestServer();

    private static Session genericSchemaSession;

    @BeforeClass
    public static void loadContext() {
        genericSchemaSession = server.sessionWithNewKeyspace();
        loadFromFileAndCommit(resourcePath, "genericSchema.gql", genericSchemaSession);
    }

    @AfterClass
    public static void closeSession() {
        genericSchemaSession.close();
    }

    @Test
    public void whenRecordingAndMatchExists_entryIsUpdated(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = new MultilevelSemanticCache();
            ReasonerAtomicQuery query = ReasonerQueries.atomic(conjunction("(role: $x, role: $y) isa binary;"), tx);

            //mock answer
            ConceptMap specificAnswer = new ConceptMap(ImmutableMap.of(
                    Graql.var("x").var(), tx.getEntityType("entity").instances().iterator().next(),
                    Graql.var("y").var(), tx.getEntityType("entity").instances().iterator().next()),
                    new LookupExplanation(),
                    query.getPattern()
            );

            //record parent
            tx.execute(query.getQuery()).stream()
                    .map(ans -> ans.explain(new LookupExplanation(), query.getPattern()))
                    .filter(ans -> !ans.equals(specificAnswer))
                    .forEach(ans -> cache.record(query, ans));

            CacheEntry<ReasonerAtomicQuery, IndexedAnswerSet> cacheEntry = cache.getEntry(query);
            assertFalse(cacheEntry.cachedElement().contains(specificAnswer));
            cache.record(query, specificAnswer);

            assertTrue(cacheEntry.cachedElement().contains(specificAnswer));

            ReasonerAtomicQuery groundQuery = query.withSubstitution(specificAnswer);
            assertFalse(cache.getAnswers(groundQuery).isEmpty());
            assertTrue(cache.answersQuery(groundQuery));
        }
    }

    @Test
    public void whenRecordingAndMatchDoesntExist_answersArePropagatedFromParents(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = new MultilevelSemanticCache();
            Concept mConcept = tx.stream(Graql.<GraqlGet>parse("match $x has resource 'm';get;")).iterator().next().get("x");
            Concept sConcept = tx.stream(Graql.<GraqlGet>parse("match $x has resource 's';get;")).iterator().next().get("x");

            ReasonerAtomicQuery groundChildQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(subRole1: $x, subRole2: $y) isa binary;" +
                            "$x id " + mConcept.id().getValue() + ";" +
                            "$y id " + sConcept.id().getValue() + ";" +
                            "};"),
                    tx);

            //mock a specific answer
            ConceptMap specificAnswer = new ConceptMap(ImmutableMap.of(
                    Graql.var("x").var(), mConcept,
                    Graql.var("y").var(), sConcept),
                    new LookupExplanation(),
                    groundChildQuery.getPattern()
            );

            ReasonerAtomicQuery parentQuery = ReasonerQueries.atomic(conjunction("(role: $x, role: $y) isa binary;"), tx);

            //record parent answers omitting specific answer
            tx.execute(parentQuery.getQuery()).stream()
                    .map(ans -> ans.explain(new LookupExplanation(), parentQuery.getPattern()))
                    .filter(ans -> !ans.equals(specificAnswer))
                    .forEach(ans -> cache.record(parentQuery, ans));

            assertNull(cache.getEntry(groundChildQuery));
            cache.record(groundChildQuery, specificAnswer);
            CacheEntry<ReasonerAtomicQuery, IndexedAnswerSet> cacheEntry = cache.getEntry(groundChildQuery);
            assertEquals(
                    tx.stream(Graql.<GraqlGet>parse("match (subRole1: $x, subRole2: $y) isa binary; get;")).collect(toSet()),
                    cacheEntry.cachedElement().getAll()
            );
            assertFalse(cache.getAnswers(groundChildQuery).isEmpty());
            assertTrue(cache.answersQuery(groundChildQuery));
        }
    }

    @Test
    public void whenGettingAndMatchDoesntExist_answersFetchedFromDB(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = new MultilevelSemanticCache();

            ReasonerAtomicQuery childQuery = ReasonerQueries.atomic(conjunction("(subRole1: $x, subRole2: $y) isa binary;"), tx);
            Set<ConceptMap> cacheAnswers = cache.getAnswers(childQuery);
            assertEquals(tx.stream(childQuery.getQuery()).collect(toSet()), cacheAnswers);

            assertNull(cache.getEntry(childQuery));
        }
    }

    @Test
    public void whenGettingAndMatchDoesntExist_parentAvailable_answersFetchedFromParents(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = new MultilevelSemanticCache();

            ReasonerAtomicQuery parentQuery = ReasonerQueries.atomic(conjunction("(role: $x, role: $y) isa binary;"), tx);
            //record parent
            tx.execute(parentQuery.getQuery()).stream()
                    .map(ans -> ans.explain(new LookupExplanation(), parentQuery.getPattern()))
                    .forEach(ans -> cache.record(parentQuery, ans));
            cache.ackDBCompleteness(parentQuery);

            //retrieve child
            ReasonerAtomicQuery childQuery = ReasonerQueries.atomic(conjunction("(subRole1: $x, subRole2: $y) isa binary;"), tx);
            Set<ConceptMap> cacheAnswers = cache.getAnswers(childQuery);
            assertEquals(tx.stream(childQuery.getQuery()).collect(toSet()), cacheAnswers);
            assertEquals(cacheAnswers, cache.getEntry(childQuery).cachedElement().getAll());
        }
    }

    @Test
    public void whenGettingAndMatchDoesntExist_prospectiveParentCached_childQueriesAreEquivalent_answersFetchedFromDB(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = new MultilevelSemanticCache();

            ConceptId id = tx.getEntityType("subRoleEntity").instances().iterator().next().id();
            ConceptId dConcept = tx.stream(Graql.<GraqlGet>parse("match $d isa subSubRoleEntity, has resource 'd';get;")).iterator().next().get("d").id();
            ConceptId sConcept = tx.stream(Graql.<GraqlGet>parse("match $s isa subSubRoleEntity, has resource 's';get;")).iterator().next().get("s").id();

            ReasonerAtomicQuery parentQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(baseRole1: $x, baseRole2: $y) isa binary;" +
                            "$y id " + dConcept.getValue() + ";" +
                    "};"),
                    tx);

            //record parent, mark the answers to be explained by a rule so that we can distinguish them
            tx.execute(parentQuery.getQuery(), false).stream()
                    .map(ans -> ans.explain(new RuleExplanation(tx.getMetaRule().id()), parentQuery.getPattern()))
                    .forEach(ans -> cache.record(parentQuery, ans));

            //NB: WE ACK COMPLETENESS
            cache.ackDBCompleteness(parentQuery);

            //fetch a query that subsumes parent
            ReasonerAtomicQuery groundChildQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(baseRole1: $x, baseRole2: $y) isa binary;" +
                            "$x id " + id.getValue() + ";" +
                            "$y id " + dConcept.getValue() + ";" +
                            "};"),
                    tx);

            Set<ConceptMap> childAnswers = cache.getAnswers(groundChildQuery);
            assertEquals(tx.stream(groundChildQuery.getQuery(), false).collect(toSet()), childAnswers);
            assertTrue(cache.answersQuery(groundChildQuery));
            assertNotNull(cache.getEntry(groundChildQuery));
            childAnswers.forEach(ans -> assertTrue(ans.explanation().isRuleExplanation()));
            assertTrue(cache.isDBComplete(groundChildQuery));

            //fetch a different query, the query is structurally equivalent to the child query but
            //should have no parents in the cache so the answer needs to be fetched from the db
            ReasonerAtomicQuery anotherGroundChildQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(baseRole1: $x, baseRole2: $y) isa binary;" +
                            "$x id " + id.getValue() + ";" +
                            "$y id " + sConcept.getValue() + ";" +
                            "};"),
                    tx);

            Set<ConceptMap> anotherChildAnswers = cache.getAnswers(anotherGroundChildQuery);
            assertFalse(anotherChildAnswers.isEmpty());
            assertEquals(tx.stream(anotherGroundChildQuery.getQuery(), false).collect(toSet()), anotherChildAnswers);

            anotherChildAnswers.forEach(ans -> assertTrue(ans.explanation().isLookupExplanation()));
            assertFalse(cache.isDBComplete(anotherGroundChildQuery));
        }
    }

    @Test
    public void whenGettingAndMatchDoesntExist_prospectiveParentCached_childQueriesAreNotEquivalent_answersFetchedFromDB(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = new MultilevelSemanticCache();
            ConceptId fConcept = tx.getEntityType("subRoleEntity").instances().iterator().next().id();
            ConceptId mConcept = tx.stream(Graql.<GraqlGet>parse("match $m isa subSubRoleEntity, has resource 'm';get;")).iterator().next().get("m").id();
            ConceptId dConcept = tx.stream(Graql.<GraqlGet>parse("match $d isa subSubRoleEntity, has resource 'd';get;")).iterator().next().get("d").id();
            ConceptId sConcept = tx.stream(Graql.<GraqlGet>parse("match $s isa subSubRoleEntity, has resource 's';get;")).iterator().next().get("s").id();

            ReasonerAtomicQuery parentQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(baseRole1: $x, baseRole2: $y, baseRole3: $z) isa ternary;" +
                            "$z id " + dConcept.getValue() + ";" +
                            "};"),
                    tx);

            //record parent, mark the answers to be explained by a rule so that we can distinguish them
            tx.execute(parentQuery.getQuery(), false).stream()
                    .map(ans -> ans.explain(new LookupExplanation(), parentQuery.getPattern()))
                    .forEach(ans -> cache.record(parentQuery, ans));
            cache.ackDBCompleteness(parentQuery);

            //fetch a query that subsumes parent
            ReasonerAtomicQuery directChildQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(baseRole1: $x, baseRole2: $y, baseRole3: $z) isa ternary;" +
                            "$x id " + fConcept.getValue() + ";" +
                            "$z id " + dConcept.getValue() + ";" +
                            "};"),
                    tx);

            Set<ConceptMap> childAnswers = cache.getAnswers(directChildQuery);
            assertEquals(tx.stream(directChildQuery.getQuery(), false).collect(toSet()), childAnswers);

            assertNotNull(cache.getEntry(directChildQuery));
            assertTrue(cache.isDBComplete(directChildQuery));

            //fetch a different query that is not structurally equivalent to the child query,
            //consequently the query should have no parents in the cache so the answer needs to be fetched from the db
            ReasonerAtomicQuery groundIndirectChildQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(baseRole1: $x, baseRole2: $y, baseRole3: $z) isa ternary;" +
                            "$x id " + mConcept.getValue() + ";" +
                            "$y id " + mConcept.getValue() + ";" +
                            "$z id " + sConcept.getValue() + ";" +
                            "};"),
                    tx);

            Set<ConceptMap> groundChildAnswers = cache.getAnswers(groundIndirectChildQuery);

            assertFalse(groundChildAnswers.isEmpty());
            assertEquals(tx.stream(groundIndirectChildQuery.getQuery(), false).collect(toSet()), groundChildAnswers);
            assertNotNull(cache.getEntry(groundIndirectChildQuery));
            assertFalse(cache.isDBComplete(groundIndirectChildQuery));
        }
    }

    @Test
    public void whenGettingAndMatchDoesntExist_prospectiveParentNotComplete_childQueriesAreNotEquivalent_answersFetchedFromDB(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = new MultilevelSemanticCache();
            ConceptId fConcept = tx.getEntityType("subRoleEntity").instances().iterator().next().id();
            ConceptId mConcept = tx.stream(Graql.<GraqlGet>parse("match $m isa subSubRoleEntity, has resource 'm';get;")).iterator().next().get("m").id();
            ConceptId dConcept = tx.stream(Graql.<GraqlGet>parse("match $d isa subSubRoleEntity, has resource 'd';get;")).iterator().next().get("d").id();
            ConceptId sConcept = tx.stream(Graql.<GraqlGet>parse("match $s isa subSubRoleEntity, has resource 's';get;")).iterator().next().get("s").id();

            ReasonerAtomicQuery parentQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(baseRole1: $x, baseRole2: $y, baseRole3: $z) isa ternary;" +
                            "$z id " + dConcept.getValue() + ";" +
                            "};"),
                    tx);

            //record parent, mark the answers to be explained by a rule so that we can distinguish them
            tx.execute(parentQuery.getQuery(), false).stream()
                    .map(ans -> ans.explain(new LookupExplanation(), parentQuery.getPattern()))
                    .forEach(ans -> cache.record(parentQuery, ans));

            //fetch a query that subsumes parent
            ReasonerAtomicQuery childQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(baseRole1: $x, baseRole2: $y, baseRole3: $z) isa ternary;" +
                            "$x id " + fConcept.getValue() + ";" +
                            "$z id " + dConcept.getValue() + ";" +
                            "};"),
                    tx);

            Set<ConceptMap> childAnswers = cache.getAnswers(childQuery);
            assertEquals(tx.stream(childQuery.getQuery(), false).collect(toSet()), childAnswers);

            //since the parent is incomplete, no new entry will be created
            assertNull(cache.getEntry(childQuery));
            assertFalse(cache.isDBComplete(childQuery));

            //Fetch a different query that is not structurally equivalent to the child query.
            //The query has no entry in the cache but has a parent though, and although it is not dbComplete this query is ground so answers
            //should be fetched from parent and the query should be acked dbComplete.
            ReasonerAtomicQuery groundChildQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(baseRole1: $x, baseRole2: $y, baseRole3: $z) isa ternary;" +
                            "$x id " + fConcept.getValue() + ";" +
                            "$y id " + mConcept.getValue() + ";" +
                            "$z id " + dConcept.getValue() + ";" +
                            "};"),
                    tx);

            Set<ConceptMap> anotherChildAnswers = cache.getAnswers(groundChildQuery);
            assertFalse(anotherChildAnswers.isEmpty());
            assertEquals(tx.stream(groundChildQuery.getQuery(), false).collect(toSet()), anotherChildAnswers);
            assertNotNull(cache.getEntry(groundChildQuery));
            assertTrue(cache.answersQuery(groundChildQuery));
            assertTrue(cache.isDBComplete(groundChildQuery));
        }
    }

    @Test
    public void whenGettingAndMatchExists_queryNotGround_queryDBComplete_answersFetchedFromCache(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = new MultilevelSemanticCache();

            ReasonerAtomicQuery query = ReasonerQueries.atomic(conjunction("(subRole1: $x, subRole2: $y) isa binary;"), tx);
            //record
            tx.execute(query.getQuery()).stream()
                    .map(ans -> ans.explain(new LookupExplanation(), query.getPattern()))
                    .forEach(ans -> cache.record(query, ans));
            cache.ackDBCompleteness(query);

            //retrieve
            Set<ConceptMap> cacheAnswers = cache.getAnswers(query);
            assertEquals(tx.stream(query.getQuery()).collect(toSet()), cacheAnswers);
            assertEquals(cacheAnswers, cache.getEntry(query).cachedElement().getAll());
        }
    }

    @Test
    public void whenGettingAndMatchExists_queryNotGround_queryNotDBComplete_answersFetchedFromDBAndCache(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = new MultilevelSemanticCache();

            ReasonerAtomicQuery query = ReasonerQueries.atomic(conjunction("(subRole1: $x, subRole2: $y) isa binary;"), tx);
            //record
            tx.execute(query.getQuery()).stream()
                    .map(ans -> ans.explain(new LookupExplanation(), query.getPattern()))
                    .forEach(ans -> cache.record(query, ans));
            cache.ackDBCompleteness(query);

            //mock a rule explained answer
            ConceptMap inferredAnswer = new ConceptMap(ImmutableMap.of(
                    Graql.var("x").var(), tx.getEntityType("entity").instances().iterator().next(),
                    Graql.var("y").var(), tx.getEntityType("entity").instances().iterator().next()),
                    new RuleExplanation(tx.getMetaRule().id()),
                    query.getPattern()
            );
            cache.record(query, inferredAnswer);

            //retrieve
            Set<ConceptMap> cacheAnswers = cache.getAnswers(query);
            tx.stream(query.getQuery()).forEach(ans -> assertTrue(cacheAnswers.contains(ans)));
            assertTrue(cacheAnswers.contains(inferredAnswer));
            assertEquals(cacheAnswers, cache.getEntry(query).cachedElement().getAll());

            ReasonerAtomicQuery groundQuery = query.withSubstitution(inferredAnswer);
            assertFalse(cache.getAnswers(groundQuery).isEmpty());
            assertTrue(cache.answersQuery(groundQuery));
        }
    }

    @Test
    public void whenGettingAndMatchExistsAndAnswersFetchedFromDBAndCache_weDontReturnDuplicateAnswers(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = new MultilevelSemanticCache();
            Concept sConcept = tx.stream(Graql.<GraqlGet>parse("match $x has resource 's';get;")).iterator().next().get("x");
            Concept fConcept = tx.stream(Graql.<GraqlGet>parse("match $x has resource 'f';get;")).iterator().next().get("x");

            ReasonerAtomicQuery query = ReasonerQueries.atomic(conjunction("(subRole1: $x, subRole2: $y) isa binary;"), tx);

            ConceptMap dbAnswer = new ConceptMap(ImmutableMap.of(
                    Graql.var("x").var(), fConcept,
                    Graql.var("y").var(), sConcept)
            ).explain(new LookupExplanation(), query.getPattern());

            //record
            tx.execute(query.getQuery()).stream()
                    .map(ans -> ans.explain(new LookupExplanation(), query.getPattern()))
                    .filter(ans -> !ans.equals(dbAnswer))
                    .forEach(ans -> cache.record(query, ans));

            //mock a rule explained answer that is equal to a dbAnswer
            ConceptMap inferredAnswer = dbAnswer.explain(new RuleExplanation(tx.getMetaRule().id()), query.getPattern());
            cache.record(query, inferredAnswer);

            //retrieve
            List<ConceptMap> cacheAnswers = cache.getAnswerStream(query).collect(toList());
            assertCollectionsNonTriviallyEqual(cacheAnswers, new HashSet<>(cacheAnswers));
        }
    }

    @Test
    public void whenGettingAndMatchExists_queryGround_answerFound_answersFetchedFromCache(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = new MultilevelSemanticCache();

            ReasonerAtomicQuery parentQuery = ReasonerQueries.atomic(conjunction("(role: $x, role: $y) isa binary;"), tx);
            //record parent
            tx.stream(parentQuery.getQuery())
                    .map(ans -> ans.explain(new LookupExplanation(), parentQuery.getPattern()))
                    .forEach(ans -> cache.record(parentQuery, ans));
            cache.ackDBCompleteness(parentQuery);

            Concept mConcept = tx.stream(Graql.<GraqlGet>parse("match $x has resource 'm';get;")).iterator().next().get("x");
            Concept sConcept = tx.stream(Graql.<GraqlGet>parse("match $x has resource 's';get;")).iterator().next().get("x");
            Concept fConcept = tx.stream(Graql.<GraqlGet>parse("match $x has resource 'f';get;")).iterator().next().get("x");

            //record child
            ReasonerAtomicQuery preGroundChildQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(subRole1: $x, subRole2: $y) isa binary;" +
                            "$x id " + fConcept.id().getValue() + ";" +
                            "$y id " + sConcept.id().getValue() + ";" +
                            "};"),
                    tx);
            tx.stream(preGroundChildQuery.getQuery())
                    .map(ans -> ans.explain(new LookupExplanation(), preGroundChildQuery.getPattern()))
                    .forEach(ans -> cache.record(preGroundChildQuery, ans));

            //retrieve child
            ReasonerAtomicQuery groundChildQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                    "(subRole1: $x, subRole2: $y) isa binary;" +
                                "$x id " + mConcept.id().getValue() + ";" +
                                "$y id " + sConcept.id().getValue() + ";" +
                            "};"),
                    tx);

            //mock a specific answer
            ConceptMap specificAnswer = new ConceptMap(ImmutableMap.of(
                    Graql.var("x").var(), mConcept,
                    Graql.var("y").var(), sConcept),
                    new LookupExplanation(),
                    groundChildQuery.getPattern()
            );
            Set<ConceptMap> cacheAnswers = cache.getAnswers(groundChildQuery);
            assertEquals(tx.stream(groundChildQuery.getQuery()).collect(toSet()), cacheAnswers);
            assertTrue(cache.answersQuery(groundChildQuery));
            assertTrue(cacheAnswers.contains(specificAnswer));
        }
    }

    @Test
    public void whenGettingAndMatchExists_queryGround_queryDBComplete_answerNotFound_answersFetchedFromDbAndCache(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = new MultilevelSemanticCache();

            Concept mConcept = tx.stream(Graql.<GraqlGet>parse("match $x has resource 'm';get;")).iterator().next().get("x");
            Concept sConcept = tx.stream(Graql.<GraqlGet>parse("match $x has resource 's';get;")).iterator().next().get("x");
            Concept fConcept = tx.stream(Graql.<GraqlGet>parse("match $x has resource 'f';get;")).iterator().next().get("x");

            ReasonerAtomicQuery genericQuery = ReasonerQueries.atomic(conjunction("{(subRole1: $x, subRole2: $y) isa binary;};"), tx);
            ReasonerAtomicQuery parentQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(subRole1: $x, subRole2: $y) isa binary;" +
                            "$y id " + sConcept.id().getValue() + ";" +
                            "};"),
                    tx);

            tx.stream(genericQuery.getQuery(), false)
                    .filter(ans -> ans.get("y").equals(sConcept))
                    .map(ans -> ans.explain(new LookupExplanation(), parentQuery.getPattern()))
                    .forEach(ans -> cache.record(parentQuery, ans));

            //mock a rule explained answer
            ConceptMap inferredAnswer = new ConceptMap(
                    ImmutableMap.of(
                            Graql.var("x").var(), mConcept,
                            Graql.var("y").var(), fConcept
                    ),
                    new RuleExplanation(tx.getMetaRule().id()),
                    parentQuery.getPattern()
                    );
            cache.record(parentQuery, inferredAnswer);

            ReasonerAtomicQuery initialChildQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(subRole1: $x1, subRole2: $y1) isa binary;" +
                            "$x1 id " + mConcept.id().getValue() + ";" +
                            "$y1 id " + sConcept.id().getValue() + ";" +
                            "};"),
                    tx);
            ReasonerAtomicQuery childQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(subRole1: $x2, subRole2: $y2) isa binary;" +
                            "$x2 id " + mConcept.id().getValue() + ";" +
                            "$y2 id " + fConcept.id().getValue() + ";" +
                            "};"),
                    tx);

            tx.stream(initialChildQuery.getQuery(), false)
                    .map(ans -> ans.explain(new LookupExplanation(), initialChildQuery.getPattern()))
                    .forEach(ans -> cache.record(initialChildQuery, ans));

            cache.ackDBCompleteness(initialChildQuery);
            cache.ackDBCompleteness(childQuery);

            boolean firstChildSubsumesParent = initialChildQuery.subsumes(parentQuery);
            boolean secondChildSubsumesParent = childQuery.subsumes(parentQuery);
            boolean childSubsumesAnotherChild = initialChildQuery.subsumes(childQuery);
            boolean childrenEquivalent = ReasonerQueryEquivalence.StructuralEquivalence.equivalent(initialChildQuery, childQuery);
            //initialChildQuery.isEquivalent(childQuery);

            Set<ConceptMap> answers = cache.getAnswers(childQuery);
            MultiUnifier childToParentUnifier = childQuery.getMultiUnifier(parentQuery, UnifierType.STRUCTURAL_SUBSUMPTIVE);
            assertTrue(answers.stream().flatMap(childToParentUnifier::apply).anyMatch(ans -> ans.equals(inferredAnswer)));
        }
    }

    @Test
    public void whenGettingAndMatchExists_queryGround_queryNotDBComplete_answerNotFound_answersFetchedFromDbAndCache(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = new MultilevelSemanticCache();
            Concept mConcept = tx.stream(Graql.<GraqlGet>parse("match $x has resource 'm';get;")).iterator().next().get("x");
            Concept sConcept = tx.stream(Graql.<GraqlGet>parse("match $x has resource 's';get;")).iterator().next().get("x");
            Concept fConcept = tx.stream(Graql.<GraqlGet>parse("match $x has resource 'f';get;")).iterator().next().get("x");

            ReasonerAtomicQuery childQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(subRole1: $x, subRole2: $y) isa binary;" +
                            "$x id " + mConcept.id().getValue() + ";" +
                            "};"),
                    tx);

            //mock a specific answer
            ConceptMap specificAnswer = new ConceptMap(ImmutableMap.of(
                    Graql.var("x").var(), mConcept,
                    Graql.var("y").var(), sConcept),
                    new LookupExplanation(),
                    childQuery.getPattern()
            );
            //mock a rule explained answer
            ConceptMap inferredAnswer = new ConceptMap(ImmutableMap.of(
                    Graql.var("x").var(), mConcept,
                    Graql.var("y").var(), tx.getEntityType("entity").instances().iterator().next()),
                    new RuleExplanation(tx.getMetaRule().id()),
                    childQuery.getPattern()
            );

            //record child, we record child first so that answers do not get propagated
            ReasonerAtomicQuery preChildQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(subRole1: $x, subRole2: $y) isa binary;" +
                            "$x id " + fConcept.id().getValue() + ";" +
                            "};")
                    , tx);
            tx.stream(preChildQuery.getQuery())
                    .map(ans -> ans.explain(new LookupExplanation(), preChildQuery.getPattern()))
                    .forEach(ans -> cache.record(preChildQuery, ans));

            ReasonerAtomicQuery parentQuery = ReasonerQueries.atomic(conjunction("(role: $x, role: $y) isa binary;"), tx);
            //record parent
            tx.stream(parentQuery.getQuery())
                    .map(ans -> ans.explain(new LookupExplanation(), parentQuery.getPattern()))
                    .filter(ans -> !ans.equals(specificAnswer))
                    .filter(ans -> !ans.equals(inferredAnswer))
                    .forEach(ans -> cache.record(parentQuery, ans));
            cache.ackDBCompleteness(parentQuery);

            cache.record(childQuery, inferredAnswer);

            Set<ConceptMap> childCacheAnswers = cache.getAnswers(childQuery);
            assertEquals(
                    Stream.concat(
                            tx.stream(childQuery.getQuery()),
                            Stream.of(inferredAnswer)
                    ).collect(toSet()),
                    childCacheAnswers);
            assertTrue(childCacheAnswers.contains(specificAnswer));
        }
    }

    @Test
    public void whenExecutingSameQueryTwice_secondTimeWeFetchResultFromCache(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = new MultilevelSemanticCache();
            ReasonerAtomicQuery query = ReasonerQueries.atomic(conjunction("(role: $x, role: $y) isa binary;"), tx);
            //record parent
            Set<ConceptMap> answers = tx.execute(query.getQuery()).stream()
                    .map(ans -> ans.explain(new LookupExplanation(), query.getPattern()))
                    .peek(ans -> cache.record(query, ans))
                    .collect(toSet());
            cache.ackCompleteness(query);

            CacheEntry<ReasonerAtomicQuery, IndexedAnswerSet> match = cache.getEntry(query);

            assertNotNull(match);
            assertEquals(answers, match.cachedElement().getAll());
            answers.forEach(ans -> {
                ReasonerAtomicQuery groundQuery = query.withSubstitution(ans);
                assertFalse(cache.getAnswers(groundQuery).isEmpty());
                assertTrue(cache.answersQuery(groundQuery));
            });
        }
    }

    @Test
    public void whenFullyResolvingAQuery_allSubgoalsAreMarkedAsComplete(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = CacheCasting.queryCacheCast(tx.queryCache());
            ReasonerAtomicQuery query = ReasonerQueries.atomic(conjunction("(role: $x, role: $y) isa baseRelation;"), tx);

            query.resolve().collect(toSet());
            cache.queries().forEach(q -> assertEquals(cache.isDBComplete(q), cache.isComplete(q)));
        }
    }

    @Test
    public void whenResolvingASequenceOfQueries_onlyFullyResolvedSubgoalsAreMarkedAsComplete(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = CacheCasting.queryCacheCast(tx.queryCache());
            ReasonerAtomicQuery query = ReasonerQueries.atomic(conjunction("(symmetricRole: $x, symmetricRole: $y) isa binary-symmetric;"), tx);

            Set<ConceptMap> incompleteAnswers = query.resolve().limit(3).collect(toSet());
            Set<ReasonerAtomicQuery> incompleteQueries = cache.queries();
            //binary symmetric has no db entries
            assertTrue(cache.isDBComplete(query));
            Sets.difference(incompleteQueries, Collections.singleton(query))
                    .forEach(q -> {
                        assertFalse(cache.isDBComplete(q));
                        assertFalse(cache.isComplete(q));
            });

            query = ReasonerQueries.atomic(conjunction("(symmetricRole: $y, symmetricRole: $z) isa binary-trans;"), tx);
            Set<ConceptMap> answers = query.resolve().collect(toSet());
            Sets.difference(cache.queries(), Collections.singleton(query))
                    .forEach(q -> {
                        assertTrue(cache.isDBComplete(q));
                        assertEquals(cache.isDBComplete(q), cache.isComplete(q));
                    });
        }
    }

    @Test
    public void whenExecutingConjunctionWithPartialQueriesComplete_weExploitCache(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            ReasonerQueryImpl query = ReasonerQueries.create(conjunction(
                    "{" +
                            "$x isa baseEntity;" +
                            "$link isa baseEntity;" +
                            "(baseRole1: $x, baseRole2: $link) isa ternary;" +
                            "(baseRole2: $link, baseRole2: $y) isa ternary;" +
                            "(symmetricRole: $y, symmetricRole: $z) isa binary-symmetric;" +
                            "(symmetricRole: $z, symmetricRole: $w) isa binary-trans;" +
                            "};"
            ), tx);

            //record all partial queries
            query.getAtoms(RelationAtom.class)
                    .map(ReasonerQueries::atomic)
                    .forEach(q -> q.resolve(new HashSet<>()).collect(Collectors.toSet()));

            Set<ConceptMap> preFetchCache = getCacheContent(tx);

            assertTrue(query.isCacheComplete());
            Set<ConceptMap> answers = query.resolve(new HashSet<>()).collect(toSet());
            assertEquals(preFetchCache, getCacheContent(tx));
        }
    }

    @Test
    public void whenInstancesAreInserted_weUpdateCompleteness(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = CacheCasting.queryCacheCast(tx.queryCache());
            ReasonerAtomicQuery query = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(symmetricRole: $x, symmetricRole: $y) isa binary-trans;" +
                            "};"
            ), tx);

            List<ConceptMap> answers = tx.execute(query.getQuery());
            assertTrue(cache.isComplete(query));
            assertTrue(cache.isDBComplete(query));

            Entity entity = tx.getEntityType("anotherBaseRoleEntity").instances().iterator().next();

            tx.getRelationType("binary").create()
                    .assign(tx.getRole("baseRole1"), entity)
                    .assign(tx.getRole("baseRole2"), entity);

            assertFalse(cache.isComplete(query));
            assertFalse(cache.isDBComplete(query));

            ConceptMap newAnswer = new ConceptMap(ImmutableMap.of(Graql.var("x").var(), entity, Graql.var("y").var(), entity));
            List<ConceptMap> requeriedAnswers = tx.execute(query.getQuery());
            assertTrue(requeriedAnswers.contains(newAnswer));
        }
    }

    @Test
    public void whenInferredInstancesAreInserted_weDoNotUpdateCompleteness(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = CacheCasting.queryCacheCast(tx.queryCache());
            Entity entity = tx.getEntityType("anotherBaseRoleEntity").instances().iterator().next();
            ReasonerAtomicQuery query = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(baseRole1: $x, baseRole2: $y) isa binary;" +
                            "$x id " + entity.id().getValue() + ";" +
                            "$y id " + entity.id().getValue() + ";" +
                            "};"
            ), tx);

            cache.ackCompleteness(query);

            assertTrue(cache.isComplete(query));
            assertTrue(cache.isDBComplete(query));

            query.materialise(new ConceptMap()).collect(toList());

            assertTrue(cache.isComplete(query));
            assertTrue(cache.isDBComplete(query));
        }
    }

    @Test
    public void whenInstancesAreDeleted_weUpdateCompleteness(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = CacheCasting.queryCacheCast(tx.queryCache());
            Entity subRoleEntity = tx.getEntityType("subRoleEntity").instances().iterator().next();
            Entity anotherBaseRoleEntity = tx.getEntityType("anotherBaseRoleEntity").instances().iterator().next();
            ReasonerAtomicQuery query = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(symmetricRole: $x, symmetricRole: $y) isa binary-trans;" +
                            "};"
            ), tx);

            ReasonerAtomicQuery boundedQuery = ReasonerQueries.atomic(conjunction(
                    "{" +
                            "(symmetricRole: $x, symmetricRole: $y) isa binary-trans;" +
                            "$x id " + subRoleEntity.id() + ";" +
                            "};"
            ), tx);

            Relation relation = tx.getRelationType("binary").create()
                    .assign(tx.getRole("baseRole1"), anotherBaseRoleEntity)
                    .assign(tx.getRole("baseRole2"), anotherBaseRoleEntity);

            List<ConceptMap> answers = tx.execute(query.getQuery());
            List<ConceptMap> boundedAnswers = tx.execute(boundedQuery.getQuery());
            assertTrue(cache.isComplete(query));
            assertTrue(cache.isDBComplete(query));

            relation.delete();

            assertFalse(cache.isComplete(query));
            assertFalse(cache.isDBComplete(query));

            ConceptMap answer = new ConceptMap(ImmutableMap.of(Graql.var("x").var(), anotherBaseRoleEntity, Graql.var("y").var(), anotherBaseRoleEntity));
            List<ConceptMap> requeriedAnswers = tx.execute(query.getQuery());
            List<ConceptMap> requeriedBoundedAnswers = tx.execute(boundedQuery.getQuery());
            assertFalse(requeriedAnswers.contains(answer));
            assertCollectionsNonTriviallyEqual(boundedAnswers, requeriedBoundedAnswers);
        }
    }

    @Test
    public void whenRecordingQueryWithUniqueAnswer_weAckCompleteness(){
        try(Transaction tx = genericSchemaSession.readTransaction()) {
            MultilevelSemanticCache cache = CacheCasting.queryCacheCast(tx.queryCache());
            ReasonerQueryImpl baseQuery = ReasonerQueries.create(conjunction("{$x has resource $r via $rel;};"), tx);
            Set<ConceptMap> answers = baseQuery.resolve().collect(toSet());

            ConceptMap answer = answers.iterator().next();
            Concept owner = answer.get("x");
            Concept attribute = answer.get("r");
            Concept relation = answer.get("rel");
            Object value = attribute.asAttribute().value();

            ReasonerAtomicQuery ownerMapped = ReasonerQueries.atomic(conjunction("{$x has resource $r;$x id " + owner.id() + ";};"), tx);
            ReasonerAtomicQuery ownerAndValueMapped = ReasonerQueries.atomic(conjunction("{$x has resource '" + value + "';$x id " + owner.id() +";};"), tx);
            ReasonerAtomicQuery ownerAndValueMappedBaseType = ReasonerQueries.atomic(conjunction("{$x has attribute '" + value + "';$x id " + owner.id() +";};"), tx);
            ReasonerAtomicQuery ownerMappedWithRelVariable = ReasonerQueries.atomic(conjunction("{$x has resource $r via $rel;$x id " + owner.id() + ";};"), tx);
            ReasonerAtomicQuery ownerAndValueMappedWithRelVariable = ReasonerQueries.atomic(conjunction("{" +
                    "$x has resource '" + value + "' via $rel;" +
                    "$x id " + owner.id() + ";" +
                    "};"
            ), tx);
            ReasonerAtomicQuery ownerAndRelationMapped = ReasonerQueries.atomic(conjunction("{" +
                    "$x has resource $r via $rel;" +
                    "$x id " + owner.id() + ";" +
                    "$rel id " + relation.id() + ";" +
                    "};"
            ), tx);
            ReasonerAtomicQuery allMapped = ReasonerQueries.atomic(conjunction("{" +
                    "$x has resource $r;" +
                    "$x id " + owner.id() + ";" +
                    "$r id " + attribute.id() + ";" +
                    "};"
            ), tx);

            assertTrue(ownerAndValueMapped.hasUniqueAnswer());
            assertTrue(allMapped.hasUniqueAnswer());
            assertFalse(ownerMapped.hasUniqueAnswer());
            assertFalse(ownerAndValueMappedBaseType.hasUniqueAnswer());
            assertFalse(ownerMappedWithRelVariable.hasUniqueAnswer());
            assertFalse(ownerAndValueMappedWithRelVariable.hasUniqueAnswer());
            assertFalse(ownerAndRelationMapped.hasUniqueAnswer());

            ownerAndValueMapped.resolve()
                    .map(ans -> ans.explain(new LookupExplanation(), ownerAndValueMapped.getPattern()))
                    .forEach(ans -> cache.record(ownerAndValueMapped, ans));
            allMapped.resolve()
                    .map(ans -> ans.explain(new LookupExplanation(), allMapped.getPattern()))
                    .forEach(ans -> cache.record(allMapped, ans));

            assertTrue(cache.isComplete(ownerAndValueMapped));
            assertTrue(cache.isDBComplete(ownerAndValueMapped));
            assertTrue(cache.isComplete(allMapped));
            assertTrue(cache.isDBComplete(allMapped));
        }

    }

    private Set<ConceptMap> getCacheContent(Transaction tx){
        return CacheCasting.queryCacheCast(tx.queryCache()).queries().stream()
                .map(q -> CacheCasting.queryCacheCast(tx.queryCache()).getEntry(q))
                .flatMap(e -> e.cachedElement().getAll().stream())
                .collect(toSet());
    }

    private Conjunction<Statement> conjunction(String patternString) {
        Set<Statement> vars = Graql.parsePattern(patternString)
                .getDisjunctiveNormalForm().getPatterns()
                .stream().flatMap(p -> p.getPatterns().stream()).collect(toSet());
        return Graql.and(vars);
    }
}
