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

package grakn.core.server.session;

import com.google.common.collect.ImmutableMap;
import grakn.core.common.config.Config;
import grakn.core.common.config.ConfigKey;
import grakn.core.common.exception.ErrorMessage;
import grakn.core.server.kb.Schema;
import grakn.core.server.session.optimisation.JanusPreviousPropertyStepStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.LazyBarrierStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.PathRetractionStrategy;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.janusgraph.core.EdgeLabel;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.Namifiable;
import org.janusgraph.core.PropertyKey;
import org.janusgraph.core.RelationType;
import org.janusgraph.core.VertexLabel;
import org.janusgraph.core.schema.JanusGraphIndex;
import org.janusgraph.core.schema.JanusGraphManagement;
import org.janusgraph.graphdb.database.StandardJanusGraph;
import org.janusgraph.graphdb.transaction.StandardJanusGraphTx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static java.util.Arrays.stream;


/**
 * This produces a JanusGraph with custom Grakn configurations.
 */
final public class JanusGraphFactory {
    private final static Logger LOG = LoggerFactory.getLogger(JanusGraphFactory.class);
    private static final AtomicBoolean strategiesApplied = new AtomicBoolean(false);
    private static final String JANUS_PREFIX = "janusmr.ioformat.conf.";
    private static final String STORAGE_BACKEND = "storage.backend";
    private static final String STORAGE_HOSTNAME = ConfigKey.STORAGE_HOSTNAME.name();
    private static final String STORAGE_KEYSPACE = ConfigKey.STORAGE_KEYSPACE.name();
    private static final String STORAGE_BATCH_LOADING = ConfigKey.STORAGE_BATCH_LOADING.name();
    private static final String STORAGE_REPLICATION_FACTOR = ConfigKey.STORAGE_REPLICATION_FACTOR.name();

    //These properties are loaded in by default and can optionally be overwritten
    private static final Properties DEFAULT_PROPERTIES;

    static {
        String DEFAULT_CONFIG = "resources/default-configs.properties";
        DEFAULT_PROPERTIES = new Properties();
        try (InputStream in = JanusGraphFactory.class.getClassLoader().getResourceAsStream(DEFAULT_CONFIG)) {
            DEFAULT_PROPERTIES.load(in);
        } catch (IOException e) {
            throw new RuntimeException(ErrorMessage.INVALID_PATH_TO_CONFIG.getMessage(DEFAULT_CONFIG), e);
        }
    }

    private Config config;

    public JanusGraphFactory(Config config) {
        this.config = config;
    }

    public Config config() {
        return config;
    }

    /**
     * This map is used to override hidden config files.
     * The key of the map refers to the key of the properties file that gets passed in which provides the value to be injected.
     * The value of the map specifies the key to inject into.
     */
    private static final Map<String, String> janusConfig = ImmutableMap.of(
            STORAGE_BACKEND, JANUS_PREFIX + STORAGE_BACKEND,
            STORAGE_HOSTNAME, JANUS_PREFIX + STORAGE_HOSTNAME,
            STORAGE_REPLICATION_FACTOR, JANUS_PREFIX + STORAGE_REPLICATION_FACTOR
    );

    public synchronized StandardJanusGraph openGraph(String keyspace) {
        StandardJanusGraph janusGraph = configureGraph(keyspace, config);
        buildJanusIndexes(janusGraph);
        janusGraph.tx().onClose(org.apache.tinkerpop.gremlin.structure.Transaction.CLOSE_BEHAVIOR.ROLLBACK);
        if (!strategiesApplied.getAndSet(true)) {
            TraversalStrategies strategies = TraversalStrategies.GlobalCache.getStrategies(StandardJanusGraph.class);
            strategies = strategies.clone().addStrategies(new JanusPreviousPropertyStepStrategy());
            //TODO: find out why Tinkerpop added these strategies. They result in many NoOpBarrier steps which slowed down our queries so we had to remove them.
            strategies.removeStrategies(PathRetractionStrategy.class, LazyBarrierStrategy.class);
            TraversalStrategies.GlobalCache.registerStrategies(StandardJanusGraph.class, strategies);
            TraversalStrategies.GlobalCache.registerStrategies(StandardJanusGraphTx.class, strategies);
        }

        return janusGraph;
    }

