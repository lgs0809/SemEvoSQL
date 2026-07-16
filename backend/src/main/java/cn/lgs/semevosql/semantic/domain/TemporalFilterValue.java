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
package cn.lgs.semevosql.semantic.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;

/** Typed temporal literals shared by semantic validation and JDBC parameter compilation. */
public final class TemporalFilterValue {
    private TemporalFilterValue() { }

    public static Object normalize(String dataType, Object value) {
        String type = Objects.toString(dataType, "").toUpperCase(Locale.ROOT)
            .replaceAll("\\([^)]*\\)", "").trim();
        boolean timestamp = type.startsWith("TIMESTAMP") || type.equals("DATETIME");
        boolean date = type.equals("DATE");
        boolean time = !timestamp && type.startsWith("TIME");
        if ((!timestamp && !date && !time) || value == null) return value;
        if (value instanceof Collection<?> values) return values.stream().map(v -> normalize(dataType, v)).toList();
        String text = Objects.toString(value, "").trim();
        try {
            if (date) return LocalDate.parse(text);
            if (time) {
                try { return OffsetTime.parse(text); }
                catch (DateTimeParseException noOffset) { return LocalTime.parse(text); }
            }
            String iso = text.replace(' ', 'T');
            try { return OffsetDateTime.parse(iso); }
            catch (DateTimeParseException noOffset) {
                try { return LocalDateTime.parse(iso); }
                catch (DateTimeParseException noTime) { return LocalDate.parse(iso).atStartOfDay(); }
            }
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("Temporal filter requires a valid ISO date/time literal for " + type
                + "; use timeBinding for a month, period, range or relative-time description", invalid);
        }
    }
}
