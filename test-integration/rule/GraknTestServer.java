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

package grakn.core.rule;

import com.datastax.driver.core.Cluster;
import grakn.core.common.config.Config;
import grakn.core.common.config.ConfigKey;
import grakn.core.server.GraknStorage;
import grakn.core.server.Server;
import grakn.core.server.ServerFactory;
import grakn.core.server.keyspace.KeyspaceImpl;
import grakn.core.server.keyspace.KeyspaceManager;
import grakn.core.server.rpc.KeyspaceRequestsHandler;
import grakn.core.server.rpc.KeyspaceService;
import grakn.core.server.rpc.OpenRequest;
import grakn.core.server.rpc.ServerKeyspaceRequestsHandler;
import grakn.core.server.rpc.ServerOpenRequest;
import grakn.core.server.rpc.SessionService;
import grakn.core.server.session.HadoopGraphFactory;
import grakn.core.server.session.JanusGraphFactory;
import grakn.core.server.session.SessionFactory;
import grakn.core.server.session.SessionImpl;
import grakn.core.server.util.LockManager;
import io.grpc.ServerBuilder;
import org.apache.commons.io.FileUtils;
import org.junit.rules.ExternalResource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * This rule is a test server rule which starts Cassandra and Grakn Core Server on random, unused ports.
 * It allows multiple test server instances to run concurrently.
 * It enables all of the integration tests to run concurrently on the same machine.
 */
public class GraknTestServer extends ExternalResource {
    protected static final Path DEFAULT_SERVER_CONFIG_PATH = Paths.get("server/conf/grakn.properties");
    protected static final Path DEFAULT_CASSANDRA_CONFIG_PATH = Paths.get("test-integration/resources/cassandra-embedded.yaml");

    // Grakn Core Server
    protected final Path serverConfigPath;
    protected Config serverConfig;
    protected Server graknServer;
    protected int grpcPort;
    protected KeyspaceManager keyspaceManager;
    protected SessionFactory sessionFactory;
    protected Path dataDirTmp;

    // Cassandra
    protected final Path originalCassandraConfigPath;
    protected File updatedCassandraConfigPath;
    protected int storagePort;
    protected int nativeTransportPort;
    protected int thriftPort;


    public GraknTestServer() {
        this(DEFAULT_SERVER_CONFIG_PATH, DEFAULT_CASSANDRA_CONFIG_PATH);
    }

    public GraknTestServer(Path serverConfigPath, Path cassandraConfigPath) {
        System.setProperty("java.security.manager", "nottodaypotato");
        this.serverConfigPath = serverConfigPath;
        this.originalCassandraConfigPath = cassandraConfigPath;
    }

    @Override
    protected void before() {
        try {
            // Start Cassandra
            System.out.println("Starting Grakn Storage...");
            generateCassandraRandomPorts();
            updatedCassandraConfigPath = buildCassandraConfigWithRandomPorts();
            System.setProperty("cassandra.config", "file:" + updatedCassandraConfigPath.getAbsolutePath());
            System.setProperty("cassandra-foreground", "true");
            GraknStorage.main(new String[]{});
            System.out.println("Grakn Storage started");

            // Start Grakn Core Server
            grpcPort = findUnusedLocalPort();
            dataDirTmp = Files.createTempDirectory("db-for-test");
            serverConfig = createTestConfig(dataDirTmp.toString());
            System.out.println("Starting Grakn Core Server...");
            graknServer = createServer();
            graknServer.start();
            System.out.println("Grakn Core Server started");
        } catch (IOException e) {
            throw new RuntimeException("Cannot start Grakn Core Server", e);
        }
    }

    @Override
    protected void after() {
        try {
            graknServer.close();
            FileUtils.deleteDirectory(dataDirTmp.toFile());
            updatedCassandraConfigPath.delete();
        } catch (Exception e) {
            throw new RuntimeException("Could not shut down ", e);
        }
    }


    // Getters

