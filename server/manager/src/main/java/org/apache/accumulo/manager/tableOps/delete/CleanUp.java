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
package org.apache.accumulo.manager.tableOps.delete;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Map.Entry;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.clientImpl.thrift.ThriftSecurityException;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.NamespaceId;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.fate.FateId;
import org.apache.accumulo.core.fate.Repo;
import org.apache.accumulo.core.fate.zookeeper.DistributedReadWriteLock.LockType;
import org.apache.accumulo.core.iterators.user.GrepIterator;
import org.apache.accumulo.core.metadata.SystemTables;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.DataFileColumnFamily;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.manager.Manager;
import org.apache.accumulo.manager.tableOps.ManagerRepo;
import org.apache.accumulo.manager.tableOps.Utils;
import org.apache.accumulo.server.fs.VolumeManager;
import org.apache.accumulo.server.util.MetadataTableUtil;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class CleanUp extends ManagerRepo {

  private static final Logger log = LoggerFactory.getLogger(CleanUp.class);

  private static final long serialVersionUID = 1L;

  private final TableId tableId;
  private final NamespaceId namespaceId;

  public CleanUp(TableId tableId, NamespaceId namespaceId) {
    this.tableId = tableId;
    this.namespaceId = namespaceId;
  }

  @Override
  public Repo<Manager> call(FateId fateId, Manager manager) {
    int refCount = 0;

    try {
      // look for other tables that references this table's files
      AccumuloClient client = manager.getContext();
      try (BatchScanner bs =
          client.createBatchScanner(SystemTables.METADATA.tableName(), Authorizations.EMPTY, 8)) {
        Range allTables = TabletsSection.getRange();
        Range tableRange = TabletsSection.getRange(tableId);
        Range beforeTable =
            new Range(allTables.getStartKey(), true, tableRange.getStartKey(), false);
        Range afterTable = new Range(tableRange.getEndKey(), false, allTables.getEndKey(), true);
        bs.setRanges(Arrays.asList(beforeTable, afterTable));
        bs.fetchColumnFamily(DataFileColumnFamily.NAME);
        IteratorSetting cfg = new IteratorSetting(40, "grep", GrepIterator.class);
        GrepIterator.setTerm(cfg, "/" + tableId + "/");
        bs.addScanIterator(cfg);

        for (Entry<Key,Value> entry : bs) {
          if (entry.getKey().getColumnQualifier().toString().contains("/" + tableId + "/")) {
            refCount++;
          }
        }
      }

    } catch (Exception e) {
      refCount = -1;
      log.error("Failed to scan " + SystemTables.METADATA.tableName()
          + " looking for references to deleted table " + tableId, e);
    }

    // remove metadata table entries
    try {
      // Intentionally do not pass manager lock. If manager loses lock, this operation may complete
      // before manager can kill itself.
      // If the manager lock passed to deleteTable, it is possible that the delete mutations will be
      // dropped. If the delete operations
      // are dropped and the operation completes, then the deletes will not be repeated.
      MetadataTableUtil.deleteTable(tableId, refCount != 0, manager.getContext(), null);
    } catch (Exception e) {
      log.error("error deleting " + tableId + " from metadata table", e);
    }

    if (refCount == 0) {
      // delete the data files
      try {
        VolumeManager fs = manager.getVolumeManager();
        for (String dir : manager.getContext().getTablesDirs()) {
          fs.deleteRecursively(new Path(dir, tableId.canonical()));
        }
      } catch (IOException e) {
        log.error("Unable to remove deleted table directory", e);
      } catch (IllegalArgumentException exception) {
        if (exception.getCause() instanceof UnknownHostException) {
          /* Thrown if HDFS encounters a DNS problem in some edge cases */
          log.error("Unable to remove deleted table directory", exception);
        } else {
          throw exception;
        }
      }
    }

    // remove table from zookeeper
    try {
      manager.getTableManager().removeTable(tableId, namespaceId);
      manager.getContext().clearTableListCache();
    } catch (Exception e) {
      log.error("Failed to find table id in zookeeper", e);
    }

    // remove any permissions associated with this table
    try {
      manager.getContext().getSecurityOperation().deleteTable(manager.getContext().rpcCreds(),
          tableId, namespaceId);
    } catch (ThriftSecurityException e) {
      log.error("{}", e.getMessage(), e);
    }

    Utils.unreserveTable(manager, tableId, fateId, LockType.WRITE);
    Utils.unreserveNamespace(manager, namespaceId, fateId, LockType.READ);

    LoggerFactory.getLogger(CleanUp.class).debug("Deleted table " + tableId);

    return null;
  }

  @Override
  public void undo(FateId fateId, Manager environment) {
    // nothing to do
  }

}
