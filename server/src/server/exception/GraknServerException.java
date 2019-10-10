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

package grakn.core.server.exception;

import grakn.core.common.exception.ErrorMessage;
import grakn.core.common.exception.GraknException;
import grakn.core.server.keyspace.Keyspace;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;

import static grakn.core.common.exception.ErrorMessage.INITIALIZATION_EXCEPTION;

/**
 * Backend Grakn Exception
 * Failures which occur server side are wrapped in this exception.
 * This can include but is not limited to:
 * - Cassandra Timeouts
 * - Malformed Requests
 */
public class GraknServerException extends GraknException {

    GraknServerException(String error) {
        super(error);
    }

    GraknServerException(String error, Exception e) {
        super(error, e);
    }

    @CheckReturnValue
    public static GraknServerException unreachableStatement(Exception cause) {
        return unreachableStatement(null, cause);
    }

    @CheckReturnValue
    public static GraknServerException unreachableStatement(String message) {
        return unreachableStatement(message, null);
    }

    @CheckReturnValue
    private static GraknServerException unreachableStatement(@Nullable String message, Exception cause) {
        return new GraknServerException("Statement expected to be unreachable: " + message, cause);
    }

    @Override
    public String getName() {
        return this.getClass().getName();
    }

    public static GraknServerException create(String error) {
        return new GraknServerException(error);
    }

    public static GraknServerException initializationException(Keyspace keyspace) {
        return create(INITIALIZATION_EXCEPTION.getMessage(keyspace));
    }

    public static GraknServerException invalidPIDException(String pid) {
        return create(ErrorMessage.COULD_NOT_GET_PID.getMessage(pid));
    }

    public static GraknServerException fileWriteException(String filepath) {
        return create(ErrorMessage.FILE_WRITE_EXCEPTION.getMessage(filepath));
    }
}
