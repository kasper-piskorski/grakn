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

package grakn.core.graql.reasoner.query;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.concept.type.EntityType;
import grakn.core.concept.type.Role;
import grakn.core.graql.reasoner.atom.Atom;
import grakn.core.graql.reasoner.atom.binary.AttributeAtom;
import grakn.core.graql.reasoner.atom.predicate.ValuePredicate;
import grakn.core.graql.reasoner.cache.SemanticDifference;
import grakn.core.graql.reasoner.cache.VariableDefinition;
import grakn.core.graql.reasoner.unifier.Unifier;
import grakn.core.graql.reasoner.utils.Pair;
import grakn.core.rule.GraknTestServer;
import grakn.core.server.session.SessionImpl;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.Graql;
import graql.lang.pattern.Conjunction;
import graql.lang.statement.Statement;
import graql.lang.statement.Variable;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static grakn.core.util.GraqlTestUtil.assertCollectionsEqual;
import static grakn.core.util.GraqlTestUtil.assertCollectionsNonTriviallyEqual;
import static grakn.core.util.GraqlTestUtil.loadFromFileAndCommit;
import static java.util.stream.Collectors.toSet;
import static junit.framework.TestCase.assertEquals;

@SuppressWarnings("Duplicates")
public class SemanticDifferenceIT {

    private static String resourcePath = "test-integration/graql/reasoner/resources/";
    
    @ClassRule
    public static final GraknTestServer server = new GraknTestServer();

    private static SessionImpl genericSchemaSession;

    @BeforeClass
    public static void loadContext(){
        genericSchemaSession = server.sessionWithNewKeyspace();
        loadFromFileAndCommit(resourcePath, "genericSchema.gql", genericSchemaSession);
    }

    @AfterClass
    public static void closeSession(){
        genericSchemaSession.close();
    }

    @Test
    public void whenChildSpecifiesType_typesAreFilteredCorrectly(){
        try(TransactionOLTP tx = genericSchemaSession.transaction().write()) {
            EntityType subRoleEntity = tx.getEntityType("subRoleEntity");
            String base = "(baseRole1: $x, baseRole2: $y) isa binary;";
            String parentPattern = patternise(base);
            String childPattern = patternise(base, "$x isa " + subRoleEntity.label() + ";");
            ReasonerAtomicQuery parent = ReasonerQueries.atomic(conjunction(parentPattern), tx);
            ReasonerAtomicQuery child = ReasonerQueries.atomic(conjunction(childPattern), tx);

            Set<Pair<Unifier, SemanticDifference>> semanticPairs = child.getMultiUnifierWithSemanticDiff(parent);
            Pair<Unifier, SemanticDifference> semanticPair = Iterables.getOnlyElement(semanticPairs);

            SemanticDifference expected = new SemanticDifference(
                    ImmutableSet.of(
                            new VariableDefinition(new Variable("x"), subRoleEntity, null, new HashSet<>(), new HashSet<>())
                    )
            );
            assertEquals(expected, semanticPair.getValue());
            Set<ConceptMap> childAnswers = tx.stream(child.getQuery(), false).collect(Collectors.toSet());
            Set<ConceptMap> propagatedAnswers = projectAnswersToChild(child, parent, semanticPair.getKey().inverse(), semanticPair.getValue());
            assertCollectionsNonTriviallyEqual(propagatedAnswers + "\n!=\n" + childAnswers + "\n", childAnswers, propagatedAnswers);
        }
    }

