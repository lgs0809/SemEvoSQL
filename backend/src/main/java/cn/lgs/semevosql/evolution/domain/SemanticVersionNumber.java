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
package cn.lgs.semevosql.evolution.domain;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable MAJOR.MINOR.PATCH Semantic Version value. */
public record SemanticVersionNumber(int major, int minor, int patch) implements Comparable<SemanticVersionNumber> {

    private static final Pattern PATTERN = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$");

    public SemanticVersionNumber {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Semantic Version components must be non-negative");
        }
    }

    public static SemanticVersionNumber parse(String value) {
        Objects.requireNonNull(value, "Semantic Version is required");
        Matcher matcher = PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Semantic Version must use MAJOR.MINOR.PATCH format: " + value);
        }
        return new SemanticVersionNumber(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
    }

    public SemanticVersionNumber next(SemanticVersionLevel level) {
        Objects.requireNonNull(level, "Semantic Version level is required");
        return switch (level) {
            case PATCH -> new SemanticVersionNumber(major, minor, Math.addExact(patch, 1));
            case MINOR -> new SemanticVersionNumber(major, Math.addExact(minor, 1), 0);
            case MAJOR -> new SemanticVersionNumber(Math.addExact(major, 1), 0, 0);
            case INITIAL -> throw new IllegalArgumentException("INITIAL is not a valid increment level");
        };
    }

    @Override
    public int compareTo(SemanticVersionNumber other) {
        int majorComparison = Integer.compare(major, other.major);
        if (majorComparison != 0) {
            return majorComparison;
        }
        int minorComparison = Integer.compare(minor, other.minor);
        return minorComparison != 0 ? minorComparison : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

}
