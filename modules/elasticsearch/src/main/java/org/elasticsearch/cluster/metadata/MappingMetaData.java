/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.action.TimestampParsingException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.compress.CompressedString;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.joda.FormatDateTimeFormatter;
import org.elasticsearch.common.joda.Joda;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.internal.TimestampFieldMapper;

import java.io.IOException;
import java.util.Map;

import static org.elasticsearch.common.xcontent.support.XContentMapValues.*;

/**
 * @author kimchy (shay.banon)
 */
public class MappingMetaData {

    public static class Routing {

        public static final Routing EMPTY = new Routing(false, null);

        private final boolean required;

        private final String path;

        private final String[] pathElements;

        public Routing(boolean required, String path) {
            this.required = required;
            this.path = path;
            if (path == null) {
                pathElements = Strings.EMPTY_ARRAY;
            } else {
                pathElements = Strings.delimitedListToStringArray(path, ".");
            }
        }

        public boolean required() {
            return required;
        }

        public boolean hasPath() {
            return path != null;
        }

        public String path() {
            return this.path;
        }

        public String[] pathElements() {
            return this.pathElements;
        }
    }

    public static class Timestamp {

        public static String parseStringTimestamp(String timestampAsString, FormatDateTimeFormatter dateTimeFormatter) throws TimestampParsingException {
            long ts;
            try {
                ts = Long.parseLong(timestampAsString);
            } catch (NumberFormatException e) {
                try {
                    ts = dateTimeFormatter.parser().parseMillis(timestampAsString);
                } catch (RuntimeException e1) {
                    throw new TimestampParsingException(timestampAsString);
                }
            }
            return String.valueOf(ts);
        }


        public static final Timestamp EMPTY = new Timestamp(false, null, TimestampFieldMapper.DEFAULT_DATE_TIME_FORMAT);

        private final boolean enabled;

        private final String path;

        private final String format;

        private final String[] pathElements;

        private final FormatDateTimeFormatter dateTimeFormatter;

        public Timestamp(boolean enabled, String path, String format) {
            this.enabled = enabled;
            this.path = path;
            if (path == null) {
                pathElements = Strings.EMPTY_ARRAY;
            } else {
                pathElements = Strings.delimitedListToStringArray(path, ".");
            }
            this.format = format;
            this.dateTimeFormatter = Joda.forPattern(format);
        }

        public boolean enabled() {
            return enabled;
        }

        public boolean hasPath() {
            return path != null;
        }

        public String path() {
            return this.path;
        }

        public String[] pathElements() {
            return this.pathElements;
        }

        public String format() {
            return this.format;
        }

        public FormatDateTimeFormatter dateTimeFormatter() {
            return this.dateTimeFormatter;
        }
    }

    private final String type;

    private final CompressedString source;

    private final Routing routing;
    private final Timestamp timestamp;

    public MappingMetaData(DocumentMapper docMapper) {
        this.type = docMapper.type();
        this.source = docMapper.mappingSource();
        this.routing = new Routing(docMapper.routingFieldMapper().required(), docMapper.routingFieldMapper().path());
        this.timestamp = new Timestamp(docMapper.timestampFieldMapper().enabled(), docMapper.timestampFieldMapper().path(), docMapper.timestampFieldMapper().dateTimeFormatter().format());
    }