    @Test
    public void whenChildSpecialisesType_typesAreFilteredCorrectly(){
        try(TransactionOLTP tx = genericSchemaSession.transaction().write()) {
            EntityType baseRoleEntity = tx.getEntityType("baseRoleEntity");
            EntityType subRoleEntity = tx.getEntityType("subRoleEntity");
            String base = "(baseRole1: $x, baseRole2: $y) isa binary;";
            String parentPattern = patternise(base, "$x isa " + baseRoleEntity.label() + ";");
            String childPattern = patternise(base, "$x isa " + subRoleEntity.label() + ";");
            ReasonerAtomicQuery parent = ReasonerQueries.atomic(conjunction(parentPattern), tx);
            ReasonerAtomicQuery child = ReasonerQueries.atomic(conjunction(childPattern), tx);

            Set<Pair<Unifier, SemanticDifference>> semanticPairs = child.getMultiUnifierWithSemanticDiff(parent);
            Pair<Unifier, SemanticDifference> semanticPair = Iterables.getOnlyElement(semanticPairs);

            SemanticDifference expected = new SemanticDifference(
                    ImmutableSet.of(
                            new VariableDefinition(new Variable("x"), subRoleEntity, null, new HashSet<>(), new HashSet<>())
                    )
            );
            assertEquals(expected, semanticPair.getValue());
            Set<ConceptMap> childAnswers = tx.stream(child.getQuery(), false).collect(Collectors.toSet());
            Set<ConceptMap> propagatedAnswers = projectAnswersToChild(child, parent, semanticPair.getKey().inverse(), semanticPair.getValue());
            assertCollectionsNonTriviallyEqual(propagatedAnswers + "\n!=\n" + childAnswers + "\n", childAnswers, propagatedAnswers);
        }
    }

    @Test
    public void whenChildSpecifiesRole_rolesAreFilteredCorrectly(){
        try(TransactionOLTP tx = genericSchemaSession.transaction().write()) {
            Role role = tx.getRole("baseRole1");
            String base = "($role: $x, baseRole2: $y) isa binary;";
            String parentPattern = patternise(base);
            String childPattern = patternise(base, "$role type " + role.label() + ";");
            ReasonerAtomicQuery parent = ReasonerQueries.atomic(conjunction(parentPattern), tx);
            ReasonerAtomicQuery child = ReasonerQueries.atomic(conjunction(childPattern), tx);

            Set<Pair<Unifier, SemanticDifference>> semanticPairs = child.getMultiUnifierWithSemanticDiff(parent);
            Pair<Unifier, SemanticDifference> semanticPair = Iterables.getOnlyElement(semanticPairs);

            SemanticDifference expected = new SemanticDifference(
                    ImmutableSet.of(
                            new VariableDefinition(new Variable("role"), null, role, new HashSet<>(), new HashSet<>()),
                            new VariableDefinition(new Variable("x"), null, null, Sets.newHashSet(role), new HashSet<>())
                    )
            );
            assertEquals(expected, semanticPair.getValue());
            Set<ConceptMap> childAnswers = tx.stream(child.getQuery(), false).collect(Collectors.toSet());
            Set<ConceptMap> propagatedAnswers = projectAnswersToChild(child, parent, semanticPair.getKey().inverse(), semanticPair.getValue());
            assertCollectionsNonTriviallyEqual(propagatedAnswers + "\n!=\n" + childAnswers + "\n", childAnswers, propagatedAnswers);
        }
    }

    @Test
    public void whenChildSpecialisesRole_rolesAreFilteredCorrectly(){
        try(TransactionOLTP tx = genericSchemaSession.transaction().write()) {
            Role baseRole = tx.getRole("baseRole1");
            Role subRole = tx.getRole("subRole1");
            String base = "($role: $x, baseRole2: $y) isa binary;";
            String parentPattern = patternise(base, "$role type " + baseRole.label() + ";");
            String childPattern = patternise(base, "$role type " + subRole.label() + ";");
            ReasonerAtomicQuery parent = ReasonerQueries.atomic(conjunction(parentPattern), tx);
            ReasonerAtomicQuery child = ReasonerQueries.atomic(conjunction(childPattern), tx);

            Set<Pair<Unifier, SemanticDifference>> semanticPairs = child.getMultiUnifierWithSemanticDiff(parent);
            Pair<Unifier, SemanticDifference> semanticPair = Iterables.getOnlyElement(semanticPairs);

            SemanticDifference expected = new SemanticDifference(
                    ImmutableSet.of(
                            new VariableDefinition(new Variable("x"), null, null, Sets.newHashSet(subRole), new HashSet<>())
                    )
            );
            assertEquals(expected, semanticPair.getValue());
            Set<ConceptMap> childAnswers = tx.stream(child.getQuery(), false).collect(Collectors.toSet());
            Set<ConceptMap> propagatedAnswers = projectAnswersToChild(child, parent, semanticPair.getKey().inverse(), semanticPair.getValue());
            assertCollectionsNonTriviallyEqual(propagatedAnswers + "\n!=\n" + childAnswers + "\n", childAnswers, propagatedAnswers);
        }
    }

