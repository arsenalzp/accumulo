/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.test.fate.user;

import static org.apache.accumulo.test.fate.FateTestUtil.createFateTable;
import static org.apache.accumulo.test.fate.TestLock.createDummyLockID;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.clientImpl.ClientContext;
import org.apache.accumulo.core.fate.AbstractFateStore;
import org.apache.accumulo.core.fate.FateStore;
import org.apache.accumulo.core.fate.user.UserFateStore;
import org.apache.accumulo.test.fate.FateExecutionOrderITBase;

public class UserFateExecutionOrderIT_SimpleSuite extends FateExecutionOrderITBase {
  @Override
  public void executeTest(FateTestExecutor<FeoTestEnv> testMethod, int maxDeferred,
      AbstractFateStore.FateIdGenerator fateIdGenerator) throws Exception {
    var table = getUniqueNames(1)[0];
    try (ClientContext client = (ClientContext) Accumulo.newClient().from(getClientProps()).build();
        FateStore<FeoTestEnv> fs = new UserFateStore<>(client, table, createDummyLockID(), null,
            maxDeferred, fateIdGenerator)) {
      createFateTable(client, table);
      testMethod.execute(fs, getCluster().getServerContext());
      client.tableOperations().delete(table);
    }
  }
}
