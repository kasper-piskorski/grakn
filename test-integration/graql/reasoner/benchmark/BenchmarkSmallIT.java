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

package grakn.core.graql.reasoner.benchmark;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import grakn.client.GraknClient;
import grakn.core.api.Transaction;
import grakn.core.concept.Concept;
import grakn.core.concept.answer.Answer;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.concept.thing.Entity;
import grakn.core.concept.type.EntityType;
import grakn.core.concept.type.RelationType;
import grakn.core.concept.type.Role;
import grakn.core.graql.reasoner.cache.IndexedAnswerSet;
import grakn.core.graql.reasoner.graph.DiagonalGraph;
import grakn.core.graql.reasoner.graph.LinearTransitivityMatrixGraph;
import grakn.core.graql.reasoner.graph.PathTreeGraph;
import grakn.core.graql.reasoner.graph.TransitivityChainGraph;
import grakn.core.graql.reasoner.graph.TransitivityMatrixGraph;
import grakn.core.graql.reasoner.state.AtomicState;
import grakn.core.graql.reasoner.state.TransitiveClosureState;
import grakn.core.graql.reasoner.unifier.UnifierImpl;
import grakn.core.graql.reasoner.utils.TarjanSCC;
import grakn.core.rule.GraknTestServer;
import grakn.core.server.kb.concept.ConceptUtils;
import grakn.core.server.session.SessionImpl;
import grakn.core.server.session.TransactionOLTP;
import grakn.core.util.GraqlTestUtil;
import graql.lang.Graql;
import graql.lang.query.GraqlGet;
import graql.lang.query.GraqlQuery;
import graql.lang.statement.Statement;
import graql.lang.statement.Variable;
import java.util.ArrayList;
import java.util.List;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;


@SuppressWarnings({"CheckReturnValue", "Duplicates"})
public class BenchmarkSmallIT {

    @ClassRule
    public static final GraknTestServer server = new GraknTestServer();


    @Test
    public void test(){
        GraknClient graknClient = new GraknClient("localhost:48555");
        GraknClient.Session remoteSession = graknClient.session("s_acadc9f5c73741329a75e0c40b5aa9c4");
        try(Transaction tx = remoteSession.transaction().read()) {
            long start = System.currentTimeMillis();
            //(5 seconds, 388.673 milliseconds)\
            GraqlQuery query = Graql.parse(
                    "match " +
                            "$taxpayer isa User;" +
                            "(filer: $taxpayer, declaration: $declaration) isa files_tax_declaration;" +
                            "(declaration: $declaration, form: $form) isa materializes_in_form;" +
                            "$field (form: $form, field: $field_value);" +
                            "$field isa has_form_field; $field has identifier $field_id;" +
                            "$field has index_group $index_group;" +
                            "(field_relationship: $field, indexable: $index_instance) isa is_indexed_by;" +
                            "get; limit 1;");
            List<? extends Answer> answers = tx.execute(query);
            System.out.println("query answers: " + answers.size() + " time : " + (System.currentTimeMillis() - start));
        }
        /*
        try(Transaction tx = remoteSession.transaction().read()) {
            long start = System.currentTimeMillis();
            //(5 seconds, 388.673 milliseconds)\
            GraqlQuery query1 = Graql.parse(
                    "match " +
                            "$fiscal_domicile_relation (citizen: $taxpayer, place_of_fiscal_domicile: $fiscal_domicile);" +
                            "$fiscal_domicile_relation isa has_fiscal_domicile_at;" +
                            "$fiscal_domicile_relation has is_fiscal_domicile_at_the_end_of_tax_year == false;" +
                            "$taxpayer isa User;" +
                            "(filer: $taxpayer, declaration: $declaration) isa files_tax_declaration;" +
                            "(declaration: $declaration, form: $form) isa materializes_in_form;" +
                            "$fiscal_domicile has city_code $city_code;" +
                            "get ;");
            List<? extends Answer> answers = tx.execute(query1);
            System.out.println("query 1 answers: " + answers.size() + " time : " + (System.currentTimeMillis() - start));
        }
        try(Transaction tx = remoteSession.transaction().read()) {
            //(2 seconds, 157.848 milliseconds)
            long start = System.currentTimeMillis();
            GraqlQuery query2 = Graql.parse(
                    "match " +
                            "$fiscal_domicile_relation (citizen: $taxpayer, place_of_fiscal_domicile: $fiscal_domicile);" +
                            "$fiscal_domicile_relation isa has_fiscal_domicile_at; $fiscal_domicile_relation has is_fiscal_domicile_at_the_end_of_tax_year == false;" +
                            "$taxpayer isa User;" +
                            "(filer: $taxpayer, declaration: $declaration) isa files_tax_declaration;" +
                            "(declaration: $declaration, form: $form) isa materializes_in_form; $fiscal_domicile has city_code $city_code;" +
                            "get;");
            List<? extends Answer> answers2 = tx.execute(query2);
            System.out.println("query 2 answers: " + answers2.size() + " time : " + (System.currentTimeMillis() - start));
        }

         */
    }

