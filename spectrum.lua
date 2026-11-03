-- SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
-- SPDX-License-Identifier: GPL-3.0-or-later

-- Turn audio samples into bar heights and use colors from the current video.
local ffi = require("ffi")
local spectrum = {}
local methods = {}
methods.__index = methods
local log10 = math.log(10)

local function number(value, fallback, low, high)
    value = tonumber(value)
    if not value or value ~= value then value = fallback end
    return math.max(low, math.min(high, value))
end

local function seed_from(text)
    local seed = 1
    for i = 1, #(text or "") do seed = (seed * 31 + text:byte(i)) % 2147483647 end
    return math.max(1, seed)
end

function spectrum.new(settings)
    local self = setmetatable({candidates={}, seed=1}, methods)
    self:configure(settings or {})
    return self
end

function methods:configure(config)
    local size = number(config.fft_size, 1024, 256, 4096)
    local n = 256
    while n < size do n = n * 2 end
    local rate = tonumber(config.sample_rate) or 44100
    if rate ~= 11025 and rate ~= 22050 and rate ~= 44100 and rate ~= 48000 then rate = 44100 end
    local count = math.floor(number(config.count, 48, 8, 192))
    self.settings = {
        enabled=config.enabled ~= false, count=count,
        width=number(config.width, 8, 1, 80), gap=number(config.gap, 4, 0, 40),
        fill_width=config.fill_width ~= false, bottom_gap=number(config.bottom_gap, 16, 0, 60),
        height=number(config.height, 140, 20, 2000), min_height=number(config.min_height, 2, 0, 40),
        radius=number(config.radius, 2, 0, 40), opacity=number(config.opacity, .9, 0, 1),
        update_rate=number(config.update_rate, 60, 5, 60), fft_size=n, sample_rate=rate,
        min_frequency=number(config.min_frequency, 40, 10, rate / 2 - 20),
        max_frequency=number(config.max_frequency, 10000, 40, rate / 2),
        min_db=number(config.min_db, -65, -120, -10), max_db=number(config.max_db, -8, -30, 0),
        gain=number(config.gain, 1, .1, 10), attack=number(config.attack, 40, 1, 80),
        release=number(config.release, 18, .2, 40), palette_size=math.floor(number(config.palette_size, 8, 1, 24)),
        color_mode=config.color_mode == "per_song" and "per_song" or "random",
        color_change_speed=number(config.color_change_speed, 10, .1, 144),
        color_source=config.color_source == "thumbnail" and "thumbnail" or "video",
        video_color_fps=math.floor(number(config.video_color_fps, 8, 1, 30)),
    }
    local s = self.settings
    s.max_frequency = math.max(s.min_frequency + 10, s.max_frequency)
    s.max_db = math.max(s.min_db + 1, s.max_db)
    self.real, self.imag = ffi.new('double[?]', n), ffi.new('double[?]', n)
    self.samples, self.window = ffi.new('int16_t[?]', n), ffi.new('double[?]', n)
    self.reverse = ffi.new('int[?]', n)
    self.cosine, self.sine = ffi.new('double[?]', n / 2), ffi.new('double[?]', n / 2)
    self.magnitudes = ffi.new('double[?]', n / 2 + 1)
    self.targets, self.heights = ffi.new('double[?]', count), ffi.new('double[?]', count)
    self.velocities = ffi.new('double[?]', count)
    self.low_bins, self.high_bins = {}, {}
    -- Cache the window, sample order and angles; they stay the same during a song.
    for i = 0, n - 1 do
        local bits, reversed, value = n, 0, i
        while bits > 1 do
            reversed = reversed * 2 + value % 2
            value = math.floor(value / 2)
            bits = bits / 2
        end
        self.reverse[i] = reversed
        self.window[i] = .5 - .5 * math.cos(2 * math.pi * i / (n - 1))
    end
    for i = 0, n / 2 - 1 do
        self.cosine[i] = math.cos(2 * math.pi * i / n)
        self.sine[i] = -math.sin(2 * math.pi * i / n)
    end
    for i = 0, count - 1 do
        local first = s.min_frequency * (s.max_frequency / s.min_frequency) ^ (i / count)
        local last = s.min_frequency * (s.max_frequency / s.min_frequency) ^ ((i + 1) / count)
        local low = math.max(1, math.min(n / 2, math.floor(first * n / rate)))
        self.low_bins[i] = low
        self.high_bins[i] = math.max(low, math.min(n / 2, math.floor(last * n / rate)))
    end
    self.age, self.reason, self.sequence, self.frame_sequence = 100, nil, -1, -1
    self.color_timer = 0
    self:choose_colors()
end

