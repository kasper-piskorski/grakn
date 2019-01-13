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

package grakn.core.graql.reasoner.reasoning;

import com.google.common.collect.Sets;
import grakn.core.graql.answer.ConceptMap;
import grakn.core.graql.internal.reasoner.utils.ReasonerUtils;
import grakn.core.graql.query.GetQuery;
import grakn.core.graql.query.Graql;
import grakn.core.graql.query.pattern.Statement;
import grakn.core.graql.query.pattern.Variable;
import grakn.core.rule.GraknTestServer;
import grakn.core.server.Transaction;
import grakn.core.server.session.SessionImpl;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static grakn.core.graql.internal.Schema.ImplicitType.HAS;
import static grakn.core.graql.internal.Schema.ImplicitType.HAS_OWNER;
import static grakn.core.graql.internal.Schema.ImplicitType.HAS_VALUE;
import static grakn.core.graql.query.Graql.type;
import static grakn.core.graql.query.Graql.var;
import static grakn.core.util.GraqlTestUtil.assertCollectionsEqual;
import static grakn.core.util.GraqlTestUtil.loadFromFileAndCommit;
import static java.util.stream.Collectors.toSet;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Suite of tests checking different meanders and aspects of reasoning - full reasoning cycle is being tested.
 */
@SuppressWarnings("CheckReturnValue")
public class ResourceAttachmentIT {

    private static String resourcePath = "test-integration/graql/reasoner/stubs/";

    @ClassRule
    public static final GraknTestServer server = new GraknTestServer();

    private static SessionImpl resourceAttachmentSession;
    @BeforeClass
    public static void loadContext(){
        resourceAttachmentSession = server.sessionWithNewKeyspace();
        loadFromFileAndCommit(resourcePath, "resourceAttachment.gql", resourceAttachmentSession);
    }

    @AfterClass
    public static void closeSession(){
        resourceAttachmentSession.close();

    }

    @Test
    //Expected result: When the head of a rule contains resource assertions, the respective unique resources should be generated or reused.
    public void reusingResources_reattachingResourceToEntity() {
        try(Transaction tx = resourceAttachmentSession.transaction(Transaction.Type.WRITE)) {

            String queryString = "match $x isa genericEntity, has reattachable-resource-string $y; get;";
            List<ConceptMap> answers = tx.execute(Graql.<GetQuery>parse(queryString));
            String queryString2 = "match $x isa reattachable-resource-string; get;";
            List<ConceptMap> answers2 = tx.execute(Graql.<GetQuery>parse(queryString2));

            assertEquals(tx.getEntityType("genericEntity").instances().count(), answers.size());
            assertEquals(1, answers2.size());
        }
    }

    @Test
    //Expected result: When the head of a rule contains resource assertions, the respective unique resources should be generated or reused.
    public void reusingResources_queryingForGenericRelation() {
        try(Transaction tx = resourceAttachmentSession.transaction(Transaction.Type.WRITE)) {

            String queryString = "match $x isa genericEntity;($x, $y); get;";
            List<ConceptMap> answers = tx.execute(Graql.<GetQuery>parse(queryString));

            assertEquals(3, answers.size());
            assertEquals(2, answers.stream().filter(answer -> answer.get("y").isAttribute()).count());
        }
    }

    @Test
    //Expected result: When the head of a rule contains resource assertions, the respective unique resources should be generated or reused.
    public void reusingResources_usingExistingResourceToDefineSubResource() {
        try(Transaction tx = resourceAttachmentSession.transaction(Transaction.Type.WRITE)) {
                        String queryString = "match $x isa genericEntity, has subResource $y; get;";
            List<ConceptMap> answers = tx.execute(Graql.<GetQuery>parse(queryString));
            assertEquals(tx.getEntityType("genericEntity").instances().count(), answers.size());

            String queryString2 = "match $x isa subResource; get;";
            List<ConceptMap> answers2 = tx.execute(Graql.<GetQuery>parse(queryString2));
            assertEquals(1, answers2.size());
            assertTrue(answers2.iterator().next().get("x").isAttribute());

            String queryString3 = "match $x isa reattachable-resource-string; $y isa subResource;get;";
            List<ConceptMap> answers3 = tx.execute(Graql.<GetQuery>parse(queryString3));
            assertEquals(1, answers3.size());

            assertTrue(answers3.iterator().next().get("x").isAttribute());
            assertTrue(answers3.iterator().next().get("y").isAttribute());
        }
    }

