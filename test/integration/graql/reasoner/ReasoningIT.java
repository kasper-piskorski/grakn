/*
 * Copyright (C) 2020 Grakn Labs
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

package grakn.core.graql.reasoner;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.kb.concept.api.Concept;
import grakn.core.kb.concept.api.Relation;
import grakn.core.kb.concept.api.Thing;
import grakn.core.kb.server.Session;
import grakn.core.kb.server.Transaction;
import grakn.core.test.rule.GraknTestServer;
import graql.lang.Graql;
import graql.lang.query.GraqlGet;
import graql.lang.statement.Variable;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static grakn.core.util.GraqlTestUtil.assertCollectionsNonTriviallyEqual;
import static grakn.core.util.GraqlTestUtil.loadFromFileAndCommit;
import static java.util.stream.Collectors.toSet;
import static org.hamcrest.Matchers.empty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Suite of tests checking different meanders and aspects of reasoning - full reasoning cycle is being tested.
 */
@SuppressWarnings({"CheckReturnValue", "Duplicates"})
public class ReasoningIT {

    @ClassRule
    public static final GraknTestServer server = new GraknTestServer();

    private static String resourcePath = "test/integration/graql/reasoner/stubs/";

    //The tests validate the correctness of the rule reasoning implementation w.r.t. the intended semantics of rules.
    //The ignored tests reveal some bugs in the reasoning algorithm, as they don't return the expected results,
    //as specified in the respective comments below.

    @Test
    public void test() {
        for(int i = 0; i < 50 ; i++) {
            try (Session session = server.sessionWithNewKeyspace()) {
                loadFromFileAndCommit(resourcePath, "debug-schema.gql", session);
                loadFromFileAndCommit(resourcePath, "debug-data-trim4.gql", session);

                try (Transaction tx = session.transaction(Transaction.Type.WRITE)) {
                    long start = System.currentTimeMillis();

                    List<ConceptMap> r2answers = tx.execute(Graql.parse("match $x isa relationship-2; get;")
                            .asGet(), false);

                    List<ConceptMap> r5answers = tx.execute(Graql.parse(
                            "match $x isa relationship-5; get;"
                    )
                            .asGet(), false);

                    List<ConceptMap> r8answers = tx.execute(Graql.parse("match $x isa relationship-8; get;")
                            .asGet(), false);


                    /*
                    List<ConceptMap> r4answers = tx.execute(Graql.parse(
                    "match " +
                    "(role-1: $var-7, role-7: $var-6) isa relationship-2;" +
                    "not { (role-5: $var-7, role-6: $var-8) isa relationship-5;}; get;").asGet(), false);
                    */

                    List<ConceptMap> answers = tx.execute(Graql.parse(
                            "match $thing isa relationship-4; get $thing;"
                            //"match (role-5: $x, role-6: $y) isa relationship-6; get;"
                            //"match (role-5: $x, role-6: $y) isa relationship-4; get;"
                    )
                            .asGet());
                    System.out.println("execution time: " + (System.currentTimeMillis() - start));

                    Set<Concept> concepts = answers.stream().flatMap(a -> a.concepts().stream()).collect(toSet());
                    System.out.println("concepts: " + concepts.size());
                    System.out.println("answers: " + answers.size());
                    //assertEquals(2979, answers.size());
                    //assertEquals(1629, answers.size());
                    //assertEquals(548, answers.size());
                    assertEquals(11, answers.size());

                }
            }
        }
    }


