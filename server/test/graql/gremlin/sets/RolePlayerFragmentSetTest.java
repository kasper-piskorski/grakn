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

package grakn.core.graql.gremlin.sets;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import grakn.core.concept.Label;
import grakn.core.concept.type.Role;
import grakn.core.concept.type.SchemaConcept;
import grakn.core.graql.gremlin.EquivalentFragmentSet;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.statement.Variable;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.Collection;
import java.util.HashSet;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("Duplicates")
public class RolePlayerFragmentSetTest {

    private final Variable a = new Variable("a");
    private final Variable b = new Variable("b");
    private final Variable c = new Variable("c");
    private final Variable d = new Variable("d");
    private TransactionOLTP tx;

    @Before
    public void setUp(){
        tx = mock(TransactionOLTP.class);
    }

    @Test
    public void whenApplyingRoleOptimisation_ExpandRoleToAllSubs() {
        Label author = Label.of("author");
        Label director = Label.of("director");

        Role authorConcept = mock(Role.class);
        Role directorConcept = mock(Role.class);

        //Mock author
        when(authorConcept.label()).thenReturn(author);
        when(authorConcept.isRole()).thenReturn(true);
        when(authorConcept.asRole()).thenReturn(authorConcept);
        Mockito.doReturn(Stream.of(directorConcept, authorConcept)).when(authorConcept).subs();

        //Mock director
        when(directorConcept.label()).thenReturn(director);
        when(directorConcept.isRole()).thenReturn(true);
        when(directorConcept.asRole()).thenReturn(directorConcept);
        Mockito.doReturn(Stream.of(directorConcept)).when(directorConcept).subs();

        when(tx.getSchemaConcept(author)).thenReturn(authorConcept);
        when(tx.getSchemaConcept(director)).thenReturn(directorConcept);


        EquivalentFragmentSet authorLabelFragmentSet = EquivalentFragmentSets.label(null, d, ImmutableSet.of(author));

        Collection<EquivalentFragmentSet> fragmentSets = Sets.newHashSet(
                EquivalentFragmentSets.rolePlayer(null, a, b, c, d),
                authorLabelFragmentSet
        );

        RolePlayerFragmentSet.ROLE_OPTIMISATION.apply(fragmentSets, tx);

        HashSet<EquivalentFragmentSet> expected = Sets.newHashSet(
                RolePlayerFragmentSet.of(null, a, b, c, null, ImmutableSet.of(author, director), null),
                authorLabelFragmentSet
        );

        assertEquals(expected, fragmentSets);
    }

    @Test
    public void whenRoleIsNotInGraph_DoNotApplyRoleOptimisation() {
        Label magician = Label.of("magician");

        Collection<EquivalentFragmentSet> fragmentSets = Sets.newHashSet(
                EquivalentFragmentSets.rolePlayer(null, a, b, c, d),
                EquivalentFragmentSets.label(null, d, ImmutableSet.of(magician))
        );

        Collection<EquivalentFragmentSet> expected = Sets.newHashSet(fragmentSets);

        RolePlayerFragmentSet.ROLE_OPTIMISATION.apply(fragmentSets, tx);

        assertEquals(expected, fragmentSets);
    }

    @Test
    public void whenLabelDoesNotReferToARole_DoNotApplyRoleOptimisation() {
        Label movie = Label.of("movie");
        SchemaConcept movieConcept = mock(SchemaConcept.class);
        when(movieConcept.isRole()).thenReturn(false);
        when(tx.getSchemaConcept(movie)).thenReturn(movieConcept);

        Collection<EquivalentFragmentSet> fragmentSets = Sets.newHashSet(
                EquivalentFragmentSets.rolePlayer(null, a, b, c, d),
                EquivalentFragmentSets.label(null, d, ImmutableSet.of(movie))
        );

        Collection<EquivalentFragmentSet> expected = Sets.newHashSet(fragmentSets);

        RolePlayerFragmentSet.ROLE_OPTIMISATION.apply(fragmentSets, tx);

        assertEquals(expected, fragmentSets);
    }

    @Test
    public void whenApplyingRoleOptimisationToMetaRole_DoNotExpandRoleToAllSubs() {
        Label role = Label.of("role");
        SchemaConcept metaRole = mock(SchemaConcept.class);
        when(metaRole.label()).thenReturn(role);
        when(tx.getSchemaConcept(role)).thenReturn(metaRole);


        EquivalentFragmentSet authorLabelFragmentSet = EquivalentFragmentSets.label(null, d, ImmutableSet.of(role));

        Collection<EquivalentFragmentSet> fragmentSets = Sets.newHashSet(
                EquivalentFragmentSets.rolePlayer(null, a, b, c, d),
                authorLabelFragmentSet
        );

        RolePlayerFragmentSet.ROLE_OPTIMISATION.apply(fragmentSets, tx);

        HashSet<EquivalentFragmentSet> expected = Sets.newHashSet(
                RolePlayerFragmentSet.of(null, a, b, c, null, null, null),
                authorLabelFragmentSet
        );

        assertEquals(expected, fragmentSets);
    }

    @Test
    public void whenRelationTypeIsNotInGraph_DoNotApplyRelationTypeOptimisation() {
        Label magician = Label.of("magician");

        Collection<EquivalentFragmentSet> fragmentSets = Sets.newHashSet(
                EquivalentFragmentSets.rolePlayer(null, a, b, c, null),
                EquivalentFragmentSets.isa(null, a, d, true),
                EquivalentFragmentSets.label(null, d, ImmutableSet.of(magician))
        );

        Collection<EquivalentFragmentSet> expected = Sets.newHashSet(fragmentSets);

        RolePlayerFragmentSet.RELATION_TYPE_OPTIMISATION.apply(fragmentSets, tx);

        assertEquals(expected, fragmentSets);
    }

    @Test
    public void whenLabelDoesNotReferToARelationType_DoNotApplyRelationTypeOptimisation() {
        Label movie = Label.of("movie");
        SchemaConcept movieConcept = mock(SchemaConcept.class);
        when(movieConcept.isRelationType()).thenReturn(false);
        when(tx.getSchemaConcept(movie)).thenReturn(movieConcept);
        Collection<EquivalentFragmentSet> fragmentSets = Sets.newHashSet(
                EquivalentFragmentSets.rolePlayer(null, a, b, c, null),
                EquivalentFragmentSets.isa(null, a, d, true),
                EquivalentFragmentSets.label(null, d, ImmutableSet.of(movie))
        );

        Collection<EquivalentFragmentSet> expected = Sets.newHashSet(fragmentSets);

        RolePlayerFragmentSet.RELATION_TYPE_OPTIMISATION.apply(fragmentSets, tx);

        assertEquals(expected, fragmentSets);
    }
}