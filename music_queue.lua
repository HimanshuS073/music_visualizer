local Queue = {}
Queue.__index = Queue

function Queue.new()
    return setmetatable({manual={}, suggestions={}, recent={}, dismissed={}}, Queue)
end

function Queue:start(song, source)
    self.current = song
    self.source, self.choice_source = source, nil
    self.suggestions, self.dismissed = {}, {}
    self.waiting, self.ended, self.request = false, false, nil
    table.insert(self.recent, song.video_id)
    if #self.recent > 20 then table.remove(self.recent, 1) end
end

function Queue:accept(id, songs, done)
    if id ~= self.request then return false end
    self.waiting = not done
    if songs then
        self.suggestions = {}
        for _, song in ipairs(songs) do
            if not self.dismissed[song.video_id] then
                table.insert(self.suggestions, song)
            end
        end
    end
    return true
end

function Queue:next()
    return self.manual[1] or self.suggestions[1]
end

function Queue:skip_suggestion()
    local song = table.remove(self.suggestions, 1)
    if song then self.dismissed[song.video_id] = true end
    return song
end

function Queue:advance(autoplay)
    if not self.ended then return end
    if #self.manual > 0 then return table.remove(self.manual, 1) end
    if autoplay and #self.suggestions > 0 then
        return table.remove(self.suggestions, 1), self.current
    end
end

return Queue
