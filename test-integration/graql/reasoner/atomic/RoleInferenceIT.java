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

package grakn.core.graql.reasoner.atomic;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Multimap;
import grakn.core.concept.type.Role;
import grakn.core.graql.reasoner.atom.binary.RelationAtom;
import grakn.core.graql.reasoner.query.ReasonerQueries;
import grakn.core.rule.GraknTestServer;
import grakn.core.server.kb.Schema;
import grakn.core.server.session.SessionImpl;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.Graql;
import graql.lang.pattern.Conjunction;
import graql.lang.statement.Statement;
import graql.lang.statement.Variable;
import java.util.Set;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import static grakn.core.util.GraqlTestUtil.loadFromFileAndCommit;
import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@SuppressWarnings({"CheckReturnValue", "Duplicates"})
public class RoleInferenceIT {

    @ClassRule
    public static final GraknTestServer server = new GraknTestServer();

    private static SessionImpl roleInferenceSetSession;
    private static SessionImpl genericSchemaSession;
    private static SessionImpl ruleApplicabilitySetSession;

    @BeforeClass
    public static void loadContext(){
        final String resourcePath = "test-integration/graql/reasoner/resources/";
        roleInferenceSetSession = server.sessionWithNewKeyspace();
        loadFromFileAndCommit(resourcePath, "roleInferenceTest.gql", roleInferenceSetSession);
        genericSchemaSession = server.sessionWithNewKeyspace();
        loadFromFileAndCommit(resourcePath, "genericSchema.gql", genericSchemaSession);
        ruleApplicabilitySetSession = server.sessionWithNewKeyspace();
        loadFromFileAndCommit(resourcePath,"ruleApplicabilityTest.gql", ruleApplicabilitySetSession);
    }

    @AfterClass
    public static void closeSession(){
        roleInferenceSetSession.close();
        genericSchemaSession.close();
        ruleApplicabilitySetSession.close();
    }

    @Test
    public void testRoleInference_TypedBinaryRelation(){
        TransactionOLTP tx = roleInferenceSetSession.transaction().write();
        String patternString = "{ ($x, $y); $x isa entity1; $y isa entity2; };";
        String patternString2 = "{ ($x, $y) isa binary; $x isa entity1; $y isa entity2; };";

        ImmutableSetMultimap<Role, Variable> correctRoleMap = ImmutableSetMultimap.of(
                tx.getRole("role1"), new Variable("x"),
                tx.getRole("role2"), new Variable("y"));
        roleInference(patternString, correctRoleMap, tx);
        roleInference(patternString2, correctRoleMap, tx);
        tx.close();
    }

    @Test
    public void testRoleInference_TypedBinaryRelation_SingleTypeMissing(){
        TransactionOLTP tx = roleInferenceSetSession.transaction().write();
        String patternString = "{ ($x, $y); $x isa entity1; };";
        String patternString2 = "{ ($x, $y) isa binary; $x isa entity1; };";

        ImmutableSetMultimap<Role, Variable> correctRoleMap = ImmutableSetMultimap.of(
                tx.getRole("role1"), new Variable("x"),
                tx.getRole("role"), new Variable("y"));
        roleInference(patternString, correctRoleMap, tx);
        roleInference(patternString2, correctRoleMap, tx);
        tx.close();
    }

    @Test //each type maps to a specific role
    public void testRoleInference_TypedTernaryRelationWithKnownRole(){
        TransactionOLTP tx = roleInferenceSetSession.transaction().write();
        String patternString = "{  ($x, $y, role3: $z);$x isa entity1;$y isa entity2;  };";
        String patternString2 = "{ ($x, $y, role3: $z) isa ternary;$x isa entity1;$y isa entity2; };";

        ImmutableSetMultimap<Role, Variable> correctRoleMap = ImmutableSetMultimap.of(
                tx.getRole("role1"), new Variable("x"),
                tx.getRole("role2"), new Variable("y"),
                tx.getRole("role3"), new Variable("z"));
        roleInference(patternString, correctRoleMap, tx);
        roleInference(patternString2, correctRoleMap, tx);
        tx.close();
    }

