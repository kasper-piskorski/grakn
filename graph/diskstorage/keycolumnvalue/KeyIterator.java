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

package grakn.core.graph.diskstorage.keycolumnvalue;

import grakn.core.graph.diskstorage.Entry;
import grakn.core.graph.diskstorage.StaticBuffer;
import grakn.core.graph.diskstorage.util.RecordIterator;


public interface KeyIterator extends RecordIterator<StaticBuffer> {

    /**
     * Returns an iterator over all entries associated with the current
     * key that match the column range specified in the query.
     * <p>
     * Closing the returned sub-iterator has no effect on this iterator.
     *
     * Calling {@link #next()} might close previously returned RecordIterators
     * depending on the implementation, hence it is important to iterate over
     * (and close) the RecordIterator before calling {@link #next()} or {@link #hasNext()}.
     *
     */
    RecordIterator<Entry> getEntries();

}