    @Test
    public void whenChildSpecialisesRole_rolePlayersPlayingMultipleRoles_differenceIsCalculatedCorrectly(){
        try(TransactionOLTP tx = genericSchemaSession.transaction().write()) {
            Role baseRole1 = tx.getRole("baseRole1");
            Role subRole1 = tx.getRole("subRole1");
            Role baseRole2 = tx.getRole("baseRole2");
            Role subRole2 = tx.getRole("subRole2");
            String base = "($role: $x, $role2: $x) isa binary;";
            String parentPattern = patternise(base, "$role type " + baseRole1.label() + ";", "$role2 type " + baseRole2.label() + ";");
            String childPattern = patternise(base, "$role type " + subRole1.label() + ";", "$role2 type " + subRole2.label() + ";");
            ReasonerAtomicQuery parent = ReasonerQueries.atomic(conjunction(parentPattern), tx);
            ReasonerAtomicQuery child = ReasonerQueries.atomic(conjunction(childPattern), tx);

            Set<Pair<Unifier, SemanticDifference>> semanticPairs = child.getMultiUnifierWithSemanticDiff(parent);
            Pair<Unifier, SemanticDifference> semanticPair = Iterables.getOnlyElement(semanticPairs);

            SemanticDifference expected = new SemanticDifference(
                    ImmutableSet.of(
                            new VariableDefinition(new Variable("x"), null, null, Sets.newHashSet(subRole1, subRole2), new HashSet<>())
                    )
            );
            assertEquals(expected, semanticPair.getValue());
            Set<ConceptMap> childAnswers = tx.stream(child.getQuery(), false).collect(Collectors.toSet());
            Set<ConceptMap> propagatedAnswers = projectAnswersToChild(child, parent, semanticPair.getKey().inverse(), semanticPair.getValue());
            assertCollectionsEqual(propagatedAnswers + "\n!=\n" + childAnswers + "\n", childAnswers, propagatedAnswers);
        }
    }

    @Test
    public void whenChildAndParentHaveVariableRoles_differenceIsCalculatedCorrectly(){
        try(TransactionOLTP tx = genericSchemaSession.transaction().write()) {
            String base = "($role: $x, $role2: $y) isa binary;";
            String parentPattern = patternise(base);
            String childPattern = patternise(base, "$y id V123;");
            ReasonerAtomicQuery parent = ReasonerQueries.atomic(conjunction(parentPattern), tx);
            ReasonerAtomicQuery child = ReasonerQueries.atomic(conjunction(childPattern), tx);

            Set<Pair<Unifier, SemanticDifference>> semanticPairs = child.getMultiUnifierWithSemanticDiff(parent);

            SemanticDifference expected = new SemanticDifference(ImmutableSet.of());
            semanticPairs.stream().map(Pair::getValue).forEach(sd -> assertEquals(expected, sd));
        }
    }

    @Test
    public void whenChildSpecialisesPlayedRole_RPsAreFilteredCorrectly(){
        try(TransactionOLTP tx = genericSchemaSession.transaction().write()) {
            Role subRole1 = tx.getRole("subRole1");
            Role subRole2 = tx.getRole("subSubRole2");
            String parentPattern = patternise("(baseRole1: $x, baseRole2: $y) isa binary;");
            String childPattern = patternise("(" + subRole1.label() + ": $x, " + subRole2.label() + ": $y) isa binary;");
            ReasonerAtomicQuery parent = ReasonerQueries.atomic(conjunction(parentPattern), tx);
            ReasonerAtomicQuery child = ReasonerQueries.atomic(conjunction(childPattern), tx);

            Set<Pair<Unifier, SemanticDifference>> semanticPairs = child.getMultiUnifierWithSemanticDiff(parent);
            Pair<Unifier, SemanticDifference> semanticPair = Iterables.getOnlyElement(semanticPairs);

            SemanticDifference expected = new SemanticDifference(
                    ImmutableSet.of(
                            new VariableDefinition(new Variable("x"), null, null, Sets.newHashSet(subRole1), new HashSet<>()),
                            new VariableDefinition(new Variable("y"), null, null, Sets.newHashSet(subRole2), new HashSet<>())
                    )
            );
            assertEquals(expected, semanticPair.getValue());
            Set<ConceptMap> childAnswers = tx.stream(child.getQuery(), false).collect(Collectors.toSet());
            Set<ConceptMap> propagatedAnswers = projectAnswersToChild(child, parent, semanticPair.getKey().inverse(), semanticPair.getValue());
            assertCollectionsNonTriviallyEqual(propagatedAnswers + "\n!=\n" + childAnswers + "\n", childAnswers, propagatedAnswers);
        }
    }