    @Test
    public void whenReasoningWithResourcesInRelationForm_ResultsAreComplete() {
        try(Transaction tx = resourceAttachmentSession.transaction(Transaction.Type.WRITE)) {

            List<ConceptMap> concepts = tx.execute(Graql.<GetQuery>parse("match $x isa genericEntity;get;"));
            List<ConceptMap> subResources = tx.execute(Graql.<GetQuery>parse(
                    "match $x isa genericEntity has subResource $res; get;"));

            String queryString = "match " +
                    "$rel($role:$x) isa @has-reattachable-resource-string;" +
                    "$x isa genericEntity;" +
                    "get;";
            List<ConceptMap> answers = tx.execute(Graql.<GetQuery>parse(queryString));
            //base resource yield 3 roles: metarole, base attribute rule, specific role
            //subresources yield 4 roles: all the above + specialised role
            assertEquals(concepts.size() * 3 + subResources.size() * 4, answers.size());
            answers.forEach(ans -> assertEquals(3, ans.size()));
        }
    }

    //TODO leads to cache inconsistency
    @Ignore
    @Test
    public void whenReasoningWithResourcesWithRelationVar_ResultsAreComplete() {
        try(Transaction tx = resourceAttachmentSession.transaction(Transaction.Type.WRITE)) {

            Statement has = var("x").has("reattachable-resource-string", var("y"), var("r"));
            List<ConceptMap> answers = tx.execute(Graql.match(has).get());
            assertEquals(3, answers.size());
            answers.forEach(a -> assertTrue(a.vars().contains(new Variable("r"))));
        }
    }

    @Test
    public void whenExecutingAQueryWithImplicitTypes_InferenceHasAtLeastAsManyResults() {
        try(Transaction tx = resourceAttachmentSession.transaction(Transaction.Type.WRITE)) {

            Statement owner = type(HAS_OWNER.getLabel("reattachable-resource-string").getValue());
            Statement value = type(HAS_VALUE.getLabel("reattachable-resource-string").getValue());
            Statement hasRes = type(HAS.getLabel("reattachable-resource-string").getValue());

            GetQuery query = Graql.match(
                    var().rel(owner, "x").rel(value, "y").isa(hasRes),
                    var("a").has("reattachable-resource-string", var("b"))  // This pattern is added only to encourage reasoning to activate
            ).get();


            Set<ConceptMap> resultsWithoutInference = tx.stream(query,false).collect(toSet());
            Set<ConceptMap> resultsWithInference = tx.stream(query).collect(toSet());

            assertThat(resultsWithoutInference, not(empty()));
            assertThat(Sets.difference(resultsWithoutInference, resultsWithInference), empty());
        }
    }

    @Test
    //Expected result: When the head of a rule contains resource assertions, the respective unique resources should be generated or reused.
    public void reusingResources_attachingExistingResourceToARelation() {
        try(Transaction tx = resourceAttachmentSession.transaction(Transaction.Type.WRITE)) {

            String queryString = "match $x isa genericEntity, has reattachable-resource-string $y; $z isa relation; get;";
            List<ConceptMap> answers = tx.execute(Graql.<GetQuery>parse(queryString));
            assertEquals(2, answers.size());
            answers.forEach(ans ->
                    {
                        assertTrue(ans.get("x").isEntity());
                        assertTrue(ans.get("y").isAttribute());
                        assertTrue(ans.get("z").isRelationship());
                    }
            );

            String queryString2 = "match $x isa relation, has reattachable-resource-string $y; get;";
            List<ConceptMap> answers2 = tx.execute(Graql.<GetQuery>parse(queryString2));
            assertEquals(1, answers2.size());
            answers2.forEach(ans ->
                    {
                        assertTrue(ans.get("x").isRelationship());
                        assertTrue(ans.get("y").isAttribute());
                    }
            );
        }
    }

    @Test
    //Expected result: When the head of a rule contains resource assertions, the respective unique resources should be generated or reused.
    public void reusingResources_derivingResourceFromOtherResourceWithConditionalValue() {
        try(Transaction tx = resourceAttachmentSession.transaction(Transaction.Type.WRITE)) {
                        String queryString = "match $x has derived-resource-boolean $r; get;";
            List<ConceptMap> answers = tx.execute(Graql.<GetQuery>parse(queryString));
            assertEquals(1, answers.size());
        }
    }

