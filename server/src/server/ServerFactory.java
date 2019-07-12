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

package grakn.core.server;

import grakn.benchmark.lib.instrumentation.ServerTracing;
import grakn.core.common.config.Config;
import grakn.core.common.config.ConfigKey;
import grakn.core.server.keyspace.KeyspaceManager;
import grakn.core.server.rpc.KeyspaceService;
import grakn.core.server.rpc.OpenRequest;
import grakn.core.server.rpc.ServerOpenRequest;
import grakn.core.server.rpc.SessionService;
import grakn.core.server.session.JanusGraphFactory;
import grakn.core.server.session.SessionFactory;
import grakn.core.server.util.ServerID;
import grakn.core.server.util.LockManager;
import io.grpc.ServerBuilder;

/**
 * This is a factory class which contains methods for instantiating a Server in different ways.
 */
public class ServerFactory {
    /**
     * Create a Server configured for Grakn Core.
     *
     * @return a Server instance configured for Grakn Core
     */
    public static Server createServer(boolean benchmark) {
        // Grakn Server configuration
        ServerID serverID = ServerID.me();
        Config config = Config.create();

        JanusGraphFactory janusGraphFactory = new JanusGraphFactory(config);

        // locks
        LockManager lockManager = new LockManager();

        KeyspaceManager keyspaceStore = new KeyspaceManager(janusGraphFactory, config);

        // session factory
        SessionFactory sessionFactory = new SessionFactory(lockManager, janusGraphFactory, keyspaceStore, config);

        // Enable server tracing
        if (benchmark) {
            ServerTracing.initInstrumentation("server-instrumentation");
        }

        // create gRPC server
        io.grpc.Server serverRPC = createServerRPC(config, sessionFactory, keyspaceStore, janusGraphFactory);

        return createServer(serverID, serverRPC, keyspaceStore);
    }

    /**
     * Allows the creation of a Server instance with various configurations
     *
     * @return a Server instance
     */

    public static Server createServer(ServerID serverID, io.grpc.Server rpcServer, KeyspaceManager keyspaceStore) {
        Server server = new Server(serverID, rpcServer, keyspaceStore);

        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "grakn-server-shutdown"));

        return server;
    }

    private static io.grpc.Server createServerRPC(Config config, SessionFactory sessionFactory, KeyspaceManager keyspaceStore, JanusGraphFactory janusGraphFactory) {
        int grpcPort = config.getProperty(ConfigKey.GRPC_PORT);
        OpenRequest requestOpener = new ServerOpenRequest(sessionFactory);

        SessionService sessionService = new SessionService(requestOpener);

        Runtime.getRuntime().addShutdownHook(new Thread(sessionService::shutdown, "session-service-shutdown"));

        return ServerBuilder.forPort(grpcPort)
                .addService(sessionService)
                .addService(new KeyspaceService(keyspaceStore, sessionFactory, janusGraphFactory))
                .build();
    }

}