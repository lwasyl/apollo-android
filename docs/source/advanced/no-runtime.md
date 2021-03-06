---
title: Using apollo without `apollo-runtime` 
---

`apollo-runtime` and `ApolloClient` provides support for doing the network requests and interacting with the cache but you can use the generated queries without the runtime if you want.

For this, remove the `com.apollographql.apollo:apollo-runtime`dependency and replace it with:

```
  implementation("com.apollographql.apollo:apollo-api:x.y.z")
```

## Composing HTTP request body

To compose HTTP POST request body `Operation` provides such API:

```java
    Query query = ...
    ByteString payload = query.composeRequestBody();
    okhttp3.MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
    okhttp3.RequestBody requestBody = RequestBody.create(mediaType, payload);
```

If GraphQL operation defines any variable with custom scalar type, you must provide properly configured instance of `com.apollographql.apollo.response.ScalarTypeAdapters`:

```java
    ScalarTypeAdapters scalarTypeAdapters = new ScalarTypeAdapters(<provide your custom scalar type adapters>);
    Query query = ...
    ByteString payload = query.composeRequestBody(scalarTypeAdapters);
    okhttp3.MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
    okhttp3.RequestBody requestBody = RequestBody.create(mediaType, payload);
```

In case when GraphQL server supports auto persistence query:

```java
    Query query = ...
    boolean autoPersistQueries = ... // encode extensions attributes required by query auto persistence or not
    withQueryDocument = ... // encode query document or not
    ScalarTypeAdapters scalarTypeAdapters = ...
    ByteString payload = query.composeRequestBody(autoPersistQueries, withQueryDocument, scalarTypeAdapters);
    okhttp3.MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
    okhttp3.RequestBody requestBody = RequestBody.create(mediaType, payload);
```

## Parsing HTTP response body

All `Operation` instances provide an API to parse `Response` from raw `okio.BufferedSource` source that represents http response body returned by the GraphQL server.
If for some reason you want to use your own network layer and don't want to use fully featured `ApolloClient` provided by `apollo-runtime` you can use this API:

```java
    okhttp3.Response httpResponse = ...;

    Response<Operation.Data> response = new Query().parse(httpResponse.body().source());
```

If you do have custom GraphQL scalar types, pass properly configured instance of `com.apollographql.apollo.response.ScalarTypeAdapters`:

```java
    okhttp3.Response httpResponse = ...;

    ScalarTypeAdapters scalarTypeAdapters = new ScalarTypeAdapters(<provide your custom scalar type adapters>);

    Response<Operation.Data> response = new Query().parse(httpResponse.body().source(), scalarTypeAdapters);
```

## Converting Query.Data back to JSON

In case you have an instance of `Operation.Data` and want to convert it back to JSON representation, you can use `OperationDataJsonSerializer.serialize` static method.

```java
    Operation.Data data = ...;

    String json = OperationDataJsonSerializer.serialize(data, "  ");
```

Just like above, you can provide instance of custom `ScalarTypeAdapters` as last argument.

Simpler extension function is available for `Kotlin` users:
```kotlin
   val json = data.toJson()

   // or
   val json = data.toJson(indent = "  ")
```

## Creating request payload for POST request

To compose a GraphQL POST request along with operation variables to be sent to the server, you can use `Operation.Variables#marshal()` API: 

```java
    // Generated GraphQL query, mutation, subscription
    Query query = ...;

    String requestPayload = "{" +
        "\"operationName\": " + query.name().name() + ", " +
        "\"query\": " + query.queryDocument() + ", " +
        "\"variables\": " + query.variables().marshal() +
        "}";
```

The same to serialize variables with the custom GraphQL scalar type adapters:

```java
    // Generated GraphQL query, mutation, subscription
    Query query = ...;
  
    ScalarTypeAdapters scalarTypeAdapters = new ScalarTypeAdapters(<provide your custom scalar type adapters>);

    String requestPayload = "{" +
        "\"operationName\": " + query.name().name() + ", " +
        "\"query\": " + query.queryDocument() + ", " +
        "\"variables\": " + query.variables().marshal(scalarTypeAdapters) +
        "}";
```
