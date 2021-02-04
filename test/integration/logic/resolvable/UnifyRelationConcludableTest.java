/*
 * Copyright (C) 2021 Grakn Labs
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package grakn.core.logic.resolvable;

import grakn.core.common.exception.GraknException;
import grakn.core.common.iterator.Iterators;
import grakn.core.common.iterator.ResourceIterator;
import grakn.core.common.parameters.Arguments;
import grakn.core.common.parameters.Label;
import grakn.core.concept.Concept;
import grakn.core.concept.ConceptManager;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.concept.thing.Relation;
import grakn.core.concept.thing.Thing;
import grakn.core.concept.type.AttributeType;
import grakn.core.concept.type.RelationType;
import grakn.core.concept.type.RoleType;
import grakn.core.concept.type.ThingType;
import grakn.core.concept.type.Type;
import grakn.core.logic.LogicManager;
import grakn.core.logic.Rule;
import grakn.core.pattern.Conjunction;
import grakn.core.pattern.Disjunction;
import grakn.core.rocks.RocksGrakn;
import grakn.core.rocks.RocksSession;
import grakn.core.rocks.RocksTransaction;
import grakn.core.test.integration.util.Util;
import grakn.core.traversal.common.Identifier;
import graql.lang.Graql;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static grakn.common.collection.Collections.list;
import static grakn.common.collection.Collections.map;
import static grakn.common.collection.Collections.pair;
import static grakn.common.collection.Collections.set;
import static grakn.core.common.exception.ErrorMessage.Internal.ILLEGAL_STATE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UnifyRelationConcludableTest {

    private static Path directory = Paths.get(System.getProperty("user.dir")).resolve("unify-relation-test");
    private static String database = "unify-relation-test";
    private static RocksGrakn grakn;
    private static RocksSession session;
    private static RocksTransaction rocksTransaction;
    private static ConceptManager conceptMgr;
    private static LogicManager logicMgr;

    @BeforeClass
    public static void setUp() throws IOException {
        Util.resetDirectory(directory);
        grakn = RocksGrakn.open(directory);
        grakn.databases().create(database);

        try(RocksSession schemaSession = grakn.session(database, Arguments.Session.Type.SCHEMA)) {
            try (RocksTransaction tx = schemaSession.transaction(Arguments.Transaction.Type.WRITE)) {
                tx.query().define(Graql.parseQuery(
                        "define\n" +
                                "person sub entity,\n" +
                                "   owns first-name,\n" +
                                "   owns last-name,\n" +
                                "   owns age,\n" +
                                "   plays employment:employee,\n" +
                                "   plays employment:employer,\n" +
                                "   plays employment:employee-recommender,\n" +
                                "   plays part-time-employment:part-time-employee,\n" +
                                "   plays part-time-employment:part-time-employer,\n" +
                                "   plays part-time-employment:part-time-employee-recommender,\n" +
                                "   plays part-time-driving:night-shift-driver,\n" +
                                "   plays part-time-driving:day-shift-driver,\n" +
                                "   plays part-time-driving:part-time-driver-recommender,\n" +
                                "   plays friendship:friend;\n" +
                                "\n" +
                                "organisation sub entity,\n" +
                                "  plays employment:employer,\n" +
                                "  plays employment:employee, # awkward\n" +
                                "  plays employment:employee-recommender;\n" +
                                "part-time-organisation sub organisation,\n" +
                                "  plays part-time-employment:part-time-employer,\n" +
                                "  plays part-time-employment:part-time-employee,\n" +
                                "  plays part-time-employment:part-time-employee-recommender;\n" +
                                "part-time-driving-hire sub part-time-organisation,\n" +
                                "  plays part-time-driving:part-time-taxi,\n" +
                                "  plays part-time-driving:part-time-driver-recommender,\n" +
                                "  plays part-time-driving:night-shift-driver,\n" +
                                "  plays part-time-driving:day-shift-driver;\n" +
                                "\n" +
                                "#define complex role hierarchy that can be easily converted into a relation AND role hierarchy in future\n" +
                                "employment sub relation,\n" +
                                "  relates employer,\n" +
                                "  relates employee,\n" +
                                "  relates employee-recommender;\n" +
                                "\n" +
                                "part-time-employment sub employment,\n" +
                                "  relates part-time-employer as employer,\n" +
                                "  relates part-time-employee as employee,\n" +
                                "  relates part-time-employee-recommender as employee-recommender,\n" +
                                "  relates restriction;\n" +
                                "\n" +
                                "part-time-driving sub part-time-employment,\n" +
                                "  relates night-shift-driver as part-time-employee,\n" +
                                "  relates day-shift-driver as part-time-employee,\n" +
                                "  relates part-time-taxi as part-time-employer,\n" +
                                "  relates part-time-driver-recommender as part-time-employee-recommender;\n" +
                                "\n" +
                                "friendship sub relation,\n" +
                                "   relates friend;\n" +
                                "name sub attribute, value string, abstract;\n" +
                                "first-name sub name;\n" +
                                "last-name sub name;\n" +
                                "age sub attribute, value long;"
                ).asDefine());
                tx.commit();
            }
        }

        try(RocksSession dataSession = grakn.session(database, Arguments.Session.Type.DATA)){
            try (RocksTransaction tx = dataSession.transaction(Arguments.Transaction.Type.WRITE)) {
                tx.query().insert(Graql.parseQuery(
                        "insert " +
                                "(part-time-taxi: $x, night-shift-driver: $y) isa part-time-driving; " +
                                "(part-time-employer: $x, part-time-employee: $y, part-time-employee-recommender: $z) isa part-time-employment; " +
                                // note duplicate RP, needed to satisfy one of the child queries
                                "(part-time-taxi: $x, night-shift-driver: $x, part-time-driver-recommender: $z) isa part-time-driving; " +
                                "$x isa part-time-driving-hire;" +
                                "$y isa part-time-driving-hire;" +
                                "$z isa part-time-driving-hire;"
                        ).asInsert()
                );
                tx.commit();
            }
        }
        session = grakn.session(database, Arguments.Session.Type.SCHEMA);
    }

    @AfterClass
    public static void tearDown() {
        session.close();
        grakn.close();
    }

    @Before
    public void setUpTransaction() {
        rocksTransaction = session.transaction(Arguments.Transaction.Type.WRITE);
        conceptMgr = rocksTransaction.concepts();
        logicMgr = rocksTransaction.logic();
    }

    @After
    public void tearDownTransaction() {
        rocksTransaction.close();
    }

    private Map<String, Set<String>> getStringMapping(Map<Identifier, Set<Identifier>> map) {
        Map<String, Set<String>> mapping = new HashMap<>();
        map.forEach((id, mapped) -> {
            HashSet<String> stringified = new HashSet<>();
            mapped.forEach(m -> stringified.add(m.toString()));
            mapping.put(id.toString(), stringified);
        });
        return mapping;
    }

    private Thing instanceOf(String label) {
        ThingType type = conceptMgr.getThingType(label);
        assert type != null : "Cannot find type " + label;
        if (type.isEntityType()) return type.asEntityType().create();
        else if (type.isRelationType()) return type.asRelationType().create();
        else if (type.isAttributeType()) {
            AttributeType atype = type.asAttributeType();
            if (atype.isString()) return type.asAttributeType().asString().put("john");
            else if (atype.isLong()) return type.asAttributeType().asLong().put(10L);
        }
        throw GraknException.of(ILLEGAL_STATE);
    }

    private Set<Label> typeHierarchy(String type){
        return conceptMgr.getThingType(type).getSubtypes()
                .map(Type::getLabel).collect(Collectors.toSet());
    }

    private RoleType role(String roleType, String typeScope){
        return conceptMgr.getRelationType(typeScope)
                .getRelates()
                .filter(r -> r.getLabel().name().equals(roleType))
                .findFirst().orElse(null);
    }

    private Set<Label> roleHierarchy(String roleType, String typeScope){
        return role(roleType, typeScope)
                .getSubtypes()
                .map(Type::getLabel)
                .collect(Collectors.toSet());
    }

    private void addRolePlayer(Relation relation, String role, Thing player) {
        RelationType relationType = relation.getType();
        RoleType roleType = relationType.getRelates(role);
        assert roleType != null : "Role type " + role + " does not exist in relation type " + relation.getType().getLabel();
        relation.addPlayer(roleType, player);
    }


    private Conjunction resolvedConjunction(String query) {
        Conjunction conjunction = Disjunction.create(Graql.parsePattern(query).asConjunction().normalise()).conjunctions().iterator().next();
        logicMgr.typeResolver().resolve(conjunction);
        return conjunction;
    }

    private Rule createRule(String label, String whenConjunctionPattern, String thenThingPattern) {
        return logicMgr.putRule(
                label,
                Graql.parsePattern(whenConjunctionPattern).asConjunction(),
                Graql.parseVariable(thenThingPattern).asThing()
        );
    }

    @Test
    public void relation_and_player_unifies_rule_relation_exact() {
        Unifier unifier = uniqueUnifier(
                "{ $r (employee: $y) isa employment; }", 
                " (employee: $x) isa employment", 
                "{ $x isa person; }"
        );
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$y", set("$x"));
            put("$r", set("$_0"));
            put("$_employment:employee", set("$_employment:employee"));
            put("$_employment", set("$_employment"));
        }};
        assertEquals(expected, result);

        // test requirements
        assertEquals(2, unifier.constraintRequirements().types().size());
        assertEquals(
                typeHierarchy("employment"),
                unifier.constraintRequirements().types().get(Identifier.Variable.label("employment")));
        assertEquals(
                roleHierarchy("employee", "employment"),
                unifier.constraintRequirements().types().get(Identifier.Variable.label("employment:employee")));
        assertEquals(0, unifier.constraintRequirements().isaExplicit().size());
        assertEquals(0, unifier.constraintRequirements().predicates().size());

        // test filter allows a valid answer
        // code below tests unifier applied to an answer that is 1) satisfiable, 2) non-satisfiable
        Relation employment = instanceOf("employment").asRelation();
        Thing person = instanceOf("person");
        addRolePlayer(employment, "employee", person);
        Map<Identifier, Concept> identifiedConcepts = map(
                pair(Identifier.Variable.anon(0), employment),
                pair(Identifier.Variable.name("x"), person),
                pair(Identifier.Variable.label("employment"), employment.getType()),
                pair(Identifier.Variable.label("employment:employee"), employment.getType().getRelates("employee"))
        );
        Optional<ConceptMap> unified = unifier.unUnify(identifiedConcepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.isPresent());
        assertEquals(2, unified.get().concepts().size());
        assertEquals(employment, unified.get().get("r"));
        assertEquals(person, unified.get().get("y"));

        // filter out invalid types
        Relation friendship = instanceOf("friendship").asRelation();
        person = instanceOf("person");
        addRolePlayer(friendship, "friend", person);
        identifiedConcepts = map(
                pair(Identifier.Variable.anon(0), friendship),
                pair(Identifier.Variable.name("x"), person),
                pair(Identifier.Variable.label("employment"), friendship.getType()),
                pair(Identifier.Variable.label("employment:employee"), friendship.getType().getRelates("friend"))
        );
        unified = unifier.unUnify(identifiedConcepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.isPresent());
    }

    @Test
    public void relation_type_and_player_unifies_rule_relation_exact() {
        Unifier unifier = uniqueUnifier(
                "{ (employee: $y) isa $rel; }",
                " (employee: $x) isa employment ",
                "{ $x isa person; }"
        );
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$y", set("$x"));
            put("$rel", set("$_employment"));
            put("$_relation:employee", set("$_employment:employee"));
        }};
        assertEquals(expected, result);

        // test requirements
        assertEquals(1, unifier.constraintRequirements().types().size());
        assertEquals(
                roleHierarchy("employee", "employment"),
                unifier.constraintRequirements().types().get(Identifier.Variable.label("relation:employee")));
        assertEquals(0, unifier.constraintRequirements().isaExplicit().size());
        assertEquals(0, unifier.constraintRequirements().predicates().size());

        // test filter allows a valid answer
        Relation employment = instanceOf("employment").asRelation();
        Thing person = instanceOf("person");
        addRolePlayer(employment, "employee", person);
        Map<Identifier, Concept> identifiedConcepts = map(
                pair(Identifier.Variable.anon(0), employment),
                pair(Identifier.Variable.name("x"), person),
                pair(Identifier.Variable.label("employment"), employment.getType()),
                pair(Identifier.Variable.label("employment:employee"), employment.getType().getRelates("employee"))
        );
        Optional<ConceptMap> unified = unifier.unUnify(identifiedConcepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.isPresent());
        assertEquals(2, unified.get().concepts().size());
        assertEquals(employment.getType(), unified.get().get("rel"));
        assertEquals(person, unified.get().get("y"));

        // filter out invalid types
        Relation friendship = instanceOf("friendship").asRelation();
        person = instanceOf("person");
        addRolePlayer(friendship, "friend", person);
        identifiedConcepts = map(
                pair(Identifier.Variable.anon(0), friendship),
                pair(Identifier.Variable.name("x"), person),
                pair(Identifier.Variable.label("employment"), friendship.getType()),
                pair(Identifier.Variable.label("employment:employee"), friendship.getType().getRelates("friend"))
        );
        unified = unifier.unUnify(identifiedConcepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.isPresent());
    }

    @Test
    public void relation_role_unifies_rule_relation_exact() {
        Unifier unifier = uniqueUnifier(
                "{ ($role: $y) isa employment; }",
                " (employee: $x) isa employment ",
                "{ $x isa person; }"
        );
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$y", set("$x"));
            put("$role", set("$_employment:employee"));
            put("$_employment", set("$_employment"));
        }};
        assertEquals(expected, result);

        // test requirements
        assertEquals(1, unifier.constraintRequirements().types().size());
        assertEquals(
                typeHierarchy("employment"),
                unifier.constraintRequirements().types().get(Identifier.Variable.label("employment")));
        assertEquals(0, unifier.constraintRequirements().isaExplicit().size());
        assertEquals(0, unifier.constraintRequirements().predicates().size());

        // test filter allows a valid answer
        Relation employment = instanceOf("employment").asRelation();
        Thing person = instanceOf("person");
        addRolePlayer(employment, "employee", person);
        Map<Identifier, Concept> identifiedConcepts = map(
                pair(Identifier.Variable.anon(0), employment),
                pair(Identifier.Variable.name("x"), person),
                pair(Identifier.Variable.label("employment"), employment.getType()),
                pair(Identifier.Variable.label("employment:employee"), employment.getType().getRelates("employee"))
        );
        Optional<ConceptMap> unified = unifier.unUnify(identifiedConcepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.isPresent());
        assertEquals(2, unified.get().concepts().size());
        assertEquals(employment.getType().getRelates("employee"), unified.get().get("role"));
        assertEquals(person, unified.get().get("y"));

        // filter out invalid types
        Relation friendship = instanceOf("friendship").asRelation();
        person = instanceOf("person");
        addRolePlayer(friendship, "friend", person);
        identifiedConcepts = map(
                pair(Identifier.Variable.anon(0), friendship),
                pair(Identifier.Variable.name("x"), person),
                pair(Identifier.Variable.label("employment"), friendship.getType()),
                pair(Identifier.Variable.label("employment:employee"), friendship.getType().getRelates("friend"))
        );
        unified = unifier.unUnify(identifiedConcepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.isPresent());
    }

    @Test
    public void relation_without_isa_unifies_rule_relation() {
        Unifier unifier = uniqueUnifier(
                "{ (employee: $y); }",
                " (employee: $x) isa employment ",
                "{ $x isa person; }"
        );
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$y", set("$x"));
            put("$_relation:employee", set("$_employment:employee"));
        }};
        assertEquals(expected, result);

        // test requirements
        assertEquals(1, unifier.constraintRequirements().types().size());
        assertEquals(
                roleHierarchy("employee", "employment"),
                unifier.constraintRequirements().types().get(Identifier.Variable.label("relation:employee")));
        assertEquals(0, unifier.constraintRequirements().isaExplicit().size());
        assertEquals(0, unifier.constraintRequirements().predicates().size());
    }

    @Test
    public void relation_duplicate_players_unifies_rule_relation_distinct_players() {
        List<Unifier> unifiers = unifiers(
                "{ (employee: $p, employee: $p) isa employment; }",
                "($employee: $x, $employee: $y) isa $employment",
                "{ $x isa person; $y isa person; $employment type employment;$employee type employment:employee; }"
        ).toList();
        Set<Map<String, Set<String>>> result = Iterators.iterate(unifiers).map(u -> getStringMapping(u.mapping())).toSet();
        Set<Map<String, Set<String>>> expected = set(
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$x", "$y"));
                    put("$_employment", set("$employment"));
                    put("$_employment:employee", set("$employee"));
                }}
        );
        assertEquals(expected, result);

        Unifier unifier = unifiers.get(0);

        // test requirements
        assertEquals(2, unifier.constraintRequirements().types().size());
        assertEquals(
                typeHierarchy("employment"),
                unifier.constraintRequirements().types().get(Identifier.Variable.label("employment")));
        assertEquals(
                roleHierarchy("employee", "employment"),
                unifier.constraintRequirements().types().get(Identifier.Variable.label("employment:employee")));
        assertEquals(0, unifier.constraintRequirements().isaExplicit().size());
        assertEquals(0, unifier.constraintRequirements().predicates().size());

        // test filter allows a valid answer
        Relation employment = instanceOf("employment").asRelation();
        Thing person = instanceOf("person");
        addRolePlayer(employment, "employee", person);
        addRolePlayer(employment, "employee", person);
        Map<Identifier, Concept> identifiedConcepts = map(
                pair(Identifier.Variable.anon(0), employment),
                pair(Identifier.Variable.name("x"), person),
                pair(Identifier.Variable.name("y"), person),
                pair(Identifier.Variable.name("employment"), employment.getType()),
                pair(Identifier.Variable.name("employee"), employment.getType().getRelates("employee"))
        );
        Optional<ConceptMap> unified = unifier.unUnify(identifiedConcepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.isPresent());
        assertEquals(1, unified.get().concepts().size());
        assertEquals(person, unified.get().get("p"));

        // filter out answers with differing role players that must be the same
        employment = instanceOf("employment").asRelation();
        person = instanceOf("person");
        Thing differentPerson = instanceOf("person");
        addRolePlayer(employment, "employee", person);
        addRolePlayer(employment, "employee", differentPerson);
        identifiedConcepts = map(
                pair(Identifier.Variable.anon(0), employment),
                pair(Identifier.Variable.name("x"), person),
                pair(Identifier.Variable.name("y"), differentPerson),
                pair(Identifier.Variable.name("employment"), employment.getType()),
                pair(Identifier.Variable.name("employee"), employment.getType().getRelates("employee"))
        );
        unified = unifier.unUnify(identifiedConcepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.isPresent());
    }

    //TODO tests below do not test for requirements and unifier application to answers

    //[Single VARIABLE ROLE in parent]
    @Test
    public void relation_variables_one_to_many_unifiers() {
        String conjunction = "{ ($role: $p) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("three-people-are-employed",
                               "{ $x isa person; $y isa person; $z isa person; }",
                               "(employee: $x, employee: $y, employee: $z) isa employment");

        ResourceIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$x"));
                    put("$role", set("$_employment:employee"));
                    put("$_employment", set("$_employment"));
                }},
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$y"));
                    put("$role", set("$_employment:employee"));
                    put("$_employment", set("$_employment"));
                }},
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$z"));
                    put("$role", set("$_employment:employee"));
                    put("$_employment", set("$_employment"));
                }}
        );
        assertEquals(expected, result);
    }

    //[Single VARIABLE ROLE in parent]
    @Test
    public void relation_player_role_unifies_rule_relation_repeated_variable_role() {
        String conjunction = "{ ($role: $p) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("two-people-are-employed",
                "{ $x isa person; $y isa person; $employment type employment; " +
                        "$employee type employment:employee; $employer type employment:employer; }",
                "($employee: $x, $employee: $y) isa $employment");

        ResourceIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$x"));
                    put("$role", set("$employee"));
                    put("$_employment", set("$employment"));
                }},
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$y"));
                    put("$role", set("$employee"));
                    put("$_employment", set("$employment"));
                }}
        );
        assertEquals(expected, result);
    }

    //[REFLEXIVE rule, Fewer roleplayers in parent]
    @Test
    public void relation_variable_multiple_identical_unifiers() {
        String conjunction = "{ (employee: $p) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("the-same-person-is-employed-twice",
                               "{ $x isa person; $y isa person; $employment type employment; $employee type employment:employee; }",
                               "($employee: $x, $employee: $x) isa $employment");

        ResourceIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$x"));
                    put("$_employment:employee", set("$employee"));
                    put("$_employment", set("$employment"));
                }}
        );
        assertEquals(expected, result);
    }

    //[many2many]
    @Test
    public void unify_relation_many_to_many() {
        String conjunction = "{ (employee: $p, employee: $q) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("three-people-are-employed",
                               "{ $x isa person; $y isa person; $z isa person; }",
                               "(employee: $x, employee: $y, employee: $z) isa employment");

        ResourceIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$x"));
                    put("$q", set("$y"));
                    put("$_employment", set("$_employment"));
                    put("$_employment:employee", set("$_employment:employee"));
                }},
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$x"));
                    put("$q", set("$z"));
                    put("$_employment", set("$_employment"));
                    put("$_employment:employee", set("$_employment:employee"));
                }},
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$y"));
                    put("$q", set("$x"));
                    put("$_employment", set("$_employment"));
                    put("$_employment:employee", set("$_employment:employee"));
                }},
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$y"));
                    put("$q", set("$z"));
                    put("$_employment", set("$_employment"));
                    put("$_employment:employee", set("$_employment:employee"));
                }},
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$z"));
                    put("$q", set("$x"));
                    put("$_employment", set("$_employment"));
                    put("$_employment:employee", set("$_employment:employee"));
                }},
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$z"));
                    put("$q", set("$y"));
                    put("$_employment", set("$_employment"));
                    put("$_employment:employee", set("$_employment:employee"));
                }}
        );
        assertEquals(expected, result);
    }

    //[many2many]
    @Test
    public void relation_unifies_many_to_many_rule_relation_players() {
        String conjunction = "{ (employee: $p, employer: $p, employee: $q) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("two-people-are-employed-one-is-also-the-employer",
                               "{ $x isa person; $y isa person; }",
                               "(employee: $x, employer: $x, employee: $y) isa employment");

        List<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        List<Map<String, Set<String>>> result = Iterators.iterate(unifier).map(u -> getStringMapping(u.mapping())).toList();

        List<Map<String, Set<String>>> expected = list(
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$x"));
                    put("$q", set("$y"));
                    put("$_employment", set("$_employment"));
                    put("$_employment:employee", set("$_employment:employee"));
                    put("$_employment:employer", set("$_employment:employer"));
                }},
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$x", "$y"));
                    put("$q", set("$x"));
                    put("$_employment", set("$_employment"));
                    put("$_employment:employee", set("$_employment:employee"));
                    put("$_employment:employer", set("$_employment:employer"));
                }}
        );
        assertEquals(expected, result);
    }

    //[multiple VARIABLE ROLE, many2many]
    @Test
    public void relation_variable_role_unifies_many_to_many_rule_relation_roles() {
        String conjunction = "{ ($role1: $p, $role1: $q, $role2: $q) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("two-people-are-employed-one-is-also-the-employer",
                               "{ $x isa person; $y isa person; }",
                               "(employee: $x, employer: $x, employee: $y) isa employment");

        ResourceIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$x"));
                    put("$q", set("$x", "$y"));
                    put("$_employment", set("$_employment"));
                    put("$role1", set("$_employment:employee"));
                    put("$role2", set("$_employment:employer"));
                }},
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$x"));
                    put("$q", set("$x", "$y"));
                    put("$_employment", set("$_employment"));
                    put("$role1", set("$_employment:employee", "$_employment:employer"));
                    put("$role2", set("$_employment:employee"));
                }},
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$y"));
                    put("$q", set("$x"));
                    put("$_employment", set("$_employment"));
                    put("$role1", set("$_employment:employee", "$_employment:employer"));
                    put("$role2", set("$_employment:employee"));
                }},
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$y"));
                    put("$q", set("$x"));
                    put("$_employment", set("$_employment"));
                    put("$role1", set("$_employment:employee"));
                    put("$role2", set("$_employment:employer"));
                }}
        );
        assertEquals(expected, result);
    }

    //[multiple VARIABLE ROLE, many2many]
    @Test
    public void relation_variable_role_unifies_many_to_many_rule_relation_roles_2() {
        String conjunction = "{ ($role1: $p, $role2: $q, $role1: $p) isa employment; }";

        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("two-people-are-employed-one-is-also-the-employer",
                               "{ $x isa person; $y isa person; }",
                               "(employee: $x, employer: $x, employee: $y) isa employment");

        ResourceIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$x", "$y"));
                    put("$q", set("$x"));
                    put("$_employment", set("$_employment"));
                    put("$role1", set("$_employment:employee"));
                    put("$role2", set("$_employment:employer"));
                }},
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$x", "$y"));
                    put("$q", set("$x"));
                    put("$_employment", set("$_employment"));
                    put("$role1", set("$_employment:employee", "$_employment:employer"));
                    put("$role2", set("$_employment:employee"));
                }},
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$x"));
                    put("$q", set("$y"));
                    put("$_employment", set("$_employment"));
                    put("$role1", set("$_employment:employee", "$_employment:employer"));
                    put("$role2", set("$_employment:employee"));
                }}
        );
        assertEquals(expected, result);
    }

    //[reflexive parent]
    //TODO check answer satisfiability
    @Test
    public void relation_duplicate_roles_unifies_rule_relation_distinct_roles() {
        String conjunction = "{ (employee: $p, employee: $p) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("two-people-are-employed",
                               "{ $x isa person; $y isa person; $employment type employment; $employee type employment:employee; }",
                               "($employee: $x, $employee: $y) isa $employment");

        ResourceIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$x", "$y"));
                    put("$_employment:employee", set("$employee"));
                    put("$_employment", set("$employment"));
                }}
        );
        assertEquals(expected, result);
    }

    //[reflexive child]
    @Test
    public void relation_distinct_roles_unifies_rule_relation_duplicate_roles() {
        String conjunction = "{ (employee: $p, employee: $q) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("a-person-is-employed-twice",
                               "{ $x isa person; $employment type employment; $employee type employment:employee; }",
                               "($employee: $x, $employee: $x) isa $employment");

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        Set<Map<String, Set<String>>> result = Iterators.iterate(unifiers).map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$x"));
                    put("$q", set("$x"));
                    put("$_employment", set("$employment"));
                    put("$_employment:employee", set("$employee"));
                }}
        );
        assertEquals(expected, result);

        Unifier unifier = unifiers.get(0);
        // test requirements
        assertEquals(2, unifier.constraintRequirements().types().size());
        assertEquals(
                typeHierarchy("employment"),
                unifier.constraintRequirements().types().get(Identifier.Variable.label("employment")));
        assertEquals(
                roleHierarchy("employee", "employment"),
                unifier.constraintRequirements().types().get(Identifier.Variable.label("employment:employee")));
        assertEquals(0, unifier.constraintRequirements().isaExplicit().size());
        assertEquals(0, unifier.constraintRequirements().predicates().size());

        // test filter allows a valid answer
        Relation employment = instanceOf("employment").asRelation();
        Thing person = instanceOf("person");
        addRolePlayer(employment, "employee", person);
        addRolePlayer(employment, "employee", person);
        Map<Identifier, Concept> identifiedConcepts = map(
                pair(Identifier.Variable.anon(0), employment),
                pair(Identifier.Variable.name("x"), person),
                pair(Identifier.Variable.name("employment"), employment.getType()),
                pair(Identifier.Variable.name("employee"), employment.getType().getRelates("employee"))
        );
        Optional<ConceptMap> unified = unifier.unUnify(identifiedConcepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.isPresent());
        assertEquals(2, unified.get().concepts().size());
        assertEquals(person, unified.get().get("p"));
        assertEquals(person, unified.get().get("q"));
    }

    //[reflexive parent, child]
    @Test
    public void relation_duplicate_roles_unifies_rule_relation_duplicate_roles() {
        String conjunction = "{ (employee: $p, employee: $p) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("a-person-is-employed-twice",
                               "{ $x isa person; $employment type employment; $employee type employment:employee; }",
                               "($employee: $x, $employee: $x) isa $employment");

        ResourceIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                new HashMap<String, Set<String>>() {{
                    put("$p", set("$x"));
                    put("$_employment", set("$employment"));
                    put("$_employment:employee", set("$employee"));
                }}
        );
        assertEquals(expected, result);
    }

    @Test
    public void relation_more_players_than_rule_relation_fails_unify() {
        String parentRelation = "{ (part-time-employee: $r, employer: $p, restriction: $q) isa part-time-employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(parentRelation));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("one-employee-one-employer",
                               "{ $x isa person; $y isa organisation; " +
                                       "$employee type employment:employee; $employer type employment:employer; }",
                               "($employee: $x, $employer: $y) isa employment");

        ResourceIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = Collections.emptySet();
        assertEquals(expected, result);
    }

    @Test
    public void binaryRelationWithRoleHierarchy_ParentWithBaseRoles(){
        String parentRelation = "{ (employer: $x, employee: $y); }";
        String conclusion = "(part-time-employer: $u, part-time-employee: $v) isa part-time-employment";
        String conclusion2 = "(part-time-taxi: $u, night-shift-driver: $v) isa part-time-driving";

        unification(parentRelation, conclusion, "{ $u isa part-time-organisation; $v isa person;}");
        unification(parentRelation, conclusion2, "{ $u isa part-time-driving-hire; $v isa person;}");
    }

    @Test
    public void binaryRelationWithRoleHierarchy_ParentWithSubRoles(){
        String parentRelation = "{ (part-time-employer: $x, part-time-employee: $y); }";
        String conclusion = "(part-time-employer: $u, night-shift-driver: $v)";
        String conclusion2 = "(part-time-taxi: $u, night-shift-driver: $v)";
        String conclusion3 = "(part-time-taxi: $u, employee-recommender: $v)";
        String conclusion4 = "(employer: $u, employee: $v)";

        unification(parentRelation, conclusion, "{$u isa part-time-organisation; $v isa person;}");
        unification(parentRelation, conclusion2, "{$u isa part-time-organisation; $v isa person;}");
        unification(parentRelation, conclusion3, "{$u isa part-time-organisation; $v isa person;}");
        nonExistentUnifier(parentRelation, conclusion4, "{$u isa part-time-organisation; $v isa person;}");
    }

    @Test
    public void ternaryRelationWithRoleHierarchy_ParentWithBaseRoles(){
        String parentRelation = "{ (employer: $x, employee: $y, employee-recommender: $z); }";
        String conclusion = "(employer: $u, part-time-employee: $v, part-time-driver-recommender: $q)";
        String conclusion2 = "(employer: $z, part-time-employee: $y, part-time-driver-recommender: $x)";
        String conclusion3 = "(part-time-employer: $u, part-time-employee: $v, part-time-driver-recommender: $q)";
        String conclusion4 = "(part-time-employer: $y, part-time-employee: $z, part-time-driver-recommender: $x)";
        String conclusion5 = "(part-time-employer: $u, part-time-employer: $v, part-time-driver-recommender: $q)";

        unification(parentRelation, conclusion, "{$u isa organisation; $v isa person; $q isa person;}");
        unification(parentRelation, conclusion2, "{$z isa organisation; $y isa person; $x isa person;}");
        unification(parentRelation, conclusion3, "{$u isa organisation; $v isa person; $q isa person;}");
        unification(parentRelation, conclusion4, "{$z isa organisation; $y isa person; $x isa person;}");
        nonExistentUnifier(parentRelation, conclusion5, "{$u isa organisation; $v isa organisation; $q isa person;}");
    }

    @Test
    public void ternaryRelationWithRoleHierarchy_ParentWithSubRoles(){
        String parentRelation = "{(part-time-employer: $x, part-time-employee: $y, part-time-employee-recommender: $z);}";
        String conclusion = "(employer: $u, part-time-employee: $v, part-time-driver-recommender: $q)";
        String conclusion2 = "(part-time-employer: $u, part-time-employee: $v, part-time-driver-recommender: $q)";
        String conclusion3 = "(part-time-employer: $y, part-time-employee: $z, part-time-driver-recommender: $x)";
        String conclusion4 = "(part-time-taxi: $u, part-time-employee: $v, part-time-driver-recommender: $q)";
        String conclusion5 = "(part-time-taxi: $y, part-time-employee: $z, part-time-driver-recommender: $x)";
        String conclusion6 = "(part-time-employer: $u, part-time-employer: $v, part-time-driver-recommender: $q)";

        nonExistentUnifier(parentRelation, conclusion, "{$u isa organisation; $v isa person; $q isa person;}");
        unification(parentRelation, conclusion2, "{$u isa organisation; $v isa person; $q isa person;}");
        unification(parentRelation, conclusion3, "{$y isa organisation; $z isa person; $x isa person;}");
        unification(parentRelation, conclusion4,"{$u isa organisation; $v isa person; $q isa person;}");
        unification(parentRelation, conclusion5, "{$y isa organisation; $z isa person; $x isa person;}");
        nonExistentUnifier(parentRelation, conclusion6, "{$u isa organisation; $v isa person; $q isa person;}");
    }

    @Test
    public void ternaryRelationWithRoleHierarchy_ParentWithBaseRoles_childrenRepeatRolePlayers(){
        String parentRelation = "{ (employer: $x, employee: $y, employee-recommender: $z);}";
        String conclusion = "(employer: $u, part-time-employee: $u, part-time-driver-recommender: $q)";
        String conclusion2 = "(employer: $y, part-time-employee: $y, part-time-driver-recommender: $x)";
        String conclusion3 = "(part-time-employer: $u, part-time-employee: $u, part-time-driver-recommender: $q)";
        String conclusion4 = "(part-time-employer: $y, part-time-employee: $y, part-time-driver-recommender: $x)";
        String conclusion5 = "(part-time-employer: $u, part-time-employer: $u, part-time-driver-recommender: $q)";

        unification(parentRelation, conclusion, "{$u isa person; $u isa person; $q isa person;}");
        unification(parentRelation, conclusion2, "{$y isa organisation; $y isa person; $x isa person;}");
        unification(parentRelation, conclusion3,  "{$u isa person; $u isa person; $q isa person;}");
        unification(parentRelation, conclusion4, "{$y isa organisation; $y isa person; $x isa person;}");
        nonExistentUnifier(parentRelation, conclusion5, "{$u isa person; $u isa person; $q isa person;}");
    }

    @Test
    public void ternaryRelationWithRoleHierarchy_ParentWithBaseRoles_parentRepeatRolePlayers(){
        String parentRelation = "{ (employer: $x, employee: $x, employee-recommender: $y);}";
        String conclusion = "(employer: $u, part-time-employee: $v, part-time-driver-recommender: $q)";
        String conclusion2 = "(employer: $z, part-time-employee: $y, part-time-driver-recommender: $x)";
        String conclusion3 = "(part-time-employer: $u, part-time-employee: $v, part-time-driver-recommender: $q)";
        String conclusion4 = "(part-time-employer: $y, part-time-employee: $y, part-time-driver-recommender: $x)";
        String conclusion5 = "(part-time-employer: $u, part-time-employer: $v, part-time-driver-recommender: $q)";

        unification(parentRelation, conclusion, "{$u isa person; $v isa person; $q isa person;}");
        unification(parentRelation, conclusion2, "{$z isa person; $y isa person; $x isa person;}");
        unification(parentRelation, conclusion3, "{$u isa person; $v isa person; $q isa person;}");
        unification(parentRelation, conclusion4, "{$z isa person; $y isa person; $x isa person;}");
        nonExistentUnifier(parentRelation, conclusion5, "{$u isa person; $v isa person; $q isa person;}");
    }

    @Test
    public void binaryRelationWithRoleHierarchy_ParentHasFewerRelationPlayers() {
        String parent = "{ (part-time-employer: $x) isa employment; }";
        String parent2 = "{ (part-time-employee: $y) isa employment; }";
        String conclusion = "(part-time-employer: $y, part-time-employee: $x) isa part-time-employment";

        unification(parent, conclusion,"{$x isa person; $y isa person;}");
        unification(parent2, conclusion, "{$x isa person; $y isa person;}");
    }

    private ResourceIterator<Unifier> unifiers(String parent, String ruleConclusion, String rulePremises){
        Conjunction parentConjunction = resolvedConjunction(parent);
        Concludable.Relation queryConcludable = Concludable.create(parentConjunction).iterator().next().asRelation();
        Rule rule = createRule("test-rule", rulePremises, ruleConclusion);
        return queryConcludable.unify(rule.conclusion(), conceptMgr);
    }

    private void nonExistentUnifier(String parent, String ruleConclusion, String rulePremises){
        assertFalse(unifiers(parent, ruleConclusion, rulePremises).hasNext());
    }

    private Unifier uniqueUnifier(String parent, String ruleConclusion, String rulePremises){
        List<Unifier> unifiers = unifiers(parent, ruleConclusion, rulePremises).toList();
        assertEquals(1, unifiers.size());
        return unifiers.iterator().next();
    }

    private void unification(String parent, String ruleConclusion, String rulePremises){
        Unifier unifier = uniqueUnifier(parent, ruleConclusion, rulePremises);
        List<ConceptMap> childAnswers = rocksTransaction.query().match(Graql.match(Graql.parsePattern(ruleConclusion))).toList();
        List<ConceptMap> parentAnswers = rocksTransaction.query().match(Graql.match(Graql.parsePattern(parent))).toList();
        List<ConceptMap> unifiedAnswers = childAnswers.stream()
                .map(ans -> buildIdentifierMap(ans, unifier))
                .map(imap -> unifier.unUnify(imap, new Unifier.Requirements.Instance(map())))
                .filter(Optional::isPresent).map(Optional::get)
                .collect(Collectors.toList());

        assertFalse(childAnswers.isEmpty());
        assertFalse(unifiedAnswers.isEmpty());
        assertFalse(parentAnswers.isEmpty());
        assertTrue(parentAnswers.containsAll(unifiedAnswers));
    }

    Map<Identifier, Concept> buildIdentifierMap(ConceptMap ans, Unifier unifier){
        Map<Identifier, Concept> imap = ans.concepts().entrySet().stream()
                .collect(Collectors.toMap(e -> Identifier.Variable.name(e.getKey().name()), Map.Entry::getValue));
        Map<Identifier, Set<Identifier>> inverseMapping = unifier.inverseMapping();
        inverseMapping.keySet().stream()
                .filter(Identifier::isLabel)
                .forEach(i -> {
                    //lookup concepts corresponding to labels
                    String label = i.asVariable().asLabel().reference().toString().replace("$_", "");
                    //split role scope
                    if (label.contains(":")) {
                        String[] rolePair = label.split(":");
                        imap.put(i, role(rolePair[1], rolePair[0]));
                    } else {
                        imap.put(i, conceptMgr.getThingType(label));
                    }
                });
        return imap;
    }
}
