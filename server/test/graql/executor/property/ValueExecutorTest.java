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

package grakn.core.graql.executor.property;

import grakn.core.graql.executor.property.value.ValueOperation;
import grakn.core.graql.executor.property.value.ValueComparison;
import graql.lang.Graql;
import graql.lang.property.ValueProperty;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ValueExecutorTest {

    @Test
    public void regexPredicateInterpretsCharacterClassesCorrectly() {
        ValueComparison.String predicate = new ValueComparison.String(Graql.Token.Comparator.LIKE, "\\d");

        assertTrue(predicate.test("0"));
        assertTrue(predicate.test("1"));
        assertFalse(predicate.test("a"));
    }

    @Test
    public void regexPredicateInterpretsQuotesCorrectly() {
        ValueComparison.String predicate = new ValueComparison.String(Graql.Token.Comparator.LIKE, "\"");

        assertTrue(predicate.test("\""));
        assertFalse(predicate.test("\\\""));
    }

    @Test
    public void regexPredicateInterpretsBackslashesCorrectly() {
        ValueComparison.String predicate = new ValueComparison.String(Graql.Token.Comparator.LIKE, "\\\\");

        assertTrue(predicate.test("\\"));
        assertFalse(predicate.test("\\\\"));
    }

    @Test
    public void regexPredicateInterpretsNewlineCorrectly() {
        ValueComparison.String predicate = new ValueComparison.String(Graql.Token.Comparator.LIKE, "\\n");

        assertTrue(predicate.test("\n"));
        assertFalse(predicate.test("\\n"));
    }

    @Test
    public void regexPredicateToStringDoesNotEscapeMostThings() {
        ValueProperty.Operation.Comparison.String predicate = new ValueProperty.Operation
                .Comparison.String(Graql.Token.Comparator.LIKE, "don't escape these: \\d, \", \n ok");

        assertEquals(Graql.Token.Comparator.LIKE + " \"don't escape these: \\d, \", \n ok\"", predicate.toString());
    }

    @Test
    public void regexPredicateToStringEscapesForwardSlashes() {
        ValueProperty.Operation.Comparison.String predicate = new ValueProperty.Operation
                .Comparison.String(Graql.Token.Comparator.LIKE, "escape this: / ok");

        assertEquals(Graql.Token.Comparator.LIKE + " \"escape this: \\/ ok\"", predicate.toString());
    }

    @Test
    public void whenNumberOperationsAreCompared_compatibilityIsRecognisedCorrectly() {
        //TODO
    }

    @Test
    public void whenStringOperationsAreCompared_compatibilityIsRecognisedCorrectly() {
        //TODO
    }

    @Test
    public void whenVariableOperationsAreCompared_compatibilityIsRecognisedCorrectly() {
        //TODO
    }

    @Test
    public void whenDateOperationsAreCompared_compatibilityIsRecognisedCorrectly() {
        //TODO
    }


    @Test
    public void whenNumberOperationsAreCompared_subsumptionIsRecognisedCorrectly() {
        final long leftBound = 1337L;
        final long rightBound = 1667L;
        ValueOperation<?, ?> eqL = ValueOperation.of(new ValueProperty.Operation.Comparison.Number<>(Graql.Token.Comparator.EQV, leftBound));
        ValueOperation<?, ?> gteL = ValueOperation.of(new ValueProperty.Operation.Comparison.Number<>(Graql.Token.Comparator.GTE, leftBound));
        ValueOperation<?, ?> gtL = ValueOperation.of(new ValueProperty.Operation.Comparison.Number<>(Graql.Token.Comparator.GT, leftBound));
        ValueOperation<?, ?> lteL = ValueOperation.of(new ValueProperty.Operation.Comparison.Number<>(Graql.Token.Comparator.LTE, leftBound));
        ValueOperation<?, ?> ltL = ValueOperation.of(new ValueProperty.Operation.Comparison.Number<>(Graql.Token.Comparator.LT, leftBound));
        ValueOperation<?, ?> neqL = ValueOperation.of(new ValueProperty.Operation.Comparison.Number<>(Graql.Token.Comparator.NEQV, leftBound));

        subsumes(eqL, Arrays.asList(gteL, lteL), Arrays.asList(neqL, gtL, ltL));
        subsumes(gteL, Collections.emptyList(), Arrays.asList(eqL, gtL, lteL, ltL, neqL));
        subsumes(gtL, Collections.singletonList(gteL), Arrays.asList(eqL, lteL, ltL, neqL));
        subsumes(lteL, Collections.emptyList(), Arrays.asList(eqL, gteL, gtL, ltL, neqL));
        subsumes(ltL, Collections.singletonList(lteL), Arrays.asList(eqL, gteL, gtL, neqL));
        subsumes(neqL, Collections.emptyList(), Arrays.asList(eqL, gteL, gtL, lteL, ltL));

        ValueOperation<?, ?> eqR = ValueOperation.of(new ValueProperty.Operation.Comparison.Number<>(Graql.Token.Comparator.EQV, rightBound));
        ValueOperation<?, ?> gteR = ValueOperation.of(new ValueProperty.Operation.Comparison.Number<>(Graql.Token.Comparator.GTE, rightBound));
        ValueOperation<?, ?> gtR = ValueOperation.of(new ValueProperty.Operation.Comparison.Number<>(Graql.Token.Comparator.GT, rightBound));
        ValueOperation<?, ?> lteR = ValueOperation.of(new ValueProperty.Operation.Comparison.Number<>(Graql.Token.Comparator.LTE, rightBound));
        ValueOperation<?, ?> ltR = ValueOperation.of(new ValueProperty.Operation.Comparison.Number<>(Graql.Token.Comparator.LT, rightBound));
        ValueOperation<?, ?> neqR = ValueOperation.of(new ValueProperty.Operation.Comparison.Number<>(Graql.Token.Comparator.NEQV, rightBound));
    }

    @Test
    public void whenStringOperationsAreCompared_subsumptionIsRecognisedCorrectly() {
        //TODO
    }

    @Test
    public void whenVariableOperationsAreCompared_subsumptionIsRecognisedCorrectly() {
        //TODO
    }

    @Test
    public void whenDateOperationsAreCompared_subsumptionIsRecognisedCorrectly() {
        //TODO
    }

    private void subsumes(ValueOperation<?, ?> src, List<ValueOperation<?, ?>> subsumes, List<ValueOperation<?, ?>> subsumesNot){
        assertTrue("Unexpected subsumption outcome: " + src + " !<= " + src, src.subsumes(src));
        subsumes.forEach(tgt -> assertTrue("Unexpected subsumption outcome: " + src + " !<= " + tgt, src.subsumes(tgt)));
        subsumesNot.forEach(tgt -> assertFalse("Unexpected subsumption outcome: " + src + " !>= " + tgt, src.subsumes(tgt)));
    }
}