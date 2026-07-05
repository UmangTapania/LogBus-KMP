package com.github.umangtapania.logbus

/**
 * A route's tool for turning an event into a string — any way the dev wants (plain, JSON, boxed,
 * one-line). A formatter **belongs to the route, not the bus**: a route holds one and uses it inside
 * its own [LogRoute.log]. The bus never touches formatting.
 *
 * Only the interface exists for now; concrete formatters (like box + emoji) are add-ons shipped later.
 */
public fun interface LogFormatter {
    public fun format(event: LogEvent): String
}