    @Test
    //Expected result: When the head of a rule contains resource assertions, the respective unique resources should be generated or reused.
    public void reusingResources_attachingStrayResourceToEntityDoesntThrowErrors() {
        try(Transaction tx = resourceAttachmentSession.transaction(Transaction.Type.WRITE)) {
            String queryString = "match $x isa yetAnotherEntity, has derived-resource-string 'unattached'; get;";
            List<ConceptMap> answers = tx.execute(Graql.<GetQuery>parse(queryString));
            assertEquals(2, answers.size());
        }
    }

    @Test
    //Expected result: When the head of a rule contains resource assertions, the respective unique resources should be generated or reused.
    public void derivingResourceWithSpecificValue() {
        try(Transaction tx = resourceAttachmentSession.transaction(Transaction.Type.WRITE)) {
                        String queryString = "match $x has derived-resource-string 'value'; get;";
            String queryString2 = "match $x has derived-resource-string $r; get;";
            GetQuery query = Graql.parse(queryString);
            GetQuery query2 = Graql.parse(queryString2);
            List<ConceptMap> answers = tx.execute(query);
            List<ConceptMap> answers2 = tx.execute(query2);
            List<ConceptMap> requeriedAnswers = tx.execute(query);
            assertEquals(2, answers.size());
            assertEquals(4, answers2.size());
            assertEquals(answers.size(), requeriedAnswers.size());
            assertTrue(answers.containsAll(requeriedAnswers));
        }
    }

    @Test //Expected result: When the head of a rule contains resource assertions, the respective unique resources should be generated or reused.
    public void derivingResources_requireNotHavingSpecificValue() {
        try(Transaction tx = resourceAttachmentSession.transaction(Transaction.Type.WRITE)) {
            String queryString = "match " +
                    "$x has derived-resource-string $val !== 'unattached';" +
                    "get;";
            String queryStringVariant1 = "match " +
                    "$x has derived-resource-string $val;" +
                    "$val !== 'unattached'; get;";
            String queryStringVariant2 = "match " +
                    "$x has derived-resource-string $val;" +
                    "$unwanted 'unattached';" +
                    "$val !== $unwanted; get;";

            String complementQueryString = "match $x has derived-resource-string $val 'unattached'; get;";
            String completeQuerssyString = "match $x has derived-resource-string $val; get;";

            //List<ConceptMap> answers = tx.execute(Graql.<GetQuery>parse(queryString));
            //List<ConceptMap> answersPrime = tx.execute(Graql.<GetQuery>parse(queryStringVariant1));
            List<ConceptMap> answersBis = tx.execute(Graql.<GetQuery>parse(queryStringVariant2));

            ///List<ConceptMap> complement = tx.execute(Graql.<GetQuery>parse(complementQueryString));
            //List<ConceptMap> complete = tx.execute(Graql.<GetQuery>parse(completeQueryString));
            //List<ConceptMap> expectedAnswers = ReasonerUtils.listDifference(complete, complement);

           // assertCollectionsEqual(expectedAnswers, answers);
            //assertCollectionsEqual(expectedAnswers, answersPrime);
            //assertCollectionsEqual(expectedAnswers, answersBis);
        }
    }

    @Test //Expected result: When the head of a rule contains resource assertions, the respective unique resources should be generated or reused.
    public void derivingResources_requireValuesToBeDifferent() {
        try(Transaction tx = resourceAttachmentSession.transaction(Transaction.Type.WRITE)) {
            String queryString = "match " +
                    "$x has derived-resource-string $val;" +
                    "$y has reattachable-resource-string $anotherVal;" +
                    "$val !== $anotherVal;" +
                    "get;";

            tx.stream(Graql.<GetQuery>parse(queryString)).forEach(ans -> assertNotEquals(ans.get("val"), ans.get("anotherVal")));
        }
    }

    //TODO another bug here
    //@Ignore
    @Test //Expected result: When the head of a rule contains resource assertions, the respective unique resources should be generated or reused.
    public void derivingResources_requireAnEntityToHaveTwoDistinctResourcesOfNotAbstractType() {
        try(Transaction tx = resourceAttachmentSession.transaction(Transaction.Type.WRITE)) {
            String queryString = "match " +
                    "$x has derivable-resource-string $value;" +
                    "$x has derivable-resource-string $unwantedValue;" +
                    "$unwantedValue 'unattached';" +
                    "$value !== $unwantedValue;" +
                    "$value isa $type;" +
                    "$unwantedValue isa $type;" +
                    "$type != $unwantedType;" +
                    "$unwantedType label 'derivable-resource-string';" +
                    "get;";
            List<ConceptMap> execute = tx.execute(Graql.<GetQuery>parse(queryString));
        }
    }
}
