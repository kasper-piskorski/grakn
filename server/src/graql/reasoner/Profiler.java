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

package grakn.core.graql.reasoner;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Profiler {

    private final Map<String, Long> registeredTimes = new HashMap<>();
    private final Map<String, Long> registeredCalls = new HashMap<>();
    private static final Logger LOG = LoggerFactory.getLogger(Profiler.class);

    public void updateTime(String name, long increment){
        Long match = registeredTimes.get(name);
        registeredTimes.put(name, match != null? (match + increment) : increment);
    }

    public void updateCallCount(String name){
        Long match = registeredCalls.get(name);
        registeredCalls.put(name, match != null? (match + 1) : 1);
    }

    public long getTime(String name){ return registeredTimes.get(name);}
    public long getCount(String name){ return registeredCalls.get(name);}

    public void print(){
        registeredTimes.entrySet().stream()
                .filter(e -> e.getValue() > cutOff)
                .sorted(Comparator.comparing(Map.Entry::getValue))
                .forEach(System.out::println);
        System.out.println();

        registeredCalls.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getValue))
                .forEach(System.out::println);
        System.out.println();
    }

    private static long cutOff = 200;

    public void logTimes(){

        registeredTimes.entrySet().stream()
                .filter(e -> e.getValue() > cutOff)
                .sorted(Comparator.comparing(Map.Entry::getValue))
                .forEach(e -> LOG.debug(e.toString()));
        LOG.debug("");

        registeredCalls.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(e -> LOG.debug(e.toString()));
        clear();

    }

    public void clear(){
        registeredTimes.clear();
        registeredCalls.clear();
    }
}
