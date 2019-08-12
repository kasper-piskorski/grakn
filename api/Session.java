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

package grakn.core.api;

import javax.annotation.CheckReturnValue;

/**
 * This class facilitates the construction of Grakn Graphs by determining which session should be built.
 * The graphs produced by a session are singletons bound to a specific keyspace.
 */
public interface Session extends AutoCloseable {

    /**
     * Gets a new transaction bound to the keyspace of this Session.
     *
     * @return A a Transaction Builder object
     */
    @CheckReturnValue
    Transaction.Builder transaction();

    /**
     * Closes the main connection to the graph. This should be done at the end of using the graph.
     */
    void close();

    /**
     * Use to determine which Keyspace this Session is interacting with.
     *
     * @return The Keyspace of the knowledge base this Session is interacting with.
     */
    Keyspace keyspace();
}
