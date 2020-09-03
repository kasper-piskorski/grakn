/*
 * Copyright (C) 2020 Grakn Labs
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
 *
 */

package grakn.core.graph.util;

import grakn.core.graph.iid.PrefixIID;
import grakn.core.graph.iid.VertexIID;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static grakn.core.common.collection.Bytes.join;
import static grakn.core.common.collection.Bytes.longToSortedBytes;
import static grakn.core.common.collection.Bytes.shortToSortedBytes;
import static grakn.core.common.iterator.Iterators.filter;
import static java.nio.ByteBuffer.wrap;
import static java.util.Arrays.copyOfRange;

public abstract class KeyGenerator {

    protected final ConcurrentMap<PrefixIID, AtomicInteger> typeKeys;
    protected final ConcurrentMap<VertexIID.Type, AtomicLong> thingKeys;
    protected final int initialValue;
    protected final int delta;

    KeyGenerator(int initialValue, int delta) {
        typeKeys = new ConcurrentHashMap<>();
        thingKeys = new ConcurrentHashMap<>();
        this.initialValue = initialValue;
        this.delta = delta;
    }

    public byte[] forType(PrefixIID root) {
        return shortToSortedBytes(typeKeys.computeIfAbsent(
                root, k -> new AtomicInteger(initialValue)
        ).getAndAdd(delta));
    }

    public byte[] forThing(VertexIID.Type typeIID) {
        return longToSortedBytes(thingKeys.computeIfAbsent(
                typeIID, k -> new AtomicLong(initialValue)
        ).getAndAdd(delta));
    }

    public static class Buffered extends KeyGenerator {

        public Buffered() {
            super(Encoding.Key.BUFFERED.initialValue(), Encoding.Key.BUFFERED.isIncrement() ? 1 : -1);
        }
    }

    public static class Persisted extends KeyGenerator {

        public Persisted() {
            super(Encoding.Key.PERSISTED.initialValue(), Encoding.Key.PERSISTED.isIncrement() ? 1 : -1);
        }

        public void sync(Storage storage) {
            syncTypeKeys(storage);
            syncThingKeys(storage);
        }

        private void syncTypeKeys(Storage storage) {
            for (Encoding.Vertex.Type encoding : Encoding.Vertex.Type.values()) {
                byte[] prefix = encoding.prefix().bytes();
                byte[] lastIID = storage.getLastKey(prefix);
                AtomicInteger nextValue = lastIID != null ?
                        new AtomicInteger(wrap(copyOfRange(lastIID, PrefixIID.LENGTH, VertexIID.Type.LENGTH)).getShort() + delta) :
                        new AtomicInteger(initialValue);
                typeKeys.put(PrefixIID.of(encoding), nextValue);
            }
        }

        private void syncThingKeys(Storage storage) {
            Encoding.Vertex.Thing[] thingsWithGeneratedIID = new Encoding.Vertex.Thing[]{
                    Encoding.Vertex.Thing.ENTITY, Encoding.Vertex.Thing.RELATION, Encoding.Vertex.Thing.ROLE
            };

            for (Encoding.Vertex.Thing thingEncoding : thingsWithGeneratedIID) {
                byte[] typeEncoding = Encoding.Vertex.Type.of(thingEncoding).prefix().bytes();
                Iterator<byte[]> typeIterator = filter(storage.iterate(typeEncoding, (iid, value) -> iid),
                                                       iid -> iid.length == VertexIID.Type.LENGTH);
                while (typeIterator.hasNext()) {
                    byte[] typeIID = typeIterator.next();
                    byte[] prefix = join(thingEncoding.prefix().bytes(), typeIID);
                    byte[] lastIID = storage.getLastKey(prefix);
                    AtomicLong nextValue = lastIID != null ?
                            new AtomicLong(wrap(
                                    copyOfRange(lastIID, VertexIID.Thing.PREFIX_W_TYPE_LENGTH, VertexIID.Thing.DEFAULT_LENGTH)
                            ).getShort() + delta) :
                            new AtomicLong(initialValue);
                    thingKeys.put(VertexIID.Type.of(typeIID), nextValue);
                }
            }
        }
    }
}
