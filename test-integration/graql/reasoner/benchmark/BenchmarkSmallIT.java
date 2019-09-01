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
import grakn.core.concept.Concept;
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
import grakn.core.graql.reasoner.utils.TarjanSCC;
import grakn.core.rule.GraknTestServer;
import grakn.core.server.session.SessionImpl;
import grakn.core.server.session.TransactionOLTP;
import grakn.core.util.GraqlTestUtil;
import graql.lang.Graql;
import graql.lang.query.GraqlGet;
import graql.lang.statement.Statement;
import graql.lang.statement.Variable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;


@SuppressWarnings({"CheckReturnValue", "Duplicates"})
public class BenchmarkSmallIT {

    @ClassRule
    public static final GraknTestServer server = new GraknTestServer();

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

        int limit = 100;
        int N = 10;

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
        //executeQuery(Graql.parse(queryString).asGet().match().get().limit(limit), tx, "limit " + limit);
        tx.close();
        session.close();
    }

    private void printTimes(TransactionOLTP tx){
        System.out.println("consume time: " + AtomicState.consumeTime);
        System.out.println("propagate time: " + AtomicState.propagateTime);
        System.out.println("IndexedAnswerSet:add " + IndexedAnswerSet.addTime);

        AtomicState.consumeTime = 0;
        AtomicState.propagateTime= 0;
        IndexedAnswerSet.addTime= 0;

        tx.profiler().print();
        //System.out.println("tarjan time: " + TransitiveClosureState.tarjanTime);
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
        System.out.println(new Object(){}.getClass().getEnclosingMethod().getName());
        List<Integer> sizes = Arrays.asList(50, 100, 200, 400, 800);
        for (Integer N : sizes) {
            long start = System.currentTimeMillis();
            //int N = 100;
            int limit = 10;
            int answers = (N+1)*N/2;
            SessionImpl session = server.sessionWithNewKeyspace();
            TransitivityChainGraph transitivityChainGraph = new TransitivityChainGraph(session);

            transitivityChainGraph.load(N);
            System.out.println("load time: " + (System.currentTimeMillis() - start));
            System.out.println("N = " + N);

            String queryString = "match (Q-from: $x, Q-to: $y) isa Q; get;";
            GraqlGet query = Graql.parse(queryString).asGet();
            String queryString2 = "match (Q-from: $x, Q-to: $y) isa Q;$x has index 'a'; get;";
            GraqlGet query2 = Graql.parse(queryString2).asGet();

            try (TransactionOLTP tx = session.transaction().write()) {
                List<ConceptMap> fullAnswers = executeQuery(query, tx, "full");
                //printTimes(tx);

                //GraqlTestUtil.assertCollectionsEqual(fullAnswers, tarjanAnswers);
                assertEquals(answers, fullAnswers.size());
            }

            session.close();
        }
    }

    public HashMultimap<Concept, Concept> tarjanTC(GraqlGet baseQuery, TransactionOLTP tx){
        long start2 = System.currentTimeMillis();
        List<ConceptMap> dbAnswers = tx.execute(baseQuery, false);
        long dbTime = System.currentTimeMillis() - start2;

        HashMultimap<Concept, Concept> graph = HashMultimap.create();
        dbAnswers.forEach(ans -> graph.put(ans.get("x"), ans.get("y")));

        HashMultimap<Concept, Concept> successorMap = new TarjanSCC<>(graph).successorMap();

        System.out.println("tarjan answers: " + successorMap.entries().size() + " time: " + (System.currentTimeMillis() - start2) + " Db time: " + dbTime);
        return successorMap;
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

        List<Integer> sizes = Arrays.asList(10, 20, 30, 40, 50);

        int N = 20;
        //for (Integer N : sizes) {

        int limit = 1000;
        int limit2 = 100000;

        SessionImpl session = server.sessionWithNewKeyspace();
        TransitivityMatrixGraph transitivityMatrixGraph = new TransitivityMatrixGraph(session);
        //                         DJ       IC     FO
        //results @N = 10 3025
        //results @N = 15 14400     ?
        //results @N = 20 44100     ?       ?     12s     4 s
        //results @N = 25 105625    ?       ?     50s    11 s
        //results @N = 30 216225    ?       ?      ?     30 s
        //results @N = 35 396900   ?        ?      ?     76 s
        //results @N = 40 672400
        //results @N = 45
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

            //tx.getRelationType("Q").instances().forEach(System.out::println);
            //HashMultimap<Concept, Concept> tarjanTC = tarjanTC(query, tx);
            List<ConceptMap> fullAnswers = executeQuery(query, tx, "full");

            /*
            List<ConceptMap> tarjanAnswers = tarjanTC.entries().stream()
                    .map(e -> new ConceptMap(ImmutableMap.of(new Variable("x"), e.getKey(), new Variable("y"), e.getValue())))
                    .collect(Collectors.toList());
            GraqlTestUtil.assertCollectionsEqual(fullAnswers, tarjanAnswers);

             */
        }

        try(TransactionOLTP tx = session.transaction().write()) {
            String index = "a";
            Concept startConcept = tx.execute(Graql.parse("match $x has index '" + index +"'; get;").asGet()).iterator().next().get("x");
            String queryString3 = "match (Q-from: $x, Q-to: $y) isa Q;$x id " + startConcept.id().getValue() + "; get;";
            GraqlGet query3 = Graql.parse(queryString3).asGet();

            List<ConceptMap> specificAnswers = executeQuery(query3, tx, "With start node id");

            HashMultimap<Concept, Concept> tarjanTC = tarjanTC(query, tx);
            List<ConceptMap> tarjanSpecificAnswers = tarjanTC.get(startConcept).stream()
                    .map(target -> new ConceptMap(ImmutableMap.of(new Variable("x"), startConcept, new Variable("y"), target)))
                    .collect(Collectors.toList());
            GraqlTestUtil.assertCollectionsEqual(specificAnswers, tarjanSpecificAnswers);
        }

        try(TransactionOLTP tx = session.transaction().write()) {
            String index = "a" + (N-1) + "," + (N-1);
            Concept endConcept = tx.execute(Graql.parse("match $x has index '" + index +"'; get;").asGet()).iterator().next().get("x");
            String queryString3 = "match (Q-from: $x, Q-to: $y) isa Q;$y id " + endConcept.id().getValue() + "; get;";
            GraqlGet query3 = Graql.parse(queryString3).asGet();

            List<ConceptMap> specificAnswers = executeQuery(query3, tx, "With end node id");

            HashMultimap<Concept, Concept> tarjanTC = tarjanTC(query, tx);
            List<ConceptMap> tarjanSpecificAnswers = tarjanTC.entries().stream()
                    .filter(e -> e.getValue().equals(endConcept))
                    .map(Map.Entry::getKey)
                    .map(src -> new ConceptMap(ImmutableMap.of(new Variable("y"), endConcept, new Variable("x"), src)))
                    .collect(Collectors.toList());
            GraqlTestUtil.assertCollectionsEqual(specificAnswers, tarjanSpecificAnswers);
        }


            session.close();

        /*
        try(TransactionOLTP tx = session.transaction().write()) {
            executeQuery(query.match().get().limit(limit), tx, "limit " + limit);
            printTimes(tx);
        }

        try(TransactionOLTP tx = session.transaction().write()) {
            executeQuery(query.match().get().limit(limit2), tx, "limit " + limit2);
            printTimes(tx);
        }

         */

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
        //printTimes(transaction);
        return results;
    }

}