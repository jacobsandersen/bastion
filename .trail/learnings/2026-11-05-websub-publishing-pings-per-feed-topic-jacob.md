---
title: "WebSub publishing pings per-feed topic URLs derived from each post, and stays independent of the feed format"
author: Jacob
date: 2026-11-05
branch: main
cost: { minutes: 20, tokens: 96546 }
id: l-a0d754a4
scope: repo
status: active
src: agent
---

## Decided
Bastion has no single topic URL setting. The frontend (Herald) serves the feeds as mf2 h-feeds (Atom may come later), and Bastion only tells the configured hubs which of those feed URLs changed. It never fetches or parses a feed, so no feed format is assumed.
Each post change pings every feed the post belongs to: main, uncategorized (no tags), per post type (only listed types), per tag (as written, no hashtag, URL-encoded like encodeURIComponent), and the year feed for the current year only (earlier years are archives).
The change event carries the post's type, tags and published year from before and after the write, so feeds a post just left (removed tag, changed type) are pinged too. The user preferred a fatter event over leaving those feeds stale.

## Tried and ruled out
Pinging with the new post's permalink as the topic: subscribers subscribe to feeds, not posts, so the hub would find nobody. Dropping the topic entirely: the publish ping needs hub.url.
Deriving topics from a hardcoded feed path: breaks whenever the frontend's feed paths change.

## Watch out
The websub module may only depend on the post events interface, so it cannot load posts; anything it needs to decide topics must travel on the event.
Pinging more than the current-year feed would be wasted work. Tag URL encoding must match what the frontend builds.

In the code: bastion.websub.topics.* in application.yaml, WebsubTopicResolver, PostFeedFacets on PostChangedEvent, WebsubPublisher.publish(topics).
