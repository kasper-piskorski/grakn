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
 *
 */

package grakn.core.server.session;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import grakn.core.kb.concept.api.Label;
import grakn.core.kb.server.ShardManager;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ShardManagerImpl implements ShardManager {
    private final static int TIMEOUT_MINUTES_ATTRIBUTES_CACHE = 2;
    private final static int ATTRIBUTES_CACHE_MAX_SIZE = 10000;

    private final Cache<Label, Long> shardsEphemeral;

    public ShardManagerImpl(){
        this.shardsEphemeral = CacheBuilder.newBuilder()
                .expireAfterAccess(TIMEOUT_MINUTES_ATTRIBUTES_CACHE, TimeUnit.MINUTES)
                .maximumSize(ATTRIBUTES_CACHE_MAX_SIZE)
                .build();
    }

    public Long getEphemeralShardCount(Label type){ return shardsEphemeral.getIfPresent(type);}
    public void updateEphemeralShardCount(Label type, Long count){ shardsEphemeral.put(type, count);}

    private void ackShardCommit(Label type, String txId) {
    }

    @Override
    public void ackCommit(Set<Label> labels, String txId) {
        labels.forEach(label -> ackShardCommit(label, txId));
    }

}