    public void drop(String keyspace) {
        try {
            JanusGraph graph = openGraph(keyspace);
            graph.close();
            org.janusgraph.core.JanusGraphFactory.drop(graph);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    private static StandardJanusGraph configureGraph(String keyspace, Config config) {
        org.janusgraph.core.JanusGraphFactory.Builder builder = org.janusgraph.core.JanusGraphFactory.build().
                set(STORAGE_HOSTNAME, config.getProperty(ConfigKey.STORAGE_HOSTNAME)).
                set(STORAGE_KEYSPACE, keyspace).
                set(STORAGE_BATCH_LOADING, false);

        //Load Defaults
        DEFAULT_PROPERTIES.forEach((key, value) -> builder.set(key.toString(), value));

        //Load Passed in properties
        config.properties().forEach((key, value) -> {
            //Inject properties into other default properties
            if (janusConfig.containsKey(key)) {
                builder.set(janusConfig.get(key), value);
            }

            builder.set(key.toString(), value);
        });

        LOG.debug("Opening graph {}", keyspace);
        return (StandardJanusGraph) builder.open();
    }


    private static void buildJanusIndexes(JanusGraph graph) {
        JanusGraphManagement management = graph.openManagement();

        makeVertexLabels(management);
        makeEdgeLabels(management);
        makePropertyKeys(management);

        makeIndicesVertexCentric(management);
        makeIndicesComposite(management);

        management.commit();
    }

    private static void makeEdgeLabels(JanusGraphManagement management) {
        for (Schema.EdgeLabel edgeLabel : Schema.EdgeLabel.values()) {
            EdgeLabel label = management.getEdgeLabel(edgeLabel.getLabel());
            if (label == null) {
                management.makeEdgeLabel(edgeLabel.getLabel()).make();
            }
        }
    }

    private static void makeVertexLabels(JanusGraphManagement management) {
        for (Schema.BaseType baseType : Schema.BaseType.values()) {
            VertexLabel foundLabel = management.getVertexLabel(baseType.name());
            if (foundLabel == null) {
                management.makeVertexLabel(baseType.name()).make();
            }
        }
    }

    private static void makeIndicesVertexCentric(JanusGraphManagement management) {
        ResourceBundle keys = ResourceBundle.getBundle("resources/indices-edges");
        Set<String> edgeLabels = keys.keySet();
        for (String edgeLabel : edgeLabels) {
            String[] propertyKeyStrings = keys.getString(edgeLabel).split(",");

            //Get all the property keys we need
            Set<PropertyKey> propertyKeys = stream(propertyKeyStrings).map(keyId -> {
                PropertyKey key = management.getPropertyKey(keyId);
                if (key == null) {
                    throw new RuntimeException("Trying to create edge index on label [" + edgeLabel + "] but the property [" + keyId + "] does not exist");
                }
                return key;
            }).collect(Collectors.toSet());

            //Get the edge and indexing information
            RelationType relationType = management.getRelationType(edgeLabel);
            EdgeLabel label = management.getEdgeLabel(edgeLabel);

            //Create index on each property key
            for (PropertyKey key : propertyKeys) {
                if (management.getRelationIndex(relationType, edgeLabel + "by" + key.name()) == null) {
                    management.buildEdgeIndex(label, edgeLabel + "by" + key.name(), Direction.BOTH, Order.decr, key);
                }
            }

            //Create index on all property keys
            String propertyKeyId = propertyKeys.stream().map(Namifiable::name).collect(Collectors.joining("_"));
            if (management.getRelationIndex(relationType, edgeLabel + "by" + propertyKeyId) == null) {
                PropertyKey[] allKeys = propertyKeys.toArray(new PropertyKey[propertyKeys.size()]);
                management.buildEdgeIndex(label, edgeLabel + "by" + propertyKeyId, Direction.BOTH, Order.decr, allKeys);
            }
        }
    }

    private static void makePropertyKeys(JanusGraphManagement management) {
        stream(Schema.VertexProperty.values()).forEach(property ->
                makePropertyKey(management, property.name(), property.getPropertyClass()));

        stream(Schema.EdgeProperty.values()).forEach(property ->
                makePropertyKey(management, property.name(), property.getPropertyClass()));
    }

    private static void makePropertyKey(JanusGraphManagement management, String propertyKey, Class type) {
        if (management.getPropertyKey(propertyKey) == null) {
            management.makePropertyKey(propertyKey).dataType(type).make();
        }
    }

    private static void makeIndicesComposite(JanusGraphManagement management) {
        ResourceBundle keys = ResourceBundle.getBundle("resources/indices-composite");
        Set<String> keyString = keys.keySet();
        for (String propertyKeyLabel : keyString) {
            String indexLabel = "by" + propertyKeyLabel;
            JanusGraphIndex index = management.getGraphIndex(indexLabel);

            if (index == null) {
                boolean isUnique = Boolean.parseBoolean(keys.getString(propertyKeyLabel));
                PropertyKey key = management.getPropertyKey(propertyKeyLabel);
                JanusGraphManagement.IndexBuilder indexBuilder = management.buildIndex(indexLabel, Vertex.class).addKey(key);
                if (isUnique) {
                    indexBuilder.unique();
                }
                indexBuilder.buildCompositeIndex();
            }
        }
    }
}