    public String grpcUri() {
        return serverConfig.getProperty(ConfigKey.SERVER_HOST_NAME) + ":" + serverConfig.getProperty(ConfigKey.GRPC_PORT);
    }

    public SessionImpl sessionWithNewKeyspace() {
        KeyspaceImpl randomKeyspace = KeyspaceImpl.of("a" + UUID.randomUUID().toString().replaceAll("-", ""));
        return session(randomKeyspace);
    }

    public SessionImpl session(KeyspaceImpl keyspace) {
        return sessionFactory.session(keyspace);
    }

    public SessionFactory sessionFactory() {
        return sessionFactory;
    }

    public Config serverConfig() {
        return serverConfig;
    }

    // Cassandra Helpers

    protected void generateCassandraRandomPorts() throws IOException {
        storagePort = findUnusedLocalPort();
        nativeTransportPort = findUnusedLocalPort();
        thriftPort = findUnusedLocalPort();
    }

    protected File buildCassandraConfigWithRandomPorts() throws IOException {
        byte[] bytes = Files.readAllBytes(originalCassandraConfigPath);
        String configString = new String(bytes, StandardCharsets.UTF_8);

        configString = configString + "\nstorage_port: " + storagePort;
        configString = configString + "\nnative_transport_port: " + nativeTransportPort;
        configString = configString + "\nrpc_port: " + thriftPort;
        InputStream configStream = new ByteArrayInputStream(configString.getBytes(StandardCharsets.UTF_8));

        String directory = "target/embeddedCassandra";
        org.apache.cassandra.io.util.FileUtils.createDirectory(directory);
        Path copyName = Paths.get(directory, "cassandra-embedded.yaml");
        // Create file in directory we just created and copy the stream content into it.
        Files.copy(configStream, copyName);
        return copyName.toFile();
    }

    protected synchronized static int findUnusedLocalPort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }

    // Grakn Core Server helpers

    protected Config createTestConfig(String dataDir) throws FileNotFoundException {
        InputStream testConfig = new FileInputStream(serverConfigPath.toFile());

        Config config = Config.read(testConfig);
        config.setConfigProperty(ConfigKey.DATA_DIR, dataDir);
        //Override gRPC port with a random free port
        config.setConfigProperty(ConfigKey.GRPC_PORT, grpcPort);
        //Override Storage Port used by Janus to communicate with Cassandra Backend
        config.setConfigProperty(ConfigKey.STORAGE_PORT, thriftPort);

        //Override ports used by HadoopGraph
        config.setConfigProperty(ConfigKey.HADOOP_STORAGE_PORT, thriftPort);
        config.setConfigProperty(ConfigKey.STORAGE_CQL_NATIVE_PORT, nativeTransportPort);

        return config;
    }

    private Server createServer() {
        // distributed locks
        LockManager lockManager = new LockManager();
        JanusGraphFactory janusGraphFactory = new JanusGraphFactory(serverConfig);
        HadoopGraphFactory hadoopGraphFactory = new HadoopGraphFactory(serverConfig);
        Cluster cluster = Cluster.builder()
                .addContactPoint(serverConfig.getProperty(ConfigKey.STORAGE_HOSTNAME))
                .withPort(serverConfig.getProperty(ConfigKey.STORAGE_CQL_NATIVE_PORT))
                .build();
        keyspaceManager = new KeyspaceManager(cluster);
        sessionFactory = new SessionFactory(lockManager, janusGraphFactory, hadoopGraphFactory, serverConfig);

        OpenRequest requestOpener = new ServerOpenRequest(sessionFactory);

        KeyspaceRequestsHandler requestsHandler = new ServerKeyspaceRequestsHandler(
                keyspaceManager, sessionFactory, janusGraphFactory);

        io.grpc.Server serverRPC = ServerBuilder.forPort(grpcPort)
                .addService(new SessionService(requestOpener))
                .addService(new KeyspaceService(requestsHandler))
                .build();

        return ServerFactory.createServer(serverRPC);
    }
}