    @Test //without cardinality constraints the $y variable can be mapped to any of the three roles hence metarole is assigned
    public void testRoleInference_TypedTernaryRelation(){
        TransactionOLTP tx = roleInferenceSetSession.transaction().write();
        String patternString = "{ ($x, $y, $z);$x isa entity1;$y isa entity2; };";
        String patternString2 = "{ ($x, $y, $z) isa ternary;$x isa entity1;$y isa entity2; };";

        ImmutableSetMultimap<Role, Variable> correctRoleMap = ImmutableSetMultimap.of(
                tx.getRole("role1"), new Variable("x"),
                tx.getRole("role2"), new Variable("y"),
                tx.getRole("role"), new Variable("z"));
        roleInference(patternString, correctRoleMap, tx);
        roleInference(patternString2, correctRoleMap, tx);
        tx.close();
    }

    @Test
    public void testRoleInference_TernaryRelationWithRepeatingRolePlayers(){
        TransactionOLTP tx = roleInferenceSetSession.transaction().write();
        String patternString = "{ (role1: $x, role2: $y, $y); };";
        String patternString2 = "{ (role1: $x, role2: $y, $y) isa ternary; };";

        ImmutableSetMultimap<Role, Variable> correctRoleMap = ImmutableSetMultimap.of(
                tx.getRole("role1"), new Variable("x"),
                tx.getRole("role2"), new Variable("y"),
                tx.getRole("role"), new Variable("y"));
        roleInference(patternString, correctRoleMap, tx);
        roleInference(patternString2, correctRoleMap, tx);
        tx.close();
    }

    @Test
    public void testRoleInference_TypedTernaryRelation_TypesPlaySubRoles_SubRolesAreCorrectlyIdentified(){
        TransactionOLTP tx = roleInferenceSetSession.transaction().write();
        String patternString = "{ (role: $x, role: $y, role: $z); $x isa anotherEntity1; $y isa anotherEntity2; $z isa anotherEntity3; };";
        String patternString2 = "{ (role: $x, role: $y, role: $z) isa ternary; $x isa anotherEntity1; $y isa anotherEntity2; $z isa anotherEntity3; };";

        ImmutableSetMultimap<Role, Variable> correctRoleMap = ImmutableSetMultimap.of(
                tx.getRole("subRole1"), new Variable("x"),
                tx.getRole("subRole2"), new Variable("y"),
                tx.getRole("subRole3"), new Variable("z"));
        roleInference(patternString, correctRoleMap, tx);
        roleInference(patternString2, correctRoleMap, tx);
        tx.close();
    }

    @Test
    public void testRoleInference_TypedTernaryRelationWithMetaRoles_MetaRolesShouldBeOverwritten(){
        TransactionOLTP tx = roleInferenceSetSession.transaction().write();
        String patternString = "{ (role: $x, role: $y, role: $z); $x isa entity1; $y isa entity2; $z isa entity3; };";
        String patternString2 = "{ (role: $x, role: $y, role: $z) isa ternary; $x isa entity1; $y isa entity2; $z isa entity3; };";

        ImmutableSetMultimap<Role, Variable> correctRoleMap = ImmutableSetMultimap.of(
                tx.getRole("role1"), new Variable("x"),
                tx.getRole("role2"), new Variable("y"),
                tx.getRole("role3"), new Variable("z"));
        roleInference(patternString, correctRoleMap, tx);
        roleInference(patternString2, correctRoleMap, tx);
        tx.close();
    }

    @Test
    public void testRoleInference_TypedTernaryRelation_TypesAreSubTypes_TopRolesShouldBeChosen(){
        TransactionOLTP tx = roleInferenceSetSession.transaction().write();
        String patternString = "{ (role: $x, role: $y, role: $z); $x isa subEntity1; $y isa subEntity2; $z isa subEntity3; };";
        String patternString2 = "{ (role: $x, role: $y, role: $z) isa ternary; $x isa subEntity1; $y isa subEntity2; $z isa subEntity3; };";

        ImmutableSetMultimap<Role, Variable> correctRoleMap = ImmutableSetMultimap.of(
                tx.getRole("role1"), new Variable("x"),
                tx.getRole("role2"), new Variable("y"),
                tx.getRole("role3"), new Variable("z"));
        roleInference(patternString, correctRoleMap, tx);
        roleInference(patternString2, correctRoleMap, tx);
        tx.close();
    }

