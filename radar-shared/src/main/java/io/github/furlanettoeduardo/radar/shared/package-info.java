/**
 * Message contracts exchanged between radar-ingestion and radar-api over SQS.
 *
 * <p>Records only, no framework. Each adapter owns its own serialization, so a contract never
 * carries a mapping annotation. If Spring configuration ever needs to be shared between the
 * services, it gets a dedicated module rather than a place here.
 */
package io.github.furlanettoeduardo.radar.shared;
