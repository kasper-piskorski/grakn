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

package grakn.core.server.session.cache;

import grakn.core.concept.Concept;
import grakn.core.concept.ConceptId;
import grakn.core.concept.Label;
import grakn.core.concept.thing.Attribute;
import grakn.core.concept.thing.Entity;
import grakn.core.concept.thing.Relation;
import grakn.core.concept.type.AttributeType;
import grakn.core.concept.type.EntityType;
import grakn.core.concept.type.RelationType;
import grakn.core.concept.type.Role;
import grakn.core.concept.type.SchemaConcept;
import grakn.core.graql.reasoner.utils.Pair;
import grakn.core.rule.GraknTestServer;
import grakn.core.server.kb.Schema;
import grakn.core.server.session.SessionImpl;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.Graql;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static graql.lang.Graql.type;
import static graql.lang.Graql.var;
import static junit.framework.TestCase.assertTrue;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;


/**
 * Interesting takeaways:
 * - Creating a new type, we retrieve and cache the entire hierarchy up to `Thing` (top level meta concept)
 * - When fetching an existing type, we only cache that single type, not the entire hierarchy
 * - When fetching instances of a type, we cache both the instances and that type (but again, no supertypes)
 */

@SuppressWarnings("CheckReturnValue")
public class TransactionCacheIT {
    static final Path SERVER_SMALL_TX_CACHE_CONFIG_PATH = Paths.get("test-integration/resources/grakn-small-tx-cache.properties");
    static final Path CASSANDRA_CONFIG_PATH = Paths.get("test-integration/resources/cassandra-embedded.yaml");

    @ClassRule
    public static final GraknTestServer server = new GraknTestServer(SERVER_SMALL_TX_CACHE_CONFIG_PATH, CASSANDRA_CONFIG_PATH);

    @Rule
    public final ExpectedException expectedException = ExpectedException.none();
    private TransactionOLTP tx;
    private SessionImpl session;

    @Before
    public void setUp() {
        session = server.sessionWithNewKeyspace();
        tx = session.transaction().write();
    }

    @After
    public void tearDown() {
        tx.close();
        session.close();
    }


    @Test
    public void whenOpenTransaction_ConceptCacheIsEmpty() {
        TransactionCache transactionCache = tx.cache();
        Map<ConceptId, Concept> conceptCache = transactionCache.getConceptCache();
        assertEquals(0, conceptCache.size());
    }

    @Test
    public void whenOpenTransaction_SchemaConceptCacheIsEmpty() {
        TransactionCache transactionCache = tx.cache();
        Map<Label, SchemaConcept> schemaConceptCache = transactionCache.getSchemaConceptCache();
        assertEquals(0, schemaConceptCache.size());
    }

    @Test
    public void whenQueryForConcept_ConceptCacheIsUpdated() {
        // define and insert a concept
        EntityType person = tx.putEntityType("person");
        person.create();
        tx.commit();

        // examine new transaction's cache
        tx = session.transaction().read();
        EntityType retrievedPerson = tx.getEntityType("person");
        Entity retrievedPersonInstance = retrievedPerson.instances().collect(Collectors.toList()).get(0);

        TransactionCache transactionCache = tx.cache();
        Map<ConceptId, Concept> conceptCache = transactionCache.getConceptCache();

        // concept cache contains "person" type and "person" instance
        assertEquals(2, conceptCache.size());
        assertThat(conceptCache.values(), containsInAnyOrder(retrievedPerson, retrievedPersonInstance));
    }

    @Test
    public void whenQueryForSchemaConcept_SchemaConceptCacheIsUpdated() {
        // define and insert a concept
        EntityType person = tx.putEntityType("person");
        tx.commit();

        // examine new transaction's cache
        tx = session.transaction().read();
        tx.getEntityType("person");

        TransactionCache transactionCache = tx.cache();
        Map<Label, SchemaConcept> schemaConceptCache = transactionCache.getSchemaConceptCache();

        assertEquals(1, schemaConceptCache.size());
        assertEquals("person", schemaConceptCache.values().iterator().next().label().toString());
    }

