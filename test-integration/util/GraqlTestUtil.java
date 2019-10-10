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

package grakn.core.util;

import grakn.core.concept.Label;
import grakn.core.concept.thing.Attribute;
import grakn.core.concept.thing.Thing;
import grakn.core.concept.type.EntityType;
import grakn.core.server.session.Session;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.Graql;
import graql.lang.pattern.Pattern;
import graql.lang.query.MatchClause;
import org.apache.commons.collections.CollectionUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Helper methods for writing tests for Graql
 *
 */
@SuppressWarnings("CheckReturnValue")
public class GraqlTestUtil {

    public static void assertExists(TransactionOLTP tx, Pattern... patterns) {
        assertTrue(tx.stream(Graql.match(patterns)).iterator().hasNext());
    }

    public static void assertExists(TransactionOLTP tx, MatchClause matchClause) {
        assertTrue(tx.stream(matchClause).iterator().hasNext());
    }

    public static void assertNotExists(TransactionOLTP tx, Pattern... patterns) {
        assertFalse(tx.stream(Graql.match(patterns)).iterator().hasNext());
    }

    public static void assertNotExists(TransactionOLTP tx, MatchClause matchClause) {
        assertFalse(tx.stream(matchClause).iterator().hasNext());
    }

    public static <T> void assertCollectionsEqual(Collection<T> c1, Collection<T> c2) {
        assertTrue("\nc1: " + c1 + "\n!=\nc2: " + c2 , CollectionUtils.isEqualCollection(c1, c2));
    }

    public static <T> void assertCollectionsEqual(String msg, Collection<T> c1, Collection<T> c2) {
        assertTrue(msg, CollectionUtils.isEqualCollection(c1, c2));
    }

    public static <T> void assertCollectionsNonTriviallyEqual(Collection<T> c1, Collection<T> c2){
        assertFalse("Trivial equality!", c1.isEmpty() && c2.isEmpty());
        assertCollectionsEqual(c1, c2);
    }

    public static <T> void assertCollectionsNonTriviallyEqual(String msg, Collection<T> c1, Collection<T> c2){
        assertFalse("Trivial equality!", c1.isEmpty() && c2.isEmpty());
        assertCollectionsEqual(msg, c1, c2);
    }

    public static void loadFromFile(String gqlPath, String file, TransactionOLTP tx) {
        try {
            System.out.println("Loading... " + gqlPath + file);
            InputStream inputStream = GraqlTestUtil.class.getClassLoader().getResourceAsStream(gqlPath + file);
            String s = new BufferedReader(new InputStreamReader(inputStream)).lines().collect(Collectors.joining("\n"));
            Graql.parseList(s).forEach(tx::execute);
        } catch (Exception e) {
            System.err.println(e);
            throw new RuntimeException(e);
        }
    }

    public static void loadFromFileAndCommit(String gqlPath, String file, Session session) {
        TransactionOLTP tx = session.transaction().write();
        loadFromFile(gqlPath, file, tx);
        tx.commit();
    }


    public static Thing putEntityWithResource(TransactionOLTP tx, String id, EntityType type, Label key) {
        Thing inst = type.create();
        Attribute attributeInstance = tx.getAttributeType(key.getValue()).create(id);
        inst.has(attributeInstance);
        return inst;
    }

    public static Thing getInstance(TransactionOLTP tx, String id){
        Set<Thing> things = tx.getAttributesByValue(id)
                .stream().flatMap(Attribute::owners).collect(toSet());
        if (things.size() != 1) {
            throw new IllegalStateException("Multiple things with given resource value");
        }
        return things.iterator().next();
    }
}
