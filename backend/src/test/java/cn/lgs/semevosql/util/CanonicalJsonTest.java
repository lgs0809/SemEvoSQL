/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.lgs.semevosql.util;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {
    @Test void objectKeysAndSetsAreCanonicalButSqlParameterOrderIsPreserved() throws Exception {
        var first=new LinkedHashMap<String,Object>();first.put("z",new LinkedHashSet<>(List.of("b","a")));first.put("a",Map.of("y",2,"x",1));
        var second=new LinkedHashMap<String,Object>();second.put("a",Map.of("x",1,"y",2));second.put("z",new LinkedHashSet<>(List.of("a","b")));
        assertEquals(CanonicalJson.write(first),CanonicalJson.write(second));
        assertNotEquals(CanonicalJson.write(List.of(1,2)),CanonicalJson.write(List.of(2,1)));
    }
}