    @Test
    public void whenCreateNewConcept_ConceptCacheIsUpdated() {
        // define and insert a concept
        EntityType person = tx.putEntityType("person");
        Entity personInstance = person.create();

        TransactionCache transactionCache = tx.cache();
        Map<ConceptId, Concept> conceptCache = transactionCache.getConceptCache();

        assertEquals(4, conceptCache.size());

        List<String> conceptCacheLabels = conceptCache.values().stream().map(concept -> {
            if (concept.isThing()) {
                return concept.asThing().type().label().toString();
            } else {
                return concept.asType().label().toString();
            }
        }).collect(Collectors.toList());
        assertThat(conceptCacheLabels, containsInAnyOrder("person", "person", "entity", "thing"));
    }

    @Test
    public void whenCreateNewSchemaConcept_SchemaConceptCacheIsUpdated() {
        // define a concept type
        EntityType person = tx.putEntityType("person");

        TransactionCache transactionCache = tx.cache();
        Map<Label, SchemaConcept> schemaConceptCache = transactionCache.getSchemaConceptCache();

        /*
        Expect 3 types in the schema concept cache because:
        1. `thing` is required to create the next type label ID
        2. Hierarchy of supertypes is immediates retrieved and cache on new type creation
         */
        assertEquals(3, schemaConceptCache.size());
        List<String> cachedSchemaConceptLabels = schemaConceptCache.values().stream().map(schemaConcept -> schemaConcept.label().toString()).collect(Collectors.toList());
        assertThat(cachedSchemaConceptLabels, containsInAnyOrder("person", "entity", "thing"));
    }

    @Test
    public void whenDeleteConcept_ConceptCacheIsUpdated() {
        // define and insert a concept
        EntityType person = tx.putEntityType("person");
        Entity personInstance = person.create();

        TransactionCache transactionCache = tx.cache();
        Map<ConceptId, Concept> conceptCache = transactionCache.getConceptCache();

        personInstance.delete();

        List<String> cachedConceptLabels = conceptCache.values().stream().map(concept -> concept.asType().label().toString()).collect(Collectors.toList());
        assertEquals(3, conceptCache.size());
        assertThat(cachedConceptLabels, containsInAnyOrder("person", "entity", "thing"));
    }

    @Test
    public void whenDeleteSchemaConcept_SchemaConceptCacheIsUpdated() {
        // define and insert a concept
        EntityType person = tx.putEntityType("person");

        TransactionCache transactionCache = tx.cache();
        Map<Label, SchemaConcept> schemaConceptCache = transactionCache.getSchemaConceptCache();

        person.delete();

        assertEquals(2, schemaConceptCache.size());
        List<String> cachedSchemaConceptLabels = schemaConceptCache.values().stream().map(schemaConcept -> schemaConcept.label().toString()).collect(Collectors.toList());
        assertThat(cachedSchemaConceptLabels, containsInAnyOrder("entity", "thing"));
    }

    @Test
    public void whenAddingAndDeletingSameAttributeInSameTx_transactionCacheIsInSync() {
        String testAttributeLabel = "test-attribute";
        String testAttributeValue = "test-attribute-value";
        String index = Schema.generateAttributeIndex(Label.of(testAttributeLabel), testAttributeValue);

        // define the schema
        tx.execute(Graql.define(type(testAttributeLabel).sub("attribute").datatype(Graql.Token.DataType.STRING)));
        tx.commit();


        tx = session.transaction().write();
        tx.execute(Graql.insert(var("x").isa(testAttributeLabel).val(testAttributeValue)));
        tx.execute(Graql.match(var("x").isa(testAttributeLabel).val(testAttributeValue)).delete());
        assertFalse(tx.cache().getNewAttributes().containsKey(new Pair<>(Label.of(testAttributeLabel), index)));
        assertTrue(tx.cache().getRemovedAttributes().contains(index));
    }



