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

package grakn.core.graql.reasoner.graph;

import grakn.core.concept.ConceptId;
import grakn.core.concept.Label;
import grakn.core.concept.type.EntityType;
import grakn.core.concept.type.RelationType;
import grakn.core.concept.type.Role;
import grakn.core.server.session.SessionImpl;
import grakn.core.server.session.TransactionOLTP;

import static grakn.core.util.GraqlTestUtil.loadFromFile;
import static grakn.core.util.GraqlTestUtil.putEntityWithResource;

@SuppressWarnings("CheckReturnValue")
public class DiagonalGraph{

    private final SessionImpl session;
    private final static String gqlPath = "test-integration/graql/reasoner/resources/";
    private final static String gqlFile = "diagonalTest.gql";

    public DiagonalGraph(SessionImpl session) {
        this.session = session;
    }
    private Label key(){ return Label.of("name");}

    public final void load(int n, int m) {
        TransactionOLTP tx = session.transaction().write();
        loadFromFile(gqlPath, gqlFile, tx);
        buildExtensionalDB(n, m, tx);
        tx.commit();
    }

    protected void buildExtensionalDB(int n, int m, TransactionOLTP tx) {
        Role relFrom = tx.getRole("rel-from");
        Role relTo = tx.getRole("rel-to");

        EntityType entity1 = tx.getEntityType("entity1");
        RelationType horizontal = tx.getRelationType("horizontal");
        RelationType vertical = tx.getRelationType("vertical");
        ConceptId[][] instanceIds = new ConceptId[n][m];
        long inserts = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                instanceIds[i][j] = putEntityWithResource(tx, "a" + i + "," + j, entity1, key()).id();
                inserts++;
                if (inserts % 100 == 0) System.out.println("inst inserts: " + inserts);

            }
        }

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                if (i < n - 1) {
                    vertical.create()
                            .assign(relFrom, tx.getConcept(instanceIds[i][j]))
                            .assign(relTo, tx.getConcept(instanceIds[i + 1][j]));
                    inserts++;
                }
                if (j < m - 1) {
                    horizontal.create()
                            .assign(relFrom, tx.getConcept(instanceIds[i][j]))
                            .assign(relTo, tx.getConcept(instanceIds[i][j + 1]));
                    inserts++;
                }
                if (inserts % 100 == 0) System.out.println("rel inserts: " + inserts);
            }
        }
    }
}