    /**
     * Executes a scalability test defined in terms of the number of rules in the system. Creates a simple rule chain:
     *
     * R_i(x, y) := R_{i-1}(x, y);     i e [1, N]
     *
     * with a single initial relation instance R_0(a ,b)
     *
     */
    @Test
    public void nonRecursiveChainOfRules() {
        final int N = 200;
        System.out.println(new Object(){}.getClass().getEnclosingMethod().getName());
        SessionImpl session = server.sessionWithNewKeyspace();

        //NB: loading data here as defining it as KB and using graql api leads to circular dependencies
        try(TransactionOLTP tx = session.transaction().write()) {
            Role fromRole = tx.putRole("fromRole");
            Role toRole = tx.putRole("toRole");

            RelationType relation0 = tx.putRelationType("relation0")
                    .relates(fromRole)
                    .relates(toRole);

            for (int i = 1; i <= N; i++) {
                tx.putRelationType("relation" + i)
                        .relates(fromRole)
                        .relates(toRole);
            }
            EntityType genericEntity = tx.putEntityType("genericEntity")
                    .plays(fromRole)
                    .plays(toRole);

            Entity fromEntity = genericEntity.create();
            Entity toEntity = genericEntity.create();

            relation0.create()
                    .assign(fromRole, fromEntity)
                    .assign(toRole, toEntity);

            for (int i = 1; i <= N; i++) {
                Statement fromVar = new Statement(new Variable().asReturnedVar());
                Statement toVar = new Statement(new Variable().asReturnedVar());
                Statement rulePattern = Graql
                        .type("rule" + i)
                        .when(
                                Graql.and(
                                        Graql.var()
                                                .rel(Graql.type(fromRole.label().getValue()), fromVar)
                                                .rel(Graql.type(toRole.label().getValue()), toVar)
                                                .isa("relation" + (i - 1))
                                )
                        )
                        .then(
                                Graql.and(
                                        Graql.var()
                                                .rel(Graql.type(fromRole.label().getValue()), fromVar)
                                                .rel(Graql.type(toRole.label().getValue()), toVar)
                                                .isa("relation" + i)
                                )
                        );
                tx.execute(Graql.define(rulePattern));
            }
            tx.commit();
        }

        try( TransactionOLTP tx = session.transaction().read()) {
            final long limit = 1;
            String queryPattern = "(fromRole: $x, toRole: $y) isa relation" + N + ";";
            String queryString = "match " + queryPattern + " get;";
            String limitedQueryString = "match " + queryPattern +
                    "get; limit " + limit +  ";";

            assertEquals(executeQuery(queryString, tx, "full").size(), limit);
            assertEquals(executeQuery(limitedQueryString, tx, "limit").size(), limit);
        }
        session.close();
    }

    /**
     * 2-rule transitive test with transitivity expressed in terms of two linear rules
     * The rules are defined as:
     *
     * (Q-from: $x, Q-to: $y) isa Q;
     * ->
     * (P-from: $x, P-to: $y) isa P;
     *
     * (Q-from: $x, Q-to: $z) isa Q;
     * (P-from: $z, P-to: $y) isa P;
     * ->
     * (P-from: $z, P-to: $y) isa P;
     *
     * Each pair of neighbouring grid points is related in the following fashion:
     *
     *  a_{i  , j} -  Q  - a_{i, j + 1}
     *       |                    |
     *       Q                    Q
     *       |                    |
     *  a_{i+1, j} -  Q  - a_{i+1, j+1}
     *
     *  i e [1, N]
     *  j e [1, N]
     */
    @Test
    public void testTransitiveMatrixLinear()  {
        int N = 10;
        int limit = 100;
        SessionImpl session = server.sessionWithNewKeyspace();
        LinearTransitivityMatrixGraph linearGraph = new LinearTransitivityMatrixGraph(session);

        System.out.println(new Object(){}.getClass().getEnclosingMethod().getName());

        //                         DJ       IC     FO
        //results @N = 15 14400   3-5s
        //results @N = 20 44100    15s     8 s      8s
        //results @N = 25 105625   48s    27 s     31s
        //results @N = 30 216225  132s    65 s
        linearGraph.load(N, N);

        String queryString = "match (P-from: $x, P-to: $y) isa P; get;";
        TransactionOLTP tx = session.transaction().write();
        executeQuery(queryString, tx, "full");
        executeQuery(Graql.parse(queryString).asGet().match().get().limit(limit), tx, "limit " + limit);
        tx.close();
        session.close();
    }