    @Test
    public void testRoleInference_TypedTernaryRelation_TypesCanPlayMultipleRoles_MetaRoleIsChosen(){
        TransactionOLTP tx = roleInferenceSetSession.transaction().write();
        String patternString = "{ ($x, $y, $z); $x isa genericEntity; $y isa genericEntity; $z isa genericEntity; };";
        String patternString2 = "{ ($x, $y, $z) isa ternary; $x isa genericEntity; $y isa genericEntity; $z isa genericEntity; };";

        ImmutableSetMultimap<Role, Variable> correctRoleMap = ImmutableSetMultimap.of(
                tx.getRole("role"), new Variable("x"),
                tx.getRole("role"), new Variable("y"),
                tx.getRole("role"), new Variable("z"));
        roleInference(patternString, correctRoleMap, tx);
        roleInference(patternString2, correctRoleMap, tx);
        tx.close();
    }

    @Test //for each role player role mapping is ambiguous so metarole has to be assigned
    public void testRoleInference_NoInformationPresent(){
        TransactionOLTP tx = roleInferenceSetSession.transaction().write();
        String relationString = "{ ($x, $y); };";
        RelationAtom relation = (RelationAtom) ReasonerQueries.atomic(conjunction(relationString, tx), tx).getAtom();
        relation.getRoleVarMap().entries().forEach(e -> assertTrue(Schema.MetaSchema.isMetaLabel(e.getKey().label())));
        tx.close();
    }

    @Test //for each role player role mapping is ambiguous so metarole has to be assigned
    public void testRoleInference_MetaRelationType(){
        TransactionOLTP tx = roleInferenceSetSession.transaction().write();
        String relationString = "{ ($x, $y) isa relation; };";
        RelationAtom relation = (RelationAtom) ReasonerQueries.atomic(conjunction(relationString, tx), tx).getAtom();
        relation.getRoleVarMap().entries().forEach(e -> assertTrue(Schema.MetaSchema.isMetaLabel(e.getKey().label())));
        tx.close();
    }

    @Test //missing role is ambiguous without cardinality constraints
    public void testRoleInference_RoleHierarchyInvolved() {
        TransactionOLTP tx = genericSchemaSession.transaction().write();
        String relationString = "{ ($p, subRole2: $gc) isa binary; };";
        String relationString2 = "{ (subRole1: $gp, $p) isa binary; };";
        RelationAtom relation = (RelationAtom) ReasonerQueries.atomic(conjunction(relationString, tx), tx).getAtom();
        RelationAtom relation2 = (RelationAtom) ReasonerQueries.atomic(conjunction(relationString2, tx), tx).getAtom();
        Multimap<Role, Variable> roleMap = roleSetMap(relation.getRoleVarMap());
        Multimap<Role, Variable> roleMap2 = roleSetMap(relation2.getRoleVarMap());

        ImmutableSetMultimap<Role, Variable> correctRoleMap = ImmutableSetMultimap.of(
                tx.getRole("role"), new Variable("p"),
                tx.getRole("subRole2"), new Variable("gc"));
        ImmutableSetMultimap<Role, Variable> correctRoleMap2 = ImmutableSetMultimap.of(
                tx.getRole("role"), new Variable("p"),
                tx.getRole("subRole1"), new Variable("gp"));
        assertEquals(correctRoleMap, roleMap);
        assertEquals(correctRoleMap2, roleMap2);
        tx.close();
    }