    public MappingMetaData(String type, Map<String, Object> mapping) throws IOException {
        this.type = type;
        this.source = new CompressedString(XContentFactory.jsonBuilder().map(mapping).string());
        Map<String, Object> withoutType = mapping;
        if (mapping.size() == 1 && mapping.containsKey(type)) {
            withoutType = (Map<String, Object>) mapping.get(type);
        }
        if (withoutType.containsKey("_routing")) {
            boolean required = false;
            String path = null;
            Map<String, Object> routingNode = (Map<String, Object>) withoutType.get("_routing");
            for (Map.Entry<String, Object> entry : routingNode.entrySet()) {
                String fieldName = Strings.toUnderscoreCase(entry.getKey());
                Object fieldNode = entry.getValue();
                if (fieldName.equals("required")) {
                    required = nodeBooleanValue(fieldNode);
                } else if (fieldName.equals("path")) {
                    path = fieldNode.toString();
                }
            }
            this.routing = new Routing(required, path);
        } else {
            this.routing = Routing.EMPTY;
        }
        if (withoutType.containsKey("_timestamp")) {
            boolean enabled = false;
            String path = null;
            String format = TimestampFieldMapper.DEFAULT_DATE_TIME_FORMAT;
            Map<String, Object> timestampNode = (Map<String, Object>) withoutType.get("_timestamp");
            for (Map.Entry<String, Object> entry : timestampNode.entrySet()) {
                String fieldName = Strings.toUnderscoreCase(entry.getKey());
                Object fieldNode = entry.getValue();
                if (fieldName.equals("enabled")) {
                    enabled = nodeBooleanValue(fieldNode);
                } else if (fieldName.equals("path")) {
                    path = fieldNode.toString();
                } else if (fieldName.equals("format")) {
                    format = fieldNode.toString();
                }
            }
            this.timestamp = new Timestamp(enabled, path, format);
        } else {
            this.timestamp = Timestamp.EMPTY;
        }
    }

    MappingMetaData(String type, CompressedString source, Routing routing, Timestamp timestamp) {
        this.type = type;
        this.source = source;
        this.routing = routing;
        this.timestamp = timestamp;
    }

    public String type() {
        return this.type;
    }

    public CompressedString source() {
        return this.source;
    }

    public Routing routing() {
        return this.routing;
    }

    public Timestamp timestamp() {
        return this.timestamp;
    }

    public ParseContext createParseContext(@Nullable String routing, @Nullable String timestamp) {
        return new ParseContext(
                routing == null && routing().hasPath(),
                timestamp == null && timestamp().hasPath()
        );
    }

    public void parse(XContentParser parser, ParseContext parseContext) throws IOException {
        innerParse(parser, parseContext);
    }

    private void innerParse(XContentParser parser, ParseContext context) throws IOException {
        if (!context.parsingStillNeeded()) {
            return;
        }

        XContentParser.Token t = parser.currentToken();
        if (t == null) {
            t = parser.nextToken();
        }
        if (t == XContentParser.Token.START_OBJECT) {
            t = parser.nextToken();
        }
        String routingPart = context.routingParsingStillNeeded() ? routing().pathElements()[context.locationRouting] : null;
        String timestampPart = context.timestampParsingStillNeeded() ? timestamp().pathElements()[context.locationTimestamp] : null;

        for (; t == XContentParser.Token.FIELD_NAME; t = parser.nextToken()) {
            // Must point to field name
            String fieldName = parser.currentName();
            // And then the value...
            t = parser.nextToken();

            boolean incLocationRouting = false;
            boolean incLocationTimestamp = false;
            if (context.routingParsingStillNeeded() && fieldName.equals(routingPart)) {
                if (context.locationRouting + 1 == routing.pathElements().length) {
                    context.routing = parser.textOrNull();
                    context.routingResolved = true;
                } else {
                    incLocationRouting = true;
                }
            }
            if (context.timestampParsingStillNeeded() && fieldName.equals(timestampPart)) {
                if (context.locationTimestamp + 1 == timestamp.pathElements().length) {
                    context.timestamp = parser.textOrNull();
                    context.timestampResolved = true;
                } else {
                    incLocationTimestamp = true;
                }
            }

            if (incLocationRouting || incLocationTimestamp) {
                if (t == XContentParser.Token.START_OBJECT) {
                    context.locationRouting += incLocationRouting ? 1 : 0;
                    context.locationTimestamp += incLocationTimestamp ? 1 : 0;
                    innerParse(parser, context);
                    context.locationRouting -= incLocationRouting ? 1 : 0;
                    context.locationTimestamp -= incLocationTimestamp ? 1 : 0;
                }
            } else {
                parser.skipChildren();
            }

            if (!context.parsingStillNeeded()) {
                return;
            }
        }
    }