function methods:reset(id)
    self.age, self.reason, self.sequence, self.frame_sequence = 100, nil, -1, -1
    self.color_timer = 0
    for i = 0, self.settings.count - 1 do
        self.targets[i], self.heights[i], self.velocities[i] = 0, 0, 0
    end
    self.candidates, self.seed = {}, seed_from(id)
    self:choose_colors()
end

function methods:transform(samples)
    local n, s = self.settings.fft_size, self.settings
    for i = 0, n - 1 do
        local target = self.reverse[i]
        self.real[target] = samples[i] / 32768 * self.window[i]
        self.imag[target] = 0
    end
    -- Combine pairs of samples, then groups of four, eight, and so on.
    local length = 2
    while length <= n do
        local half, stride = length / 2, n / length
        for first = 0, n - 1, length do
            for j = 0, half - 1 do
                local left, right, angle = first + j, first + j + half, j * stride
                local cosine, sine = self.cosine[angle], self.sine[angle]
                local real = cosine * self.real[right] - sine * self.imag[right]
                local imag = cosine * self.imag[right] + sine * self.real[right]
                self.real[right], self.imag[right] = self.real[left] - real, self.imag[left] - imag
                self.real[left], self.imag[left] = self.real[left] + real, self.imag[left] + imag
            end
        end
        length = length * 2
    end
    for i = 0, n / 2 do
        self.magnitudes[i] = math.sqrt(self.real[i] ^ 2 + self.imag[i] ^ 2) * 4 / n
    end
    for i = 0, s.count - 1 do
        local peak = 0
        for bin = self.low_bins[i], self.high_bins[i] do peak = math.max(peak, self.magnitudes[bin]) end
        local db = 20 * math.log(math.max(1e-9, peak * s.gain)) / log10
        self.targets[i] = math.max(0, math.min(1, (db - s.min_db) / (s.max_db - s.min_db)))
    end
    self.age = 0
end