    private void printTimes(TransactionOLTP tx){
        System.out.println("consume time: " + AtomicState.consumeTime);
        System.out.println("propagate time: " + AtomicState.propagateTime);
        System.out.println("ConceptMap::hashCode: " + ConceptMap.hashCodeTime);
        System.out.println("ConceptMap::project: " + ConceptMap.projectTime);
        System.out.println("ConceptMap::project calls: " + ConceptMap.projectCalls);
        System.out.println("UnifierImpl::apply: " + UnifierImpl.unifyTime);
        System.out.println("IndexedAnswerSet:add " + IndexedAnswerSet.addTime);
        System.out.println("ConceptUtils::merge: " + ConceptUtils.mergeTime);
        System.out.println("    ConceptUtils::setTime: " + ConceptUtils.setTime);
        System.out.println("    ConceptUtils::setEqualityTime: " + ConceptUtils.setEqualityTime);
        System.out.println("    ConceptUtils::varIntersectionTime: " + ConceptUtils.varIntersectionTime);
        System.out.println("ConceptUtils::disjointSet: " + ConceptUtils.disjointSetTime);
        System.out.println("ConceptUtils::disjointType: " + ConceptUtils.disjointTypeTime);

        AtomicState.consumeTime = 0;
        AtomicState.propagateTime= 0;
        ConceptMap.hashCodeTime = 0;
        ConceptMap.projectTime= 0;
        ConceptMap.projectCalls= 0;
        UnifierImpl.unifyTime= 0;
        IndexedAnswerSet.addTime= 0;
        ConceptUtils.mergeTime= 0;
        ConceptUtils.setTime= 0;
        ConceptUtils.setEqualityTime= 0;
        ConceptUtils.varIntersectionTime= 0;
        ConceptUtils.disjointSetTime= 0;
        ConceptUtils.disjointTypeTime= 0;

        tx.profiler().print();
        System.out.println("tarjan time: " + TransitiveClosureState.tarjanTime);
    }

    /**
     * single-rule transitivity test with initial data arranged in a chain of length N
     * The rule is given as:
     *
     * (Q-from: $x, Q-to: $z) isa Q;
     * (Q-from: $z, Q-to: $y) isa Q;
     * ->
     * (Q-from: $x, Q-to: $y) isa Q;
     *
     * Each neighbouring grid points are related in the following fashion:
     *
     *  a_{i} -  Q  - a_{i + 1}
     *
     *  i e [0, N)
     */
    @Test
    public void testTransitiveChain()  {
        int N = 100;
        int limit = 10;
        int answers = (N+1)*N/2;
        SessionImpl session = server.sessionWithNewKeyspace();
        System.out.println(new Object(){}.getClass().getEnclosingMethod().getName());
        TransitivityChainGraph transitivityChainGraph = new TransitivityChainGraph(session);
        long start = System.currentTimeMillis();
        transitivityChainGraph.load(N);
        System.out.println("load time: " + (System.currentTimeMillis() - start));

        String queryString = "match (Q-from: $x, Q-to: $y) isa Q; get;";
        GraqlGet query = Graql.parse(queryString).asGet();
        String queryString2 = "match (Q-from: $x, Q-to: $y) isa Q;$x has index 'a'; get;";
        GraqlGet query2 = Graql.parse(queryString2).asGet();

        try(TransactionOLTP tx = session.transaction().write()) {
            List<ConceptMap> fullAnswers = executeQuery(query, tx, "full");
            printTimes(tx);
        /*
        assertEquals(executeQuery(query, tx, "full").size(), answers);
        assertEquals(executeQuery(query2, tx, "With specific resource").size(), N);

        executeQuery(query.match().get().limit(limit), tx, "limit " + limit);
        executeQuery(query2.match().get().limit(limit), tx, "limit " + limit);
         */

            long start2 = System.currentTimeMillis();
            List<ConceptMap> dbAnswers = tx.execute(query, false);
            System.out.println("Db time: " + (System.currentTimeMillis() - start2));

            HashMultimap<Concept, Concept> graph = HashMultimap.create();
            dbAnswers.forEach(ans -> graph.put(ans.get("x"), ans.get("y")));

            List<ConceptMap> tarjanAnswers = new ArrayList<>();
            HashMultimap<Concept, Concept> successorMap = new TarjanSCC<>(graph).successorMap();
            successorMap.entries()
                    .forEach(e -> tarjanAnswers.add(new ConceptMap(ImmutableMap.of(new Variable("x"), e.getKey(), new Variable("y"), e.getValue()))));
            System.out.println("tarjan answers: " + tarjanAnswers.size() + " time: " + (System.currentTimeMillis() - start2));
            GraqlTestUtil.assertCollectionsEqual(fullAnswers, tarjanAnswers);
            assertEquals(answers, tarjanAnswers.size());
            System.out.println();
        }

        try(TransactionOLTP tx = session.transaction().write()) {
            executeQuery(query.match().get().limit(limit), tx, "limit " + limit);
            printTimes(tx);
        }
        session.close();
    }

