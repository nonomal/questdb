/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2024 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.test.griffin.engine.functions;

import io.questdb.griffin.SqlException;
import io.questdb.std.ObjList;
import io.questdb.test.AbstractCairoTest;
import io.questdb.test.tools.BindVariableTestTuple;
import org.junit.Test;

public class InTimestampTimestampTest extends AbstractCairoTest {
    @Test
    public void testBindVarTypeChange() throws SqlException {
        ddl("create table test as (select rnd_int() a, timestamp_sequence(0, 1000) ts from long_sequence(100))");

        // when more than one argument supplied, the function will match exact values from the list
        final ObjList<BindVariableTestTuple> tuples = new ObjList<>();
        tuples.add(new BindVariableTestTuple(
                "simple",
                "a\tts\n" +
                        "-1148479920\t1970-01-01T00:00:00.000000Z\n" +
                        "315515118\t1970-01-01T00:00:00.001000Z\n" +
                        "-948263339\t1970-01-01T00:00:00.005000Z\n",
                bindVariableService -> {
                    bindVariableService.setInt(0, 0);
                    bindVariableService.setInt(1, 1000);
                    bindVariableService.setStr(2, "1970-01-01T00:00:00.005000Z");
                }
        ));

        tuples.add(new BindVariableTestTuple(
                "type change",
                "a\tts\n" +
                        "1326447242\t1970-01-01T00:00:00.006000Z\n" +
                        "592859671\t1970-01-01T00:00:00.007000Z\n" +
                        "-1191262516\t1970-01-01T00:00:00.010000Z\n",
                bindVariableService -> {
                    bindVariableService.setLong(0, 6000);
                    bindVariableService.setStr(1, "1970-01-01T00:00:00.007000Z");
                    bindVariableService.setInt(2, 10_000);
                }
        ));

        assertSql("test where ts in ($1,$2,$3)", tuples);

        assertSql("test where ts in $1", tuples);
//
//        assertSql("test where ts in '2004'", tuples);
//
//        assertSql("test where ts in (1000, 2000, '1970-01-01T00:00:00.007000Z')", tuples);

    }
}