    @Test
    public void whenModifyingVertexAndEvictingFromJanusTx_UpdateIsRead() {
        /*
        0. set janus tx cache size small
        1. read a janus vertex
        2. write to vertex (not delete -- add edges and properties)
        3. do a bunch of janus ops to evict this vertex from the janus cache
        4. re-read the original vertex and confirm the edges and properties are still there
        */

        Role work = tx.putRole("work");
        Role author = tx.putRole("author");
        AttributeType<String> provenance = tx.putAttributeType("provenance", AttributeType.DataType.STRING);
        EntityType somework = tx.putEntityType("somework").plays(work);
        EntityType person = tx.putEntityType("person").plays(author);
        RelationType authoredBy = tx.putRelationType("authored-by").relates(work).relates(author).has(provenance);

        Entity aPerson = person.create();
        Relation aRelation = authoredBy.create();
        aRelation.assign(author, aPerson);
        ConceptId relationId = aRelation.id();
        Attribute<String> aProvenance = provenance.create("hello");
        aRelation.has(aProvenance);

        tx.commit();
        tx = session.transaction().write();

        // retrieve the vertex as a concept
        aRelation = tx.getConcept(relationId);

        // modify the vertex by adding a concept
        provenance = tx.getAttributeType("provenance");
        Attribute<String> newProvenance = provenance.create("bye");
        aRelation.has(newProvenance);

        // retireve the specific janus vertex
        Vertex relationVertex = tx.getTinkerTraversal().V(Schema.elementId(relationId)).next();
        relationVertex.property("testKey", "testValue");

        // do a bunch of janus ops to evict the relation
        person = tx.getEntityType("person");
        for (int i = 0; i < Integer.parseInt(server.serverConfig().properties().get("cache.tx-cache-size").toString()); i++) {
            person.create();
        }

        Vertex janusVertex = tx.getTinkerTraversal().V(Schema.elementId(relationId)).next();

        // confirm we have the exact same object from Janus
        assertSame(janusVertex, relationVertex);
        assertEquals("testValue", janusVertex.property("testKey").value().toString());
    }


    @Test
    public void whenModifyingEvictedVertex_UpdateIsRead() {
        /*
        0. set janus tx cache size to small
        1. read janus vertex
        2. do a bunch of janus ops to evict this vertex from janus cache
        3. write to vertex (not delete -- add edges and properties)
        4. re-read the original vertex and confirm edges and properties are still there
        */

        Role work = tx.putRole("work");
        Role author = tx.putRole("author");
        AttributeType<String> provenance = tx.putAttributeType("provenance", AttributeType.DataType.STRING);
        EntityType somework = tx.putEntityType("somework").plays(work);
        EntityType person = tx.putEntityType("person").plays(author);
        RelationType authoredBy = tx.putRelationType("authored-by").relates(work).relates(author).has(provenance);

        Entity aPerson = person.create();
        Relation aRelation = authoredBy.create();
        aRelation.assign(author, aPerson);
        ConceptId relationId = aRelation.id();
        Attribute<String> aProvenance = provenance.create("hello");
        aRelation.has(aProvenance);

        // create vertices that we will later read to force a cache eviction
        List<String> fillerJanusVertices = new ArrayList<>();
        for (int i = 0; i < Integer.parseInt(server.serverConfig().properties().get("cache.tx-cache-size").toString()); i++) {
            fillerJanusVertices.add(Schema.elementId(person.create().id()));
        }
        tx.commit();
        tx = session.transaction().write();

        // retrieve the vertex as a concept
        aRelation = tx.getConcept(relationId);
        // retrieve the specific janus vertex
        Vertex relationVertex = tx.getTinkerTraversal().V(Schema.elementId(relationId)).next();

        // read a bunch of janus vertices to evict the relation
        List<Vertex> vertices = tx.getTinkerTraversal().V(fillerJanusVertices).toStream().collect(Collectors.toList());

        // modify the vertex by adding a property
        provenance = tx.getAttributeType("provenance");
        relationVertex.property("testKey", "testValue");

        // re-retrieve the specific janus vertex
        Vertex janusVertex = tx.getTinkerTraversal().V(Schema.elementId(relationId)).next();

        // confirm we have the exact same object from Janus
        assertSame(janusVertex, relationVertex);
        assertEquals("testValue", janusVertex.property("testKey").value().toString());
    }
}