    @Test
    public void whenChildSpecifiesResourceValuePredicate_valuesAreFilteredCorrectly(){
        try(TransactionOLTP tx = genericSchemaSession.transaction().write()) {
            String parentPattern = patternise("$x has resource $r;");
            final String value = "m";
            String childPattern = patternise("$x has resource '" + value + "';");
            ReasonerAtomicQuery parent = ReasonerQueries.atomic(conjunction(parentPattern), tx);
            ReasonerAtomicQuery child = ReasonerQueries.atomic(conjunction(childPattern), tx);

            Set<Pair<Unifier, SemanticDifference>> semanticPairs = child.getMultiUnifierWithSemanticDiff(parent);
            Pair<Unifier, SemanticDifference> semanticPair = Iterables.getOnlyElement(semanticPairs);

            AttributeAtom resource = (AttributeAtom) child.getAtom();

            SemanticDifference expected = new SemanticDifference(
                    ImmutableSet.of(
                            new VariableDefinition(resource.getAttributeVariable(),null, null, new HashSet<>(), resource.getInnerPredicates(ValuePredicate.class).collect(toSet()))
                    )
            );
            assertEquals(expected, semanticPair.getValue());
            Set<ConceptMap> childAnswers = tx.stream(child.getQuery(), false).collect(Collectors.toSet());
            Set<ConceptMap> propagatedAnswers = projectAnswersToChild(child, parent, semanticPair.getKey().inverse(), semanticPair.getValue());
            assertCollectionsNonTriviallyEqual(propagatedAnswers + "\n!=\n" + childAnswers + "\n", childAnswers, propagatedAnswers);
        }
    }

    @Test
    public void whenChildSpecialisesResourceValuePredicate_valuesAreFilteredCorrectly(){
        try(TransactionOLTP tx = genericSchemaSession.transaction().write()) {
            String parentPattern = patternise("$x has resource !== 'm';");
            final String value = "b";
            String childPattern = patternise("$x has resource '" + value + "';");
            ReasonerAtomicQuery parent = ReasonerQueries.atomic(conjunction(parentPattern), tx);
            ReasonerAtomicQuery child = ReasonerQueries.atomic(conjunction(childPattern), tx);

            Set<Pair<Unifier, SemanticDifference>> semanticPairs = child.getMultiUnifierWithSemanticDiff(parent);
            Pair<Unifier, SemanticDifference> semanticPair = Iterables.getOnlyElement(semanticPairs);

            AttributeAtom resource = (AttributeAtom) child.getAtom();

            SemanticDifference expected = new SemanticDifference(
                    ImmutableSet.of(
                            new VariableDefinition(resource.getAttributeVariable(),null, null, new HashSet<>(), resource.getInnerPredicates(ValuePredicate.class).collect(toSet()))
                    )
            );
            assertEquals(expected, semanticPair.getValue());
            Set<ConceptMap> childAnswers = tx.stream(child.getQuery(), false).collect(Collectors.toSet());
            Set<ConceptMap> propagatedAnswers = projectAnswersToChild(child, parent, semanticPair.getKey().inverse(), semanticPair.getValue());
            assertCollectionsNonTriviallyEqual(propagatedAnswers + "\n!=\n" + childAnswers + "\n", childAnswers, propagatedAnswers);
        }
    }