    public static void writeTo(MappingMetaData mappingMd, StreamOutput out) throws IOException {
        out.writeUTF(mappingMd.type());
        mappingMd.source().writeTo(out);
        // routing
        out.writeBoolean(mappingMd.routing().required());
        if (mappingMd.routing().hasPath()) {
            out.writeBoolean(true);
            out.writeUTF(mappingMd.routing().path());
        } else {
            out.writeBoolean(false);
        }
        // timestamp
        out.writeBoolean(mappingMd.timestamp().enabled());
        if (mappingMd.timestamp().hasPath()) {
            out.writeBoolean(true);
            out.writeUTF(mappingMd.timestamp().path());
        } else {
            out.writeBoolean(false);
        }
        out.writeUTF(mappingMd.timestamp().format());
    }

    public static MappingMetaData readFrom(StreamInput in) throws IOException {
        String type = in.readUTF();
        CompressedString source = CompressedString.readCompressedString(in);
        // routing
        Routing routing = new Routing(in.readBoolean(), in.readBoolean() ? in.readUTF() : null);
        // timestamp
        Timestamp timestamp = new Timestamp(in.readBoolean(), in.readBoolean() ? in.readUTF() : null, in.readUTF());
        return new MappingMetaData(type, source, routing, timestamp);
    }

    public static class ParseResult {
        public final String routing;
        public final boolean routingResolved;
        public final String timestamp;
        public final boolean timestampResolved;

        public ParseResult(String routing, boolean routingResolved, String timestamp, boolean timestampResolved) {
            this.routing = routing;
            this.routingResolved = routingResolved;
            this.timestamp = timestamp;
            this.timestampResolved = timestampResolved;
        }
    }

    public static class ParseContext {

        final boolean shouldParseRouting;
        final boolean shouldParseTimestamp;

        int locationRouting = 0;
        int locationTimestamp = 0;
        boolean routingResolved;
        boolean timestampResolved;
        String routing;
        String timestamp;

        public ParseContext(boolean shouldParseRouting, boolean shouldParseTimestamp) {
            this.shouldParseRouting = shouldParseRouting;
            this.shouldParseTimestamp = shouldParseTimestamp;
        }

        /**
         * The routing value parsed, <tt>null</tt> if does not require parsing, or not resolved.
         */
        public String routing() {
            return routing;
        }

        /**
         * Does routing parsing really needed at all?
         */
        public boolean shouldParseRouting() {
            return shouldParseRouting;
        }

        /**
         * Has routing been resolved during the parsing phase.
         */
        public boolean routingResolved() {
            return routingResolved;
        }

        /**
         * Is routing parsing still needed?
         */
        public boolean routingParsingStillNeeded() {
            return shouldParseRouting && !routingResolved;
        }

        /**
         * The timestamp value parsed, <tt>null</tt> if does not require parsing, or not resolved.
         */
        public String timestamp() {
            return timestamp;
        }

        /**
         * Does timestamp parsing really needed at all?
         */
        public boolean shouldParseTimestamp() {
            return shouldParseTimestamp;
        }

        /**
         * Has timestamp been resolved during the parsing phase.
         */
        public boolean timestampResolved() {
            return timestampResolved;
        }

        /**
         * Is timestamp parsing still needed?
         */
        public boolean timestampParsingStillNeeded() {
            return shouldParseTimestamp && !timestampResolved;
        }

        /**
         * Do we really need parsing?
         */
        public boolean shouldParse() {
            return shouldParseRouting || shouldParseTimestamp;
        }

        /**
         * Is parsing still needed?
         */
        public boolean parsingStillNeeded() {
            return routingParsingStillNeeded() || timestampParsingStillNeeded();
        }
    }
}
