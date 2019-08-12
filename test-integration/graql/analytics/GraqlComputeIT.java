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

package grakn.core.graql.analytics;

import com.google.common.collect.Lists;
import grakn.core.concept.ConceptId;
import grakn.core.concept.Label;
import grakn.core.concept.answer.ConceptList;
import grakn.core.concept.answer.ConceptSet;
import grakn.core.concept.answer.ConceptSetMeasure;
import grakn.core.concept.answer.Numeric;
import grakn.core.concept.thing.Entity;
import grakn.core.concept.type.AttributeType;
import grakn.core.concept.type.EntityType;
import grakn.core.concept.type.RelationType;
import grakn.core.concept.type.Role;
import grakn.core.graql.exception.GraqlSemanticException;
import grakn.core.rule.GraknTestServer;
import grakn.core.server.exception.InvalidKBException;
import grakn.core.server.kb.Schema;
import grakn.core.server.session.SessionImpl;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.Graql;
import graql.lang.exception.GraqlException;
import graql.lang.query.GraqlCompute;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static graql.lang.Graql.Token.Compute.Algorithm.CONNECTED_COMPONENT;
import static graql.lang.Graql.Token.Compute.Algorithm.DEGREE;
import static graql.lang.query.GraqlCompute.Argument.contains;
import static junit.framework.TestCase.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GraqlComputeIT {

    private static final String thingy = "thingy";
    private static final String anotherThing = "anotherThing";
    private static final String related = "related";

    private String entityId1;
    private String entityId2;
    private String entityId3;
    private String entityId4;
    private String relationId12;
    private String relationId24;

    public SessionImpl session;

    @ClassRule
    public static final GraknTestServer server = new GraknTestServer();

    @Before
    public void setUp() {
        session = server.sessionWithNewKeyspace();
    }

    @After
    public void closeSession() { session.close(); }

    @Rule
    public ExpectedException expectedEx = ExpectedException.none();

    @Test
    public void testNullResourceDoesNotBreakAnalytics() throws InvalidKBException {
        try (TransactionOLTP tx = session.transaction().write()) {
            // make slightly odd graph
            Label resourceTypeId = Label.of("degree");
            EntityType thingy = tx.putEntityType("thingy");

            AttributeType<Long> attribute = tx.putAttributeType(resourceTypeId, AttributeType.DataType.LONG);
            thingy.has(attribute);

            Role degreeOwner = tx.getRole(Schema.ImplicitType.HAS_OWNER.getLabel(resourceTypeId).getValue());
            Role degreeValue = tx.getRole(Schema.ImplicitType.HAS_VALUE.getLabel(resourceTypeId).getValue());
            RelationType relationType = tx.putRelationType(Schema.ImplicitType.HAS.getLabel(resourceTypeId))
                    .relates(degreeOwner)
                    .relates(degreeValue);
            thingy.plays(degreeOwner);

            Entity thisThing = thingy.create();
            relationType.create().assign(degreeOwner, thisThing);

            tx.commit();
        }

        // the null role-player caused analytics to fail at some stage
        try (TransactionOLTP tx = session.transaction().read()) {
            tx.execute(Graql.compute().centrality().using(DEGREE));
        } catch (RuntimeException e) {
            e.printStackTrace();
            fail();
        }
    }

    @Test
    public void testSubgraphContainingRuleDoesNotBreakAnalytics() {
        expectedEx.expect(GraqlSemanticException.class);
        expectedEx.expectMessage(GraqlSemanticException.labelNotFound(Label.of("rule")).getMessage());
        try (TransactionOLTP tx = session.transaction().read()) {
            tx.execute(Graql.compute().count().in("rule", "thing"));
        }
    }

    @Test
    public void testSubgraphContainingRoleDoesNotBreakAnalytics() {
        expectedEx.expect(GraqlSemanticException.class);
        expectedEx.expectMessage(GraqlSemanticException.labelNotFound(Label.of("role")).getMessage());
        try (TransactionOLTP tx = session.transaction().read()) {
            tx.execute(Graql.compute().count().in("role"));
        }
    }

    @Test
    public void testConcurrentAnalyticsJobsBySubmittingGraqlComputeQueries() {
        addSchemaAndEntities();

        List<String> queryList = new ArrayList<>();
        queryList.add("compute count;");
        queryList.add("compute cluster using connected-component;");
        queryList.add("compute cluster using k-core;");
        queryList.add("compute centrality using degree;");
        queryList.add("compute centrality using k-core;");
        queryList.add("compute path from " + entityId1 + ", to " + entityId4 + ";");

        List<?> result = queryList.parallelStream().map(query -> {
            try (TransactionOLTP tx = session.transaction().read()) {
                return tx.execute(Graql.<GraqlCompute>parse(query)).toString();
            }
        }).collect(Collectors.toList());
        assertEquals(queryList.size(), result.size());
    }

    @Test
    public void testGraqlCount() throws InvalidKBException {
        addSchemaAndEntities();
        try (TransactionOLTP tx = session.transaction().write()) {
            assertEquals(6, tx.execute(Graql.parse("compute count;").asComputeStatistics())
                    .get(0).number().intValue());

            assertEquals(3, tx.execute(Graql.parse("compute count in [thingy, thingy];").asComputeStatistics())
                    .get(0).number().intValue());
        }
    }

    @Test
    public void testDegrees() {
        addSchemaAndEntities();
        try (TransactionOLTP tx = session.transaction().write()) {
            List<ConceptSetMeasure> degrees =
                    tx.execute(Graql.parse("compute centrality using degree;").asComputeCentrality());

            Map<String, Long> correctDegrees = new HashMap<>();
            correctDegrees.put(entityId1, 1L);
            correctDegrees.put(entityId2, 2L);
            correctDegrees.put(entityId3, 0L);
            correctDegrees.put(entityId4, 1L);
            correctDegrees.put(relationId12, 2L);
            correctDegrees.put(relationId24, 2L);

            assertTrue(!degrees.isEmpty());
            degrees.forEach(conceptSetMeasure -> conceptSetMeasure.set().forEach(
                    id -> {
                        assertTrue(correctDegrees.containsKey(id.getValue()));
                        assertEquals(correctDegrees.get(id.getValue()).intValue(), conceptSetMeasure.measurement().intValue());
                    }
            ));
        }
    }

    @Test(expected = GraqlSemanticException.class)
    public void testInvalidTypeWithStatistics() {
        try (TransactionOLTP tx = session.transaction().write()) {
            tx.execute(Graql.parse("compute sum of thingy;").asComputeStatistics());
        }
    }

    @Test(expected = GraqlSemanticException.class)
    public void testInvalidTypeWithDegree() {
        try (TransactionOLTP tx = session.transaction().write()) {
            tx.execute(Graql.parse("compute centrality of thingy, using degree;").asComputeCentrality());
        }
    }

    @Test
    public void testStatisticsMethods() throws InvalidKBException {
        try (TransactionOLTP tx = session.transaction().write()) {
            Label resourceTypeId = Label.of("my-resource");

            AttributeType<Long> resource = tx.putAttributeType(resourceTypeId, AttributeType.DataType.LONG);
            EntityType thingy = tx.putEntityType("thingy");
            thingy.has(resource);

            Entity theResourceOwner = thingy.create();

            Role resourceOwner = tx.getRole(Schema.ImplicitType.HAS_OWNER.getLabel(resourceTypeId).getValue());
            Role resourceValue = tx.getRole(Schema.ImplicitType.HAS_VALUE.getLabel(resourceTypeId).getValue());
            RelationType relationType = tx.getRelationType(Schema.ImplicitType.HAS.getLabel(resourceTypeId).getValue());

            relationType.create()
                    .assign(resourceOwner, theResourceOwner)
                    .assign(resourceValue, resource.create(1L));
            relationType.create()
                    .assign(resourceOwner, theResourceOwner)
                    .assign(resourceValue, resource.create(2L));
            relationType.create()
                    .assign(resourceOwner, theResourceOwner)
                    .assign(resourceValue, resource.create(3L));

            tx.commit();
        }

        try (TransactionOLTP tx = session.transaction().write()) {
            // use graql to compute various statistics
            Numeric result = tx.execute(Graql.parse("compute sum of my-resource;").asComputeStatistics()).get(0);
            assertEquals(6, result.number().intValue());
            result = tx.execute(Graql.parse("compute min of my-resource;").asComputeStatistics()).get(0);
            assertEquals(1, result.number().intValue());
            result = tx.execute(Graql.parse("compute max of my-resource;").asComputeStatistics()).get(0);
            assertEquals(3, result.number().intValue());
            result = tx.execute(Graql.parse("compute mean of my-resource;").asComputeStatistics()).get(0);
            assertNotNull(result.number());
            assertEquals(2.0, result.number().doubleValue(), 0.1);
            result = tx.execute(Graql.parse("compute median of my-resource;").asComputeStatistics()).get(0);
            assertEquals(2, result.number().intValue());
        }
    }

    @Test
    public void testConnectedComponents() throws InvalidKBException {
        try (TransactionOLTP tx = session.transaction().write()) {
            List<ConceptSet> clusterList =
                    tx.execute(Graql.parse("compute cluster using connected-component;").asComputeCluster());
            assertTrue(clusterList.isEmpty());

            GraqlCompute parsed = Graql.parse("compute cluster using connected-component, where contains = V123;").asComputeCluster();
            GraqlCompute expected = Graql.compute().cluster().using(CONNECTED_COMPONENT).where(contains("V123"));
            assertEquals(expected, parsed);
        }
    }

    @Test
    public void testSinglePath() throws InvalidKBException {
        addSchemaAndEntities();

        try (TransactionOLTP tx = session.transaction().write()) {
            GraqlCompute.Path query = Graql.parse("compute path from " + entityId1 + ", to " + entityId2 + ";").asComputePath();
            List<ConceptList> paths = tx.execute(query);

            List<ConceptId> path = Collections.emptyList();
            if (!paths.isEmpty()) path = paths.get(0).list();
            List<String> result = path.stream().map(ConceptId::getValue).collect(Collectors.toList());
            List<String> expected = Lists.newArrayList(entityId1, relationId12, entityId2);

            assertEquals(expected, result);
        }
    }

    @Test
    public void testPath() throws InvalidKBException {
        addSchemaAndEntities();

        try (TransactionOLTP tx = session.transaction().write()) {
            GraqlCompute.Path query = Graql.parse("compute path from " + entityId1 + ", to " + entityId2 + ";").asComputePath();
            List<ConceptList> paths = tx.execute(query);
            assertEquals(1, paths.size());
            List<String> result = paths.get(0).list().stream().map(ConceptId::getValue).collect(Collectors.toList());
            List<String> expected = Lists.newArrayList(entityId1, relationId12, entityId2);

            assertEquals(expected, result);
        }
    }

    @Test(expected = GraqlSemanticException.class)
    public void testNonResourceTypeAsSubgraphForAnalytics() throws InvalidKBException {
        try (TransactionOLTP tx = session.transaction().write()) {
            tx.putEntityType(thingy);
            tx.commit();
        }

        try (TransactionOLTP tx = session.transaction().write()) {
            tx.execute(Graql.parse("compute sum of thingy;").asComputeStatistics());
        }
    }

    @Test(expected = GraqlException.class)
    public void testErrorWhenNoSubgraphForAnalytics() throws InvalidKBException {
        try (TransactionOLTP tx = session.transaction().write()) {
            tx.execute(Graql.parse("compute sum;").asComputeStatistics());
            tx.execute(Graql.parse("compute min;").asComputeStatistics());
            tx.execute(Graql.parse("compute max;").asComputeStatistics());
            tx.execute(Graql.parse("compute mean;").asComputeStatistics());
            tx.execute(Graql.parse("compute std;").asComputeStatistics());
        }
    }

    @Test
    public void testAnalyticsDoesNotCommitByMistake() throws InvalidKBException {
        try (TransactionOLTP tx = session.transaction().write()) {
            tx.putAttributeType("number", AttributeType.DataType.LONG);
            tx.commit();
        }

        Set<String> analyticsCommands = new HashSet<>(Arrays.asList(
                "compute count;",
                "compute centrality using degree;",
                "compute mean of number;"));

        analyticsCommands.forEach(command -> {
            try (TransactionOLTP tx = session.transaction().write()) {
                // insert a node but do not commit it
                tx.execute(Graql.parse("define thingy sub entity;").asDefine());
                // use analytics
                tx.execute(Graql.<GraqlCompute>parse(command));
            }

            try (TransactionOLTP tx = session.transaction().write()) {
                // see if the node was commited
                assertNull(tx.getEntityType("thingy"));
            }
        });
    }

    private void addSchemaAndEntities() throws InvalidKBException {
        try (TransactionOLTP tx = session.transaction().write()) {
            EntityType entityType1 = tx.putEntityType(thingy);
            EntityType entityType2 = tx.putEntityType(anotherThing);

            Entity entity1 = entityType1.create();
            Entity entity2 = entityType1.create();
            Entity entity3 = entityType1.create();
            Entity entity4 = entityType2.create();

            entityId1 = entity1.id().getValue();
            entityId2 = entity2.id().getValue();
            entityId3 = entity3.id().getValue();
            entityId4 = entity4.id().getValue();

            Role role1 = tx.putRole("role1");
            Role role2 = tx.putRole("role2");
            entityType1.plays(role1).plays(role2);
            entityType2.plays(role1).plays(role2);
            RelationType relationType = tx.putRelationType(related).relates(role1).relates(role2);

            relationId12 = relationType.create()
                    .assign(role1, entity1)
                    .assign(role2, entity2).id().getValue();
            relationId24 = relationType.create()
                    .assign(role1, entity2)
                    .assign(role2, entity4).id().getValue();

            tx.commit();
        }
    }
}