    @Test
    public void whenChildSpecifiesValuePredicateOnType_valuesAreFilteredCorrectly(){
        try(TransactionOLTP tx = genericSchemaSession.transaction().write()) {
            String parentPattern = patternise("$x isa resource-long;");
            final long value = 0;
            String childPattern = patternise("$x == " + value + " isa resource-long ;");
            ReasonerAtomicQuery parent = ReasonerQueries.atomic(conjunction(parentPattern), tx);
            ReasonerAtomicQuery child = ReasonerQueries.atomic(conjunction(childPattern), tx);

            Set<Pair<Unifier, SemanticDifference>> semanticPairs = child.getMultiUnifierWithSemanticDiff(parent);
            Pair<Unifier, SemanticDifference> semanticPair = Iterables.getOnlyElement(semanticPairs);

            Atom type = child.getAtom();
            SemanticDifference expected = new SemanticDifference(
                    ImmutableSet.of(
                            new VariableDefinition(type.getVarName(),null, null, new HashSet<>(), type.getPredicates(ValuePredicate.class).collect(toSet()))
                    )
            );
            assertEquals(expected, semanticPair.getValue());
            Set<ConceptMap> childAnswers = tx.stream(child.getQuery(), false).collect(Collectors.toSet());
            Set<ConceptMap> propagatedAnswers = projectAnswersToChild(child, parent, semanticPair.getKey().inverse(), semanticPair.getValue());
            assertCollectionsNonTriviallyEqual(propagatedAnswers + "\n!=\n" + childAnswers + "\n", childAnswers, propagatedAnswers);
        }
    }

    @Test
    public void whenChildSpecialisesValuePredicateOnType_valuesAreFilteredCorrectly2(){
        try(TransactionOLTP tx = genericSchemaSession.transaction().write()) {
            String parentPattern = patternise("$x > 0 isa resource-long;");
            final long value = 1;
            String childPattern = patternise("$x == " + value + " isa resource-long;");
            ReasonerAtomicQuery parent = ReasonerQueries.atomic(conjunction(parentPattern), tx);
            ReasonerAtomicQuery child = ReasonerQueries.atomic(conjunction(childPattern), tx);

            Set<Pair<Unifier, SemanticDifference>> semanticPairs = child.getMultiUnifierWithSemanticDiff(parent);
            Pair<Unifier, SemanticDifference> semanticPair = Iterables.getOnlyElement(semanticPairs);

            Atom type = child.getAtom();
            SemanticDifference expected = new SemanticDifference(
                    ImmutableSet.of(
                            new VariableDefinition(type.getVarName(),null, null, new HashSet<>(), type.getPredicates(ValuePredicate.class).collect(toSet()))
                    )
            );
            assertEquals(expected, semanticPair.getValue());
            Set<ConceptMap> childAnswers = tx.stream(child.getQuery(), false).collect(Collectors.toSet());
            Set<ConceptMap> propagatedAnswers = projectAnswersToChild(child, parent, semanticPair.getKey().inverse(), semanticPair.getValue());
            assertCollectionsNonTriviallyEqual(propagatedAnswers + "\n!=\n" + childAnswers + "\n", childAnswers, propagatedAnswers);
        }
    }

    private Set<ConceptMap> projectAnswersToChild(ReasonerAtomicQuery child, ReasonerAtomicQuery parent, Unifier parentToChildUnifier, SemanticDifference diff){
        return parent.tx().stream(parent.getQuery(), false)
                .map(ans -> diff.applyToAnswer(ans, child.getRoleSubstitution(), child.getVarNames(), parentToChildUnifier))
                .filter(ans -> !ans.isEmpty())
                .collect(Collectors.toSet());
    }

    private String patternise(String... patterns){
        return "{ " + String.join("", Sets.newHashSet(patterns)) + " };";
    }

    private Conjunction<Statement> conjunction(String patternString){
        Set<Statement> vars = Graql.parsePattern(patternString)
                .getDisjunctiveNormalForm().getPatterns()
                .stream().flatMap(p -> p.getPatterns().stream()).collect(toSet());
        return Graql.and(vars);
    }
}
