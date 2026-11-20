local sent, inbox = {}, {}
local function send(_, command, fields)
    table.insert(sent, {command=command, fields=fields or {}})
    return #sent
end
local function last(command)
    for i = #sent, 1, -1 do
        if sent[i].command == command then return i, sent[i].fields end
    end
    error("No command " .. command)
end
local function noop() end
package.loaded.music_backend = {new=function()
    return {fd=true, send=send, poll=function()
        local replies = inbox
        inbox = {}
        return replies
    end, close=noop}
end}
package.loaded.utf8 = {}
love = {
    graphics={getDimensions=function() return 1080, 680 end, setBackgroundColor=noop,
              setDefaultFilter=noop, newFont=noop, newShader=noop, isActive=function() return false end},
    filesystem={getSource=function() return "." end},
    window={setMode=noop, setTitle=noop, hasMouseFocus=function() return false end,
            hasFocus=function() return true end},
    keyboard={setTextInput=noop, isDown=function() return false end},
}
dofile("main.lua")
love.load()

local A = {video_id="r7qovpFAGrQ", title="Old Town Road", artist="Lil Nas X"}
local B = {video_id="BBBBBBBBBBB", title="Recommendation B", artist="Artist B"}
local C = {video_id="YnopHCL1Jk8", title="Toca Toca", artist="Fly Project"}
local D = {video_id="DDDDDDDDDDD", title="Recommendation D", artist="Artist D"}
local function deliver(id, fields)
    fields.id = id
    if fields.ok == nil then fields.ok = true end
    inbox = {fields}
    love.update(.01)
end
local function select(song)
    open_input_box()
    love.textinput(song.title)
    love.keypressed("return")
    deliver(last("search"), {results={song}})
    love.keypressed("return")
    deliver(last("play"), {})
    return last("recommend")
end
local first, seed = select(A)
assert(seed.video_id == A.video_id and seed.title == A.title and seed.artist == A.artist)
deliver(first, {recommendations={B}, done=false})
love.keypressed("n")
love.keypressed("n")
local second = select(C)
deliver(second, {recommendations={D}, done=true})
deliver(first, {recommendations={B}, done=true})
love.keypressed("n")
local feedback = {}
for _, message in ipairs(sent) do
    if message.command == "recommend_feedback" then table.insert(feedback, message.fields) end
end
assert(#feedback == 3)
assert(feedback[1].action == "skip" and feedback[1].source_id == A.video_id and feedback[1].video_id == B.video_id)
assert(feedback[2].action == "choice" and feedback[2].source_id == A.video_id and feedback[2].video_id == C.video_id)
assert(feedback[3].action == "skip" and feedback[3].source_id == C.video_id and feedback[3].video_id == D.video_id)
local before = last("play")
deliver(select(A), {ok=false, error="mocked network failure"})
local after = last("play")
assert(after > before)
love.update(1)
deliver(last("status"), {song=A, running=true, ready=true, position=32})
assert(last("play") == after)
for _, message in ipairs(sent) do assert(message.command ~= "stop") end
local function end_song(song)
    love.update(1)
    deliver(last("status"), {song=song, running=false, ready=false, ended=true})
end
local autoplay_seed = select(A)
deliver(autoplay_seed, {recommendations={B}, done=true})
open_input_box()
love.textinput(C.title)
love.keypressed("return")
deliver(last("search"), {results={C}})
love.keyboard.isDown = function() return true end
love.keypressed("return")
love.keyboard.isDown = function() return false end
local last_choice = last("recommend_feedback")
assert(sent[last_choice].fields.action == "choice")
assert(last("play") < last_choice)
end_song(A)
local manual_play, manual = last("play")
assert(manual.video_id == C.video_id)
deliver(manual_play, {})
deliver(last("recommend"), {recommendations={D}, done=true})
end_song(C)
local autoplay, song = last("play")
assert(autoplay > manual_play and song.video_id == D.video_id)
assert(last("recommend_feedback") == last_choice)
deliver(autoplay, {})
local ranked = {}
for i = 1, 20 do
    ranked[i] = {video_id=string.format("track%06d", i), title="Track " .. i, artist="Artist"}
end
local recommendation_id, request_fields = last("recommend")
assert(request_fields.fill == true)
deliver(recommendation_id, {recommendations=ranked, done=true})
love.graphics.getWidth = function() return 1080 end
love.graphics.getHeight = function() return 680 end
love.keypressed("g")
for _ = 1, 19 do love.keypressed("down") end
love.keypressed("return")
local _, picked = last("play")
assert(picked.video_id == ranked[20].video_id)
print("Player recommendation wiring passed.")