function methods:feed(reply)
    self.reason = reply.reason
    if not reply.available then
        for i = 0, self.settings.count - 1 do self.targets[i] = 0 end
        return
    end
    if not reply.pcm or reply.sequence == self.sequence then return end
    if reply.fft_size ~= self.settings.fft_size or reply.sample_rate ~= self.settings.sample_rate then return end
    local bytes = love.data.decode('string', 'base64', reply.pcm)
    if #bytes ~= self.settings.fft_size * 2 then return end
    ffi.copy(self.samples, bytes, #bytes)
    self:transform(self.samples)
    self.sequence = reply.sequence
end

function methods:update(dt, playing)
    self.age = self.age + dt
    local s = self.settings
    -- Keep each bar's velocity so new readings do not make its movement jump.
    local step = math.min(dt, .1)
    local rise_speed, fall_speed = s.attack * 2, s.release * 2
    local rise_decay = math.exp(-rise_speed * step)
    local fall_decay = math.exp(-fall_speed * step)
    for i = 0, s.count - 1 do
        local target = playing and self.age < .2 and self.targets[i] or 0
        local height, velocity = self.heights[i], self.velocities[i]
        if not playing and velocity > 0 then velocity = 0 end
        local speed, decay = fall_speed, fall_decay
        if target > height then speed, decay = rise_speed, rise_decay end
        local distance = height - target
        local motion = velocity + speed * distance
        local next_height = target + (distance + motion * step) * decay
        local next_velocity = (velocity - speed * motion * step) * decay
        if next_height < 0 or next_height > 1 then
            next_height = math.max(0, math.min(1, next_height))
            next_velocity = 0
        end
        self.heights[i], self.velocities[i] = next_height, next_velocity
    end
    if playing and self.age < .2 and s.color_mode == "random" and #self.candidates > 0 then
        local interval = 1 / s.color_change_speed
        self.color_timer = self.color_timer + dt
        if self.color_timer + 1e-9 >= interval then
            -- Skip missed changes after a slow frame instead of catching up in a loop.
            self.color_timer = math.max(0, self.color_timer - math.floor((self.color_timer + 1e-9) / interval) * interval)
            self:randomize_colors()
        end
    end
end

function methods:randomize_colors()
    -- Reuse the sampled palette and existing color table. No image work per frame.
    local palette, seed = self.palette, self.seed
    local count = #palette
    for i = 0, self.settings.count - 1 do
        seed = seed * 48271 % 2147483647
        local index = seed % count + 1
        if count > 1 and palette[index] == self.colors[i] then
            index = index % count + 1
        end
        self.colors[i] = palette[index]
    end
    self.seed = seed
end

function methods:choose_colors(keep_colors)
    local palette = {}
    local used = {}
    -- Pick different colors across the image, rather than eight similar shades.
    for _ = 1, math.min(#self.candidates, self.settings.palette_size) do
        local best, best_distance
        for index, color in ipairs(self.candidates) do
            if not used[index] then
                local distance = math.huge
                for _, chosen in ipairs(palette) do
                    local r, g, b = color[1] - chosen[1], color[2] - chosen[2], color[3] - chosen[3]
                    distance = math.min(distance, r * r + g * g + b * b)
                end
                if best == nil or distance > best_distance then
                    best, best_distance = index, distance
                end
            end
        end
        used[best] = true
        palette[#palette + 1] = self.candidates[best]
    end
    if #palette == 0 then palette = {{.38, .72, .69}, {.48, .65, .78}, {.67, .64, .77}} end
    self.palette = palette
    if keep_colors then return end
    -- F5 calls configure(), so give every config reload a fresh shuffle too.
    local random = love and love.math and love.math.random or math.random
    self.seed = (self.seed + random(1, 2147483646)) % 2147483646 + 1
    local seed = self.seed
    local function random_index(limit)
        seed = seed * 48271 % 2147483647
        return seed % limit + 1
    end
    self.colors = {}
    local bag = {}
    for i = 0, self.settings.count - 1 do
        if #bag == 0 then
            for index = 1, #palette do bag[index] = palette[index] end
            for index = #bag, 2, -1 do
                local other = random_index(index)
                bag[index], bag[other] = bag[other], bag[index]
            end
            if #bag > 1 and bag[#bag] == self.colors[i - 1] then
                bag[1], bag[#bag] = bag[#bag], bag[1]
            end
        end
        self.colors[i] = bag[#bag]
        bag[#bag] = nil
    end
end

function methods:set_palette(image, id)
    local w, h = image:getDimensions()
    local colors, seen = {}, {}
    for row = 0, 7 do
        for column = 0, 11 do
            local x = math.min(w - 1, math.floor((column + .5) * w / 12))
            local y = math.min(h - 1, math.floor((row + .5) * h / 8))
            local r, g, b, a = image:getPixel(x, y)
            local bright = .299 * r + .587 * g + .114 * b
            local key = math.floor(r * 12) .. ':' .. math.floor(g * 12) .. ':' .. math.floor(b * 12)
            if a > .5 and bright > .08 and bright < .96 and not seen[key] then
                seen[key] = true
                colors[#colors + 1] = {r, g, b, score=math.max(r, g, b) - math.min(r, g, b) + bright * .4}
            end
        end
    end
    if #colors == 0 then
        local r, g, b = image:getPixel(math.floor(w / 2), math.floor(h / 2))
        colors[1] = {r, g, b, score=0}
    end
    table.sort(colors, function(a, b) return a.score > b.score end)
    self.candidates = colors
    self.seed = seed_from(id)
    self:choose_colors()
end

function methods:set_video_palette(reply)
    if not reply.available or not reply.colors or reply.sequence == self.frame_sequence then return end
    local colors = {}
    for _, color in ipairs(reply.colors) do
        local r, g, b = tonumber(color[1]), tonumber(color[2]), tonumber(color[3])
        if r and g and b and r >= 0 and r <= 1 and g >= 0 and g <= 1 and b >= 0 and b <= 1 then
            local bright = .299 * r + .587 * g + .114 * b
            colors[#colors + 1] = {r, g, b, score=math.max(r, g, b) - math.min(r, g, b) + bright * .4}
        end
    end
    if #colors == 0 then return end
    table.sort(colors, function(a, b) return a.score > b.score end)
    self.frame_sequence = reply.sequence
    local unchanged = #colors == #self.candidates
    if unchanged then
        for i, color in ipairs(colors) do
            local previous = self.candidates[i]
            if color[1] ~= previous[1] or color[2] ~= previous[2] or color[3] ~= previous[3] then
                unchanged = false
                break
            end
        end
    end
    if unchanged then return end
    local keep_colors = #self.candidates > 0 and self.settings.color_mode == "random"
    self.candidates, self.frame_sequence = colors, reply.sequence
    -- New video frames update the palette, but only the timer changes visible colors.
    self:choose_colors(keep_colors)
end

function methods:draw(x, baseline, width, available_height)
    local s = self.settings
    if not s.enabled then return end
    local gap = math.min(s.gap, width / (s.count * 2))
    local bar_width = math.max(.5, math.min(s.width, (width - gap * (s.count - 1)) / s.count))
    local total = bar_width * s.count + gap * (s.count - 1)
    local first = x + math.max(0, (width - total) / 2)
    if s.fill_width then
        gap = math.max(0, (width - bar_width * s.count) / (s.count - 1))
        first = x
    end
    local height = math.max(s.min_height, math.min(s.height, available_height))
    for i = 0, s.count - 1 do
        local color = self.colors[i]
        love.graphics.setColor(color[1], color[2], color[3], s.opacity)
        local value = math.max(s.min_height, self.heights[i] * height)
        love.graphics.rectangle('fill', first + i * (bar_width + gap), baseline - value,
                                bar_width, value, math.min(s.radius, bar_width / 2))
    end
end

return spectrum
