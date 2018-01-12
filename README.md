## to run

```$bash
$ sbt run
```
## to test

```$bash
$ sbt test
```

## basic design

The server is built on top of akka and akka-http. Requests are dispatched through the akka-http routing DSL 
(via `EmailApiRoutes`) and are then handed-off to an actor, `EmailRegistryActor` which holds a registry of known 
mailboxes. Each mailbox is owned by an actor of type `MailboxActor`, which is a child of the registry actor. 

Mailbox-specific requests are forwarded by `EmailRegistryActor` directly to the appropriate `MailboxActor`, which
responds directly to the flow that originally made the request.

## notes on some design decisions

### mailbox data structure & pagination

The mailbox itself is stored in a `TreeMap`, which is backed by a B-tree implementation.
The message ID serves both as a primary key and an index by timestamp. This has the advantage of being simple,
but the disadvantage that there are assumptions baked into the message ID, which will most likely lead
to troubles down the road as the functionality evolves.

A more flexible implementation would have one or more indexes on the mailbox.

For the `TreeMap`, lookups are `O(log N)`, which means that lookups, deletions and pagination will not
take constant time. The approach to pagination is to send a cursor in the response body of the
mailbox listing request. The cursor is the last seen message ID. This means that the farther down
in the mailbox the request accesses, the longer the request will take, with a worst case of
`log N`. 

Although requests of this nature would be faster if we kept an iterator around between requests, the implementation would become
complicated in a couple of ways:

1. modification to the mailbox would need to cause iterators to become invalid
2. iterators would need to have a TTL so as to avoid keeping ones around that are not being used

Furthermore, the access pattern would be unlikely to warrant such a complication, as most people usually
only look at the top page of their inbox. 

### unique email addresses

There are a few strategies for generating "random" (but unique) email addresses. For now, I went with 
strategy #1. Strategy #3 is more robust. To see if it is acceptable, I'd try it out and see whether it 
is an issue during stress tests.

strategy 1:
- use a monotomically increasing counter and append it to some name
- pro: guaranteed unique, very fast, no locking, no contention
- con: not actually random, and easy to guess

strategy 2:
- use system nano time
- pro: should be fast and non-blocking
- con: relying on guarantee that no two things are created in the same nanosecond (pretty unlikely, but possible)
- con: harder to guess than a sequence number, but still guessable and not random

strategy 3:
- use a UUID
- pro: totally random, not easily guessed
- con: default impl uses SecureRandom which contains a synchronized block. This might be OK at low scale,
    especially since there's only one registry actor (no contention), but at scale, it's known to be slow. Also, 
    on a large codebase, there's no guarantees it's not being used elsewhere and causing surprising bottlenecks.

alternatives to make UUID not be blocking and/or make the blocking trivial:
 - a centralized actor that hands out UUIDs. Maybe it caches some in the background
 - use an implementation that has some thread local generator
 - use another library that is known to be faster
 - use some other method of generating random (but unique) email addresses (e.g., just a simple counter)

### Unique message IDs

Technically, the message ID generation can use the same strategy as unique email address generation. However,
for the sake of keeping things simple, I used current system time in nanoseconds as the message ID. This is so
that the messages can be sorted by ID and at the same time be sorted by most recent first. There is a risk that
messages IDs won't be unique, but that's quite low. If necessary, a random bit could be added to the end of the IDs
to virtually guarantee uniqueness for the age of the universe.

## TODOs

* implement purging of old emails (_idea: use a timer actor to periodically clean things up_)
* implement constraints on memomry size (e.g., number and size of mailboxes)
* correctness tests
    * paging through a mailbox while it is being updated
    * any way to kill a mailbox actor accidentally?
    * try to make the server crash -- made-up endpoints, wacky-looking requests, etc.
* stress tests
    * lots of mailboxes
    * lots of messages in mailboxes
    * lots of simultaneous requests
    * lots of requests to one mailbox at once