    /**
     * single-rule transitivity test with initial data arranged in a N x N square grid.
     * The rule is given as:
     *
     * (Q-from: $x, Q-to: $z) isa Q;
     * (Q-from: $z, Q-to: $y) isa Q;
     * ->
     * (Q-from: $x, Q-to: $y) isa Q;
     *
     * Each pair of neighbouring grid points is related in the following fashion:
     *
     *  a_{i  , j} -  Q  - a_{i, j + 1}
     *       |                    |
     *       Q                    Q
     *       |                    |
     *  a_{i+1, j} -  Q  - a_{i+1, j+1}
     *
     *  i e [0, N)
     *  j e [0, N)
     */
    @Test
    public void testTransitiveMatrix(){
        System.out.println(new Object(){}.getClass().getEnclosingMethod().getName());
        int N = 50;
        int limit = 1000;
        int limit2 = 100000;

        SessionImpl session = server.sessionWithNewKeyspace();
        TransitivityMatrixGraph transitivityMatrixGraph = new TransitivityMatrixGraph(session);
        //                         DJ       IC     FO
        //results @N = 15 14400     ?
        //results @N = 20 44100     ?       ?     12s     4 s
        //results @N = 25 105625    ?       ?     50s    11 s
        //results @N = 30 216225    ?       ?      ?     30 s
        //results @N = 35 396900   ?        ?      ?     76 s
        //results @N = 40 672400
        //results @N = 50 1625625
        long start = System.currentTimeMillis();
        transitivityMatrixGraph.load(N, N);
        System.out.println("load time: " + (System.currentTimeMillis() - start));

        //full result
        String queryString = "match (Q-from: $x, Q-to: $y) isa Q; get;";
        GraqlGet query = Graql.parse(queryString).asGet();

        //with specific resource
        String queryString2 = "match (Q-from: $x, Q-to: $y) isa Q;$x has index 'a'; get;";
        GraqlGet query2 = Graql.parse(queryString2).asGet();

        try(TransactionOLTP tx = session.transaction().write()) {
            //with substitution
            Concept id = tx.execute(Graql.parse("match $x has index 'a'; get;").asGet()).iterator().next().get("x");
            String queryString3 = "match (Q-from: $x, Q-to: $y) isa Q;$x id " + id.id().getValue() + "; get;";
            GraqlGet query3 = Graql.parse(queryString3).asGet();

            List<ConceptMap> fullAnswers = executeQuery(query, tx, "full");
            printTimes(tx);
            int answers = 396900;
            //List<ConceptMap> resAnswers = executeQuery(query.match().get().limit(answers), tx, "limit " + answers);

            long start2 = System.currentTimeMillis();
            List<ConceptMap> dbAnswers = tx.execute(query, false);
            System.out.println("Db time: " + (System.currentTimeMillis() - start2));

            HashMultimap<Concept, Concept> graph = HashMultimap.create();
            dbAnswers.forEach(ans -> graph.put(ans.get("x"), ans.get("y")));

            List<ConceptMap> tarjanAnswers = new ArrayList<>();
            HashMultimap<Concept, Concept> successorMap = new TarjanSCC<>(graph).successorMap();
            successorMap.entries()
                    .forEach(e -> tarjanAnswers.add(new ConceptMap(ImmutableMap.of(new Variable("x"), e.getKey(), new Variable("y"), e.getValue()))));
            System.out.println("tarjan answers: " + tarjanAnswers.size() + " time: " + (System.currentTimeMillis() - start2));
            GraqlTestUtil.assertCollectionsEqual(fullAnswers, tarjanAnswers);
            System.out.println();
        /*
        executeQuery(query2, tx, "With specific resource");
        executeQuery(query3, tx, "Single argument bound");
        executeQuery(query.match().get().limit(limit), tx, "limit " + limit);
         */

        }
        try(TransactionOLTP tx = session.transaction().write()) {
            executeQuery(query.match().get().limit(limit), tx, "limit " + limit);
            printTimes(tx);
        }

        session.close();
    }

