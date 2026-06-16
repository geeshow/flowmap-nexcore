package com.flowmap.nexcore.model;

/**
 * Runtime entry-point kind (reachability root). NEXCORE only uses two:
 * {@link #HTTP} for ProcessUnit transactions (external {@code /<Tid>.jmd} calls)
 * and {@link #BATCH} for batch job {@code execute()} methods. The full set is kept
 * for schema compatibility with flowmap-spring.
 */
public enum EntryPointKind {
    HTTP, KAFKA, RABBIT, JMS, SQS, SCHEDULED, EVENT, RUNNER, WEBSOCKET, GRPC, BATCH
}
