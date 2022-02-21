/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.query.graphql;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.apache.skywalking.oap.server.library.server.jetty.JettyJsonHandler;
import org.apache.skywalking.oap.server.library.util.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RequiredArgsConstructor
public class GraphQLQueryHandler extends JettyJsonHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphQLQueryHandler.class);

    private static final String QUERY = "query";
    private static final String VARIABLES = "variables";
    private static final String DATA = "data";
    private static final String ERRORS = "errors";
    private static final String MESSAGE = "message";

    private final Gson gson = new Gson();
    private final Type mapOfStringObjectType = new TypeToken<Map<String, Object>>() {
    }.getType();

    // 这两个 final 字段对应的 构造函数在 RequiredArgsConstructor 中
    private final String path;

    private final GraphQL graphQL;

    @Override
    public String pathSpec() {
        return path;
    }

    @Override
    protected JsonElement doPost(HttpServletRequest req) throws IOException {
        // 主要逻辑在这里：调用 GraphQL 引擎执行请求
        BufferedReader reader = new BufferedReader(new InputStreamReader(req.getInputStream()));
        String line;
        StringBuilder sb = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }

        // 将 json 字符串转换成 JsonObject 对象
        JsonObject requestJson = gson.fromJson(sb.toString(), JsonObject.class);

        /**
         * note 从请求中解析 query 参数
         *
         * todo 支持解析出 ExecutionInput 中的其他数据，例如上下文信息、dataLoader和operationName等
         */
        String query = requestJson.get(QUERY).getAsString();
        JsonElement variables = requestJson.get(VARIABLES);
        Map<String, Object> variablesMap = gson.fromJson(variables, mapOfStringObjectType);
        return execute(query,variablesMap );
    }

    private JsonObject execute(String query, Map<String, Object> variables) {
        try {
            final ExecutionInput.Builder queryBuilder = ExecutionInput.newExecutionInput().query(query);
            if (CollectionUtils.isNotEmpty(variables)) {
                queryBuilder.variables(variables);
            }
            final ExecutionInput executionInput = queryBuilder.build();
            // note 执行请求
            ExecutionResult executionResult = graphQL.execute(executionInput);
            LOGGER.debug("Execution result is {}", executionResult);

            // note
            Object data = executionResult.getData();
            List<GraphQLError> errors = executionResult.getErrors();
            JsonObject jsonObject = new JsonObject();
            if (data != null) {
                // 将 data 转换为 JsonObject 对象
                jsonObject.add(DATA, gson.fromJson(gson.toJson(data), JsonObject.class));
            }

            // 如果错误不为空，则返回所有错误
            if (CollectionUtils.isNotEmpty(errors)) {
                // 错误数组
                JsonArray errorArray = new JsonArray();
                errors.forEach(error -> {
                    JsonObject errorJson = new JsonObject();
                    // todo 没有更详细的信息了，例如位置和类型
                    errorJson.addProperty(MESSAGE, error.getMessage());
                    errorArray.add(errorJson);
                });
                jsonObject.add(ERRORS, errorArray);
            }
            // todo 没有 executionResult.getExtensions()
            return jsonObject;
        } catch (final Throwable e) {
            LOGGER.error(e.getMessage(), e);
            JsonObject jsonObject = new JsonObject();
            JsonArray errorArray = new JsonArray();
            JsonObject errorJson = new JsonObject();
            errorJson.addProperty(MESSAGE, e.getMessage());
            errorArray.add(errorJson);
            jsonObject.add(ERRORS, errorArray);
            return jsonObject;
        }
    }
}