    /**
     * single-rule mimicking transitivity test rule defined by two-hop relations
     * Initial data arranged in N x N square grid.
     *
     * Rule:
     * (rel-from:$x, rel-to:$y) isa horizontal;
     * (rel-from:$y, rel-to:$z) isa horizontal;
     * (rel-from:$z, rel-to:$u) isa vertical;
     * (rel-from:$u, rel-to:$v) isa vertical;
     * ->
     * (rel-from:$x, rel-to:$v) isa diagonal;
     *
     * Initial data arranged as follows:
     *
     *  a_{i  , j} -  horizontal  - a_{i, j + 1}
     *       |                    |
     *    vertical             vertical
     *       |                    |
     *  a_{i+1, j} -  horizontal  - a_{i+1, j+1}
     *
     *  i e [0, N)
     *  j e [0, N)
     */
    @Test
    public void testDiagonal()  {
        int N = 10; //9604
        int limit = 10;

        System.out.println(new Object(){}.getClass().getEnclosingMethod().getName());

        SessionImpl session = server.sessionWithNewKeyspace();
        DiagonalGraph diagonalGraph = new DiagonalGraph(session);
        diagonalGraph.load(N, N);
        //results @N = 40  1444  3.5s
        //results @N = 50  2304    8s    / 1s
        //results @N = 100 9604  loading takes ages
        TransactionOLTP tx = session.transaction().write();
        
        String queryString = "match (rel-from: $x, rel-to: $y) isa diagonal; get;";
        GraqlGet query = Graql.parse(queryString).asGet();

        executeQuery(query, tx, "full");
        executeQuery(query.match().get().limit(limit), tx, "limit " + limit);
        tx.close();
        session.close();
    }

    /**
     * single-rule mimicking transitivity test rule defined by two-hop relations
     * Initial data arranged in N x N square grid.
     *
     * Rules:
     * (arc-from: $x, arc-to: $y) isa arc;},
     * ->
     * (path-from: $x, path-to: $y) isa path;};

     *
     * (path-from: $x, path-to: $z) isa path;
     * (path-from: $z, path-to: $y) isa path;},
     * ->
     * (path-from: $x, path-to: $y) isa path;};
     *
     * Initial data arranged as follows:
     *
     * N - tree heights
     * l - number of links per entity
     *
     *                     a0
     *               /     .   \
     *             arc          arc
     *             /       .       \
     *           a1,1     ...    a1,1^l
     *         /   .  \         /    .  \
     *       arc   .  arc     arc    .  arc
     *       /     .   \       /     .    \
     *     a2,1 ...  a2,l  a2,l+1  ...  a2,2^l
     *            .             .
     *            .             .
     *            .             .
     *   aN,1    ...  ...  ...  ...  ... ... aN,N^l
     *
     */
    @Test
    public void testPathTree(){
        int N = 5;
        int linksPerEntity = 4;
        System.out.println(new Object(){}.getClass().getEnclosingMethod().getName());
        SessionImpl session = server.sessionWithNewKeyspace();
        PathTreeGraph pathTreeGraph = new PathTreeGraph(session);
        pathTreeGraph.load(N, linksPerEntity);
        int answers = 0;
        for(int i = 1 ; i <= N ; i++) answers += Math.pow(linksPerEntity, i);

        TransactionOLTP tx = session.transaction().write();

        String queryString = "match (path-from: $x, path-to: $y) isa path;" +
                "$x has index 'a0';" +
                "get $y; limit " + answers + ";";

        assertEquals(executeQuery(queryString, tx, "tree").size(), answers);
        tx.close();
        session.close();
    }

    private List<ConceptMap> executeQuery(String queryString, TransactionOLTP transaction, String msg){
        return executeQuery(Graql.parse(queryString).asGet(), transaction, msg);
    }

    private List<ConceptMap> executeQuery(GraqlGet query, TransactionOLTP transaction, String msg){
        final long startTime = System.currentTimeMillis();
        List<ConceptMap> results = transaction.execute(query);
        final long answerTime = System.currentTimeMillis() - startTime;
        System.out.println(msg + " results = " + results.size() + " answerTime: " + answerTime);
        return results;
    }

}