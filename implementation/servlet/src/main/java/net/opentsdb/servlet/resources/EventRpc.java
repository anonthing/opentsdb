// This file is part of OpenTSDB.
// Copyright (C) 2018 The OpenTSDB Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package net.opentsdb.servlet.resources;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import net.opentsdb.auth.AuthState;
import net.opentsdb.auth.AuthState.AuthStatus;
import net.opentsdb.auth.Authentication;
import net.opentsdb.core.TSDB;
import net.opentsdb.data.BaseTimeSeriesStringId;
import net.opentsdb.events.*;
import net.opentsdb.meta.*;
import net.opentsdb.meta.MetaDataStorageResult.MetaResult;
import net.opentsdb.servlet.applications.OpenTSDBApplication;
import net.opentsdb.servlet.exceptions.GenericExceptionMapper;
import net.opentsdb.servlet.filter.AuthFilter;
import net.opentsdb.stats.Span;
import net.opentsdb.stats.Trace;
import net.opentsdb.stats.Tracer;
import net.opentsdb.utils.Bytes;
import net.opentsdb.utils.JSON;
import net.opentsdb.utils.Pair;

import net.opentsdb.utils.UniqueKeyPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.AsyncContext;
import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("search/events")
public class EventRpc {
  private static final Logger LOG = LoggerFactory.getLogger(EventRpc.class);

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public void post(final @Context ServletConfig servlet_config,
                   final @Context HttpServletRequest request,
                   final @Context HttpServletResponse response) throws Exception {
    Object obj = servlet_config.getServletContext()
      .getAttribute(OpenTSDBApplication.TSD_ATTRIBUTE);
    if (obj == null) {
      throw new WebApplicationException("Unable to pull TSDB instance from "
        + "servlet context.",
        Response.Status.INTERNAL_SERVER_ERROR);
    } else if (!(obj instanceof TSDB)) {
      throw new WebApplicationException("Object stored for as the TSDB was "
        + "of the wrong type: " + obj.getClass(),
        Response.Status.INTERNAL_SERVER_ERROR);
    }
    final TSDB tsdb = (TSDB) obj;

    if (tsdb.getStatsCollector() != null) {
      tsdb.getStatsCollector().incrementCounter("query.new", "endpoint",
        "events");
    }

    // check auth.
    final AuthState auth_state;
    if (tsdb.getConfig().getBoolean(Authentication.AUTH_ENABLED_KEY)) {
      if (request.getAttribute(AuthFilter.AUTH_STATE_KEY) == null ||
        ((AuthState) request.getAttribute(AuthFilter.AUTH_STATE_KEY))
          .getStatus() != AuthStatus.SUCCESS) {
        throw new WebApplicationException("Access denied.",
          Response.Status.FORBIDDEN);
      }
      auth_state = (AuthState) request.getAttribute(AuthFilter.AUTH_STATE_KEY);
    } else {
      auth_state = null; // TODO - add an "unknown" auth user.
    }

    // initiate the tracer
    final Trace trace;
    final Span query_span;
    final Tracer tracer = (Tracer) tsdb.getRegistry().getDefaultPlugin(Tracer.class);
    if (tracer != null) {
      trace = tracer.newTrace(true, true);
      query_span = trace.newSpanWithThread(this.getClass().getSimpleName())
        .withTag("endpoint", "/api/search")
        .withTag("user", auth_state != null ? auth_state.getUser() : "Unkown")
        // TODO - more useful info
        .start();
    } else {
      trace = null;
      query_span = null;
    }
    Span parse_span = null;
    if (query_span != null) {
      parse_span = trace.newSpanWithThread("parseAndValidate")
        .withTag("startThread", Thread.currentThread().getName())
        .asChildOf(query_span)
        .start();
    }

    // parse the query
    final String content_type = request.getHeader("Content-Type");
    final BatchEventQuery query;
    if (content_type != null && content_type.toLowerCase().contains("yaml")) {
      throw new Exception("yaml query not supported. please use json");
    } else {
      ObjectMapper mapper = JSON.getMapper();
      JsonNode node = mapper.readTree(request.getInputStream());
      query = DefaultBatchEventQuery.parse(tsdb, mapper, node).build();
    }

    ObjectMapper map = new ObjectMapper();
    LOG.info("Event query == " + map.writeValueAsString(query));
    // TODO validate
    if (parse_span != null) {
      parse_span.setSuccessTags()
        .finish();
    }

    // TODO - actual async
    final AsyncContext async = request.startAsync();
    async.setTimeout((Integer) servlet_config.getServletContext()
      .getAttribute(OpenTSDBApplication.ASYNC_TIMEOUT_ATTRIBUTE));

    LOG.info("Executing new query=" + JSON.serializeToString(
      ImmutableMap.<String, Object>builder()
        // TODO - possible upstream headers
        .put("queryId", Bytes.byteArrayToString(String.valueOf(query.hashCode()).getBytes()))
        .put("traceId", trace != null ? trace.traceId() : "")
        .put("query", JSON.serializeToString(query))
        .build()));
    Span setup_span = null;
    if (query_span != null) {
      setup_span = trace.newSpanWithThread("setupContext")
        .withTag("startThread", Thread.currentThread().getName())
        .asChildOf(query_span)
        .start();
    }

    try {


      final Map<String, EventsStorageResult> eventsStorageResults =
        tsdb.getRegistry().getDefaultPlugin(
          EventsStorageSchema.class).runQuery(query, query_span)
          .join();

      response.setContentType(MediaType.APPLICATION_JSON);
      ByteArrayOutputStream stream = new ByteArrayOutputStream();

      JsonFactory factory = new JsonFactory();

      JsonGenerator json = factory.createGenerator(stream);

      json.writeStartObject();

      for (Map.Entry<String, EventsStorageResult> eventsStoreEntry : eventsStorageResults
        .entrySet()) {
        EventsStorageResult result = eventsStoreEntry.getValue();
        Map<String, List<Bucket>> buckets = result.buckets();
        System.out.println(buckets);



      // if (result.buckets().size() != 0) {
      if (buckets.size() != 0) {
        json.writeFieldName("buckets");
        json.writeStartArray();
        for (Map.Entry<String, List<Bucket>> bucket : buckets.entrySet()) {
          json.writeStartObject();
          json.writeFieldName(bucket.getKey());
          json.writeStartArray();
         // json.writeFieldName(bucket.getKey());
        //  json.writeStartObject();
         // json.writeStartObject(bucket.getKey());
          for (Bucket b: bucket.getValue()) {
            json.writeStartObject();
            json.writeNumberField(b.key(), b.count());
            json.writeEndObject();

          }
          json.writeEndArray();
          json.writeEndObject();

        }
        json.writeEndArray();
       // json.writeEndArray();
      }
    }
      json.writeEndObject();
      json.close();
      final byte[] data = stream.toByteArray();
      response.getOutputStream().write(data);
      response.getOutputStream().close();
      stream.close();

    } catch (Throwable t) {
      LOG.error("Unexpected exception triggering query.", t);
      GenericExceptionMapper.serialize(t, response);
      async.complete();
      if (query_span != null) {
        query_span.setErrorTags(t)
          .finish();
      }
    }

  }

}