    @Test
    public void whenMaterialising_duplicatesAreNotCreated() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "duplicateMaterialisation.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.WRITE)) {
                List<ConceptMap> answers = tx.execute(Graql.parse(
                        "match " +
                                "$rel has inferredAttribute 'inferredRelation';" +
                                "get;")
                        .asGet());
                assertEquals(25, answers.size());
            }
        }
    }

    @Test //Expected result: Both queries should return a non-empty result, with $x/$y mapped to a unique entity.
    public void unificationOfReflexiveRelations() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "reflexiveRelation.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {

                String queryString = "match (role1:$x, role2:$x) isa relation1; get;";
                String queryString2 = "match (role1:$x, role2:$y) isa relation1; get;";
                List<ConceptMap> answers = tx.execute(Graql.parse(queryString).asGet());
                List<ConceptMap> answers2 = tx.execute(Graql.parse(queryString2).asGet());

                assertEquals(1, answers.size());
                answers.forEach(x -> assertEquals(1, x.size()));

                assertEquals(4, answers2.size());

                assertNotEquals(0, answers.size() * answers2.size());

                answers2.forEach(x -> assertEquals(2, x.size()));
            }
        }
    }

    @Test //Expected result: Both queries should return a non-empty result, with $x/$y mapped to a unique entity.
    public void unificationOfReflexiveSymmetricRelations() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "reflexiveSymmetricRelation.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {

                String queryString = "match (symmetricRole: $x, symmetricRole: $x) isa symmetricRelation; get;";
                String queryString2 = "match (symmetricRole: $x, symmetricRole: $y) isa symmetricRelation; get;";
                List<ConceptMap> answers = tx.execute(Graql.parse(queryString).asGet());
                List<ConceptMap> answers2 = tx.execute(Graql.parse(queryString2).asGet());

                assertEquals(2, answers.size());
                assertEquals(8, answers2.size());
                assertNotEquals(0, answers.size() * answers2.size());
                answers.forEach(x -> assertEquals(1, x.size()));
                answers2.forEach(x -> assertEquals(2, x.size()));
            }
        }
    }

    @Test //Expected result: The query should return 10 unique matches (no duplicates).
    public void whenResolutionProducesInfiniteStreamOfAnswers_executingLimitedQueryTerminates() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "testSet7.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {

                String queryString = "match $x isa relation1; get; limit 10;";
                List<ConceptMap> answers = tx.execute(Graql.parse(queryString).asGet());
                assertEquals(10, answers.size());
                assertEquals(tx.execute(Graql.parse(queryString).asGet(), false).size(), answers.size());
            }
        }
    }

    @Test //Expected result: The query should not return any matches (or possibly return a single match with $x=$y)
    public void roleUnificationWithRepeatingRoleTypes() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "testSet9.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {

                String doubleRpQuery = "match (role1:$x, role1:$y) isa relation2; get;";
                List<ConceptMap> answers = tx.execute(Graql.parse(doubleRpQuery).asGet());
                assertThat(answers, empty());

                String singleRpQuery = "match (role1:$x) isa relation2; get;";
                List<ConceptMap> answers2 = tx.execute(Graql.parse(singleRpQuery).asGet());
                assertEquals(1, answers2.size());
            }
        }
    }

    /**
     * recursive relation having same type for different role players
     * tests for handling recursivity and equivalence of queries and relations
     */
    @Test //Expected result: The query should return a unique match
    public void transRelationWithEntityGuardsAtBothEnds() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "testSet10.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {

                String queryString = "match (role1: $x, role2: $y) isa relation2; get;";
                List<ConceptMap> answers = tx.execute(Graql.parse(queryString).asGet());
                assertEquals(1, answers.size());
            }
        }
    }

    @Test //Expected result: The query should return a unique match
    public void transRelationWithRelationGuardsAtBothEnds() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "testSet11.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {

                String queryString = "match (role1:$x, role2:$y) isa relation3; get;";
                assertEquals(1, tx.execute(Graql.parse(queryString).asGet()).size());
            }
        }
    }

    @Test //Expected result: The query should return two unique matches
    public void circularRuleDependencies() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "testSet12.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {

                String queryString = "match (role1:$x, role2:$y) isa relation3; get;";
                List<ConceptMap> answers = tx.execute(Graql.parse(queryString).asGet());
                assertEquals(2, answers.size());
            }
        }
    }

    @Test
    public void resourcesAsRolePlayers() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "resourcesAsRolePlayers.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {


                String queryString = "match $x 'partial bad flag' isa resource; ($x, resource-owner: $y) isa resource-relation; get;";
                String queryString2 = "match $x 'partial bad flag 2' isa resource; ($x, resource-owner: $y) isa resource-relation; get;";
                String queryString3 = "match $x 'bad flag' isa resource ; ($x, resource-owner: $y) isa resource-relation; get;";
                String queryString4 = "match $x 'no flag' isa resource ; ($x, resource-owner: $y) isa resource-relation; get;";
                String queryString5 = "match $x isa resource; ($x, resource-owner: $y) isa resource-relation; get;";
                String queryString6 = "match $x isa resource; $x contains 'bad flag';($x, resource-owner: $y) isa resource-relation; get;";

                GraqlGet query = Graql.parse(queryString).asGet();
                GraqlGet query2 = Graql.parse(queryString2).asGet();
                GraqlGet query3 = Graql.parse(queryString3).asGet();
                GraqlGet query4 = Graql.parse(queryString4).asGet();
                GraqlGet query5 = Graql.parse(queryString5).asGet();
                GraqlGet query6 = Graql.parse(queryString6).asGet();


                List<ConceptMap> answers = tx.execute(query);
                List<ConceptMap> answers2 = tx.execute(query2);
                List<ConceptMap> answers3 = tx.execute(query3);
                List<ConceptMap> answers4 = tx.execute(query4);
                List<ConceptMap> answers5 = tx.execute(query5);
                List<ConceptMap> answers6 = tx.execute(query6);

                assertEquals(2, answers.size());
                assertEquals(1, answers2.size());
                assertEquals(1, answers3.size());
                assertEquals(1, answers4.size());
                assertEquals(answers.size() + answers2.size() + answers3.size() + answers4.size(), answers5.size());
                assertEquals(answers5.size() - answers4.size(), answers6.size());
            }
        }

    }

    @Test
    public void resourcesAsRolePlayers_vpPropagationTest() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "resourcesAsRolePlayers.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {


                String queryString = "match $x 'partial bad flag' isa resource; ($x, resource-owner: $y) isa another-resource-relation; get;";
                String queryString2 = "match $x 'partial bad flag 2' isa resource; ($x, resource-owner: $y) isa another-resource-relation; get;";
                String queryString3 = "match $x 'bad flag' isa resource ; ($x, resource-owner: $y) isa another-resource-relation; get;";
                String queryString4 = "match $x 'no flag' isa resource ; ($x, resource-owner: $y) isa another-resource-relation; get;";
                String queryString5 = "match $x isa resource; ($x, resource-owner: $y) isa another-resource-relation; get;";
                String queryString6 = "match $x isa resource; $x contains 'bad flag';($x, resource-owner: $y) isa another-resource-relation; get;";

                GraqlGet query = Graql.parse(queryString).asGet();
                GraqlGet query2 = Graql.parse(queryString2).asGet();
                GraqlGet query3 = Graql.parse(queryString3).asGet();
                GraqlGet query4 = Graql.parse(queryString4).asGet();
                GraqlGet query5 = Graql.parse(queryString5).asGet();
                GraqlGet query6 = Graql.parse(queryString6).asGet();

                List<ConceptMap> answers = tx.execute(query);
                List<ConceptMap> answers2 = tx.execute(query2);
                List<ConceptMap> answers3 = tx.execute(query3);
                List<ConceptMap> answers4 = tx.execute(query4);
                List<ConceptMap> answers5 = tx.execute(query5);
                List<ConceptMap> answers6 = tx.execute(query6);

                assertEquals(3, answers.size());
                assertEquals(3, answers2.size());
                assertEquals(3, answers3.size());
                assertEquals(3, answers4.size());
                assertEquals(answers5.size(), answers.size() + answers2.size() + answers3.size() + answers4.size());
                assertEquals(answers6.size(), answers5.size() - answers4.size());
            }
        }
    }

    @Test //Expected result: Returns db and inferred relations + their inverses and relations with self for all entities
    public void reasoningWithRepeatingRoles() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "testSet22.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {

                String queryString = "match (friend:$x1, friend:$x2) isa knows-trans; get;";
                List<ConceptMap> answers = tx.execute(Graql.parse(queryString).asGet());
                assertEquals(16, answers.size());
            }
        }
    }

    @Test //Expected result: The same set of results is always returned
    public void reasoningWithLimitHigherThanNumberOfResults_ReturnsConsistentResults() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "testSet23.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {

                String queryString = "match (friend1:$x1, friend2:$x2) isa knows-trans; get; limit 60;";
                List<ConceptMap> oldAnswers = tx.execute(Graql.parse(queryString).asGet());
                for (int i = 0; i < 5; i++) {
                    List<ConceptMap> answers = tx.execute(Graql.parse(queryString).asGet());
                    assertEquals(6, answers.size());
                    assertCollectionsNonTriviallyEqual(oldAnswers, answers);
                }
            }
        }
    }

    @Test //Expected result: Relations between all entity instances including relation between each instance and itself
    public void reasoningWithEntityTypes() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "testSet24.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {

                String reflexiveQuery = "match (role1:$x1, role2:$x2) isa reflexiveRelation; get;";
                List<ConceptMap> reflexive = tx.execute(Graql.parse(reflexiveQuery).asGet());
                assertEquals(9, reflexive.size());

                String uniquePairQuery = "match (role1:$x1, role2:$x2) isa uniquePairRelation; get;";
                List<ConceptMap> uniquePairs = tx.execute(Graql.parse(uniquePairQuery).asGet());
                assertEquals(6, uniquePairs.size());
            }
        }
    }

    @Test //Expected result: Timeline is correctly recognised via applying resource comparisons in the rule body
    public void reasoningWithResourceValueComparison() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "testSet25.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {

                String queryString = "match (predecessor:$x1, successor:$x2) isa message-succession; get;";
                List<ConceptMap> answers = tx.execute(Graql.parse(queryString).asGet());
                assertEquals(10, answers.size());
            }
        }
    }

    //tests if partial substitutions are propagated correctly - atom disjointness may lead to variable loss (bug #15476)
    @Test //Expected result: 2 relations obtained by correctly finding relations
    public void reasoningWithRelations() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "testSet26.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {

                String queryString = "match (role1: $x1, role2: $x2) isa relation2; get;";
                List<ConceptMap> answers = tx.execute(Graql.parse(queryString).asGet());
                assertEquals(2, answers.size());

                String queryString2 = "match " +
                        "$b isa entity2;" +
                        "$b has res1 'value';" +
                        "$rel1 has res2 'value1';" +
                        "$rel1 (role1: $p, role2: $b) isa relation1;" +
                        "$rel2 has res2 'value2';" +
                        "$rel2 (role1: $c, role2: $b) isa relation1; get;";
                List<ConceptMap> answers2 = tx.execute(Graql.parse(queryString2).asGet());
                assertEquals(2, answers2.size());
                Set<Variable> vars = Sets.newHashSet(new Variable("b"),
                        new Variable("p"),
                        new Variable("c"),
                        new Variable("rel1"),
                        new Variable("rel2"));
                answers2.forEach(ans -> assertTrue(ans.vars().containsAll(vars)));
            }
        }
    }

    @Test //Expected result: number of answers equal to specified limit (no duplicates produced)
    public void whenReasoningWithRelationConjunctions_duplicatesNotProducesAndTypesInferredCorrectly() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "testSet28.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {

                String queryWithTypes = "match " +
                        "(role1: $x, role2: $y);" +
                        "(role1: $y, role2: $z);" +
                        "(role3: $z, role4: $w) isa relation3;" +
                        "get; limit 3;";

                assertEquals(3, tx.execute(Graql.parse(queryWithTypes).asGet()).size());

                String typeAmbiguousQuery = "match " +
                        "(role1: $x, role2: $y) isa relation1;" +
                        "(role1: $y, role2: $z) isa relation1;" +
                        "(role3: $z, role4: $w) isa relation3; get;";

                assertThat(tx.execute(Graql.parse(typeAmbiguousQuery).asGet()), empty());
            }
        }
    }

    @Test
    public void relationTypesAreCorrectlyInferredInConjunction_TypesAreAbsent() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "testSet28b.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {

                String queryString = "match " +
                        "$a isa entity1;" +
                        "($a, $b); $b isa entity3;" +
                        "($b, $c);" +
                        "get;";

                List<ConceptMap> answers = tx.execute(Graql.parse(queryString).asGet());
                assertEquals(4, answers.size());
                answers.forEach(ans -> assertEquals(3, ans.size()));
            }
        }
    }

    @Test
    public void relationTypesAreCorrectlyInferredInConjunction_TypesAreAbsent_DisconnectedQuery() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "testSet28b.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {

                String pattern = "{$a isa entity1;($a, $b); $b isa entity3;};";
                String pattern2 = "{($c, $d);};";
                String queryString = "match " +
                        pattern +
                        pattern2 +
                        "get;";
                List<ConceptMap> partialAnswers = tx.execute(Graql.match(Graql.parsePatternList(pattern)).get());

                //single relation that satisfies the types
                assertEquals(1, partialAnswers.size());

                List<ConceptMap> partialAnswers2 = tx.execute(Graql.match(Graql.parsePatternList(pattern2)).get());
                //(4 db relations  + 1 inferred) x 2 for variable swap
                assertEquals(10, partialAnswers2.size());

                //1 relation satisfying ($a, $b) with types x (4 db relations + 1 inferred) x 2 for var change
                List<ConceptMap> answers = tx.execute(Graql.parse(queryString).asGet());
                assertEquals(answers.size(), partialAnswers.size() * partialAnswers2.size());
                answers.forEach(ans -> assertEquals(4, ans.size()));
            }
        }
    }

    /**
     * Should find the possible relation configurations:
     * (x, z) - (z, z1) - (z1, z)
     * - (z, z2) - (z2, z)
     * - (z, y)  - { (y,z) (y, x) }
     * - (z, x)  - { res, (x, y), (x, z) }
     */
    @Test
    public void relationTypesAreCorrectlyInferredInConjunction_TypesAreAbsent_WithRelationWithoutAnyBounds() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "testSet28b.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {

                String entryPattern = "{" +
                        "$a isa entity1;" +
                        "($a, $b);" +
                        "};";

                List<ConceptMap> entryAnswers = tx.execute(Graql.match(Graql.parsePatternList(entryPattern)).get());
                assertEquals(2, entryAnswers.size());

                String partialPattern = "{" +
                        "$a isa entity1;" +
                        "($a, $b); $b isa entity3;" +
                        "($b, $c);" +
                        "};";

                List<ConceptMap> partialAnswers = tx.execute(Graql.match(Graql.parsePatternList(partialPattern)).get());
                assertEquals(4, partialAnswers.size());
                String queryString = "match " +
                        partialPattern +
                        "($c, $d);" +
                        "get;";

                List<ConceptMap> answers = tx.execute(Graql.parse(queryString).asGet());
                assertEquals(6, answers.size());
                answers.forEach(ans -> assertEquals(4, ans.size()));
            }
        }
    }

    @Test
    public void whenAppendingRolePlayers_queryIsRewrittenCorrectly() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "appendingRPs.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {
                List<ConceptMap> persistedRelations = tx.execute(Graql.parse("match $r isa baseRelation; get;").asGet(), false);

                List<ConceptMap> answers = tx.execute(Graql.<GraqlGet>parse("match (someRole: $x, anotherRole: $y, anotherRole: $z, inferredRole: $z); $y != $z;get;"));
                assertEquals(1, answers.size());

                List<ConceptMap> answers2 = tx.execute(Graql.<GraqlGet>parse("match (someRole: $x, yetAnotherRole: $y, andYetAnotherRole: $y, inferredRole: $z); get;"));
                assertEquals(1, answers2.size());

                List<ConceptMap> answers3 = tx.execute(Graql.<GraqlGet>parse("match " +
                        "(someRole: $x, inferredRole: $z); " +
                        "not {(anotherRole: $z);};" +
                        "get;"));

                assertTrue(answers3.isEmpty());

                List<ConceptMap> answers4 = tx.execute(Graql.<GraqlGet>parse("match " +
                        "$r (someRole: $x, inferredRole: $z); " +
                        "not {$r (anotherRole: $z);};" +
                        "get;"));
                assertEquals(2, answers4.size());

                List<ConceptMap> answers5 = tx.execute(Graql.<GraqlGet>parse("match " +
                        "$r (someRole: $x, inferredRole: $z); " +
                        "not {$r (yetAnotherRole: $y, andYetAnotherRole: $y);};" +
                        "get;"));
                assertEquals(2, answers5.size());

                assertEquals("New relations were created!", persistedRelations, tx.execute(Graql.parse("match $r isa baseRelation; get;").asGet(), false));
            }
        }
    }

    @Test
    public void whenAppendingRolePlayers_DuplicatesAreCreated() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "appendingRPs.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {
                List<ConceptMap> answers = tx.execute(Graql.parse("match " +
                        "$r (anotherRole: $x, anotherRole: $x) isa baseRelation;get;").asGet());
                assertEquals(1, answers.size());
                answers.forEach(answer -> {
                    Relation baseRelation = answer.get("r").asRelation();
                    Thing player = answer.get("x").asThing();
                    List<Thing> identicalRolePlayers = baseRelation.rolePlayers(tx.getRole("anotherRole"))
                            .filter(thing -> thing.equals(player))
                            .collect(Collectors.toList());
                    assertTrue(identicalRolePlayers.size() == 2);
                });
            }
        }
    }

    @Test
    public void whenCopyingRolePlayer_DuplicateRoleRetrievedCorrectly() {
        try (Session session = server.sessionWithNewKeyspace()) {
            try (Transaction tx = session.transaction(Transaction.Type.WRITE)) {
                // copies a role player to also play another role
                tx.execute(Graql.parse("define " +
                        "baseEntity sub entity, plays someRole, plays anotherRole; " +
                        "baseRelation sub relation, relates someRole, relates anotherRole;" +
                        "duplicateRole-CopyPlayer sub rule, " +
                        "when { " +
                        "    $r (someRole: $y, anotherRole: $z) isa baseRelation; " +
                        "}, " +
                        "then { " +
                        "    $r (anotherRole: $y) isa baseRelation; " +
                        "};").asDefine());
                tx.execute(Graql.parse("insert $x isa baseEntity; $y isa baseEntity;" +
                        " (someRole: $x, anotherRole: $y) isa baseRelation; ").asInsert());
                tx.commit();
            }

            try (Transaction tx = session.transaction(Transaction.Type.WRITE)) {
                List<ConceptMap> answers = tx.execute(Graql.parse("match $r (anotherRole: $x, anotherRole: $y) isa baseRelation; get;").asGet());
                assertEquals(2, answers.size());
            }
        }
    }

    @Test //when rule are defined to append new RPs no new relation instances should be created
    public void whenAppendingRolePlayers_noNewRelationsAreCreated() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "appendingRPs.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {
                List<ConceptMap> persistedRelations = tx.execute(Graql.parse("match $r isa baseRelation; get;").asGet(), false);
                List<ConceptMap> inferredRelations = tx.execute(Graql.parse("match $r isa baseRelation; get;").asGet());
                assertCollectionsNonTriviallyEqual("New relations were created!", persistedRelations, inferredRelations);

                Set<ConceptMap> variants = Stream.of(
                        Iterables.getOnlyElement(tx.execute(Graql.<GraqlGet>parse("match $r (someRole: $x, anotherRole: $y, anotherRole: $z, inferredRole: $z); $y != $z;get;"), false)),
                        Iterables.getOnlyElement(tx.execute(Graql.<GraqlGet>parse("match $r (someRole: $x, inferredRole: $z ); $x has resource 'value'; get;"), false)),
                        Iterables.getOnlyElement(tx.execute(Graql.<GraqlGet>parse("match $r (someRole: $x, yetAnotherRole: $y, andYetAnotherRole: $y, inferredRole: $z); get;"), false)),
                        Iterables.getOnlyElement(tx.execute(Graql.<GraqlGet>parse("match $r (anotherRole: $x, andYetAnotherRole: $y); get;"), false))
                )
                        .map(ans -> ans.project(Sets.newHashSet(new Variable("r"))))
                        .collect(toSet());

                assertCollectionsNonTriviallyEqual("Rules are not matched correctly!", variants, inferredRelations);
            }
        }
    }

    @Test //when rule are defined to append new RPs no new relation instances should be created
    public void whenAppendingRolePlayers_whenHeadRelationHasSymmetricRoles_answersContainAllPermutations() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "appendingRPs.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {
                List<ConceptMap> derivedRPTriples = tx.execute(Graql.<GraqlGet>parse("match (inferredRole: $x, inferredRole: $y, inferredRole: $z) isa derivedRelation; get;"));
                List<ConceptMap> derivedRelations = tx.execute(Graql.<GraqlGet>parse("match $r (inferredRole: $x, inferredRole: $y, inferredRole: $z) isa derivedRelation; get;"));

                //NB: same answer is obtained from both rules
                //three symmetric roles hence 3! results
                assertEquals("Rule body is not rewritten correctly!", 6, derivedRPTriples.size());
                assertEquals("Rule body is not rewritten correctly!", 6, derivedRelations.size());
                assertEquals(1, derivedRelations.stream().map(ans -> ans.get("r")).collect(toSet()).size());
            }
        }
    }

    @Test //tests whether shared resources are recognised correctly
    public void inferrableRelationWithRolePlayersSharingResource() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "testSet29.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {

                String queryString = "match " +
                        "(role1: $x, role2: $y) isa binary-base;" +
                        "$x has name $n;" +
                        "$y has name $n;" +
                        "get;";

                String queryString2 = "match " +
                        "(role1: $x, role2: $y) isa binary-base;" +
                        "$x has name $n;" +
                        "$y has name $n;" +
                        "$n == 'a';" +
                        "get;";

                String queryString3 = "match " +
                        "(role1: $x, role2: $y) isa binary-base;" +
                        "$x has name 'a';" +
                        "$y has name 'a';" +
                        "get;";

                List<ConceptMap> answers = tx.execute(Graql.parse(queryString).asGet());
                List<ConceptMap> answers2 = tx.execute(Graql.parse(queryString2).asGet());
                List<ConceptMap> answers3 = tx.execute(Graql.parse(queryString3).asGet());

                assertEquals(3, answers.size());
                answers.forEach(ans -> {
                    assertEquals(3, ans.size());
                    assertEquals(ans.get("x"), ans.get("y"));
                });

                assertEquals(1, answers2.size());
                assertEquals(1, answers3.size());
                answers2.stream()
                        .map(a -> a.project(Sets.newHashSet(new Variable("x"), new Variable("y"))))
                        .forEach(a -> assertTrue(answers3.contains(a)));
            }
        }
    }

    @Test
    public void ternaryRelationsRequiringDifferentMultiunifiers() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "testSet29.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {


                String queryString = "match " +
                        "(role1: $a, role2: $b, role3: $c) isa ternary-base;" +
                        "get;";

                String queryString2 = "match " +
                        "(role: $a, role2: $b, role: $c) isa ternary-base;" +
                        "$b has name 'b';" +
                        "get;";

                String queryString3 = "match " +
                        "($r: $a) isa ternary-base;" +
                        "get;";

                String queryString4 = "match " +
                        "($r: $b) isa ternary-base;" +
                        "$b has name 'b';" +
                        "get;";

                List<ConceptMap> answers = tx.execute(Graql.parse(queryString).asGet());
                assertEquals(27, answers.size());

                List<ConceptMap> answers2 = tx.execute(Graql.parse(queryString2).asGet());
                assertEquals(9, answers2.size());

                List<ConceptMap> answers3 = tx.execute(Graql.parse(queryString3).asGet());
                assertEquals(12, answers3.size());

                List<ConceptMap> answers4 = tx.execute(Graql.parse(queryString4).asGet());
                assertEquals(answers3.stream()
                                .filter(ans -> ans.get("a").asThing()
                                        .attributes(tx.getAttributeType("name"))
                                        .anyMatch(at -> at.value().equals("b")))
                                .count(),
                        answers4.size());
                assertEquals(4, answers4.size());
            }
        }
    }

    @Test
    //tests scenario where rules define mutually recursive relation and resource and we query for an attributed type corresponding to the relation
    public void mutuallyRecursiveRelationAndResource_queryForAttributedType() {
        try (Session session = server.sessionWithNewKeyspace()) {
            loadFromFileAndCommit(resourcePath, "testSet30.gql", session);
            try (Transaction tx = session.transaction(Transaction.Type.READ)) {
                String specificPairs = "match $p isa pair, has name 'ff'; get;";
                List<ConceptMap> answers = tx.execute(Graql.parse(specificPairs).asGet());
                assertEquals(16, answers.size());

                String pairQuery = "match $p isa pair; get;";
                List<ConceptMap> pairs = tx.execute(Graql.parse(pairQuery).asGet());
                assertEquals(64, pairs.size());
            }
        }
    }
}

