package com.flowmap.nexcore.model;

/**
 * Node layer — identical set to flowmap-spring's {@code Layer} so the same
 * web renderer/colour scheme applies. NEXCORE mapping:
 * ProcessUnit→CONTROLLER, FunctionUnit→SERVICE, DataUnit→REPOSITORY,
 * batch job→BATCH, linked/outbound→EXTERNAL, kafka/db→RESOURCE.
 */
public enum Layer {
    CONTROLLER, SERVICE, REPOSITORY, COMPONENT, CONFIG, BATCH, EXTERNAL, RESOURCE, GATEWAY, OTHER
}
