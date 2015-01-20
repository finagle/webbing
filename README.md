# Webbing #

Webbing is a collection of independent libraries to support writing of web services on Finagle.

## `webbing/route` ##

The `webbing/route` library provides *route combinator* primitives to support the asynchronous routing of
arbitrary requests.  Routes are written as expressions that process requests, extracting values and making
assertions as necessary. When successful, a Route yields some strongly-typed result. When a request cannot
be routed, processing may fall back to other routes.

The `webbing/route-finagle-http` project provides utilities for routing (Netty) HTTP requests.  This is currently the
only concrete router implementation.

This library is currently **experimental** and the API is subject to change.

## Example ##

The `webbing/example` project provides a simplistic example of how one
might build a web app using webbing/route.  In order to run the service:

    :; webbing/example/run
    ...
    I 0205 22:21:02.046 THREAD1 com.twitter.webbing.Example$.main: serving com.twitter.webbing.Example=0.0.0.0:8080

And then you can play with the API using curl:

    :; curl -s localhost:8080/api/1/users
    ver
    :; curl -s localhost:8080/api/1/ver
    dog boojum
    :; curl -s localhost:8080/api/1/ver/dog
    boojum
    :; curl -sXPUT -urobey:yebor localhost:8080/api/1/robey/cat/commie
    :; curl -s localhost:8080/api/1/users
    ver
    robey
    :; curl -s localhost:8080/api/1/robey/cat
    commie

License
-------

Licensed under the **[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)** (the "License");
you may not use this software except in compliance with the License.

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

