# Webbing #

Webbing is a collection of independent libraries to support writing of web services on Finagle.

## `webbing/route` ##

The `webbing/route` library provides *route combinator( primitives to support the asynchronous routing of
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

## License ##

These libraries should be open-sourced as we gain confidence in them. They are currently Twitter-internal, however.
