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
import grakn.core.concept.thing.Thing;
import grakn.core.concept.type.EntityType;
import grakn.core.concept.type.RelationType;
import grakn.core.concept.type.Role;
import grakn.core.server.session.SessionImpl;
import grakn.core.server.session.TransactionOLTP;

import static grakn.core.util.GraqlTestUtil.loadFromFile;
import static grakn.core.util.GraqlTestUtil.putEntityWithResource;

@SuppressWarnings("CheckReturnValue")
public class LinearTransitivityMatrixGraph{

    private final SessionImpl session;
    private final static String gqlPath = "test-integration/graql/reasoner/resources/";
    private final static String gqlFile = "linearTransitivity.gql";
    private final static Label key = Label.of("index");

    public LinearTransitivityMatrixGraph(SessionImpl session){
        this.session = session;
    }

    public final void load(int n, int m) {
        TransactionOLTP tx = session.transaction().write();
        loadFromFile(gqlPath, gqlFile, tx);
        buildExtensionalDB(n, m, tx);
        tx.commit();
    }

    protected void buildExtensionalDB(int n, int m, TransactionOLTP tx){
        Role Qfrom = tx.getRole("Q-from");
        Role Qto = tx.getRole("Q-to");

        EntityType aEntity = tx.getEntityType("a-entity");
        RelationType Q = tx.getRelationType("Q");
        ConceptId[][] aInstancesIds = new ConceptId[n+1][m+1];
        Thing aInst = putEntityWithResource(tx, "a", tx.getEntityType("entity2"), key);
        for(int i = 1 ; i <= n ;i++) {
            for (int j = 1; j <= m; j++) {
                aInstancesIds[i][j] = putEntityWithResource(tx, "a" + i + "," + j, aEntity, key).id();
            }
        }

        Q.create()
                .assign(Qfrom, aInst)
                .assign(Qto, tx.getConcept(aInstancesIds[1][1]));

        for(int i = 1 ; i <= n ; i++) {
            for (int j = 1; j <= m; j++) {
                if ( i < n ) {
                    Q.create()
                            .assign(Qfrom, tx.getConcept(aInstancesIds[i][j]))
                            .assign(Qto, tx.getConcept(aInstancesIds[i+1][j]));
                }
                if ( j < m){
                    Q.create()
                            .assign(Qfrom, tx.getConcept(aInstancesIds[i][j]))
                            .assign(Qto, tx.getConcept(aInstancesIds[i][j+1]));
                }
            }
        }
    }
}