    @Test //entity1 plays role1 but entity2 plays roles role1, role2 hence ambiguous and metarole has to be assigned, EXPECTED TO CHANGE WITH CARDINALITY CONSTRAINTS
    public void testRoleInference_WithMetaType(){
        TransactionOLTP tx = ruleApplicabilitySetSession.transaction().write();
        String relationString = "{ ($x, $y, $z) isa ternary; $x isa singleRoleEntity; $y isa twoRoleEntity; $z isa entity; };";
        RelationAtom relation = (RelationAtom) ReasonerQueries.atomic(conjunction(relationString, tx), tx).getAtom();
        ImmutableSetMultimap<Role, Variable> roleMap = ImmutableSetMultimap.of(
                tx.getRole("someRole"), new Variable("x"),
                tx.getRole("role"), new Variable("y"),
                tx.getRole("role"), new Variable("z"));
        assertEquals(roleMap, roleSetMap(relation.getRoleVarMap()));
        tx.close();
    }

    @Test //entity1 plays role1, entity2 plays 2 roles, entity3 plays 3 roles hence ambiguous and metarole has to be assigned, EXPECTED TO CHANGE WITH CARDINALITY CONSTRAINTS
    public void testRoleInference_RoleMappingUnambiguous(){
        TransactionOLTP tx = ruleApplicabilitySetSession.transaction().write();
        String relationString = "{ ($x, $y, $z) isa ternary;$x isa singleRoleEntity; $y isa twoRoleEntity; $z isa threeRoleEntity; };";
        RelationAtom relation = (RelationAtom) ReasonerQueries.atomic(conjunction(relationString, tx), tx).getAtom();
        ImmutableSetMultimap<Role, Variable> roleMap = ImmutableSetMultimap.of(
                tx.getRole("someRole"), new Variable("x"),
                tx.getRole("role"), new Variable("y"),
                tx.getRole("role"), new Variable("z"));
        assertEquals(roleMap, roleSetMap(relation.getRoleVarMap()));
        tx.close();
    }

    @Test //for each role player role mapping is ambiguous so metarole has to be assigned
    public void testRoleInference_AllRolePlayersHaveAmbiguousRoles(){
        TransactionOLTP tx = ruleApplicabilitySetSession.transaction().write();
        String relationString = "{ ($x, $y, $z) isa ternary;$x isa twoRoleEntity; $y isa threeRoleEntity; $z isa anotherTwoRoleEntity; };";
        RelationAtom relation = (RelationAtom) ReasonerQueries.atomic(conjunction(relationString, tx), tx).getAtom();
        relation.getRoleVarMap().entries().forEach(e -> assertTrue(Schema.MetaSchema.isMetaLabel(e.getKey().label())));
        tx.close();
    }

    @Test //relation relates a single role so instead of assigning metarole this role should be assigned
    public void testRoleInference_RelationHasVerticalRoleHierarchy(){
        TransactionOLTP tx = ruleApplicabilitySetSession.transaction().write();
        String relationString = "{ ($x, $y) isa reifying-relation; };";
        RelationAtom relation = (RelationAtom) ReasonerQueries.atomic(conjunction(relationString, tx), tx).getAtom();
        ImmutableSetMultimap<Role, Variable> roleMap = ImmutableSetMultimap.of(
                tx.getRole("someRole"), new Variable("x"),
                tx.getRole("someRole"), new Variable("y"));
        assertEquals(roleMap, roleSetMap(relation.getRoleVarMap()));
        tx.close();
    }

    private void roleInference(String patternString, ImmutableSetMultimap<Role, Variable> expectedRoleMAp, TransactionOLTP tx){
        RelationAtom atom = (RelationAtom) ReasonerQueries.atomic(conjunction(patternString, tx), tx).getAtom();
        Multimap<Role, Variable> roleMap = roleSetMap(atom.getRoleVarMap());
        assertEquals(expectedRoleMAp, roleMap);
    }

    private Multimap<Role, Variable> roleSetMap(Multimap<Role, Variable> roleVarMap) {
        Multimap<Role, Variable> roleMap = HashMultimap.create();
        roleVarMap.entries().forEach(e -> roleMap.put(e.getKey(), e.getValue()));
        return roleMap;
    }

    private Conjunction<Statement> conjunction(String patternString, TransactionOLTP tx){
        Set<Statement> vars = Graql.parsePattern(patternString)
                .getDisjunctiveNormalForm().getPatterns()
                .stream().flatMap(p -> p.getPatterns().stream()).collect(toSet());
        return Graql.and(vars);
    }
}
