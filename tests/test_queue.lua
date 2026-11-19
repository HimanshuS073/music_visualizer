local Queue = require("music_queue")
local queue = Queue.new()
local a = {video_id="r7qovpFAGrQ"}
local b = {video_id="BBBBBBBBBBB"}
local c = {video_id="YnopHCL1Jk8"}
local d = {video_id="DDDDDDDDDDD"}

queue:start(a)
queue.request = 1
assert(queue:accept(1, {b}, false))
assert(queue:skip_suggestion() == b)
assert(queue:skip_suggestion() == nil)
assert(queue:accept(1, {b, d}, true))
assert(queue:next() == d)
queue.manual = {c}
queue.ended = true
assert(queue:next() == c)
assert(queue:advance(true) == c)
queue:start(c)
queue.request = 2
assert(not queue:accept(1, {b}, true))
assert(queue:accept(2, {d}, true))
assert(queue:next() == d)
assert(queue:advance(true) == nil)
queue.ended = true
assert(queue:advance(false) == nil)
local song, source = queue:advance(true)
assert(song == d and source == c)
queue:start(d, source)
assert(queue.source == c)
queue.request = 3
assert(queue:accept(3, nil, true))
assert(queue.current == d and not queue.waiting)
print("Queue checks passed.")
